package com.nikit.nepalikeyboard.nepali.lexicon

import com.nikit.nepalikeyboard.nepali.lexicon.model.BigramEntry
import com.nikit.nepalikeyboard.nepali.lexicon.model.LexiconEntry
import com.nikit.nepalikeyboard.nepali.lexicon.model.Suggestion
import com.nikit.nepalikeyboard.nepali.lexicon.model.SuggestionSource
import com.nikit.nepalikeyboard.nepali.translit.RomanizedEngine
import com.nikit.nepalikeyboard.nepali.translit.TransliterationRules

/**
 * Ranks candidate words for the suggestion strip.
 *
 * ## Scoring model
 *
 * The final score for a candidate is a weighted sum of four independent
 * signals, each normalised to roughly `[0, 1]`:
 *
 * ```
 *   score = w_freq   * frequency
 *         + w_prefix * prefixMatchQuality
 *         + w_bigram * bigramAffinity
 *         + w_learn  * personalUsage
 *         + w_exact  * exactKeyMatch
 * ```
 *
 * The weights are constants rather than user-tunable. Exposing them would let a
 * user make the keyboard worse, and there is no single "correct" setting — the
 * defaults below were chosen so that:
 *
 *  * an exact key match always outranks a mere prefix match, because typing the
 *    whole word and getting something else is the single most infuriating
 *    failure mode;
 *  * frequency dominates among equally-good matches, because that is what
 *    makes the strip feel like it knows the language;
 *  * personal usage (words the user actually accepted before) can overcome a
 *    moderate frequency deficit, because individual vocabulary varies hugely;
 *  * bigram context breaks ties and reorders near-equal candidates, which is
 *    the main thing it is good for.
 *
 * ## Locality
 *
 * The scorer owns the only mutable ranking state in the lexicon layer: a
 * "last committed word" used for bigram context. Everything else is read-only.
 * It is therefore confined to the engine's single background dispatcher and is
 * **not** thread-safe by design — making it thread-safe would mean
 * synchronisation on a path that runs on every keystroke.
 */
class CandidateRanker {

    /**
     * Two-word transitions indexed by the *previous* word for O(1) context
     * lookup. A `HashMap` keyed on a String allocates on lookup only if the key
     * is not already interned, which is why keys are stored as the exact
     * strings from the bigram table and looked up with those same instances
     * wherever possible.
     */
    private val bigramIndex: HashMap<String, ArrayList<BigramEntry>> = HashMap(4096)

    /** Highest conditional frequency observed overall, used to normalise bigram affinity. */
    private var maxBigramFrequency: Double = 1.0

    /**
     * Personal usage counts, keyed by Devanagari word. Populated from the
     * DataStore-backed learned-words store. Bounded by [MAX_LEARNED_ENTRIES].
     */
    private val personalUsage: HashMap<String, Int> = HashMap(256)

    /** Highest personal usage count, used to normalise the learned signal. */
    private var maxPersonalUsage: Int = 1

    /** The previously committed word, or "" at a sentence boundary. */
    var previousWord: String = ""
        private set

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Feed the bigram table. Call once during load, before any ranking. */
    fun loadBigrams(bigrams: List<BigramEntry>) {
        bigramIndex.clear()
        maxBigramFrequency = 1.0
        for (b in bigrams) {
            if (b.first.isEmpty() && b.second.isEmpty()) continue
            val list = bigramIndex.getOrPut(b.first) { ArrayList(4) }
            list.add(b)
            if (b.frequency > maxBigramFrequency) maxBigramFrequency = b.frequency
        }
    }

    /** Replace the learned-word table wholesale. */
    fun loadPersonalUsage(usage: Map<String, Int>) {
        personalUsage.clear()
        maxPersonalUsage = 1
        for ((word, count) in usage) {
            personalUsage[word] = count
            if (count > maxPersonalUsage) maxPersonalUsage = count
        }
    }

    /** Increment the usage count for [word]. Called when the user accepts it. */
    fun recordUsage(word: String) {
        if (word.isEmpty()) return
        val next = (personalUsage[word] ?: 0) + 1
        personalUsage[word] = next
        if (next > maxPersonalUsage) maxPersonalUsage = next
    }

    /**
     * Remember [word] as the context for the next ranking pass.
     *
     * Called when a word is committed with a space or punctuation. Passing an
     * empty string resets the context, which is what happens after a sentence
     * terminator.
     */
    fun setContext(word: String) {
        previousWord = word
    }

    /** Clear the bigram context. */
    fun resetContext() {
        previousWord = ""
    }

    // ------------------------------------------------------------------
    // Ranking
    // ------------------------------------------------------------------

    /**
     * Score and sort [candidates] in place, returning the best [limit].
     *
     * @param candidates the raw candidate entries from the trie
     * @param romanInput the exact Romanized text the user has typed
     * @param limit maximum suggestions to return
     */
    fun rank(
        candidates: List<LexiconEntry>,
        romanInput: String,
        limit: Int
    ): List<Suggestion> {
        if (candidates.isEmpty()) return emptyList()

        val inputLower = romanInput.lowercase()
        val inputLen = inputLower.length
        val context = previousWord

        val scored = ArrayList<Suggestion>(candidates.size)

        for (entry in candidates) {
            val key = entry.roman
            val keyLower = key.lowercase()

            // ---------------- frequency ----------------
            val freqScore = entry.frequency.coerceIn(0.0, 1.0)

            // ---------------- prefix match quality ----------------
            // An exact key match is worth more than any prefix match, and a
            // match that consumes more of the key is worth more than one that
            // consumes less. This is what stops "nep" from outranking "nepal"
            // when the user has typed all five letters.
            val prefixScore: Double = when {
                keyLower == inputLower -> 1.0
                inputLen == 0 -> 0.0
                keyLower.startsWith(inputLower) -> {
                    // Ratio of the key that the input already covers.
                    inputLen.toDouble() / keyLower.length.toDouble()
                }
                else -> {
                    // The input matched via an alias rather than the canonical
                    // key. Give it a solid but not top score.
                    0.55
                }
            }

            // ---------------- exact key match bonus ----------------
            val exactBonus = if (keyLower == inputLower) 1.0 else 0.0

            // ---------------- bigram affinity ----------------
            val bigramScore = if (context.isEmpty()) {
                // At a sentence boundary, prefer words that commonly *start* a
                // sentence. Those are recorded under the empty-string key.
                bigramAffinity("", entry.devanagari)
            } else {
                bigramAffinity(context, entry.devanagari)
            }

            // ---------------- personal usage ----------------
            val usage = personalUsage[entry.devanagari] ?: 0
            val learnScore = if (usage == 0) {
                0.0
            } else {
                // Logarithmic so that the tenth use of a word is not ten times
                // as strong a signal as the first.
                (kotlin.math.ln(1.0 + usage) / kotlin.math.ln(1.0 + maxPersonalUsage))
                    .coerceIn(0.0, 1.0)
            }

            val score =
                W_FREQUENCY * freqScore +
                    W_PREFIX * prefixScore +
                    W_EXACT * exactBonus +
                    W_BIGRAM * bigramScore +
                    W_LEARNED * learnScore

            scored.add(
                Suggestion(
                    text = entry.devanagari,
                    isLiteral = false,
                    source = when {
                        usage > 0 -> SuggestionSource.LEARNED
                        else -> SuggestionSource.LEXICON
                    },
                    score = score,
                    frequency = entry.frequency
                )
            )
        }

        // Sort descending by score, then by frequency, then alphabetically so
        // that the ordering is fully deterministic. A non-deterministic order
        // would make the strip jitter between identical inputs.
        scored.sortWith(
            compareByDescending<Suggestion> { it.score }
                .thenByDescending { it.frequency }
                .thenBy { it.text }
        )

        return if (scored.size > limit) {
            ArrayList(scored.subList(0, limit))
        } else {
            scored
        }
    }

    /**
     * How strongly [second] tends to follow [first].
     *
     * Returns a value in `[0, 1]`. When there is no recorded transition we
     * return a small non-zero floor rather than zero: a hard zero would make
     * the bigram term a pure filter, which discards the words that simply
     * happen to be absent from a small table.
     */
    private fun bigramAffinity(first: String, second: String): Double {
        if (second.isEmpty()) return 0.0

        val list = bigramIndex[first] ?: return BIGRAM_FLOOR
        if (list.isEmpty()) return BIGRAM_FLOOR

        // The per-context lists are short (a handful of followers each), so a
        // linear scan beats building a nested map and costs no allocation.
        var best = -1.0
        for (i in list.indices) {
            val b = list[i]
            if (b.second == second) {
                if (b.frequency > best) best = b.frequency
            }
        }
        if (best < 0.0) return BIGRAM_FLOOR

        // Normalise against the strongest transition in the whole table so the
        // signal is comparable across contexts.
        val normalised = best / maxBigramFrequency
        return (BIGRAM_FLOOR + (1.0 - BIGRAM_FLOOR) * normalised).coerceIn(0.0, 1.0)
    }

    // ------------------------------------------------------------------
    // Suggestion assembly
    // ------------------------------------------------------------------

    /**
     * Build the complete suggestion strip.
     *
     * The strip is ordered exactly as the product specification requires:
     *
     *  1. **Literal Romanized input** — always first, so a user who wants to
     *     type Latin (a password, a URL fragment, an English word the lexicon
     *     does not know) can always escape transliteration with one tap.
     *  2. **The top transliteration match** — what the phonetic engine produces
     *     for the whole input. This is the "trust the keyboard" option.
     *  3. **Lexical/statistical alternatives** — dictionary words and learned
     *     words ranked by the scorer above.
     *
     * Duplicates are removed by display text so the same Devanagari string
     * never appears twice, and the literal option is suppressed when the input
     * is empty or when the input is already Devanagari.
     */
    fun buildSuggestions(
        romanInput: String,
        transliteration: String,
        lexical: List<Suggestion>,
        limit: Int,
        includeLiteral: Boolean
    ): List<Suggestion> {
        val out = ArrayList<Suggestion>(limit)
        val seen = HashSet<String>(limit * 2)

        // 1. Literal Romanized input.
        if (includeLiteral && romanInput.isNotEmpty()) {
            if (seen.add(romanInput)) {
                out.add(
                    Suggestion(
                        text = romanInput,
                        isLiteral = true,
                        source = SuggestionSource.LITERAL,
                        // Just below the top transliteration so that a confident
                        // conversion wins, but above weak lexical matches.
                        score = LITERAL_SCORE,
                        frequency = 0.0
                    )
                )
            }
        }

        // 2. Best transliteration.
        if (transliteration.isNotEmpty()) {
            if (seen.add(transliteration)) {
                out.add(
                    Suggestion(
                        text = transliteration,
                        isLiteral = false,
                        source = SuggestionSource.TRANSLITERATION,
                        score = TRANSLITERATION_SCORE,
                        frequency = 0.0
                    )
                )
            }
        }

        // 3. Lexical candidates, already sorted.
        for (s in lexical) {
            if (out.size >= limit) break
            if (s.text.isEmpty()) continue
            if (seen.add(s.text)) out.add(s)
        }

        return out
    }

    /** Number of learned words currently tracked. For the settings screen. */
    val learnedWordCount: Int get() = personalUsage.size

    /** Number of bigram contexts currently indexed. For the settings screen. */
    val bigramContextCount: Int get() = bigramIndex.size

    companion object {
        // ---- Scoring weights ------------------------------------------
        private const val W_FREQUENCY = 1.00
        private const val W_PREFIX = 0.85
        private const val W_EXACT = 1.20
        private const val W_BIGRAM = 0.40
        private const val W_LEARNED = 0.55

        /** Floor applied when no transition is recorded for a pair. */
        private const val BIGRAM_FLOOR = 0.02

        /** Score assigned to the literal-input option in the strip. */
        private const val LITERAL_SCORE = 1.55

        /** Score assigned to the engine's own transliteration. */
        private const val TRANSLITERATION_SCORE = 1.90

        /** Upper bound on retained learned words, to keep the map small. */
        const val MAX_LEARNED_ENTRIES = 512

        /**
         * Translate a Romanized string into the *display* form used by the
         * strip's literal option, which is simply the input unchanged. Kept as
         * a named function so the intent is explicit at the call site.
         */
        fun literalFor(romanInput: String): String = romanInput

        /**
         * Produce the engine's best transliteration for a complete word. Used
         * as the second strip entry.
         */
        fun transliterationFor(romanInput: String): String =
            RomanizedEngine.toDevanagari(romanInput, isComplete = true)

        /** True when [word] is a plausible word to learn. */
        fun isLearnable(word: String): Boolean =
            word.length > 1 && !TransliterationRules.keepsFinalSchwa(word)
    }
}
