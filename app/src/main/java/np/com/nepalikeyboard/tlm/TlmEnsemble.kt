// Ensemble + compact LM of the transliterationlm web engine (docs/tlm.js `Transliterator._score/_beam`, `CompactLM`).
package np.com.nepalikeyboard.tlm

/** Constants of the web engine's `ENS` object (docs/tlm.js, session 41 Dual-Core weighting). */
object Ens {
    const val CAP: Double = 0.45
    const val CAP_CONF: Double = -1.2
    const val CAP_COMMON_F: Int = 15
    const val SW_BM: Double = -0.25
    const val SW_DM: Double = 0.45
}

/**
 * Mean / Dual-Core teacher-forced log-probability over several seq2seq members, exactly like the web app:
 * members whose source vocabulary has no capitals are the "specialists" (a4kd), the rest the "base" models (main + m2).
 */
class EnsembleNeural(private val members: List<KtNeural>) : Neural {
    private val baseIdx = members.indices.filter { !members[it].lower }
    private val specIdx = members.indices.filter { members[it].lower }
    private val dual = baseIdx.isNotEmpty() && specIdx.isNotEmpty()

    class Pack(val word: String, val encs: Array<Any>, val cap: Double?, val swc: Boolean)

    override var stale: (() -> Boolean)? = null
        set(value) {
            field = value
            for (m in members) m.stale = value
        }

    private fun checkStale() {
        if (stale?.invoke() == true) throw TlmStale()
    }

    override fun encode(word: String, lang: String): Any = encodeCap(word, lang, null, false)

    override fun encodeCap(word: String, lang: String, cap: Double?, swc: Boolean): Any {
        val encs = Array<Any>(members.size) { i ->
            if (i > 0) checkStale()
            members[i].encode(word, lang)
        }
        return Pack(word, encs, cap, swc)
    }

    override fun score(enc: Any, cands: List<String>): FloatArray {
        val pk = enc as Pack
        val n = members.size
        if (n == 1) return members[0].score(pk.encs[0], cands)
        val outs = Array(n) { i ->
            if (i > 0) checkStale()
            members[i].score(pk.encs[i], cands)
        }
        val m = cands.size
        val res = FloatArray(m) { -1e9f }
        if (!dual) {
            for (j in 0 until m) {
                var s = 0.0
                var bad = false
                for (o in outs) {
                    if (o[j] <= -1e8f) { bad = true; break }
                    s += o[j]
                }
                res[j] = if (bad) -1e9f else (s / n).toFloat()
            }
            return res
        }
        val nb = baseIdx.size
        val ns = specIdx.size
        val aa = DoubleArray(m)
        val bb = DoubleArray(m)
        val valid = BooleanArray(m)
        var mxA = -1e9
        var mxB = -1e9
        val cap = pk.cap
        for (j in 0 until m) {
            var bad = false
            for (o in outs) if (o[j] <= -1e8f) { bad = true; break }
            if (bad) continue
            var a = 0.0
            var b = 0.0
            for (q in baseIdx) a += outs[q][j]
            a /= nb
            for (q in specIdx) b += outs[q][j]
            b /= ns
            // s37/s38 capped veto: the specialist may only pull a base score down to (b - cap); cap is null for common words
            if (cap != null && b >= Ens.CAP_CONF) a = maxOf(a, b - cap)
            if (a > mxA) mxA = a
            if (b > mxB) mxB = b
            aa[j] = a; bb[j] = b; valid[j] = true
        }
        val bm = if (pk.swc) Ens.SW_BM else -0.42
        val dm = if (pk.swc) Ens.SW_DM else 0.32
        val w = if ((mxA < -0.95 && mxB >= -0.85 && mxB > mxA + 0.55) || (mxB >= bm && mxB > mxA + dm)) 0.85 else if (mxA < -1.25) 0.20 else 0.15
        for (j in 0 until m) if (valid[j]) res[j] = ((1 - w) * aa[j] + w * bb[j]).toFloat()
        return res
    }

    /** Union of every member's beam, re-scored by the ensemble. */
    override fun beam(enc: Any, k: Int): List<Pair<String, Float>> {
        val pk = enc as Pack
        val n = members.size
        if (n == 1) return members[0].beam(pk.encs[0], k)
        val names = LinkedHashSet<String>()
        for (i in 0 until n) {
            checkStale()
            for ((w, _) in members[i].beam(pk.encs[i], k)) names.add(w)
        }
        if (names.isEmpty()) return emptyList()
        val list = names.toList()
        val sc = score(pk, list)
        return list.indices.map { list[it] to sc[it] }.sortedByDescending { it.second }.take(k).filter { it.second > -1e8f }
    }
}

/** s32 compact skeleton-keyed Kneser-Ney stem-bigram LM (`subword_lm.tsv`); mirrors model/hybrid.py CompactLM / docs/tlm.js. */
class CompactLM(text: String) {
    private val u = HashMap<String, DoubleArray>(40000)
    private val b = HashMap<String, Double>(100000)
    private val suf = ArrayList<Pair<String, Double>>()
    private val skc = HashMap<String, String>()
    private val lpc = HashMap<String, Double>()
    private var n = 1.0
    private var nb = 1.0
    private var d = 0.75

    init {
        var sec = ""
        var start = 0
        val len = text.length
        while (start < len) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = len
            if (end > start) {
                val ln = text.substring(start, end)
                when {
                    ln.startsWith("#H") -> { val p = ln.split('\t'); n = p[1].toDouble(); nb = p[2].toDouble(); d = p[3].toDouble() }
                    ln.startsWith("#S") -> { val p = ln.split('\t'); suf.add(p[1] to p[2].toInt() / 100.0) }
                    ln == "#U" || ln == "#B" -> sec = ln
                    else -> {
                        val p = ln.split('\t')
                        if (sec == "#U") u[p[0]] = doubleArrayOf(p[1].toDouble(), p[2].toDouble(), p[3].toDouble())
                        else if (p.size >= 3) b[p[0] + "\t" + p[1]] = p[2].toDouble()
                    }
                }
            }
            start = end + 1
        }
        suf.sortByDescending { it.first.length }
    }

    private val dentalNasal = Regex("ं(?=[कखगघचछजझटठडढतथदधपफबभ])")

    fun skel(w: String): String {
        skc[w]?.let { return it }
        if (skc.size > 100000) skc.clear()
        var s = java.text.Normalizer.normalize(w, java.text.Normalizer.Form.NFC).replace("\u200d", "").replace("\u200c", "")
        s = s.replace("ी", "ि").replace("ू", "ु").replace("ई", "इ").replace("ऊ", "उ").replace("ँ", "ं").replace("श", "स").replace("ष", "स")
        s = s.replace("ङ्", "ं").replace("ञ्", "ं").replace("ण", "न").replace("व", "ब")
        s = dentalNasal.replace(s, "न")
        s = s.replace("ृ", "रि").trimEnd('्')
        skc[w] = s
        return s
    }

    private fun pk(s: String): Double {
        val x = u[s] ?: return 1e-7
        return Math.max(x[2], 0.5) / nb
    }

    private fun kn(ps: String, s: String): Pair<Double, Double> {
        val c1: Double
        val nf: Double
        if (ps == "^") {
            c1 = n / 12.0; nf = nb / 40.0
        } else {
            val pu = u[ps] ?: return Pair(Math.log(Math.max(pk(s), 1e-8)), 0.0)
            c1 = pu[0]; nf = pu[1]
        }
        val c = b[ps + "\t" + s] ?: 0.0
        return Pair(Math.log(Math.max((Math.max(c - d, 0.0) + d * nf * pk(s)) / Math.max(c1, 1.0), 1e-8)), c)
    }

    fun lp(prev: String?, cand: String, lam: Double = 0.85): Double {
        val key = (prev ?: "^") + "\u0001" + cand + "\u0001" + lam
        lpc[key]?.let { return it }
        val ps = if (prev == null) "^" else skel(prev)
        val s = skel(cand)
        val first = kn(ps, s)
        var l = first.first
        if (first.second <= 0.0) {
            for ((w, slp) in suf) {
                if (cand.length >= w.length + 2 && cand.endsWith(w)) {
                    val st = skel(cand.dropLast(w.length))
                    val su = u[st]
                    if (su != null && su[0] > 20.0) {
                        val wc = u[s]?.get(0) ?: 0.0
                        val second = kn(ps, st)
                        if (second.second > 0.0 || wc < 5.0) l = Math.max(l, second.first + slp - lam)
                        break
                    }
                }
            }
        }
        if (lpc.size > 400000) lpc.clear()
        lpc[key] = l
        return l
    }
}
