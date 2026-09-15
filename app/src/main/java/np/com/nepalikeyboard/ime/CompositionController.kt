package np.com.nepalikeyboard.ime

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import np.com.nepalikeyboard.engine.PhoneticEngine
import np.com.nepalikeyboard.engine.unicode.Devanagari
import np.com.nepalikeyboard.engine.unicode.Graphemes

/** What the IME service exposes to the controller. */
interface ImeHost {
    /** Current connection of the focused editor, or null while none is focused. */
    val inputConnection: InputConnection?

    val editorDescriptor: EditorDescriptor

    /** `performEditorAction` for the action the editor asked for. */
    fun performEditorAction()

    /** `requestHideSelf(0)` with the right flags. */
    fun hideInputView()

    /** Launches the settings activity. */
    fun openSettings()

    /** Applies a new keyboard height scale to the input view. */
    fun applyKeyboardHeightScale(scale: Float)

    /** Switches to the next input method (system picker when only one is enabled). */
    fun switchToNextInputMethod()
}

/**
 * Owns everything that mutates the editor through `InputConnection`.
 *
 * Invariants enforced here
 * ------------------------
 * 1. **Composing text is the single source of truth in the field.** In
 *    romanized mode the *roman* buffer is the source of truth and the Devanagari
 *    preview is always re-rendered from it, so deleting, switching scripts or
 *    re-rendering can never desynchronise the two.
 * 2. **Every delete is grapheme-cluster aligned.** Surrogate pairs (emoji) and
 *    Devanagari clusters (म + ा + त + ् + र) are never split, because
 *    `deleteSurroundingText` counts UTF-16 units.
 * 3. **Sensitive fields are literal-only.** No composition, no learning, no
 *    candidates; the caller guarantees the same for suggestions.
 * 4. **Reusable scratch buffers.** The roman buffer and the transliteration
 *    builder are reused across keystrokes; the only allocation on the hot path
 *    is the immutable String handed to the UI and the candidate worker.
 */
internal class CompositionController(private val host: ImeHost) {

    private val buffer = StringBuilder(INITIAL_BUFFER)
    private val previewBuilder = StringBuilder(INITIAL_BUFFER * 2)
    private val extractedRequest = ExtractedTextRequest().also {
        it.flags = 0
        it.hintMaxChars = EXTRACT_HINT_CHARS
        it.hintMaxLines = 1
    }

    private var composingActive = false

    /** True while the editor holds an active composing region of ours. */
    val hasComposition: Boolean get() = composingActive

    val romanLength: Int get() = buffer.length

    val hasRomanInput: Boolean get() = buffer.length > 0

    /** Immutable snapshot of the roman buffer for the UI and candidate worker. */
    fun romanText(): String = buffer.toString()

    fun isDigitOnlyBuffer(): Boolean {
        if (buffer.isEmpty()) return false
        for (index in buffer.indices) {
            if (buffer[index] !in '0'..'9') return false
        }
        return true
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Drops any composition (editor switch, input view hidden, mode switch). */
    fun reset(ic: InputConnection?) {
        if (composingActive) {
            try {
                ic?.finishComposingText()
            } catch (_: RuntimeException) {
                // The editor may already be gone; nothing to clean up.
            }
        }
        composingActive = false
        buffer.setLength(0)
    }

    /**
     * Called from `onUpdateSelection`. When the framework reports that our
     * composing region vanished (the app rewrote the text, the user moved the
     * caret out of the composition), the roman buffer is stale and must be
     * dropped so the next keystroke starts a clean word instead of corrupting
     * what is already on screen.
     */
    fun reconcileSelection(composingStart: Int, composingEnd: Int, selectionStart: Int, selectionEnd: Int): Boolean {
        if (!composingActive) return false
        val compositionGone = composingStart < 0 || composingEnd < 0
        if (compositionGone) {
            composingActive = false
            buffer.setLength(0)
            return true
        }
        // Any selection outside the composing region means the caret was moved
        // (or the text was edited) underneath us.
        val outside = selectionStart < composingStart || selectionEnd > composingEnd
        if (outside) {
            composingActive = false
            buffer.setLength(0)
            return true
        }
        return false
    }

    // -----------------------------------------------------------------------
    // Romanized (phonetic) input
    // -----------------------------------------------------------------------

    /** Appends a roman character and re-renders the preview. Returns the preview. */
    fun typeRoman(ic: InputConnection, text: String, devanagariDigits: Boolean): String? {
        if (text.isEmpty()) return null
        if (buffer.length + text.length > MAX_BUFFER) {
            // Extremely long uncommitted word: flush instead of growing forever.
            commitComposition(ic, buffer.toString())
        }
        buffer.append(text)
        return render(ic, devanagariDigits)
    }

    /** Removes one roman code point. Returns the new preview, or null when empty. */
    fun backspaceRoman(ic: InputConnection, devanagariDigits: Boolean): String? {
        if (buffer.isEmpty()) return null
        val deleteCount = if (buffer.length >= 2 &&
            Character.isLowSurrogate(buffer[buffer.length - 1]) &&
            Character.isHighSurrogate(buffer[buffer.length - 2])
        ) {
            2
        } else {
            1
        }
        buffer.setLength(buffer.length - deleteCount)
        if (buffer.isEmpty()) {
            reset(ic)
            return ""
        }
        return render(ic, devanagariDigits)
    }

    /** Transliteration of the current buffer without touching the editor. */
    fun previewTransliteration(devanagariDigits: Boolean): String {
        if (buffer.isEmpty()) return ""
        previewBuilder.setLength(0)
        PhoneticEngine.transliterateInto(buffer, previewBuilder, devanagariDigits)
        return previewBuilder.toString()
    }

    private fun render(ic: InputConnection, devanagariDigits: Boolean): String {
        previewBuilder.setLength(0)
        PhoneticEngine.transliterateInto(buffer, previewBuilder, devanagariDigits)
        val preview = previewBuilder.toString()
        try {
            ic.setComposingText(preview, 1)
            composingActive = true
        } catch (_: RuntimeException) {
            composingActive = false
        }
        return preview
    }

    /**
     * Ends the composition, replacing it with [text] when provided.
     * Returns the text that was actually committed (may be empty).
     */
    fun commitComposition(ic: InputConnection, text: String?): String {
        val payload = text ?: buffer.toString()
        buffer.setLength(0)
        composingActive = false
        if (payload.isNotEmpty()) {
            try {
                ic.finishComposingText()
                ic.commitText(payload, 1)
            } catch (_: RuntimeException) {
                return payload
            }
        }
        return payload
    }

    // -----------------------------------------------------------------------
    // Literal input (English, Devanagari layouts, digits, emoji)
    // -----------------------------------------------------------------------

    fun commitText(ic: InputConnection, text: String, addSpaceAfter: Boolean = false, devanagariDigits: Boolean = false) {
        if (text.isEmpty()) return
        buffer.setLength(0)
        composingActive = false
        val payload = when {
            devanagariDigits && text.any { it in '0'..'9' } -> Devanagari.digitsToDevanagari(text)
            else -> text
        }
        try {
            if (addSpaceAfter) {
                ic.commitText(payload, 1)
                ic.commitText(" ", 1)
            } else {
                ic.commitText(payload, 1)
            }
        } catch (_: RuntimeException) {
            // Editor went away between keystrokes.
        }
    }

    // -----------------------------------------------------------------------
    // Deletion (grapheme aware)
    // -----------------------------------------------------------------------

    /** Deletes exactly one grapheme cluster. Returns true when something was deleted. */
    fun deleteCluster(ic: InputConnection): Boolean {
        val before = readTextBefore(ic, DELETE_LOOKBACK) ?: return false
        if (before.isEmpty()) return false
        val boundary = Graphemes.prevBoundary(before, before.length)
        val count = before.length - boundary
        if (count <= 0) return false
        return try {
            ic.deleteSurroundingText(count, 0)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * Deletes exactly one grapheme cluster to the right of the caret.
     *
     * The mirror of [deleteCluster]; used by the hardware Delete key. A cluster
     * is measured with the same rules as deletion to the left, so a surrogate
     * pair or a Devanagari conjunct is never cut in half.
     */
    fun deleteClusterForward(ic: InputConnection): Boolean {
        val after = try {
            ic.getTextAfterCursor(DELETE_LOOKBACK, 0)?.toString()
        } catch (_: RuntimeException) {
            null
        } ?: return false
        if (after.isEmpty()) return false
        val boundary = Graphemes.nextBoundary(after, 0)
        val count = if (boundary <= 0) 1 else boundary
        return try {
            ic.deleteSurroundingText(0, count)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * Deletes [count] whole words to the left of the caret, cluster aligned.
     * Returns how many words were actually consumed.
     */
    fun deleteWords(ic: InputConnection, count: Int): Int {
        if (count <= 0) return 0
        val before = readTextBefore(ic, DELETE_WORD_LOOKBACK) ?: return 0
        if (before.isEmpty()) return 0
        var cursor = before.length
        var consumed = 0
        while (consumed < count) {
            val next = Graphemes.prevWordBoundary(before, cursor)
            if (next == cursor) break
            cursor = next
            consumed++
        }
        if (consumed == 0) return 0
        val deleteCount = before.length - cursor
        return try {
            ic.deleteSurroundingText(deleteCount, 0)
            consumed
        } catch (_: RuntimeException) {
            0
        }
    }

    // -----------------------------------------------------------------------
    // Cursor movement (space-bar glide)
    // -----------------------------------------------------------------------

    /**
     * Moves the caret by [characters] positions using absolute offsets derived
     * from [InputConnection.getExtractedText].
     *
     * `getTextBeforeCursor` alone cannot express "move one character right", and
     * `setSelection` needs absolute document coordinates, so the extracted text
     * is the only correct tool here. The request object is reused to avoid
     * allocaring one per gesture step.
     */
    fun moveCursor(ic: InputConnection, characters: Int): Boolean {
        if (characters == 0) return false
        val extracted = try {
            ic.getExtractedText(extractedRequest, 0)
        } catch (_: RuntimeException) {
            null
        } ?: return false
        val text = extracted.text ?: return false
        val startOffset = extracted.startOffset
        val currentStart = extracted.selectionStart
        val currentEnd = extracted.selectionEnd
        if (currentStart < 0 || currentEnd < 0) return false
        if (currentStart != currentEnd) {
            // Collapse a selection to its edge in the direction of travel.
            val collapseTo = if (characters < 0) currentStart else currentEnd
            val target = (startOffset + collapseTo).coerceIn(startOffset, startOffset + text.length)
            return setSelectionSafely(ic, target)
        }
        val absolute = startOffset + currentStart
        val target = (absolute + characters).coerceIn(startOffset, startOffset + text.length)
        if (target == absolute) return false
        return setSelectionSafely(ic, target)
    }

    private fun setSelectionSafely(ic: InputConnection, position: Int): Boolean = try {
        ic.setSelection(position, position)
        true
    } catch (_: RuntimeException) {
        false
    }

    /** Selects the whole document (used by the clipboard panel's "select all"). */
    fun selectAll(ic: InputConnection): Boolean {
        val extracted = try {
            ic.getExtractedText(extractedRequest, 0)
        } catch (_: RuntimeException) {
            null
        } ?: return false
        val text = extracted.text ?: return false
        return setSelectionSafely(ic, extracted.startOffset) &&
            setSelectionSafely(ic, extracted.startOffset + text.length)
    }

    // -----------------------------------------------------------------------
    // Context helpers (capitalization, predictions)
    // -----------------------------------------------------------------------

    /** Last [count] characters before the caret, or null when unavailable. */
    fun readTextBefore(ic: InputConnection, count: Int): String? = try {
        ic.getTextBeforeCursor(count, 0)?.toString()
    } catch (_: RuntimeException) {
        null
    }

    /**
     * True when the caret sits at a sentence start, which is what drives
     * auto-capitalization and the transient [ShiftState.AUTO].
     */
    fun isAtSentenceStart(ic: InputConnection): Boolean {
        val before = readTextBefore(ic, CAPITALIZATION_LOOKBACK) ?: return false
        if (before.isEmpty()) return true
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) {
            // Only whitespace typed so far (e.g. after pressing space at the very
            // beginning): keep the sentence-start state across newlines only.
            for (index in before.indices.reversed()) {
                if (before[index] == '\n') return true
            }
            return true
        }
        val last = trimmed[trimmed.length - 1]
        return last == '.' || last == '!' || last == '?' || last == '\n' ||
            last == Devanagari.DANDA || last == Devanagari.DOUBLE_DANDA ||
            last == '\u2026'
    }

    /** The word immediately left of the caret, used for bigram prediction. */
    /**
     * Trailing words of the line the caret is on, nearest first.
     *
     * Used to seed next-word prediction with real context. One read, then pure
     * in-memory work, so it costs a single `getTextBeforeCursor` on the main
     * thread and never touches the editor again.
     */
    fun wordsOnCurrentLine(ic: InputConnection, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val before = readTextBefore(ic, CONTEXT_LINE_LOOKBACK) ?: return emptyList()
        if (before.isEmpty()) return emptyList()
        var end = before.length
        while (end > 0) {
            val previous = before[end - 1]
            if (previous == '\n' || previous == '\r' || previous == Devanagari.DANDA) break
            end--
        }
        if (end >= before.length) return emptyList()
        val words = ArrayList<String>(limit)
        var cursor = before.length
        while (cursor > end && words.size < limit) {
            while (cursor > end && before[cursor - 1].isWhitespace()) cursor--
            if (cursor <= end) break
            val start = Graphemes.prevWordBoundary(before, cursor)
            if (start >= cursor) break
            words.add(before.substring(start, cursor))
            cursor = start
        }
        return words
    }

    fun previousWord(ic: InputConnection): String? {
        val before = readTextBefore(ic, PREVIOUS_WORD_LOOKBACK) ?: return null
        return trailingWord(before)
    }

    /**
     * The last word of [text], or null when there is none.
     *
     * Pure: no editor access, so callers that already hold a string (for example
     * after stripping their own composing region off the tail) can reuse the
     * exact same word-boundary rules.
     */
    fun trailingWord(text: String): String? {
        if (text.isEmpty()) return null
        var end = text.length
        // Skip trailing whitespace and punctuation.
        while (end > 0 && !Devanagari.isWordChar(text[end - 1])) end--
        if (end == 0) return null
        val start = Graphemes.prevWordBoundary(text, end)
        if (start >= end) return null
        return text.substring(start, end)
    }

    /** Clears the roman buffer without touching the editor's composed text. */
    fun clearBuffer() {
        buffer.setLength(0)
        composingActive = false
    }

    private companion object {
        const val INITIAL_BUFFER = 64
        const val MAX_BUFFER = 128
        const val DELETE_LOOKBACK = 32
        const val DELETE_WORD_LOOKBACK = 160
        const val CAPITALIZATION_LOOKBACK = 16
        const val CONTEXT_LINE_LOOKBACK = 240
        const val PREVIOUS_WORD_LOOKBACK = 64
        const val EXTRACT_HINT_CHARS = 512
    }
}
