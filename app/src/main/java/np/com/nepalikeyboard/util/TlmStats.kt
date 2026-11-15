package np.com.nepalikeyboard.util

/**
 * Rolling end-to-end keystroke latency of the TinyLM pipeline: from the key press handing a request to the worker until
 * the candidates for it exist (queue wait + model). Shown in the settings app so regressions are visible on-device.
 */
object TlmStats {
    private const val SIZE = 64
    private val ring = LongArray(SIZE)
    private var count = 0
    private val deepRing = LongArray(SIZE)
    private var deepCount = 0

    /** Time from key press until the full ensemble answer (the shallow answer is already on screen by then). */
    fun recordDeep(nanos: Long) {
        synchronized(this) {
            deepRing[deepCount % SIZE] = nanos
            deepCount++
        }
    }

    fun record(nanos: Long) {
        synchronized(this) {
            ring[count % SIZE] = nanos
            count++
        }
    }

    /** "p50 7 ms - p95 18 ms (64 keys)" or null before the first keystroke. */
    fun summary(): String? {
        val copy: LongArray
        synchronized(this) {
            val n = minOf(count, SIZE)
            if (n == 0) return null
            copy = ring.copyOf(n)
        }
        copy.sort()
        val p50 = copy[copy.size / 2] / 1_000_000
        val p95 = copy[(copy.size * 95 / 100).coerceAtMost(copy.size - 1)] / 1_000_000
        val deep: LongArray
        synchronized(this) { deep = deepRing.copyOf(minOf(deepCount, SIZE)) }
        val suffix = if (deep.isEmpty()) "" else {
            deep.sort()
            "; ensemble p50 ${deep[deep.size / 2] / 1_000_000} ms, p95 ${deep[(deep.size * 95 / 100).coerceAtMost(deep.size - 1)] / 1_000_000} ms"
        }
        return "p50 $p50 ms, p95 $p95 ms over ${copy.size} keys$suffix"
    }
}
