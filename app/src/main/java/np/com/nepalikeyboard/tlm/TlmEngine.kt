// Port of the transliterationlm hybrid engine (docs/tlm.js, model/hybrid.py), synced with NikitHamal/transliterationlm session 42.
// Layers: 1 lexicon + morphology candidates, 2 seq2seq ensemble (main + m2 + a4kd), 1.5 stem-bigram LM, 2b sentence context scorer, 3 Byakaran rules.
package np.com.nepalikeyboard.tlm

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/** Neural layer abstraction so the engine logic is testable without a model. */
interface Neural {
    fun encode(word: String, lang: String): Any

    /** Like [encode] plus the ensemble knobs of the web engine (capped-veto value, single-word common protection). */
    fun encodeCap(word: String, lang: String, cap: Double?, swc: Boolean): Any = encode(word, lang)

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


data class Params(
    val lam: Double = 0.5, val mu: Double = 2.0, val direct: Double = 8.0, val directStep: Double = 3.0, val snapRatio: Double = 0.1,
    val comp2Pen: Double = 2.5, val compPen: Double = 3.0, val nnPen: Double = 1.0, val nFull: Int = 16, val nNn: Int = 6, val nStem: Int = 4,
    val k: Int = 5, val snapMin: Int = 30, val beamThr: Double = -1.5,
    val ctxBeta: Double = 1.0, val ctxMargin: Double = 8.0, val ctxUnk: Double = 2.0,
    val oov: Double = 0.0, val oovConf: Double = 0.0, val confLp: Double = -0.3, val fmin: Int = 0,
    val gateK: Double = 0.0, val gateD: Double = 1.0, val gateHm: Double = 1.0, val genMax: Int = 0, val genPen: Double = 0.0,
    val lmMu: Double = 0.3, val lmSkel: Double = 0.0, val lmMargin: Double = 4.0, val lmLam: Double = 0.85,
    val shTau: Double = Double.NaN, val shAtt: Double = 0.0, val shBeam: Double = Double.NaN, val shLock: Double = 15.0,
    val altPen: Double = 1.5, val dfloor: Double = -4.0,
)

/** Build [Params] from config.json -> "hybrid" (same keys and defaults as docs/tlm.js `Transliterator.P`). */
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
        lmMu = h.optDouble("lm_mu", d.lmMu), lmSkel = h.optDouble("lm_skel", d.lmSkel), lmMargin = h.optDouble("lm_margin", d.lmMargin), lmLam = h.optDouble("lm_lam", d.lmLam),
        shTau = h.optDouble("sh_tau", d.shTau), shAtt = h.optDouble("sh_att", d.shAtt), shBeam = h.optDouble("sh_beam", d.shBeam), shLock = h.optDouble("sh_lock", d.shLock),
        altPen = h.optDouble("alt_pen", d.altPen), dfloor = h.optDouble("dfloor", d.dfloor),
    )
}

data class Seg(var src: String, var out: String, val word: Boolean, var alts: List<String> = emptyList(), var low: Boolean = false)

typealias TlmRanked = List<Triple<String, Double, String>>

private val NASAL_RE = Regex("an[sjd]")
private val NASAL_CLUSTER_RE = Regex("m[kgcjtdnpbsh]")
private val LONG_VOWEL_HINTS = listOf("ii", "ee", "oo", "uu", "ng")
private val HRU_RE = Regex("हरु(लाई|मा|को|का|की|ले|बाट|सँग|सित|देखि|सम्म|प्रति)?$")
private val HRU_ENDS = listOf("hru", "hrule", "hrulai", "hruko", "hruma", "hrubata")
private val MYE_RE = Regex("^(.*(?:[ािीुू]|[गभआ]))ये(र|को|का|की|कै|न|)$")
private val IRAHE_RE = Regex("^(.*िरहे)(छ|छु|छौ|छौं|छौँ)$")
private val IRAHE_ROMAN = Regex("irahe(chh|x)")
private val SAK_RE = Regex("^सक(ेको|ेका|्यो|ियो|ेँ|्यौं|ेछ|्छ|्छु|ेन)")
private val PAR_TAIL_RE = Regex("(्न|ान|उन)$")
private val PARA_RE = Regex("^paraa?$")
private val NORM_VOWEL_RE = Regex("([aeiou])\\1{3,}")
private val NORM_CONS_RE = Regex("([^aeiou])\\1{3,}")
private val ALT_YE_RE = Regex("^ye(?=[a-z]{2})")
private val ALT_TE_RE = Regex("^te(?=[sht])")
private val ALT_IRAKHE_RE = Regex("irakhe")
private val WORD_RE = Regex("^[A-Za-z][A-Za-z0-9_']*$")
private val NUM_RE = Regex("^\\d+(?:[.,]\\d+)*$")
private val TOKEN_RE = Regex(
    """https?://\S+|www\.\S+|[\p{L}\p{N}_.+-]+@[\p{L}\p{N}_-]+\.[\p{L}\p{N}_.-]+|[#@][\p{L}\p{N}_]+|[A-Za-z][A-Za-z0-9_']*|\d+(?:[.,]\d+)*|\s+|[\s\S]""",
)
private val LEFT_TOKEN_RE = Regex("\\s+|[।?!,;:]|[^\\s।?!,;:]+")

private fun isWs(s: String): Boolean {
    if (s.isEmpty()) return false
    for (c in s) if (!c.isWhitespace()) return false
    return true
}

private fun isDeva(c: Char) = c in '\u0900'..'\u097F'
private fun isDevl(c: Char) = c in '\u0900'..'\u0963' || c in '\u0971'..'\u097F'

/** s14 informal typing: collapse elongation (paraaaa -> paraa). */
fun normRoman(w: String): String = NORM_CONS_RE.replace(NORM_VOWEL_RE.replace(w, "$1$1"), "$1")

/** s14 chat conventions (ye->ya, te->tya, v->bh) so the model can score words typed the way people text. */
fun altRoman(w: String): String {
    if (w.length < 4) return w
    return ALT_IRAKHE_RE.replace(ALT_TE_RE.replace(ALT_YE_RE.replace(w, "ya"), "tya").replace("v", "bh"), "irahe")
}

private const val FSM_CONS = "कखगघङचछजझञटठडढणतथदधनपफबभमयरलवशषसहळ"
private const val FSM_IV = "अआइईउऊऋएऐओऔ"
private const val FSM_M = "ािीुूृेैोौॉ"
private val FSM_OK = mapOf('^' to "CV", 'C' to "CMHNXZV", 'V' to "CNXV", 'M' to "CNXV", 'H' to "C", 'N' to "CV", 'X' to "CV", 'Z' to "CMHN", 'O' to "CVO")

/** s21: Devanagari orthotactic FSM - beam candidates must be well-formed. */
private fun fsmOk(w: String): Boolean {
    var p = '^'
    for (c in w) {
        val k = when {
            FSM_CONS.indexOf(c) >= 0 -> 'C'
            FSM_IV.indexOf(c) >= 0 -> 'V'
            FSM_M.indexOf(c) >= 0 -> 'M'
            c == '्' -> 'H'
            c == 'ं' || c == 'ँ' -> 'N'
            c == 'ः' -> 'X'
            c == '़' -> 'Z'
            else -> 'O'
        }
        if (FSM_OK[p]!!.indexOf(k) < 0) return false
        p = k
    }
    return p != '^'
}

class HybridEngine(
    private val neural: Neural, private val lex: TlmLexicon, private val direct: Map<String, List<String>>, keep: Set<String>,
    private val suffixes: List<Pair<String, String>>, private val join: Set<String>, private val p: Params = Params(),
    private val ctx: CtxScorer? = null,
) {
    private val keep = keep

    /** Full 3-model ensemble; until it is attached (background load) the engine runs on [neural] alone. */
    @Volatile var ensemble: Neural? = null

    /** s32 stem-bigram LM (null until its asset is parsed). */
    @Volatile var lm: CompactLM? = null

    /** Direct-table entries marked with '!' in direct_ne.tsv: Stage 2 never overrides them. */
    @Volatile var definitive: Set<String> = emptySet()

    private val cache = object : LinkedHashMap<String, TlmRanked>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, TlmRanked>) = size > 4000
    }
    private val whoNext = setOf("हो", "हुन्", "हुनुहुन्छ", "होइन", "छ", "छन्", "हुन्छ")
    private val pronoun = setOf("तपाईं", "तिमी", "ऊ", "यो", "त्यो", "उनी", "को")
    private val subj1p = setOf("हामी", "हामीले")
    private val subj1s = setOf("म", "मैले")
    private val subj2 = setOf("तिमी", "तिमीले")
    private val reset = setOf("ऊ", "उनी", "उसले", "उनले", "यसले", "त्यसले", "तिनीहरू", "उनीहरू", "यो", "त्यो", "तपाईं", "तपाईंले")
    private val verbish = listOf("छु", "छ", "छौं", "छौँ", "छौ", "छन्", "यो", "एँ", "ए", "ये", "यौ", "यौं", "यौँ", "एको", "थियो", "हुन्छ", "ेर", "दै", "ने", "ला", "ली", "इन्", "इयो", "ेँ")
    private val particles = setOf("र", "तर", "कि", "अनि", "भने", "जब", "अब", "त", "नि", "पनि", "के", "कसरी", "किन", "भोलि", "आज", "हिजो", "अहिले", "फेरि", "चाहिँ", "बिना", "लागि")
    private val maNext = setOf("लाई", "ले", "को", "का", "बाट", "भन्दा", "सँग", "सित", "देखि", "सम्म", "प्रति")
    private val khanNext = setOf("man", "jana", "jane", "gaye", "gayo", "gayau", "gaya", "janxu", "jaanxu", "janchu", "janchhu", "jaanchhu", "jaanchu", "janxa", "janchha", "jaanchha", "gayeko")
    private val sangaiNot = listOf("मा", "देखि", "बाट", "सम्म", "लाई", "भन्दा")
    private val poss = setOf("मेरो", "तिम्रो", "हाम्रो", "उसको", "यसको", "उनको", "तपाईंको", "तिनको", "उहाँको")
    private val notSubjNext = setOf("लाई", "को", "का", "की", "सँग", "बाट", "देखि", "भन्दा", "सित", "प्रति")
    private val kiSubj = setOf("तिमी", "म", "हामी", "ऊ", "उनी", "तपाईं", "उहाँ", "तिमीले", "हामीले", "मैले", "उनीहरू", "तिनीहरू")
    private val kiVerb = listOf("यो", "छ", "छन्", "हो", "भो", "थियो", "छु", "छौ", "छौं", "छौँ", "एँ", "ला", "हुन्छ", "यौ", "यौं", "यौँ", "छिन्", "ेँ", "ने")
    private val par = setOf("पर्छ", "पर्दैन", "पर्‍यो", "पर्यो", "पर्ने", "पर्नेछ")
    private val hon = setOf("हुन्छ", "भयो", "भएको", "हुनुभयो", "हुनुहोस्", "हुनुहुन्छ", "भएन", "हुनेछ", "हुँदैन", "हुनुहुन्न", "पर्छ", "पर्दैन", "पर्‍यो", "पर्ने", "पर्नेछ")
    private val punct = setOf("।", "?", "!", ",", ";")
    private val lockWords = setOf("ma", "k", "baba", "paani", "sir")
    private val ctxPunct = setOf(",", ";", ":", "।", "?", "!")

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
        if (kind == "direct") return n0 // s21: Pragya chandrabindu forms are canonical
        if (n0 == "गरो") return "गाह्रो"
        val mr = IRAHE_RE.find(n0)
        if (mr != null && IRAHE_ROMAN.containsMatchIn(w)) return mr.groupValues[1] + "को " + mr.groupValues[2] // s14: lagirahechha -> लागिरहेको छ
        val wl = w.lowercase()
        if (wl == "sorry" && n0 == "सोरी") return "सरी"
        var n = if (n0.endsWith("उन्छु")) n0.dropLast(4) + "उँछु" else n0
        if (n.startsWith("तपाईँ")) return "तपाईं" + n.substring(5)
        if (n.startsWith("तपाई") && !n.startsWith("तपाईं") && !n.startsWith("तपाईँ")) return "तपाईं" + n.substring(4)
        val mye = MYE_RE.find(n)
        if (mye != null) { // s12: खायेर -> खाएर (ties go to the standard form)
            val alt = mye.groupValues[1] + "ए" + mye.groupValues[2]
            if ((lex.freq[alt] ?: 0) >= (lex.freq[n] ?: 0)) n = alt
        }
        if (n.endsWith("द्") && (lex.freq[n.dropLast(1)] ?: 0) >= 2 * (lex.freq[n] ?: 0) && (lex.freq[n.dropLast(1)] ?: 0) >= 200) n = n.dropLast(1)
        if (HRU_ENDS.any { wl.endsWith(it) } && n.length >= 5 && n.contains("हरु")) n = HRU_RE.replace(n, "हरू\$1")
        if ((LONG_VOWEL_HINTS.any { wl.contains(it) } || NASAL_CLUSTER_RE.containsMatchIn(wl)) && lp > -1.8) return n
        if (wl.contains("sh") && (n.contains("श") || n.contains("ष")) && lp > -1.2) return n
        val alt = lex.snap[Keys.hdkey(n)]
        return if (alt != null && alt != n && lex.freq.containsKey(alt) && lex.freq[alt]!! >= p.snapMin && (lex.freq[n] ?: 0) < p.snapRatio * lex.freq[alt]!!) alt else n
    }

    private fun b(f: Int) = if (f >= p.fmin) p.lam * ln1p(f.toDouble()) + p.mu - (if (f <= p.genMax) p.genPen else 0.0) else p.oov

    /** neural-agreement gate: lexicon/morphology candidates far below the model's best log-prob get no free frequency boost */
    private fun gate(n: String, kind: String, lp: Double, mx: Double, bk: String?, rkW: String?, hasStrongLex: Boolean): Double {
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

    /** s35 multi-word split-only shield: never fires on single-word inputs or direct full-word lexicon hits */
    private fun shield(n: String, kind: String, f: Int, pr: Double, lp: Double, mx: Double, sent: Boolean): Double {
        if (!sent || p.shTau.isNaN() || kind == "direct" || pr <= 0.0 || (mx - lp) <= p.shTau) return pr
        val inLex = lex.freq.containsKey(n)
        val lock = if (p.shLock.isNaN()) 15.0 else p.shLock
        return if ((!inLex && (kind == "comp" || kind == "comp2")) || (f < 15 && (mx - lp) > maxOf(p.shTau, 2.2) && !(inLex && (lex.freq[n] ?: 0).toDouble() >= lock))) pr * p.shAtt else pr
    }

    private fun prior(w: String, kind: String, f: Int, lp: Double, rkW: String?, hasStrongLex: Boolean): Double = when (kind) {
        "beam" -> if (lex.freq.containsKey(w) && !(hasStrongLex && rkW != null && Keys.dkey(w) != rkW)) b(lex.freq[w]!!) else p.oov + (if (lp >= p.confLp) p.oovConf else 0.0)
        "direct" -> p.direct - p.directStep * f + (if (lp < p.dfloor) p.dfloor - lp else 0.0) // s14: a curated entry cannot be sunk by an overconfident model
        "comp2" -> b(f) - p.comp2Pen
        "lex" -> b(f)
        "lex_nn" -> b(f) - p.nnPen
        "comp" -> if (lex.freq.containsKey(w)) b(lex.freq[w]!!) else b(f) - p.compPen
        else -> 0.0
    }

    /** s38 cap_common: curated direct entry or common lexicon entry without a dialectal suffix keeps the single-word weights */
    private fun capCommon(w: String): Boolean {
        if (direct.containsKey(w)) return true
        val f = (lex.full[Keys.rkey(w)] ?: emptyList<String>()).take(p.nFull).maxOfOrNull { lex.freq[it] ?: 0 } ?: 0
        return f >= Ens.CAP_COMMON_F && !(w.endsWith("nya") || w.endsWith("yako") || w.endsWith("chham") || w.endsWith("tami") || w.endsWith("harule") || w.endsWith("bata"))
    }

    private class E(var kind: String, var f: Int, var lp: Double?)

    private fun cacheKey(lang: String, sent: Boolean, w: String) = "$lang|${if (sent) 1 else 0}|$w"

    /** True when the full-ensemble answer for [roman] is not cached yet (the caller should schedule a deep pass). */
    fun deepPending(roman: String, sent: Boolean): Boolean =
        ensemble != null && synchronized(this) { !cache.containsKey(cacheKey("ne", sent, normRoman(roman.lowercase()))) }

    /**
     * Ranked candidates (word, score, kind), best first, for ONE roman word.
     * [deep] = the 3-model ensemble (what the web app shows); otherwise only the main model answers (fast tier, the keystroke path).
     */
    @Synchronized
    fun rankWord(roman: String, topn: Int = 5, lang: String = "ne", sent: Boolean = false, deep: Boolean = true): TlmRanked {
        val w = normRoman(roman.lowercase())
        val ens = ensemble
        val deepKey = cacheKey(lang, sent, w)
        val useEns = deep && ens != null
        if (!useEns && ens != null) cache[deepKey]?.let { return it.take(topn) } // the ensemble answer is better than the fast one
        val ck = if (useEns) deepKey else "$deepKey|s"
        val hit = cache[ck]
        if (hit != null) return hit.take(topn)
        val res = rank(w, lang, sent, if (useEns) ens!! else neural)
        cache[ck] = res
        return res.take(topn)
    }

    private fun rank(w: String, lang: String, sent: Boolean, nn: Neural): TlmRanked {
        val isCom = lang == "ne" && capCommon(w)
        val capOn = sent && lang == "ne" && !isCom
        val cap = if (capOn) Ens.CAP else null
        val swc = !sent && isCom
        val e = nn.encodeCap(w, lang, cap, swc)
        val pool = LinkedHashMap<String, E>()
        if (lang == "ne") {
            for ((n, kf) in lexCands(w)) pool[n] = E(kf.first, kf.second, null)
            (direct[w] ?: emptyList()).forEachIndexed { pos, n -> val x = pool[n]; if (x != null) { x.kind = "direct"; x.f = pos } else pool[n] = E("direct", pos, null) }
            val names = pool.keys.toList()
            if (names.isNotEmpty()) nn.score(e, names).forEachIndexed { i, s -> pool[names[i]]!!.lp = s.toDouble() }
        }
        // s14 informal-typing view (v->bh, ye->ya, te->tya): candidates from both views, lp = max(lp(w), lp(alt) - alt_pen)
        var alt: String? = null
        var ea: Any? = null
        if (lang == "ne") {
            val a = altRoman(w)
            if (a != w) { alt = a; ea = nn.encodeCap(a, lang, cap, swc) }
        }
        if (alt != null && ea != null) {
            for ((n, kf) in lexCands(alt)) if (n !in pool) pool[n] = E(kf.first, kf.second, null)
            val nul = pool.keys.filter { pool[it]!!.lp == null }
            if (nul.isNotEmpty()) nn.score(e, nul).forEachIndexed { i, s -> pool[nul[i]]!!.lp = s.toDouble() }
            val all = pool.keys.toList()
            nn.score(ea, all).forEachIndexed { i, s -> val v = pool[all[i]]!!; v.lp = max(v.lp ?: -1e9, s.toDouble() - p.altPen) }
        }
        var mx = -1e9
        for (v in pool.values) mx = max(mx, v.lp ?: -1e9)
        val thr = if (sent && !p.shBeam.isNaN()) max(p.beamThr, p.shBeam) else p.beamThr
        if (lang != "ne" || pool.isEmpty() || mx < thr) {
            if (lang == "ne") {
                val c2 = comp2Cands(w).filter { it.key !in pool }
                for ((n, kf) in c2) pool[n] = E(kf.first, kf.second, null)
                if (c2.isNotEmpty()) { val names = c2.keys.toList(); nn.score(e, names).forEachIndexed { i, s -> pool[names[i]]!!.lp = s.toDouble() } }
            }
            val addW = ArrayList<String>()
            for ((n, lp) in nn.beam(e, p.k)) {
                val x = pool[n]
                if (x != null) {
                    x.lp = if (alt != null) max(x.lp ?: -1e9, lp.toDouble()) else lp.toDouble()
                } else if (fsmOk(n)) {
                    pool[n] = E("beam", 0, lp.toDouble()); addW.add(n)
                }
            }
            if (alt != null && ea != null) {
                val addA = ArrayList<String>()
                for ((n, lp) in nn.beam(ea, p.k)) {
                    val x = pool[n]
                    if (x != null) {
                        x.lp = max(x.lp ?: -1e9, lp.toDouble() - p.altPen)
                    } else if (fsmOk(n)) {
                        pool[n] = E("beam", 0, lp.toDouble() - p.altPen); addA.add(n)
                    }
                }
                if (addA.isNotEmpty()) nn.score(e, addA).forEachIndexed { i, s -> val v = pool[addA[i]]!!; v.lp = max(v.lp ?: -1e9, s.toDouble()) }
                if (addW.isNotEmpty()) nn.score(ea, addW).forEachIndexed { i, s -> val v = pool[addW[i]]!!; v.lp = max(v.lp ?: -1e9, s.toDouble() - p.altPen) }
            }
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
            val pr = prior(n, x.kind, fEff, l, rkW, hasStrongLex)
            var sc = l + (if (lang == "ne") shield(n, x.kind, fEff, pr, l, gmx, sent) else pr) - (if (lang == "ne") gate(n, x.kind, l, gmx, gbk, rkW, hasStrongLex) else 0.0)
            if (lang == "ne") {
                if (hasShortNasal && n.contains("ाँ")) sc -= 1.2
                if (isChhu) { if (m.endsWith("छु")) sc += 3.0 else if (m.endsWith("ला")) sc -= 8.0 }
                if (x.kind != "direct" && gbn != null && n != gbn && w.contains("b") && !w.contains("v") && !w.contains("w") && gbn.drop(1).contains("ब") && !gbn.drop(1).contains("व") && n.drop(1).contains("व") && !n.drop(1).contains("ब") && gmx > -0.65 && (gmx - l) > 1.2 && x.f < 100) sc -= 2.0 * (gmx - l)
            }
            val cur = best[m]; if (cur == null || sc > cur.first) best[m] = sc to x.kind
        }
        return best.map { Triple(it.key, it.value.first, it.value.second) }.sortedByDescending { it.second }.take(12)
    }

    /** IME chips */
    fun candidates(word: String, n: Int = 5, lang: String = "ne") = rankWord(word, n, lang).map { it.first }

    private fun isWord(t: String): Boolean {
        if (!WORD_RE.matches(t)) return false
        if (t.any { it.isDigit() } || t.lowercase().trimEnd('\'') in keep || (t.length >= 2 && t == t.uppercase())) return false
        return true
    }

    // ---- layer 3
    @Synchronized
    fun analyze(text: String, byakaran: Boolean = true, lang: String = "ne"): List<Seg> {
        val toks = TOKEN_RE.findAll(text).map { it.value }.toList(); val segs = ArrayList<Seg>(); val ranked = HashMap<Int, TlmRanked>()
        val sent = toks.count { isWord(it) } >= 2
        for ((i, t) in toks.withIndex()) {
            when {
                isWord(t) -> {
                    val r = rankWord(t, 5, lang, sent, true); ranked[segs.size] = r
                    val o = r.firstOrNull()?.first ?: t
                    segs.add(Seg(t, o, true, listOf(o) + r.map { it.first }.filter { it != o }, !lex.freq.containsKey(o)))
                }
                NUM_RE.matches(t) && lang == "ne" -> segs.add(Seg(t, t.map { if (it.isDigit()) "०१२३४५६७८९"[it - '0'] else it }.joinToString(""), false))
                t == "." -> {
                    val nxt = toks.getOrElse(i + 1) { "" }; val prev = segs.lastOrNull()?.out ?: ""
                    segs.add(Seg(t, if ((nxt.isEmpty() || isWs(nxt)) && prev.isNotEmpty() && isDeva(prev.last())) "।" else t, false))
                }
                else -> segs.add(Seg(t, t, false))
            }
        }
        if (!byakaran || lang != "ne") return segs
        if (ctx != null && ranked.isNotEmpty()) { lmAdjust(segs, ranked); ctxPass(segs, ranked) }
        agree(segs)
        return joinSegs(segs).res
    }

    private class Joined(val res: ArrayList<Seg>, val where: HashMap<Int, Int>, val head: HashMap<Int, Int>)

    /** Padayog / Padabiyog joining (mirrors Transliterator._join): where[i] = joined piece of segment i, head[k] = first segment of piece k */
    private fun joinSegs(segs: List<Seg>): Joined {
        val res = ArrayList<Seg>(); val where = HashMap<Int, Int>(); val head = HashMap<Int, Int>()
        for ((i, g) in segs.withIndex()) {
            val n = res.size
            if (g.word && g.out in hon && n >= 2 && res[n - 1].out == " " && res[n - 2].out !in join &&
                (res[n - 2].out.endsWith("नु") || (g.out in par && PAR_TAIL_RE.containsMatchIn(res[n - 2].out)))
            ) {
                res.removeAt(n - 1); val pv = res.last()
                if (!pv.out.endsWith("नु")) pv.out += "ु"
                pv.out += g.out; pv.src += " " + g.src; pv.alts = listOf(pv.out); where[i] = res.size - 1
            } else if (g.word && SAK_RE.containsMatchIn(g.out) && n >= 2 && res[n - 1].out == " " && res[n - 2].out.let { it.endsWith("ि") || it.endsWith("ी") } &&
                res[n - 2].out.length >= 4 && res[n - 2].out !in particles
            ) { // s14: थाकि सकेको -> थाकिसकेको
                res.removeAt(n - 1); val pv = res.last()
                pv.out = (if (pv.out.endsWith("ी")) pv.out.dropLast(1) + "ि" else pv.out) + g.out; pv.src += " " + g.src; pv.alts = listOf(pv.out); where[i] = res.size - 1
            } else if (g.word && g.out in join && n >= 2 && isWs(res[n - 1].out) && res[n - 1].out.length == 1 && isDevl(res[n - 2].out.last()) && res[n - 2].out !in join) {
                var nxt = ""
                for (j in i + 1 until segs.size) if (!isWs(segs[j].out)) { nxt = segs[j].out; break }
                val prevOut = res[n - 2].out
                if ((g.out == "को" && prevOut in pronoun && nxt in whoNext) || (g.out == "सँग" && (prevOut == "राम्रो" || prevOut == "नराम्रो")) ||
                    (g.out == "सँगै" && sangaiNot.any { prevOut.endsWith(it) }) || (g.out == "तिर" && prevOut in poss)
                ) { res.add(g); where[i] = res.size - 1; head[res.size - 1] = i }
                else {
                    res.removeAt(n - 1); val pv = res.last(); val s = g.out
                    pv.out += s; pv.src += " " + g.src; pv.alts = listOf(pv.out) + pv.alts.drop(1).map { a -> a + s }; where[i] = res.size - 1
                }
            } else { res.add(g); where[i] = res.size - 1; head[res.size - 1] = i }
        }
        return Joined(res, where, head)
    }

    private fun ctxSeq(res: List<Seg>, V: Map<String, Int>): Triple<List<Int>, List<Int>, Map<Int, Int>> {
        val ids = ArrayList<Int>(); val sid = ArrayList<Int>(); val pos = HashMap<Int, Int>(); var s = 0; var fresh = true
        for ((k, g) in res.withIndex()) {
            val p = g.out
            if (isWs(p)) { if (p.contains('\n') && !fresh) { s++; fresh = true }; continue }
            val t: Int = when {
                p in ctxPunct -> V[p]!!
                p[0] in "0123456789०१२३४५६७८९" -> V["<num>"]!!
                p[0] in 'A'..'Z' || p[0] in 'a'..'z' -> V["<lat>"]!!
                isDevl(p[0]) -> V[p] ?: 1
                else -> continue
            }
            if (fresh) { ids.add(V["<bos>"]!!); sid.add(s); fresh = false }
            pos[k] = ids.size; ids.add(t); sid.add(s)
            if (p == "।" || p == "?" || p == "!") { s++; fresh = true }
        }
        return Triple(ids, sid, pos)
    }

    private fun isLocked(tl: String, cands: TlmRanked): Boolean {
        val dl = direct[tl]
        return cands.size < 2 || tl in lockWords || (dl != null && tl in definitive && cands[0].first == dl[0])
    }

    /**
     * s32 Stage 1.5: compact stem-bigram KN Viterbi over chains of consecutive roman words (mirrors Hybrid._lm_adjust).
     * [startPrev] lets the keyboard seed a chain with the already committed Devanagari word left of the caret.
     */
    private fun lmAdjust(segs: List<Seg>, ranked: MutableMap<Int, TlmRanked>, startPrev: Map<Int, String> = emptyMap()) {
        val lm = lm ?: return
        val mu = p.lmMu; val bs = p.lmSkel; val marg = p.lmMargin; val lam = p.lmLam
        if (mu <= 0 && bs <= 0) return
        class Node(val i: Int, val st: List<Triple<String, Double, String>>, val free: Boolean)
        val chains = ArrayList<ArrayList<Node>>(); var cur = ArrayList<Node>(); var last = -1
        for (i in ranked.keys.sorted()) {
            if (last >= 0) {
                var brk = false
                for (j in last + 1 until i) { val t = segs[j].src; if (t.trim().isNotEmpty() || t.contains('\n')) { brk = true; break } }
                if (brk) { chains.add(cur); cur = ArrayList() }
            }
            last = i
            val cands = ranked[i]!!
            if (cands.isEmpty() || cands[0].first in join) continue
            val tl = segs[i].src.lowercase()
            val top = cands[0].second
            val st = if (isLocked(tl, cands)) listOf(cands[0]) else cands.filter { top - it.second <= marg && it.first !in join }.take(5)
            cur.add(Node(i, st, st.size > 1))
        }
        chains.add(cur)
        for (ch in chains) {
            if (ch.none { it.free }) continue
            val em = ch.map { node ->
                if (!(node.free && bs > 0)) node.st.map { it.second }
                else {
                    val top = node.st[0].second
                    val pr = node.st.map { exp(it.second - top) }; val z = pr.sum()
                    val sk = node.st.map { lm.skel(it.first) }; val mass = HashMap<String, Double>()
                    sk.forEachIndexed { j, k -> mass[k] = (mass[k] ?: 0.0) + pr[j] }
                    node.st.mapIndexed { j, c -> c.second + bs * ln(mass[sk[j]]!! / z) }
                }
            }
            val n = ch.size; val fw = ArrayList<DoubleArray>(); val bw = arrayOfNulls<DoubleArray>(n)
            for (t in 0 until n) {
                val cs = ch[t].st
                if (t == 0) fw.add(DoubleArray(cs.size) { j -> em[0][j] + mu * lm.lp(startPrev[ch[0].i], cs[j].first, lam) })
                else {
                    val pc = ch[t - 1].st; val prevFw = fw[t - 1]
                    fw.add(DoubleArray(cs.size) { j ->
                        var bestV = -Double.MAX_VALUE
                        for (q in pc.indices) { val v = prevFw[q] + mu * lm.lp(pc[q].first, cs[j].first, lam); if (v > bestV) bestV = v }
                        em[t][j] + bestV
                    })
                }
            }
            bw[n - 1] = DoubleArray(ch[n - 1].st.size)
            for (t in n - 2 downTo 0) {
                val nx = ch[t + 1].st; val nextBw = bw[t + 1]!!
                bw[t] = DoubleArray(ch[t].st.size) { j ->
                    val c = ch[t].st[j].first
                    var bestV = -Double.MAX_VALUE
                    for (q in nx.indices) { val v = mu * lm.lp(c, nx[q].first, lam) + em[t + 1][q] + nextBw[q]; if (v > bestV) bestV = v }
                    bestV
                }
            }
            for ((t, node) in ch.withIndex()) {
                if (!node.free) continue
                val st = node.st
                val mm = DoubleArray(st.size) { fw[t][it] + bw[t]!![it] }; val mb = mm.max(); val old = ranked[node.i]!!; val top = old[0].second
                val nw = st.indices.map { Triple(st[it].first, top + mm[it] - mb, st[it].third) }.sortedByDescending { it.second }
                val seen = nw.map { it.first }.toHashSet()
                ranked[node.i] = nw + old.filter { it.first !in seen }
                val out = nw[0].first; segs[node.i].out = out; segs[node.i].alts = listOf(out) + segs[node.i].alts.filter { it != out }
            }
        }
    }

    /** Stage 2: sequential left-to-right contextual rescoring of ambiguous words (mirrors Hybrid._ctx) */
    private fun ctxPass(segs: ArrayList<Seg>, ranked: Map<Int, TlmRanked>) {
        val cx = ctx ?: return; val V = cx.vocab
        for (i in ranked.keys.sorted()) {
            val cands = ranked[i]!!
            if (cands.size < 2 || segs[i].src.lowercase() in lockWords || cands[0].first in join) continue
            val tlo = segs[i].src.lowercase(); val dlo = direct[tlo]
            if (dlo != null && tlo in definitive && cands[0].first == dlo[0]) continue // s14: a single-valued curated entry is definitive
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
            var bi = 0; for (j in 1 until cs.size) if (sc[j] > sc[bi]) bi = j
            val g = segs[i]; g.out = cs[bi].first; g.alts = listOf(g.out) + cands.map { it.first }.filter { it != g.out }; g.low = !lex.freq.containsKey(g.out)
        }
    }

    /** previous non-space piece; a newline counts as a sentence start */
    private fun prevOf(segs: List<Seg>, i: Int): String {
        for (j in i - 1 downTo 0) { val o = segs[j].out; if (o.contains('\n')) return ""; if (!isWs(o)) return o }
        return ""
    }

    private fun nextOut(segs: List<Seg>, i: Int): String {
        for (j in i + 1 until segs.size) if (!isWs(segs[j].out)) return segs[j].out
        return ""
    }

    private fun nextSrc(segs: List<Seg>, i: Int): String {
        for (j in i + 1 until segs.size) { if (isWs(segs[j].out)) continue; return if (segs[j].word) segs[j].src.lowercase() else "" }
        return ""
    }

    private fun nextMa(segs: List<Seg>, i: Int): Boolean {
        for (j in i + 1 until segs.size) { if (isWs(segs[j].out)) continue; return segs[j].word && segs[j].src.lowercase() == "ma" }
        return false
    }

    /** subject-verb agreement (हामी ..छौं / तिमी ..छौ / म ..एँ), म/मा, कि vs की and the other s14 spelling rules (mirrors Transliterator._agree) */
    private fun agree(segs: List<Seg>) {
        val F = lex.freq
        var subj: String? = null; var teach = false; var lastSubj = -9
        for ((i, g) in segs.withIndex()) {
            var t = g.out
            fun put(v: String) {
                g.out = v
                if (v != t) g.alts = listOf(v) + g.alts.filter { it != v }
                t = v
            }
            val srcL = if (g.word) g.src.lowercase() else ""
            if (g.word && srcL == "ma" && (t == "म" || t == "मा")) {
                val prev = prevOf(segs, i); val nx = nextOut(segs, i)
                put(if (!(prev.endsWith("एको") && (nx == "धन्यवाद" || nx == "आभारी")) &&
                    (nx in maNext || nx == "र" || prev == "" || prev in punct || prev in particles || prev in join || verbish.any { prev.endsWith(it) })) "म" else "मा")
            }
            if (g.word && PARA_RE.matches(normRoman(srcL)) && (t == "पर" || t == "परा" || t == "पार" || t == "पारा")) { // s14: "para ma" (manner, पारा) vs "alik para" (far, पर)
                var ns: Seg? = null
                for (j in i + 1 until segs.size) if (!isWs(segs[j].out)) { ns = segs[j]; break }
                if (ns != null && ns.word && ns.src.lowercase() == "ma") put("पारा")
            }
            if (g.word && srcL == "khana" && (t == "खाना" || t == "खान") && nextSrc(segs, i) in khanNext) put("खान") // s14: चिया खान गयौं
            if (g.word && srcL == "baba" && t == "बाबा" && prevOf(segs, i) == "र") put("बुबा")
            if (g.word && srcL == "pani" && (t == "पनि" || t == "पानी")) {
                val prev = prevOf(segs, i); val nxt = nextOut(segs, i)
                if ((prev in PANI_PREV && nxt in PANI_NEXT) || (prev in PANI_PREV2 && nxt in PANI_NEXT2)) put("पानी")
            }
            if (!g.word) { if (t in punct || t.contains('\n')) { subj = null; teach = false }; continue }
            if (t.startsWith("शिक्षक") || t.startsWith("गुरु") || t.startsWith("मास्टर")) teach = true
            if (teach && t.startsWith("पठाउ")) put("पढाउ" + t.substring(4))
            if (t == "र" && subj == "1s" && lastSubj == i - 2) { subj = "1p"; continue } // s14: "ma ra mera sathi ..." = we
            if (t in subj1p) { subj = "1p"; lastSubj = i; continue }
            if (t in subj1s) { subj = "1s"; lastSubj = i; continue }
            if (t in subj2) { subj = "2"; lastSubj = i; continue }
            if (t in reset) { if (nextOut(segs, i) !in notSubjNext) subj = null; continue }
            if (subj == "1p" && (t.endsWith("छौ") || t.endsWith("यौ") || t.endsWith("ेनौ"))) g.out = t + "ँ"
            else if (subj == "2" && (t.endsWith("छौं") || t.endsWith("यौं") || t.endsWith("ेनौं") || t.endsWith("छौँ") || t.endsWith("यौँ") || t.endsWith("ेनौँ"))) g.out = t.dropLast(1)
            else if (subj == "1s" && t.endsWith("ए") && (F[t] ?: 0) >= 3 && (F[t + "ँ"] ?: 0) >= 1) g.out = t + "ँ"
            else if ((subj == "1s" || nextMa(segs, i)) && t.length >= 3 && t.endsWith("े") && t != "ले" && (F[t + "ँ"] ?: 0) >= 1 &&
                (!t.endsWith("ने") || (t[t.length - 3] != '्' && (F[t] ?: 0) < 50))
            ) g.out = t + "ँ" // s14: देखे -> देखेँ, भागे म -> भागेँ म
            else if (subj == "1s" && t.length >= 3 && t.endsWith("थे")) g.out = t + "ँ" // s14: बस्थे -> बस्थेँ
            else if (subj == "1s" && t.endsWith("ये") && (F[t.dropLast(2) + "एँ"] ?: 0) >= 20) g.out = t.dropLast(2) + "एँ"
            if (t == "की" || t == "कि") {
                val prev = prevOf(segs, i); val nx = nextOut(segs, i)
                g.out = if (kiVerb.any { prev.endsWith(it) } || ((prev == "" || prev in punct) && nx in kiSubj)) "कि" else "की"
            }
            if (g.out != t) g.alts = listOf(g.out) + g.alts.filter { it != g.out }
        }
    }

    private val PANI_PREV = setOf("यहाँ", "त्यहाँ", "कहाँ", "धारामा", "तातो", "चिसो", "सफा", "फोहोर")
    private val PANI_NEXT = setOf("छ", "छैन", "थियो", "थिएन", "पर्यो", "पर्‍यो", "पर्छ")
    private val PANI_PREV2 = setOf("आज", "भोलि", "हिजो", "अहिले", "त", "धेरै", "बिहानै")
    private val PANI_NEXT2 = setOf("परेन", "पर्दैन", "परिरहेको", "पर्दै")

    // ---- keyboard (IME) API --------------------------------------------------------------------------

    /** Ranked chips for the word being typed; [joins][i] is true when chip i should be glued to the previous word (Padayog). */
    class Suggestion(val words: List<String>, val joins: BooleanArray, val pending: Boolean = false)

    private fun leftSegs(left: String): ArrayList<Seg> {
        val segs = ArrayList<Seg>()
        val toks = LEFT_TOKEN_RE.findAll(left.takeLast(160)).map { it.value }.toList().takeLast(14)
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
        ensemble?.stale = stale
        try {
            return suggestInner(left, roman, n, byakaran, deep)
        } finally {
            neural.stale = null
            ensemble?.stale = null
        }
    }

    private fun suggestInner(left: String, roman: String, n: Int, byakaran: Boolean, deep: Boolean): Suggestion {
        val segs = leftSegs(left); val i = segs.size
        val sent = segs.any { it.word }
        val first = rankWord(roman, 12, "ne", sent, deep)
        if (first.isEmpty()) return Suggestion(emptyList(), BooleanArray(0))
        segs.add(Seg(roman, first[0].first, true, first.map { it.first }, !lex.freq.containsKey(first[0].first)))
        val rk = HashMap<Int, TlmRanked>(); rk[i] = first
        if (byakaran) {
            val startPrev = HashMap<Int, String>()
            var j = i - 1
            while (j >= 0 && isWs(segs[j].out) && !segs[j].out.contains('\n')) j--
            if (j >= 0 && segs[j].word && segs[j].out.isNotEmpty() && isDeva(segs[j].out[0])) startPrev[i] = segs[j].out
            lmAdjust(segs, rk, startPrev)
            if (ctx != null && (rk[i]?.size ?: 0) >= 2) ctxPass(segs, rk)
            agree(segs)
        }
        val ranked = rk[i] ?: first
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
        val pending = !deep && ensemble != null && !cache.containsKey(cacheKey("ne", sent, normRoman(roman.lowercase())))
        return Suggestion(words, joins, pending)
    }

    /** English/technical tokens (email, app, NEB ...) stay Latin: the literal chip leads for these. */
    fun keepsLatin(t: String) = t.lowercase().trimEnd('\'') in keep || (t.length >= 2 && t == t.uppercase())

    fun transliterate(text: String, byakaran: Boolean = true): String = analyze(text, byakaran).joinToString("") { it.out }
}
