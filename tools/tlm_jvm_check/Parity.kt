import np.com.nepalikeyboard.tlm.*
import java.io.File
fun main(args: Array<String>) {
    val dir = "app/src/main/assets/tlm/"
    val eng = TlmLoader.load { File(dir + it).readBytes() }
    val sb = StringBuilder()
    val lefts = listOf("", "मेरो ", "म ", "हामी ", "तपाईं ")
    for ((i, r) in File("/tmp/jv/romans.txt").readLines().withIndex()) {
        val left = lefts[i % lefts.size]
        val s = eng.suggest(left, r, 6, true, true)
        sb.append(left).append('|').append(r).append('=').append(s.words.joinToString(",")).append('|').append(s.joins.joinToString("") { if (it) "1" else "0" }).append('\n')
    }
    File(args[0]).writeText(sb.toString())
}
