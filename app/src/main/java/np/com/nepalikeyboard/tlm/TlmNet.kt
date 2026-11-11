package np.com.nepalikeyboard.tlm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Dependency-free inference core for the TinyLM transformers.
 *
 * It replaces ONNX Runtime (~15 MB of native code per ABI) with ~300 lines of Kotlin that read the packed
 * `*.tlmw` weight files produced by `tools/pack_tlm.py`. Weights are stored int8 on disk and expanded to
 * float once at load (the models are ~2.5 M parameters), activations are float. The stage-1 decoder uses a
 * KV cache so a keystroke costs a handful of single-token steps instead of re-running the whole prefix.
 */
internal class Tensor(val dims: IntArray, val f: FloatArray?, val q: ByteArray?, val rowScale: FloatArray?)

internal class TlmWeights(bytes: ByteArray) {
    private val t = HashMap<String, Tensor>()

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.get() == 'T'.code.toByte() && b.get() == 'L'.code.toByte() && b.get() == 'W'.code.toByte() && b.get() == '1'.code.toByte()) { "bad tlmw magic" }
        val n = b.int
        repeat(n) {
            val nameBytes = ByteArray(b.short.toInt() and 0xFFFF)
            b.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)
            val kind = b.get().toInt()
            val nd = b.get().toInt()
            val dims = IntArray(nd) { b.int }
            val cnt = dims.fold(1) { a, c -> a * c }
            when (kind) {
                0 -> {
                    val f = FloatArray(cnt)
                    b.asFloatBuffer().get(f)
                    b.position(b.position() + 4 * cnt)
                    t[name] = Tensor(dims, f, null, null)
                }
                1 -> {
                    val s = b.float
                    val q = ByteArray(cnt)
                    b.get(q)
                    t[name] = Tensor(dims, FloatArray(cnt) { q[it] * s }, null, null)
                }
                else -> {
                    val sc = FloatArray(dims[0])
                    b.asFloatBuffer().get(sc)
                    b.position(b.position() + 4 * dims[0])
                    val q = ByteArray(cnt)
                    b.get(q)
                    t[name] = Tensor(dims, null, q, sc)
                }
            }
        }
    }

    operator fun get(name: String): Tensor = t[name] ?: error("missing tensor $name")
    fun has(name: String) = t.containsKey(name)
    fun f(name: String): FloatArray = get(name).f!!
    fun lin(prefix: String): Lin {
        val w = get("$prefix.weight")
        return Lin(w.f!!, f("$prefix.bias"), w.dims[0], w.dims[1])
    }
    fun norm(prefix: String) = LNorm(f("$prefix.weight"), f("$prefix.bias"))
}

internal class Lin(private val w: FloatArray, private val b: FloatArray, val nOut: Int, val nIn: Int) {
    /**
     * y = W x + b. Four independent accumulators break the add dependency chain (ART/HotSpot do not vectorise float
     * reductions), which is ~2.5x faster than a single running sum on the same weights.
     */
    fun mv(x: FloatArray, xo: Int, y: FloatArray, yo: Int) {
        var base = 0
        val n8 = nIn and 7.inv()
        for (o in 0 until nOut) {
            var s0 = b[o]
            var s1 = 0f
            var s2 = 0f
            var s3 = 0f
            var i = 0
            while (i < n8) {
                val wi = base + i
                val xi = xo + i
                s0 += w[wi] * x[xi] + w[wi + 4] * x[xi + 4]
                s1 += w[wi + 1] * x[xi + 1] + w[wi + 5] * x[xi + 5]
                s2 += w[wi + 2] * x[xi + 2] + w[wi + 6] * x[xi + 6]
                s3 += w[wi + 3] * x[xi + 3] + w[wi + 7] * x[xi + 7]
                i += 8
            }
            while (i < nIn) { s0 += w[base + i] * x[xo + i]; i++ }
            y[yo + o] = (s0 + s1) + (s2 + s3)
            base += nIn
        }
    }
}

internal class LNorm(private val g: FloatArray, private val b: FloatArray) {
    fun apply(x: FloatArray, xo: Int, y: FloatArray, yo: Int) {
        val n = g.size
        var mu = 0f
        for (i in 0 until n) mu += x[xo + i]
        mu /= n
        var v = 0f
        for (i in 0 until n) { val d = x[xo + i] - mu; v += d * d }
        val inv = 1f / sqrt(v / n + 1e-5f)
        for (i in 0 until n) y[yo + i] = (x[xo + i] - mu) * inv * g[i] + b[i]
    }
}

internal object Nn {
    /** erf, Abramowitz & Stegun 7.1.26 refined (|err| < 1.5e-7) - used only to build the GELU table. */
    private fun erf(x: Float): Float {
        val s = if (x < 0) -1f else 1f
        val a = if (x < 0) -x else x
        val t = 1f / (1f + 0.3275911f * a)
        val y = 1f - (((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t * exp(-a * a)
        return s * y
    }

    private fun geluExact(x: Float): Float = 0.5f * x * (1f + erf(x * 0.70710678f))

    private const val G_RANGE = 8f
    private const val G_RES = 64f
    private val geluTable = FloatArray((2 * G_RANGE * G_RES).toInt() + 2) { geluExact(it / G_RES - G_RANGE) }

    /** GELU by table lookup + linear interpolation (step 1/64, error < 4e-5): no exp() per activation. */
    fun gelu(x: Float): Float {
        if (x >= G_RANGE) return x
        if (x <= -G_RANGE) return 0f
        val p = (x + G_RANGE) * G_RES
        val i = p.toInt()
        val f = p - i
        val t0 = geluTable[i]
        return t0 + (geluTable[i + 1] - t0) * f
    }

    fun geluInPlace(a: FloatArray, n: Int) {
        for (i in 0 until n) a[i] = gelu(a[i])
    }

    fun dot(a: FloatArray, ao: Int, b: FloatArray, bo: Int, n: Int): Float {
        var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
        var i = 0
        val n4 = n and 3.inv()
        while (i < n4) {
            s0 += a[ao + i] * b[bo + i]
            s1 += a[ao + i + 1] * b[bo + i + 1]
            s2 += a[ao + i + 2] * b[bo + i + 2]
            s3 += a[ao + i + 3] * b[bo + i + 3]
            i += 4
        }
        while (i < n) { s0 += a[ao + i] * b[bo + i]; i++ }
        return (s0 + s1) + (s2 + s3)
    }

    /** Single-query multi-head attention over [n] keys; [skip] (optional) marks masked key positions. */
    fun attend(
        q: FloatArray, qo: Int, k: FloatArray, v: FloatArray, n: Int, out: FloatArray, oo: Int, heads: Int, d: Int,
        sc: FloatArray, skip: BooleanArray? = null,
    ) {
        val dk = d / heads
        val scale = 1f / sqrt(dk.toFloat())
        for (h in 0 until heads) {
            val ho = h * dk
            var mx = -3.4e38f
            for (j in 0 until n) {
                if (skip != null && skip[j]) { sc[j] = -3.4e38f; continue }
                val s = dot(q, qo + ho, k, j * d + ho, dk) * scale
                sc[j] = s
                if (s > mx) mx = s
            }
            var z = 0f
            for (j in 0 until n) {
                if (skip != null && skip[j]) { sc[j] = 0f; continue }
                val e = exp(sc[j] - mx); sc[j] = e; z += e
            }
            val inv = 1f / z
            for (i in 0 until dk) out[oo + ho + i] = 0f
            for (j in 0 until n) {
                val p = sc[j]
                if (p == 0f) continue
                val vo = j * d + ho
                for (i in 0 until dk) out[oo + ho + i] += p * v[vo + i]
            }
            for (i in 0 until dk) out[oo + ho + i] *= inv
        }
    }
}

/** Knobs shared with the Android layer (kept out of the pure-Kotlin core so the JVM harness still compiles). */
object TlmTuning {
    /** Called once at the start of every inference helper thread (the app lifts it to display priority). */
    @Volatile var threadInit: Runnable? = null

    /** Upper bound for extra scoring threads besides the caller. 0 disables parallel scoring. */
    @Volatile var maxHelperThreads: Int = 3
}

/** Thrown from inside a long computation when the request was superseded by a newer keystroke. */
class TlmStale : RuntimeException(null, null, false, false)

/** Stage 1: character-level encoder/decoder transformer with cached cross- and self-attention. */
internal class TlmSeq2Seq(private val w: TlmWeights, val d: Int, private val heads: Int, private val nEnc: Int, private val nDec: Int, val nTgt: Int) {
    private class EncL(val n1: LNorm, val q: Lin, val k: Lin, val v: Lin, val o: Lin, val n2: LNorm, val fa: Lin, val fb: Lin)
    private class DecL(
        val n1: LNorm, val sq: Lin, val sk: Lin, val sv: Lin, val so: Lin,
        val n2: LNorm, val cq: Lin, val ck: Lin, val cv: Lin, val co: Lin,
        val n3: LNorm, val fa: Lin, val fb: Lin,
    )

    private val se = w.f("se.weight"); private val sp = w.f("sp.weight")
    private val te = w.f("te.weight"); private val tp = w.f("tp.weight")
    private val ne = w.norm("ne"); private val nd = w.norm("nd"); private val outL = w.lin("out")
    private val enc = List(nEnc) { l -> val p = "enc.$l"; EncL(w.norm("$p.n1"), w.lin("$p.att.q"), w.lin("$p.att.k"), w.lin("$p.att.v"), w.lin("$p.att.o"), w.norm("$p.n2"), w.lin("$p.ff.a"), w.lin("$p.ff.b")) }
    private val dec = List(nDec) { l ->
        val p = "dec.$l"
        DecL(
            w.norm("$p.n1"), w.lin("$p.sa.q"), w.lin("$p.sa.k"), w.lin("$p.sa.v"), w.lin("$p.sa.o"),
            w.norm("$p.n2"), w.lin("$p.ca.q"), w.lin("$p.ca.k"), w.lin("$p.ca.v"), w.lin("$p.ca.o"),
            w.norm("$p.n3"), w.lin("$p.ff.a"), w.lin("$p.ff.b"),
        )
    }
    val maxPos = tp.size / d
    private val ffDim = enc[0].fa.nOut
    private val sqrtD = sqrt(d.toFloat())

    class Enc(val ids: IntArray, val crossK: Array<FloatArray>, val crossV: Array<FloatArray>)

    /** Per-hypothesis decoder state: self-attention KV cache for every layer. */
    class Dec(layers: Int, positions: Int, d: Int) {
        val k = Array(layers) { FloatArray(positions * d) }
        val v = Array(layers) { FloatArray(positions * d) }
        fun copyFrom(o: Dec, upTo: Int, d: Int) {
            for (l in k.indices) {
                System.arraycopy(o.k[l], 0, k[l], 0, upTo * d)
                System.arraycopy(o.v[l], 0, v[l], 0, upTo * d)
            }
        }
    }

    fun newDec() = Dec(nDec, maxPos, d)

    /**
     * Everything one decoding thread needs, allocated once: step temporaries, a decoder KV cache, and the per-position
     * log-prob rows of the prefix being scored. Threads never share a [Scr].
     */
    inner class Scr {
        val x = FloatArray(d); val y = FloatArray(d); val q = FloatArray(d); val a = FloatArray(d); val tmp = FloatArray(d)
        val ff = FloatArray(ffDim)
        val sc = FloatArray(64)
        val dec = newDec()
        val lp = Array(maxPos) { FloatArray(nTgt) }
        val consumed = IntArray(maxPos)
        var consumedLen = 0
    }

    fun newScr() = Scr()

    private val encSc = FloatArray(64)
    private val encFf = FloatArray(ffDim)

    fun encode(src: IntArray): Enc {
        val s = src.size
        val x = FloatArray(s * d)
        for (i in 0 until s) for (j in 0 until d) x[i * d + j] = se[src[i] * d + j] * sqrtD + sp[i * d + j]
        val y = FloatArray(s * d); val q = FloatArray(s * d); val k = FloatArray(s * d); val v = FloatArray(s * d); val a = FloatArray(s * d); val tmp = FloatArray(d)
        for (l in enc) {
            for (i in 0 until s) { l.n1.apply(x, i * d, y, i * d); l.q.mv(y, i * d, q, i * d); l.k.mv(y, i * d, k, i * d); l.v.mv(y, i * d, v, i * d) }
            for (i in 0 until s) {
                Nn.attend(q, i * d, k, v, s, a, i * d, heads, d, encSc)
                l.o.mv(a, i * d, tmp, 0)
                for (j in 0 until d) x[i * d + j] += tmp[j]
            }
            for (i in 0 until s) {
                l.n2.apply(x, i * d, y, i * d); l.fa.mv(y, i * d, encFf, 0)
                Nn.geluInPlace(encFf, ffDim)
                l.fb.mv(encFf, 0, tmp, 0)
                for (j in 0 until d) x[i * d + j] += tmp[j]
            }
        }
        val mem = FloatArray(s * d)
        for (i in 0 until s) ne.apply(x, i * d, mem, i * d)
        val ck = Array(nDec) { FloatArray(s * d) }; val cv = Array(nDec) { FloatArray(s * d) }
        for (l in 0 until nDec) for (i in 0 until s) { dec[l].ck.mv(mem, i * d, ck[l], i * d); dec[l].cv.mv(mem, i * d, cv[l], i * d) }
        return Enc(src, ck, cv)
    }

    /**
     * One decoder step: consume [token] at position [pos] using [st]'s KV cache; writes log-probs over the target vocab
     * into [out].
     */
    fun step(e: Enc, st: Dec, token: Int, pos: Int, r: Scr, out: FloatArray) {
        val x = r.x; val y = r.y; val q = r.q; val a = r.a; val tmp = r.tmp; val ff = r.ff
        for (j in 0 until d) x[j] = te[token * d + j] * sqrtD + tp[pos * d + j]
        val s = e.ids.size
        for (li in 0 until nDec) {
            val l = dec[li]
            l.n1.apply(x, 0, y, 0)
            l.sq.mv(y, 0, q, 0)
            l.sk.mv(y, 0, st.k[li], pos * d)
            l.sv.mv(y, 0, st.v[li], pos * d)
            Nn.attend(q, 0, st.k[li], st.v[li], pos + 1, a, 0, heads, d, r.sc)
            l.so.mv(a, 0, tmp, 0)
            for (j in 0 until d) x[j] += tmp[j]
            l.n2.apply(x, 0, y, 0)
            l.cq.mv(y, 0, q, 0)
            Nn.attend(q, 0, e.crossK[li], e.crossV[li], s, a, 0, heads, d, r.sc)
            l.co.mv(a, 0, tmp, 0)
            for (j in 0 until d) x[j] += tmp[j]
            l.n3.apply(x, 0, y, 0)
            l.fa.mv(y, 0, ff, 0)
            Nn.geluInPlace(ff, ffDim)
            l.fb.mv(ff, 0, tmp, 0)
            for (j in 0 until d) x[j] += tmp[j]
        }
        nd.apply(x, 0, y, 0)
        outL.mv(y, 0, out, 0)
        var mx = out[0]
        for (i in 1 until nTgt) if (out[i] > mx) mx = out[i]
        var z = 0f
        for (i in 0 until nTgt) z += exp(out[i] - mx)
        val lz = mx + ln(z)
        for (i in 0 until nTgt) out[i] -= lz
    }
}

/**
 * [Neural] backend on the pure-Kotlin core.
 *
 * Teacher-forced scoring of the candidate pool is the hot path of every keystroke, so it is split over up to
 * [TlmTuning.maxHelperThreads] + 1 cores (each with its own scratch and KV cache); beam search is allocation free.
 */
class KtNeural(bytes: ByteArray, vocabSrc: List<String>, private val tgt: List<String>, private val maxSrc: Int, d: Int, heads: Int, nEnc: Int, nDec: Int) : Neural {
    private val net = TlmSeq2Seq(TlmWeights(bytes), d, heads, nEnc, nDec, tgt.size)
    private val s2i = vocabSrc.withIndex().associate { it.value to it.index }
    private val t2i = tgt.withIndex().associate { it.value to it.index }
    private val t2iChar = HashMap<Char, Int>().also { m -> tgt.forEachIndexed { i, t -> if (t.length == 1) m[t[0]] = i } }

    override var stale: (() -> Boolean)? = null

    private val helpers: Int = run {
        val cores = Runtime.getRuntime().availableProcessors()
        minOf(TlmTuning.maxHelperThreads, cores - 1).coerceAtLeast(0)
    }
    private val pool: java.util.concurrent.ExecutorService? =
        if (helpers <= 0) null else java.util.concurrent.Executors.newFixedThreadPool(helpers) { r ->
            Thread {
                try { TlmTuning.threadInit?.run() } catch (_: Throwable) {}
                r.run()
            }.also { it.isDaemon = true; it.name = "tlm-score" }
        }
    private val scrs = Array(helpers + 1) { net.newScr() }

    @Synchronized
    override fun encode(word: String, lang: String): Any {
        val ids = ArrayList<Int>()
        ids.add(s2i["<$lang>"]!!)
        for (c in word) s2i[c.toString()]?.let { ids.add(it) }
        return net.encode(ids.take(maxSrc).toIntArray())
    }

    private fun scoreChunk(e: TlmSeq2Seq.Enc, order: IntArray, from: Int, to: Int, seqs: Array<IntArray?>, out: FloatArray, r: TlmSeq2Seq.Scr) {
        r.consumedLen = 0
        for (oi in from until to) {
            val ci = order[oi]
            val t = seqs[ci]!!
            var common = 0
            val cl = r.consumedLen
            while (common < cl && common < t.size - 1 && r.consumed[common] == t[common]) common++
            for (j in common until t.size - 1) {
                net.step(e, r.dec, t[j], j, r, r.lp[j])
                r.consumed[j] = t[j]
            }
            r.consumedLen = t.size - 1
            var s = 0f
            for (j in 0 until t.size - 1) s += r.lp[j][t[j + 1]]
            out[ci] = s
        }
    }

    @Synchronized
    override fun score(enc: Any, cands: List<String>): FloatArray {
        val e = enc as TlmSeq2Seq.Enc
        val n = cands.size
        val out = FloatArray(n) { -1e9f }
        // token sequences [BOS, c1..cn, EOS]
        val seqs = arrayOfNulls<IntArray>(n)
        var ok = 0
        for (ci in 0 until n) {
            val c = cands[ci]
            if (c.length > 32) continue
            val a = IntArray(c.length + 2)
            a[0] = 1
            var good = true
            for (j in c.indices) { val id = t2iChar[c[j]]; if (id == null) { good = false; break }; a[j + 1] = id }
            if (!good) continue
            a[a.size - 1] = 2
            seqs[ci] = a
            ok++
        }
        if (ok == 0) return out
        // sorted so neighbours share prefixes (the decoder KV cache and log-prob rows are reused)
        val order = IntArray(ok)
        run { var k = 0; for (ci in 0 until n) if (seqs[ci] != null) order[k++] = ci }
        val boxed = order.toTypedArray()
        java.util.Arrays.sort(boxed) { a, b ->
            val x = seqs[a]!!; val y = seqs[b]!!
            var i = 0
            while (i < x.size && i < y.size && x[i] == y[i]) i++
            if (i < x.size && i < y.size) x[i] - y[i] else x.size - y.size
        }
        for (i in 0 until ok) order[i] = boxed[i]

        val chunks = if (pool == null || ok < PARALLEL_MIN) 1 else minOf(helpers + 1, ok / MIN_PER_CHUNK).coerceAtLeast(1)
        if (chunks == 1) {
            scoreChunk(e, order, 0, ok, seqs, out, scrs[0])
            return out
        }
        val bounds = IntArray(chunks + 1) { ok * it / chunks }
        val futures = ArrayList<java.util.concurrent.Future<*>>(chunks - 1)
        for (c in 1 until chunks) {
            val r = scrs[c]; val from = bounds[c]; val to = bounds[c + 1]
            futures.add(pool!!.submit { scoreChunk(e, order, from, to, seqs, out, r) })
        }
        var failure: Throwable? = null
        try { scoreChunk(e, order, bounds[0], bounds[1], seqs, out, scrs[0]) } catch (t: Throwable) { failure = t }
        for (f in futures) {
            try { f.get() } catch (t: Throwable) { if (failure == null) failure = t.cause ?: t }
        }
        if (failure != null) throw failure
        return out
    }

    // beam scratch (beam runs under the same lock as score)
    private var beamStatesA: Array<TlmSeq2Seq.Dec>? = null
    private var beamStatesB: Array<TlmSeq2Seq.Dec>? = null

    @Synchronized
    override fun beam(enc: Any, k: Int): List<Pair<String, Float>> {
        val e = enc as TlmSeq2Seq.Enc
        val V = tgt.size
        val r = scrs[0]
        var states = beamStatesA?.takeIf { it.size == k } ?: Array(k) { net.newDec() }.also { beamStatesA = it }
        var next = beamStatesB?.takeIf { it.size == k } ?: Array(k) { net.newDec() }.also { beamStatesB = it }
        val maxLen = 36
        var ys = Array(k) { IntArray(maxLen).also { a -> a[0] = 1 } }
        var ysNext = Array(k) { IntArray(maxLen) }
        var sc = FloatArray(k) { if (it == 0) 0f else -1e9f }
        var fin = BooleanArray(k)
        val capacity = k * V
        val cScore = FloatArray(capacity); val cB = IntArray(capacity); val cV = IntArray(capacity)
        val picked = BooleanArray(capacity)
        val lp = FloatArray(V)
        val topB = IntArray(k); val topV = IntArray(k); val topS = FloatArray(k)
        var len = 1
        for (step in 0 until 34) {
            if (stale?.invoke() == true) throw TlmStale()
            val l = len
            var m = 0
            for (b in 0 until k) {
                if (fin[b]) { cScore[m] = sc[b]; cB[m] = b; cV[m] = 0; m++; continue }
                if (sc[b] < -5e8f) continue
                net.step(e, states[b], ys[b][l - 1], l - 1, r, lp)
                val base = sc[b]
                for (v in 2 until V) { cScore[m] = base + lp[v]; cB[m] = b; cV[m] = v; m++ }
            }
            if (m == 0) break
            java.util.Arrays.fill(picked, 0, m, false)
            val n = minOf(k, m)
            for (t in 0 until n) {
                var best = -1
                for (i in 0 until m) {
                    if (picked[i]) continue
                    if (best < 0 || cScore[i] > cScore[best] ||
                        (cScore[i] == cScore[best] && cB[i] * V + cV[i] < cB[best] * V + cV[best])
                    ) best = i
                }
                picked[best] = true
                topB[t] = cB[best]; topV[t] = cV[best]; topS[t] = cScore[best]
            }
            val oldFin = fin
            val newFin = BooleanArray(k)
            val newSc = FloatArray(k)
            for (i in 0 until k) {
                if (i < n) {
                    next[i].copyFrom(states[topB[i]], l, net.d)
                    System.arraycopy(ys[topB[i]], 0, ysNext[i], 0, l)
                    ysNext[i][l] = topV[i]
                    newFin[i] = oldFin[topB[i]] || topV[i] == 2
                    newSc[i] = topS[i]
                } else {
                    System.arraycopy(ys[0], 0, ysNext[i], 0, l)
                    ysNext[i][l] = 0
                    newFin[i] = true
                    newSc[i] = -1e9f
                }
            }
            val ts = states; states = next; next = ts
            val ty = ys; ys = ysNext; ysNext = ty
            fin = newFin; sc = newSc
            len = l + 1
            var all = true
            for (i in 0 until k) if (!fin[i]) { all = false; break }
            if (all) break
        }
        val out = LinkedHashMap<String, Float>()
        for (b in 0 until k) {
            val sb = StringBuilder()
            for (i in 1 until len) { val t = ys[b][i]; if (t == 0 || t == 2) break; sb.append(tgt[t]) }
            val w = sb.toString()
            if (w.isNotEmpty() && w !in out && sc[b] > -1e8f) out[w] = sc[b]
        }
        return out.map { it.key to it.value }
    }

    private companion object {
        const val PARALLEL_MIN = 10
        const val MIN_PER_CHUNK = 5
    }
}

/** Stage 2: 3-layer bidirectional context scorer over an 11-word window (centre = <mask>). */
class KtCtx(bytes: ByteArray, words: List<String>, private val heads: Int = 4) : CtxScorer {
    override val vocab: Map<String, Int> = words.withIndex().associate { it.value to it.index }
    private val w = TlmWeights(bytes)
    private val emb = w["emb"]
    private val d = emb.dims[1]
    private val pos = w.f("pos")
    private val cbias = w.f("cbias")
    private val nL = (0 until 16).count { w.has("blocks.$it.qkv.weight") }
    private class Blk(val ln1: LNorm, val qkv: Lin, val o: Lin, val ln2: LNorm, val f1: Lin, val f2: Lin)
    private val blocks = List(nL) { Blk(w.norm("blocks.$it.ln1"), w.lin("blocks.$it.qkv"), w.lin("blocks.$it.o"), w.norm("blocks.$it.ln2"), w.lin("blocks.$it.f1"), w.lin("blocks.$it.f2")) }
    private val lnF = w.norm("ln")
    private val proj = w.lin("proj")
    private val W = pos.size / d
    private val centre = W / 2

    // scratch (score is @Synchronized)
    private val x = FloatArray(W * d)
    private val y = FloatArray(W * d)
    private val qkv = FloatArray(W * 3 * d)
    private val k = FloatArray(W * d)
    private val v = FloatArray(W * d)
    private val a = FloatArray(d)
    private val tmp = FloatArray(d)
    private val sc = FloatArray(W + 1)
    private val ff = FloatArray(blocks[0].f1.nOut)
    private val skip = BooleanArray(W)
    private val hn = FloatArray(d)
    private val h = FloatArray(d)
    private val e = FloatArray(d)

    private fun row(id: Int, out: FloatArray, off: Int) {
        val s = emb.rowScale!![id]; val base = id * d; val q = emb.q!!
        for (j in 0 until d) out[off + j] = q[base + j] * s
    }

    /**
     * Padding positions (id 0) are masked as attention keys, so their rows never influence anything: they are not
     * embedded, projected or fed forward at all (the right half of the window is always padding while typing).
     */
    @Synchronized
    override fun score(win: IntArray, cand: IntArray): FloatArray {
        val n = W
        for (i in 0 until n) skip[i] = win[i] == 0
        for (i in 0 until n) {
            if (skip[i]) continue
            row(win[i], x, i * d)
            for (j in 0 until d) x[i * d + j] += pos[i * d + j]
        }
        for ((bi, b) in blocks.withIndex()) {
            val last = bi == nL - 1
            for (i in 0 until n) {
                if (skip[i]) continue
                b.ln1.apply(x, i * d, y, i * d)
                b.qkv.mv(y, i * d, qkv, i * 3 * d)
                System.arraycopy(qkv, i * 3 * d + d, k, i * d, d)
                System.arraycopy(qkv, i * 3 * d + 2 * d, v, i * d, d)
            }
            for (i in 0 until n) {
                if (last && i != centre) continue
                if (skip[i]) continue
                Nn.attend(qkv, i * 3 * d, k, v, n, a, 0, heads, d, sc, skip)
                b.o.mv(a, 0, tmp, 0)
                for (j in 0 until d) x[i * d + j] += tmp[j]
                b.ln2.apply(x, i * d, y, i * d)
                b.f1.mv(y, i * d, ff, 0)
                Nn.geluInPlace(ff, ff.size)
                b.f2.mv(ff, 0, tmp, 0)
                for (j in 0 until d) x[i * d + j] += tmp[j]
            }
        }
        lnF.apply(x, centre * d, hn, 0)
        proj.mv(hn, 0, h, 0)
        return FloatArray(cand.size) { ci ->
            row(cand[ci], e, 0)
            cbias[cand[ci]] + Nn.dot(h, 0, e, 0, d)
        }
    }
}
