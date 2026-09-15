package np.com.nepalikeyboard.ime

import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import np.com.nepalikeyboard.data.ClipboardEntry
import np.com.nepalikeyboard.data.LayoutPreference
import np.com.nepalikeyboard.data.OneHandedSide
import np.com.nepalikeyboard.data.SettingsSnapshot
import np.com.nepalikeyboard.engine.Candidate
import np.com.nepalikeyboard.engine.CandidateEngine
import np.com.nepalikeyboard.engine.CandidateKind
import np.com.nepalikeyboard.engine.unicode.Devanagari
import np.com.nepalikeyboard.keyboard.FeedbackConfig
import np.com.nepalikeyboard.keyboard.KeyDef
import np.com.nepalikeyboard.keyboard.KeyKind
import np.com.nepalikeyboard.keyboard.KeyboardLayout
import np.com.nepalikeyboard.keyboard.LayoutCatalog
import np.com.nepalikeyboard.keyboard.LayoutId
import np.com.nepalikeyboard.keyboard.isLetterLayout
import np.com.nepalikeyboard.util.EmojiCategory
import np.com.nepalikeyboard.util.KeyboardLog

/**
 * The keyboard's brain: one state holder, one reducer, one thread.
 *
 * ## Threading
 *
 * Every field below is **main-thread confined**. The instance is created by
 * `NepaliImeService.onCreateInputView` (or by the settings sandbox) and is only
 * ever touched from
 *
 *  * pointer dispatch, through [ImeKeySink] / [KeyboardActionSink];
 *  * the service's editor and selection callbacks;
 *  * coroutines launched on the `Dispatchers.Main.immediate` scope handed in,
 *    which includes the settings / clipboard / emoji / lexicon-availability
 *    flows collected in [init];
 *  * `CandidateEngine`, which delivers results on the main thread by contract.
 *
 * That single-thread confinement is what keeps the keystroke path free of
 * locks, atomics and volatile reads: a key press is a plain field write plus,
 * only when something visible changed, one immutable snapshot for Compose.
 *
 * ## Two lanes, one reducer
 *
 * Latency-critical input does not allocate intent objects; the pointer machine
 * calls the [ImeKeySink] methods directly. Everything else - panels, settings,
 * clipboard, emoji, candidate results - arrives as a method call too, and all
 * of them funnel through the same private helpers into [publish]. There is no
 * second place where UI state can change.
 *
 * ## Where text goes
 *
 * This class never touches `InputConnection` itself: [CompositionController]
 * owns every read and write of the editor, so its invariants (whole-word
 * composition, grapheme-safe deletes, literal input in sensitive fields) hold on
 * every path that reaches the editor.
 */
internal class KeyboardController(
    private val host: ImeHost,
    private val runtime: ImeRuntime,
    private val scope: CoroutineScope,
) : KeyboardActionSink, CandidateEngine.Callback {

    private val composer = CompositionController(host)

    private val engine = CandidateEngine(
        scope = scope,
        learning = runtime.learning,
        lexiconProvider = { runtime.lexicon },
        callback = this,
    )

    private val _state = MutableStateFlow(ImeUiState())
    val state: StateFlow<ImeUiState> = _state.asStateFlow()

    // -----------------------------------------------------------------------
    // Main-thread state (the model behind ImeUiState)
    // -----------------------------------------------------------------------

    private var snapshot: SettingsSnapshot = SettingsSnapshot.Default
    private var ready = false
    private var defaultLayoutApplied = false

    private var mode: KeyboardMode = KeyboardMode.ROMAN
    private var shift: ShiftState = ShiftState.AUTO
    private var panel: ImePanel = ImePanel.NONE
    private var showingSymbols = false
    private var symbolsAlt = false
    private var devanagariAlt = false
    private var oneHanded: OneHandedSide = OneHandedSide.OFF

    private var candidates: List<Candidate> = emptyList()
    private var candidatesLoading = false
    private var generation = 0L
    private var lastCommittedWord: String? = null
    private var composingPreview: String = ""

    private var clipboardEntries: List<ClipboardEntry> = emptyList()
    private var clipboardObserving = false
    private var emojiRecents: List<String> = emptyList()
    private var emojiQuery = ""
    private var emojiCategory: EmojiCategory = EmojiCategory.SMILEYS

    private var learningFlushJob: Job? = null
    private var heightPersistJob: Job? = null

    /**
     * The base character the last press inserted, kept only so that a long-press
     * popup on the same key can replace it. Cleared by every other action, so it
     * can never be used against text the user did not just type.
     */
    private var lastPressKey: KeyDef? = null
    private var lastPressChar: String? = null

    init {
        runtime.ensureLexicon()
        scope.launch {
            runtime.settings.snapshotState.collect { settings -> applySettings(settings) }
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
        // The dictionary is optional: until it lands, transliteration, literal
        // input and personal words all work, and this collector simply refreshes
        // the strip once the lexical candidates become available.
        scope.launch {
            runtime.lexiconReady.collect { available ->
                if (available) refreshCandidates()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Settings
    // -----------------------------------------------------------------------

    /**
     * Applies a settings snapshot.
     *
     * The default-layout preference is only honoured for the first emission of a
     * session: after that the user's in-keyboard script choice owns [mode], so
     * toggling an unrelated setting in the settings app can never yank the script
     * out from under a sentence in progress.
     */
    private fun applySettings(settings: SettingsSnapshot) {
        val previous = snapshot
        snapshot = settings
        ready = true
        if (!defaultLayoutApplied) {
            defaultLayoutApplied = true
            mode = modeForPreference(settings.defaultLayout)
            shift = initialShift()
        }
        oneHanded = settings.oneHanded
        if (settings.keyboardHeightScale != previous.keyboardHeightScale) {
            // The view applies its own size immediately; this keeps the persisted
            // value and the on-screen height in step without waiting for DataStore.
            host.applyKeyboardHeightScale(settings.keyboardHeightScale)
        }
        syncClipboardObservation()
        publish(layout = layoutForCurrentState())
    }

    private fun modeForPreference(preference: LayoutPreference): KeyboardMode = when (preference) {
        LayoutPreference.ROMAN -> KeyboardMode.ROMAN
        LayoutPreference.NATIVE -> KeyboardMode.NATIVE
        LayoutPreference.ENGLISH -> KeyboardMode.ENGLISH
    }

    // -----------------------------------------------------------------------
    // Session lifecycle (called by the service)
    // -----------------------------------------------------------------------

    /** Input view became visible: a fresh session starts here. */
    fun onInputViewShown() {
        defaultLayoutApplied = false
        panel = ImePanel.NONE
        showingSymbols = false
        symbolsAlt = false
        devanagariAlt = false
        candidates = emptyList()
        lastCommittedWord = null
        emojiQuery = ""
        composingPreview = ""
        composer.reset(host.inputConnection)
        mode = modeForPreference(snapshot.defaultLayout)
        shift = initialShift()
        syncClipboardObservation()
        publish(layout = layoutForCurrentState())
        refreshCandidates()
    }

    /** Input view hidden: nothing may keep writing into the editor. */
    fun onInputViewHidden() {
        composer.reset(host.inputConnection)
        panel = ImePanel.NONE
        showingSymbols = false
        symbolsAlt = false
        composingPreview = ""
        candidates = emptyList()
        generation++
        publish()
        syncClipboardObservation()
        flushLearning()
    }

    /**
     * A new editor took focus, or the same editor restarted.
     *
     * A composition belongs to the editor that started it; it is finished and
     * discarded here, never migrated, so text can never land in the wrong field.
     */
    fun editorChanged() {
        composer.reset(host.inputConnection)
        candidates = emptyList()
        composingPreview = ""
        lastCommittedWord = null
        emojiQuery = ""
        showingSymbols = false
        symbolsAlt = false
        devanagariAlt = false
        shift = initialShift()
        publish(layout = layoutForCurrentState())
        refreshCandidates()
    }

    /** The editor went away with no successor. */
    fun editorFinished() {
        composer.reset(host.inputConnection)
        composer.clearBuffer()
        candidates = emptyList()
        composingPreview = ""
        generation++
        publish()
        flushLearning()
    }

    /**
     * Selection and composing-region report from `onUpdateSelection`.
     *
     * When the framework says our composing region is gone - the app rewrote the
     * text, the user tapped elsewhere - the roman buffer is stale and is
     * dropped. Without this a later backspace would delete the wrong characters,
     * which is the classic way an IME corrupts a word.
     */
    fun onSelectionChanged(
        selectionStart: Int,
        selectionEnd: Int,
        composingStart: Int,
        composingEnd: Int,
    ) {
        val dropped = composer.reconcileSelection(
            composingStart = composingStart,
            composingEnd = composingEnd,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
        )
        if (dropped) {
            candidates = emptyList()
            composingPreview = ""
            publish()
            return
        }
        // Caret moved by the user or by the app: refresh the transient capital
        // state, but never override an explicit shift or caps lock.
        if (selectionStart != selectionEnd) return
        if (shift == ShiftState.ON || shift == ShiftState.LOCKED) return
        val shouldAuto = shouldAutoShift()
        val wasAuto = shift == ShiftState.AUTO
        if (shouldAuto != wasAuto) {
            shift = if (shouldAuto) ShiftState.AUTO else ShiftState.OFF
            publish(layout = layoutForCurrentState())
        }
    }

    // -----------------------------------------------------------------------
    // Hot lane: keys
    // -----------------------------------------------------------------------

    override fun onKeyPressed(key: KeyDef) {
        when (key.kind) {
            KeyKind.CHARACTER -> {
                lastPressKey = key
                lastPressChar = key.output
                typeCharacter(key.output)
            }

            KeyKind.COMMA, KeyKind.PERIOD -> {
                lastPressKey = key
                lastPressChar = key.output
                typePunctuation(key.output)
            }

            KeyKind.BACKSPACE -> handleBackspace()
            else -> Unit
        }
    }

    override fun onKeyReleased(key: KeyDef) {
        when (key.kind) {
            KeyKind.SPACE -> handleSpace()
            KeyKind.ENTER -> handleEnter()
            KeyKind.SHIFT -> handleShiftTap()
            KeyKind.SYMBOLS -> toggleSymbols()
            KeyKind.LAYOUT_SWITCH -> cycleMode()
            KeyKind.EMOJI -> togglePanel(ImePanel.EMOJI)
            KeyKind.CLIPBOARD -> togglePanel(ImePanel.CLIPBOARD)
            KeyKind.SETTINGS -> host.openSettings()
            KeyKind.HIDE -> host.hideInputView()
            KeyKind.ONE_HANDED -> toggleOneHanded()
            else -> Unit
        }
    }

    /**
     * Long-press release.
     *
     * [option] is the popup character the finger settled on. Character keys
     * already inserted their base glyph on press, so the popup *replaces* that
     * glyph instead of appending to it: hold "a", pick "a with macron", and the
     * editor ends up with exactly one character. The replacement goes through
     * grapheme-safe deletion, so a base letter plus a matra is never split.
     *
     * A plain hold ([option] == null) on Shift locks capitals, and on the script
     * key flips the Devanagari extras page.
     */
    override fun onKeyLongPressed(key: KeyDef, option: String?) {
        if (option != null) {
            val ic = host.inputConnection
            if (ic != null && lastPressKey === key && lastPressChar != null) {
                composer.deleteCluster(ic)
            }
            lastPressKey = null
            lastPressChar = null
            typeCharacter(option)
            return
        }
        when (key.kind) {
            KeyKind.SHIFT -> if (mode == KeyboardMode.NATIVE) {
                devanagariAlt = !devanagariAlt
                publish(layout = layoutForCurrentState())
            } else {
                shift = ShiftState.LOCKED
                publish(layout = layoutForCurrentState())
            }

            else -> Unit
        }
    }

    override fun onKeySlid(key: KeyDef) {
        // Slide-to-select only changes which key fires on release; the pointer
        // machine in KeyboardSurface owns that, so the model does not move.
    }

    override fun onCursorDrag(characters: Int) {
        if (characters == 0) return
        composer.moveCursor(host.inputConnection ?: return, characters)
    }

    override fun onSwipeDelete(words: Int) {
        if (words <= 0) return
        val ic = host.inputConnection ?: return
        if (composer.hasRomanInput) {
            // The swipe started on the uncommitted word: consume that first, then
            // fall through to committed text for however many steps remain.
            composer.reset(ic)
            composingPreview = ""
            candidates = emptyList()
            publish()
        }
        if (composer.deleteWords(ic, words) > 0) {
            refreshCandidates()
        }
    }

    override fun onGestureFinished() {
        publish()
    }

    // -----------------------------------------------------------------------
    // Hardware / API lane
    // -----------------------------------------------------------------------
    //
    // Physical keyboards and API integrations drive the same helpers as the
    // on-screen keys, so a hardware Delete and a swiped Backspace can never
    // diverge in behaviour.

    /** `KEYCODE_DEL`: identical to pressing the on-screen Backspace. */
    fun hardwareBackspace() {
        handleBackspace()
    }

    /** `KEYCODE_FORWARD_DEL`: one grapheme cluster to the right of the caret. */
    fun hardwareForwardDelete() {
        val ic = host.inputConnection ?: return
        composer.deleteClusterForward(ic)
        refreshCandidates()
    }

    /** `KEYCODE_ENTER` / `KEYCODE_NUMPAD_ENTER`. */
    fun hardwareEnter() {
        handleEnter()
    }

    /** `KEYCODE_SPACE`. */
    fun hardwareSpace() {
        handleSpace()
    }

    // -----------------------------------------------------------------------
    // Text insertion
    // -----------------------------------------------------------------------

    private fun typeCharacter(text: String) {
        if (text.isEmpty()) return
        val editor = host.editorDescriptor
        val ic = host.inputConnection
        if (ic == null) {
            publish()
            return
        }

        if (!usesComposition(editor) || !isRomanKey(text)) {
            if (composer.hasRomanInput) {
                // A digit, symbol or matra ends the romanized word: commit what is
                // already on screen, then insert literally.
                commitRoman(ic, correctedWord = null)
            }
            composer.commitText(
                ic = ic,
                text = text,
                addSpaceAfter = false,
                devanagariDigits = snapshot.devanagariNumerals,
            )
            afterLiteralCommit(text)
            return
        }

        val preview = composer.typeRoman(ic, text, snapshot.devanagariNumerals)
        composingPreview = preview ?: composer.previewTransliteration(snapshot.devanagariNumerals)
        if (shift == ShiftState.AUTO || shift == ShiftState.ON) shift = ShiftState.OFF
        publish()
        refreshCandidates()
    }

    private fun typePunctuation(text: String) {
        val ic = host.inputConnection ?: return
        if (composer.hasRomanInput) commitRoman(ic, correctedWord = null)
        composer.commitText(ic, text, addSpaceAfter = false, devanagariDigits = false)
        afterLiteralCommit(text)
    }

    /** Space: accept the current word, then insert a space. */
    private fun handleSpace() {
        val ic = host.inputConnection ?: return
        if (composer.hasRomanInput) {
            commitRoman(ic, correctedWord = bestCommitForm(), addSpaceAfter = true)
            updateShiftAfterEdit()
            refreshCandidates()
            return
        }
        composer.commitText(ic, " ", addSpaceAfter = false, devanagariDigits = false)
        lastCommittedWord = null
        updateShiftAfterEdit()
        refreshCandidates()
    }

    /** Enter: the editor's own action when it asked for one, a newline otherwise. */
    private fun handleEnter() {
        val ic = host.inputConnection ?: return
        val editor = host.editorDescriptor
        if (composer.hasRomanInput) commitRoman(ic, correctedWord = bestCommitForm())
        candidates = emptyList()
        if (editor.enterInsertsNewline) {
            composer.commitText(ic, "\n", addSpaceAfter = false, devanagariDigits = false)
            lastCommittedWord = null
        } else {
            host.performEditorAction()
        }
        updateShiftAfterEdit()
        refreshCandidates()
    }

    private fun handleBackspace() {
        val ic = host.inputConnection ?: return
        // 1. Uncommitted romanized text: delete inside the buffer and re-render
        //    the whole word. The editor only ever sees a complete replacement, so
        //    a word can never end up half-deleted or half-transliterated.
        if (composer.hasRomanInput) {
            val preview = composer.backspaceRoman(ic, snapshot.devanagariNumerals)
            composingPreview = preview.orEmpty()
            publish()
            refreshCandidates()
            return
        }
        // 2. Committed text: one grapheme cluster per press (surrogate pairs and
        //    Devanagari matra/halant clusters survive intact).
        composingPreview = ""
        composer.deleteCluster(ic)
        refreshCandidates()
    }

    // -----------------------------------------------------------------------
    // Committing the romanized word
    // -----------------------------------------------------------------------

    /**
     * Ends the romanized composition.
     *
     * [correctedWord] replaces the deterministic transliteration when
     * [bestCommitForm] resolved a dictionary spelling or auto-correct picked a
     * better candidate; otherwise the text already visible in the composing
     * region is committed verbatim. Either way the committed word is the one the
     * suggestion strip was showing, so accepting "space" never inserts something
     * the user never saw. The result is learned, and the bigram
     * `previous -> committed` is fed to the statistical model.
     */
    private fun commitRoman(
        ic: InputConnection,
        correctedWord: String?,
        addSpaceAfter: Boolean = false,
    ): String {
        if (!composer.hasRomanInput) return ""
        val roman = composer.romanText()
        val transliteration = composer.previewTransliteration(snapshot.devanagariNumerals)
        val payload = correctedWord ?: transliteration
        composer.commitComposition(ic, payload)
        if (addSpaceAfter) {
            composer.commitText(ic, " ", addSpaceAfter = false, devanagariDigits = false)
        }
        val previous = lastCommittedWord
        learnWord(payload, roman)
        if (previous != null && previous != payload) runtime.learning.recordBigram(previous, payload)
        lastCommittedWord = payload
        composingPreview = ""
        return payload
    }

    /**
     * The word the space bar (and Enter) should commit, or null to commit exactly
     * what is on screen.
     *
     * Two tiers, both deliberately conservative:
     *
     * 1. **Exact dictionary key.** When the roman buffer is the exact key of a
     *    bundled word, the dictionary spelling is what the user meant: the
     *    deterministic layer cannot recover vowel length that the ASCII key threw
     *    away (`nepal` transliterates to नेपल, the word is नेपाल). This tier does
     *    not require the auto-correct setting, because it is a spelling rather
     *    than a guess - and it is only applied while suggestions are visible, so
     *    the word being committed is always the one the strip is showing.
     * 2. **Auto-correct.** Only for words the dictionary does not know, only with
     *    the setting on, and only when a lexical or personal candidate is on
     *    offer. Unknown proper nouns therefore survive untouched.
     *
     * Sensitive fields never reach either tier.
     */
    private fun bestCommitForm(): String? {
        val editor = host.editorDescriptor
        if (editor.sensitive || !editor.autoCorrectAllowed) return null
        val transliteration = composer.previewTransliteration(snapshot.devanagariNumerals)
        if (transliteration.isEmpty()) return null
        val lexicon = runtime.lexicon ?: return null

        if (snapshot.showSuggestions && !composer.isDigitOnlyBuffer()) {
            val exact = lexicon.entryForRoman(composer.romanText().lowercase())
            if (exact != null && exact.word != transliteration) return exact.word
        }
        if (!snapshot.autoCorrect) return null
        if (lexicon.isKnownWord(transliteration)) return null
        for (index in candidates.indices) {
            val candidate = candidates[index]
            if (candidate.kind != CandidateKind.LEXICAL && candidate.kind != CandidateKind.PERSONAL) continue
            return if (candidate.text == transliteration) null else candidate.text
        }
        return null
    }

    override fun onCandidateSelected(candidate: Candidate) {
        val ic = host.inputConnection ?: return
        if (composer.hasRomanInput) {
            val roman = composer.romanText()
            composer.commitComposition(ic, candidate.text)
            val previous = lastCommittedWord
            learnWord(candidate.text, roman)
            if (previous != null && previous != candidate.text) {
                runtime.learning.recordBigram(previous, candidate.text)
            }
            lastCommittedWord = candidate.text
            composer.clearBuffer()
        } else {
            composer.commitText(ic, candidate.text, addSpaceAfter = false, devanagariDigits = false)
            learnWord(candidate.text, roman = "")
        }
        composingPreview = ""
        candidates = emptyList()
        updateShiftAfterEdit()
        refreshCandidates()
    }

    // -----------------------------------------------------------------------
    // Script modes, shift and layers
    // -----------------------------------------------------------------------

    private fun cycleMode() {
        setMode(
            when (mode) {
                KeyboardMode.ROMAN -> KeyboardMode.NATIVE
                KeyboardMode.NATIVE -> KeyboardMode.ENGLISH
                KeyboardMode.ENGLISH -> KeyboardMode.ROMAN
            },
        )
    }

    override fun onCycleModeRequested() {
        cycleMode()
    }

    override fun onModeSelected(mode: KeyboardMode) {
        setMode(mode)
    }

    /** Switching scripts mid-sentence finishes the word in progress first. */
    private fun setMode(next: KeyboardMode) {
        if (next == mode) {
            panel = ImePanel.NONE
            publish()
            return
        }
        host.inputConnection?.let { ic ->
            if (composer.hasRomanInput) commitRoman(ic, correctedWord = null)
        }
        mode = next
        showingSymbols = false
        symbolsAlt = false
        devanagariAlt = false
        composingPreview = ""
        shift = if (next == KeyboardMode.NATIVE) ShiftState.OFF else initialShift()
        candidates = emptyList()
        panel = ImePanel.NONE
        publish(layout = layoutForCurrentState())
        refreshCandidates()
    }

    private fun toggleSymbols() {
        showingSymbols = !showingSymbols
        symbolsAlt = false
        publish(layout = layoutForCurrentState())
    }

    private fun handleShiftTap() {
        if (mode == KeyboardMode.NATIVE) {
            devanagariAlt = !devanagariAlt
            publish(layout = layoutForCurrentState())
            return
        }
        shift = when (shift) {
            ShiftState.OFF -> ShiftState.ON
            ShiftState.AUTO -> ShiftState.ON
            ShiftState.ON -> ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        publish(layout = layoutForCurrentState())
    }

    private fun initialShift(): ShiftState = if (shouldAutoShift()) ShiftState.AUTO else ShiftState.OFF

    private fun shouldAutoShift(): Boolean {
        if (!snapshot.autoCapitalize) return false
        if (!supportsAutoCapitalization(host.editorDescriptor)) return false
        val ic = host.inputConnection ?: return false
        return composer.isAtSentenceStart(ic)
    }

    private fun updateShiftAfterEdit() {
        shift = when {
            shift == ShiftState.LOCKED -> ShiftState.LOCKED
            shouldAutoShift() -> ShiftState.AUTO
            else -> ShiftState.OFF
        }
        publish(layout = layoutForCurrentState())
    }

    private fun supportsAutoCapitalization(editor: EditorDescriptor): Boolean =
        !editor.sensitive && (editor.kind == EditorKind.TEXT || editor.kind == EditorKind.MULTILINE)

    private fun toggleOneHanded() {
        val next = if (oneHanded == OneHandedSide.OFF) OneHandedSide.LEFT else OneHandedSide.OFF
        onOneHandedChanged(next)
    }

    // -----------------------------------------------------------------------
    // Layout resolution
    // -----------------------------------------------------------------------

    private fun layoutId(): LayoutId {
        val editor = host.editorDescriptor
        when (editor.kind) {
            EditorKind.NUMBER, EditorKind.DECIMAL, EditorKind.DATE, EditorKind.TIME -> return LayoutId.NUMERIC
            EditorKind.PHONE -> return LayoutId.PHONE
            else -> Unit
        }
        if (editor.sensitive) return LayoutId.ENGLISH
        if (showingSymbols) return if (symbolsAlt) LayoutId.SYMBOLS_ALT else LayoutId.SYMBOLS
        return when (mode) {
            KeyboardMode.ROMAN -> when (editor.kind) {
                EditorKind.EMAIL -> LayoutId.EMAIL
                EditorKind.URI -> LayoutId.URI
                else -> LayoutId.ROMAN
            }

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

    // -----------------------------------------------------------------------
    // Candidates
    // -----------------------------------------------------------------------

    /**
     * Recomputes the strip for whatever is in front of the caret.
     *
     * Never blocks: the request goes through a conflated channel and the result
     * comes back on the main thread through [onCandidatesReady], tagged with the
     * generation it was requested for. A result whose generation has been
     * superseded by a newer keystroke is dropped instead of displayed, which is
     * what stops the strip from flickering back to an older word.
     */
    private fun refreshCandidates() {
        val editor = host.editorDescriptor
        if (editor.sensitive || !snapshot.showSuggestions) {
            generation++
            candidates = emptyList()
            candidatesLoading = false
            publish()
            return
        }
        generation++
        val request = generation

        if (composer.hasRomanInput) {
            candidatesLoading = true
            engine.submitRoman(
                generation = request,
                buffer = composer.romanText(),
                previousWord = contextWordBeforeCaret(),
                devanagariDigits = snapshot.devanagariNumerals,
            )
            publish()
            return
        }

        val prefix = wordPrefixBeforeCaret()
        val previous = contextWordBeforeCaret()
        if (prefix == null && previous == null) {
            candidates = emptyList()
            candidatesLoading = false
            publish()
            return
        }
        candidatesLoading = true
        engine.submitPrediction(
            generation = request,
            previousWord = previous,
            devanagariPrefix = prefix,
        )
        publish()
    }

    override fun onCandidatesReady(generation: Long, candidates: List<Candidate>, predictionOnly: Boolean) {
        if (generation != this.generation) return
        this.candidates = candidates
        this.candidatesLoading = false
        publish()
    }

    /**
     * The word immediately before the caret, ignoring our own composing text.
     *
     * `getTextBeforeCursor` happily returns the composition, so the composing
     * preview is stripped from the tail before the trailing word is taken.
     */
    private fun contextWordBeforeCaret(): String? {
        lastCommittedWord?.let { return it }
        val ic = host.inputConnection ?: return null
        val before = composer.readTextBefore(ic, CONTEXT_LOOKBACK) ?: return null
        if (before.isEmpty()) return null
        val stripped = if (composingPreview.isNotEmpty() && before.endsWith(composingPreview)) {
            before.substring(0, before.length - composingPreview.length)
        } else {
            before
        }
        return composer.trailingWord(stripped)
    }

    /** The run of word characters immediately left of the caret, capped. */
    private fun wordPrefixBeforeCaret(): String? {
        val ic = host.inputConnection ?: return null
        val before = composer.readTextBefore(ic, PREFIX_LOOKBACK) ?: return null
        if (before.isEmpty()) return null
        var start = before.length
        while (start > 0 && Devanagari.isWordChar(before[start - 1])) start--
        if (start == before.length) return null
        return before.substring(start)
    }

    // -----------------------------------------------------------------------
    // Panels, clipboard, emoji
    // -----------------------------------------------------------------------

    override fun onPanelRequested(panel: ImePanel) {
        togglePanel(panel)
    }

    private fun togglePanel(target: ImePanel) {
        panel = if (panel == target) ImePanel.NONE else target
        if (panel == ImePanel.EMOJI) emojiQuery = ""
        publish()
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
        val ic = host.inputConnection ?: return
        if (composer.hasRomanInput) commitRoman(ic, correctedWord = null)
        composer.commitText(ic, emoji, addSpaceAfter = false, devanagariDigits = false)
        runtime.emoji.record(emoji)
        afterLiteralCommit(emoji)
    }

    override fun onClearEmojiRecents() {
        runtime.emoji.clear()
    }

    override fun onClipboardEntrySelected(entry: ClipboardEntry) {
        val ic = host.inputConnection ?: return
        if (composer.hasRomanInput) commitRoman(ic, correctedWord = null)
        composer.commitText(ic, entry.text, addSpaceAfter = false, devanagariDigits = false)
        afterLiteralCommit(entry.text)
    }

    override fun onClipboardEntryRemoved(entry: ClipboardEntry) {
        runtime.clipboard.remove(entry)
    }

    override fun onClipboardEntryPinned(entry: ClipboardEntry) {
        runtime.clipboard.togglePin(entry)
    }

    override fun onClipboardCleared() {
        runtime.clipboard.clearAll()
    }

    override fun onOpenSettings() {
        host.openSettings()
    }

    override fun onHideKeyboard() {
        host.hideInputView()
    }

    override fun onSwitchInputMethod() {
        host.switchToNextInputMethod()
    }

    override fun onOneHandedChanged(side: OneHandedSide) {
        oneHanded = side
        scope.launch { runtime.settings.setOneHanded(side) }
        publish()
    }

    /**
     * Live height drag from the in-keyboard layout sheet.
     *
     * The input view resizes on every callback so the drag feels direct, but the
     * value is only written to DataStore once the drag settles: a slider gesture
     * otherwise produces one disk transaction per pixel.
     */
    override fun onKeyboardHeightRequested(scale: Float) {
        val coerced = scale.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
        host.applyKeyboardHeightScale(coerced)
        heightPersistJob?.cancel()
        heightPersistJob = scope.launch {
            delay(HEIGHT_PERSIST_DELAY_MS)
            runCatching { runtime.settings.setKeyboardHeightScale(coerced) }
                .onFailure { error -> KeyboardLog.w("Height persist failed: ${error.message}") }
        }
    }

    // -----------------------------------------------------------------------
    // Learning
    // -----------------------------------------------------------------------

    /**
     * Feeds one committed word to the personal model.
     *
     * Never called for password or opt-out fields, for single characters or for
     * pure digits: those carry no predictive value and are exactly what users do
     * not want remembered.
     */
    private fun learnWord(word: String, roman: String) {
        if (word.length < 2) return
        if (host.editorDescriptor.sensitive) return
        if (!hasLetter(word)) return
        val hits = runtime.learning.recordWord(word)
        if (hits == PROMOTION_HITS) runtime.learning.addPersonalWord(word, roman)
        scheduleLearningFlush()
    }

    private fun hasLetter(text: String): Boolean {
        for (index in text.indices) {
            val c = text[index]
            if (c in 'a'..'z' || c in 'A'..'Z' || Devanagari.isDevanagariBlock(c)) return true
        }
        return false
    }

    /** Coalesces writes: bursts of typing collapse into one DataStore commit. */
    private fun scheduleLearningFlush() {
        learningFlushJob?.cancel()
        learningFlushJob = scope.launch {
            delay(LEARNING_FLUSH_DELAY_MS)
            runCatching { runtime.learning.flush() }
                .onFailure { error -> KeyboardLog.w("Learning flush failed: ${error.message}") }
        }
    }

    private fun flushLearning() {
        learningFlushJob?.cancel()
        learningFlushJob = scope.launch {
            runCatching { runtime.learning.flush() }
                .onFailure { error -> KeyboardLog.w("Learning flush failed: ${error.message}") }
        }
    }

    // -----------------------------------------------------------------------
    // Clipboard observation
    // -----------------------------------------------------------------------

    private fun syncClipboardObservation() {
        val shouldObserve = snapshot.clipboardEnabled
        if (shouldObserve == clipboardObserving) return
        clipboardObserving = shouldObserve
        if (shouldObserve) {
            runtime.clipboard.startObservation()
        } else {
            runtime.clipboard.stopObservation()
        }
    }

    // -----------------------------------------------------------------------
    // Publishing
    // -----------------------------------------------------------------------

    private fun usesComposition(editor: EditorDescriptor): Boolean =
        mode == KeyboardMode.ROMAN && !editor.sensitive && !editor.kind.isNumericPad()

    private fun isRomanKey(text: String): Boolean {
        if (text.length != 1) return false
        val c = text[0]
        return c in 'a'..'z' || c in 'A'..'Z'
    }

    private fun afterLiteralCommit(text: String) {
        lastCommittedWord = null
        composingPreview = ""
        val boundary = text.length == 1 && (
            text[0] == '.' || text[0] == '!' || text[0] == '?' ||
                text[0] == Devanagari.DANDA || text[0] == '\n'
            )
        if (boundary) {
            updateShiftAfterEdit()
        } else {
            publish()
        }
        refreshCandidates()
    }

    private fun EditorKind.isNumericPad(): Boolean = when (this) {
        EditorKind.NUMBER, EditorKind.DECIMAL, EditorKind.PHONE, EditorKind.DATE, EditorKind.TIME -> true
        else -> false
    }

    /**
     * Publishes the model as one immutable snapshot.
     *
     * Called from every mutation path. The equality check means a gesture that
     * changes nothing visible (sliding a finger across keys, a released repeat)
     * costs one structural comparison and no state emission at all.
     */
    private fun publish(layout: KeyboardLayout? = null) {
        val current = _state.value
        val resolvedLayout = layout ?: current.layout ?: layoutForCurrentState()
        val next = ImeUiState(
            ready = ready,
            mode = mode,
            layout = resolvedLayout,
            layoutId = resolvedLayout.id,
            shift = shift,
            panel = panel,
            showingSymbols = showingSymbols,
            symbolsAlt = symbolsAlt,
            devanagariAlt = devanagariAlt,
            editor = host.editorDescriptor,
            candidates = candidates,
            candidatesLoading = candidatesLoading,
            romanBuffer = composer.romanText(),
            composingPreview = composingPreview,
            suggestionsVisible = snapshot.showSuggestions,
            toolbarVisible = snapshot.showToolbar,
            numberRow = snapshot.numberRow,
            devanagariNumerals = snapshot.devanagariNumerals,
            autoCorrect = snapshot.autoCorrect,
            longPressSymbols = snapshot.longPressSymbols,
            cursorDragEnabled = snapshot.cursorDragEnabled,
            swipeDeleteEnabled = snapshot.swipeDeleteEnabled,
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
    }

    private companion object {
        const val CONTEXT_LOOKBACK = 64
        const val PREFIX_LOOKBACK = 24
        const val PROMOTION_HITS = 2
        const val MIN_HEIGHT_SCALE = 0.7f
        const val MAX_HEIGHT_SCALE = 1.6f
        const val LEARNING_FLUSH_DELAY_MS = 4_000L
        const val HEIGHT_PERSIST_DELAY_MS = 220L
    }
}
