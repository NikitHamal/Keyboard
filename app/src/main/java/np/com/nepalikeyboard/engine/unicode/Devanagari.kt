package np.com.nepalikeyboard.engine.unicode

/**
 * Devanagari character classification.
 *
 * Everything here is `Char`-level and allocation free: these predicates run
 * inside the keystroke path (backspace, cluster deletion, cursor gliding), so
 * they must never box, allocate or call into ICU.
 */
object Devanagari {

    const val VIRAMA: Char = '\u094D'          // ्  halant
    const val ANUSVARA: Char = '\u0902'        // ं
    const val CHANDRABINDU: Char = '\u0901'    // ँ
    const val VISARGA: Char = '\u0903'         // ः
    const val NUKTA: Char = '\u093C'           // ़
    const val DANDA: Char = '\u0964'           // ।
    const val DOUBLE_DANDA: Char = '\u0965'    // ॥
    const val AVAGRAHA: Char = '\u093D'        // ऽ
    const val ZWJ: Char = '\u200D'
    const val ZWNJ: Char = '\u200C'
    const val ZERO_WIDTH_SPACE: Char = '\u200B'
    const val VARIATION_SELECTOR_16: Char = '\uFE0F'
    const val COMBINING_KEYCAP: Char = '\u20E3'
    const val SKIN_TONE_MIN: Char = '\uD83C'   // surrogate-aware checks below

    private const val BLOCK_START = 0x0900
    private const val BLOCK_END = 0x097F
    private const val EXTENDED_START = 0xA8E0
    private const val EXTENDED_END = 0xA8FF
    private const val VEDIC_START = 0x1CD0
    private const val VEDIC_END = 0x1CFF

    private const val INDEPENDENT_VOWEL_START = 0x0904
    private const val INDEPENDENT_VOWEL_END = 0x0914
    private const val CONSONANT_START = 0x0915
    private const val CONSONANT_END = 0x0939
    private const val NUKTA_CONSONANT_START = 0x0958
    private const val NUKTA_CONSONANT_END = 0x095F
    private const val EXTRA_CONSONANT_START = 0x0978
    private const val EXTRA_CONSONANT_END = 0x097F
    private const val VOWEL_SIGN_START = 0x093E
    private const val VOWEL_SIGN_END = 0x094C
    private const val DIGIT_START = 0x0966
    private const val DIGIT_END = 0x096F

    /** True for any code unit inside the main Devanagari block (U+0900–U+097F). */
    fun isDevanagariBlock(c: Char): Boolean = c.code in BLOCK_START..BLOCK_END

    /** True for Devanagari Extended (U+A8E0–U+A8FF) and Vedic Extensions. */
    fun isDevanagariExtended(c: Char): Boolean =
        c.code in EXTENDED_START..EXTENDED_END || c.code in VEDIC_START..VEDIC_END

    /**
     * True for characters that must never be separated from their base
     * character: dependent vowel signs (matras), the virama, anusvara,
     * chandrabindu, visarga, nukta, Vedic marks, Devanagari Extended marks and
     * the zero-width joiners used to build half-forms.
     */
    fun isCombiningMark(c: Char): Boolean = when (c.code) {
        in VOWEL_SIGN_START..VOWEL_SIGN_END -> true
        0x094D -> true // virama
        0x0900, 0x0901, 0x0902, 0x0903 -> true // inverted candrabindu, candrabindu, anusvara, visarga
        0x093A, 0x093B, 0x093C -> true // oe-matra, ooe-matra, nukta
        0x094E, 0x094F -> true // prishthamatra-e, aw
        0x0951, 0x0952, 0x0953, 0x0954, 0x0955, 0x0956, 0x0957 -> true // Vedic tone marks
        in EXTENDED_START..EXTENDED_END -> true
        in VEDIC_START..VEDIC_END -> true
        0x200C, 0x200D -> true // ZWNJ, ZWJ
        0x00A0 -> true // no-break space binds clusters apart but never splits
        else -> false
    }

    /** Combining marks that change a consonant into a full syllable (matras). */
    fun isVowelSign(c: Char): Boolean = c.code in VOWEL_SIGN_START..VOWEL_SIGN_END

    fun isIndependentVowel(c: Char): Boolean = c.code in INDEPENDENT_VOWEL_START..INDEPENDENT_VOWEL_END

    fun isConsonant(c: Char): Boolean {
        val code = c.code
        return code in CONSONANT_START..CONSONANT_END ||
            code in NUKTA_CONSONANT_START..NUKTA_CONSONANT_END ||
            code in EXTRA_CONSONANT_START..EXTRA_CONSONANT_END
    }

    fun isDigit(c: Char): Boolean = c.code in DIGIT_START..DIGIT_END

    fun isDanda(c: Char): Boolean = c == DANDA || c == DOUBLE_DANDA

    /** True when [c] is a Devanagari or Latin letter/digit/combining mark. */
    fun isWordChar(c: Char): Boolean =
        isDevanagariBlock(c) || isDevanagariExtended(c) ||
            (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
            c == '_' || c == '\'' || c == '\u2019'

    fun toDevanagariDigit(ascii: Char): Char =
        if (ascii in '0'..'9') (DIGIT_START + (ascii - '0')).toChar() else ascii

    fun toAsciiDigit(devanagari: Char): Char =
        if (isDigit(devanagari)) ('0' + (devanagari.code - DIGIT_START)).toChar() else devanagari

    /** Rewrites ASCII digits in [text] as ०–९ (used by the Devanagari layouts). */
    fun digitsToDevanagari(text: CharSequence): String {
        var needsWork = false
        for (index in 0 until text.length) {
            if (text[index] in '0'..'9') {
                needsWork = true
                break
            }
        }
        if (!needsWork) return text.toString()
        val builder = StringBuilder(text.length)
        for (index in 0 until text.length) builder.append(toDevanagariDigit(text[index]))
        return builder.toString()
    }

    fun digitsToAscii(text: CharSequence): String {
        var needsWork = false
        for (index in 0 until text.length) {
            if (isDigit(text[index])) {
                needsWork = true
                break
            }
        }
        if (!needsWork) return text.toString()
        val builder = StringBuilder(text.length)
        for (index in 0 until text.length) builder.append(toAsciiDigit(text[index]))
        return builder.toString()
    }

    /** True when the (already cluster-aligned) sequence ends with a halant. */
    fun hasTrailingVirama(text: CharSequence, end: Int = text.length): Boolean =
        end > 0 && text[end - 1] == VIRAMA

    /**
     * Removes a dangling virama, which is what a half-typed conjunct looks like
     * while the user is still typing. Used when committing a romanized word.
     */
    fun stripDanglingVirama(text: String): String =
        if (text.isNotEmpty() && text[text.length - 1] == VIRAMA) text.substring(0, text.length - 1) else text

    /**
     * Counts grapheme clusters between [from] and [to] without allocating.
     * Latin: one cluster per code point (surrogate pairs counted once).
     * Devanagari: base + all combining marks + virama-joined consonants = one.
     */
    fun countClusters(text: CharSequence, from: Int = 0, to: Int = text.length): Int {
        var count = 0
        var index = from
        while (index < to) {
            index = Graphemes.nextBoundary(text, index, to)
            count++
        }
        return count
    }
}
