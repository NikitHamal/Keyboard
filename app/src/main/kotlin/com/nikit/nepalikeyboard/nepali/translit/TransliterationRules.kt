package com.nikit.nepalikeyboard.nepali.translit

import com.nikit.nepalikeyboard.nepali.unicode.Devanagari

/**
 * The phonetic rule set that drives Romanized-Nepali transliteration.
 *
 * ## Design
 *
 * Transliteration is implemented as a **longest-match, left-to-right scan**
 * over a set of [Rule]s rather than as a context-free grammar or a heavyweight
 * finite-state transducer. The reasons:
 *
 *  1. **Determinism.** The same input always produces the same output. This
 *     matters enormously for a keyboard: a user who backspaces and retypes the
 *     same letters must get the same word back.
 *  2. **Incrementality.** Appending one character to the tail of the input can
 *     only affect the last few syllables, so the engine can keep a committed
 *     prefix and re-scan only the tail. That is what makes 120 FPS typing
 *     achievable for a transliterator.
 *  3. **Auditability.** A flat table is something a Nepali speaker can read,
 *     argue with, and correct. A transducer is not.
 *
 * ## Ordering rules
 *
 * Rules are sorted by descending [Rule.source] length so that the scan is
 * greedy: `kshya` is matched before `ksh`, which is matched before `k`. Ties
 * are broken by [Rule.priority] (lower wins), which is how the ambiguous
 * mappings below are resolved.
 *
 * ## The ambiguity problem
 *
 * Romanized Nepali has no standard. The same Devanagari word can be typed many
 * ways, and the same Latin sequence can mean different things:
 *
 *  * `sha`, `sa`, `sh` all plausibly mean स; `Sha` or `shha` mean ष.
 *  * `cha` is च; `chha` or `Cha` is छ. Many users type `cha` for छ.
 *  * `ta` is त but `Ta` is ट. Users who cannot type capitals will type `ta`
 *    for both, so we must pick the statistically likelier reading and let the
 *    lexicon resurface the alternative as a candidate.
 *  * `va` and `wa` both map to व; `ba` maps to ब, and in Nepali orthography व
 *    and ब are genuinely distinct, so we must not merge them.
 *
 * We resolve these by convention — the **case-sensitive** reading is the
 * "correct" one, matching the widely-used ITRANS/Harvard-Kyoto convention that
 * Nepali learners know — and we emit the case-insensitive alternative as a
 * lower-ranked candidate from the lexicon layer rather than from this engine.
 * That keeps this table deterministic and small, and lets statistics do the
 * disambiguation where statistics are actually informative.
 */
object TransliterationRules {

    /**
     * A single phonetic mapping.
     *
     * @property source the Latin sequence to match, already lower-cased for
     *         case-insensitive rules; see [caseSensitive].
     * @property target the Devanagari output. May be empty, which denotes
     *         "consume these letters and emit nothing" (used for silent
     *         letters and for the schwa-deletion helper).
     * @property caseSensitive when true, the rule only fires if the input
     *         casing matches [source] exactly. Used for the retroflex vs
     *         dental distinction (`Ta` → ट, `ta` → त).
     * @property priority lower is matched first when two rules have the same
     *         source length. Default 100.
     * @property kind classifies the rule's role, which the engine uses to
     *         decide whether a following vowel becomes a matra or an
     *         independent letter.
     */
    data class Rule(
        val source: String,
        val target: String,
        val caseSensitive: Boolean = false,
        val priority: Int = DEFAULT_PRIORITY,
        val kind: RuleKind = RuleKind.SEGMENT
    ) {
        companion object {
            const val DEFAULT_PRIORITY = 100
        }
    }

    /** What a rule produces, which drives vowel attachment. */
    enum class RuleKind {
        /** A consonant: a following vowel becomes a matra. */
        CONSONANT,

        /** An independent vowel: stands alone. */
        VOWEL,

        /** A dependent vowel sign (matra): attaches to the previous consonant. */
        MATRA,

        /** A structural sign such as anusvara or visarga. */
        SIGN,

        /** A punctuation or digit: passed through unchanged. */
        PASSTHROUGH,

        /** Consumes input and emits nothing. */
        SILENT,

        /** Anything else (a conjunct, an explicit virama, etc.). */
        SEGMENT
    }

    // ==================================================================
    // Vowel rules
    // ==================================================================

    /**
     * Vowels. Order matters: `au` must be tried before `a`, and `ai` before
     * `a`. The sort below handles that via source length, but keeping them
     * grouped here makes the intent readable.
     */
    private val VOWEL_RULES: List<Rule> = listOf(
        Rule("a", Devanagari.A.toString(), kind = RuleKind.VOWEL),
        Rule("aa", Devanagari.AA.toString(), kind = RuleKind.VOWEL),
        Rule("A", Devanagari.AA.toString(), caseSensitive = true, kind = RuleKind.VOWEL),
        Rule("i", Devanagari.I.toString(), kind = RuleKind.VOWEL),
        Rule("ii", Devanagari.II.toString(), kind = RuleKind.VOWEL),
        Rule("I", Devanagari.II.toString(), caseSensitive = true, kind = RuleKind.VOWEL),
        Rule("ee", Devanagari.II.toString(), priority = 110, kind = RuleKind.VOWEL),
        Rule("u", Devanagari.U.toString(), kind = RuleKind.VOWEL),
        Rule("uu", Devanagari.UU.toString(), kind = RuleKind.VOWEL),
        Rule("U", Devanagari.UU.toString(), caseSensitive = true, kind = RuleKind.VOWEL),
        Rule("oo", Devanagari.UU.toString(), priority = 110, kind = RuleKind.VOWEL),
        Rule("e", Devanagari.E.toString(), kind = RuleKind.VOWEL),
        Rule("ai", Devanagari.AI.toString(), kind = RuleKind.VOWEL),
        Rule("o", Devanagari.O.toString(), kind = RuleKind.VOWEL),
        Rule("au", Devanagari.AU.toString(), kind = RuleKind.VOWEL),
        Rule("ou", Devanagari.AU.toString(), priority = 110, kind = RuleKind.VOWEL),
        Rule("ri", Devanagari.VOCALIC_R.toString(), priority = 120, kind = RuleKind.VOWEL),
        Rule("Ri", Devanagari.VOCALIC_R.toString(), caseSensitive = true, kind = RuleKind.VOWEL)
    )

    // ==================================================================
    // Consonant rules
    // ==================================================================

    /**
     * Consonants. The case-sensitive pairs encode the retroflex/dental and
     * aspirated/unaspirated distinctions that Romanized Nepali conventionally
     * marks with capitals.
     */
    private val CONSONANT_RULES: List<Rule> = listOf(
        // ---- Velars ------------------------------------------------------
        Rule("kh", Devanagari.KHA.toString(), kind = RuleKind.CONSONANT),
        Rule("k", Devanagari.KA.toString(), kind = RuleKind.CONSONANT),
        Rule("gh", Devanagari.GHA.toString(), kind = RuleKind.CONSONANT),
        Rule("g", Devanagari.GA.toString(), kind = RuleKind.CONSONANT),
        Rule("ng", Devanagari.NGA.toString(), kind = RuleKind.CONSONANT),
        Rule("nng", Devanagari.NGA.toString(), priority = 90, kind = RuleKind.CONSONANT),

        // ---- Palatals ----------------------------------------------------
        Rule("chh", Devanagari.CHA.toString(), kind = RuleKind.CONSONANT),
        Rule("Chh", Devanagari.CHA.toString(), caseSensitive = true, kind = RuleKind.CONSONANT),
        Rule("ch", Devanagari.CA.toString(), kind = RuleKind.CONSONANT),
        Rule("jh", Devanagari.JHA.toString(), kind = RuleKind.CONSONANT),
        Rule("j", Devanagari.JA.toString(), kind = RuleKind.CONSONANT),
        Rule("ny", Devanagari.NYA.toString(), kind = RuleKind.CONSONANT),

        // ---- Retroflex (marked with capitals in the ITRANS convention) ----
        Rule("TTh", Devanagari.TTHA.toString(), caseSensitive = true, kind = RuleKind.CONSONANT),
        Rule("DDh", Devanagari.DDHA.toString(), caseSensitive = true, kind = RuleKind.CONSONANT),
        Rule("T", Devanagari.TTA.toString(), caseSensitive = true, kind = RuleKind.CONSONANT),
        Rule("D", Devanagari.DDA.toString(), caseSensitive = true, kind = RuleKind.CONSONANT),
        Rule("N", Devanagari.NNA.toString(), caseSensitive = true, kind = RuleKind.CONSONANT),
        Rule("tt", Devanagari.TTA.toString(), priority = 95, kind = RuleKind.CONSONANT),
        Rule("dd", Devanagari.DDA.toString(), priority = 95, kind = RuleKind.CONSONANT),
        Rule("nn", Devanagari.NNA.toString(), priority = 96, kind = RuleKind.CONSONANT),

        // ---- Dentals -----------------------------------------------------
        Rule("th", Devanagari.THA.toString(), kind = RuleKind.CONSONANT),
        Rule("t", Devanagari.TA.toString(), kind = RuleKind.CONSONANT),
        Rule("dh", Devanagari.DHA.toString(), kind = RuleKind.CONSONANT),
        Rule("d", Devanagari.DA.toString(), kind = RuleKind.CONSONANT),
        Rule("n", Devanagari.NA.toString(), kind = RuleKind.CONSONANT),

        // ---- Labials -----------------------------------------------------
        Rule("ph", Devanagari.PHA.toString(), kind = RuleKind.CONSONANT),
        Rule("f", Devanagari.FA.toString(), kind = RuleKind.CONSONANT),
        Rule("p", Devanagari.PA.toString(), kind = RuleKind.CONSONANT),
        Rule("bh", Devanagari.BHA.toString(), kind = RuleKind.CONSONANT),
        Rule("b", Devanagari.BA.toString(), kind = RuleKind.CONSONANT),
        Rule("m", Devanagari.MA.toString(), kind = RuleKind.CONSONANT),

        // ---- Semivowels and sibilants ------------------------------------
        // Both "v" and "w" are व; "b" is deliberately NOT merged with them,
        // because Nepali distinguishes ब from व and collapsing the two
        // produces wrong words far more often than it helps.
        Rule("v", Devanagari.VA.toString(), kind = RuleKind.CONSONANT),
        Rule("w", Devanagari.VA.toString(), priority = 110, kind = RuleKind.CONSONANT),
        Rule("y", Devanagari.YA.toString(), kind = RuleKind.CONSONANT),
        Rule("r", Devanagari.RA.toString(), kind = RuleKind.CONSONANT),
        Rule("l", Devanagari.LA.toString(), kind = RuleKind.CONSONANT),
        Rule("shh", Devanagari.SSA.toString(), kind = RuleKind.CONSONANT),
        Rule("Sh", Devanagari.SSA.toString(), caseSensitive = true, kind = RuleKind.CONSONANT),
        Rule("sh", Devanagari.SHA.toString(), kind = RuleKind.CONSONANT),
        Rule("s", Devanagari.SA.toString(), kind = RuleKind.CONSONANT),
        Rule("h", Devanagari.HA.toString(), kind = RuleKind.CONSONANT),

        // ---- Nukta consonants (Persian/Arabic loans, fully naturalised) ---
        Rule("q", Devanagari.QA.toString(), kind = RuleKind.CONSONANT),
        Rule("khh", Devanagari.KHHA.toString(), priority = 90, kind = RuleKind.CONSONANT),
        Rule("ggh", Devanagari.GHHA.toString(), priority = 90, kind = RuleKind.CONSONANT),
        Rule("z", Devanagari.ZA.toString(), kind = RuleKind.CONSONANT),
        Rule("Rh", Devanagari.RHA.toString(), caseSensitive = true, kind = RuleKind.CONSONANT),
        Rule("Dhh", Devanagari.RHA.toString(), caseSensitive = true, priority = 92, kind = RuleKind.CONSONANT)
    )

    // ==================================================================
    // Conjunct rules — must be tried before the generic consonant rules
    // because they are longer and more specific.
    // ==================================================================

    private val CONJUNCT_RULES: List<Rule> = listOf(
        Rule("kshya", Devanagari.KSHYA, priority = 10, kind = RuleKind.SEGMENT),
        Rule("kshy", Devanagari.KSHYA, priority = 11, kind = RuleKind.SEGMENT),
        Rule("ksh", Devanagari.KSHATTRA, priority = 10, kind = RuleKind.CONSONANT),
        Rule("X", Devanagari.KSHATTRA, caseSensitive = true, priority = 15, kind = RuleKind.CONSONANT),
        Rule("nx", Devanagari.NA.toString() + Devanagari.VIRAMA + Devanagari.CHA, priority = 14, kind = RuleKind.CONSONANT),
        Rule("nch", Devanagari.NA.toString() + Devanagari.VIRAMA + Devanagari.CHA, priority = 15, kind = RuleKind.CONSONANT),
        Rule("rx", Devanagari.RA.toString() + Devanagari.VIRAMA + Devanagari.CHA, priority = 14, kind = RuleKind.CONSONANT),
        Rule("rch", Devanagari.RA.toString() + Devanagari.VIRAMA + Devanagari.CHA, priority = 15, kind = RuleKind.CONSONANT),
        Rule("chha", Devanagari.CHA.toString(), priority = 12, kind = RuleKind.CONSONANT),
        Rule("x", Devanagari.CHA.toString(), priority = 20, kind = RuleKind.CONSONANT),
        Rule("gya", Devanagari.GYA, priority = 15, kind = RuleKind.CONSONANT),
        Rule("gy", Devanagari.GYA, priority = 20, kind = RuleKind.CONSONANT),
        Rule("gn", Devanagari.GYA, priority = 25, kind = RuleKind.CONSONANT),
        Rule("dny", Devanagari.GYA, priority = 25, kind = RuleKind.CONSONANT),
        Rule("tr", Devanagari.TRA, priority = 30, kind = RuleKind.CONSONANT),
        Rule("shr", Devanagari.SHRA, priority = 30, kind = RuleKind.CONSONANT),
        Rule("shri", Devanagari.SHRA + Devanagari.II, priority = 12, kind = RuleKind.SEGMENT),
        Rule("dy", Devanagari.DYA, priority = 35, kind = RuleKind.CONSONANT),
        Rule("tt", Devanagari.TTA_CONJUNCT, priority = 95, kind = RuleKind.CONSONANT)
    )

    // ==================================================================
    // Signs and punctuation
    // ==================================================================

    private val SIGN_RULES: List<Rule> = listOf(
        Rule("nz", Devanagari.ANUSVARA_STR, priority = 20, kind = RuleKind.SIGN),
        Rule("M", Devanagari.ANUSVARA_STR, caseSensitive = true, priority = 20, kind = RuleKind.SIGN),
        Rule("m~", Devanagari.CANDRABINDU_STR, priority = 15, kind = RuleKind.SIGN),
        Rule("~", Devanagari.CANDRABINDU_STR, priority = 20, kind = RuleKind.SIGN),
        Rule("H", Devanagari.VISARGA.toString(), caseSensitive = true, priority = 20, kind = RuleKind.SIGN),
        Rule("h~", Devanagari.VISARGA.toString(), priority = 25, kind = RuleKind.SIGN)
    )

    /**
     * Rules that consume input and emit nothing, or that alter the vowel
     * attachment of the *previous* syllable.
     *
     * The leading apostrophe is the standard Romanized-Nepali device for
     * suppressing the inherent vowel: `ram'` produces राम rather than रामा.
     * We also accept `_` and `.` as separators, because mobile keyboards make
     * the apostrophe awkward and users improvise.
     */
    private val CONTROL_RULES: List<Rule> = listOf(
        Rule("'", "", priority = 5, kind = RuleKind.SILENT),
        Rule("_", "", priority = 5, kind = RuleKind.SILENT),
        Rule(".n", Devanagari.ANUSVARA_STR, priority = 18, kind = RuleKind.SIGN)
    )

    /**
     * Everything, sorted for the greedy longest-match scan.
     *
     * Sorting is by (descending source length, ascending priority). Kotlin's
     * `sortedWith` is stable, so equal keys preserve declaration order, which
     * is why the tables above are written in the order a human would want.
     */
    val ALL_RULES: List<Rule> = buildList {
        addAll(CONJUNCT_RULES)
        addAll(CONTROL_RULES)
        addAll(SIGN_RULES)
        addAll(CONSONANT_RULES)
        addAll(VOWEL_RULES)
    }.sortedWith(
        compareByDescending<Rule> { it.source.length }
            .thenBy { it.priority }
    )

    /**
     * Index the rules by their first character so the scan can skip
     * straight to the relevant candidates instead of trying all ~90 rules at
     * every input position.
     *
     * Keyed on the *lower-cased* first char. Case-sensitive rules are filtered
     * after lookup by [Rule.caseSensitive], which costs one comparison and
     * keeps the index small.
     */
    val RULES_BY_FIRST_CHAR: Map<Char, List<Rule>> = ALL_RULES
        .groupBy { it.source[0].lowercaseChar() }
        .mapValues { (_, rules) -> rules.sortedWith(
            compareByDescending<Rule> { it.source.length }.thenBy { it.priority }
        ) }

    /**
     * Characters that can begin a rule. Used by the engine to short-circuit:
     * if the next input character is not in this set, it is emitted verbatim
     * and we do not touch the rule index at all.
     */
    val RULE_INITIALS: BooleanArray = BooleanArray(128).also { arr ->
        for (key in RULES_BY_FIRST_CHAR.keys) {
            if (key.code < 128) arr[key.code] = true
        }
    }

    // ==================================================================
    // Schwa deletion
    // ==================================================================

    /**
     * Words whose final inherent /a/ must be kept. Nepali orthography deletes
     * the word-final schwa in most native words (राम is /ram/, not /rama/ and
     * not /raama/), but a closed set of words keeps it, usually because they
     * are loans, onomatopoeia, or one syllable long.
     *
     * Stored as *Romanized* forms because this check runs before the word has
     * been converted. Comparisons are case-insensitive.
     */
    val SCHWA_RETENTION_WORDS: Set<String> = setOf(
        // Very short words where the final vowel is phonologically real.
        "ma", "ta", "ra", "ki", "ka", "la", "na", "cha", "ho", "xa",
        "ba", "ja", "ga", "da", "pa", "sa", "ha", "ya", "va",
        // Common loans and high-frequency words that keep a final vowel.
        "data", "drama", "sala", "kala", "pala", "nara", "dhara",
        "papa", "mama", "baba", "nana", "tara", "kara", "para",
        "yatra", "mitra", "putra", "patra", "shastra", "mantra",
        // Nepali words ending in a pronounced -a / matra aa.
        "jharna", "khukura", "aama", "bhai", "didi", "bahu",
        "guru", "jetha", "kancha", "thula", "sano", "dhoka", "hawa",
        "maya", "katha", "ghatana", "yojana", "janata", "janta",
        "mula", "paisa", "sathi", "kura", "chinta", "neta", "sewa",
        "sukha", "dukkha", "jhagada", "samaya", "bichar", "khana",
        "bata", "khoja", "rakha", "bana", "deu", "leu"
    )

    /**
     * Prefixes after which a following schwa is reliably retained, because
     * the prefix itself ends in a real vowel.
     */
    val SCHWA_RETENTION_PREFIXES: List<String> = listOf(
        "pra", "para", "pari", "prati", "anu", "abhi", "upa", "vi", "ni",
        "sam", "san", "su", "dur", "dus", "nir", "nis", "ut", "ud"
    )

    /**
     * Vowel sequences that are never a schwa and must keep their vowel sign.
     */
    val VOWEL_LETTERS: Set<Char> = setOf(
        'a', 'i', 'u', 'e', 'o', 'A', 'I', 'U', 'E', 'O'
    )

    // ==================================================================
    // Convenience accessors used by the engine and by tests
    // ==================================================================

    /** Look up candidate rules that could start at a character. */
    fun candidatesFor(first: Char): List<Rule> =
        RULES_BY_FIRST_CHAR[first.lowercaseChar()] ?: emptyList()

    /**
     * True when [word] (a Romanized form) should keep its word-final inherent
     * vowel.
     */
    fun keepsFinalSchwa(word: String): Boolean {
        if (word.isEmpty()) return true
        val lower = word.lowercase()
        if (lower in SCHWA_RETENTION_WORDS) return true
        // Single-character syllables always keep the vowel.
        if (word.length <= 2) return true
        // A word ending in an explicit vowel letter is not a schwa case.
        val last = word[word.length - 1]
        if (last in VOWEL_LETTERS) {
            // Ending in "a" *is* the schwa case; anything else is a real vowel.
            return last != 'a'
        }
        return false
    }

    /** Total rule count, exposed for the settings screen's diagnostics panel. */
    val ruleCount: Int get() = ALL_RULES.size
}
