/*
 * Copyright (C) 2026 Nikit Hamal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nikit.nepalikeyboard.ime.text.composing

import com.nikit.nepalikeyboard.nepali.lexicon.LexiconRepository
import com.nikit.nepalikeyboard.nepali.translit.RomanizedEngine
import com.nikit.nepalikeyboard.nepali.translit.TransliterationRules
import com.nikit.nepalikeyboard.nepali.unicode.Devanagari
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phonetic transliteration composer for Nepali (Romanized).
 *
 * Maintains the active Romanized input buffer directly during typing so that
 * inherent vowels (such as 'a' in "nepal") are perfectly preserved without
 * relying on lossy reverse-transliteration from Devanagari.
 */
@Serializable
@SerialName("nepali-romanized")
object NepaliRomanized : Composer {
    override val id = "nepali-romanized"
    override val label = "Nepali Romanized"

    override val toRead = 32

    private val composingBuffer = StringBuilder()

    /** The exact Romanized string typed by the user for the active composing word. */
    val currentRoman: String
        @Synchronized get() = composingBuffer.toString()

    @Synchronized
    fun clearComposing() {
        composingBuffer.setLength(0)
    }

    @Synchronized
    fun onBackspace(): Boolean {
        if (composingBuffer.isNotEmpty()) {
            composingBuffer.deleteCharAt(composingBuffer.length - 1)
            return true
        }
        return false
    }

    @Synchronized
    override fun getActions(precedingText: String, toInsert: String): Pair<Int, String> {
        if (toInsert.length != 1 || !isAsciiLetter(toInsert[0])) {
            clearComposing()
            return 0 to toInsert
        }

        val boundary = wordStart(precedingText)
        val wordLen = precedingText.length - boundary

        // If editor has no previous word characters, restart composing buffer
        if (wordLen == 0) {
            composingBuffer.setLength(0)
        }

        composingBuffer.append(toInsert)
        val roman = composingBuffer.toString()

        // Check exact matches / chat aliases / compounding first (e.g. nepal -> नेपाल, xa -> छ, hunxa -> हुन्छ)
        val exactMatch = LexiconRepository.get().getExactMatch(roman)
        val newDevanagari = exactMatch ?: RomanizedEngine.transliterate(roman, isComplete = false).devanagari
        return wordLen to newDevanagari
    }

    /**
     * Re-renders the trailing word of [composingText] as a finished word.
     *
     * Called when the word is committed (space, enter, punctuation): resolves
     * exact lexicon matches first, then falls back to schwa-resolved transliteration.
     */
    fun finalizeWord(composingText: String): String {
        val boundary = wordStart(composingText)
        if (boundary == composingText.length) {
            clearComposing()
            return composingText
        }
        val roman = synchronized(this) {
            if (composingBuffer.isNotEmpty()) composingBuffer.toString()
            else reverseTransliterate(composingText.substring(boundary))
        }
        if (roman.isEmpty()) {
            clearComposing()
            return composingText
        }
        val exactMatch = LexiconRepository.get().getExactMatch(roman)
        val resolved = exactMatch ?: RomanizedEngine.transliterate(roman, isComplete = true).devanagari
        clearComposing()
        return composingText.substring(0, boundary) + resolved
    }

    /**
     * Maps Devanagari text back to its canonical Romanized form.
     *
     * Greedy longest-match over the reversed rule table; characters with no
     * rule (spaces, punctuation, digits, emoji) pass through unchanged.
     */
    fun reverseTransliterate(devanagari: String): String {
        if (devanagari.isEmpty()) return ""
        val out = StringBuilder(devanagari.length * 2)
        var i = 0
        while (i < devanagari.length) {
            val c = devanagari[i]
            // Never leak raw virama combining mark into Roman string
            if (c == Devanagari.VIRAMA) {
                i++
                continue
            }
            var matched: Pair<String, String>? = null
            for (entry in REVERSE_RULES) {
                if (devanagari.startsWith(entry.first, i)) {
                    matched = entry
                    break
                }
            }
            if (matched != null) {
                out.append(matched.second)
                i += matched.first.length
            } else {
                if (isAsciiLetter(c) || c.isWhitespace() || c.isDigit()) {
                    out.append(c)
                }
                i++
            }
        }
        return out.toString()
    }

    /** True for the Devanagari block: letters, matras, signs, digits. */
    fun isDevanagariWordChar(c: Char): Boolean = c in '\u0900'..'\u097F'

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /** Index where the trailing Devanagari word run starts. */
    private fun wordStart(text: String): Int {
        var i = text.length
        while (i > 0 && isDevanagariWordChar(text[i - 1])) i--
        return i
    }

    /**
     * The reversed rule table: Devanagari target → canonical Romanized source,
     * longest target first so the greedy scan prefers the longest match.
     */
    private val REVERSE_RULES: List<Pair<String, String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val seen = HashSet<String>()
        buildList {
            for (rule in TransliterationRules.ALL_RULES) {
                if (rule.target.isNotEmpty() && seen.add(rule.target)) {
                    add(rule.target to rule.source)
                }
            }
        }.sortedByDescending { it.first.length }
    }
}
