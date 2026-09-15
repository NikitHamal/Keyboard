package np.com.nepalikeyboard.engine

/**
 * A compact radix tree (character trie) stored in flat primitive arrays.
 *
 * Why not `HashMap<Char, Node>` per node? A 500-word Nepali lexicon plus its
 * roman keys produces a few thousand nodes; per-node hash maps would cost
 * hundreds of kilobytes of headers and pointer chasing. This layout stores the
 * whole tree in six parallel arrays:
 *
 * ```
 * childChars  [ 'a' 'b' 'c' | 'a' 'h' | ... ]   sorted per parent
 * childNodes  [  12   7   3  |  9   4   | ... ]   parallel node indexes
 * childStart  [ 0, 3, 5, ... ]                    slice start per node
 * childCount  [ 3, 2, 1, ... ]                    slice length per node
 * terminal    [ -1, 6, -1, ... ]                  payload index or NONE
 * ```
 *
 * Children are stored sorted by character, which turns lookup into a 3-step
 * binary search over a contiguous cache line - no boxing, no allocation, and
 * safe for unsynchronised concurrent reads once built.
 *
 * The trie is built once on `Dispatchers.Default` at startup and never mutated
 * afterwards, so every consumer (candidate worker) may read it lock free.
 */
internal class CompactTrie private constructor(
    @JvmField val childChars: CharArray,
    @JvmField val childNodes: IntArray,
    @JvmField val childStart: IntArray,
    @JvmField val childCount: IntArray,
    @JvmField val terminalPayload: IntArray,
    @JvmField val nodeCount: Int,
) {

    val size: Int get() = nodeCount

    /**
     * Follows [key] from [start] to [end] (exclusive) and returns the resulting
     * node, or [NONE] when the key is not a prefix of anything in the trie.
     */
    fun walk(key: CharSequence, start: Int = 0, end: Int = key.length): Int {
        var node = ROOT
        var index = start
        while (index < end) {
            node = childOf(node, key[index])
            if (node == NONE) return NONE
            index++
        }
        return node
    }

    /** Binary search for a single child transition. */
    fun childOf(node: Int, ch: Char): Int {
        var low = childStart[node]
        var high = low + childCount[node] - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = childChars[mid]
            when {
                candidate == ch -> return childNodes[mid]
                candidate < ch -> low = mid + 1
                else -> high = mid - 1
            }
        }
        return NONE
    }

    /** Payload stored at [node], or [NONE] when the node does not terminate a key. */
    fun payloadOf(node: Int): Int = if (node == NONE) NONE else terminalPayload[node]

    fun hasChildren(node: Int): Boolean = childCount[node] > 0

    companion object {
        const val NONE: Int = -1
        const val ROOT: Int = 0
    }
}

/**
 * Build-time companion for [CompactTrie].
 *
 * Uses hash maps while inserting (fast, allocation-friendly during a one-off
 * background build) and then flattens into primitive arrays. The builder is
 * discarded immediately after [build], so its memory is reclaimed by the next GC.
 */
internal class CompactTrieBuilder(estimatedKeys: Int = 512) {

    private class Node {
        @JvmField val children: HashMap<Char, Int> = HashMap(4)
        @JvmField var payload: Int = CompactTrie.NONE
    }

    private val nodes = ArrayList<Node>(estimatedKeys * 2)

    init {
        nodes.add(Node()) // root = index 0
    }

    val nodeCount: Int get() = nodes.size

    /** Inserts [key], mapping it to [payload]. Later inserts overwrite earlier ones. */
    fun insert(key: CharSequence, payload: Int) {
        if (key.isEmpty()) return
        var nodeIndex = CompactTrie.ROOT
        for (index in key.indices) {
            val ch = key[index]
            val existing = nodes[nodeIndex].children[ch]
            if (existing != null) {
                nodeIndex = existing
            } else {
                val fresh = nodes.size
                nodes.add(Node())
                nodes[nodeIndex].children[ch] = fresh
                nodeIndex = fresh
            }
        }
        nodes[nodeIndex].payload = payload
    }

    fun build(): CompactTrie {
        val count = nodes.size
        val starts = IntArray(count)
        val counts = IntArray(count)
        var total = 0
        for (index in 0 until count) {
            val childSize = nodes[index].children.size
            starts[index] = total
            counts[index] = childSize
            total += childSize
        }
        val chars = CharArray(total)
        val childNodes = IntArray(total)
        val payloads = IntArray(count) { CompactTrie.NONE }
        for (index in 0 until count) {
            val node = nodes[index]
            payloads[index] = node.payload
            if (node.children.isEmpty()) continue
            // Sorting by character enables binary search at query time.
            val sortedKeys = node.children.keys.toCharArray()
            java.util.Arrays.sort(sortedKeys)
            var offset = starts[index]
            for (key in sortedKeys) {
                chars[offset] = key
                childNodes[offset] = node.children.getValue(key)
                offset++
            }
        }
        return CompactTrie(chars, childNodes, starts, counts, payloads, count)
    }
}
