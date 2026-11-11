package np.com.nepalikeyboard.tlm

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the on-device TinyLM [HybridEngine] from `assets/tlm/`:
 *
 *  * `encoder/decoder.int8.onnx` - stage 1 character seq2seq transducer (1.9M params, int8)
 *  * `ctx.int8.onnx` + `ctx_vocab.txt` - stage 2 sentence-context scorer (homophones, म/मा, पनि/पानी ...)
 *  * `lex_ne.tsv` - 119k word frequency lexicon, `direct_ne.tsv` - slang / direct table
 *
 * Call [load] once per process from a background thread (takes a few hundred ms).
 */
object TlmLoader {

    private fun Context.bytes(name: String): ByteArray = assets.open("tlm/$name").use { it.readBytes() }

    private fun Context.text(name: String): String = String(bytes(name), Charsets.UTF_8)

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    fun load(context: Context): HybridEngine {
        val cfg = JSONObject(context.text("config.json"))
        val vocab = JSONObject(context.text("vocab.json"))
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val enc = env.createSession(context.bytes("encoder.int8.onnx"), opts)
        val dec = env.createSession(context.bytes("decoder.int8.onnx"), opts)
        val neural = OrtNeural(
            env, enc, dec,
            vocab.getJSONArray("src").strings(), vocab.getJSONArray("tgt").strings(),
            cfg.getInt("max_src"), cfg.getInt("d"),
        )

        val lex = TlmLexicon(context.text("lex_ne.tsv"))
        val direct = HashMap<String, List<String>>()
        for (line in context.text("direct_ne.tsv").lineSequence()) {
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
            val words = context.text("ctx_vocab.txt").split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
            ctx = OrtCtx(env, env.createSession(context.bytes("ctx.int8.onnx"), opts), words)
        }
        val engine = HybridEngine(neural, lex, direct, keep, suffixes, join, params, ctx)
        // Warm the JIT and the ORT kernels so the first real keystroke is fast.
        engine.suggest("राम ", "namaste")
        engine.suggest("", "ma")
        return engine
    }
}
