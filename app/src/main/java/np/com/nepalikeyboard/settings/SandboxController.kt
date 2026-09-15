package np.com.nepalikeyboard.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import np.com.nepalikeyboard.data.ClipboardEntry
import np.com.nepalikeyboard.data.OneHandedSide
import np.com.nepalikeyboard.data.SettingsSnapshot
import np.com.nepalikeyboard.engine.Candidate
import np.com.nepalikeyboard.engine.CandidateEngine
import np.com.nepalikeyboard.engine.PhoneticEngine
import np.com.nepalikeyboard.engine.unicode.Devanagari
import np.com.nepalikeyboard.engine.unicode.Graphemes
import np.com.nepalikeyboard.ime.EditorDescriptor
import np.com.nepalikeyboard.ime.ImePanel
import np.com.nepalikeyboard.ime.ImeRuntime
import np.com.nepalikeyboard.ime.ImeUiState
import np.com.nepalikeyboard.ime.KeyboardActionSink
import np.com.nepalikeyboard.ime.KeyboardMode
import np.com.nepalikeyboard.ime.ShiftState
import np.com.nepalikeyboard.keyboard.FeedbackConfig
import np.com.nepalikeyboard.keyboard.KeyDef
import np.com.nepalikeyboard.keyboard.KeyKind
import np.com.nepalikeyboard.keyboard.KeyboardLayout
import np.com.nepalikeyboard.keyboard.LayoutCatalog
import np.com.nepalikeyboard.keyboard.LayoutId
import np.com.nepalikeyboard.keyboard.isLetterLayout
import np.com.nepalikeyboard.util.EmojiCategory

/** What the sandbox exposes to its screen. */
@Immutable
data class SandboxState(
    val text: String = "",
    val caret: Int = 0,
) {
    /** The cluster the caret sits after, used for the caret marker. */
    val caretAtEnd: Boolean get() = text.length == caret
}

/**
 * A real keyboard that types into nothing.
 *
 * The sandbox mounts the *same* [np.com.nepalikeyboard.keyboard.KeyboardHost] the
 * IME window uses and drives it with this sink, so what the user tests here is
 * literally the production key grid, gesture machine, transliteration engine and
 * candidate ranker. The difference is where the text goes:
 *
 *  * the target is an in-memory [StringBuilder] owned by this screen - closing
 *    the screen drops the text, and no `InputConnection`, `EditorInfo` or other
 *    app's field is ever involved;
 *  * nothing is learned. [CandidateEngine] is only ever *read* here, never
 *    written, so personal-dictionary entries and bigram counts are untouched;
 *  * no clipboard capture happens: the panel reads history but the sandbox does
 *    not call `capture`.
 *
 * That is what "stateless zero-retention sandbox" means concretely: the only
 * thing that survives a rotation is nothing at all.
 */
class SandboxController(
    context: Context,
    private val scope: CoroutineScope,
) : KeyboardActionSink, CandidateEngine.Callback {

    private val runtime = ImeRuntime.get(context)

    private val engine = CandidateEngine(
        scope = scope,
        learning = runtime.learning,
        lexiconProvider = { runtime.lexicon },
        callback = this,
    )

    private val _state = MutableStateFlow(ImeUiState(ready = true))
    val state: StateFlow<ImeUiState> = _state.asStateFlow()

    private val _sandbox = MutableStateFlow(SandboxState())
    val sandbox: StateFlow<SandboxState> = _sandbox.asStateFlow()

    private val buffer = StringBuilder(INITIAL_BUFFER)
    private var caret = 0
    private var caretInitialised = false

    private val roman = StringBuilder(INITIAL_BUFFER)
    private var preview = ""

    private var snapshot: SettingsSnapshot = runtime.settings.current
    private var mode = KeyboardMode.ROMAN
    private var shift = ShiftState.AUTO
    private var panel = ImePanel.NONE
    private var showingSymbols = false
    private var symbolsAlt = false
    private var devanagariAlt = false
    private var oneHanded = OneHandedSide.OFF
    private var candidates: List<Candidate> = emptyList()
    private var candidatesLoading = false
    private var generation = 0L
    private var clipboardEntries: List<ClipboardEntry> = emptyList()
    private var emojiRecents: List<String> = emptyList()
    private var emojiQuery = ""
    private var emojiCategory: EmojiCategory = EmojiCategory.SMILEYS
    private var lastCommit = ""

    init {
        runtime.ensureLexicon()
        mode = when (snapshot.defaultLayout) {
            np.com.nepalikeyboard.data.LayoutPreference.ROMAN -> KeyboardMode.ROMAN
            np.com.nepalikeyboard.data.LayoutPreference.NATIVE -> KeyboardMode.NATIVE
            np.com.nepalikeyboard.data.LayoutPreference.ENGLISH -> KeyboardMode.ENGLISH
        }
        scope.launch {
            runtime.settings.snapshotState.collect { settings ->
                snapshot = settings
                oneHanded = settings.oneHanded
                publish(layout = layoutForCurrentState())
            }
        }
        scope.launch {
            runtime.clipboard.entries.collect { entries ->
                clipboardEntries = entries
                publish()
            }
        }
        scope.launch {
            runtime.emoji.recents.collect { recents ->
                emojiRecents = recents
                publish()
            }
        }
        scope.launch {
            runtime.lexiconReady.collect { available ->
                if (available) refreshCandidates()
            }
        }
        publish(layout = layoutForCurrentState())
    }

    // -----------------------------------------------------------------------
    // Hot lane
    // -----------------------------------------------------------------------

    override fun onKeyPressed(key: KeyDef) {
        when (key.kind) {
            KeyKind.CHARACTER -> typeCharacter(key.output)
            KeyKind.COMMA, KeyKind.PERIOD -> typePunctuation(key.output)
            KeyKind.BACKSPACE -> handleBackspace()
            else -> Unit
        }
    }

    override fun onKeyReleased(key: KeyDef) {
        when (key.kind) {
            KeyKind.SPACE -> handleSpace()
            KeyKind.ENTER -> handleEnter()
            KeyKind.SHIFT -> handleShiftTap()
            KeyKind.SYMBOLS -> {
                showingSymbols = !showingSymbols
                symbolsAlt = false
                publish(layout = layoutForCurrentState())
            }

            KeyKind.LAYOUT_SWITCH -> cycleMode()
            KeyKind.EMOJI -> togglePanel(ImePanel.EMOJI)
            KeyKind.CLIPBOARD -> togglePanel(ImePanel.CLIPBOARD)
            KeyKind.SETTINGS -> togglePanel(ImePanel.LAYOUT)
            KeyKind.HIDE -> { /* the sandbox has nothing to hide */ }
            KeyKind.ONE_HANDED -> onOneHandedChanged(
                if (oneHanded == OneHandedSide.OFF) OneHandedSide.LEFT else OneHandedSide.OFF,
            )

            else -> Unit
        }
    }

    override fun onKeyLongPressed(key: KeyDef, option: String?) {
        if (option != null) {
            typeCharacter(option)
            return
        }
        if (key.kind == KeyKind.SHIFT) {
            if (mode == KeyboardMode.NATIVE) {
                devanagariAlt = true
            } else {
                shift = ShiftState.LOCKED
            }
            publish(layout = layoutForCurrentState())
        }
    }

    override fun onKeySlid(key: KeyDef) = Unit

    override fun onCursorDrag(characters: Int) {
        if (characters == 0) return
        val next = (caret + characters).coerceIn(0, buffer.length)
        if (next == caret) return
        caret = next
        publish()
    }

    override fun onSwipeDelete(words: Int) {
        if (words <= 0) return
        if (roman.isNotEmpty()) {
            roman.setLength(0)
            preview = ""
            candidates = emptyList()
            refreshCandidates()
            publish()
            return
        }
        var remaining = words
        while (remaining > 0 && caret > 0) {
            val before = buffer.substring(0, caret)
            val boundary = Graphemes.prevWordBoundary(before, before.length)
            val target = if (boundary >= caret) caret - 1 else boundary
            if (target < 0) break
            buffer.delete(target, caret)
            caret = target
            remaining--
        }
        refreshCandidates()
        publish()
    }

    override fun onGestureFinished() = Unit

    // -----------------------------------------------------------------------
    // Typing
    // -----------------------------------------------------------------------

    private fun typeCharacter(text: String) {
        if (text.isEmpty()) return
        if (mode != KeyboardMode.ROMAN || !isRomanKey(text)) {
            if (roman.isNotEmpty()) commitRoman(corrected = null)
            insert(text)
            afterInsert(text)
            return
        }
        roman.append(text)
        preview = PhoneticEngine.transliterate(roman, snapshot.devanagariNumerals)
        if (shift == ShiftState.AUTO || shift == ShiftState.ON) shift = ShiftState.OFF
        publish()
        refreshCandidates()
    }

    private fun typePunctuation(text: String) {
        if (roman.isNotEmpty()) commitRoman(corrected = null)
        insert(text)
        afterInsert(text)
    }

    private fun handleSpace() {
        if (roman.isNotEmpty()) {
            commitRoman(corrected = null)
            insert(" ")
            afterInsert(" ")
            return
        }
        insert(" ")
        afterInsert(" ")
    }

    private fun handleEnter() {
        if (roman.isNotEmpty()) commitRoman(corrected = null)
        insert("\n")
        afterInsert("\n")
    }

    private fun handleBackspace() {
        if (roman.isNotEmpty()) {
            val last = roman.length - 1
            if (last > 0 &&
                Character.isLowSurrogate(roman[last]) &&
                Character.isHighSurrogate(roman[last - 1])
            ) {
                roman.setLength(last - 1)
            } else {
                roman.setLength(last)
            }
            preview = if (roman.isEmpty()) "" else PhoneticEngine.transliterate(roman, snapshot.devanagariNumerals)
            publish()
            refreshCandidates()
            return
        }
        if (caret == 0 || buffer.isEmpty()) return
        val before = buffer.substring(0, caret)
        val boundary = Graphemes.prevBoundary(before, before.length)
        val target = if (boundary >= caret) caret - 1 else boundary
        if (target < 0) return
        buffer.delete(target, caret)
        caret = target
        publish()
    }

    private fun commitRoman(corrected: String?) {
        if (roman.isEmpty()) return
        val payload = corrected ?: preview
        insert(payload)
        roman.setLength(0)
        preview = ""
        candidates = emptyList()
        lastCommit = payload
        publish()
    }

    private fun insert(text: String) {
        if (text.isEmpty()) return
        val position = caret.coerceIn(0, buffer.length)
        buffer.insert(position, text)
        caret = position + text.length
    }

    private fun afterInsert(text: String) {
        val boundary = text.length == 1 && (
            text[0] == '.' || text[0] == '!' || text[0] == '?' || text[0] == Devanagari.DANDA || text[0] == '\n'
            )
        if (boundary) {
            shift = if (snapshot.autoCapitalize) ShiftState.AUTO else ShiftState.OFF
            publish(layout = layoutForCurrentState())
        } else {
            publish()
        }
        refreshCandidates()
    }

    private fun cycleMode() {
        setMode(
            when (mode) {
                KeyboardMode.ROMAN -> KeyboardMode.NATIVE
                KeyboardMode.NATIVE -> KeyboardMode.ENGLISH
                KeyboardMode.ENGLISH -> KeyboardMode.ROMAN
            },
        )
    }

    private fun setMode(next: KeyboardMode) {
        if (next == mode) {
            panel = ImePanel.NONE
            publish()
            return
        }
        if (roman.isNotEmpty()) commitRoman(corrected = null)
        mode = next
        showingSymbols = false
        symbolsAlt = false
        devanagariAlt = false
        // The sandbox always restarts a script switch from a clean lowercase
        // state; the IME's auto-capitalisation is driven by the real editor.
        shift = ShiftState.OFF
        candidates = emptyList()
        panel = ImePanel.NONE
        publish(layout = layoutForCurrentState())
        refreshCandidates()
    }

    private fun handleShiftTap() {
        if (mode == KeyboardMode.NATIVE) {
            devanagariAlt = !devanagariAlt
        } else {
            shift = when (shift) {
                ShiftState.OFF -> ShiftState.ON
                ShiftState.AUTO -> ShiftState.ON
                ShiftState.ON -> ShiftState.OFF
                ShiftState.LOCKED -> ShiftState.OFF
            }
        }
        publish(layout = layoutForCurrentState())
    }

    private fun togglePanel(target: ImePanel) {
        panel = if (panel == target) ImePanel.NONE else target
        if (panel == ImePanel.EMOJI) emojiQuery = ""
        publish()
    }

    // -----------------------------------------------------------------------
    // Panels, candidates, layout
    // -----------------------------------------------------------------------

    override fun onOpenSettings() {
        togglePanel(ImePanel.LAYOUT)
    }

    override fun onHideKeyboard() = Unit

    override fun onPanelRequested(panel: ImePanel) {
        togglePanel(panel)
    }

    override fun onEmojiQueryChanged(query: String) {
        emojiQuery = query
        publish()
    }

    override fun onEmojiCategoryChanged(category: EmojiCategory) {
        emojiCategory = category
        emojiQuery = ""
        publish()
    }

    override fun onEmojiSelected(emoji: String) {
        if (roman.isNotEmpty()) commitRoman(corrected = null)
        insert(emoji)
        // Deliberately not recorded: the sandbox must not write to emoji recents
        // or anywhere else the user's real typing history lives.
        publish()
    }

    override fun onClearEmojiRecents() {
        // Emoji recency is picker state, not typed text: clearing it here is the
        // same action the IME window offers.
        runtime.emoji.clear()
    }

    override fun onCandidateSelected(candidate: Candidate) {
        if (roman.isNotEmpty()) commitRoman(corrected = candidate.text) else insert(candidate.text)
        refreshCandidates()
    }

    override fun onClipboardEntrySelected(entry: ClipboardEntry) {
        if (roman.isNotEmpty()) commitRoman(corrected = null)
        insert(entry.text)
        lastCommit = entry.text
        publish()
    }

    // Clipboard *management* is shared with the IME window (removing or pinning a
    // remembered snippet is not typed text). Only the sandbox's own output buffer
    // is ephemeral, and nothing is ever captured from it.
    override fun onClipboardEntryRemoved(entry: ClipboardEntry) {
        runtime.clipboard.remove(entry)
    }

    override fun onClipboardEntryPinned(entry: ClipboardEntry) {
        runtime.clipboard.togglePin(entry)
    }

    override fun onClipboardCleared() {
        runtime.clipboard.clearAll()
    }

    override fun onOneHandedChanged(side: OneHandedSide) {
        oneHanded = side
        publish()
    }

    override fun onKeyboardHeightRequested(scale: Float) {
        // Height is a real setting, so the slider here behaves exactly like the
        // one in the IME window; nothing about the typed text is persisted.
        scope.launch { runtime.settings.setKeyboardHeightScale(scale) }
    }

    override fun onCycleModeRequested() {
        cycleMode()
    }

    override fun onModeSelected(mode: KeyboardMode) {
        setMode(mode)
    }

    override fun onSwitchInputMethod() = Unit

    override fun onCandidatesReady(generation: Long, candidates: List<Candidate>, predictionOnly: Boolean) {
        if (generation != this.generation) return
        this.candidates = candidates
        this.candidatesLoading = false
        publish()
    }

    private fun refreshCandidates() {
        if (!snapshot.showSuggestions) {
            generation++
            candidates = emptyList()
            publish()
            return
        }
        generation++
        val request = generation
        if (roman.isNotEmpty()) {
            candidatesLoading = true
            engine.submitRoman(
                generation = request,
                buffer = roman.toString(),
                previousWord = lastCommit.ifEmpty { null },
                devanagariDigits = snapshot.devanagariNumerals,
            )
            publish()
            return
        }
        val previous = trailingWordBefore()
        if (previous == null) {
            candidates = emptyList()
            candidatesLoading = false
            publish()
            return
        }
        candidatesLoading = true
        engine.submitPrediction(generation = request, previousWord = previous, devanagariPrefix = null)
        publish()
    }

    private fun trailingWordBefore(): String? {
        if (caret == 0) return null
        val before = buffer.substring(0, caret)
        var end = before.length
        while (end > 0 && !Devanagari.isWordChar(before[end - 1])) end--
        if (end == 0) return null
        val start = Graphemes.prevWordBoundary(before, end)
        if (start >= end) return null
        return before.substring(start, end)
    }

    private fun layoutId(): LayoutId {
        if (showingSymbols) return if (symbolsAlt) LayoutId.SYMBOLS_ALT else LayoutId.SYMBOLS
        return when (mode) {
            KeyboardMode.ROMAN -> LayoutId.ROMAN
            KeyboardMode.NATIVE -> if (devanagariAlt) LayoutId.DEVANAGARI_ALT else LayoutId.DEVANAGARI
            KeyboardMode.ENGLISH -> LayoutId.ENGLISH
        }
    }

    private fun layoutForCurrentState(): KeyboardLayout {
        val id = layoutId()
        return LayoutCatalog.resolve(
            id = id,
            shifted = shift.isShifted && id.isLetterLayout,
            numberRow = snapshot.numberRow && id.isLetterLayout,
            devanagariNumerals = snapshot.devanagariNumerals,
        )
    }

    private fun isRomanKey(text: String): Boolean {
        if (text.length != 1) return false
        val c = text[0]
        return c in 'a'..'z' || c in 'A'..'Z'
    }

    private fun publish(layout: KeyboardLayout? = null) {
        val current = _state.value
        val resolvedLayout = layout ?: current.layout ?: layoutForCurrentState()
        val next = ImeUiState(
            ready = true,
            mode = mode,
            layout = resolvedLayout,
            layoutId = resolvedLayout.id,
            shift = shift,
            panel = panel,
            showingSymbols = showingSymbols,
            symbolsAlt = symbolsAlt,
            devanagariAlt = devanagariAlt,
            editor = EditorDescriptor.Text,
            candidates = candidates,
            candidatesLoading = candidatesLoading,
            romanBuffer = roman.toString(),
            composingPreview = preview,
            suggestionsVisible = snapshot.showSuggestions,
            toolbarVisible = snapshot.showToolbar,
            numberRow = snapshot.numberRow,
            devanagariNumerals = snapshot.devanagariNumerals,
            autoCorrect = false,
            longPressSymbols = snapshot.longPressSymbols,
            swipeDeleteEnabled = snapshot.swipeDeleteEnabled,
            cursorDragEnabled = snapshot.cursorDragEnabled,
            cursorGlideSpeed = snapshot.cursorGlideSpeed,
            feedback = FeedbackConfig(
                hapticsEnabled = snapshot.hapticsEnabled,
                soundEnabled = snapshot.soundEnabled,
                strength = snapshot.hapticStrength,
            ),
            oneHanded = oneHanded,
            oneHandedWidthFraction = snapshot.oneHandedWidthFraction,
            keyboardHeightScale = snapshot.keyboardHeightScale,
            keyRoundnessScale = snapshot.keyRoundnessScale,
            clipboardEntries = clipboardEntries,
            clipboardEnabled = snapshot.clipboardEnabled,
            emojiRecents = emojiRecents,
            emojiQuery = emojiQuery,
            emojiCategory = emojiCategory,
            theme = snapshot,
        )
        if (next != current) _state.value = next

        if (!caretInitialised) {
            caretInitialised = true
            caret = buffer.length
        }
        val sandboxState = SandboxState(text = buffer.toString(), caret = caret)
        if (sandboxState != _sandbox.value) _sandbox.value = sandboxState
    }

    /** Drops the ephemeral buffer. Called when the sandbox leaves composition. */
    fun clear() {
        buffer.setLength(0)
        caret = 0
        roman.setLength(0)
        preview = ""
        candidates = emptyList()
        lastCommit = ""
        publish()
    }

    private companion object {
        const val INITIAL_BUFFER = 128
    }
}
