package np.com.nepalikeyboard.tlm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the on-device TinyLM [HybridEngine] from `assets/tlm/`:
 *
 *  * `s2s.tlmw` - stage 1 character seq2seq transducer (1.9M params, int8) for the pure-Kotlin core in `TlmNet.kt`
 *  * `ctx.tlmw` + `ctx_vocab.txt` - stage 2 sentence-context scorer (homophones, म/मा, पनि/पानी ...)
 *  * `lex_ne.tsv` - 119k word frequency lexicon, `direct_ne.tsv` - slang / direct table
 *
 * No native code is involved (ONNX Runtime was removed to shrink the APK by ~60 MB), so loading cannot crash the process.
 * Call [load] once per process from a background thread.
 */
object TlmLoader {

    private fun ((String) -> ByteArray).text(name: String): String = String(this(name), Charsets.UTF_8)

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    /**
     * [read] returns the bytes of a file under `assets/tlm/` (Android assets in the app, plain files in the JVM harness).
     * Phase 1 (this call) builds the engine around the main model only so typing works immediately;
     * [attachExtras] then adds the m2 + a4kd ensemble members and the stem-bigram LM exactly like the web app.
     * [full] = true does both in one blocking call (JVM harness).
     */
    fun load(read: (String) -> ByteArray, full: Boolean = true): HybridEngine {
        val cfg = JSONObject(read.text("config.json"))
        val vocab = JSONObject(read.text("vocab.json"))
        val m = cfg.getJSONObject("cfg")
        val neural = KtNeural(
            read("s2s.tlmw"),
            vocab.getJSONArray("src").strings(), vocab.getJSONArray("tgt").strings(),
            cfg.getInt("max_src"), m.getInt("d"), m.getInt("h"), m.getInt("enc"), m.getInt("dec"),
            lower = vocab.getJSONArray("src").length() < 50,
        )

        val lex = TlmLexicon(read.text("lex_ne.tsv"))
        val direct = HashMap<String, List<String>>()
        val definitive = HashSet<String>()
        for (line in read.text("direct_ne.tsv").lineSequence()) {
            val p = line.split('\t')
            if (p.size >= 2) {
                val v = p.drop(1).toMutableList()
                // '!' = definitive entry (Stage 2 never overrides it)
                if (v[0].startsWith("!")) { v[0] = v[0].substring(1); definitive.add(p[0]) }
                direct[p[0]] = v
            }
        }

        val sufObj = cfg.optJSONObject("suffixes")
        val suffixes = ArrayList<Pair<String, String>>()
        if (sufObj != null) {
            val it = sufObj.keys()
            while (it.hasNext()) {
                val k = it.next()
                suffixes.add(k to sufObj.getString(k))
            }
        }
        val join = cfg.optJSONArray("join")?.strings()?.toSet() ?: emptySet()
        val keep = cfg.getJSONArray("keep").strings().toSet()
        val params = paramsFromConfig(cfg.optJSONObject("hybrid") ?: JSONObject())

        var ctx: CtxScorer? = null
        if (cfg.optBoolean("ctx", false)) {
            val words = read.text("ctx_vocab.txt").split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
            ctx = KtCtx(read("ctx.tlmw"), words, cfg.optInt("ctx_heads", 4))
        }
        val engine = HybridEngine(neural, lex, direct, keep, suffixes, join, params, ctx)
        engine.definitive = definitive
        mainNeural = neural
        if (full) attachExtras(engine, read)
        // Warm the JIT so the first real keystroke is fast.
        engine.suggest("राम ", "namaste", deep = false)
        engine.suggest("", "ma", deep = false)
        return engine
    }

    @Volatile private var mainNeural: KtNeural? = null

    /** Load problems of the optional parts (ensemble members, stem LM); empty when everything loaded. */
    val notes: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /** Adds the ensemble members listed in config.json (`ensemble`) and the compact LM (`lm_file`). Every part is optional and failure-tolerant. */
    fun attachExtras(engine: HybridEngine, read: (String) -> ByteArray) {
        val cfg = JSONObject(read.text("config.json"))
        val main = mainNeural
        try {
            val subs = cfg.optJSONArray("ensemble")?.strings().orEmpty()
            if (main != null && subs.isNotEmpty()) {
                val members = ArrayList<KtNeural>()
                members.add(main)
                for (sub in subs) {
                    val sc = JSONObject(read.text("${sub}_config.json"))
                    val sv = JSONObject(read.text("${sub}_vocab.json"))
                    val c = sc.getJSONObject("cfg")
                    members.add(
                        KtNeural(
                            read("$sub.tlmw"), sv.getJSONArray("src").strings(), sv.getJSONArray("tgt").strings(),
                            sc.getInt("max_src"), c.getInt("d"), c.getInt("h"), c.getInt("enc"), c.getInt("dec"),
                            lower = sv.getJSONArray("src").length() < 50,
                        ),
                    )
                }
                engine.ensemble = EnsembleNeural(members)
            }
        } catch (t: Throwable) {
            engine.ensemble = null
            notes.add("ensemble load failed: $t")
        }
        try {
            val lmFile = cfg.optString("lm_file", "")
            if (lmFile.isNotEmpty()) engine.lm = CompactLM(read.text(lmFile))
        } catch (t: Throwable) {
            engine.lm = null
            notes.add("stem LM load failed: $t")
        }
    }

    private val WARMUP_WORDS = listOf(
        "ma", "ho", "chha", "ra", "ko", "ka", "ki", "le", "lai", "ma", "hami", "timi", "tapai", "k", "ke", "kina", "kasari",
        "ramro", "namaste", "dhanyabad", "nepal", "ghar", "school", "aaja", "bholi", "hijo", "pani", "tara", "ani", "yo", "tyo",
        "jaane", "garne", "bhayo", "hunchha", "chhaina", "xa", "xaina", "khana", "kaam", "dherai", "thorai", "maya", "sathi",
        "bhai", "dai", "didi", "aama", "buwa", "kati", "kahile", "kaha", "kun", "kasto", "mero", "timro", "hamro", "tapaiko",
        "usle", "maile", "sanga", "bata", "dekhi", "samma", "sabai", "kehi", "aru", "naya", "thulo", "sano", "thik", "hajur",
        "ok", "hello", "thank", "you", "please", "sorry", "love", "friend", "good", "morning", "night", "bye", "kathmandu",
        "pokhara", "computer", "mobile", "phone", "internet", "facebook", "message", "call", "video", "photo", "gaun", "sahar",
        "desh", "bhasha", "kitab", "paisa", "gari", "bari", "paani", "dudh", "chiya", "bhat", "dal", "tarkari", "garnu",
        "hunuhunchha", "bhanera", "chahinchha", "pardaina", "sakchhu", "janchhu", "aauchhu", "khanchhu", "padhchhu",
    )

    /**
     * Ranks the most common typed words once, in the background, so (a) ART's JIT has compiled the inference kernels
     * before the first real keystroke and (b) those words are already in the engine's LRU cache.
     */
    fun warmup(engine: HybridEngine, pauseMs: Long = 6L, deepToo: Boolean = true, stop: () -> Boolean = { false }) {
        var left = ""
        for (word in WARMUP_WORDS) {
            if (stop()) return
            // Never hold the engine lock against a real keystroke: wait until the user pauses.
            while (TlmTuning.userIsTyping()) {
                if (stop()) return
                try {
                    Thread.sleep(150L)
                } catch (_: InterruptedException) {
                    return
                }
            }
            try {
                engine.suggest(left, word, 6, true, false)
                if (deepToo && engine.ensemble != null) engine.suggest(left, word, 6, true, true)
            } catch (_: Throwable) {
                // Warm-up is best effort.
            }
            left = if (left.isEmpty()) "म " else ""
            try {
                Thread.sleep(pauseMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}
