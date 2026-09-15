package np.com.nepalikeyboard.keyboard

import androidx.compose.runtime.Immutable

/** Semantic key roles. The hot path dispatches on this enum: no string work. */
enum class KeyKind {
    /** Emits [KeyDef.output] into the editor (letter, digit, punctuation, matra). */
    CHARACTER,

    /** Shift / caps-lock cycle, also the Devanagari "extras page" toggle. */
    SHIFT,

    /** Grapheme-cluster delete, hosts swipe-to-delete. */
    BACKSPACE,

    /** Space: hosts cursor gliding, commits the composition on tap. */
    SPACE,

    /** Performs the editor action (send/search/go/next/done) or inserts a newline. */
    ENTER,

    /** Cycles the script: Romanized -> Devanagari -> English. */
    LAYOUT_SWITCH,

    /** Opens the symbol layer of the current script. */
    SYMBOLS,

    /** Opens the emoji panel. */
    EMOJI,

    /** Opens the clipboard history panel. */
    CLIPBOARD,

    /** Launches the settings activity. */
    SETTINGS,

    /** Toggles one-handed mode. */
    ONE_HANDED,

    /** Hides the input view. */
    HIDE,

    COMMA,

    PERIOD,
}

/** Which font family and emphasis a key cap should use. */
enum class KeyLabelStyle {
    LATIN,
    DEVANAGARI,
    ICON,
    ACTION,
}

/**
 * One physical key.
 *
 * Keys are built once per (layout, shift) pair and cached, then passed around by
 * reference during pointer dispatch, so a keypress never allocates.
 */
@Immutable
class KeyDef(
    val id: Int,
    val label: String,
    val output: String,
    val kind: KeyKind,
    val weight: Float = 1f,
    val labelStyle: KeyLabelStyle = KeyLabelStyle.LATIN,
    val labelRes: Int = 0,
    val longPress: List<String> = emptyList(),
) {
    val isCharacter: Boolean get() = kind == KeyKind.CHARACTER

    override fun toString(): String = "KeyDef($label, $kind)"

    companion object {
        private const val SPECIAL_BASE = 0x0010_0000

        /** Deterministic identity for Compose `key()` and animation state. */
        fun keyId(kind: KeyKind, output: String): Int =
            if (kind == KeyKind.CHARACTER) {
                SPECIAL_BASE + (output.hashCode() and 0x7FFF)
            } else {
                SPECIAL_BASE * 2 + kind.ordinal
            }
    }
}

/** A row of keys with optional fractional indentation on either side. */
@Immutable
class KeyRow(
    val keys: List<KeyDef>,
    val indentLeft: Float = 0f,
    val indentRight: Float = 0f,
) {
    val totalWeight: Float
        get() {
            var sum = indentLeft + indentRight
            for (key in keys) sum += key.weight
            return sum
        }
}

enum class LayoutId {
    ENGLISH,
    ROMAN,
    DEVANAGARI,
    DEVANAGARI_ALT,
    SYMBOLS,
    SYMBOLS_ALT,
    NUMERIC,
    PHONE,
    EMAIL,
    URI,
}

/**
 * True for layouts that carry letters (and therefore honour the optional number
 * row and the shift state). Symbol, numeric and phone pads never do.
 */
val LayoutId.isLetterLayout: Boolean
    get() = when (this) {
        LayoutId.ENGLISH, LayoutId.ROMAN, LayoutId.DEVANAGARI, LayoutId.DEVANAGARI_ALT,
        LayoutId.EMAIL, LayoutId.URI,
        -> true

        LayoutId.SYMBOLS, LayoutId.SYMBOLS_ALT, LayoutId.NUMERIC, LayoutId.PHONE -> false
    }

/**
 * A complete keyboard layer.
 *
 * Rows may differ in length and key weights; the renderer normalises each row
 * independently (that is what makes a 9-key home row sit flush with a 10-key top
 * row while the space bar still spans four units).
 */
@Immutable
class KeyboardLayout(
    val id: LayoutId,
    val rows: List<KeyRow>,
) {
    /** Flat, row-major copy used for O(1) hit testing and Compose iteration. */
    val keys: List<KeyDef> = buildList {
        for (row in rows) addAll(row.keys)
    }

    val keyCount: Int get() = keys.size

    /** Number of weights occupied by each row, used by [KeyboardGeometry]. */
    val rowWeights: FloatArray = FloatArray(rows.size) { index -> rows[index].totalWeight }

    /** Index of the first key of each row inside [keys]. */
    val rowStarts: IntArray = IntArray(rows.size).also { starts ->
        var cursor = 0
        for (index in rows.indices) {
            starts[index] = cursor
            cursor += rows[index].keys.size
        }
    }

    fun keyAt(index: Int): KeyDef? = if (index in keys.indices) keys[index] else null

    fun rowOf(index: Int): Int {
        var row = 0
        for (i in rowStarts.indices) {
            if (index >= rowStarts[i]) row = i else break
        }
        return row
    }
}
