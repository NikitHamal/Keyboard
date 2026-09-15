package np.com.nepalikeyboard.engine

import androidx.compose.runtime.Immutable

/** What a matched roman unit turns into. */
internal enum class UnitKind {
    /** A consonant (or consonant conjunct such as क्ष / त्र / ज्ञ). */
    CONSONANT,

    /** A vowel: independent form when word-initial, matra when after a consonant. */
    VOWEL,

    /** A nasal that assimilates into anusvara (ं). */
    ANUSVARA,
}

/**
 * One romanization rule.
 *
 * @param roman        the source characters, matched case-sensitively.
 * @param independent  Devanagari form used at the start of a syllable (vowels)
 *                     or the consonant glyph itself.
 * @param dependent    matra used after a consonant. Empty for the inherent 'a'.
 */
@Immutable
internal class PhoneticRule(
    val roman: String,
    val independent: String,
    val dependent: String,
    val kind: UnitKind,
) {
    override fun toString(): String = "$roman -> $independent/$dependent ($kind)"
}

/**
 * The complete rule set of the deterministic phonetic parser.
 *
 * Design notes
 * ------------
 * 1. **Longest match wins.** Rules are bucketed by leading character and sorted
 *    by descending roman length, so `chh` beats `ch` beats `c`.
 * 2. **Case is meaningful, but only where it must be.** Lowercase is the
 *    everyday layer (`th` = थ). Capitals supply the retroflex/extra series
 *    (`T` = ट, `D` = ड, `N` = ण, `S` = ष) exactly like ITRANS. The IME feeds
 *    auto-capitalization through as lowercase (see `KeyboardController`) so that
 *    a sentence starting with "Namaste" still yields नमस्ते rather than णमस्ते.
 * 3. **Orthographic correctness over phonetic transcription.** Devanagari words
 *    do not end in a halant; a word-final consonant keeps its inherent 'a' in
 *    writing even when it is not pronounced (नेपाल, रात, देश). The engine
 *    therefore only inserts ् between two adjacent consonants, which is what
 *    produces नमस्ते (`m` + `s` + `t` + `e`) and क्ष्य (`ksh` + `y` + `a`).
 * 4. **Ambiguity is a feature.** Where the romanization is genuinely
 *    ambiguous (`b` vs `v`, `kam` vs `kaam`, स vs ष), the deterministic layer
 *    emits exactly one literal answer and the lexicon supplies the statistically
 *    likely one. धन्यबाद (literal) vs धन्यवाद (dictionary) is the canonical
 *    example and is covered by an instrumented expectation in
 *    `PhoneticEngineTest`.
 */
internal object PhoneticRules {

    const val VIRAMA_CHAR: Char = '\u094D'
    const val ANUSVARA_CHAR: Char = '\u0902'

    /** Vowels, longest roman form first. */
    private val VOWELS: Array<PhoneticRule> = arrayOf(
        rule("aa", "\u0906", "\u093E", UnitKind.VOWEL),   // आ / ा
        rule("ai", "\u0910", "\u0948", UnitKind.VOWEL),   // ऐ / ै
        rule("au", "\u0914", "\u094C", UnitKind.VOWEL),   // औ / ौ
        rule("ou", "\u0914", "\u094C", UnitKind.VOWEL),   // औ / ौ (colloquial)
        rule("ee", "\u0908", "\u0940", UnitKind.VOWEL),   // ई / ी
        rule("oo", "\u090A", "\u0942", UnitKind.VOWEL),   // ऊ / ू
        rule("ri", "\u090B", "\u0943", UnitKind.VOWEL),   // ऋ / ृ
        rule("a", "\u0905", "", UnitKind.VOWEL),          // अ / inherent
        rule("i", "\u0907", "\u093F", UnitKind.VOWEL),    // इ / ि
        rule("u", "\u0909", "\u0941", UnitKind.VOWEL),    // उ / ु
        rule("e", "\u090F", "\u0947", UnitKind.VOWEL),    // ए / े
        rule("o", "\u0913", "\u094B", UnitKind.VOWEL),    // ओ / ो
        rule("A", "\u0906", "\u093E", UnitKind.VOWEL),    // आ / ा  (ITRANS long-a)
        rule("I", "\u0908", "\u0940", UnitKind.VOWEL),    // ई / ी
        rule("U", "\u090A", "\u0942", UnitKind.VOWEL),    // ऊ / ू
    )

    /** Consonants, including the three true ligatures of Nepali. */
    private val CONSONANTS: Array<PhoneticRule> = arrayOf(
        rule("ksh", "\u0915\u094D\u0937", "", UnitKind.CONSONANT), // क्ष
        rule("chh", "\u091B", "", UnitKind.CONSONANT),             // छ
        rule("shh", "\u0937", "", UnitKind.CONSONANT),             // ष
        rule("Chh", "\u091B", "", UnitKind.CONSONANT),             // छ
        rule("kh", "\u0916", "", UnitKind.CONSONANT),              // ख
        rule("gh", "\u0918", "", UnitKind.CONSONANT),              // घ
        rule("ch", "\u091A", "", UnitKind.CONSONANT),              // च
        rule("jh", "\u091D", "", UnitKind.CONSONANT),              // झ
        rule("th", "\u0925", "", UnitKind.CONSONANT),              // थ
        rule("dh", "\u0927", "", UnitKind.CONSONANT),              // ध
        rule("ph", "\u092B", "", UnitKind.CONSONANT),              // फ
        rule("bh", "\u092D", "", UnitKind.CONSONANT),              // भ
        rule("sh", "\u0936", "", UnitKind.CONSONANT),              // श
        rule("tr", "\u0924\u094D\u0930", "", UnitKind.CONSONANT),  // त्र
        rule("gy", "\u091C\u094D\u091E", "", UnitKind.CONSONANT),  // ज्ञ
        rule("Th", "\u0920", "", UnitKind.CONSONANT),              // ठ
        rule("Dh", "\u0922", "", UnitKind.CONSONANT),              // ढ
        rule("Sh", "\u0937", "", UnitKind.CONSONANT),              // ष
        rule("T", "\u091F", "", UnitKind.CONSONANT),               // ट
        rule("D", "\u0921", "", UnitKind.CONSONANT),               // ड
        rule("N", "\u0923", "", UnitKind.CONSONANT),               // ण
        rule("S", "\u0937", "", UnitKind.CONSONANT),               // ष
        rule("k", "\u0915", "", UnitKind.CONSONANT),               // क
        rule("K", "\u0915", "", UnitKind.CONSONANT),               // क
        rule("g", "\u0917", "", UnitKind.CONSONANT),               // ग
        rule("G", "\u0917", "", UnitKind.CONSONANT),               // ग
        rule("c", "\u091A", "", UnitKind.CONSONANT),               // च
        rule("C", "\u091A", "", UnitKind.CONSONANT),               // च
        rule("j", "\u091C", "", UnitKind.CONSONANT),               // ज
        rule("J", "\u091C", "", UnitKind.CONSONANT),               // ज
        rule("t", "\u0924", "", UnitKind.CONSONANT),               // त
        rule("d", "\u0926", "", UnitKind.CONSONANT),               // द
        rule("n", "\u0928", "", UnitKind.CONSONANT),               // न
        rule("p", "\u092A", "", UnitKind.CONSONANT),               // प
        rule("P", "\u092A", "", UnitKind.CONSONANT),               // प
        rule("b", "\u092C", "", UnitKind.CONSONANT),               // ब
        rule("B", "\u092C", "", UnitKind.CONSONANT),               // ब
        rule("m", "\u092E", "", UnitKind.CONSONANT),               // म
        rule("y", "\u092F", "", UnitKind.CONSONANT),               // य
        rule("r", "\u0930", "", UnitKind.CONSONANT),               // र
        rule("R", "\u0930", "", UnitKind.CONSONANT),               // र
        rule("l", "\u0932", "", UnitKind.CONSONANT),               // ल
        rule("L", "\u0932", "", UnitKind.CONSONANT),               // ल
        rule("v", "\u0935", "", UnitKind.CONSONANT),               // व
        rule("w", "\u0935", "", UnitKind.CONSONANT),               // व
        rule("W", "\u0935", "", UnitKind.CONSONANT),               // व
        rule("s", "\u0938", "", UnitKind.CONSONANT),               // स
        rule("h", "\u0939", "", UnitKind.CONSONANT),               // ह
        rule("H", "\u0939", "", UnitKind.CONSONANT),               // ह
        rule("q", "\u0915", "", UnitKind.CONSONANT),               // क (loan words)
        rule("z", "\u091C\u093C", "", UnitKind.CONSONANT),         // ज़
        rule("f", "\u092B\u093C", "", UnitKind.CONSONANT),         // फ़
        rule("x", "\u0915\u094D\u0937", "", UnitKind.CONSONANT),   // क्ष
    )

    /**
     * Nasal assimilation sources: `n` collapses into anusvara when it precedes
     * a stop, and `M` does so unconditionally. `N` is *not* listed: a capital N
     * is an explicit request for ण (retroflex), which is also what makes the
     * ITRANS-style `N` usable.
     */
    private val NASALS: Array<PhoneticRule> = arrayOf(
        rule("M", ANUSVARA_CHAR.toString(), "", UnitKind.ANUSVARA),
        rule("n", ANUSVARA_CHAR.toString(), "", UnitKind.ANUSVARA),
    )

    /** Stops that trigger nasal assimilation for `n`. */
    private val PLOSIVE_TRIGGERS: BooleanArray = BooleanArray(128).also { table ->
        for (trigger in "kgcjtdpb") table[trigger.code] = true
        // Aspirated digraphs start with the same letters, which is exactly the
        // behaviour we want: "sangh" -> संघ, "chandra" -> चंद्र.
    }

    /**
     * Rules bucketed by their first character. One array per ASCII letter, so a
     * match is an array walk with no hashing and no allocation.
     */
    private val buckets: Array<Array<PhoneticRule>?> = Array(128) { null }

    /**
     * Longest match first; on equal length the assimilation rule is tried first,
     * so `n` in "sanga" becomes ं and only falls back to न when the next
     * character is not a stop (that check lives in [match], and it is what keeps
     * "nepal" as नेपाल rather than ने ं पल). Kinds are ordered ANUSVARA, VOWEL,
     * CONSONANT - the reverse of the enum declaration - because the nasal is the
     * more specific reading of the same letter.
     */
    private val byLengthThenKind = Comparator<PhoneticRule> { a, b ->
        val byLength = b.roman.length.compareTo(a.roman.length)
        if (byLength != 0) byLength else b.kind.ordinal.compareTo(a.kind.ordinal)
    }

    init {
        val all = ArrayList<PhoneticRule>(VOWELS.size + CONSONANTS.size + NASALS.size)
        all += VOWELS
        all += CONSONANTS
        all += NASALS
        val grouped = HashMap<Char, MutableList<PhoneticRule>>(64)
        for (rule in all) {
            val first = rule.roman[0]
            if (first.code >= 128) continue
            grouped.getOrPut(first) { ArrayList(8) } += rule
        }
        for ((first, rules) in grouped) {
            rules.sortWith(byLengthThenKind)
            buckets[first.code] = rules.toTypedArray()
        }
    }

    /**
     * Longest rule matching `text` at [index], or null when the character is
     * passed through literally (punctuation, digits, unsupported letters).
     */
    fun match(text: CharSequence, index: Int, end: Int): PhoneticRule? {
        val first = text[index]
        if (first.code >= 128) return null
        val candidates = buckets[first.code] ?: return null
        for (candidate in candidates) {
            val length = candidate.roman.length
            if (index + length > end) {
                // A truncated rule at the very end of the buffer still has to be
                // considered for length-1 rules; longer ones simply cannot match.
                continue
            }
            if (matches(text, index, candidate.roman)) {
                if (candidate.kind == UnitKind.ANUSVARA && candidate.roman != "M") {
                    // `n` / `N` only assimilate when a stop follows.
                    if (!isAnusvaraContext(text, index + length, end)) continue
                }
                return candidate
            }
        }
        return null
    }

    /** True when the character after the nasal is a stop (assimilation applies). */
    fun isAnusvaraContext(text: CharSequence, index: Int, end: Int): Boolean {
        if (index >= end) return false
        val next = text[index]
        if (next.code >= 128) return false
        return PLOSIVE_TRIGGERS[next.code]
    }

    private fun matches(text: CharSequence, index: Int, roman: String): Boolean {
        for (offset in roman.indices) {
            if (text[index + offset] != roman[offset]) return false
        }
        return true
    }

    private fun rule(roman: String, independent: String, dependent: String, kind: UnitKind) =
        PhoneticRule(roman, independent, dependent, kind)
}
