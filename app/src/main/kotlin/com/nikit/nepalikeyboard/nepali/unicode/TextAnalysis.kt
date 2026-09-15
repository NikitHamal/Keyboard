package com.nikit.nepalikeyboard.nepali.unicode

/**
 * Pure, allocation-free text analysis used by the input layer.
 *
 * Every function here is a deliberate counterpart to something
 * `InputConnection` does *not* give us. The framework can report
 * `getTextBeforeCursor(n)` but it has no notion of grapheme clusters, no notion
 * of Devanagari aksharas, and no notion of "the word the cursor is inside".
 * Getting these wrong is the difference between a keyboard that feels correct
 * and one that visibly mangles text.
 */
object TextAnalysis {

    // ------------------------------------------------------------------
    // Word characters
    // ------------------------------------------------------------------

    /**
     * True when [c] is a character that participates in a word.
     *
     * Deliberately *not* `Character.isLetterOrDigit` alone: that returns true
     * for the Devanagari combining marks too (they are letters), which is fine,
     * but it excludes the apostrophe, which we want inside words so that
     * "don't" is one token and — more importantly for Nepali — so that the
     * transliteration engine can treat `'` as a syllable separator without
     * word-splitting.
     */
    fun isWordChar(c: Char): Boolean = when {
        c.isLetterOrDigit() -> true
        // Devanagari structural signs belong to the word.
        Devanagari.isTerminalSign(c) -> true
        Devanagari.isVirama(c) -> true
        // Explicit joiners are part of the word.
        c == '\u200D' || c == '\u200C' -> true
        // Apostrophes and the Devanagari avagraha keep contractions together.
        c == '\'' || c == '\u2019' || c == '\u093D' -> true
        else -> false
    }

    /** True when [c] is a CJK ideograph or kana, which have no space-delimited words. */
    fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0xF900..0xFAFF) ||
            (code in 0x3040..0x30FF) ||
            (code in 0xAC00..0xD7AF)
    }

    // ------------------------------------------------------------------
    // Word boundary scanning
    // ------------------------------------------------------------------

    /**
     * Return the UTF-16 index at which the word immediately before [cursor]
     * begins.
     *
     * Skips trailing non-word characters first (so pressing delete after
     * "word, " removes the comma and space, then the word). Returns `start`
     * when there is nothing to delete.
     */
    fun wordStartBefore(text: CharSequence, cursor: Int, start: Int = 0): Int {
        if (cursor <= start) return start

        var i = cursor
        // Phase 1: consume trailing separators.
        while (i > start && !isWordChar(text[i - 1]) && !isCjk(text[i - 1])) {
            i--
        }
        // Phase 2: consume the word itself.
        if (i > start && isCjk(text[i - 1])) {
            // CJK: delete exactly one ideograph.
            return i - 1
        }
        while (i > start && isWordChar(text[i - 1])) {
            i--
        }
        return i
    }

    /**
     * Return the UTF-16 index at which the word immediately after [cursor]
     * ends. Skips leading separators, then consumes the word. Returns `end`
     * when there is nothing to delete.
     */
    fun wordEndAfter(text: CharSequence, cursor: Int, end: Int = text.length): Int {
        if (cursor >= end) return end

        var i = cursor
        while (i < end && !isWordChar(text[i]) && !isCjk(text[i])) {
            i++
        }
        if (i < end && isCjk(text[i])) {
            return i + 1
        }
        while (i < end && isWordChar(text[i])) {
            i++
        }
        return i
    }

    /**
     * The word the cursor currently sits inside or immediately after, as a
     * substring. Returns the empty string when there is none. Used by the
     * suggestion engine to look up the partially-typed word.
     *
     * This *does* allocate — callers must be off the keystroke path, or must
     * tolerate one small allocation per word boundary change.
     */
    fun currentWordBefore(text: CharSequence, cursor: Int, start: Int = 0): String {
        val s = wordStartBefore(text, cursor, start)
        if (s >= cursor) return ""
        val sb = StringBuilder(cursor - s)
        for (i in s until cursor) sb.append(text[i])
        return sb.toString()
    }

    /** Length in code units of the word preceding the cursor. */
    fun currentWordLengthBefore(text: CharSequence, cursor: Int, start: Int = 0): Int =
        cursor - wordStartBefore(text, cursor, start)

    // ------------------------------------------------------------------
    // Sentence / capitalisation state
    // ------------------------------------------------------------------

    /**
     * Decide whether the next character typed should be auto-capitalised.
     *
     * The rule is deliberately conservative. We capitalise when, ignoring
     * whitespace and opening punctuation, the last meaningful character was a
     * sentence terminator, or the cursor is at the very start of the field.
     *
     * We do **not** capitalise after a colon, after a comma, in the middle of
     * a word, or when the preceding token is a known abbreviation. The
     * abbreviation check is intentionally narrow: only "e.g.", "i.e.", "etc.",
     * and "vs." appear often enough in chat-style text to matter, and a false
     * positive here is only a minor annoyance, not a correctness bug.
     */
    fun shouldCapitalise(text: CharSequence, cursor: Int): Boolean {
        if (cursor <= 0) return true

        // Walk backwards over whitespace and opening delimiters.
        var i = cursor
        while (i > 0) {
            val c = text[i - 1]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i--
                continue
            }
            if (c == '"' || c == '\'' || c == '\u201C' || c == '\u2018' ||
                c == '(' || c == '[' || c == '{' || c == '\u2014' || c == '-'
            ) {
                i--
                continue
            }
            break
        }

        if (i <= 0) return true

        val last = text[i - 1]

        // A Devanagari danda terminates a sentence just like a full stop.
        if (last == Devanagari.DANDA || last == Devanagari.DOUBLE_DANDA) return true
        if (last != '.' && last != '!' && last != '?') return false

        // Guard against decimals: "3." followed by a digit is not a sentence.
        if (last == '.' && i >= 2 && text[i - 2].isDigit()) {
            // Only a terminator if what follows (if anything) is not a digit.
            if (i < text.length && text[i].isDigit()) return false
        }

        // Guard against common abbreviations.
        val wordStart = wordStartBefore(text, i - 1, 0)
        if (i - 1 - wordStart in 2..6) {
            val token = StringBuilder(8)
            for (k in wordStart until i) token.append(text[k].lowercaseChar())
            when (token.toString()) {
                "e.g", "i.e", "etc", "vs", "mr", "mrs", "dr", "no" -> return false
            }
        }

        return true
    }

    /**
     * True when the text ends with a sentence terminator followed only by
     * spaces, which is the condition for the "space becomes full stop"
     * behaviour in a language where it is enabled.
     */
    fun endsWithSentence(text: CharSequence, cursor: Int): Boolean {
        var i = cursor
        while (i > 0 && text[i - 1] == ' ') i--
        if (i <= 0) return false
        return Devanagari.isSentenceTerminator(text[i - 1]) ||
            text[i - 1] == '.' || text[i - 1] == '!' || text[i - 1] == '?'
    }

    // ------------------------------------------------------------------
    // Word counting (for the "delete N words" gesture)
    // ------------------------------------------------------------------

    /**
     * Walk backwards over [wordCount] words and return the UTF-16 index where
     * deletion should stop.
     *
     * Used by the swipe-to-delete gesture: the further the finger travels, the
     * more words are consumed. The scan is bounded so that a long drag cannot
     * walk the entire field, which would be both slow and surprising.
     */
    fun wordStartBeforeN(
        text: CharSequence,
        cursor: Int,
        wordCount: Int,
        start: Int = 0
    ): Int {
        if (wordCount <= 0 || cursor <= start) return cursor
        var i = cursor
        var remaining = wordCount
        while (remaining > 0 && i > start) {
            val next = wordStartBefore(text, i, start)
            if (next >= i) {
                // No progress possible (e.g. cursor is already at `start`).
                return next
            }
            i = next
            remaining--
        }
        return i
    }

    // ------------------------------------------------------------------
    // Script detection
    // ------------------------------------------------------------------

    /**
     * True when [text] contains at least one Devanagari code point. Cheap
     * because it short-circuits on the first hit.
     */
    fun containsDevanagari(text: CharSequence): Boolean {
        for (i in text.indices) {
            if (Devanagari.isDevanagariBlock(text[i])) return true
        }
        return false
    }

    /**
     * True when every letter in [text] is ASCII. Used to decide whether the
     * Romanized engine should stay engaged or hand over to the native layout.
     */
    fun isAllAsciiLetters(text: CharSequence): Boolean {
        for (i in text.indices) {
            val c = text[i]
            if (c in '\u0080'..'\uFFFF') return false
        }
        return true
    }

    /**
     * Trim leading and trailing whitespace without allocating when there is
     * nothing to trim. Returns the *same instance* in that case, which is what
     * makes it usable on the hot path.
     */
    fun trim(text: String): String {
        var s = 0
        var e = text.length
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return if (s == 0 && e == text.length) text else text.substring(s, e)
    }
}
