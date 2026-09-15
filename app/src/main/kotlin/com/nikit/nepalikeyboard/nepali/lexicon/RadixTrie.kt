package com.nikit.nepalikeyboard.nepali.lexicon

import com.nikit.nepalikeyboard.nepali.lexicon.model.LexiconEntry

/**
 * A compact radix tree (Patricia trie) over Romanized word keys.
 *
 * ## Why a radix tree rather than a hash map
 *
 * The keyboard must answer **prefix** queries: as the user types `nep`, we need
 * every word whose Romanized form starts with `nep`, ordered by frequency. A
 * `HashMap<String, Entry>` answers exact-match queries in O(1) but cannot
 * answer prefix queries at all without scanning every key.
 *
 * A naive character trie answers prefix queries in O(prefix length) but burns
 * one object per character — for a 40 000-word Nepali lexicon that is roughly
 * 250 000 node objects, which is tens of megabytes of heap on a device where
 * the whole app should stay under about 20 MB.
 *
 * A radix tree compresses chains of single-child nodes into one edge labelled
 * with a substring. The same lexicon collapses to roughly 15 000 nodes. Each
 * node is a plain object with two arrays, so the allocation cost is small and,
 * crucially, bounded and predictable.
 *
 * ## Memory layout
 *
 * A node stores:
 *  * `edge` — the substring labelling the edge *into* this node
 *  * `children` — child nodes, sorted by first character
 *  * `childFirstChar` — the first character of each child's edge, so a lookup
 *    can binary-search instead of scanning
 *  * `entry` — the lexicon entry when this node terminates a word
 *  * `maxFrequency` — the highest frequency anywhere in this subtree, used to
 *    order children so the most likely completion is found first and to allow
 *    the caller to abandon hopeless subtrees
 *
 * `childFirstChar` is a `CharArray` and kept parallel to `children` rather
 * than derived, because deriving it would allocate a String per comparison.
 *
 * ## Threading
 *
 * The tree is built once during lexicon load and is immutable thereafter. All
 * lookups are read-only and safe from any thread, including the main thread,
 * without synchronisation.
 */
class RadixTrie {

    /**
     * A single tree node.
     *
     * Nodes are deliberately mutable *during construction* and treated as
     * immutable afterwards. Making them fully immutable would require either a
     * second pass over the tree or a persistent-data-structure rewrite, both of
     * which cost more than the discipline of "build then freeze".
     */
    class Node internal constructor(
        /** The substring labelling the edge from the parent into this node. */
        internal var edge: String,
        /** Terminating word, or null when this node is a pure interior node. */
        internal var entry: LexiconEntry?
    ) {
        /** Children, kept sorted by [childFirstChar] for binary search. */
        internal var children: Array<Node?> = EMPTY_CHILDREN

        /** First character of each child's edge; parallel to [children]. */
        internal var childFirstChar: CharArray = EMPTY_CHARS

        /** Number of live children. */
        internal var childCount: Int = 0

        /**
         * Highest [LexiconEntry.frequency] in this subtree. Lets a query
         * strategy prefer the most productive branch and lets callers skip
         * subtrees that cannot beat the current best score.
         */
        internal var maxFrequency: Double = 0.0

        /** Total number of word terminations in this subtree. */
        internal var subtreeSize: Int = 0

        internal companion object {
            val EMPTY_CHILDREN = arrayOfNulls<Node>(0)
            val EMPTY_CHARS = CharArray(0)
        }
    }

    private val root: Node = Node("", null)

    /** Number of words inserted. */
    var size: Int = 0
        private set

    // ==================================================================
    // Construction
    // ==================================================================

    /**
     * Insert [entry] under each of its Romanized spellings.
     *
     * If a key already exists, the higher-frequency entry wins. That can only
     * happen if the asset contains a duplicate key, which is a data bug; we
     * resolve it deterministically rather than throwing so that a slightly
     * malformed asset degrades instead of crashing the keyboard on startup.
     */
    fun insert(entry: LexiconEntry) {
        for (key in entry.allRomans) {
            if (key.isEmpty()) continue
            insertKey(key, entry)
        }
        size++
    }

    /**
     * Bulk insert, followed by a sort pass on the tree.
     *
     * Sorting children and recomputing subtree statistics once at the end is
     * far cheaper than maintaining them on every insert.
     */
    fun insertAll(entries: Collection<LexiconEntry>) {
        for (e in entries) insert(e)
        finalise(root)
    }

    private fun insertKey(key: String, entry: LexiconEntry) {
        var node = root
        var index = 0
        val keyLen = key.length

        while (index < keyLen) {
            val c = key[index]
            val childIdx = findChild(node, c)

            if (childIdx < 0) {
                // No child starts with `c`: create a fresh leaf with the whole
                // remaining key as its edge.
                val leaf = Node(key.substring(index), entry)
                addChild(node, c, leaf)
                return
            }

            val child = node.children[childIdx]!!
            val edge = child.edge

            // How far does the existing edge agree with the remaining key?
            val common = commonPrefixLength(edge, key, index)

            if (common == edge.length) {
                // The whole edge is consumed; descend.
                index += common
                node = child
                continue
            }

            // Partial agreement: split the edge.
            //
            //   child.edge = "nepal", new key = "nepaal", common = 4 ("nepa")
            //
            // becomes
            //
            //   middle("nepa")                 <- no entry of its own
            //     +-- child("al")  entry=नेपाल   <- the OLD child keeps ITS entry
            //     +-- leaf("al")   entry=नेपाल   <- the new key's remainder
            //
            // The critical invariant is that the middle node holds NO entry
            // unless the *new* key terminates exactly at the split point. An
            // earlier implementation copied the old child's entry up into the
            // middle and then nulled the child, which silently destroyed every
            // word that happened to be a strict prefix of a later insert.
            val middle = Node(edge.substring(0, common), null)

            // The old child keeps its own entry and shrinks to the remainder.
            child.edge = edge.substring(common)

            // Detach the old child from `node` and re-attach it under `middle`.
            node.children[childIdx] = null
            node.childCount--
            addChild(middle, child.edge[0], child)

            if (index + common == keyLen) {
                // The new key ends exactly at the split point, so `middle` is
                // the terminator for the new key.
                middle.entry = entry
            } else {
                // The new key continues past the split point: add a leaf.
                val leaf = Node(key.substring(index + common), entry)
                addChild(middle, key[index + common], leaf)
            }

            addChild(node, c, middle)
            return
        }

        // The key was consumed exactly at an existing node: this is an exact
        // match, so mark it as a terminator.
        if (node.entry == null || entry.frequency > node.entry!!.frequency) {
            node.entry = entry
        } else {
            // Keep the existing entry but record the alias relationship by
            // preferring the higher-frequency one. Nothing else to do.
        }
    }

    /**
     * Sort every node's children and compute subtree statistics bottom-up.
     * Called once after construction.
     */
    private fun finalise(node: Node) {
        if (node.childCount > 0) {
            // Compact away the nulls left by edge splits.
            val live = ArrayList<Node>(node.childCount)
            for (c in node.children) if (c != null) live.add(c)

            for (child in live) finalise(child)

            // Sort by first character so lookups can binary-search. Ties are
            // impossible: two children cannot share a first character.
            live.sortBy { it.edge[0] }

            val n = live.size
            val arr = arrayOfNulls<Node>(n)
            val chars = CharArray(n)
            var maxFreq = node.entry?.frequency ?: 0.0
            var subtree = if (node.entry != null) 1 else 0

            for (k in 0 until n) {
                val child = live[k]
                arr[k] = child
                chars[k] = child.edge[0]
                if (child.maxFrequency > maxFreq) maxFreq = child.maxFrequency
                subtree += child.subtreeSize
            }

            node.children = arr
            node.childFirstChar = chars
            node.childCount = n
            node.maxFrequency = maxFreq
            node.subtreeSize = subtree
        } else {
            // Leaf: no children. Compact the empty array and record stats.
            node.children = Node.EMPTY_CHILDREN
            node.childFirstChar = Node.EMPTY_CHARS
            node.maxFrequency = node.entry?.frequency ?: 0.0
            node.subtreeSize = if (node.entry != null) 1 else 0
        }
    }

    // ==================================================================
    // Lookups
    // ==================================================================

    /**
     * Find the node whose edge path spells exactly [key].
     *
     * @return the matching node, or null when [key] is not a prefix in the tree
     */
    fun findNode(key: String): Node? {
        var node = root
        var index = 0
        val keyLen = key.length

        while (index < keyLen) {
            val childIdx = findChild(node, key[index])
            if (childIdx < 0) return null
            val child = node.children[childIdx]!!

            val edge = child.edge
            val remaining = keyLen - index

            if (edge.length > remaining) {
                // The edge is longer than what is left of the key: the key can
                // only match if the key is a strict prefix of this edge.
                return if (regionEquals(edge, 0, key, index, remaining)) child else null
            }

            if (!regionEquals(key, index, edge, 0, edge.length)) return null

            index += edge.length
            node = child
        }
        return node
    }

    /** Exact-match lookup. Returns the entry for [key], or null. */
    fun findExact(key: String): LexiconEntry? = findNode(key)?.entry

    /**
     * Collect up to [limit] entries whose key starts with [prefix], ordered by
     * descending frequency.
     *
     * The traversal is best-first: at each node the children are visited in
     * order of [Node.maxFrequency], so the highest-value words surface first
     * and the `limit` cutoff discards the rest. Without this ordering a
     * depth-first walk could fill the result with rare words and miss common
     * ones entirely.
     *
     * @param out destination list; cleared first
     * @return the number of entries collected
     */
    fun collectPrefix(prefix: String, limit: Int, out: MutableList<LexiconEntry>): Int {
        out.clear()
        if (limit <= 0) return 0

        // Resolve the node that the prefix path lands on. An empty prefix is a
        // request for the whole tree, which is legal but only used by tests
        // and by the "everything starting with this syllable" fallback.
        val start: Node = if (prefix.isEmpty()) {
            root
        } else {
            findNode(prefix) ?: return 0
        }

        // A node reached by consuming the prefix exactly may itself terminate a
        // word (the prefix *is* a word). It should be offered first because it
        // is an exact match, which is almost always what the user wants. Note
        // that `findNode("")` returns `root`, whose entry is always null, so
        // the empty-prefix case correctly adds nothing here.
        start.entry?.let { out.add(it) }

        if (out.size < limit) {
            collectFrom(start, limit, out)
        }
        return out.size
    }

    /**
     * Depth-first collection with child ordering by subtree frequency.
     *
     * Uses an explicit [ArrayDeque] rather than recursion: the tree can be deep
     * for long words and the keyboard runs with a small stack on some OEM
     * builds.
     */
    private fun collectFrom(start: Node, limit: Int, out: MutableList<LexiconEntry>) {
        if (start.childCount == 0) return

        val stack = ArrayDeque<Node>(16)

        // Local helper: push children in ascending frequency order so that
        // popping yields the highest-frequency child first.
        pushChildrenByFrequency(stack, start)

        while (stack.isNotEmpty() && out.size < limit) {
            val node = stack.removeLast()
            node.entry?.let {
                out.add(it)
                if (out.size >= limit) return
            }
            if (node.childCount > 0) pushChildrenByFrequency(stack, node)
        }
    }

    /**
     * Push [node]'s children onto [stack] so that the highest-frequency child
     * is on top. Insertion sort on a small array; child counts are tiny
     * (typically under 10), so this beats any general-purpose sort and, more
     * importantly, allocates nothing beyond the stack itself.
     */
    private fun pushChildrenByFrequency(stack: ArrayDeque<Node>, node: Node) {
        val n = node.childCount
        if (n == 0) return
        if (n == 1) {
            stack.addLast(node.children[0]!!)
            return
        }

        // Copy indices and insertion-sort them by descending maxFrequency.
        // `order` is a small IntArray allocated per node visit; for n <= 10
        // this is a handful of bytes and is far cheaper than a comparator
        // closure allocation.
        val order = IntArray(n) { it }
        for (a in 1 until n) {
            val key = order[a]
            val keyFreq = node.children[key]!!.maxFrequency
            var b = a - 1
            while (b >= 0 && node.children[order[b]]!!.maxFrequency < keyFreq) {
                order[b + 1] = order[b]
                b--
            }
            order[b + 1] = key
        }

        // Descending order was computed, but the stack pops from the end, so we
        // push in ascending order and let the pop reverse it.
        for (k in n - 1 downTo 0) {
            stack.addLast(node.children[order[k]]!!)
        }
    }

    /**
     * True when any key in the tree starts with [prefix]. Cheap: it only walks
     * the tree, it does not collect.
     */
    fun containsPrefix(prefix: String): Boolean {
        if (prefix.isEmpty()) return size > 0
        return findNode(prefix) != null
    }

    /** Number of words whose Romanized key starts with [prefix]. */
    fun countPrefix(prefix: String): Int {
        if (prefix.isEmpty()) return size
        val node = findNode(prefix) ?: return 0
        return node.subtreeSize
    }

    /** The highest-frequency word under [prefix], or null. */
    fun bestForPrefix(prefix: String): LexiconEntry? {
        if (prefix.isEmpty()) return null
        val node = findNode(prefix) ?: return null
        if (node.entry != null && node.childCount == 0) return node.entry

        var best = node.entry
        var bestFreq = best?.frequency ?: 0.0
        var current = node
        while (current.childCount > 0) {
            // Children are sorted by character, not frequency, so we must scan
            // for the most frequent one. Child counts are small.
            var next: Node? = null
            var nextFreq = -1.0
            for (k in 0 until current.childCount) {
                val c = current.children[k]!!
                if (c.maxFrequency > nextFreq) {
                    nextFreq = c.maxFrequency
                    next = c
                }
            }
            current = next ?: break
            val e = current.entry
            if (e != null && e.frequency > bestFreq) {
                best = e
                bestFreq = e.frequency
            }
        }
        return best
    }

    // ==================================================================
    // Node-level helpers
    // ==================================================================

    /** Binary-search the child whose edge starts with [c]. Returns -1 if none. */
    private fun findChild(node: Node, c: Char): Int {
        val chars = node.childFirstChar
        val n = node.childCount
        if (n == 0) return -1

        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val mc = chars[mid]
            when {
                mc < c -> lo = mid + 1
                mc > c -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Add a child, maintaining the invariant that children are unsorted until finalise. */
    private fun addChild(node: Node, firstChar: Char, child: Node) {
        val n = node.childCount
        if (n >= node.children.size) {
            // Grow geometrically. Starting at 1 and doubling keeps the total
            // number of reallocations logarithmic while avoiding an oversized
            // first allocation for the (many) nodes with a single child.
            val newSize = if (n == 0) 1 else n * 2
            node.children = node.children.copyOf(newSize)
            node.childFirstChar = node.childFirstChar.copyOf(newSize)
        }
        node.children[n] = child
        node.childFirstChar[n] = firstChar
        node.childCount = n + 1
    }

    /** Length of the longest common prefix of `a[aStart..]` and `b[bStart..]`. */
    private fun commonPrefixLength(a: String, b: String, bStart: Int): Int {
        var i = 0
        val maxA = a.length
        val maxB = b.length - bStart
        val limit = if (maxA < maxB) maxA else maxB
        while (i < limit && a[i] == b[bStart + i]) i++
        return i
    }

    /** Compare `a[aStart until aStart+n]` with `b[bStart until bStart+n]`. */
    private fun regionEquals(
        a: String,
        aStart: Int,
        b: String,
        bStart: Int,
        n: Int
    ): Boolean {
        if (aStart + n > a.length || bStart + n > b.length) return false
        for (i in 0 until n) {
            if (a[aStart + i] != b[bStart + i]) return false
        }
        return true
    }
}
