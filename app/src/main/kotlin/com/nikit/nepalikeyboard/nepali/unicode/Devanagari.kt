package com.nikit.nepalikeyboard.nepali.unicode

/**
 * Devanagari code-point constants plus the small amount of script-specific
 * knowledge the keyboard needs.
 *
 * Grouping these in one place is deliberate: the transliteration engine, the
 * native layout definition, the grapheme segmenter, and the word-mutation
 * helpers all need to agree on which code points are consonants, which are
 * independent vowels, and which are dependent vowel signs. Duplicating the
 * lists would guarantee drift.
 *
 * References: Unicode 15.1, Devanagari block U+0900–U+097F.
 */
object Devanagari {

    // ------------------------------------------------------------------
    // Structural signs
    // ------------------------------------------------------------------

    /** U+0901 DEVANAGARI SIGN CANDRABINDU — nasalisation, written above. */
    const val CANDRABINDU: Char = '\u0901'

    /** U+0902 DEVANAGARI SIGN ANUSVARA — marks a nasal release. */
    const val ANUSVARA: Char = '\u0902'

    /** U+0903 DEVANAGARI SIGN VISARGA — final unvoiced release (ः). */
    const val VISARGA: Char = '\u0903'

    /** U+093C DEVANAGARI SIGN NUKTA — modifies a base consonant. */
    const val NUKTA: Char = '\u093C'

    /** U+093D DEVANAGARI SIGN AVAGRAHA — marks an elided अ. */
    const val AVAGRAHA: Char = '\u093D'

    /**
     * U+094D DEVANAGARI SIGN VIRAMA (halant). Removes the inherent /a/ from a
     * consonant and, when followed by another consonant, forms a conjunct.
     */
    const val VIRAMA: Char = '\u094D'

    /** U+0951..U+0954 are Vedic accents; re-exported for completeness. */
    const val VEDIC_ANUSVARA: Char = '\u0950'

    /** U+0964 DEVANAGARI DANDA — the Nepali/Hindi full stop (।). */
    const val DANDA: Char = '\u0964'

    /** U+0965 DEVANAGARI DOUBLE DANDA (॥). */
    const val DOUBLE_DANDA: Char = '\u0965'

    /** U+0971 DEVANAGARI SIGN HIGH SPACING DOT. */
    const val HIGH_SPACING_DOT: Char = '\u0971'

    // ------------------------------------------------------------------
    // Abugida geometry: the string form of a conjunct connector.
    // ------------------------------------------------------------------

    /** The virama expressed as a String, to avoid per-call char boxing. */
    const val VIRAMA_STR: String = "\u094D"

    /** The anusvara expressed as a String. */
    const val ANUSVARA_STR: String = "\u0902"

    /** The candrabindu expressed as a String. */
    const val CANDRABINDU_STR: String = "\u0901"

    // ------------------------------------------------------------------
    // Independent vowels  अ .. औ
    // ------------------------------------------------------------------

    const val A: Char = '\u0905'
    const val AA: Char = '\u0906'
    const val I: Char = '\u0907'
    const val II: Char = '\u0908'
    const val U: Char = '\u0909'
    const val UU: Char = '\u090A'
    const val VOCALIC_R: Char = '\u090B'
    const val VOCALIC_L: Char = '\u090C'
    const val E_SHORT: Char = '\u090D' // candra e, used in Marathi; kept for completeness
    const val E: Char = '\u090F'
    const val AI: Char = '\u0910'
    const val O_SHORT: Char = '\u0911' // candra o
    const val O: Char = '\u0913'
    const val AU: Char = '\u0914'

    // ------------------------------------------------------------------
    // Consonants  क .. ह
    // ------------------------------------------------------------------

    const val KA: Char = '\u0915'
    const val KHA: Char = '\u0916'
    const val GA: Char = '\u0917'
    const val GHA: Char = '\u0918'
    const val NGA: Char = '\u0919'
    const val CA: Char = '\u091A'
    const val CHA: Char = '\u091B'
    const val JA: Char = '\u091C'
    const val JHA: Char = '\u091D'
    const val NYA: Char = '\u091E'
    const val TTA: Char = '\u091F' // retroflex ṭa
    const val TTHA: Char = '\u0920' // retroflex ṭha
    const val DDA: Char = '\u0921' // retroflex ḍa
    const val DDHA: Char = '\u0922' // retroflex ḍha
    const val NNA: Char = '\u0923' // retroflex ṇa
    const val TA: Char = '\u0924' // dental ta
    const val THA: Char = '\u0925' // dental tha
    const val DA: Char = '\u0926' // dental da
    const val DHA: Char = '\u0927' // dental dha
    const val NA: Char = '\u0928'
    const val PA: Char = '\u092A'
    const val PHA: Char = '\u092B'
    const val BA: Char = '\u092C'
    const val BHA: Char = '\u092D'
    const val MA: Char = '\u092E'
    const val YA: Char = '\u092F'
    const val RA: Char = '\u0930'
    const val LA: Char = '\u0932'
    /** U+0935 VA — a labiodental approximant. Distinct from ब in Nepali. */
    const val VA: Char = '\u0935'
    const val SHA: Char = '\u0936' // palatal śa
    const val SSA: Char = '\u0937' // retroflex ṣa
    const val SA: Char = '\u0938' // dental sa
    const val HA: Char = '\u0939'

    // ------------------------------------------------------------------
    // Dependent vowel signs (matras)
    // ------------------------------------------------------------------

    /** U+093E — sign AA, used for the long ā. */
    const val SIGN_AA: Char = '\u093E'
    /** U+093F — sign I, written *before* the consonant. */
    const val SIGN_I: Char = '\u093F'
    /** U+0940 — sign II. */
    const val SIGN_II: Char = '\u0940'
    /** U+0941 — sign U. */
    const val SIGN_U: Char = '\u0941'
    /** U+0942 — sign UU. */
    const val SIGN_UU: Char = '\u0942'
    /** U+0943 — sign vocalic R. */
    const val SIGN_VOCALIC_R: Char = '\u0943'
    /** U+0944 — sign vocalic RR. */
    const val SIGN_VOCALIC_RR: Char = '\u0944'
    /** U+0947 — sign E. */
    const val SIGN_E: Char = '\u0947'
    /** U+0948 — sign AI. */
    const val SIGN_AI: Char = '\u0948'
    /** U+094B — sign O. */
    const val SIGN_O: Char = '\u094B'
    /** U+094C — sign AU. */
    const val SIGN_AU: Char = '\u094C'
    /** U+0945 — candra E sign (Marathi). */
    const val SIGN_CANDRA_E: Char = '\u0945'
    /** U+0949 — candra O sign (Marathi). */
    const val SIGN_CANDRA_O: Char = '\u0949'
    /** U+094A — sign short O (Kashmiri / Marathi). */
    const val SIGN_SHORT_O: Char = '\u094A'
    /** U+0946 — sign short E. */
    const val SIGN_SHORT_E: Char = '\u0946'
    /** U+0962 — sign vocalic L. */
    const val SIGN_VOCALIC_L: Char = '\u0962'
    /** U+0963 — sign vocalic LL. */
    const val SIGN_VOCALIC_LL: Char = '\u0963'

    // ------------------------------------------------------------------
    // Nepali numerals  ० .. ९
    // ------------------------------------------------------------------

    /** U+0966 DEVANAGARI DIGIT ZERO. */
    const val DIGIT_ZERO: Char = '\u0966'
    /** U+096F DEVANAGARI DIGIT NINE. */
    const val DIGIT_NINE: Char = '\u096F'

    // ------------------------------------------------------------------
    // Precomposed consonants with nukta (the ambiguous-phonetic targets).
    // These have canonical decompositions; we prefer the precomposed form in
    // output because it is what Nepali users see in print and it keeps the
    // grapheme cluster to a single code point.
    // ------------------------------------------------------------------

    /** U+0958 क़ qa (क + ़). */
    const val QA: Char = '\u0958'
    /** U+0959 ख़ kha (ख + ़) — the "kh" in Persian loans. */
    const val KHHA: Char = '\u0959'
    /** U+095A ग़ gha (ग + ़). */
    const val GHHA: Char = '\u095A'
    /** U+095B ज़ za (ज + ़) — essential for Nepali words like ज़रुरी. */
    const val ZA: Char = '\u095B'
    /** U+095C ड़ ṛa (ड + ़) — a flapped retroflex. */
    const val DDDA: Char = '\u095C'
    /** U+095D ढ़ ṛha (ढ + ़). */
    const val RHA: Char = '\u095D'
    /** U+095E फ़ fa (फ + ़) — essential for Nepali loans. */
    const val FA: Char = '\u095E'
    /** U+095F य़ ya (य + ़). */
    const val YYA: Char = '\u095F'

    // ------------------------------------------------------------------
    // Precomposed conjunct letters that have their own code point.
    // ------------------------------------------------------------------

    /** U+0915 + U+094D + U+0937 = क्ष. */
    const val KSHATTRA: String = "\u0915\u094D\u0937"

    /**
     * क्ष + ् + य = क्ष्य. The extra virama is required: क्ष ends in a
     * consonant whose inherent /a/ must be suppressed before the य.
     */
    const val KSHYA: String = "\u0915\u094D\u0937\u094D\u092F"

    /** U+091C + U+094D + U+091E = ज्ञ. */
    const val GYA: String = "\u091C\u094D\u091E"

    /** U+0924 + U+094D + U+0930 = त्र. */
    const val TRA: String = "\u0924\u094D\u0930"

    /** U+0924 + U+094D + U+0924 = त्त. */
    const val TTA_CONJUNCT: String = "\u0924\u094D\u0924"

    /** U+0926 + U+094D + U+092F = द्य. */
    const val DYA: String = "\u0926\u094D\u092F"

    /** U+0936 + U+094D + U+0930 = श्र. */
    const val SHRA: String = "\u0936\u094D\u0930"

    /**
     * U+0928 + U+094D + U+0928 — not a true conjunct in Nepali but a common
     * accidental sequence; listed so the romanizer can special-case it.
     */
    const val NNA_CONJUNCT: String = "\u0928\u094D\u0928"

    // ------------------------------------------------------------------
    // Composite and standalone characters used by the native keyboard layout.
    // ------------------------------------------------------------------

    /**
     * U+0950 DEVANAGARI OM (ॐ).
     *
     * A single code point, not a ligature of अ + ु + ँ, which is why it lives
     * here rather than being composed by the romanizer. Nepali prose uses it in
     * religious and formal writing.
     */
    const val OM: Char = '\u0950'

    /**
     * ड़ ṛa written canonically as ड + ़ (U+0921 U+093C).
     *
     * The precomposed U+095C form ([DDDA]) is what most Nepali fonts render, and
     * it is what the romanizer emits. `RRHA`-style composite forms are offered
     * on the native layout's symbol layer for users whose fonts or input habits
     * expect the decomposed sequence, and for text interchange with systems that
     * normalise to the decomposed form.
     */
    const val RRA_COMPOSITE: String = "\u0921\u093C"

    /**
     * ढ़ ṛha written canonically as ढ + ़ (U+0922 U+093C).
     *
     * The decomposed counterpart of [RHA].
     */
    const val RRHA_COMPOSITE: String = "\u0922\u093C"

    /**
     * ऱ — the Devanagari letter RRA (U+0931), used in Marathi and in a handful
     * of Nepali loanwords. Distinct from ड़.
     */
    const val RRA: Char = '\u0931'

    // ------------------------------------------------------------------
    // Lookup tables
    // ------------------------------------------------------------------

    /**
     * Independent vowels indexed by their position in the standard ordering.
     * Used by the romanizer to pick between the independent form (at the start
     * of a syllable) and the dependent matra (after a consonant).
     */
    val INDEPENDENT_VOWELS: CharArray = charArrayOf(
        A, AA, I, II, U, UU, VOCALIC_R, VOCALIC_L, E, AI, O, AU
    )

    /**
     * Dependent vowel signs, index-aligned with [INDEPENDENT_VOWELS] *except*
     * that the vocalic pairs and the Marathi-only candra vowels are omitted,
     * so use [matraForIndependentVowel] rather than the array index.
     */
    val DEPENDENT_VOWEL_SIGNS: CharArray = charArrayOf(
        SIGN_AA, SIGN_I, SIGN_II, SIGN_U, SIGN_UU, SIGN_VOCALIC_R,
        SIGN_VOCALIC_L, SIGN_E, SIGN_AI, SIGN_O, SIGN_AU
    )

    /**
     * Devanagari digits, index 0..9, so `DIGITS[n]` yields the Nepali numeral
     * for a Latin digit.
     */
    val DIGITS: CharArray = CharArray(10) { (DIGIT_ZERO.code + it).toChar() }

    /**
     * The full consonant inventory as a set, used by the romanizer's
     * "is the previous output a consonant?" check and by the native layout
     * generator.
     */
    val CONSONANTS: CharArray = charArrayOf(
        KA, KHA, GA, GHA, NGA,
        CA, CHA, JA, JHA, NYA,
        TTA, TTHA, DDA, DDHA, NNA,
        TA, THA, DA, DHA, NA,
        PA, PHA, BA, BHA, MA,
        YA, RA, LA,
        VA, SHA, SSA, SA, HA,
        QA, KHHA, GHHA, ZA, DDDA, RHA, FA, YYA
    )

    /** Set membership test for consonants. Backed by a char-indexed bitset. */
    private val consonantBits: LongArray = buildBitset(CONSONANTS)

    /** Set membership test for dependent vowel signs. */
    private val matraBits: LongArray = buildBitset(
        charArrayOf(
            SIGN_AA, SIGN_I, SIGN_II, SIGN_U, SIGN_UU, SIGN_VOCALIC_R,
            SIGN_VOCALIC_RR, SIGN_VOCALIC_L, SIGN_VOCALIC_LL,
            SIGN_E, SIGN_AI, SIGN_O, SIGN_AU,
            SIGN_CANDRA_E, SIGN_CANDRA_O, SIGN_SHORT_E, SIGN_SHORT_O
        )
    )

    /** Set membership test for independent vowels. */
    private val vowelBits: LongArray = buildBitset(INDEPENDENT_VOWELS)

    /** Set membership test for the structural signs that end an akshara. */
    private val terminalSignBits: LongArray = buildBitset(
        charArrayOf(ANUSVARA, CANDRABINDU, VISARGA, NUKTA, AVAGRAHA)
    )

    /** True when [c] is a Devanagari consonant. */
    fun isConsonant(c: Char): Boolean = testBits(consonantBits, c.code)

    /** True when [c] is a dependent vowel sign (matra). */
    fun isMatra(c: Char): Boolean = testBits(matraBits, c.code)

    /** True when [c] is an independent vowel. */
    fun isIndependentVowel(c: Char): Boolean = testBits(vowelBits, c.code)

    /** True when [c] is anusvara, candrabindu, visarga, nukta, or avagraha. */
    fun isTerminalSign(c: Char): Boolean = testBits(terminalSignBits, c.code)

    /** True when [c] is the virama / halant. */
    fun isVirama(c: Char): Boolean = c == VIRAMA

    /** True when [c] is a Devanagari digit. */
    fun isDigit(c: Char): Boolean = c in DIGIT_ZERO..DIGIT_NINE

    /** True when [c] lies anywhere in the Devanagari block. */
    fun isDevanagariBlock(c: Char): Boolean = c in '\u0900'..'\u097F'

    /** True when [c] is the danda or double danda. */
    fun isDanda(c: Char): Boolean = c == DANDA || c == DOUBLE_DANDA

    /**
     * Map an independent vowel to its dependent (matra) counterpart.
     *
     * Returns `null` when the vowel has no matra form in ordinary Nepali usage
     * (the vocalic liquids, and the Marathi candra vowels), in which case the
     * romanizer falls back to emitting virama + independent vowel.
     */
    fun matraForIndependentVowel(v: Char): Char? = when (v) {
        AA -> SIGN_AA
        I -> SIGN_I
        II -> SIGN_II
        U -> SIGN_U
        UU -> SIGN_UU
        VOCALIC_R -> SIGN_VOCALIC_R
        VOCALIC_L -> SIGN_VOCALIC_L
        E -> SIGN_E
        AI -> SIGN_AI
        O -> SIGN_O
        AU -> SIGN_AU
        E_SHORT -> SIGN_CANDRA_E
        O_SHORT -> SIGN_CANDRA_O
        else -> null
    }

    /**
     * Map a dependent vowel sign back to its independent vowel. The inverse of
     * [matraForIndependentVowel]; needed when the romanizer decides that an
     * "a" following a vowel sign actually begins a new syllable.
     */
    fun independentVowelForMatra(m: Char): Char? = when (m) {
        SIGN_AA -> AA
        SIGN_I -> I
        SIGN_II -> II
        SIGN_U -> U
        SIGN_UU -> UU
        SIGN_VOCALIC_R -> VOCALIC_R
        SIGN_VOCALIC_L -> VOCALIC_L
        SIGN_E -> E
        SIGN_AI -> AI
        SIGN_O -> O
        SIGN_AU -> AU
        SIGN_CANDRA_E -> E_SHORT
        SIGN_CANDRA_O -> O_SHORT
        else -> null
    }

    /**
     * Convert a Latin digit character to its Devanagari counterpart. Returns
     * the input unchanged when it is not a Latin digit.
     */
    fun toDevanagariDigit(c: Char): Char =
        if (c in '0'..'9') DIGITS[c - '0'] else c

    /**
     * The Devanagari digit for a value in `0..9`.
     *
     * Unlike [toDevanagariDigit] this takes an `Int` value rather than a
     * character, which is what the native layout generator needs when it builds
     * its digit row from an index. Values outside the range are clamped rather
     * than throwing, because a layout generator should never be able to crash
     * the keyboard.
     */
    fun digitChar(value: Int): Char = DIGITS[value.coerceIn(0, 9)]

    /**
     * Convert a Devanagari digit to its Latin counterpart. Returns the input
     * unchanged when it is not a Devanagari digit.
     */
    fun toLatinDigit(c: Char): Char =
        if (isDigit(c)) ('0' + (c - DIGIT_ZERO)) else c

    /**
     * The danda is the Nepali sentence terminator. Used by the auto-capitalise
     * and double-space-period logic, both of which must treat a preceding
     * Devanagari sentence end as a sentence boundary.
     */
    fun isSentenceTerminator(c: Char): Boolean = c == '.' || c == '!' || c == '?' ||
        c == DANDA || c == DOUBLE_DANDA

    // ------------------------------------------------------------------
    // Bitset helpers. A char-keyed bitset costs 8 KB for the whole BMP and
    // makes membership a single shift+mask instead of a linear scan, which
    // matters because the romanizer probes these on every input character.
    // ------------------------------------------------------------------

    private fun buildBitset(chars: CharArray): LongArray {
        val bits = LongArray((Char.MAX_VALUE.code shr 6) + 1)
        for (c in chars) {
            val v = c.code
            bits[v shr 6] = bits[v shr 6] or (1L shl (v and 63))
        }
        return bits
    }

    private fun testBits(bits: LongArray, value: Int): Boolean {
        if (value < 0) return false
        val word = value shr 6
        if (word >= bits.size) return false
        return (bits[word] and (1L shl (value and 63))) != 0L
    }
}
