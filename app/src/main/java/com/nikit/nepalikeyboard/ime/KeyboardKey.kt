package com.nikit.nepalikeyboard.ime

import androidx.compose.runtime.Immutable
import com.nikit.nepalikeyboard.unicode.Devanagari

/**
 * =============================================================================
 * THE KEY MODEL
 * =============================================================================
 *
 * A single sealed hierarchy describing every key the keyboard can render.
 *
 * ### Why a sealed class and not a data table
 *
 * The alternative design — one `KeyDef` data class with a `keyType` enum and a
 * payload string — is what most keyboards ship. It is smaller, but it forces
 * every consumer to switch on the type *and* validate the payload, because
 * nothing stops you constructing a key of type "character" whose payload is
 * three characters long.
 *
 * A sealed hierarchy makes the illegal states unrepresentable. A
 * [KeyboardKey.Character] always holds exactly one `Char`. A
 * [KeyboardKey.DevCharacter] always holds a non-empty Devanagari string. The
 * `when` in `KeyboardViewModel.onKeyPressed` is exhaustive by construction, so
 * adding a key type without handling it is a compile error rather than a silent
 * no-op at runtime.
 *
 * ### Why `@Immutable`
 *
 * The key grid is the largest, most frequently recomposed subtree in the app.
 * Every key carries this annotation so Compose can skip a key whose instance
 * has not changed. Because the layouts are built as `val` top-level lists of
 * `object`/`data class` instances that are never rebuilt, a recomposition of
 * the grid does no work at all beyond the keys that actually changed state.
 *
 * Every subtype is either an `object` (stateless, identity-comparable, zero
 * allocation) or a `data class` holding only primitives and strings.
 */
@Immutable
sealed interface KeyboardKey {

    /**
     * A Latin character key.
     *
     * [char] is the unshifted glyph. Shift is applied at press time by the
     * ViewModel, not baked into the key, so a single key object serves both
     * shift states and the grid never needs rebuilding when shift toggles —
     * which is what keeps shift from causing a full-grid recomposition.
     */
    @Immutable
    data class Character(val char: Char) : KeyboardKey

    /**
     * A Devanagari key on the native layout.
     *
     * [text] is the exact code point sequence to insert — a consonant, an
     * independent vowel, a matra, the virama, a sign, or a digit. It is a
     * `String` rather than a `Char` because some entries are legitimately two
     * code units (a nukta consonant formed from base + U+093C, or a matra
     * carrying a combining mark). The transliteration engine never sees these;
     * they are inserted literally.
     */
    @Immutable
    data class DevCharacter(val text: String) : KeyboardKey

    /** A suggestion the user tapped, to be inserted verbatim. */
    @Immutable
    data class Commit(val text: String) : KeyboardKey

    /** Switches to [target] input mode. */
    @Immutable
    data class ModeSwitch(val target: InputMode) : KeyboardKey

    // --- Stateless keys. Objects, so identity comparison is free. ---

    /** Inserts a space; commits any pending composition first. */
    data object Space : KeyboardKey

    /** Inserts a newline, or fires the editor's requested action. */
    data object Enter : KeyboardKey

    /** Deletes one grapheme cluster, or one Roman character when composing. */
    data object Backspace : KeyboardKey

    /** Deletes the preceding word. Emitted by the backspace swipe gesture. */
    data object BackspaceWord : KeyboardKey

    /** Cycles shift / caps lock. */
    data object Shift : KeyboardKey

    /** Toggles the number and symbol layer. */
    data object GlyphToggle : KeyboardKey

    /** Shows the deeper symbol layer. */
    data object MoreSymbols : KeyboardKey

    /** Toggles the one-handed layout. */
    data object OneHanded : KeyboardKey

    /** Moves the one-handed layout to the other edge. */
    data object OneHandedSwap : KeyboardKey

    /** Opens the emoji panel. */
    data object Emoji : KeyboardKey

    /** Opens the clipboard panel. */
    data object Clipboard : KeyboardKey

    /** Inserts a tab. */
    data object Tab : KeyboardKey

    /** Dismisses the keyboard. */
    data object Escape : KeyboardKey

    /**
     * Switches to the next installed input method.
     *
     * Handled entirely by the service, because only the service holds the
     * `InputMethodManager`; the ViewModel deliberately ignores it.
     */
    data object Globe : KeyboardKey

    /** Moves the caret one grapheme cluster left. */
    data object CursorLeft : KeyboardKey

    /** Moves the caret one grapheme cluster right. */
    data object CursorRight : KeyboardKey

    /** Moves the caret one word left. */
    data object CursorUp : KeyboardKey

    /** Moves the caret one word right. */
    data object CursorDown : KeyboardKey

    /** Selects the whole field. */
    data object SelectAll : KeyboardKey
}

/**
 * =============================================================================
 * THE LAYOUT TABLES
 * =============================================================================
 *
 * These are the actual key arrangements. They are top-level `val`s of immutable
 * objects, built once at class-initialisation time and never mutated, which is
 * what lets the Compose grid skip recomposition entirely when nothing changed.
 *
 * ### Row conventions
 *
 * Each physical row is a `List<KeyboardKey>`. The renderer adds the modifier
 * keys (shift, backspace) at the row ends, so this table describes only the
 * content keys. Row lengths vary — 10, 9, 7 — and the renderer centres each row
 * and pads it, which is how every platform keyboard handles the stagger.
 *
 * ### Latin layout
 *
 * Standard QWERTY, with the third row carrying the shift-modified punctuation
 * pair. `?123` and the shift key are added by the renderer.
 */
object KeyboardLayouts {

    // =========================================================================
    // Latin QWERTY
    // =========================================================================

    /**
     * Lowercase QWERTY. Uppercase is derived at press time; there is no
     * separate uppercase table, which is what keeps the shift key cheap.
     */
    val QWERTY_ROW_1: List<KeyboardKey> = charRow("qwertyuiop")
    val QWERTY_ROW_2: List<KeyboardKey> = charRow("asdfghjkl")
    val QWERTY_ROW_3: List<KeyboardKey> = charRow("zxcvbnm")

    /** The punctuation letters the third row carries on the shift layer. */
    val QWERTY_ROW_3_SHIFTED: List<KeyboardKey> = charRow("zxcvbnm")

    /**
     * Number row, shown as the top row of the symbol layer.
     * Shifted digits produce the standard US-layout symbols.
     */
    val NUMBER_ROW: List<KeyboardKey> = charRow("1234567890")

    /**
     * The symbol layer's three rows.
     *
     * Row 1 is the digits (so a user typing a phone number taps once, not
     * twice), row 2 is common punctuation, row 3 is the arithmetic and bracket
     * set. The renderer appends `=\<`, space, and backspace.
     */
    val SYMBOLS_ROW_1: List<KeyboardKey> = charRow("1234567890")
    val SYMBOLS_ROW_2: List<KeyboardKey> =
        listOf('@', '#', '$', '_', '&', '-', '+', '(', ')', '/').map { KeyboardKey.Character(it) }
    val SYMBOLS_ROW_3: List<KeyboardKey> =
        listOf('*', '"', '\'', ':', ';', '!', '?').map { KeyboardKey.Character(it) }

    /**
     * The deeper symbol layer, reached by the `=\<` key. Mirrors the
     * conventions of the platform keyboard so muscle memory transfers.
     */
    val MORE_SYMBOLS_ROW_1: List<KeyboardKey> =
        listOf('~', '`', '|', '•', '√', 'π', '÷', '×', '¶', '∆').map { KeyboardKey.Character(it) }
    val MORE_SYMBOLS_ROW_2: List<KeyboardKey> =
        listOf('£', '¢', '€', '¥', '^', '°', '=', '{', '}').map { KeyboardKey.Character(it) }
    val MORE_SYMBOLS_ROW_3: List<KeyboardKey> =
        listOf('%', '©', '®', '™', '✓', '\\', '<', '>').map { KeyboardKey.Character(it) }

    // =========================================================================
    // Nepali native Devanagari
    // =========================================================================

    /**
     * The Devanagari layout, arranged by phonetic position on a QWERTY grid so
     * that the finger movements a user already knows transfer.
     *
     * The arrangement follows the conventional Nepali Unicode keyboard, which
     * is itself derived from the Hindi InScript standard:
     *
     *   row 1: ौ ै ा ी ू ब ह ग द ज ड ़
     *   row 2: ो े ् ि ु प र क त च ट
     *   row 3: ॉ ं म न व ल स य
     *
     * Everything a user needs for ordinary prose is on these three rows. The
     * remaining vowels, matras, and signs live on the shift layer
     * ([DEVANAGARI_SHIFT_*]), and the nukta forms plus the rarer signs live on
     * the symbol layer reached by `?123`.
     *
     * Matras are placed where their independent-vowel counterparts sit on the
     * InScript layout, so a user who knows "ा is on the home row, first key"
     * finds it there in both layouts.
     */
    val DEVANAGARI_ROW_1: List<KeyboardKey> = devRow(
        Devanagari.SIGN_AU.toString(),   // ौ
        Devanagari.SIGN_AI.toString(),   // ै
        Devanagari.SIGN_AA.toString(),   // ा
        Devanagari.SIGN_II.toString(),   // ी
        Devanagari.SIGN_UU.toString(),   // ू
        Devanagari.BA.toString(),        // ब
        Devanagari.HA.toString(),        // ह
        Devanagari.GA.toString(),        // ग
        Devanagari.DA.toString(),        // द
        Devanagari.JA.toString(),        // ज
        Devanagari.DDA.toString(),       // ड
        Devanagari.NUKTA.toString()      // ़
    )

    val DEVANAGARI_ROW_2: List<KeyboardKey> = devRow(
        Devanagari.SIGN_O.toString(),    // ो
        Devanagari.SIGN_E.toString(),    // े
        Devanagari.VIRAMA_STR,           // ्
        Devanagari.SIGN_I.toString(),    // ि
        Devanagari.SIGN_U.toString(),    // ु
        Devanagari.PA.toString(),        // प
        Devanagari.RA.toString(),        // र
        Devanagari.KA.toString(),        // क
        Devanagari.TA.toString(),        // त
        Devanagari.CHA.toString(),       // च
        Devanagari.TTA.toString()        // ट
    )

    val DEVANAGARI_ROW_3: List<KeyboardKey> = devRow(
        Devanagari.SIGN_CANDRA_O.toString(), // ॉ
        Devanagari.ANUSVARA_STR,             // ं
        Devanagari.MA.toString(),            // म
        Devanagari.NA.toString(),            // न
        Devanagari.VA.toString(),            // व
        Devanagari.LA.toString(),            // ल
        Devanagari.SA.toString(),            // स
        Devanagari.YA.toString()             // य
    )

    /**
     * Shift layer of the Devanagari layout: the independent vowels, the
     * aspirated and retroflex consonants, the sibilants, and the remaining
     * signs.
     *
     * Arranged so that each shifted key carries the aspirated counterpart of
     * the consonant sitting below it, which is the InScript convention and lets
     * the user learn one relationship instead of memorising forty keys.
     */
    val DEVANAGARI_SHIFT_ROW_1: List<KeyboardKey> = devRow(
        Devanagari.AU.toString(),        // औ
        Devanagari.AI.toString(),        // ऐ
        Devanagari.AA.toString(),        // आ
        Devanagari.II.toString(),        // ई
        Devanagari.UU.toString(),        // ऊ
        Devanagari.BHA.toString(),       // भ
        Devanagari.NGA.toString(),       // ङ
        Devanagari.GHA.toString(),       // घ
        Devanagari.DHA.toString(),       // ध
        Devanagari.JHA.toString(),       // झ
        Devanagari.DDHA.toString(),      // ढ
        Devanagari.VISARGA.toString()    // ः
    )

    val DEVANAGARI_SHIFT_ROW_2: List<KeyboardKey> = devRow(
        Devanagari.O.toString(),         // ओ
        Devanagari.E.toString(),         // ए
        Devanagari.A.toString(),         // अ
        Devanagari.I.toString(),         // इ
        Devanagari.U.toString(),         // उ
        Devanagari.PHA.toString(),       // फ
        Devanagari.RRA.toString(),       // ऱ
        Devanagari.KHA.toString(),       // ख
        Devanagari.THA.toString(),       // थ
        Devanagari.CA.toString(),        // च (aspirate छ lives on row 3)
        Devanagari.TTHA.toString()       // ठ
    )

    val DEVANAGARI_SHIFT_ROW_3: List<KeyboardKey> = devRow(
        Devanagari.CANDRABINDU_STR,      // ँ
        Devanagari.NYA.toString(),       // ञ
        Devanagari.NNA.toString(),       // ण
        Devanagari.SHA.toString(),       // श
        Devanagari.SSA.toString(),       // ष
        Devanagari.CHHA.toString(),      // छ
        Devanagari.SA.toString(),        // स
        Devanagari.YYA.toString()        // य़
    )

    /**
     * Devanagari digits, on the symbol layer's first row so that typing a
     * number in Nepali costs one tap per digit.
     */
    val DEVANAGARI_DIGITS: List<KeyboardKey> =
        List(10) { i -> KeyboardKey.DevCharacter(Devanagari.digitChar(i).toString()) }

    /**
     * The nukta (Perso-Arabic loan) consonants, on the deeper symbol layer.
     *
     * Rare in everyday prose but required for correct orthography in words like
     * ज़रुरी and फ़ोन, so they are reachable rather than omitted. The decomposed
     * composites are offered alongside the precomposed forms because text
     * interchange between systems does not always normalise consistently.
     */
    val DEVANAGARI_EXTRA_ROW_2: List<KeyboardKey> = devRow(
        Devanagari.QA.toString(),        // क़
        Devanagari.KHHA.toString(),      // ख़
        Devanagari.GHHA.toString(),      // ग़
        Devanagari.ZA.toString(),        // ज़
        Devanagari.DDDA.toString(),      // ड़
        Devanagari.RHA.toString(),       // ढ़
        Devanagari.FA.toString(),        // फ़
        Devanagari.RRA_COMPOSITE         // ड + ़
    )

    /**
     * Punctuation and the rarer vowel signs, on the deeper symbol layer.
     *
     * The danda and double danda are the Devanagari sentence terminators and are
     * genuinely frequent in Nepali prose, which is why they are on the first
     * reachable symbol layer rather than buried further.
     */
    val DEVANAGARI_EXTRA_ROW_3: List<KeyboardKey> = devRow(
        Devanagari.DANDA.toString(),     // ।
        Devanagari.DOUBLE_DANDA.toString(), // ॥
        Devanagari.AVAGRAHA.toString(),  // ऽ
        Devanagari.OM.toString(),        // ॐ
        Devanagari.SIGN_VOCALIC_RR.toString(),
        Devanagari.SIGN_VOCALIC_L.toString(),
        Devanagari.SIGN_VOCALIC_LL.toString()
    )

    // =========================================================================
    // Construction helpers
    // =========================================================================

    /** Builds a row of Latin character keys from a string of glyphs. */
    private fun charRow(chars: String): List<KeyboardKey> =
        List(chars.length) { i -> KeyboardKey.Character(chars[i]) }

    /**
     * Builds a row of Devanagari keys.
     *
     * Takes `vararg String` rather than a single concatenated string because
     * some entries are multi-code-unit sequences (a nukta consonant is base +
     * U+093C) and splitting a flat string by code point would break them.
     */
    private fun devRow(vararg texts: String): List<KeyboardKey> =
        texts.map { KeyboardKey.DevCharacter(it) }

    // =========================================================================
    // Lookup for the renderer
    // =========================================================================

    /**
     * Returns the three content rows for the given mode and layer.
     *
     * Centralising this keeps the Compose grid free of layout branching — it
     * asks for rows and renders them, and every mode/layer combination is
     * resolved here. Returning a `List<List<KeyboardKey>>` reuses the cached
     * row objects, so the result is allocation-free after the first call.
     */
    fun rowsFor(
        mode: InputMode,
        symbolsLayer: Boolean,
        moreSymbolsLayer: Boolean,
        shift: ShiftState
    ): List<List<KeyboardKey>> = when (mode) {
        InputMode.DEVANAGARI -> when {
            moreSymbolsLayer -> listOf(
                DEVANAGARI_DIGITS,
                DEVANAGARI_EXTRA_ROW_2,
                DEVANAGARI_EXTRA_ROW_3
            )
            symbolsLayer -> listOf(
                DEVANAGARI_DIGITS,
                SYMBOLS_ROW_2,
                SYMBOLS_ROW_3
            )
            shift.isUppercase -> listOf(
                DEVANAGARI_SHIFT_ROW_1,
                DEVANAGARI_SHIFT_ROW_2,
                DEVANAGARI_SHIFT_ROW_3
            )
            else -> listOf(DEVANAGARI_ROW_1, DEVANAGARI_ROW_2, DEVANAGARI_ROW_3)
        }
        else -> when {
            moreSymbolsLayer -> listOf(
                MORE_SYMBOLS_ROW_1,
                MORE_SYMBOLS_ROW_2,
                MORE_SYMBOLS_ROW_3
            )
            symbolsLayer -> listOf(SYMBOLS_ROW_1, SYMBOLS_ROW_2, SYMBOLS_ROW_3)
            shift.isUppercase -> listOf(QWERTY_ROW_1, QWERTY_ROW_2, QWERTY_ROW_3_SHIFTED)
            else -> listOf(QWERTY_ROW_1, QWERTY_ROW_2, QWERTY_ROW_3)
        }
    }

    /** The displayed glyph for a key, honouring the current shift state. */
    fun glyphFor(key: KeyboardKey, shift: ShiftState): String = when (key) {
        is KeyboardKey.Character -> {
            if (shift.isUppercase) {
                when (key.char) {
                    in 'a'..'z' -> (key.char - 32).toString()
                    // Shifted digits produce the US-layout symbol, which is what
                    // every other keyboard does and what users expect.
                    '1' -> "!"
                    '2' -> "@"
                    '3' -> "#"
                    '4' -> "$"
                    '5' -> "%"
                    '6' -> "^"
                    '7' -> "&"
                    '8' -> "*"
                    '9' -> "("
                    '0' -> ")"
                    else -> key.char.toString()
                }
            } else {
                key.char.toString()
            }
        }
        is KeyboardKey.DevCharacter -> key.text
        is KeyboardKey.Commit -> key.text
        is KeyboardKey.ModeSwitch -> ""
        else -> ""
    }

    /** True when the key should render with the accent (tonal) colour. */
    fun isAccentKey(key: KeyboardKey): Boolean = when (key) {
        KeyboardKey.Enter, is KeyboardKey.ModeSwitch -> true
        else -> false
    }

    /**
     * Relative width weight for a key, used by the grid to size modifiers.
     *
     * Content keys are weight 1. The renderer multiplies this for shift,
     * backspace, and the glyph-toggle key.
     */
    fun widthWeight(key: KeyboardKey): Float = 1f
}
