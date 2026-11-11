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
    fun mv(x: FloatArray, xo: Int, y: FloatArray, yo: Int) {
        var base = 0
        for (o in 0 until nOut) {
            var s = b[o]
            var i = 0
            // manual 4-way unroll: ART does not auto-vectorise, this is ~1.6x faster
            while (i + 3 < nIn) {
                s += w[base + i] * x[xo + i] + w[base + i + 1] * x[xo + i + 1] + w[base + i + 2] * x[xo + i + 2] + w[base + i + 3] * x[xo + i + 3]
                i += 4
            }
            while (i < nIn) { s += w[base + i] * x[xo + i]; i++ }
            y[yo + o] = s
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
    /** erf, Abramowitz & Stegun 7.1.26 refined (|err| < 1.5e-7) */
    private fun erf(x: Float): Float {
        val s = if (x < 0) -1f else 1f
        val a = if (x < 0) -x else x
        val t = 1f / (1f + 0.3275911f * a)
        val y = 1f - (((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t * exp(-a * a)
        return s * y
    }

    fun gelu(x: Float): Float = 0.5f * x * (1f + erf(x * 0.70710678f))

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
                var s = 0f
                val ko = j * d + ho
                for (i in 0 until dk) s += q[qo + ho + i] * k[ko + i]
                s *= scale
                sc[j] = s
                if (s > mx) mx = s
            }
            var z = 0f
            for (j in 0 until n) {
                if (skip != null && skip[j]) { sc[j] = 0f; continue }
                val e = exp(sc[j] - mx); sc[j] = e; z += e
            }
            val inv = 1f / z
            for (i in 0 until dk) {
                var s = 0f
                for (j in 0 until n) s += sc[j] * v[j * d + ho + i]
                out[oo + ho + i] = s * inv
            }
        }
    }
}

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
    private val maxPos = tp.size / d
    private val ffDim = enc[0].fa.nOut
    private val sqrtD = sqrt(d.toFloat())

    // scratch (all public entry points are @Synchronized on the owning TlmNeural)
    private val sc = FloatArray(64)
    private val ffBuf = FloatArray(ffDim)
    private val logits = FloatArray(nTgt)

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

    fun encode(src: IntArray): Enc {
        val s = src.size
        var x = FloatArray(s * d)
        for (i in 0 until s) for (j in 0 until d) x[i * d + j] = se[src[i] * d + j] * sqrtD + sp[i * d + j]
        val y = FloatArray(s * d); val q = FloatArray(s * d); val k = FloatArray(s * d); val v = FloatArray(s * d); val a = FloatArray(s * d); val tmp = FloatArray(d)
        for (l in enc) {
            for (i in 0 until s) { l.n1.apply(x, i * d, y, i * d); l.q.mv(y, i * d, q, i * d); l.k.mv(y, i * d, k, i * d); l.v.mv(y, i * d, v, i * d) }
            for (i in 0 until s) {
                Nn.attend(q, i * d, k, v, s, a, i * d, heads, d, sc)
                l.o.mv(a, i * d, tmp, 0)
                for (j in 0 until d) x[i * d + j] += tmp[j]
            }
            for (i in 0 until s) {
                l.n2.apply(x, i * d, y, i * d); l.fa.mv(y, i * d, ffBuf, 0)
                for (j in ffBuf.indices) ffBuf[j] = Nn.gelu(ffBuf[j])
                l.fb.mv(ffBuf, 0, tmp, 0)
                for (j in 0 until d) x[i * d + j] += tmp[j]
            }
        }
        val mem = FloatArray(s * d)
        for (i in 0 until s) ne.apply(x, i * d, mem, i * d)
        val ck = Array(nDec) { FloatArray(s * d) }; val cv = Array(nDec) { FloatArray(s * d) }
        for (l in 0 until nDec) for (i in 0 until s) { dec[l].ck.mv(mem, i * d, ck[l], i * d); dec[l].cv.mv(mem, i * d, cv[l], i * d) }
        return Enc(src, ck, cv)
    }

    /** One decoder step: consume [token] at position [pos]; returns log-probs over the target vocab (valid until the next call). */
    fun step(e: Enc, st: Dec, token: Int, pos: Int): FloatArray {
        val x = FloatArray(d); val y = FloatArray(d); val q = FloatArray(d); val a = FloatArray(d); val tmp = FloatArray(d)
        for (j in 0 until d) x[j] = te[token * d + j] * sqrtD + tp[pos * d + j]
        val s = e.ids.size
        for ((li, l) in dec.withIndex()) {
            l.n1.apply(x, 0, y, 0)
            l.sq.mv(y, 0, q, 0)
            l.sk.mv(y, 0, st.k[li], pos * d)
            l.sv.mv(y, 0, st.v[li], pos * d)
            Nn.attend(q, 0, st.k[li], st.v[li], pos + 1, a, 0, heads, d, sc)
            l.so.mv(a, 0, tmp, 0)
            for (j in 0 until d) x[j] += tmp[j]
            l.n2.apply(x, 0, y, 0)
            l.cq.mv(y, 0, q, 0)
            Nn.attend(q, 0, e.crossK[li], e.crossV[li], s, a, 0, heads, d, sc)
            l.co.mv(a, 0, tmp, 0)
            for (j in 0 until d) x[j] += tmp[j]
            l.n3.apply(x, 0, y, 0)
            l.fa.mv(y, 0, ffBuf, 0)
            for (j in ffBuf.indices) ffBuf[j] = Nn.gelu(ffBuf[j])
            l.fb.mv(ffBuf, 0, tmp, 0)
            for (j in 0 until d) x[j] += tmp[j]
        }
        nd.apply(x, 0, y, 0)
        outL.mv(y, 0, logits, 0)
        var mx = logits[0]
        for (i in 1 until nTgt) if (logits[i] > mx) mx = logits[i]
        var z = 0f
        for (i in 0 until nTgt) z += exp(logits[i] - mx)
        val lz = mx + ln(z)
        for (i in 0 until nTgt) logits[i] -= lz
        return logits
    }
}

/** [Neural] backend on the pure-Kotlin core. Thread-safe (coarse lock; calls are a few ms). */
class KtNeural(bytes: ByteArray, vocabSrc: List<String>, private val tgt: List<String>, private val maxSrc: Int, d: Int, heads: Int, nEnc: Int, nDec: Int) : Neural {
    private val net = TlmSeq2Seq(TlmWeights(bytes), d, heads, nEnc, nDec, tgt.size)
    private val s2i = vocabSrc.withIndex().associate { it.value to it.index }
    private val t2i = tgt.withIndex().associate { it.value to it.index }

    @Synchronized
    override fun encode(word: String, lang: String): Any {
        val ids = ArrayList<Int>()
        ids.add(s2i["<$lang>"]!!)
        for (c in word) s2i[c.toString()]?.let { ids.add(it) }
        return net.encode(ids.take(maxSrc).toIntArray())
    }

    @Synchronized
    override fun score(enc: Any, cands: List<String>): FloatArray {
        val e = enc as TlmSeq2Seq.Enc
        val out = FloatArray(cands.size) { -1e9f }
        val ok = cands.indices.filter { i -> cands[i].length <= 32 && cands[i].all { t2i.containsKey(it.toString()) } }
        if (ok.isEmpty()) return out
        // token sequences [BOS, c1..cn, EOS]; sorted so neighbours share prefixes (decoder KV cache is reused)
        val seqs = HashMap<Int, IntArray>()
        for (ci in ok) seqs[ci] = IntArray(cands[ci].length + 2).also { a -> a[0] = 1; cands[ci].forEachIndexed { j, c -> a[j + 1] = t2i[c.toString()]!! }; a[a.size - 1] = 2 }
        val order = ok.sortedWith { a, b ->
            val x = seqs[a]!!; val y = seqs[b]!!
            var i = 0
            while (i < x.size && i < y.size && x[i] == y[i]) i++
            if (i < x.size && i < y.size) x[i] - y[i] else x.size - y.size
        }
        val st = net.newDec()
        val lps = ArrayList<FloatArray>()      // lps[j] = log-probs after consuming inputs 0..j of the current prefix
        var consumed = IntArray(0)
        for (ci in order) {
            val t = seqs[ci]!!
            var common = 0
            while (common < consumed.size && common < t.size - 1 && consumed[common] == t[common]) common++
            while (lps.size > common) lps.removeAt(lps.size - 1)
            for (j in common until t.size - 1) lps.add(net.step(e, st, t[j], j).copyOf())
            consumed = t.copyOf(t.size - 1)
            var s = 0f
            for (j in 0 until t.size - 1) s += lps[j][t[j + 1]]
            out[ci] = s
        }
        return out
    }

    @Synchronized
    override fun beam(enc: Any, k: Int): List<Pair<String, Float>> {
        val e = enc as TlmSeq2Seq.Enc
        val V = tgt.size
        var ys = List(k) { mutableListOf(1) }
        var sc = FloatArray(k) { if (it == 0) 0f else -1e9f }
        var fin = BooleanArray(k)
        var states = Array(k) { net.newDec() }
        for (step in 0 until 34) {
            val l = ys[0].size
            val cand = ArrayList<Triple<Float, Int, Int>>()
            for (b in 0 until k) {
                if (fin[b]) { cand.add(Triple(sc[b], b, 0)); continue }
                if (sc[b] < -5e8f) continue
                val lp = net.step(e, states[b], ys[b][l - 1], l - 1)
                for (v in 2 until V) cand.add(Triple(sc[b] + lp[v], b, v))
            }
            if (cand.isEmpty()) break
            cand.sortWith(compareBy<Triple<Float, Int, Int>>({ -it.first }, { it.second * V + it.third }))
            val top = cand.take(k)
            val newStates = Array(k) { i ->
                val st = net.newDec()
                if (i < top.size) st.copyFrom(states[top[i].second], l, net.d)
                st
            }
            val n = top.size
            ys = List(k) { i -> if (i < n) (ys[top[i].second] + top[i].third).toMutableList() else (ys[0] + 0).toMutableList() }
            val oldFin = fin
            fin = BooleanArray(k) { i -> if (i < n) oldFin[top[i].second] || top[i].third == 2 else true }
            sc = FloatArray(k) { i -> if (i < n) top[i].first else -1e9f }
            states = newStates
            if (fin.all { it }) break
        }
        val out = LinkedHashMap<String, Float>()
        for (b in 0 until k) {
            val sb = StringBuilder()
            for (t in ys[b].drop(1)) { if (t == 0 || t == 2) break; sb.append(tgt[t]) }
            val w = sb.toString()
            if (w.isNotEmpty() && w !in out && sc[b] > -1e8f) out[w] = sc[b]
        }
        return out.map { it.key to it.value }
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

    private fun row(id: Int, out: FloatArray, off: Int) {
        val s = emb.rowScale!![id]; val base = id * d; val q = emb.q!!
        for (j in 0 until d) out[off + j] = q[base + j] * s
    }

    @Synchronized
    override fun score(win: IntArray, cand: IntArray): FloatArray {
        val n = W
        val x = FloatArray(n * d)
        for (i in 0 until n) { row(win[i], x, i * d); for (j in 0 until d) x[i * d + j] += pos[i * d + j] }
        val skip = BooleanArray(n) { win[it] == 0 }
        val y = FloatArray(n * d); val qkv = FloatArray(n * 3 * d); val k = FloatArray(n * d); val v = FloatArray(n * d)
        val a = FloatArray(d); val tmp = FloatArray(d); val sc = FloatArray(n)
        val ff = FloatArray(blocks[0].f1.nOut)
        for ((bi, b) in blocks.withIndex()) {
            val last = bi == nL - 1
            for (i in 0 until n) {
                b.ln1.apply(x, i * d, y, i * d)
                b.qkv.mv(y, i * d, qkv, i * 3 * d)
                System.arraycopy(qkv, i * 3 * d + d, k, i * d, d)
                System.arraycopy(qkv, i * 3 * d + 2 * d, v, i * d, d)
            }
            val rows = if (last) centre..centre else 0 until n
            for (i in rows) {
                val q = FloatArray(d); System.arraycopy(qkv, i * 3 * d, q, 0, d)
                Nn.attend(q, 0, k, v, n, a, 0, heads, d, sc, skip)
                b.o.mv(a, 0, tmp, 0)
                for (j in 0 until d) x[i * d + j] += tmp[j]
                b.ln2.apply(x, i * d, y, i * d)
                b.f1.mv(y, i * d, ff, 0)
                for (j in ff.indices) ff[j] = Nn.gelu(ff[j])
                b.f2.mv(ff, 0, tmp, 0)
                for (j in 0 until d) x[i * d + j] += tmp[j]
            }
        }
        val hn = FloatArray(d); val h = FloatArray(d)
        lnF.apply(x, centre * d, hn, 0)
        proj.mv(hn, 0, h, 0)
        val e = FloatArray(d)
        return FloatArray(cand.size) { ci ->
            row(cand[ci], e, 0)
            var s = cbias[cand[ci]]
            for (j in 0 until d) s += h[j] * e[j]
            s
        }
    }
}
