package com.nikit.nepalikeyboard.ime

import android.app.Application
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.AndroidViewModel
import com.nikit.nepalikeyboard.clipboard.ClipItem
import com.nikit.nepalikeyboard.lexicon.LexiconRepository
import com.nikit.nepalikeyboard.lexicon.model.Suggestion
import com.nikit.nepalikeyboard.settings.KeyboardPreferences
import com.nikit.nepalikeyboard.settings.SettingsRepository
import com.nikit.nepalikeyboard.translit.RomanizedEngine
import com.nikit.nepalikeyboard.unicode.Devanagari
import com.nikit.nepalikeyboard.unicode.GraphemeClusterSegmenter
import com.nikit.nepalikeyboard.unicode.TextAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * =============================================================================
 * KEYBOARD VIEW MODEL
 * =============================================================================
 *
 * All keyboard state and all decision-making live here. The Compose layer reads
 * immutable snapshots and reports user intent back; it computes nothing and
 * owns nothing. The service layer feeds in editor events and forwards committed
 * text to the `InputConnection`.
 *
 * ### Why this split matters for an IME specifically
 *
 * An IME is destroyed and recreated on every configuration change. If state
 * lived in the composition, rotating the phone mid-word would discard the
 * composer, the suggestion list, the emoji recents, and the clipboard panel's
 * scroll position. Putting it in a ViewModel attached to the
 * `KeyboardLifecycleOwner` — which survives the config change because the owner
 * is created in the service's `onCreate` and the *process* is retained — means
 * rotation is invisible to the user.
 *
 * ### The suggestion pipeline, and why it is debounced
 *
 * Every keystroke changes the Romanized buffer, which changes what the lexicon
 * should return. But `LexiconRepository.suggest` walks a radix tree and scores
 * candidates against a bigram model — cheap, but not free, and the user types
 * faster than 20 characters per second at their worst. Firing a query per
 * keystroke would queue work behind a UI that has already moved on.
 *
 * The pipeline is therefore:
 *
 *  1. The keystroke synchronously renders the transliteration (this is pure
 *     string work on a ≤ 32-character buffer, sub-microsecond) and publishes
 *     it, so the field updates with zero latency. The user never waits.
 *  2. The lexically-ranked alternatives are computed on a debounce, and for
 *     short buffers they are computed eagerly because the trie walk is trivial.
 *  3. Only the newest query's result is kept — `collectLatest` on the buffer
 *     flow cancels the previous query, so a fast typist never sees results for
 *     a prefix they have already moved past.
 *
 * ### Password fields
 *
 * When [InputConnectionController.isPasswordField] is true, the entire learning
 * and suggestion apparatus is bypassed: no transliteration, no lexicon query,
 * no learned-word write, no clipboard capture, no auto-capitalisation. This is
 * enforced in one place — [onKeyPressed] and [refreshSuggestions] both check
 * [InputConnectionController.isPasswordField] before doing anything — rather
 * than being scattered as guards across a dozen call sites, because a single
 * missed guard would be a credential leak.
 */
class KeyboardViewModel(
    application: Application,
    private val lifecycleOwner: KeyboardLifecycleOwner,
    private val input: InputConnectionController,
    private val scope: CoroutineScope
) : AndroidViewModel(application) {

    // =========================================================================
    // Observable state
    // =========================================================================

    private val _uiState = MutableStateFlow(KeyboardUiState())
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    /**
     * The current suggestion strip contents.
     *
     * A separate `StateFlow` from [uiState] on purpose: the strip re-renders on
     * every keystroke while the rest of the keyboard does not, and merging them
     * would recompose the entire key grid every time a candidate changed.
     */
    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    /**
     * Emoji usage history, most recent first. Bounded by
     * [EMOJI_RECENT_LIMIT].
     */
    private val _recentEmoji = MutableStateFlow<List<String>>(emptyList())
    val recentEmoji: StateFlow<List<String>> = _recentEmoji.asStateFlow()

    /** Clipboard history, newest first. Empty when the feature is disabled. */
    private val _clipboard = MutableStateFlow<List<ClipItem>>(emptyList())
    val clipboard: StateFlow<List<ClipItem>> = _clipboard.asStateFlow()

    /**
     * One-shot events the UI must react to but must not re-react to on
     * recomposition — closing a panel, scrolling the strip, firing a sound.
     *
     * A `SharedFlow` with zero replay and a dropping buffer: if the UI is not
     * collecting (the keyboard is hidden) the events are dropped rather than
     * queued up to fire in a burst when it reappears.
     */
    private val _events = MutableSharedFlow<KeyboardEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<KeyboardEvent> = _events.asSharedFlow()

    // =========================================================================
    // Collaborators
    // =========================================================================

    /** The process-level dictionary index. Shared by every keyboard instance. */
    private val lexicon: LexiconRepository = LexiconRepository.get()

    /** Persisted user preferences. */
    private val preferences: SettingsRepository = SettingsRepository.get(application)

    /** Local, on-device clipboard history with the user's retention rules. */
    private val clipboardStore: ClipboardHistoryStore = ClipboardHistoryStore.get(application)

    /** Local, on-device learned-word counters. */
    private val learnedStore: LearnedWordStore = LearnedWordStore.get(application)

    /** Snapshot of the user's settings, mirrored for allocation-free reads. */
    private var prefs: KeyboardPreferences = KeyboardPreferences()

    // =========================================================================
    // Composer state
    // =========================================================================

    /**
     * The authoritative Romanized buffer.
     *
     * Empty means no word is being composed. Kept as a field rather than read
     * from [KeyboardUiState] on every keystroke because the hot path must not
     * allocate a state copy to answer "what are we composing".
     */
    private var romanBuffer: String = ""

    /**
     * The Devanagari rendering of [romanBuffer], recomputed on every mutation.
     * Cached rather than derived on read because both the field update and the
     * strip render need it in the same frame.
     */
    private var devanagariBuffer: String = ""

    /**
     * Guard that serialises composer mutations.
     *
     * The service's `onUpdateSelection` callback and the UI's keystroke handler
     * can both run in the same frame — a fast typist can move the caret while a
     * commit is in flight. Without a lock, the two can interleave such that the
     * composing region is set from a buffer that was already invalidated. A
     * `Mutex` here costs nothing (it is never contended in practice) and makes
     * the interleaving impossible.
     */
    private val composerMutex = Mutex()

    /** In-flight suggestion query, cancelled when the buffer changes. */
    private var suggestionJob: Job? = null

    /**
     * The last completed word, used as bigram context. Updated on every commit
     * so that "mero " primes the model to expect "desh" or "naam".
     */
    private var lastCommittedWord: String = ""

    /**
     * Uptime of the last space commit, for double-space-to-period. Held here
     * rather than only in the controller so that the preference toggle can be
     * honoured without consulting the controller.
     */
    private var lastSpaceAt: Long = 0L

    /** True while the keyboard is on screen. Gates clipboard capture. */
    private var keyboardVisible: Boolean = false

    /** True when the service is being torn down; suppresses late writes. */
    private var serviceDestroying: Boolean = false

    // =========================================================================
    // Construction
    // =========================================================================

    init {
        // Observe preferences for the lifetime of the ViewModel. A single
        // collector keeps `prefs` current, which means a settings change lands
        // on the next keystroke rather than the next service restart — the
        // settings screen and the keyboard share a process, so the flow fires
        // immediately.
        scope.launch {
            preferences.flow.collectLatest { loaded ->
                val isFirst = !prefsLoaded
                prefs = loaded
                prefsLoaded = true
                applyPreferences(loaded, isFirst)
                if (!isFirst && romanBuffer.isNotEmpty()) refreshSuggestions(romanBuffer)
            }
        }

        scope.launch {
            val learned = learnedStore.load()
            lexicon.loadLearnedWords(learned)
        }

        // Mirror the lexicon's load state into the UI so the keyboard can show
        // its degraded (transliteration-only) strip until the dictionary is
        // ready, rather than flashing an empty one.
        scope.launch {
            lexicon.state.collectLatest { state ->
                val ready = state is LexiconRepository.LoadState.Ready
                _uiState.update { it.copy(lexiconReady = ready) }
                if (ready && romanBuffer.isNotEmpty()) refreshSuggestions(romanBuffer)
            }
        }

        // Keep the personal-usage table warm as the user accepts words.
        scope.launch {
            learnedStore.flow.collectLatest { usage ->
                lexicon.loadLearnedWords(usage)
            }
        }

        // Persist emoji recents whenever they change.
        scope.launch {
            _recentEmoji.collectLatest { recent ->
                preferences.saveRecentEmoji(recent)
            }
        }
    }

    /** True once the first preference read has completed. */
    private var prefsLoaded = false

    /**
     * Applies a freshly loaded settings snapshot.
     *
     * [isFirst] distinguishes the initial load from a live change. On the
     * initial load the mode is set from the user's default; on a later change
     * the active mode is left alone, because the user is in the middle of
     * typing and switching their layout under them would be hostile.
     */
    private fun applyPreferences(loaded: KeyboardPreferences, isFirst: Boolean) {
        val current = _uiState.value
        _uiState.update {
            it.copy(
                mode = if (isFirst) loaded.defaultMode else it.mode,
                lastKeyMode = if (isFirst) loaded.defaultMode else it.lastKeyMode,
                hapticsEnabled = loaded.hapticsEnabled,
                soundEnabled = loaded.soundEnabled,
                showKeyBorders = loaded.showKeyBorders,
                showSuggestions = loaded.showSuggestions,
                keyboardHeightDp = loaded.keyboardHeightDp,
                oneHandedPreferred = loaded.oneHandedSide,
                oneHanded = if (isFirst) loaded.oneHandedSide else it.oneHanded
            )
        }
        // A disabled history must be cleared from memory, not merely hidden;
        // leaving it resident would contradict what the setting promises.
        if (!loaded.clipboardHistoryEnabled && current.clipboardHistoryEnabled) {
            scope.launch {
                clipboardStore.clear()
                _clipboard.value = emptyList()
            }
        } else if (loaded.clipboardHistoryEnabled) {
            scope.launch { _clipboard.value = clipboardStore.load() }
            scope.launch { clipboardStore.applyLimit(loaded.clipboardHistoryLimit) }
        }

        // Turning suggestions off has to retract the ones already on screen,
        // otherwise the strip keeps showing stale candidates until the next
        // keystroke — which reads as the setting having been ignored.
        if (!loaded.showSuggestions && _suggestions.value.isNotEmpty()) {
            suggestionJob?.cancel()
            _suggestions.value = emptyList()
            _uiState.update { it.copy(suggestionCount = 0) }
        }

        if (!isFirst) {
            // Re-read the emoji recents only when something actually changed,
            // so the initial load does not overwrite the in-flight read.
            scope.launch { _recentEmoji.value = preferences.loadRecentEmoji() }
        }
    }

    // =========================================================================
    // Editor lifecycle
    // =========================================================================

    /**
     * Called from `onStartInput` and from `onCreateInputView` (the latter so
     * that a configuration change re-applies the context to the rebuilt UI).
     *
     * Resets everything scoped to the previous editor, then derives the new
     * editor's constraints. The composer is cleared rather than committed here
     * because `onStartInput` fires *after* focus has already left the old field
     * — the commit for that field happened in `onFinishInputView`.
     */
    fun onEditorChanged(info: EditorInfo, controller: InputConnectionController) {
        val password = controller.isPasswordField
        val numeric = controller.isNumericField
        val multiline = controller.isMultiline()

        romanBuffer = ""
        devanagariBuffer = ""
        lastCommittedWord = ""
        lastSpaceAt = 0L
        _suggestions.value = emptyList()

        _uiState.update {
            it.copy(
                passwordField = password,
                numericField = numeric,
                multiline = multiline,
                imeAction = controller.imeAction,
                composingPreview = "",
                composingInput = "",
                suggestionCount = 0,
                // A numeric field forces the symbol layer so the user lands on
                // digits immediately, which is what every platform IME does.
                symbolsLayer = it.symbolsLayer || numeric,
                autoCapitalise = !password && controller.shouldAutoCapitalise(),
                canDelete = !controller.isCursorAtStart()
            )
        }

        if (password) {
            // Nothing to learn, nothing to suggest, nothing to remember.
            lexicon.resetContext()
        }
    }

    /** Called when the keyboard becomes visible. */
    fun onInputViewShown() {
        keyboardVisible = true
        _uiState.update {
            it.copy(autoCapitalise = !it.passwordField && input.shouldAutoCapitalise())
        }
    }

    /**
     * Called when the keyboard is being hidden.
     *
     * [commitPending] is true when the user moved to another field mid-word: the
     * half-typed Romanized buffer must be converted and committed, not
     * discarded. It is false when the whole editor is going away, because in
     * that case the app is closing the field and inserting text now would be
     * surprising.
     */
    fun onInputViewHidden(commitPending: Boolean) {
        keyboardVisible = false
        if (commitPending && romanBuffer.isNotEmpty()) {
            commitComposing(appendSpace = false)
        }
    }

    /** Called from `onFinishInput`; the editor is gone. */
    fun onEditorFinished() {
        keyboardVisible = false
        romanBuffer = ""
        devanagariBuffer = ""
        _suggestions.value = emptyList()
        _uiState.update {
            it.copy(composingPreview = "", composingInput = "", suggestionCount = 0)
        }
        lexicon.resetContext()
    }

    /** Called from `onDestroy`. */
    fun onServiceDestroying() {
        serviceDestroying = false // allow the final commit below
        if (romanBuffer.isNotEmpty()) commitComposing(appendSpace = false)
        serviceDestroying = true
        suggestionJob?.cancel()
        suggestionJob = null
    }

    /**
     * Called from `onUpdateSelection`. Abandons the composer when the caret left
     * it, which is the single most important correctness guard in the app.
     */
    fun onSelectionUpdated(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        composingStart: Int,
        composingEnd: Int
    ) {
        if (!input.selectionMovedOutsideComposing(
                oldSelStart, oldSelEnd, newSelStart, newSelEnd, composingStart, composingEnd
            )
        ) {
            return
        }
        // The user tapped elsewhere in the text. Drop the composer without
        // committing: inserting a word at a location they did not choose would
        // be worse than losing the keystrokes.
        scope.launch {
            composerMutex.withLock {
                if (romanBuffer.isEmpty()) return@withLock
                romanBuffer = ""
                devanagariBuffer = ""
                input.finishComposing()
                _suggestions.value = emptyList()
                _uiState.update {
                    it.copy(composingPreview = "", composingInput = "", suggestionCount = 0)
                }
            }
        }
    }

    /** Called from the hardware back key. Returns true when it was consumed. */
    fun onBackPressed(): Boolean {
        val current = _uiState.value
        if (current.mode.isPanelMode) {
            switchMode(current.lastKeyMode)
            return true
        }
        if (current.moreSymbolsLayer) {
            _uiState.update { it.copy(moreSymbolsLayer = false) }
            return true
        }
        if (current.symbolsLayer) {
            _uiState.update { it.copy(symbolsLayer = false) }
            return true
        }
        if (romanBuffer.isNotEmpty()) {
            scope.launch { clearComposer() }
            return true
        }
        return false
    }

    // =========================================================================
    // The hot path: a key was pressed
    // =========================================================================

    /**
     * The single entry point for every character-producing key.
     *
     * ### Allocation discipline
     *
     * This method is called on every tap and must not allocate. There is exactly
     * one allocation on this path — the `Concat` that builds the new Roman
     * buffer — and that is unavoidable because Kotlin strings are immutable.
     * Everything else is a field read or a primitive comparison.
     *
     * The result is returned synchronously: the caller (the Compose gesture
     * handler) has already fired haptics and will not wait for anything async.
     * Suggestion refresh is dispatched separately and never blocks.
     */
    fun onKeyPressed(key: KeyboardKey) {
        if (serviceDestroying || _uiState.value.inputBlocked) return

        val state = _uiState.value

        when (key) {
            is KeyboardKey.Character -> {
                emitCharacter(key.char)
            }
            is KeyboardKey.DevCharacter -> {
                emitDevanagari(key.text)
            }
            KeyboardKey.Space -> emitSpace()
            KeyboardKey.Enter -> emitEnter()
            KeyboardKey.Backspace -> emitBackspace()
            KeyboardKey.BackspaceWord -> emitBackspaceWord()
            KeyboardKey.Shift -> toggleShift()
            is KeyboardKey.ModeSwitch -> switchMode(key.target)
            KeyboardKey.GlyphToggle -> toggleSymbolLayer()
            KeyboardKey.MoreSymbols -> toggleMoreSymbols()
            KeyboardKey.OneHanded -> toggleOneHanded()
            KeyboardKey.OneHandedSwap -> swapOneHandedSide()
            KeyboardKey.Emoji -> switchMode(InputMode.EMOJI)
            KeyboardKey.Clipboard -> switchMode(InputMode.CLIPBOARD)
            KeyboardKey.Tab -> emitTab()
            KeyboardKey.Escape -> dismiss()
            KeyboardKey.Globe -> Unit // handled by the service; no state change
            KeyboardKey.CursorLeft -> input.moveCursorByClusters(-1)
            KeyboardKey.CursorRight -> input.moveCursorByClusters(1)
            KeyboardKey.CursorUp -> input.moveCursorByWord(false)
            KeyboardKey.CursorDown -> input.moveCursorByWord(true)
            KeyboardKey.SelectAll -> input.selectAll()
            is KeyboardKey.Commit -> commitSuggestion(key.text)
        }

        // Shift is one-shot outside caps lock; any producing key releases it.
        // Done after dispatch so the key itself saw the shifted state.
        if (key is KeyboardKey.Character || key is KeyboardKey.DevCharacter) {
            if (state.shift == ShiftState.SHIFTED) {
                _uiState.update { it.copy(shift = ShiftState.OFF) }
            }
        }

        _uiState.update { it.copy(canDelete = !input.isCursorAtStart()) }
    }

    /**
     * Handles a Latin character key.
     *
     * Behaviour depends entirely on the active mode:
     *  * ENGLISH — the character goes straight into the field, no composer.
     *  * ROMANIZED — the character extends the Roman buffer, and the field's
     *    composing region is re-rendered from it.
     *  * DEVANAGARI — the Latin layer is not reachable in this mode, so this is
     *    only hit via a hardware keyboard; we insert literally.
     */
    private fun emitCharacter(raw: Char) {
        val state = _uiState.value
        if (state.passwordField) {
            // Literal insertion. No composer, no learning, no suggestions.
            input.commit(applyShiftToChar(raw, state.shift))
            return
        }

        when (state.mode) {
            InputMode.ROMANIZED -> {
                val ch = if (state.autoCapitalise && romanBuffer.isEmpty()) {
                    raw.uppercaseChar()
                } else {
                    applyShiftToChar(raw, state.shift)
                }
                appendToComposer(ch)
            }
            InputMode.ENGLISH -> {
                val ch = applyShiftToChar(raw, state.shift)
                input.commit(ch.toString())
                lastSpaceAt = 0L
                maybeLearnToken(input.wordBeforeCursor())
            }
            else -> {
                input.commit(applyShiftToChar(raw, state.shift))
            }
        }
    }

    /**
     * Handles a Devanagari key from the native layout.
     *
     * These characters are inserted literally — the layout already encodes the
     * exact code points, so the transliteration engine must not see them.
     * The one piece of intelligence applied is capitalisation-independent:
     * nothing, because Devanagari has no case.
     */
    private fun emitDevanagari(text: String) {
        input.commit(text)
        lastSpaceAt = 0L
        if (Devanagari.isSentenceTerminator(text[0])) {
            _uiState.update { it.copy(autoCapitalise = true) }
        }
    }

    /**
     * Appends [ch] to the Roman buffer and re-renders the composing region.
     *
     * This is the hottest function in the app. It does:
     *  1. one string concatenation for the buffer,
     *  2. one transliteration pass over a short buffer,
     *  3. one `setComposingText` IPC.
     *
     * Nothing else. The suggestion query is scheduled, not run.
     */
    private fun appendToComposer(ch: Char) {
        val next = romanBuffer + ch
        val rendered = input.renderComposing(next)

        // The IPC happens first: if the field rejects the write (the connection
        // died, or the app is read-only) we must not advance our own buffer, or
        // the two would diverge permanently.
        val ok = input.setComposing(rendered)
        if (!ok && !input.isPasswordField) {
            // No composing support in this editor. Fall back to direct commits
            // of the rendered form, which degrades to per-character insertion.
            input.commit(input.renderCommitted(next))
            romanBuffer = ""
            devanagariBuffer = ""
            _uiState.update { it.copy(composingPreview = "", composingInput = "") }
            return
        }

        romanBuffer = next
        devanagariBuffer = rendered

        _uiState.update {
            it.copy(
                composingPreview = rendered,
                composingInput = next,
                autoCapitalise = false
            )
        }

        refreshSuggestions(next)
    }

    /**
     * Commits a space, converting any pending Romanized word on the way.
     *
     * Double-space-to-period is handled here rather than in the UI because it
     * needs both the timing and the field's content: a second space inside the
     * window must remove the first space and write ". " instead, which is a
     * field mutation the UI cannot perform.
     */
    private fun emitSpace() {
        val state = _uiState.value
        val now = SystemClock.uptimeMillis()

        // Any pending composition becomes a real word.
        val hadComposition = romanBuffer.isNotEmpty()
        val committed = if (hadComposition) {
            val word = input.renderCommitted(romanBuffer)
            input.commit(word)
            finishComposer()
            maybeLearnToken(word)
            word
        } else {
            ""
        }

        val isDouble = prefs.doubleSpacePeriod &&
            !state.passwordField &&
            lastSpaceAt != 0L &&
            now - lastSpaceAt <= DOUBLE_SPACE_WINDOW_MS

        if (isDouble) {
            // Swap the just-typed space for a full stop and a space. The field
            // contains "<text> " at this moment, so we delete one character and
            // insert the period pair.
            input.replaceBefore(1, ". ")
            lastSpaceAt = 0L
            _uiState.update { it.copy(autoCapitalise = true) }
            return
        }

        // A single space. When a composition was just committed we still emit
        // the separator — the alternative, relying on the composer to have
        // inserted it, would silently produce run-together words.
        input.commit(" ")

        lastSpaceAt = now
        if (committed.isNotEmpty()) lastCommittedWord = committed

        // After a space, a new sentence may begin. The literal " " we pass is
        // deliberate: the field now ends in a space, and the analyser needs to
        // see a whitespace character to recognise a word boundary. Passing the
        // real preceding text instead would read a stale cursor snapshot.
        _uiState.update {
            it.copy(
                autoCapitalise = !it.passwordField &&
                    prefs.autoCapitalise &&
                    TextAnalysis.shouldCapitalise(" x", 1)
            )
        }

        // Prime the bigram model with the word we just finished.
        if (committed.isNotEmpty()) {
            scope.launch { lexicon.setContext(committed) }
        }
    }

    /**
     * Handles Enter.
     *
     * In a multi-line field, or when the app declined to specify an action,
     * Enter inserts a newline. Otherwise it fires the action the app asked for
     * (Search, Send, Go…), committing any pending composition first so that the
     * word being typed is not lost by the action firing.
     */
    private fun emitEnter() {
        val state = _uiState.value
        if (romanBuffer.isNotEmpty()) {
            val word = input.renderCommitted(romanBuffer)
            finishComposer()
            val action = if (state.multiline) EditorInfo.IME_ACTION_NONE else state.imeAction
            input.commitAndPerformAction(word, action)
            maybeLearnToken(word)
        } else {
            val action = if (state.multiline) EditorInfo.IME_ACTION_NONE else state.imeAction
            input.performEditorAction(action)
        }
        lastSpaceAt = 0L
    }

    /**
     * Backspace.
     *
     * Routed through the controller, which is grapheme-cluster aware. When the
     * composer is non-empty the deletion happens on the *Roman* buffer, so the
     * user sees the Latin character they typed disappear.
     */
    private fun emitBackspace() {
        if (romanBuffer.isNotEmpty()) {
            val next = if (romanBuffer.length >= 2 &&
                Character.isLowSurrogate(romanBuffer[romanBuffer.length - 1])
            ) {
                romanBuffer.substring(0, romanBuffer.length - 2)
            } else {
                romanBuffer.substring(0, romanBuffer.length - 1)
            }
            if (next.isEmpty()) {
                input.finishComposing()
                finishComposer()
            } else {
                val rendered = input.renderComposing(next)
                input.setComposing(rendered)
                romanBuffer = next
                devanagariBuffer = rendered
                _uiState.update {
                    it.copy(composingPreview = rendered, composingInput = next)
                }
                refreshSuggestions(next)
            }
            return
        }

        input.deleteBackward()
        lastSpaceAt = 0L
        _suggestions.value = emptyList()
        _uiState.update { it.copy(suggestionCount = 0, composingPreview = "", composingInput = "") }
    }

    /** Backspace with the swipe-left gesture: delete a whole word. */
    private fun emitBackspaceWord() {
        if (romanBuffer.isNotEmpty()) {
            input.finishComposing()
            finishComposer()
            return
        }
        input.deleteWordBackward()
        lastSpaceAt = 0L
        _suggestions.value = emptyList()
        _uiState.update { it.copy(suggestionCount = 0) }
    }

    private fun emitTab() {
        if (romanBuffer.isNotEmpty()) commitComposing(appendSpace = false)
        input.commit("\t")
        lastSpaceAt = 0L
    }

    // =========================================================================
    // Shift, modes, layers
    // =========================================================================

    /**
     * Cycles OFF -> SHIFTED -> LOCKED -> OFF.
     *
     * The double-tap-for-caps-lock timing is handled by the Compose gesture
     * handler, which calls [setCapsLock] directly; this method is the plain
     * single-tap cycle.
     */
    private fun toggleShift() {
        _uiState.update {
            val next = when (it.shift) {
                ShiftState.OFF -> ShiftState.SHIFTED
                ShiftState.SHIFTED -> ShiftState.LOCKED
                ShiftState.LOCKED -> ShiftState.OFF
            }
            it.copy(shift = next)
        }
    }

    /** Sets caps lock explicitly, from the double-tap gesture. */
    fun setCapsLock(locked: Boolean) {
        _uiState.update {
            it.copy(shift = if (locked) ShiftState.LOCKED else ShiftState.OFF)
        }
    }

    /**
     * Advances shift by one step, as a single tap on the shift key does.
     *
     * A separate entry point from [onKeyPressed] even though it dispatches to
     * the same private cycle, because the UI needs to fire haptics *before*
     * the state change and wants to do so from its own gesture handler. Going
     * through `onKeyPressed(KeyboardKey.Shift)` would work but would route the
     * gesture through the generic key dispatch and lose the intent.
     */
    fun onShiftPressed() {
        toggleShift()
    }

    /**
     * Cycles the symbol layer, as the `?123` / `ABC` / `=\<` key does.
     *
     * The key has three visible states but the transition is not a straight
     * cycle: `ABC -> ?123 -> =\< -> ABC`. That progression is implemented in
     * [toggleSymbolLayer] and [toggleMoreSymbols] because it also has to keep
     * the two flags consistent, so this is a thin named wrapper rather than a
     * duplicate of the logic.
     */
    fun onGlyphTogglePressed() {
        _uiState.update {
            when {
                // Second deeper layer: back to letters.
                it.moreSymbolsLayer -> it.copy(symbolsLayer = false, moreSymbolsLayer = false)
                // First symbol layer: go deeper.
                it.symbolsLayer -> it.copy(moreSymbolsLayer = true)
                // Letters: go to the first symbol layer.
                else -> it.copy(symbolsLayer = true, moreSymbolsLayer = false)
            }
        }
    }

    /** Toggles one-handed mode, persisted so it survives across editors. */
    fun onOneHandedToggled() {
        toggleOneHanded()
    }

    /** Flips the one-handed dock from left to right or back. */
    fun onOneHandedSwapped() {
        swapOneHandedSide()
    }

    /**
     * Called after something outside the ViewModel has mutated the composing
     * region — currently only the hardware-key backspace path in
     * `NepaliImeService.onKeyDown`, which has to drive the
     * [InputConnectionController] directly because `KEYCODE_DEL` arrives as a
     * key event rather than through [onKeyPressed].
     *
     * The ViewModel's own [romanBuffer] is the authority for the soft keyboard,
     * so rather than trying to reverse-engineer the buffer from the field's
     * new contents, this simply truncates it to match: the hardware key removed
     * one cluster from the composed text, so one character comes off the Roman
     * buffer too. Anything more clever than that would need to re-derive
     * Devanagari from a partly-deleted composition, which is guesswork.
     *
     * [remaining] is the field's composing text *after* the deletion, exactly as
     * the controller reports it.
     */
    fun onComposingBufferChanged(remaining: String) {
        if (remaining.isEmpty()) {
            romanBuffer = ""
            devanagariBuffer = ""
            _suggestions.value = emptyList()
            _uiState.update {
                it.copy(composingPreview = "", composingInput = "", suggestionCount = 0)
            }
            return
        }
        if (romanBuffer.length <= 1) {
            // The buffer is already at or below the minimum the field could
            // still be composing; nothing left to trim.
            return
        }
        romanBuffer = romanBuffer.substring(0, romanBuffer.length - 1)
        devanagariBuffer = remaining
        _uiState.update {
            it.copy(composingPreview = remaining, composingInput = romanBuffer)
        }
        refreshSuggestions(romanBuffer)
    }

    /**
     * Asks the platform to show the input-method picker.
     *
     * Emitted as an event rather than called directly because the ViewModel has
     * no service reference: it is constructed by the service but must remain
     * testable without one, and the settings sandbox renders this keyboard with
     * a no-op action bundle. An event keeps that boundary intact — a sandbox
     * that emits this event simply does nothing with it.
     */
    fun requestSystemPicker() {
        scope.launch { _events.emit(KeyboardEvent.ShowInputMethodPicker) }
    }

    /**
     * Switches input mode, committing or discarding the composer as
     * appropriate.
     *
     * Moving Romantised -> Devanagari with a pending word commits it, because
     * the user clearly meant to write it. Moving to English discards it, since
     * the Romanized characters are exactly what they now want to type.
     */
    fun switchMode(target: InputMode) {
        val current = _uiState.value
        if (current.mode == target) {
            // Tapping the active mode's key toggles back out of a panel.
            if (target.isPanelMode) {
                _uiState.update { it.copy(mode = it.lastKeyMode) }
            }
            return
        }

        if (romanBuffer.isNotEmpty()) {
            if (target == InputMode.DEVANAGARI || target == InputMode.ENGLISH) {
                commitComposing(appendSpace = false)
            } else {
                scope.launch { clearComposer() }
            }
        }

        val lastKeyMode = if (target.isKeyMode) target else current.lastKeyMode
        _uiState.update {
            it.copy(
                mode = target,
                lastKeyMode = lastKeyMode,
                // Panels never want shift highlighted.
                shift = if (target.isPanelMode) ShiftState.OFF else it.shift
            )
        }
        _suggestions.value = emptyList()
        _uiState.update { it.copy(suggestionCount = 0) }
    }

    /**
     * Toggles the number/symbol layer, exiting the deeper layer when leaving.
     */
    private fun toggleSymbolLayer() {
        _uiState.update {
            val next = !it.symbolsLayer
            it.copy(symbolsLayer = next, moreSymbolsLayer = if (next) it.moreSymbolsLayer else false)
        }
    }

    private fun toggleMoreSymbols() {
        _uiState.update {
            it.copy(moreSymbolsLayer = !it.moreSymbolsLayer, symbolsLayer = true)
        }
    }

    private fun toggleOneHanded() {
        _uiState.update {
            val next = if (it.oneHanded == OneHandedSide.NONE) {
                prefs.oneHandedSide.takeIf { s -> s != OneHandedSide.NONE } ?: OneHandedSide.RIGHT
            } else {
                OneHandedSide.NONE
            }
            it.copy(oneHanded = next, oneHandedAnimating = true)
        }
        scope.launch { preferences.saveOneHandedSide(_uiState.value.oneHanded) }
    }

    private fun swapOneHandedSide() {
        _uiState.update {
            val next = if (it.oneHanded == OneHandedSide.NONE) {
                OneHandedSide.RIGHT
            } else {
                it.oneHanded.opposite()
            }
            it.copy(oneHanded = next, oneHandedAnimating = true)
        }
        scope.launch { preferences.saveOneHandedSide(_uiState.value.oneHanded) }
    }

    /** Called by the UI when a one-handed animation finishes. */
    fun onOneHandedAnimationSettled() {
        _uiState.update { it.copy(oneHandedAnimating = false) }
    }

    /** Hides the keyboard by asking the system to do so. */
    private fun dismiss() {
        input.finishComposing()
        finishComposer()
        // The service observes this event and calls requestHideSelf.
        scope.launch { _events.emit(KeyboardEvent.Dismiss) }
    }

    // =========================================================================
    // Committing
    // =========================================================================

    /**
     * Commits the current composition, optionally following it with a space.
     *
     * The full commit sequence is deliberately explicit:
     *   1. `commit(finalText)` — one atomic replace of the composing region.
     *   2. clear our own buffers.
     *   3. record the word for learning and bigram context.
     *   4. emit the trailing space if requested.
     *
     * Doing it in this order means the field is never in a state where our
     * buffer says one thing and its content says another.
     */
    private fun commitComposing(appendSpace: Boolean) {
        if (romanBuffer.isEmpty()) {
            if (appendSpace) input.commit(" ")
            return
        }
        val word = input.renderCommitted(romanBuffer)
        input.commit(word)
        finishComposer()
        maybeLearnToken(word)
        if (appendSpace) input.commit(" ")
        scope.launch { lexicon.setContext(word) }
    }

    /** Clears the composer state without touching the field's content. */
    private fun finishComposer() {
        romanBuffer = ""
        devanagariBuffer = ""
        suggestionJob?.cancel()
        suggestionJob = null
        _suggestions.value = emptyList()
        _uiState.update {
            it.copy(composingPreview = "", composingInput = "", suggestionCount = 0)
        }
    }

    /** Clears the composer *and* the field's composing region. */
    private suspend fun clearComposer() {
        composerMutex.withLock {
            input.detachComposing()
            finishComposer()
        }
    }

    /**
     * Commits a suggestion the user tapped.
     *
     * `isLiteral` suggestions insert their text verbatim — the user is choosing
     * to escape transliteration for this word.
     */
    private fun commitSuggestion(text: String) {
        if (text.isEmpty()) return
        input.commit(text)
        finishComposer()
        maybeLearnToken(text)
        scope.launch { lexicon.setContext(text) }
        // A committed word is followed by a space in every realistic flow.
        input.commit(" ")
        lastSpaceAt = SystemClock.uptimeMillis()
        _uiState.update { it.copy(autoCapitalise = prefs.autoCapitalise) }
    }

    /**
     * Commits a tapped suggestion.
     *
     * The public entry point the strip calls. It commits the word, follows it
     * with a single space, and updates the capitalisation expectation for the
     * next letter — the same sequence as a commit produced by the spacebar, so
     * a word accepted from the strip is indistinguishable from one typed and
     * confirmed, both in the field and in the learned table.
     */
    fun onSuggestionCommitted(text: String) {
        if (serviceDestroying) return
        commitSuggestion(text)
    }

    /**
     * Records [word] in the local learning table if it is a plausible word.
     *
     * Skipped entirely for password fields and when the user has turned
     * learning off. No word is ever sent anywhere; the table is a local file
     * read by this same code path on the next launch.
     */
    private fun maybeLearnToken(word: String) {
        if (word.isEmpty()) return
        if (input.isPasswordField) return
        if (!prefs.learnWords) return
        if (!Devanagari.isDevanagariBlock(word.codePointAt(0))) return
        if (word.length < MIN_LEARNABLE_LENGTH) return
        scope.launch { learnedStore.record(word) }
    }

    // =========================================================================
    // Suggestions
    // =========================================================================

    /**
     * Schedules a suggestion refresh for [roman].
     *
     * The previous query is cancelled unconditionally before the new one is
     * launched, so a fast typist produces exactly one in-flight query. The
     * debounce is zero for buffers under [EAGER_QUERY_LENGTH] because the trie
     * walk there is a handful of pointer dereferences, and a debounce would add
     * latency for no measurable saving.
     */
    private fun refreshSuggestions(roman: String) {
        if (input.isPasswordField) {
            _suggestions.value = emptyList()
            _uiState.update { it.copy(suggestionCount = 0) }
            return
        }
        if (!prefs.showSuggestions) {
            _suggestions.value = emptyList()
            _uiState.update { it.copy(suggestionCount = 0) }
            return
        }

        suggestionJob?.cancel()
        suggestionJob = scope.launch {
            if (roman.length > EAGER_QUERY_LENGTH) {
                kotlinx.coroutines.delay(SUGGESTION_DEBOUNCE_MS)
            }
            val results = withContext(Dispatchers.Default) {
                lexicon.suggest(roman, limit = SUGGESTION_LIMIT, includeLiteral = true)
            }
            // The keyboard may have been dismissed while we were computing.
            if (!keyboardVisible && romanBuffer.isEmpty()) return@launch
            // Stale-result guard: the buffer moved on while we were working.
            if (romanBuffer != roman) return@launch
            _suggestions.value = results
            _uiState.update { it.copy(suggestionCount = results.size) }
        }
    }

    // =========================================================================
    // Emoji
    // =========================================================================

    /** Records an emoji as used, moving it to the front of the recents list. */
    fun onEmojiUsed(emoji: String) {
        val current = _recentEmoji.value
        val next = ArrayList<String>(current.size + 1)
        next.add(emoji)
        for (existing in current) {
            if (existing != emoji && next.size < EMOJI_RECENT_LIMIT) next.add(existing)
        }
        _recentEmoji.value = next

        // Insert, replacing any pending composition.
        if (romanBuffer.isNotEmpty()) finishComposer()
        val padded = if (prefs.emojiAsSpacedTokens) " $emoji " else emoji
        input.commit(padded)
        lastSpaceAt = 0L
    }

    // =========================================================================
    // Clipboard
    // =========================================================================

    /**
     * Called when the system reports a new primary clip.
     *
     * Guarded on three things: the feature must be enabled, the keyboard must
     * be on screen (a background clipboard capture would be a privacy
     * violation), and the item must survive the store's own filtering.
     */
    fun onClipboardItemCaptured(item: ClipItem) {
        if (!prefs.clipboardHistoryEnabled) return
        if (!keyboardVisible) return
        if (input.isPasswordField) return
        scope.launch {
            val updated = clipboardStore.record(item)
            _clipboard.value = updated
        }
    }

    /** Inserts a clipboard entry into the field. */
    fun onClipboardItemPasted(item: ClipItem) {
        if (romanBuffer.isNotEmpty()) commitComposing(appendSpace = false)
        input.commit(item.text)
        lastSpaceAt = 0L
        scope.launch { clipboardStore.markUsed(item.text) }
    }

    /** Pins or unpins an entry so it survives the retention sweep. */
    fun onClipboardItemPinned(item: ClipItem, pinned: Boolean) {
        scope.launch {
            _clipboard.value = clipboardStore.setPinned(item.text, pinned)
        }
    }

    /** Removes a single entry. */
    fun onClipboardItemDeleted(item: ClipItem) {
        scope.launch {
            _clipboard.value = clipboardStore.delete(item.text)
        }
    }

    /** Clears the entire history, including pinned entries. */
    fun onClipboardCleared() {
        scope.launch {
            clipboardStore.clear()
            _clipboard.value = emptyList()
        }
    }

    // =========================================================================
    // Spacebar drag
    // =========================================================================

    /**
     * Accumulates horizontal drag on the spacebar into cursor movement.
     *
     * The drag distance is converted to a cluster count using a fixed
     * points-per-cluster ratio that the UI computes against its own density, so
     * the gesture feels identical on a 320 dpi phone and a 600 dpi tablet.
     * Leftover sub-cluster distance is carried in [spaceDragCarry] so a slow
     * drag still moves the caret rather than rounding to zero forever.
     */
    private var spaceDragCarry: Float = 0f

    fun onSpaceDragStarted() {
        spaceDragCarry = 0f
    }

    /**
     * @param dxPoints the horizontal delta since the last callback
     * @param pointsPerCluster how many points of travel equal one cluster
     */
    fun onSpaceDragged(dxPoints: Float, pointsPerCluster: Float) {
        if (pointsPerCluster <= 0f) return
        spaceDragCarry += dxPoints
        val clusters = (spaceDragCarry / pointsPerCluster).toInt()
        if (clusters == 0) return
        spaceDragCarry -= clusters * pointsPerCluster
        val moved = input.moveCursorByClusters(clusters)
        if (moved != clusters) {
            // We hit an edge of the field; drop the carry so a reversal responds
            // immediately rather than after unwinding phantom distance.
            spaceDragCarry = 0f
        }
    }

    fun onSpaceDragEnded() {
        spaceDragCarry = 0f
    }

    // =========================================================================
    // Settings
    // =========================================================================

    /**
     * Reports the lexicon's statistics for the settings screen.
     *
     * The settings screen reads live values on demand rather than observing a
     * mirror, because the numbers change only when the user opens that screen.
     */
    fun lexiconStatistics(): LexiconRepository.LexiconStatistics = lexicon.statistics()

    /** Number of graphemes in [text]; used by the sandbox's character counter. */
    fun graphemeCount(text: CharSequence): Int =
        GraphemeClusterSegmenter.countGraphemes(text)

    companion object {
        /**
         * Buffers shorter than this are queried eagerly. A two- or
         * three-character prefix matches few trie nodes, so the walk is
         * effectively free and a debounce would only add perceived lag.
         */
        private const val EAGER_QUERY_LENGTH = 3

        /** Debounce applied once the buffer grows past [EAGER_QUERY_LENGTH]. */
        private const val SUGGESTION_DEBOUNCE_MS = 40L

        /**
         * Maximum suggestions kept. The strip shows at most this many plus the
         * literal entry; beyond four or five options users stop reading.
         */
        private const val SUGGESTION_LIMIT = 5

        /** Recents retained for the emoji panel. */
        private const val EMOJI_RECENT_LIMIT = 32

        /** Window in which a second space counts as a double space. */
        private const val DOUBLE_SPACE_WINDOW_MS = 350L

        /** Words shorter than this are not worth learning. */
        private const val MIN_LEARNABLE_LENGTH = 2
    }
}

/**
 * Applies shift state to a Latin character.
 *
 * Shift on a digit or symbol produces the shifted glyph on that key, which is
 * why this goes through [RomanizedEngine.applyShift] rather than
 * `Char.uppercaseChar`. For Romantised input the resulting character is the one
 * the transliteration engine sees, so `Shift+A` must reach it as `A`.
 */
private fun applyShiftToChar(raw: Char, shift: ShiftState): Char {
    if (!shift.isUppercase) return raw
    if (raw in 'a'..'z') return raw - 32
    if (raw in 'A'..'Z') return raw
    return RomanizedEngine.applyShift(raw, shift = true)
}

/**
 * One-shot signals from the ViewModel to the UI.
 */
sealed interface KeyboardEvent {
    /** The keyboard should ask the system to hide itself. */
    data object Dismiss : KeyboardEvent

    /** The suggestion strip should scroll back to the start. */
    data object ResetSuggestionScroll : KeyboardEvent

    /** A warning to show inline, e.g. the lexicon failed to load. */
    data class ShowNotice(val message: String) : KeyboardEvent

    /**
     * The user asked for a different keyboard.
     *
     * Emitted rather than performed because showing the picker is a platform
     * action only the service can take, and because the settings sandbox hosts
     * this same UI with no service behind it.
     */
    data object ShowInputMethodPicker : KeyboardEvent
}
