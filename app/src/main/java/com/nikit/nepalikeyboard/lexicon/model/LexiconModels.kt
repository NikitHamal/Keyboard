package com.nikit.nepalikeyboard.lexicon.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root object of a bundled lexicon asset.
 *
 * The asset is a single JSON document under `assets/dict/`. It is decoded
 * once, off the main thread, on first use. The schema is deliberately flat and
 * primitive-heavy: every field is a String, Int, or Double so that
 * kotlinx.serialization can decode it with no custom serializers and R8 has
 * nothing clever to strip.
 *
 * @property metadata descriptive information about the asset
 * @property words the vocabulary, ordered by descending frequency so that a
 *         partial decode or a truncated asset still yields the most useful
 *         words first
 * @property bigrams the most frequent two-word transitions, used for
 *         context-sensitive ranking of the next word
 */
@Serializable
data class LexiconAsset(
    @SerialName("metadata") val metadata: LexiconMetadata,
    @SerialName("words") val words: List<LexiconEntry>,
    @SerialName("bigrams") val bigrams: List<BigramEntry> = emptyList()
)

/**
 * Descriptive information about a lexicon asset.
 *
 * @property version schema version; the loader rejects unknown versions rather
 *         than silently misinterpreting the fields
 * @property locale BCP-47 tag of the vocabulary, e.g. "ne"
 * @property wordCount number of entries in [LexiconAsset.words]
 * @property source human-readable provenance note
 * @property license license of the word list
 */
@Serializable
data class LexiconMetadata(
    @SerialName("version") val version: Int = 1,
    @SerialName("locale") val locale: String = "ne",
    @SerialName("wordCount") val wordCount: Int = 0,
    @SerialName("source") val source: String = "",
    @SerialName("license") val license: String = ""
)

/**
 * A single vocabulary entry.
 *
 * Two representations are stored per word because the Romanized engine is the
 * primary input method and the mapping from Romanized to Devanagari is
 * many-to-one:
 *
 *  * [devanagari] is the correct written form, e.g. नेपाल.
 *  * [roman] is the canonical Romanized key, e.g. "nepaal". It is lower-cased
 *    and normalised by the asset builder so that lookups can be exact rather
 *    than fuzzy.
 *  * [romanAliases] carries the spellings users actually type, which for
 *    नेपाल includes "nepal", "nepaal", and "nepAl". Having these in the asset
 *    rather than in code is what lets the lexicon resolve the inherent
 *    ambiguity of Romanized Nepali — see AGENTS.md.
 *
 * @property f relative frequency in [0, 1]. Drives base ranking.
 * @property c part-of-speech / class tag, e.g. "n" for noun, "v" for verb.
 *         Used only for display grouping today; reserved for future grammar
 *         features so that adding them does not require an asset migration.
 */
@Serializable
data class LexiconEntry(
    @SerialName("d") val devanagari: String,
    @SerialName("r") val roman: String,
    @SerialName("f") val frequency: Double,
    @SerialName("a") val romanAliases: List<String> = emptyList(),
    @SerialName("c") val wordClass: String = "n"
) {
    /**
     * All Romanized spellings that should resolve to this word, including the
     * canonical form. Allocated once at load time and cached by the trie, not
     * per lookup.
     */
    val allRomans: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        if (romanAliases.isEmpty()) listOf(roman) else buildList(romanAliases.size + 1) {
            add(roman)
            for (a in romanAliases) if (a != roman) add(a)
        }
    }
}

/**
 * A two-word transition with its relative frequency.
 *
 * @property first the Devanagari text of the first word; empty "" denotes a
 *         sentence-initial context
 * @property second the Devanagari text of the word that follows
 * @property f conditional-ish frequency in [0, 1], i.e. how often [second]
 *         follows [first] *among the observed followers of* [first]. Storing
 *         this rather than a raw count keeps the scorer's arithmetic to a
 *         single multiply.
 */
@Serializable
data class BigramEntry(
    @SerialName("a") val first: String,
    @SerialName("b") val second: String,
    @SerialName("f") val frequency: Double
)

/**
 * A candidate surfaced to the suggestion strip.
 *
 * @property text what will be committed if the user taps it
 * @property isLiteral true when this candidate is the raw Romanized input
 *         passed through unchanged rather than a Devanagari conversion. The
 *         strip renders these differently so users can tell at a glance which
 *         option escapes transliteration.
 * @property source where the candidate came from, which drives tie-breaks
 * @property score final ranking score; higher is better
 * @property frequency the lexical frequency that contributed to [score]
 */
@Serializable
data class Suggestion(
    val text: String,
    val isLiteral: Boolean = false,
    val source: SuggestionSource = SuggestionSource.LEXICON,
    val score: Double = 0.0,
    val frequency: Double = 0.0
)

/** How a [Suggestion] was produced. */
enum class SuggestionSource {
    /** The raw Romanized input, offered so the user can type Latin literally. */
    LITERAL,

    /** The best transliteration this session has produced for the input. */
    TRANSLITERATION,

    /** A dictionary word matched by the Romanized key. */
    LEXICON,

    /** A word the user has previously accepted, ranked by personal usage. */
    LEARNED,

    /** Number or symbol prediction. */
    NUMERIC
}
