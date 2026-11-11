import np.com.nepalikeyboard.tlm.*
import org.json.*
import java.io.File

class TNeural(val n: Neural) : Neural {
    var enc = 0L; var sc = 0L; var bm = 0L; var nb = 0; var ns = 0
    override fun encode(word: String, lang: String): Any { val a = System.nanoTime(); val r = n.encode(word, lang); enc += System.nanoTime() - a; return r }
    override fun score(enc: Any, cands: List<String>): FloatArray { val a = System.nanoTime(); val r = n.score(enc, cands); sc += System.nanoTime() - a; ns++; return r }
    override fun beam(enc: Any, k: Int): List<Pair<String, Float>> { val a = System.nanoTime(); val r = n.beam(enc, k); bm += System.nanoTime() - a; nb++; return r }
    override var stale: (() -> Boolean)? = null
        set(v) { field = v; n.stale = v }
}
class TCtx(val c: CtxScorer) : CtxScorer {
    var t = 0L; var n = 0
    override val vocab get() = c.vocab
    override fun score(win: IntArray, cand: IntArray): FloatArray { val a = System.nanoTime(); val r = c.score(win, cand); t += System.nanoTime() - a; n++; return r }
}
fun strs(a: JSONArray) = List(a.length()) { a.getString(it) }

fun main(args: Array<String>) {
    val dir = "app/src/main/assets/tlm/"
    fun t(n: String) = File(dir + n).readText()
    val cfg = JSONObject(t("config.json")); val vocab = JSONObject(t("vocab.json")); val m = cfg.getJSONObject("cfg")
    val kn = KtNeural(File(dir + "s2s.tlmw").readBytes(), strs(vocab.getJSONArray("src")), strs(vocab.getJSONArray("tgt")), cfg.getInt("max_src"), m.getInt("d"), m.getInt("h"), m.getInt("enc"), m.getInt("dec"))
    val neural = TNeural(kn)
    val lex = TlmLexicon(t("lex_ne.tsv"))
    val direct = HashMap<String, List<String>>()
    for (line in t("direct_ne.tsv").lineSequence()) { val p = line.split('\t'); if (p.size >= 2) direct[p[0]] = p.drop(1) }
    val suffixes = ArrayList<Pair<String, String>>(); cfg.optJSONObject("suffixes")?.let { o -> val it = o.keys(); while (it.hasNext()) { val k = it.next(); suffixes.add(k to o.getString(k)) } }
    val join = cfg.optJSONArray("join")?.let { strs(it).toSet() } ?: emptySet()
    val keep = strs(cfg.getJSONArray("keep")).toSet()
    val words = t("lex_ne.tsv").lineSequence().drop(200).take(4000).map { it.split('\t')[0] }.toList()
    val ctx = TCtx(KtCtx(File(dir + "ctx.tlmw").readBytes(), t("ctx_vocab.txt").split('\n').dropLastWhile { it.isEmpty() }, cfg.optInt("ctx_heads", 4)))
    val eng = HybridEngine(neural, lex, direct, keep, suffixes, join, paramsFromConfig(cfg.optJSONObject("hybrid") ?: JSONObject()), ctx)
    val romans = File("/tmp/jv/romans.txt").readLines()
    println("pool candidates sample, romans=${romans.size}")
    // warm
    for (r in romans.take(150)) eng.suggest("मेरो ", r, 6, true, false)
    neural.enc = 0; neural.sc = 0; neural.bm = 0; neural.nb = 0; neural.ns = 0; ctx.t = 0; ctx.n = 0
    val lat = ArrayList<Long>()
    for (r in romans.drop(150)) {
        val a = System.nanoTime(); eng.suggest("मेरो ", r, 6, true, false); lat.add(System.nanoTime() - a)
    }
    lat.sort()
    fun us(x: Long) = x / 1000
    println("n=${lat.size} p50=${us(lat[lat.size/2])}us p90=${us(lat[lat.size*9/10])}us p99=${us(lat[lat.size*99/100])}us max=${us(lat.last())}us avg=${us(lat.sum()/lat.size)}us")
    println("enc/call ${us(neural.enc / lat.size)}us  score/call ${us(neural.sc / lat.size)}us (n=${neural.ns})  ctx/call ${us(ctx.t / lat.size)}us (n=${ctx.n})")
    val bl = ArrayList<Long>()
    for (r in romans.drop(150).take(60)) { val a = System.nanoTime(); eng.suggest("मेरो ", r, 6, true, true); bl.add(System.nanoTime() - a) }
    println("deep after shallow avg=${us(bl.sum()/bl.size)}us beams=${neural.nb} beam avg=${if (neural.nb>0) us(neural.bm/neural.nb) else 0}us")
}
