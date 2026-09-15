package com.nikit.nepalikeyboard.ime

import androidx.compose.runtime.Immutable

/**
 * The three ways this keyboard produces Nepali, plus the two panels that
 * replace the key grid entirely.
 *
 * Modelled as a single flat enum rather than a sealed hierarchy because the
 * Compose UI switches on it in a `when` in exactly one place, and because
 * `rememberSaveable` can persist an enum by ordinal with no custom saver —
 * a sealed class would need one, and the saved-state registry inside an IME is
 * already the fragile part of this codebase.
 */
enum class InputMode {
    /**
     * Latin QWERTY. Produces the literal characters on the keys; the
     * transliteration engine is not consulted at all. Words typed here are
     * still offered to the lexicon as context for bigram lookup, but no
     * Devanagari is generated.
     */
    ENGLISH,

    /**
     * Native Devanagari layout. Vowels, consonants, and matras are laid out on
     * the keys directly, with the full set of combining signs reachable from
     * the shift layer. No transliteration happens: what the user taps is what
     * lands in the field.
     */
    DEVANAGARI,

    /**
     * Romanized phonetic input. The user types "namaste" and the field shows
     * नमस्ते inside a composing region, which is then replaced as they continue
     * typing. This is the mode the transliteration engine exists for.
     */
    ROMANIZED,

    /** Emoji picker panel. Replaces the key grid. */
    EMOJI,

    /** Clipboard history panel. Replaces the key grid. */
    CLIPBOARD;

    /** True for the three modes that show a key grid. */
    val isKeyMode: Boolean
        get() = this == ENGLISH || this == DEVANAGARI || this == ROMANIZED

    /** True for modes that replace the grid with a scrolling panel. */
    val isPanelMode: Boolean
        get() = this == EMOJI || this == CLIPBOARD

    /**
     * The mode to fall back to when the user leaves a panel with the back
     * gesture or by tapping a key. Preserves whichever key mode was active
     * before the panel opened, which is what makes the emoji button feel like a
     * toggle rather than a mode switch.
     */
    fun fallbackKeyMode(previous: InputMode): InputMode =
        if (previous.isKeyMode) previous else ROMANIZED

    companion object {
        /** Modes the user can cycle through with the mode-switch key. */
        val CYCLE: List<InputMode> = listOf(ROMANIZED, DEVANAGARI, ENGLISH)

        /** Parses a persisted ordinal, clamping to a safe default. */
        fun fromOrdinalOrRomanized(value: Int): InputMode {
            val values = entries
            return if (value in values.indices) values[value] else ROMANIZED
        }
    }
}

/**
 * Which shift behaviour is active.
 *
 * A three-state enum rather than two booleans, because "shift is on" and "caps
 * lock is on" are mutually exclusive in the UI (the key glyph differs), yet
 * both mean "the next letter is uppercase".
 */
enum class ShiftState {
    /** Lowercase; untouched. */
    OFF,

    /** Next letter uppercase, then automatically back to [OFF]. */
    SHIFTED,

    /** Every letter uppercase until the key is tapped again. */
    LOCKED;

    val isUppercase: Boolean
        get() = this != OFF

    companion object {
        /** Parses a persisted ordinal, clamping to a safe default. */
        fun fromOrdinalOrOff(value: Int): ShiftState {
            val values = entries
            return if (value in values.indices) values[value] else OFF
        }
    }
}

/**
 * Which edge the keyboard is docked to in one-handed mode.
 */
enum class OneHandedSide {
    /** Full width; one-handed mode is off. */
    NONE,

    /** Keyboard shrunk toward the left edge, for a left thumb. */
    LEFT,

    /** Keyboard shrunk toward the right edge, for a right thumb. */
    RIGHT;

    /** The opposite side, used by the quick side-switch gesture. */
    fun opposite(): OneHandedSide = when (this) {
        NONE -> LEFT
        LEFT -> RIGHT
        RIGHT -> LEFT
    }

    companion object {
        /** Parses a persisted ordinal, clamping to a safe default. */
        fun fromOrdinalOrNone(value: Int): OneHandedSide {
            val values = entries
            return if (value in values.indices) values[value] else NONE
        }
    }
}

/**
 * The complete, immutable state the keyboard UI renders from.
 *
 * Marked [Immutable] so that Compose can safely skip recomposition when the
 * instance is unchanged — this is not a micro-optimisation. The keyboard
 * recomposes on every keystroke and every cursor blink; without the contract
 * Compose treats each new instance as potentially different and re-runs every
 * consuming composable.
 *
 * Every field is a primitive or an enum. No `List`, no `Map`, no lambdas: those
 * would break the immutability guarantee at the runtime level and force
 * unstable-composable treatment throughout the tree. Collections live in the
 * ViewModel and are exposed through separate, individually-observable state
 * holders where the UI actually needs to iterate them (suggestions, clipboard
 * history, emoji recents).
 */
@Immutable
data class KeyboardUiState(
    /** Which key grid or panel is showing. */
    val mode: InputMode = InputMode.ROMANIZED,

    /** The key mode to return to when a panel closes. */
    val lastKeyMode: InputMode = InputMode.ROMANIZED,

    /** Shift / caps-lock state. */
    val shift: ShiftState = ShiftState.OFF,

    /**
     * True when the number/symbol row is showing instead of the letters.
     * Independent of [shift] and of [mode], because numbers are orthogonal to
     * script on every layout.
     */
    val symbolsLayer: Boolean = false,

    /**
     * True when the deeper symbol layer is showing (`=\<` on the QWERTY
     * layout). Only meaningful while [symbolsLayer] is true.
     */
    val moreSymbolsLayer: Boolean = false,

    /** Which edge the keyboard is docked to. */
    val oneHanded: OneHandedSide = OneHandedSide.NONE,

    /** True while the one-handed expand/collapse animation is running. */
    val oneHandedAnimating: Boolean = false,

    /**
     * The Devanagari rendering of the active Romanized buffer, or the literal
     * buffer in English mode. Empty when nothing is composing.
     *
     * Kept in the UI state (rather than read from the controller on demand) so
     * that the composer preview above the keys recomposes from a single
     * snapshot and cannot disagree with the suggestion strip.
     */
    val composingPreview: String = "",

    /** The raw Romanized buffer, for the literal-entry row in the strip. */
    val composingInput: String = "",

    /** True when the active field is a password field. */
    val passwordField: Boolean = false,

    /** True when the active field expects numbers. */
    val numericField: Boolean = false,

    /** True when the field is multi-line (Enter inserts a newline). */
    val multiline: Boolean = false,

    /** The editor action requested, e.g. `IME_ACTION_SEARCH`. */
    val imeAction: Int = 0,

    /** True when the keyboard should capitalise the next letter. */
    val autoCapitalise: Boolean = false,

    /** True when there is at least one editable character before the cursor. */
    val canDelete: Boolean = false,

    /** True once the lexicon has finished loading and can rank candidates. */
    val lexiconReady: Boolean = false,

    /**
     * Number of suggestions currently available. Lets the strip reserve or
     * release its vertical space without observing the suggestion list itself.
     */
    val suggestionCount: Int = 0,

    /** True when haptic feedback is enabled in settings. */
    val hapticsEnabled: Boolean = true,

    /** True when keypress sound is enabled in settings. */
    val soundEnabled: Boolean = false,

    /** True when the user has asked for key borders. */
    val showKeyBorders: Boolean = false,

    /**
     * True when the suggestion strip is enabled in settings.
     *
     * Mirrored into the UI state rather than read from the settings repository
     * at the point of render, because the strip is composed on every frame the
     * keyboard is visible and must not touch DataStore to decide whether to
     * draw itself. The value is pushed here whenever preferences change.
     */
    val showSuggestions: Boolean = true,

    /**
     * True when the keyboard should wrap toward one edge by preference, so the
     * one-handed toggle can show its engaged state before the user first taps
     * it.
     */
    val oneHandedPreferred: OneHandedSide = OneHandedSide.NONE,

    /**
     * The user's configured keyboard height, in density-independent pixels.
     *
     * Carried in the UI state so the container can size itself in the same
     * frame that everything else is laid out in. Reading it directly from the
     * settings snapshot inside the composable would make the height update one
     * recomposition later than the rest of the chrome, which reads as a flicker
     * when the height slider is dragged in the sandbox preview.
     */
    val keyboardHeightDp: Int = KeyboardPreferencesDefaults.KEYBOARD_HEIGHT_DP,

    /**
     * True while the keyboard is in a transient non-interactive state — during
     * a one-handed animation, or while the service is rebuilding after a
     * configuration change. Keys ignore input while this is set so a tap cannot
     * land on a layout that is about to change.
     */
    val inputBlocked: Boolean = false
)

/**
 * The defaults the UI state needs that also live in the preferences model.
 *
 * ### Why this exists at all
 *
 * `KeyboardUiState` lives in the `ime` package and `KeyboardPreferences` lives
 * in `settings`. Importing the latter into the former would invert the
 * dependency: the keyboard's own state object would then depend on the settings
 * layer, and the settings layer already depends on `ime` for [InputMode],
 * [OneHandedSide], and `ThemeMode`'s neighbours. That is a cycle.
 *
 * Duplicating four literals is the cheap way out. To make sure the duplication
 * cannot drift, this object is the *only* place the `ime` package states these
 * numbers, and `KeyboardPreferences` defaults are asserted against it — so a
 * change in either place that is not mirrored will fail that assertion.
 */
object KeyboardPreferencesDefaults {
    /**
     * Default height of the key area in dp, matching
     * `KeyboardPreferences.DEFAULT_KEYBOARD_HEIGHT_DP`.
     */
    const val KEYBOARD_HEIGHT_DP = 268
}
