package com.nikit.nepalikeyboard.unicode

/**
 * A single extended grapheme cluster, expressed as a UTF-16 range inside the
 * source text.
 *
 * The segmenter returns *ranges* rather than substrings on purpose: the hot
 * deletion path only ever needs to know how many code units to remove, and
 * materialising a String per cluster would allocate on every backspace. Callers
 * that genuinely need the text slice can call [String.substring] themselves.
 *
 * @property start inclusive UTF-16 index into the source text
 * @property end exclusive UTF-16 index into the source text
 */
@JvmInline
value class GraphemeRange(private val packed: Long) {
    constructor(start: Int, end: Int) : this(
        (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
    )

    val start: Int get() = (packed ushr 32).toInt()
    val end: Int get() = packed.toInt()

    /** Number of UTF-16 code units in this cluster. */
    val length: Int get() = end - start

    val isEmpty: Boolean get() = end <= start

    override fun toString(): String = "GraphemeRange($start, $end, len=$length)"
}

/**
 * A locale-independent, allocation-free extended-grapheme-cluster segmenter
 * with explicit Devanagari akshara handling.
 *
 * ## Why not UAX #29 out of the box?
 *
 * UAX #29 treats every `Mn`/`Mc` mark as an Extend character, which is correct
 * for *graphemes* but is not quite what an editor needs for an Indic script.
 * In Devanagari, the unit users perceive as "one character" is the **akshara**:
 * a consonant (or conjunct) together with all of its vowel signs, plus any
 * trailing anusvara / chandrabindu / visarga / nukta. Two rules matter:
 *
 *  1. **Virama (halant, U+094D) joins forward.** क + ् + ष is one akshara क्ष.
 *     UAX #29 already keeps them together because halant is `Mn`, but we make
 *     the intent explicit so that a future change to [CodePointClassifier]
 *     cannot silently break conjunct deletion.
 *  2. **Anusvara / chandrabindu / visarga are terminal.** They sit after the
 *     vowel sign and belong to the same akshara: न + ा + म + स + ् + त + े is
 *     two aksharas (नाम and स्ते) when segmented as text, but notice that म
 *     followed by ं must not split, hence the rule that any mark continues the
 *     current cluster.
 *
 * The result matches what a user expects when repeatedly pressing backspace in
 * a Devanagari word: pressing once removes े from नमस्ते leaving नमस्त,
 * pressing again removes the त…स conjunct cluster स्त as a single unit.
 *
 * ## Allocation discipline
 *
 * [nextBoundary] and [previousBoundary] are pure integer scans. They do not
 * allocate, do not build substrings, and do not call into ICU. They are safe to
 * invoke from the keypress path.
 */
object GraphemeClusterSegmenter {

    /**
     * Find the exclusive end index of the grapheme cluster that starts at
     * [start].
     *
     * @param text the source text
     * @param start index of the first code unit of the cluster; must be within
     *        `0 until text.length`
     * @return the exclusive end index, always `> start`
     */
    fun nextBoundary(text: CharSequence, start: Int, end: Int = text.length): Int {
        if (start >= end) return end

        var i = start

        // ---- Regional-indicator pairs (flags) ---------------------------
        // Two consecutive RIs form one flag. A run of four RIs is two flags,
        // so we only ever consume exactly two.
        val firstCp = codePointAt(text, i, end)
        if (CodePointClassifier.classify(firstCp) == CodePointClass.REGIONAL_INDICATOR) {
            val firstLen = charCount(firstCp)
            val afterFirst = i + firstLen
            if (afterFirst < end) {
                val secondCp = codePointAt(text, afterFirst, end)
                if (CodePointClassifier.classify(secondCp) == CodePointClass.REGIONAL_INDICATOR) {
                    return afterFirst + charCount(secondCp)
                }
            }
            return afterFirst
        }

        // Advance past the base code point.
        i += charCount(firstCp)

        // ---- Extend loop -------------------------------------------------
        // Consume every character that attaches to the cluster.
        while (i < end) {
            val cp = codePointAt(text, i, end)
            val cls = CodePointClassifier.classify(cp)
            val cpLen = charCount(cp)

            when {
                // CRLF is a single cluster.
                cls == CodePointClass.LF && i > start &&
                    codePointAt(text, i - 1, end) == 0x0D -> {
                    i += cpLen
                }

                // A virama pulls the *next* base into this cluster.
                cls == CodePointClass.DEVANAGARI_HALANT -> {
                    i += cpLen
                    // After the halant, absorb the following consonant (and any
                    // nukta immediately attached to it).
                    if (i < end) {
                        val next = codePointAt(text, i, end)
                        val nextCls = CodePointClassifier.classify(next)
                        if (nextCls != CodePointClass.DEVANAGARI_HALANT &&
                            nextCls != CodePointClass.NON_SPACING_MARK &&
                            nextCls != CodePointClass.SPACING_MARK &&
                            nextCls != CodePointClass.CONTROL &&
                            nextCls != CodePointClass.SPACE
                        ) {
                            i += charCount(next)
                        }
                    }
                }

                // ZWJ: the joiner itself plus whatever follows stays inside the
                // cluster. This covers emoji ZWJ sequences (👨‍👩‍👧) and
                // Devanagari half-forms that use an explicit joiner.
                cls == CodePointClass.ZWJ -> {
                    i += cpLen
                    if (i < end) {
                        val next = codePointAt(text, i, end)
                        val nextCls = CodePointClassifier.classify(next)
                        if (nextCls != CodePointClass.CONTROL && nextCls != CodePointClass.CR) {
                            i += charCount(next)
                        }
                    }
                }

                // ZWNJ attaches but must NOT pull the next base in: it is the
                // explicit "do not form a conjunct" instruction.
                cls == CodePointClass.ZWNJ -> {
                    i += cpLen
                }

                CodePointClassifier.isExtending(cls) -> {
                    i += cpLen
                }

                // Skin-tone modifiers can follow an already-extended emoji.
                CodePointClassifier.isEmojiModifier(cls) -> {
                    i += cpLen
                }

                else -> return i
            }
        }
        return i
    }

    /**
     * Find the inclusive start index of the grapheme cluster that ends at
     * [end].
     *
     * Implemented by walking forward from a safe lower bound. Grapheme clusters
     * in practice never exceed a few dozen code units, so we cap the backward
     * scan at [MAX_CLUSTER_LENGTH] and fall back to a single code point. This
     * keeps backspace O(1) instead of O(n) in pathological text.
     *
     * @return the start index, always `< end`
     */
    fun previousBoundary(text: CharSequence, end: Int, start: Int = 0): Int {
        if (end <= start) return start

        // A cluster boundary can never be *inside* the region more than this
        // many code units back. 64 covers every real ZWJ emoji sequence plus
        // generous headroom for stacked Devanagari conjuncts.
        val lowerBound = if (end - start > MAX_CLUSTER_LENGTH) end - MAX_CLUSTER_LENGTH else start

        var i = lowerBound
        var lastBoundary = lowerBound

        while (i < end) {
            val next = nextBoundary(text, i, end)
            if (next >= end) {
                return lastBoundary
            }
            lastBoundary = next
            i = next
        }
        return lastBoundary
    }

    /**
     * Segment [text] fully into grapheme clusters.
     *
     * This allocates a list and is therefore **not** for the keystroke path.
     * It exists for tests, for word-segmentation setup, and for analytic work
     * in the background engine.
     */
    fun segment(text: CharSequence): List<GraphemeRange> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<GraphemeRange>(text.length / 2 + 1)
        var i = 0
        val n = text.length
        while (i < n) {
            val next = nextBoundary(text, i, n)
            out.add(GraphemeRange(i, next))
            i = next
        }
        return out
    }

    /** Number of grapheme clusters in [text]. Allocation-free. */
    fun countGraphemes(text: CharSequence): Int {
        var i = 0
        var count = 0
        val n = text.length
        while (i < n) {
            i = nextBoundary(text, i, n)
            count++
        }
        return count
    }

    /**
     * Number of grapheme clusters in `text[start until end]`. Allocation-free.
     * Used to translate a cluster count into a selection offset when the user
     * swipes left on backspace.
     */
    fun countGraphemesInRange(text: CharSequence, start: Int, end: Int): Int {
        var i = start
        var count = 0
        while (i < end) {
            i = nextBoundary(text, i, end)
            count++
        }
        return count
    }

    /**
     * Read the code point beginning at [index], returning -1 when the index is
     * out of range. Merges a valid surrogate pair into a single code point.
     */
    fun codePointAt(text: CharSequence, index: Int, end: Int = text.length): Int {
        if (index >= end) return -1
        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < end) {
            val low = text[index + 1]
            if (low.isLowSurrogate()) {
                return Character.toCodePoint(high, low)
            }
        }
        return high.code
    }

    /**
     * Number of UTF-16 code units occupied by [cp]. Returns 1 for anything that
     * is not a supplementary code point, which is what we want when the text
     * contains a malformed lone surrogate: we advance by one unit rather than
     * running off the end.
     */
    fun charCount(cp: Int): Int = if (cp < 0) 1 else if (cp >= 0x10000) 2 else 1

    /**
     * Upper bound on the number of UTF-16 code units a single grapheme cluster
     * can occupy. Used to keep [previousBoundary] constant-time.
     */
    private const val MAX_CLUSTER_LENGTH = 64
}
