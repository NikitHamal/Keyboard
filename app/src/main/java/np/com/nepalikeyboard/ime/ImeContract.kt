package np.com.nepalikeyboard.ime

import androidx.compose.runtime.Immutable
import np.com.nepalikeyboard.data.ClipboardEntry
import np.com.nepalikeyboard.data.OneHandedSide
import np.com.nepalikeyboard.data.SettingsSnapshot
import np.com.nepalikeyboard.engine.Candidate
import np.com.nepalikeyboard.keyboard.FeedbackConfig
import np.com.nepalikeyboard.keyboard.KeyDef
import np.com.nepalikeyboard.keyboard.KeyboardLayout
import np.com.nepalikeyboard.keyboard.LayoutId
import np.com.nepalikeyboard.util.EmojiCategory

/** Which script the keyboard is currently driving. */
enum class KeyboardMode {
    /** Romanized Nepali -> Devanagari through the phonetic engine. */
    ROMAN,

    /** Direct Devanagari key layout. */
    NATIVE,

    /** Plain ASCII QWERTY. */
    ENGLISH,
}

/** Shift / caps-lock state. [AUTO] is the transient sentence-start shift. */
enum class ShiftState {
    OFF,
    AUTO,
    ON,
    LOCKED;

    val isShifted: Boolean get() = this != OFF
}

/** Which auxiliary panel replaces the key grid. */
enum class ImePanel {
    NONE,
    EMOJI,
    CLIPBOARD,
    LAYOUT,
}

/**
 * Everything the keyboard UI renders from.
 *
 * This is the single source of truth for the IME surface. It is immutable, so
 * Compose can diff it cheaply, and every collection inside it is published as a
 * fresh, never-mutated list (documented invariant of this module). It is written
 * only on the main thread and read only on the main thread.
 *
 * There is intentionally no separate "word model" field: the live word is
 * exactly the triple ([romanBuffer], [composingPreview], [candidates]), with the
 * script [mode] and the shift state deciding which slot is committed when. See
 * AGENTS.md, "The word model is a triple".
 */
@Immutable
data class ImeUiState(
    /** False until the first settings emission arrives; the UI shows no strip until then. */
    val ready: Boolean = false,

    val mode: KeyboardMode = KeyboardMode.ROMAN,
    val layout: KeyboardLayout? = null,
    val layoutId: LayoutId = LayoutId.ROMAN,
    val shift: ShiftState = ShiftState.OFF,
    val panel: ImePanel = ImePanel.NONE,
    val showingSymbols: Boolean = false,
    val symbolsAlt: Boolean = false,
    val devanagariAlt: Boolean = false,

    /** Pre-digested `EditorInfo` of the focused editor. */
    val editor: EditorDescriptor = EditorDescriptor.Text,

    val candidates: List<Candidate> = emptyList(),
    val candidatesLoading: Boolean = false,

    /** Raw romanized keys held in the uncommitted buffer (romanized mode only). */
    val romanBuffer: String = "",
    /** Devanagari rendering of [romanBuffer]; this is what sits in the composing region. */
    val composingPreview: String = "",

    val suggestionsVisible: Boolean = true,
    val toolbarVisible: Boolean = true,
    val numberRow: Boolean = false,
    val devanagariNumerals: Boolean = false,
    val autoCorrect: Boolean = false,
    val longPressSymbols: Boolean = true,
    val cursorDragEnabled: Boolean = true,
    val swipeDeleteEnabled: Boolean = true,
    val cursorGlideSpeed: Float = 1f,
    val feedback: FeedbackConfig = FeedbackConfig.Off,

    val oneHanded: OneHandedSide = OneHandedSide.OFF,
    val oneHandedWidthFraction: Float = 0.78f,
    val keyboardHeightScale: Float = 1f,
    val keyRoundnessScale: Float = 1f,

    val clipboardEntries: List<ClipboardEntry> = emptyList(),
    val clipboardEnabled: Boolean = true,
    val emojiRecents: List<String> = emptyList(),
    val emojiQuery: String = "",
    val emojiCategory: EmojiCategory = EmojiCategory.SMILEYS,

    /** Full settings snapshot, forwarded so the keyboard window can theme itself. */
    val theme: SettingsSnapshot = SettingsSnapshot.Default,
) {
    /** Password / no-personalization fields: literal input only, no learning. */
    val isSecure: Boolean get() = editor.sensitive

    /** Driven by the user setting and by the editor's privacy flags. */
    val suggestionsEnabled: Boolean get() = suggestionsVisible && !editor.sensitive

    val actionLabelRes: Int get() = editor.actionLabelRes

    val customActionLabel: String? get() = editor.customActionLabel
}

/**
 * Hot-lane callbacks from the pointer state machine.
 *
 * Implemented by `KeyboardController`; every callback is invoked on the main
 * thread from inside pointer dispatch, so implementations must stay
 * allocation-light and must not suspend.
 */
interface ImeKeySink {
    /**
     * Finger down on [key].
     *
     * Keys that must feel instantaneous act here: character keys, comma/period
     * and Backspace (which also auto-repeats through repeated calls). Keys that
     * need the user to be able to slide away act on release instead.
     */
    fun onKeyPressed(key: KeyDef)

    /** Finger up on [key] (may differ from the pressed key when sliding). */
    fun onKeyReleased(key: KeyDef)

    /** Long-press fired for [key]; [option] is the chosen popup character, if any. */
    fun onKeyLongPressed(key: KeyDef, option: String?)

    /** Finger slid onto a different key while held (slide-to-select feedback). */
    fun onKeySlid(key: KeyDef)

    /** Horizontal glide on the space bar: [characters] caret steps. */
    fun onCursorDrag(characters: Int)

    /** Swipe-to-delete: [words] word clusters consumed. */
    fun onSwipeDelete(words: Int)

    /** Gesture finished (used to settle preview state). */
    fun onGestureFinished()
}

/**
 * Everything the keyboard surface can ask of the IME.
 *
 * [KeyboardController] implements this on the main thread. The settings app's
 * typing sandbox implements the same interface with a local, throwaway buffer so
 * the identical renderer runs with no `InputConnection` and stores nothing.
 */
interface KeyboardActionSink : ImeKeySink {

    fun onOpenSettings()

    fun onHideKeyboard()

    /** Opens or closes an auxiliary panel. Passing the open panel closes it. */
    fun onPanelRequested(panel: ImePanel)

    fun onEmojiQueryChanged(query: String)

    fun onEmojiCategoryChanged(category: EmojiCategory)

    fun onEmojiSelected(emoji: String)

    fun onCandidateSelected(candidate: Candidate)

    fun onClipboardEntrySelected(entry: ClipboardEntry)

    fun onClipboardEntryRemoved(entry: ClipboardEntry)

    fun onClipboardEntryPinned(entry: ClipboardEntry)

    fun onClipboardCleared()

    fun onClearEmojiRecents()

    fun onOneHandedChanged(side: OneHandedSide)

    fun onKeyboardHeightRequested(scale: Float)

    /** Cycles the script: Romanized -> Devanagari -> English. */
    fun onCycleModeRequested()

    /** Switches to a specific script from the layout sheet. */
    fun onModeSelected(mode: KeyboardMode)

    /** Opens the system input-method picker. */
    fun onSwitchInputMethod()
}
