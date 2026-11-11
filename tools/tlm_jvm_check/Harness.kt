import np.com.nepalikeyboard.tlm.*
import org.json.*
import java.io.File

fun strs(a: JSONArray) = List(a.length()) { a.getString(it) }

fun main(args: Array<String>) {
    val dir = (args.firstOrNull() ?: "app/src/main/assets/tlm") + "/"
    fun t(n: String) = File(dir + n).readText()
    val t0 = System.nanoTime()
    val eng = TlmLoader.load { File(dir + it).readBytes() }
    println("load ms = ${(System.nanoTime() - t0) / 1_000_000}")
    eng.suggest("राम ", "namaste")
    val sents = listOf("ma ghar ma chha", "ma ghar jaanchhu ra ram ko kitab padhchhu", "hami school jaanchhau", "timi kata jaanchhau", "tapai le k garnu hunchha", "nepal ko rajdhani kathmandu ho", "mero naam sita ho ra ma kathmandu ma baschhu", "aaja ko din dherai ramro chha", "usle bhanyo ki ma pani aauchhu", "pani khanu paryo", "ram ra sita bazar gaye", "mobile ma facebook chalaunu", "sahar bata gaun samma")
    var tot = 0L; var n = 0
    for (sn in sents) {
        var left = ""
        for (w in sn.split(" ")) {
            val a = System.nanoTime()
            val s2 = eng.suggest(left, w, 6)
            tot += System.nanoTime() - a; n++
            val top = s2.words.firstOrNull() ?: w
            if (s2.joins.firstOrNull() == true && left.endsWith(" ")) left = left.dropLast(1)
            left += top + " "
        }
        println("IME : " + left.trim())
        println("BULK: " + eng.transliterate(sn))
    }
    println("avg suggest ms = ${tot / n / 1_000_00 / 10.0}")
}
