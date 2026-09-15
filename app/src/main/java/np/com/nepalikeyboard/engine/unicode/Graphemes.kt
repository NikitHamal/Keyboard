package np.com.nepalikeyboard.engine.unicode

/**
 * Allocation-free grapheme cluster navigation.
 *
 * Android's `InputConnection.deleteSurroundingText` counts UTF-16 units, so a
 * naive "delete one character" splits surrogate pairs (emoji) and destroys
 * Devanagari clusters (म + ा + त + ् + र). Every mutation in this app therefore
 * walks cluster boundaries with these helpers and converts the result back into
 * a UTF-16 length before touching the InputConnection.
 *
 * This is deliberately a hand-rolled implementation rather than
 * `java.text.BreakIterator`: BreakIterator allocates per call, is not documented
 * as thread safe, and does not implement the Indic conjunct (virama) rule that
 * Devanagari typesetting depends on.
 */
object Graphemes {

    private const val MAX_BOUNDARY_SCAN = 512

    /**
     * Start index of the cluster that ends at [from].
     * Returns 0 when [from] is already at the start of the text.
     */
    fun prevBoundary(text: CharSequence, from: Int): Int = prevBoundary(text, from, 0)

    fun prevBoundary(text: CharSequence, from: Int, floor: Int): Int {
        var index = if (from > text.length) text.length else from
        if (index <= floor) return floor
        index = stepBack(text, index)
        var guard = 0
        while (index > floor && guard++ < MAX_BOUNDARY_SCAN) {
            val previous = text[index - 1]
            when {
                Devanagari.isCombiningMark(previous) -> index = stepBack(text, index)
                previous == Devanagari.ZWJ || previous == Devanagari.ZWNJ -> index = stepBack(text, index)
                isRegionalIndicatorTail(text, index, floor) -> index = stepBack(text, index)
                isVariationSelector(previous) -> index = stepBack(text, index)
                else -> return index
            }
        }
        return index
    }

    /**
     * End index (exclusive) of the cluster that starts at [from].
     * Returns [to] when [from] is already at the end of the text.
     */
    fun nextBoundary(text: CharSequence, from: Int, to: Int = text.length): Int {
        if (from >= to) return to
        var index = stepForward(text, from, to)
        var guard = 0
        while (index < to && guard++ < MAX_BOUNDARY_SCAN) {
            val current = text[index]
            if (Devanagari.isCombiningMark(current) || current == Devanagari.ZWJ || current == Devanagari.ZWNJ) {
                index = stepForward(text, index, to)
                continue
            }
            if (isVariationSelector(current)) {
                index = stepForward(text, index, to)
                continue
            }
            // Emoji keycap sequence (digit + FE0F + 20E3) and flags (RI pairs)
            // are consumed by the mark checks above; regional indicators are
            // handled explicitly here so two-RI flags stay atomic.
            if (isRegionalIndicator(current) && isRegionalIndicatorAt(text, index - 2)) {
                index = stepForward(text, index, to)
                continue
            }
            break
        }
        return index
    }

    /**
     * Start index of the previous word, skipping any run of whitespace first.
     * Used by swipe-to-delete: one gesture step removes one whole word.
     */
    fun prevWordBoundary(text: CharSequence, from: Int): Int {
        var index = from.coerceAtMost(text.length)
        // 1. skip trailing whitespace
        while (index > 0 && isWhitespace(text[index - 1])) index--
        // 2. skip the word itself, cluster by cluster
        while (index > 0) {
            val clusterStart = prevBoundary(text, index)
            if (clusterStart == index) break
            val first = firstCodePointChar(text, clusterStart)
            if (!Devanagari.isWordChar(first)) break
            index = clusterStart
        }
        return index
    }

    /** End index (exclusive) of the next word, cluster aligned. */
    fun nextWordBoundary(text: CharSequence, from: Int): Int {
        var index = from
        while (index < text.length && !Devanagari.isWordChar(firstCodePointChar(text, index))) index++
        while (index < text.length && Devanagari.isWordChar(firstCodePointChar(text, index))) {
            index = nextBoundary(text, index)
        }
        return index
    }

    /**
     * UTF-16 length of the last [count] clusters of `text[0, from)`.
     * Callers use this to build the arguments of
     * `InputConnection.deleteSurroundingText(length, 0)`.
     */
    fun clusterSpan(text: CharSequence, from: Int, count: Int): Int {
        var index = from.coerceAtMost(text.length)
        val target = index
        var steps = 0
        while (steps < count && index > 0) {
            index = prevBoundary(text, index)
            steps++
        }
        return target - index
    }

    fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\n' || c == '\t' || c == '\u00A0' || c == '\u2009'

    fun isRegionalIndicator(c: Char): Boolean = c.code in 0xD83C..0xD83F

    fun isVariationSelector(c: Char): Boolean = c.code == 0xFE0F || c.code == 0xFE0E

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun stepBack(text: CharSequence, index: Int): Int {
        if (index >= 2) {
            val previous = text[index - 1]
            val before = text[index - 2]
            if (Character.isLowSurrogate(previous) && Character.isHighSurrogate(before)) return index - 2
        }
        return index - 1
    }

    private fun stepForward(text: CharSequence, index: Int, to: Int): Int {
        if (index + 1 < to) {
            val current = text[index]
            if (Character.isHighSurrogate(current) && Character.isLowSurrogate(text[index + 1])) return index + 2
        }
        return index + 1
    }

    private fun firstCodePointChar(text: CharSequence, index: Int): Char = text[index]

    private fun isRegionalIndicatorAt(text: CharSequence, index: Int): Boolean =
        index >= 0 && index < text.length && isRegionalIndicator(text[index])

    /** True when the code unit just before a low surrogate belongs to an RI pair. */
    private fun isRegionalIndicatorTail(text: CharSequence, index: Int, floor: Int): Boolean {
        if (index < 2 || index > text.length) return false
        val low = text[index - 1]
        val high = text[index - 2]
        if (index - 2 <= floor) return false
        return Character.isLowSurrogate(low) && Character.isHighSurrogate(high) && isRegionalIndicator(high)
    }
}
