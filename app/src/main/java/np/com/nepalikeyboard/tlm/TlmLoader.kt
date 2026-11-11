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

    /** [read] returns the bytes of a file under `assets/tlm/` (Android assets in the app, plain files in the JVM harness). */
    fun load(read: (String) -> ByteArray): HybridEngine {
        val cfg = JSONObject(read.text("config.json"))
        val vocab = JSONObject(read.text("vocab.json"))
        val m = cfg.getJSONObject("cfg")
        val neural = KtNeural(
            read("s2s.tlmw"),
            vocab.getJSONArray("src").strings(), vocab.getJSONArray("tgt").strings(),
            cfg.getInt("max_src"), m.getInt("d"), m.getInt("h"), m.getInt("enc"), m.getInt("dec"),
        )

        val lex = TlmLexicon(read.text("lex_ne.tsv"))
        val direct = HashMap<String, List<String>>()
        for (line in read.text("direct_ne.tsv").lineSequence()) {
            val p = line.split('\t')
            if (p.size >= 2) direct[p[0]] = p.drop(1)
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
        // Warm the JIT so the first real keystroke is fast.
        engine.suggest("राम ", "namaste")
        engine.suggest("", "ma")
        return engine
    }
}
