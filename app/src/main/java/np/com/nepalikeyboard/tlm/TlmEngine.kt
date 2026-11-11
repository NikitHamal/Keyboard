// Port of transliterationlm hybrid engine (model/hybrid.py, web/tlm.js) - synced from NikitHamal/transliterationlm @ 8721d51. See tools/sync_tlm.sh.
package np.com.nepalikeyboard.tlm

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/** Neural layer abstraction so the engine logic is testable without ONNX. */
interface Neural {
    fun encode(word: String, lang: String): Any
    /** teacher-forced log P(candidate | roman); -1e9 if not representable */
    fun score(enc: Any, cands: List<String>): FloatArray
    fun beam(enc: Any, k: Int): List<Pair<String, Float>>

    /** When set and it returns true, long computations abort with [TlmStale] (a newer keystroke superseded the request). */
    var stale: (() -> Boolean)?
        get() = null
        set(_) {}
}

/** Stage-2 context scorer: logits for candidate ids at the masked centre of an 11-word window */
interface CtxScorer {
    val vocab: Map<String, Int>
    fun score(win: IntArray, cand: IntArray): FloatArray
}

object Keys {
    private val DC = mapOf('क' to "k", 'ख' to "K", 'ग' to "g", 'घ' to "G", 'ङ' to "n", 'च' to "C", 'छ' to "C", 'ज' to "j", 'झ' to "Z", 'ञ' to "n",
        'ट' to "t", 'ठ' to "T", 'ड' to "d", 'ढ' to "D", 'ण' to "n", 'त' to "t", 'थ' to "T", 'द' to "d", 'ध' to "D", 'न' to "n", 'प' to "p", 'फ' to "P",
        'ब' to "b", 'भ' to "B", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "b", 'श' to "s", 'ष' to "s", 'स' to "s", 'ह' to "h", 'ळ' to "l")
    private val DV = mapOf('अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o")
    private const val LAB = "पफबभम"
    private val NUK = listOf("क़" to "क", "ख़" to "ख", "ग़" to "ग", "ज़" to "ज", "ड़" to "ड", "ढ़" to "ढ", "फ़" to "फ")
    private val RCL = listOf("kshy" to "C", "ksh" to "C", "chh" to "C", "ch" to "C", "gny" to "J", "gy" to "J", "jny" to "J", "jn" to "J", "sh" to "s", "kh" to "K",
        "gh" to "G", "th" to "T", "dh" to "D", "ph" to "P", "bh" to "B", "jh" to "Z", "ee" to "i", "ii" to "i", "oo" to "u", "uu" to "u", "ks" to "C")
    private val RSG = mapOf('x' to 'C', 'f' to 'P', 'w' to 'b', 'v' to 'b', 'z' to 'j', 'q' to 'k', 'c' to 'C')
    private fun nfc(s: String): String =
        if (java.text.Normalizer.isNormalized(s, java.text.Normalizer.Form.NFC)) s else java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)

    /** Drops every 'a' after the first char and collapses runs of identical chars (same as the old `(.)\\1+` regex pass). */
    private fun finish(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        sb.append(s[0])
        for (i in 1 until s.length) {
            val c = s[i]
            if (c == 'a') continue
            if (sb[sb.length - 1] != c) sb.append(c)
        }
        return sb.toString()
    }

    private fun hasNukta(w: String): Boolean {
        for (ch in w) if (ch == '\u093C' || ch in '\u0958'..'\u095F') return true
        return false
    }

    fun dkey(w0: String): String {
        var w = nfc(w0)
        if (w.indexOf('्') >= 0) w = w.replace("क्ष", "C").replace("ज्ञ", "J")
        if (hasNukta(w)) for ((a, b) in NUK) w = w.replace(a, b)
        val sb = StringBuilder(w.length + 2)
        for (i in w.indices) {
            val ch = w[i]
            val dc = DC[ch]
            if (dc != null) { sb.append(dc); continue }
            val dv = DV[ch]
            when {
                dv != null -> sb.append(dv)
                ch == 'ं' || ch == 'ँ' -> sb.append(if (i + 1 < w.length && LAB.indexOf(w[i + 1]) >= 0) 'm' else 'n')
                ch == 'ः' -> sb.append('h')
                ch == 'C' || ch == 'J' -> sb.append(ch)
            }
        }
        return finish(sb.toString())
    }

    fun rkey(r: String): String {
        var s = r.lowercase().filter { it in 'a'..'z' }
        for ((a, b) in RCL) s = s.replace(a, b)
        s = s.map { RSG[it] ?: it }.joinToString("")
        return finish(s)
    }

    fun nokey(k: String) = k.replace("n", "")

    private val HD_NASAL_RE = Regex("[ङञणन]्")

    fun hdkey(w0: String): String {
        val w = nfc(w0)
        val sb = StringBuilder(w.length)
        for (ch in w) {
            sb.append(
                when (ch) {
                    'ी' -> 'ि'; 'ू' -> 'ु'; 'ई' -> 'इ'; 'ऊ' -> 'उ'; 'ँ' -> 'ं'; 'श', 'ष' -> 'स'
                    else -> ch
                },
            )
        }
        val m = sb.toString()
        return if (m.indexOf('्') >= 0) HD_NASAL_RE.replace(m, "ं") else m
    }
}

class TlmLexicon(text: String) {
    val freq = HashMap<String, Int>(); val full = HashMap<String, MutableList<String>>(); val nn = HashMap<String, MutableList<String>>(); val snap = HashMap<String, String>()
    init {
        val rows = ArrayList<Pair<String, Int>>()
        for (l in text.lineSequence()) { val p = l.split('\t'); if (p.size < 2) continue; val c = p[1].toIntOrNull() ?: continue; freq[p[0]] = c; rows.add(p[0] to c) }
        rows.sortWith(compareBy<Pair<String, Int>>({ -it.second }, { it.first }))
        for ((w, _) in rows) {
            val k = Keys.dkey(w); full.getOrPut(k) { ArrayList() }.add(w); nn.getOrPut(Keys.nokey(k)) { ArrayList() }.add(w)
            snap.putIfAbsent(Keys.hdkey(w), w)
        }
    }

    /** sorted phonetic skeleton keys for prefix completion */
    val sortedKeys: Array<String> by lazy { full.keys.toTypedArray().also { it.sort() } }

    /** Most frequent words whose skeleton key starts with [key] (excluding exact-key words), best first. */
    fun completions(key: String, n: Int): List<String> {
        val ks = sortedKeys
        var lo = 0; var hi = ks.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (ks[mid] < key) lo = mid + 1 else hi = mid }
        val out = ArrayList<Pair<String, Int>>(); var scanned = 0
        while (lo < ks.size && ks[lo].startsWith(key) && scanned < 700) {
            if (ks[lo] != key) for (w in full[ks[lo]]!!.take(2)) out.add(w to (freq[w] ?: 0))
            lo++; scanned++
        }
        out.sortByDescending { it.second }
        return out.take(n).map { it.first }
    }
}

data class Params(val lam: Double = 0.5, val mu: Double = 2.0, val direct: Double = 8.0, val directStep: Double = 3.0, val snapRatio: Double = 0.1,
                  val comp2Pen: Double = 2.5, val compPen: Double = 3.0, val nnPen: Double = 1.0, val nFull: Int = 16, val nNn: Int = 6, val nStem: Int = 4,
                  val k: Int = 5, val snapMin: Int = 30, val beamThr: Double = -1.5,
                  val ctxBeta: Double = 1.0, val ctxMargin: Double = 8.0, val ctxUnk: Double = 2.0,
                  val oov: Double = 0.0, val oovConf: Double = 0.0, val confLp: Double = -0.3, val fmin: Int = 0,
                  val gateK: Double = 0.0, val gateD: Double = 1.0, val gateHm: Double = 1.0, val genMax: Int = 0, val genPen: Double = 0.0)

/** Build [Params] from config.json -> "hybrid" (same keys as model/hybrid.py DEFAULT overrides; session 5 adds gen_max / gen_pen). */
fun paramsFromConfig(h: org.json.JSONObject): Params {
    val d = Params()
    return Params(
        lam = h.optDouble("lam", d.lam), mu = h.optDouble("mu", d.mu), direct = h.optDouble("direct", d.direct), directStep = h.optDouble("direct_step", d.directStep),
        snapRatio = h.optDouble("snap_ratio", d.snapRatio), comp2Pen = h.optDouble("comp2_pen", d.comp2Pen), compPen = h.optDouble("comp_pen", d.compPen),
        nnPen = h.optDouble("nn_pen", d.nnPen), nFull = h.optInt("n_full", d.nFull), nNn = h.optInt("n_nn", d.nNn), nStem = h.optInt("n_stem", d.nStem),
        k = h.optInt("K", d.k), snapMin = h.optInt("snap_min", d.snapMin), beamThr = h.optDouble("beam_thr", d.beamThr),
        ctxBeta = h.optDouble("ctx_beta", d.ctxBeta), ctxMargin = h.optDouble("ctx_margin", d.ctxMargin), ctxUnk = h.optDouble("ctx_unk", d.ctxUnk),
        oov = h.optDouble("oov", d.oov), oovConf = h.optDouble("oov_conf", d.oovConf), confLp = h.optDouble("conf_lp", d.confLp), fmin = h.optInt("fmin", d.fmin),
        gateK = h.optDouble("gate_k", d.gateK), gateD = h.optDouble("gate_d", d.gateD), gateHm = h.optDouble("gate_hm", d.gateHm),
        genMax = h.optInt("gen_max", d.genMax), genPen = h.optDouble("gen_pen", d.genPen),
    )
}

data class Seg(val src: String, var out: String, val word: Boolean, var alts: List<String> = emptyList(), var low: Boolean = false)

private val NASAL_RE = Regex("an[sjd]")
private val NASAL_CLUSTER_RE = Regex("m[kgcjtdnpbsh]")
private val LONG_VOWEL_HINTS = listOf("ii", "ee", "oo", "uu", "ng")
private val HRU_RE = Regex("हरु(लाई|मा|को|का|की|ले|बाट|सँग|सित|देखि|सम्म|प्रति)?$")
private val HRU_ENDS = listOf("hru", "hrule", "hrulai", "hruko", "hruma", "hrubata")

class HybridEngine(
    private val neural: Neural, private val lex: TlmLexicon, private val direct: Map<String, List<String>>, keep: Set<String>,
    private val suffixes: List<Pair<String, String>>, private val join: Set<String>, private val p: Params = Params(),
    private val ctx: CtxScorer? = null
) {
    private val keep = keep
    private val cache = object : LinkedHashMap<String, List<Triple<String, Double, String>>>(256, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Triple<String, Double, String>>>) = size > 4000 }
    private val shallowKeys = HashSet<String>()
    private val deva = Regex("[\\u0900-\\u097F]"); private val devl = Regex("[\\u0900-\\u0963\\u0971-\\u097F]"); private val ws = Regex("^\\s+$")
    private val whoNext = setOf("हो", "हुन्", "हुनुहुन्छ", "होइन", "छ", "छन्", "हुन्छ")
    private val pronoun = setOf("तपाईं", "तिमी", "ऊ", "यो", "त्यो", "उनी", "को")
    private val subj1p = setOf("हामी", "हामीले"); private val subj1s = setOf("म", "मैले"); private val subj2 = setOf("तिमी", "तिमीले")
    private val reset = setOf("ऊ", "उनी", "उसले", "उनले", "तिनीहरू", "उनीहरू", "यो", "त्यो", "तपाईं", "तपाईंले")
    private val verbish = listOf("छु", "छ", "छौं", "छौ", "छन्", "यो", "एँ", "ए", "ये", "यौ", "यौं", "एको", "थियो", "हुन्छ", "ेर", "दै", "ने", "ला", "ली", "इन्", "इयो")
    private val particles = setOf("र", "तर", "कि", "अनि", "भने", "जब", "अब", "त", "नि", "पनि", "के", "कसरी", "किन", "भोलि", "आज", "हिजो", "अहिले", "फेरि")
    private val kiVerb = listOf("यो", "छ", "छन्", "हो", "भो", "थियो", "छु", "छौ", "छौं", "एँ", "ला", "हुन्छ", "यौ", "यौं", "छिन्")
    private val hon = setOf("हुन्छ", "भयो", "भएको", "हुनुभयो", "हुनुहोस्", "हुनुहुन्छ", "भएन", "हुनेछ", "हुँदैन", "हुनुहुन्न", "पर्छ", "पर्दैन", "पर्‍यो", "पर्ने", "पर्नेछ")
    private val punct = setOf("।", "?", "!", ",", ";")
    private val tokenRe = Regex("https?://\\S+|www\\.\\S+|[A-Za-z][A-Za-z0-9_']*|\\d+(?:[.,]\\d+)*|\\s+|[\\s\\S]")

    // ---- layer 1
    private fun lexCands(roman: String): LinkedHashMap<String, Pair<String, Int>> {
        val out = LinkedHashMap<String, Pair<String, Int>>(); val k = Keys.rkey(roman)
        for (w in (lex.full[k] ?: emptyList<String>()).take(p.nFull)) out[w] = "lex" to lex.freq[w]!!
        for (w in (lex.nn[Keys.nokey(k)] ?: emptyList<String>()).take(p.nNn)) if (w !in out) out[w] = "lex_nn" to lex.freq[w]!!
        val rl = roman.lowercase().filter { it in 'a'..'z' }
        for ((sr, sd) in suffixes) {
            if (rl.endsWith(sr) && rl.length - sr.length >= 2) {
                val sk = Keys.rkey(rl.dropLast(sr.length)); if (sk.isEmpty()) continue
                val stems = (lex.full[sk] ?: emptyList<String>()).take(p.nStem).toMutableList()
                for (w in (lex.nn[Keys.nokey(sk)] ?: emptyList<String>()).take(2)) if (w !in stems) stems.add(w)
                for (st in stems) {
                    if (st in join) continue
                    val w = st + sd
                    if (w !in out) out[w] = "comp" to (if (lex.freq.containsKey(w)) max(lex.freq[w]!!, lex.freq[st]!!) else lex.freq[st]!!)
                }
            }
        }
        return out
    }

    private fun comp2Cands(roman: String): LinkedHashMap<String, Pair<String, Int>> {
        val rl = roman.lowercase().filter { it in 'a'..'z' }; val out = LinkedHashMap<String, Pair<String, Int>>()
        if (rl.length < 9) return out
        for (i in 3 until rl.length - 2) for (x in (lex.full[Keys.rkey(rl.substring(0, i))] ?: emptyList<String>()).take(2)) for (y in (lex.full[Keys.rkey(rl.substring(i))] ?: emptyList<String>()).take(2))
            if (x.length >= 2 && y.length >= 2 && (x + y) !in out) out[x + y] = "comp2" to min(lex.freq[x]!!, lex.freq[y]!!)
        return out
    }

    private fun canon(n0: String, kind: String, w: String = "", lp: Double = -99.0): String {
        if (kind == "direct") return if (n0.contains("काठमाडौँ")) n0.replace("काठमाडौँ", "काठमाडौं") else n0
        if (n0 == "गरो") return "गाह्रो"
        val wl = w.lowercase()
        if (wl == "sorry" && n0 == "सोरी") return "सरी"
        var n = if (n0.endsWith("उन्छु")) n0.dropLast(4) + "उँछु" else n0
        if (n.startsWith("तपाईँ")) return "तपाईं" + n.substring(5)
        if (n.startsWith("तपाई") && !n.startsWith("तपाईं") && !n.startsWith("तपाईँ")) return "तपाईं" + n.substring(4)
        if (n.contains("काठमाडौँ")) n = n.replace("काठमाडौँ", "काठमाडौं")
        if (n.endsWith("ौँ")) return n.dropLast(1) + "ं"
        if (n.endsWith("द्") && (lex.freq[n.dropLast(1)] ?: 0) >= 2 * (lex.freq[n] ?: 0) && (lex.freq[n.dropLast(1)] ?: 0) >= 200) n = n.dropLast(1)
        if (HRU_ENDS.any { wl.endsWith(it) } && n.length >= 5 && n.contains("हरु")) {
            n = HRU_RE.replace(n, "हरू\$1")
        }
        if ((LONG_VOWEL_HINTS.any { wl.contains(it) } || NASAL_CLUSTER_RE.containsMatchIn(wl)) && lp > -1.8) return n
        if (wl.contains("sh") && (n.contains("श") || n.contains("ष")) && lp > -1.2) return n
        val alt = lex.snap[Keys.hdkey(n)]
        return if (alt != null && alt != n && lex.freq.containsKey(alt) && lex.freq[alt]!! >= p.snapMin && (lex.freq[n] ?: 0) < p.snapRatio * lex.freq[alt]!!) alt else n
    }

    private fun b(f: Int) = if (f >= p.fmin) p.lam * ln1p(f.toDouble()) + p.mu - (if (f <= p.genMax) p.genPen else 0.0) else p.oov
    /** neural-agreement gate (mirrors model/hybrid.py Hybrid.hand): lexicon/morphology candidates far below the model's best log-prob get no free frequency boost */
    private fun gate(n: String, kind: String, lp: Double, mx: Double, bk: String?, rkW: String? = null, hasStrongLex: Boolean = false): Double {
        var g = 0.0
        if (p.gateK != 0.0 && (kind == "lex" || kind == "lex_nn" || kind == "comp" || kind == "comp2")) {
            g = p.gateK * maxOf(0.0, (mx - lp) - p.gateD)
            if (bk != null && Keys.hdkey(n) == bk) g *= p.gateHm
        }
        if (kind == "beam" && rkW != null && lex.freq.containsKey(n) && !(hasStrongLex && Keys.dkey(n) != rkW) && Keys.dkey(n) != rkW) {
            g += 1.2 * maxOf(0.0, (mx - lp) - 2.4)
        }
        return g
    }

    private fun prior(w: String, kind: String, f: Int, lp: Double = 0.0, rkW: String? = null, hasStrongLex: Boolean = false): Double = when (kind) {
        "beam" -> if (lex.freq.containsKey(w) && !(hasStrongLex && rkW != null && Keys.dkey(w) != rkW)) b(lex.freq[w]!!) else p.oov + (if (lp >= p.confLp) p.oovConf else 0.0)
        "direct" -> p.direct - p.directStep * f
        "comp2" -> b(f) - p.comp2Pen
        "lex" -> b(f)
        "lex_nn" -> b(f) - p.nnPen
        "comp" -> if (lex.freq.containsKey(w)) b(lex.freq[w]!!) else b(f) - p.compPen
        else -> 0.0
    }

    /** ranked candidates (word, score, kind), best first, for ONE roman word (Nepali) */
    @Synchronized
    fun rankWord(roman: String, topn: Int = 5, lang: String = "ne", deep: Boolean = true): List<Triple<String, Double, String>> {
        val w = roman.lowercase(); val ck = "$lang|$w" + (if (deep) "" else "|s")
        if (shallowKeys.size > 4000) shallowKeys.clear()
        if (deep) {
            // A shallow answer that did not need the beam search IS the deep answer.
            val sk = "$lang|$w|s"
            val sres = cache[sk]
            if (sres != null && !shallowKeys.contains(sk)) return sres.take(topn)
        } else {
            val dres = cache["$lang|$w"]
            if (dres != null) return dres.take(topn)
        }
        val res = cache.getOrPut(ck) {
            val e = neural.encode(w, lang)
            class E(var kind: String, var f: Int, var lp: Double?)
            val pool = LinkedHashMap<String, E>()
            if (lang == "ne") {
                for ((n, kf) in lexCands(w)) pool[n] = E(kf.first, kf.second, null)
                (direct[w] ?: emptyList()).forEachIndexed { pos, n -> val x = pool[n]; if (x != null) { x.kind = "direct"; x.f = pos } else pool[n] = E("direct", pos, null) }
                val names = pool.keys.toList()
                if (names.isNotEmpty()) neural.score(e, names).forEachIndexed { i, s -> pool[names[i]]!!.lp = s.toDouble() }
            }
            val mx = pool.values.maxOfOrNull { it.lp ?: -1e9 } ?: -1e9
            if (!deep && lang == "ne" && pool.isNotEmpty() && mx < p.beamThr) shallowKeys.add(ck)
            if (lang != "ne" || pool.isEmpty() || (deep && mx < p.beamThr)) {
                if (lang == "ne") {
                    val c2 = comp2Cands(w).filter { it.key !in pool }
                    for ((n, kf) in c2) pool[n] = E(kf.first, kf.second, null)
                    if (c2.isNotEmpty()) { val names = c2.keys.toList(); neural.score(e, names).forEachIndexed { i, s -> pool[names[i]]!!.lp = s.toDouble() } }
                }
                for ((n, lp) in neural.beam(e, p.k)) { val x = pool[n]; if (x != null) x.lp = lp.toDouble() else pool[n] = E("beam", 0, lp.toDouble()) }
            }
            val best = LinkedHashMap<String, Pair<Double, String>>()
            var gmx = -1e9; var gbn: String? = null; var hasStrongLex = false; var hasShortNasal = false; var isChhu = false
            for ((n, x) in pool) {
                val l = x.lp ?: -1e9; if (l > gmx) { gmx = l; gbn = n }
                if (lang == "ne" && x.kind == "lex" && x.f >= 500 && l >= -1.8) hasStrongLex = true
                if (lang == "ne" && x.kind == "lex" && x.f >= 50 && !n.contains("ाँ") && (n.contains("ं") || n.contains("ण्ड") || n.contains("न्द"))) hasShortNasal = true
                if (lang == "ne" && (n.endsWith("छु") || n.endsWith("उन्छु"))) isChhu = true
            }
            hasShortNasal = hasShortNasal && !w.contains("aa") && NASAL_RE.containsMatchIn(w)
            isChhu = isChhu && (w.endsWith("chhu") || w.endsWith("xu"))
            val gbk = gbn?.let { Keys.hdkey(it) }; val rkW = if (lang == "ne") Keys.rkey(w) else null; gmx = maxOf(gmx, -25.0)
            for ((n, x) in pool) {
                val l = x.lp ?: -1e9
                val m = if (lang == "ne") canon(n, x.kind, w, l) else n
                val fEff = if (lang == "ne" && w == "pani" && n == "पानी" && x.kind == "direct" && x.f == 0) 1 else x.f
                var sc = l + prior(n, x.kind, fEff, l, rkW, hasStrongLex) - (if (lang == "ne") gate(n, x.kind, l, gmx, gbk, rkW, hasStrongLex) else 0.0)
                if (lang == "ne") {
                    if (hasShortNasal && n.contains("ाँ")) sc -= 1.2
                    if (isChhu) { if (m.endsWith("छु")) sc += 3.0 else if (m.endsWith("ला")) sc -= 8.0 }
                    if (x.kind != "direct" && gbn != null && n != gbn && w.contains("b") && !w.contains("v") && !w.contains("w") && gbn.drop(1).contains("ब") && !gbn.drop(1).contains("व") && n.drop(1).contains("व") && !n.drop(1).contains("ब") && gmx > -0.65 && (gmx - l) > 1.2 && x.f < 100) sc -= 2.0 * (gmx - l)
                }
                val cur = best[m]; if (cur == null || sc > cur.first) best[m] = sc to x.kind
            }
            best.map { Triple(it.key, it.value.first, it.value.second) }.sortedByDescending { it.second }.take(12)
        }
        return res.take(topn)
    }

    /** IME chips */
    fun candidates(word: String, n: Int = 5, lang: String = "ne") = rankWord(word, n, lang).map { it.first }

    private fun isWord(t: String): Boolean {
        if (!Regex("^[A-Za-z][A-Za-z0-9_']*$").matches(t)) return false
        if (t.any { it.isDigit() } || t.lowercase().trimEnd('\'') in keep || (t.length >= 2 && t == t.uppercase())) return false
        return true
    }

    // ---- layer 3
    fun analyze(text: String, byakaran: Boolean = true, lang: String = "ne"): List<Seg> {
        val toks = tokenRe.findAll(text).map { it.value }.toList(); val segs = ArrayList<Seg>(); val ranked = HashMap<Int, List<Triple<String, Double, String>>>()
        for ((i, t) in toks.withIndex()) {
            when {
                isWord(t) -> { val r = rankWord(t, 5, lang); ranked[segs.size] = r; val o = r.firstOrNull()?.first ?: t; segs.add(Seg(t, o, true, r.map { it.first }.ifEmpty { listOf(o) }, !lex.freq.containsKey(o))) }
                Regex("^\\d+(?:[.,]\\d+)*$").matches(t) && lang == "ne" -> segs.add(Seg(t, t.map { if (it.isDigit()) "०१२३४५६७८९"[it - '0'] else it }.joinToString(""), false))
                t == "." -> { val nxt = toks.getOrElse(i + 1) { "" }; val prev = segs.lastOrNull()?.out ?: ""; segs.add(Seg(t, if ((nxt.isEmpty() || ws.matches(nxt)) && prev.isNotEmpty() && deva.containsMatchIn(prev.takeLast(1))) "।" else t, false)) }
                else -> segs.add(Seg(t, t, false))
            }
        }
        if (!byakaran || lang != "ne") return segs
        if (ctx != null && ranked.isNotEmpty()) ctxPass(segs, ranked)
        agree(segs)
        return joinSegs(segs).res
    }

    private class Joined(val res: ArrayList<Seg>, val where: HashMap<Int, Int>, val head: HashMap<Int, Int>)

    /** Padayog / Padabiyog joining (mirrors Hybrid._join): where[i] = joined piece of segment i, head[k] = first segment of piece k */
    private fun joinSegs(segs: List<Seg>): Joined {
        val res = ArrayList<Seg>(); val where = HashMap<Int, Int>(); val head = HashMap<Int, Int>()
        for ((i, g) in segs.withIndex()) {
            val n = res.size
            if (g.word && g.out in hon && n >= 2 && res[n - 1].out == " " && res[n - 2].out.endsWith("नु") && res[n - 2].out !in join) {
                res.removeAt(n - 1); res.last().let { it.out += g.out; it.alts = listOf(it.out) }; where[i] = res.size - 1
            } else if (g.word && g.out in join && n >= 2 && ws.matches(res[n - 1].out) && res[n - 1].out.length == 1 && devl.containsMatchIn(res[n - 2].out.takeLast(1)) && res[n - 2].out !in join) {
                val nxt = (i + 1 until segs.size).map { segs[it].out }.firstOrNull { !ws.matches(it) } ?: ""
                if ((g.out == "को" && res[n - 2].out in pronoun && nxt in whoNext) || (g.out == "सँग" && (res[n - 2].out == "राम्रो" || res[n - 2].out == "नराम्रो"))) { res.add(g); where[i] = res.size - 1; head[res.size - 1] = i }
                else { res.removeAt(n - 1); res.last().let { pv -> val s = g.out; pv.out += s; pv.alts = listOf(pv.out) + pv.alts.drop(1).map { a -> a + s } }; where[i] = res.size - 1 }
            } else { res.add(g); where[i] = res.size - 1; head[res.size - 1] = i }
        }
        return Joined(res, where, head)
    }

    private val ctxPunct = setOf(",", ";", ":", "।", "?", "!")

    private fun ctxSeq(res: List<Seg>, V: Map<String, Int>): Triple<List<Int>, List<Int>, Map<Int, Int>> {
        val ids = ArrayList<Int>(); val sid = ArrayList<Int>(); val pos = HashMap<Int, Int>(); var s = 0; var fresh = true
        for ((k, g) in res.withIndex()) {
            val p = g.out
            if (ws.matches(p)) { if (p.contains('\n') && !fresh) { s++; fresh = true }; continue }
            val t: Int = when {
                p in ctxPunct -> V[p]!!
                p[0] in "0123456789०१२३४५६७८९" -> V["<num>"]!!
                p[0] in 'A'..'Z' || p[0] in 'a'..'z' -> V["<lat>"]!!
                devl.containsMatchIn(p.substring(0, 1)) -> V[p] ?: 1
                else -> continue
            }
            if (fresh) { ids.add(V["<bos>"]!!); sid.add(s); fresh = false }
            pos[k] = ids.size; ids.add(t); sid.add(s)
            if (p == "।" || p == "?" || p == "!") { s++; fresh = true }
        }
        return Triple(ids, sid, pos)
    }

    /** Stage 2: sequential left-to-right contextual rescoring of ambiguous words (mirrors Hybrid._ctx / tlm.js _ctx) */
    private fun ctxPass(segs: ArrayList<Seg>, ranked: Map<Int, List<Triple<String, Double, String>>>) {
        val cx = ctx ?: return; val V = cx.vocab
        for (i in ranked.keys.sorted()) {
            val cands = ranked[i]!!
            if (cands.size < 2 || segs[i].src.lowercase() in setOf("ma", "k", "baba", "paani") || cands[0].first in join) continue
            val top = cands[0].second
            val cs = cands.filter { top - it.second <= p.ctxMargin && it.first !in join }.take(5)
            if (cs.size < 2) continue
            val clone = ArrayList(segs.map { it.copy() }); agree(clone)
            val jn = joinSegs(clone); val k = jn.where[i] ?: continue
            if (jn.head[k] != i) continue
            val own = clone[i].out; val ro = jn.res[k].out
            val suffix = if (ro.startsWith(own)) ro.substring(own.length) else ""
            val (ids, sid, pos) = ctxSeq(jn.res, V); val pp = pos[k] ?: continue
            val win = IntArray(11) { idx -> val o = idx - 5; if (o == 0) 2 else { val q = pp + o; if (q in ids.indices && sid[q] == sid[pp]) ids[q] else 0 } }
            val cid = IntArray(cs.size) { V[cs[it].first + suffix] ?: 1 }
            val known = cid.indices.filter { cid[it] != 1 }; if (known.isEmpty()) continue
            val lg = cx.score(win, cid); val mx = known.maxOf { lg[it].toDouble() }
            val z = mx + ln(known.sumOf { exp(lg[it] - mx) })
            val lp = DoubleArray(cs.size) { if (cid[it] != 1) lg[it] - z else Double.NaN }
            val floor = lp.filter { !it.isNaN() }.max() - p.ctxUnk
            val sc = DoubleArray(cs.size) { cs[it].second + p.ctxBeta * (if (lp[it].isNaN()) floor else lp[it]) }
            var b = 0; for (j in 1 until cs.size) if (sc[j] > sc[b]) b = j
            val g = segs[i]; g.out = cs[b].first; g.alts = listOf(g.out) + cands.map { it.first }.filter { it != g.out }; g.low = !lex.freq.containsKey(g.out)
        }
    }

    /** previous non-space piece; a newline counts as a sentence start */
    private fun prevOf(segs: List<Seg>, i: Int): String {
        for (j in i - 1 downTo 0) { val o = segs[j].out; if (o.contains('\n')) return ""; if (!ws.matches(o)) return o }
        return ""
    }

    private fun agree(segs: List<Seg>) {
        var subj: String? = null; var teach = false
        for ((i, g) in segs.withIndex()) {
            var t = g.out
            if (g.word && g.src.lowercase() == "ma" && (t == "म" || t == "मा")) {
                val prev = prevOf(segs, i)
                g.out = if (prev.isEmpty() || prev in punct || prev in particles || prev in join || verbish.any { prev.endsWith(it) }) "म" else "मा"; t = g.out
            }
            if (!g.word) { if (t in punct || t.contains('\n')) { subj = null; teach = false }; continue }
            if (t.startsWith("शिक्षक") || t.startsWith("गुरु") || t.startsWith("मास्टर")) teach = true
            if (teach && t.startsWith("पठाउ")) { g.out = "पढाउ" + t.substring(4); t = g.out }
            if (g.word && g.src.lowercase() == "baba" && t == "बाबा" && prevOf(segs, i) == "र") {
                g.out = "बुबा"; t = g.out
            }
            if (g.word && g.src.lowercase() == "pani" && (t == "पनि" || t == "पानी")) {
                val prev = prevOf(segs, i)
                val nxt = (i + 1 until segs.size).map { segs[it].out }.firstOrNull { !ws.matches(it) } ?: ""
                if (prev in setOf("यहाँ", "त्यहाँ", "कहाँ", "धारामा", "तातो", "चिसो", "सफा", "फोहोर") && nxt in setOf("छ", "छैन", "थियो", "थिएन", "पर्यो", "पर्‍यो", "पर्छ")) {
                    g.out = "पानी"; t = g.out
                }
            }
            if (t in subj1p) { subj = "1p"; continue }; if (t in subj1s) { subj = "1s"; continue }; if (t in subj2) { subj = "2"; continue }; if (t in reset) { subj = null; continue }
            if (subj == "1p" && (t.endsWith("छौ") || t.endsWith("यौ"))) g.out = t + "ं"
            else if (subj == "2" && (t.endsWith("छौं") || t.endsWith("यौं"))) g.out = t.dropLast(1)
            else if (subj == "1s" && t.endsWith("ए") && (lex.freq[t] ?: 0) >= 3 && (lex.freq[t + "ँ"] ?: 0) >= 20) g.out = t + "ँ"
            else if (subj == "1s" && t.endsWith("ये") && (lex.freq[t.dropLast(2) + "एँ"] ?: 0) >= 20) g.out = t.dropLast(2) + "एँ"
            if (t == "की" || t == "कि") {
                val prev = prevOf(segs, i)
                g.out = if (kiVerb.any { prev.endsWith(it) }) "कि" else "की"
            }
        }
    }

    // ---- keyboard (IME) API --------------------------------------------------------------------------

    /** Ranked chips for the word being typed; [joins][i] is true when chip i should be glued to the previous word (Padayog). */
    class Suggestion(val words: List<String>, val joins: BooleanArray, val pending: Boolean = false)

    private val leftTok = Regex("\\s+|[।?!,;:]|[^\\s।?!,;:]+")

    private fun leftSegs(left: String): ArrayList<Seg> {
        val segs = ArrayList<Seg>()
        val toks = leftTok.findAll(left.takeLast(160)).map { it.value }.toList().takeLast(14)
        for (t in toks) {
            when {
                t.isBlank() -> segs.add(Seg(t, if (t.contains('\n')) "\n" else t, false))
                t in punct || t == ":" -> segs.add(Seg(t, t, false))
                else -> segs.add(Seg(t, t, true))
            }
        }
        return segs
    }

    /** Context-aware suggestions for [roman] given the text [left] of the caret (mirrors analyze() on the last word). */
    @Synchronized
    fun suggest(left: String, roman: String, n: Int = 6, byakaran: Boolean = true, deep: Boolean = true, stale: (() -> Boolean)? = null): Suggestion {
        neural.stale = stale
        try { return suggestInner(left, roman, n, byakaran, deep) } finally { neural.stale = null }
    }

    private fun suggestInner(left: String, roman: String, n: Int, byakaran: Boolean, deep: Boolean): Suggestion {
        val ranked = rankWord(roman, 12, "ne", deep)
        if (ranked.isEmpty()) return Suggestion(emptyList(), BooleanArray(0))
        val segs = leftSegs(left); val i = segs.size
        segs.add(Seg(roman, ranked[0].first, true, ranked.map { it.first }, !lex.freq.containsKey(ranked[0].first)))
        if (byakaran) {
            if (ctx != null && ranked.size >= 2) ctxPass(segs, mapOf(i to ranked))
            agree(segs)
        }
        val ordered = LinkedHashSet<String>()
        ordered.add(segs[i].out); ordered.addAll(segs[i].alts); ranked.forEach { ordered.add(it.first) }
        if (roman.length >= 3) for (w in lex.completions(Keys.rkey(roman), 3)) ordered.add(w)
        val words = ordered.take(n)
        val joins = BooleanArray(words.size)
        if (byakaran && segs.size >= 3) for ((j, w) in words.withIndex()) {
            val clone = ArrayList(segs.map { it.copy() }); clone[i].out = w
            val jn = joinSegs(clone); val k = jn.where[i] ?: continue
            joins[j] = jn.head[k] != i
        }
        return Suggestion(words, joins, !deep && shallowKeys.contains("ne|" + roman.lowercase() + "|s"))
    }

    /** English/technical tokens (email, app, NEB ...) stay Latin: the literal chip leads for these. */
    fun keepsLatin(t: String) = t.lowercase().trimEnd('\'') in keep || (t.length >= 2 && t == t.uppercase())

    fun transliterate(text: String, byakaran: Boolean = true): String = analyze(text, byakaran).joinToString("") { it.out }
}
