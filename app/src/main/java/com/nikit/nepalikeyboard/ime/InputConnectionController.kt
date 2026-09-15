package com.nikit.nepalikeyboard.ime

import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.nikit.nepalikeyboard.translit.RomanizedEngine
import com.nikit.nepalikeyboard.unicode.Devanagari
import com.nikit.nepalikeyboard.unicode.GraphemeClusterSegmenter
import com.nikit.nepalikeyboard.unicode.TextAnalysis

/**
 * =============================================================================
 * THE INPUT CONNECTION LAYER
 * =============================================================================
 *
 * Everything the keyboard does to a text field goes through exactly one object:
 * the `InputConnection` obtained from `InputMethodService.getCurrentInputConnection()`.
 * That object is a proxy across a Binder boundary into the target app's process,
 * and it is *stateful in ways that are not obvious*:
 *
 *  * `getTextBeforeCursor` on a field with 2 MB of text is a synchronous IPC
 *    that can block longer than a frame. It must never be called per keystroke
 *    in a way that lets the results pile up.
 *  * The connection returned by `getCurrentInputConnection()` changes identity
 *    when the user moves between fields, and the old one becomes a zombie whose
 *    methods silently return false.
 *  * `setComposingText` and `commitText` interact: committing while a composing
 *    region is active replaces it, and forgetting to `finishComposingText`
 *    leaves the target app believing there is an uncommitted edit in flight,
 *    which breaks its own undo and IME-action handling.
 *
 * This class owns all of that. The Compose UI never touches an
 * `InputConnection` directly; it sends intents here and reads back immutable
 * snapshots. That boundary is what keeps the UI layer testable and keeps the
 * Binder calls on a single, auditable path.
 *
 * =============================================================================
 * COMPOSING SPAN CONTRACT
 * =============================================================================
 *
 * Romanized input is inherently a composing operation: the user types "namast"
 * and sees नमस्त, types "e" and sees नमस्ते. The correct protocol is:
 *
 *   1. `setComposingText(devanagari, 1)` — the whole Romanized buffer is
 *      replaced in the field by its Devanagari rendering, marked as composing
 *      so the app underlines it and knows it is not final.
 *   2. On commit (space, punctuation, suggestion tap, Enter) —
 *      `setComposingText(finalText, 1)` then `finishComposingText()`, or
 *      `commitText(finalText, 1)` which implicitly finishes.
 *   3. On abandon (mode switch, focus loss, backspace past the start) —
 *      `finishComposingText()` with whatever is currently shown, so the app is
 *      never left holding a dangling composing region.
 *
 * We keep our own shadow copy of the current composing text ([composingText])
 * because reading it back from the connection is both slower and unreliable:
 * some apps report an empty `getComposingText` even while one is active, and
 * some editors (notably a few WebView-based ones) drop composing spans
 * entirely.
 *
 * =============================================================================
 * UNICODE SAFETY
 * =============================================================================
 *
 * Two distinct hazards, handled differently:
 *
 *  * **Surrogate pairs.** A character above U+FFFF is two UTF-16 code units.
 *    Deleting or replacing "one character" must move by two units or we emit a
 *    lone surrogate, which renders as U+FFFD and corrupts the buffer.
 *  * **Devanagari aksharas.** एक is one grapheme cluster but three code points
 *    (ए, क, ्). Deleting one "character" from it must remove the whole cluster,
 *    not leave a floating virama attached to nothing.
 *
 * Both are solved by never doing arithmetic on character counts. Every
 * deletion walks the buffer with [GraphemeClusterSegmenter], which implements
 * the UAX #29 extended-grapheme-cluster rules plus the Devanagari
 * virama-joins-forward override that UAX #29 alone does not give us. See that
 * class for the full rule table.
 *
 * =============================================================================
 */
class InputConnectionController {

    /**
     * The raw connection, or null when no editor is focused.
     *
     * Deliberately *not* cached across calls: `getCurrentInputConnection()`
     * goes through the service and can change under us at any moment (the user
     * taps a different field, the app restarts its view). We re-read it for
     * every operation and treat a null or stale result as "nothing to do".
     * The cost is a JNI hop; the alternative is writing into a dead connection.
     */
    private var connection: InputConnection? = null

    /**
     * Shadow copy of the text currently in the composing region.
     *
     * Empty means "no composing region is active". This is the single source of
     * truth for the Romanized buffer: the UI reads it to build suggestions, and
     * [setComposing] writes through it to the field. Keeping the authoritative
     * copy on our side means a field that mangles or drops composing spans
     * still gets correct behaviour, because we always replace the full region
     * rather than appending to whatever the field happens to contain.
     */
    var composingText: String = ""
        private set

    /**
     * Snapshot of the editor we are attached to. Replaced on every
     * `onStartInput`, never mutated in place — the Compose layer compares these
     * by identity for recomposition.
     */
    var editorInfo: EditorInfo? = null
        private set

    /**
     * Input type of the current field, cached from [editorInfo].
     *
     * Reading `EditorInfo.inputType` itself is cheap, but the *decisions* built
     * on it are not (password mode gates learning, suggestions, clipboard, and
     * auto-capitalisation), and those decisions are made on hot paths. Caching
     * the derived booleans means a keystroke never re-parses a bitmask.
     */
    var inputType: Int = InputType.TYPE_NULL
        private set

    /**
     * True when the field must be treated as a secret.
     *
     * Covers both the classic password variation and the newer
     * `TYPE_NUMBER_VARIATION_PASSWORD` / `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`
     * cases. When true:
     *  * no composing region is ever used — text is committed literally, one
     *    keypress at a time, so the app's own password UI sees only insertion
     *    events;
     *  * the transliteration engine is bypassed entirely;
     *  * nothing is written to the learned-word store or the clipboard history;
     *  * the suggestion strip is hidden by the UI.
     *
     * This is a hard invariant, not a preference. See AGENTS.md.
     */
    var isPasswordField: Boolean = false
        private set

    /**
     * True when the field expects a number/phone/date type, in which case we
     * route input through a numeric layout and never transliterate.
     */
    var isNumericField: Boolean = false
        private set

    /**
     * The IME action the editor asked for, e.g. `IME_ACTION_SEARCH`. Used both
     * to render the right glyph on the Enter key and to decide what
     * `performEditorAction` is called with.
     */
    var imeAction: Int = EditorInfo.IME_ACTION_NONE
        private set

    /**
     * True when the editor supplied initial text that should be selected on
     * first appearance (`EditorInfo.initialSelStart != initialSelEnd`). The
     * keyboard selects the whole field in that case, matching what every other
     * IME does for "share intent into a text field" flows.
     */
    var hasInitialSelection: Boolean = false
        private set

    /**
     * Whether the target app supports the extended editing commands
     * (`getExtractedText`, `commitCorrection`, and friends). Probed once per
     * editor in [attach]; used to decide whether it is safe to call
     * `commitCorrection` for autocorrect.
     */
    private var supportsCorrection: Boolean = false

    /**
     * Timestamp of the last committed space, for double-space-to-period.
     * `SystemClock.uptimeMillis` rather than wall time so a clock change cannot
     * spuriously fire or suppress the feature.
     */
    private var lastSpaceUptime: Long = 0L

    /**
     * Cap on how much text we will ever pull back from the field in one call.
     *
     * This is a bound on IPC size, not on a memory allocation — the platform
     * truncates anyway, but by asking for a bounded amount we keep the
     * Binder transaction under the 1 MB transaction limit and keep the call
     * fast on very large documents.
     */
    private val maxLookback: Int = 256

    // =========================================================================
    // Attachment
    // =========================================================================

    /**
     * Called from `NepaliImeService.onStartInput(info, restarting)`.
     *
     * Parses the `EditorInfo` once and derives every flag the hot paths need,
     * so that no keystroke ever reconsults a bitmask. Also resets per-editor
     * state: switching from a chat field into a password field mid-session must
     * not leak the previous field's composing buffer or capitalisation state.
     */
    fun attach(info: EditorInfo, connection: InputConnection?) {
        this.connection = connection
        this.editorInfo = info
        this.inputType = info.inputType

        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val cls = info.inputType and InputType.TYPE_MASK_CLASS

        val klassIsText = cls == InputType.TYPE_CLASS_TEXT
        val klassIsNumber = cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME

        isNumericField = klassIsNumber

        isPasswordField = when {
            // The plain text-password variation.
            klassIsText && variation == InputType.TYPE_TEXT_VARIATION_PASSWORD -> true
            // Visible-password fields render the secret on screen but are still
            // secrets, and the platform's own IME guidance is to suppress
            // learning here too. We follow suit.
            klassIsText && variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> true
            // Numeric PIN entry.
            klassIsNumber && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
            // WebView-based password inputs historically report the text class
            // with variation 0xE0 rather than the documented password
            // variation. The value is stable in the platform but is not exposed
            // as a public constant.
            klassIsText && variation == WEB_PASSWORD_VARIATION -> true
            else -> false
        }

        // A field marked "no suggestions" is a strong signal that it is secret
        // even if it does not use a password variation — some banking apps do
        // exactly this. Treat it as a password field for our purposes: no
        // learning, no suggestions, no history.
        if (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) {
            isPasswordField = true
        }

        imeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
        hasInitialSelection = info.initialSelStart >= 0 &&
            info.initialSelEnd >= 0 &&
            info.initialSelStart != info.initialSelEnd

        supportsCorrection = connection?.getExtractedText(
            ExtractedTextProbe, 0
        ) != null

        // Per-editor reset. Anything that survived the previous field must not
        // bleed into a new one.
        composingText = ""
        lastSpaceUptime = 0L
    }

    /**
     * Re-binds to a fresh connection object for the *same* editor.
     *
     * The framework hands us a new proxy after configuration changes and after
     * the target app recreates its view. We want to keep the composer state
     * (the user is mid-word, they rotated the phone, they should not lose it)
     * while pointing at the live connection.
     */
    fun rebind(connection: InputConnection?) {
        this.connection = connection
    }

    /** Detaches entirely; called from `onFinishInput`. */
    fun detach() {
        connection = null
        editorInfo = null
        inputType = InputType.TYPE_NULL
        isPasswordField = false
        isNumericField = false
        imeAction = EditorInfo.IME_ACTION_NONE
        hasInitialSelection = false
        supportsCorrection = false
        composingText = ""
        lastSpaceUptime = 0L
    }

    /** True when there is something to write to. */
    val isAttached: Boolean
        get() = connection != null

    // =========================================================================
    // Composing lifecycle
    // =========================================================================

    /**
     * Replaces the composing region with [devanagari] and records it as the
     * authoritative composing buffer.
     *
     * `setComposingText` with an empty string is *not* equivalent to
     * `finishComposingText` on every implementation: several editors keep a
     * zero-length composing span, which then swallows the next commit. So an
     * empty [devanagari] routes through [finishComposing] instead.
     *
     * The `newCursorPosition = 1` argument means "leave the cursor one
     * character after the inserted text", which for a composing region that is
     * being wholly replaced is exactly right — the cursor must sit at the end
     * of the region, not in the middle of it.
     */
    fun setComposing(devanagari: String): Boolean {
        val ic = connection ?: return false
        if (isPasswordField) return false
        if (devanagari.isEmpty()) return finishComposing()
        val ok = ic.setComposingText(devanagari, CURSOR_AT_END)
        if (ok) composingText = devanagari
        return ok
    }

    /**
     * Commits whatever is currently composing and clears the composing region.
     *
     * Uses `finishComposingText` rather than `commitText` so that the text
     * already visible in the field is left in place and simply becomes
     * non-composing, which is what the user perceives as "the word is now
     * normal text". Doing it the other way round (`commitText` with the same
     * content) would emit a second insertion and double the word in editors
     * that do not treat a commit as replacing the composing span.
     */
    fun finishComposing(): Boolean {
        val ic = connection ?: return false
        if (composingText.isEmpty()) return true
        val ok = ic.finishComposingText()
        composingText = ""
        return ok
    }

    /**
     * Clears the composing region without committing it.
     *
     * Subtly different from [finishComposing]: `finishComposingText` keeps the
     * visible text and merely stops marking it as composing, whereas this also
     * removes it. We need the removing form when the user abandons a word —
     * tapping elsewhere in the text, switching to a layout that cannot represent
     * the Roman buffer, or dismissing the keyboard — because in those cases the
     * half-typed Devanagari should not be left behind.
     *
     * Implemented as `setComposingText("", 1)` followed by
     * `finishComposingText()`. The empty set is required: on several editors
     * `finishComposingText` alone leaves the characters in the field.
     */
    fun detachComposing(): Boolean {
        val ic = connection ?: return false
        if (composingText.isEmpty()) return true
        val cleared = ic.setComposingText("", CURSOR_AT_END)
        val finished = ic.finishComposingText()
        composingText = ""
        return cleared || finished
    }

    /**
     * Commits [text], replacing any active composing region.
     *
     * This is the path taken when the user taps a suggestion. It is a single
     * `commitText` call, which on every implementation replaces the composing
     * span in one transaction — the alternative (finish, then insert) would
     * flicker the field and can trip some editors' text watchers twice.
     *
     * Returns true when the field accepted the commit, so the caller can decide
     * whether to clear its own buffer. A false return means the connection went
     * away mid-gesture and the caller should resynchronise rather than assume
     * success.
     */
    fun commit(text: String): Boolean {
        if (text.isEmpty()) return finishComposing()
        val ic = connection ?: return false
        val ok = ic.commitText(text, CURSOR_AT_END)
        composingText = ""
        return ok
    }

    /**
     * Commits [text] and *then* requests an editor action, for the
     * "type and hit search" flow where a suggestion tap should also fire Search.
     */
    fun commitAndPerformAction(text: String, action: Int): Boolean {
        val committed = commit(text)
        if (!committed) return false
        return performEditorAction(action)
    }

    /**
     * Sends the editor action the field asked for (Search, Send, Go, Next,
     * Done, …).
     *
     * Some editors report `IME_ACTION_NONE` and expect a raw newline instead
     * (that is what "Enter" means in a multi-line field). We honour that: when
     * no action was requested, we insert a newline rather than suppressing the
     * key entirely, because a keyboard whose Enter key does nothing is broken.
     */
    fun performEditorAction(action: Int): Boolean {
        val ic = connection ?: return false
        val effective = if (action == EditorInfo.IME_ACTION_NONE ||
            action == EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            // Multi-line field, or an editor that declined to specify. Emit a
            // literal newline so Enter still produces a line break.
            return ic.commitText("\n", CURSOR_AT_END)
        } else {
            action
        }
        return ic.performEditorAction(effective)
    }

    // =========================================================================
    // Reading
    // =========================================================================

    /**
     * The [maxLookback] characters preceding the cursor, or an empty string.
     *
     * Every caller in the keyboard that needs context (auto-capitalisation,
     * double-space detection, swipe-to-delete-word, bigram lookup) goes through
     * here so that the IPC is bounded in one place. Callers must not hold the
     * result across frames — the connection may have moved on.
     */
    fun textBeforeCursor(maxChars: Int = maxLookback): CharSequence {
        val ic = connection ?: return ""
        val bounded = if (maxChars > maxLookback) maxLookback else maxChars
        return ic.getTextBeforeCursor(bounded, 0) ?: ""
    }

    /** The [maxChars] characters following the cursor, or an empty string. */
    fun textAfterCursor(maxChars: Int = maxLookback): CharSequence {
        val ic = connection ?: return ""
        val bounded = if (maxChars > maxLookback) maxLookback else maxChars
        return ic.getTextAfterCursor(bounded, 0) ?: ""
    }

    /**
     * The word immediately before the cursor, as Devanagari or Latin, with the
     * composable-device-aware boundary rules from [TextAnalysis].
     *
     * Used for bigram context and for "was the previous token a real word" when
     * deciding whether to learn.
     */
    fun wordBeforeCursor(): String {
        val before = textBeforeCursor()
        if (before.isEmpty()) return ""
        return TextAnalysis.currentWordBefore(before, before.length, 0)
    }

    /** The character immediately preceding the cursor, or `'\u0000'`. */
    fun charBeforeCursor(): Char {
        val before = textBeforeCursor(4)
        return if (before.isEmpty()) '\u0000' else before[before.length - 1]
    }

    /**
     * True when the cursor sits at the very start of the field's text.
     *
     * Asking for zero characters back gives an empty result both at position
     * zero and for an empty field, so we additionally probe the selection to
     * distinguish "empty field" (cursor 0, and that is the start) from "the
     * connection is dead" (no selection at all).
     */
    fun isCursorAtStart(): Boolean {
        val ic = connection ?: return true
        val before = ic.getTextBeforeCursor(1, 0)
        return before == null || before.isEmpty()
    }

    /** True when the whole field is empty. */
    fun isFieldEmpty(): Boolean {
        val ic = connection ?: return true
        val before = ic.getTextBeforeCursor(1, 0) ?: return true
        val after = ic.getTextAfterCursor(1, 0) ?: return true
        return before.isEmpty() && after.isEmpty()
    }

    /**
     * True when the cursor is not inside our composing region — i.e. the user
     * tapped somewhere else in the text with their finger, or the app moved the
     * cursor programmatically.
     *
     * Called from `onUpdateSelection`. When this returns true the keyboard must
     * abandon its composing buffer: continuing to compose would replace text at
     * a location the user did not intend, which is the single most destructive
     * bug an IME can have.
     */
    fun selectionMovedOutsideComposing(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        composingStart: Int,
        composingEnd: Int
    ): Boolean {
        if (composingText.isEmpty()) return false
        if (composingStart < 0 || composingEnd < 0) return true
        // The cursor must still be somewhere inside [composingStart, composingEnd].
        val inside = newSelStart in composingStart..composingEnd &&
            newSelEnd in composingStart..composingEnd
        if (inside) return false
        // A pure caret move within the composing region is fine; anything that
        // leaves it, or turns into a range selection, abandons composition.
        return true
    }

    // =========================================================================
    // Editing primitives
    // =========================================================================

    /**
     * Deletes one grapheme cluster to the left of the cursor.
     *
     * Three cases, in order of preference:
     *
     *  1. **Inside a composing region.** The whole composer is Romanized input,
     *     so we drop the last *Roman* character and re-render. Doing grapheme
     *     arithmetic on the Devanagari rendering would be wrong: deleting a
     *     cluster from नमस्ते removes ते, but the user expects the Roman 'e' to
     *     disappear and the buffer to become "namast".
     *  2. **A grapheme cluster in the field.** We ask the field for a lookback
     *     window, find the previous cluster boundary with the segmenter, and
     *     issue a single `deleteSurroundingText` for the exact number of UTF-16
     *     units. Never `sendKeyEvent(KEYCODE_DEL)` — that is not grapheme-safe
     *     and corrupts surrogate pairs and Devanagari clusters, which is the
     *     single most common Unicode bug in third-party IMEs.
     *  3. **A selection.** Deleting with a selection removes the selection, not
     *     a character. This is what every native text field does and users
     *     expect it.
     */
    fun deleteBackward(): Boolean {
        val ic = connection ?: return false

        // 1. Composing region: operate on the Romanized buffer.
        if (composingText.isNotEmpty()) {
            return deleteInsideComposing()
        }

        // 3. Selection: let the field clear it. `deleteSurroundingText(0, 0)`
        //    is the documented no-op-safe way to make some editors drop a
        //    selection, but the reliable route is `commitText("")`, which
        //    replaces the selection with nothing.
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) {
            return ic.commitText("", CURSOR_AT_END)
        }

        // 2. Real text: compute the exact cluster width in UTF-16 units.
        val before = ic.getTextBeforeCursor(maxLookback, 0) ?: return false
        if (before.isEmpty()) {
            // Nothing to delete and no composing region — tell the service so
            // it can fall back to KEYCODE_DEL (some editors need the key event
            // to update their own internal state, e.g. to leave a chip).
            return false
        }
        val len = before.length
        val boundary = GraphemeClusterSegmenter.previousBoundary(before, len, 0)
        val count = len - boundary
        if (count <= 0) return false
        return ic.deleteSurroundingText(count, 0)
    }

    /**
     * Drops the last *Roman* character from the composing buffer and re-renders
     * the Devanagari.
     *
     * The unit of deletion is a UTF-16 code unit fallback only when the buffer
     * ends in a surrogate pair — which can happen if the user switched to the
     * English layout mid-word and typed a non-BMP character. Otherwise it is a
     * single `Char`, because the composer only ever holds ASCII Roman input.
     *
     * Returns false when the buffer became empty, letting the caller decide
     * whether to also tell the service the composing region is gone.
     */
    private fun deleteInsideComposing(): Boolean {
        val ic = connection ?: return false
        val current = composingText
        if (current.isEmpty()) return false

        val cut = if (current.length >= 2 && Character.isLowSurrogate(current[current.length - 1])) {
            current.length - 2
        } else {
            current.length - 1
        }
        if (cut <= 0) {
            // The composer is about to become empty. Clear the field's
            // composing span entirely so no stray mark is left behind.
            ic.finishComposingText()
            composingText = ""
            return true
        }
        val next = current.substring(0, cut)
        val rendered = renderComposing(next)
        val ok = ic.setComposingText(rendered, CURSOR_AT_END)
        if (ok) composingText = next
        return ok
    }

    /**
     * Deletes the previous word, used by the backspace swipe-left gesture.
     *
     * Repeatedly steps grapheme clusters backwards, skipping trailing
     * whitespace first and then word characters, so that "mero desh " deletes
     * to "mero " in one action. If a composing region is active we clear the
     * whole composer instead, because the composer *is* the word being written.
     *
     * The loop is bounded by [MAX_WORD_DELETE_CLUSTERS] so that a pathological
     * field (a single 10 000-character token) cannot make one gesture stall the
     * input thread.
     */
    fun deleteWordBackward(): Boolean {
        val ic = connection ?: return false

        if (composingText.isNotEmpty()) {
            ic.finishComposingText()
            composingText = ""
            return true
        }

        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) {
            return ic.commitText("", CURSOR_AT_END)
        }

        val before = ic.getTextBeforeCursor(maxLookback, 0) ?: return false
        if (before.isEmpty()) return false

        var cursor = before.length
        var clusters = 0

        // Skip trailing whitespace, but only if there is a word before it —
        // otherwise deleting from "mero " would consume the space and stop,
        // which feels like the key did nothing.
        var probe = cursor
        while (probe > 0 && clusters < MAX_WORD_DELETE_CLUSTERS) {
            val b = GraphemeClusterSegmenter.previousBoundary(before, probe, 0)
            if (b == probe) break
            val cp = before[b]
            if (cp == ' ' || cp == '\n' || cp == '\t' || cp == '\u00A0') {
                probe = b
                clusters++
            } else {
                break
            }
        }
        // Only accept the whitespace skip if a word follows it.
        val whitespaceEnd = probe

        var wordStart = whitespaceEnd
        var sawWordChar = false
        while (wordStart > 0 && clusters < MAX_WORD_DELETE_CLUSTERS) {
            val b = GraphemeClusterSegmenter.previousBoundary(before, wordStart, 0)
            if (b == wordStart) break
            val cp = before[b]
            if (TextAnalysis.isWordChar(cp)) {
                sawWordChar = true
                wordStart = b
                clusters++
            } else {
                break
            }
        }

        if (!sawWordChar) {
            // No word characters found; fall back to deleting just the
            // whitespace run we scanned, so a swipe still does something.
            wordStart = whitespaceEnd
        }

        val count = cursor - wordStart
        if (count <= 0) return false
        return ic.deleteSurroundingText(count, 0)
    }

    /**
     * Deletes [count] grapheme clusters to the left, used by backspace
     * press-and-hold ("delete faster the longer you hold").
     *
     * Bounded and grapheme-exact; never issues a repeat key event, because key
     * events are not cluster-safe on any platform.
     */
    fun deleteClustersBackward(count: Int): Boolean {
        if (count <= 0) return false
        val ic = connection ?: return false

        if (composingText.isNotEmpty()) {
            return deleteInsideComposing()
        }

        val before = ic.getTextBeforeCursor(maxLookback, 0) ?: return false
        if (before.isEmpty()) return false

        var cursor = before.length
        var removed = 0
        while (removed < count && cursor > 0) {
            val b = GraphemeClusterSegmenter.previousBoundary(before, cursor, 0)
            if (b == cursor) break
            cursor = b
            removed++
        }
        val units = before.length - cursor
        if (units <= 0) return false
        return ic.deleteSurroundingText(units, 0)
    }

    /**
     * Replaces the [before] characters preceding the cursor with [replacement].
     *
     * Used by autocorrect: the field holds the misspelling, we swap it for the
     * correction. Implemented as a single `deleteSurroundingText` followed by a
     * `commitText` so the two edits land in one batch and the target app's text
     * watcher sees a coherent change.
     */
    fun replaceBefore(before: Int, replacement: String): Boolean {
        if (before <= 0) return false
        val ic = connection ?: return false
        val deleted = ic.deleteSurroundingText(before, 0)
        if (!deleted) return false
        if (replacement.isEmpty()) return true
        return ic.commitText(replacement, CURSOR_AT_END)
    }

    /**
     * Moves the caret by [delta] grapheme clusters (negative = left).
     *
     * ### Finding the caret without a position-query API
     *
     * `InputConnection.setSelection` takes *absolute* coordinates, but the
     * connection exposes no "where is the caret" method. The observation that
     * resolves this cleanly: `getTextBeforeCursor(n, 0)` returns the `n`
     * characters immediately preceding the caret, or fewer if the field is
     * shorter. Therefore **`getTextBeforeCursor(n).length` is exactly the
     * absolute caret index**, as long as at least `n` characters precede the
     * caret. Asking for [CURSOR_WINDOW] characters makes that hold for every
     * realistic gesture, because a cursor drag only nudges the caret by a
     * handful of clusters at a time.
     *
     * So a leftward move is: read a window, walk cluster boundaries inwards
     * from its end, and the resulting index *within* the window is also the
     * correct absolute index, because the window began at absolute 0.
     *
     * A rightward move needs both halves: the absolute caret index from the
     * "before" read, plus the cluster offset within the "after" window.
     *
     * ### Why clusters and not characters
     *
     * Moving by one UTF-16 unit inside नमस्ते lands the caret between a
     * consonant and its matra. The next character typed is then inserted
     * mid-syllable, producing text that no longer renders correctly and that no
     * amount of undo repairs cleanly. Every cursor movement in this keyboard
     * walks grapheme boundaries for that reason.
     *
     * Returns the number of clusters actually moved, so a gesture handler can
     * carry leftover sub-cluster drag distance into the next frame instead of
     * accumulating rounding error.
     */
    fun moveCursorByClusters(delta: Int): Int {
        if (delta == 0) return 0
        val ic = connection ?: return 0

        // A live selection collapses first. Dragging with a selection active
        // should reposition the caret, not extend or shrink the selection.
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            val before = ic.getTextBeforeCursor(CURSOR_WINDOW, 0) ?: return 0
            val caret = before.length
            ic.setSelection(caret, caret)
            return 0
        }

        if (delta < 0) {
            val window = ic.getTextBeforeCursor(CURSOR_WINDOW, 0) ?: return 0
            if (window.isEmpty()) return 0
            var index = window.length
            var moved = 0
            while (moved > delta && index > 0) {
                val b = GraphemeClusterSegmenter.previousBoundary(window, index, 0)
                if (b == index) break
                index = b
                moved--
            }
            if (moved == 0) return 0
            // `index` is already the absolute caret position, because the
            // window began at absolute 0.
            return if (ic.setSelection(index, index)) moved else 0
        } else {
            val window = ic.getTextAfterCursor(CURSOR_WINDOW, 0) ?: return 0
            if (window.isEmpty()) return 0
            var offset = 0
            var moved = 0
            val end = window.length
            while (moved < delta && offset < end) {
                val b = GraphemeClusterSegmenter.nextBoundary(window, offset, end)
                if (b == offset) break
                offset = b
                moved++
            }
            if (moved == 0) return 0
            val before = ic.getTextBeforeCursor(CURSOR_WINDOW, 0) ?: return 0
            val target = before.length + offset
            return if (ic.setSelection(target, target)) moved else 0
        }
    }

    /**
     * Moves the caret to the end of the previous or next word.
     *
     * Uses the same caret-index identity as [moveCursorByClusters]: the length
     * of the "before" window is the absolute caret index, so a leftward move is
     * simply `setSelection(index)` on the computed word start.
     *
     * Returns the number of characters moved, or 0 if there was nothing to do.
     */
    fun moveCursorByWord(forward: Boolean): Int {
        val ic = connection ?: return 0
        return if (forward) {
            val after = ic.getTextAfterCursor(CURSOR_WINDOW, 0) ?: return 0
            if (after.isEmpty()) return 0
            val end = TextAnalysis.wordEndAfter(after, 0, after.length)
            if (end <= 0) return 0
            val before = ic.getTextBeforeCursor(CURSOR_WINDOW, 0) ?: return 0
            val target = before.length + end
            if (ic.setSelection(target, target)) end else 0
        } else {
            val before = ic.getTextBeforeCursor(CURSOR_WINDOW, 0) ?: return 0
            if (before.isEmpty()) return 0
            val start = TextAnalysis.wordStartBefore(before, before.length, 0)
            if (ic.setSelection(start, start)) before.length - start else 0
        }
    }

    /**
     * Selects the entire field's contents. Bound to a long-press on the mode
     * switcher; also used when a field arrives pre-filled with an initial
     * selection we want to widen to the whole value.
     */
    fun selectAll(): Boolean {
        val ic = connection ?: return false
        val before = ic.getTextBeforeCursor(SELECT_ALL_PROBE, 0) ?: return false
        val after = ic.getTextAfterCursor(SELECT_ALL_PROBE, 0) ?: return false
        val len = before.length + after.length
        if (len == 0) return false
        return ic.setSelection(0, len)
    }

    // =========================================================================
    // Text transforms used by the UI layer
    // =========================================================================

    /**
     * Renders a Romanized buffer into the Devanagari that should occupy the
     * composing region.
     *
     * `isComplete = false` for the live composing render so that the
     * trailing-schwa rule does not fire mid-word: typing "nepaa" must show
     * नेपा, not नेपा्. The final form is produced at commit time with
     * `isComplete = true`, which is where the schwa decision belongs.
     *
     * Password fields bypass this entirely and return the input unchanged.
     */
    fun renderComposing(roman: String): String {
        if (roman.isEmpty()) return ""
        if (isPasswordField) return roman
        return RomanizedEngine.toDevanagari(roman, isComplete = false)
    }

    /**
     * Converts a finished Romanized word into its committed Devanagari form,
     * applying the complete-word rules (schwa deletion, terminal signs).
     */
    fun renderCommitted(roman: String): String {
        if (roman.isEmpty()) return ""
        if (isPasswordField) return roman
        return RomanizedEngine.toDevanagari(roman, isComplete = true)
    }

    // =========================================================================
    // Feature-specific helpers
    // =========================================================================

    /**
     * True when the user just tapped space twice within
     * [DOUBLE_SPACE_WINDOW_MS], which the settings screen can map to
     * "insert a full stop".
     *
     * The check is done here rather than in the UI because it needs the field's
     * own text to confirm the space actually landed, and because the timing
     * source should be monotonic.
     */
    fun isDoubleSpace(): Boolean {
        val now = SystemClock.uptimeMillis()
        val previous = lastSpaceUptime
        lastSpaceUptime = now
        if (previous == 0L) return false
        return now - previous <= DOUBLE_SPACE_WINDOW_MS
    }

    /** Clears the double-space timer, e.g. after any non-space key. */
    fun resetDoubleSpaceTimer() {
        lastSpaceUptime = 0L
    }

    /**
     * The text the user can currently act on for clipboard capture.
     *
     * Returns the selection when there is one, otherwise the word under the
     * cursor. Empty in a password field — never copy anything out of a secret
     * field into a history that other apps can read.
     */
    fun selectedOrWord(): String {
        if (isPasswordField) return ""
        val ic = connection ?: return ""
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) return sel.toString()
        return wordBeforeCursor()
    }

    /**
     * True when the keyboard should offer capitalisation for the next key.
     *
     * Delegates the actual sentence analysis to [TextAnalysis] but feeds it the
     * field's real text so that the answer reflects what the app already
     * contains — tapping into the middle of a sentence must not capitalise.
     */
    fun shouldAutoCapitalise(): Boolean {
        if (isPasswordField) return false
        if (isNumericField) return false
        if (inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES == 0) return false
        val before = textBeforeCursor()
        if (before.isEmpty()) return true
        return TextAnalysis.shouldCapitalise(before, before.length)
    }

    /**
     * True when the field is a multi-line one, in which case the Enter key must
     * insert a newline instead of firing an action — and the swipe-to-enter
     * gesture must be disabled, because a swipe on a spacebar in a message
     * composer is far more likely to be a cursor drag.
     */
    fun isMultiline(): Boolean =
        (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

    /** True when the field is a URI, email, or other address-like input. */
    fun isUriLike(): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    /**
     * Rewrites ASCII digits in [text] as Devanagari digits.
     *
     * Used by the Nepali native layout when the user has asked for localised
     * numerals. Returns [text] unchanged when it contains no ASCII digit, so
     * the common case allocates nothing.
     */
    fun localiseDigits(ascii: String): String {
        var needsWork = false
        for (c in ascii) {
            if (c in '0'..'9') {
                needsWork = true
                break
            }
        }
        if (!needsWork) return ascii
        val sb = StringBuilder(ascii.length)
        for (c in ascii) {
            sb.append(Devanagari.toDevanagariDigit(c))
        }
        return sb.toString()
    }

    /**
     * Number of grapheme clusters in [text]; exposed so the UI can render a
     * character counter that agrees with what deletion will do.
     */
    fun graphemeCount(text: CharSequence): Int =
        GraphemeClusterSegmenter.countGraphemes(text)

    /**
     * Whether the target editor will accept `commitCorrection`. Cached per
     * editor by [attach]; when false the autocorrect path falls back to a plain
     * `replaceBefore` swap, which every editor supports.
     */
    val acceptsCorrections: Boolean
        get() = supportsCorrection && !isPasswordField

    companion object {
        /**
         * Cursor placement argument for every insert. `1` means "one character
         * after the inserted text", i.e. at its end. We never pass `0` or a
         * negative value: those position the caret *inside* the text just
         * written, which for a composing region means the next keystroke
         * inserts mid-syllable.
         */
        private const val CURSOR_AT_END = 1

        /**
         * Upper bound on grapheme steps per word-delete. A backspace swipe on a
         * field containing one enormous token must not spin; capping at 128
         * clusters still deletes any realistic word.
         */
        private const val MAX_WORD_DELETE_CLUSTERS = 128

        /**
         * Lookback for "select all". Large enough for any realistic field
         * while staying well under the Binder transaction limit.
         */
        private const val SELECT_ALL_PROBE = 100_000

        /**
         * Window used by every cursor-movement operation.
         *
         * 512 characters is far more than a cursor drag will ever traverse in
         * one frame, and small enough that the IPC stays comfortably inside the
         * Binder transaction budget. The `getTextBeforeCursor` length identity
         * documented on [moveCursorByClusters] holds whenever the caret is at
         * least this far into the field, which is exactly the case for a drag.
         */
        private const val CURSOR_WINDOW = 512

        /**
         * Variation code WebView uses for password inputs. Stable in the
         * platform but not declared as a public constant, so it is named here
         * rather than inlined as a bare hex literal.
         */
        private const val WEB_PASSWORD_VARIATION = 0x000000e0

        /**
         * Two taps on space within this window count as a double space.
         * 350 ms matches the platform's own double-tap timeout closely enough
         * that the feature feels native.
         */
        private const val DOUBLE_SPACE_WINDOW_MS = 350L

        /**
         * Reusable request used purely to probe whether an editor
         * implements the extracted-text interface. Allocating a fresh one per
         * field would be a small but avoidable cost on every focus change.
         */
        private val ExtractedTextProbe = android.view.inputmethod.ExtractedTextRequest()
    }
}
