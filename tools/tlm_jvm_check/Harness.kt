import ai.onnxruntime.*
import np.com.nepalikeyboard.tlm.*
import org.json.*
import java.io.File

fun strs(a: JSONArray) = List(a.length()) { a.getString(it) }

fun main(args: Array<String>) {
    val dir = "/tmp/kb/app/src/main/assets/tlm/"
    fun t(n: String) = File(dir + n).readText()
    val cfg = JSONObject(t("config.json")); val vocab = JSONObject(t("vocab.json"))
    val env = OrtEnvironment.getEnvironment()
    val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
    val t0 = System.nanoTime()
    val enc = env.createSession(File(dir + "encoder.int8.onnx").readBytes(), opts)
    val dec = env.createSession(File(dir + "decoder.int8.onnx").readBytes(), opts)
    val neural = OrtNeural(env, enc, dec, strs(vocab.getJSONArray("src")), strs(vocab.getJSONArray("tgt")), cfg.getInt("max_src"), cfg.getInt("d"))
    val lex = TlmLexicon(t("lex_ne.tsv"))
    val direct = HashMap<String, List<String>>()
    for (l in t("direct_ne.tsv").lineSequence()) { val p = l.split('\t'); if (p.size >= 2) direct[p[0]] = p.drop(1) }
    val so = cfg.getJSONObject("suffixes"); val suf = ArrayList<Pair<String, String>>(); val it = so.keys(); while (it.hasNext()) { val k = it.next(); suf.add(k to so.getString(k)) }
    val join = strs(cfg.getJSONArray("join")).toSet(); val keep = strs(cfg.getJSONArray("keep")).toSet()
    val words = t("ctx_vocab.txt").split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
    val ctx = OrtCtx(env, env.createSession(File(dir + "ctx.int8.onnx").readBytes(), opts), words)
    val eng = HybridEngine(neural, lex, direct, keep, suf, join, paramsFromConfig(cfg.getJSONObject("hybrid")), ctx)
    println("load ms = ${(System.nanoTime() - t0) / 1_000_000}")
    eng.suggest("राम ", "namaste")
    val sents = listOf("ma ghar ma chha", "ma ghar jaanchhu ra ram ko kitab padhchhu", "hami school jaanchhau", "timi kata jaanchhau", "tapai le k garnu hunchha", "nepal ko rajdhani kathmandu ho", "mero naam sita ho ra ma kathmandu ma baschhu", "aaja ko din dherai ramro chha", "usle bhanyo ki ma pani aauchhu", "pani khanu paryo", "ram ra sita bazar gaye", "mobile ma facebook chalaunu", "sahar bata gaun samma")
    for (sn in sents) {
        var left = ""; val sb = StringBuilder()
        for (w in sn.split(" ")) {
            val s2 = eng.suggest(left, w, 6)
            var top = s2.words.firstOrNull() ?: w
            if (s2.joins.firstOrNull() == true && left.endsWith(" ")) left = left.dropLast(1)
            left += top + " "
        }
        println("IME : " + left.trim())
        println("BULK: " + eng.transliterate(sn))
    }
}
