package np.com.nepalikeyboard.engine

import androidx.compose.runtime.Immutable
import np.com.nepalikeyboard.engine.unicode.Devanagari

/**
 * Where a suggestion came from. The strip uses this for ordering (literal and
 * transliteration are pinned to the front) and for the type hint label.
 */
enum class CandidateKind {
    /** The raw romanized input, committed verbatim as Latin text. */
    LITERAL,

    /** Deterministic phonetic rendering of the roman buffer. */
    TRANSLITERATION,

    /** Bundled dictionary word reached through the roman radix trie. */
    LEXICAL,

    /** Word from the user's personal dictionary / learned history. */
    PERSONAL,

    /** Next-word prediction from bigram statistics. */
    PREDICTION,
}

/**
 * A single suggestion chip.
 *
 * Instances are immutable and cheap to compare, which lets Compose skip
 * recomposition of the strip when the candidate list is unchanged. Whether the
 * text needs the Devanagari font is resolved once, at construction, instead of
 * inside composition.
 */
@Immutable
class Candidate(
    /** Exactly what will be committed to the editor. */
    val text: String,
    /** Chip label. Usually identical to [text]. */
    val display: String = text,
    /** Optional romanization shown as a secondary hint. */
    val hint: String? = null,
    val kind: CandidateKind,
    val score: Int = 0,
) {
    val usesDevanagari: Boolean = text.any { Devanagari.isDevanagariBlock(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Candidate) return false
        return text == other.text && display == other.display && hint == other.hint && kind == other.kind
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + display.hashCode()
        result = 31 * result + (hint?.hashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        return result
    }

    override fun toString(): String = "Candidate($text, kind=$kind, score=$score)"
}
