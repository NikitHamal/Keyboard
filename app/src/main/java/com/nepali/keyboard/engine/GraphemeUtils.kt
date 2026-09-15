package com.nepali.keyboard.engine

import java.text.BreakIterator
import java.util.Locale

/**
 * Unicode grapheme-cluster aware text editing utilities for Devanagari and Latin text.
 * Prevents splitting UTF-16 surrogate pairs and Devanagari combining characters (matras, halant, etc.).
 */
object GraphemeUtils {

    /**
     * Finds the offset of the previous grapheme cluster before [offset] in [text].
     */
    fun getPreviousGraphemeOffset(text: CharSequence, offset: Int): Int {
        if (offset <= 0 || text.isEmpty()) return 0
        val boundedOffset = offset.coerceAtMost(text.length)
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text.toString())
        val prev = iterator.preceding(boundedOffset)
        return if (prev == BreakIterator.DONE) 0 else prev
    }

    /**
     * Finds the offset of the next grapheme cluster after [offset] in [text].
     */
    fun getNextGraphemeOffset(text: CharSequence, offset: Int): Int {
        if (offset >= text.length || text.isEmpty()) return text.length
        val boundedOffset = offset.coerceAtLeast(0)
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text.toString())
        val next = iterator.following(boundedOffset)
        return if (next == BreakIterator.DONE) text.length else next
    }

    /**
     * Deletes the last grapheme cluster from [text].
     */
    fun deleteLastGrapheme(text: String): String {
        if (text.isEmpty()) return ""
        val prevOffset = getPreviousGraphemeOffset(text, text.length)
        return text.substring(0, prevOffset)
    }

    /**
     * Counts total grapheme clusters in [text].
     */
    fun getGraphemeCount(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text.toString())
        var count = 0
        while (iterator.next() != BreakIterator.DONE) {
            count++
        }
        return count
    }
}
