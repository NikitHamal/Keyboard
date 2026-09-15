package com.nepali.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.nepali.keyboard.engine.GraphemeUtils
import kotlinx.coroutines.flow.MutableStateFlow

enum class KeyboardMode {
    ROMANIZED,
    NEPALI_NATIVE,
    ENGLISH_QWERTY,
    SYMBOLS,
    NUMBERS
}

class ImeInputController {

    private var inputConnection: InputConnection? = null
    private var currentEditorInfo: EditorInfo? = null

    val composingText = MutableStateFlow("")
    val isPasswordField = MutableStateFlow(false)
    val imeAction = MutableStateFlow(EditorInfo.IME_ACTION_DONE)

    fun onStartInput(ic: InputConnection?, editorInfo: EditorInfo?) {
        this.inputConnection = ic
        this.currentEditorInfo = editorInfo
        this.composingText.value = ""

        if (editorInfo != null) {
            val inputType = editorInfo.inputType
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val isPassword = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                    (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
                    (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
                    (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)

            this.isPasswordField.value = isPassword
            this.imeAction.value = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        } else {
            this.isPasswordField.value = false
            this.imeAction.value = EditorInfo.IME_ACTION_DONE
        }
    }

    fun onFinishInput() {
        finishComposing()
        this.inputConnection = null
        this.currentEditorInfo = null
    }

    /**
     * Handles keypress or character input.
     */
    fun handleTextInsert(text: String, isPhoneticMode: Boolean) {
        val ic = inputConnection ?: return
        if (isPasswordField.value || !isPhoneticMode) {
            finishComposing()
            ic.commitText(text, 1)
            return
        }

        // Phonetic composing buffer update
        val newComposing = composingText.value + text
        composingText.value = newComposing
        ic.setComposingText(newComposing, 1)
    }

    /**
     * Commits selected suggestion candidate text.
     */
    fun commitCandidate(candidate: String) {
        val ic = inputConnection ?: return
        ic.commitText(candidate, 1)
        composingText.value = ""
    }

    /**
     * Finishes active composing span without adding extra text.
     */
    fun finishComposing() {
        val ic = inputConnection ?: return
        if (composingText.value.isNotEmpty()) {
            ic.finishComposingText()
            composingText.value = ""
        }
    }

    /**
     * Handles backspace action, taking grapheme clusters into account.
     */
    fun handleBackspace() {
        val ic = inputConnection ?: return
        if (composingText.value.isNotEmpty()) {
            val newComposing = GraphemeUtils.deleteLastGrapheme(composingText.value)
            composingText.value = newComposing
            if (newComposing.isEmpty()) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
            } else {
                ic.setComposingText(newComposing, 1)
            }
        } else {
            val beforeText = ic.getTextBeforeCursor(10, 0)
            if (!beforeText.isNullOrEmpty()) {
                val prevOffset = GraphemeUtils.getPreviousGraphemeOffset(beforeText, beforeText.length)
                val charsToDelete = beforeText.length - prevOffset
                ic.deleteSurroundingText(charsToDelete, 0)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }
    }

    /**
     * Swipe backspace: Deletes previous word or segment incrementally.
     */
    fun handleSwipeDeleteWord() {
        val ic = inputConnection ?: return
        if (composingText.value.isNotEmpty()) {
            composingText.value = ""
            ic.setComposingText("", 1)
            ic.finishComposingText()
            return
        }
        val beforeText = ic.getTextBeforeCursor(30, 0)
        if (!beforeText.isNullOrEmpty()) {
            val trimmed = beforeText.trimEnd()
            val lastSpace = trimmed.lastIndexOf(' ')
            val deleteCount = if (lastSpace >= 0) {
                beforeText.length - lastSpace
            } else {
                beforeText.length
            }
            ic.deleteSurroundingText(deleteCount, 0)
        }
    }

    /**
     * Spacebar drag cursor control: moves cursor left/right by delta steps safely using ExtractedText.
     */
    fun moveCursorBy(delta: Int) {
        val ic = inputConnection ?: return
        if (delta == 0) return
        finishComposing()

        val extractedText = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (extractedText != null) {
            val currentSel = extractedText.selectionStart
            val text = extractedText.text ?: ""
            if (delta < 0) {
                var target = currentSel
                for (i in 0 until -delta) {
                    target = GraphemeUtils.getPreviousGraphemeOffset(text, target)
                }
                val newSel = target.coerceAtLeast(0)
                ic.setSelection(newSel, newSel)
            } else {
                var target = currentSel
                for (i in 0 until delta) {
                    target = GraphemeUtils.getNextGraphemeOffset(text, target)
                }
                val newSel = target.coerceAtMost(text.length)
                ic.setSelection(newSel, newSel)
            }
        } else {
            // Fallback if getExtractedText returns null
            val beforeText = ic.getTextBeforeCursor(100, 0) ?: ""
            val afterText = ic.getTextAfterCursor(100, 0) ?: ""
            val currentOffset = beforeText.length
            if (delta < 0) {
                var targetOffset = currentOffset
                for (i in 0 until -delta) {
                    targetOffset = GraphemeUtils.getPreviousGraphemeOffset(beforeText, targetOffset)
                }
                val newSelection = targetOffset.coerceAtLeast(0)
                ic.setSelection(newSelection, newSelection)
            } else {
                val fullText = beforeText.toString() + afterText.toString()
                var targetOffset = currentOffset
                for (i in 0 until delta) {
                    targetOffset = GraphemeUtils.getNextGraphemeOffset(fullText, targetOffset)
                }
                val newSelection = targetOffset.coerceAtMost(fullText.length)
                ic.setSelection(newSelection, newSelection)
            }
        }
    }

    fun performImeAction() {
        val ic = inputConnection ?: return
        finishComposing()
        val action = imeAction.value
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    fun insertSpace() {
        val ic = inputConnection ?: return
        if (composingText.value.isNotEmpty()) {
            finishComposing()
        }
        ic.commitText(" ", 1)
    }
}
