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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Composer for the Nepali (Romanized) subtype: Latin keystrokes are
 * transliterated into Devanagari as the user types.
 *
 * Follows the same contract as [WithRules] (Telex) and the Hangul/Kana
 * composers: [getActions] receives the field text before the cursor plus the
 * new character and returns how many chars to replace plus the replacement.
 *
 * ### Statelessness
 *
 * The composer keeps no buffer. Every keystroke recomputes the whole current
 * word from scratch: the trailing Devanagari run is reverse-mapped to its
 * canonical Romanized form, the new letter is appended, and the result is
 * transliterated forward. Recomputing wholesale is what makes backspace,
 * cursor moves, and mid-word edits correct without any resync logic — there
 * is no state to go stale.
 *
 * The round trip relies on [reverseTransliterate] being a right inverse of
 * the forward engine for text the forward engine produced. It is built from
 * the same rule table, so in practice it is; any transient mismatch
 * self-heals on the next keystroke because the computation restarts from the
 * actual field text.
 *
 * ### Word boundaries
 *
 * Only the trailing run of Devanagari word characters (U+0900–U+097F) is
 * retransliterated; everything before it is passed through byte-identical.
 * Rewriting previously committed words would be a data-loss bug, so the head
 * is never touched.
 *
 * Non-letters (space, punctuation, digits) pass straight through, which ends
 * the transliteration run exactly where the word ends.
 */
@Serializable
@SerialName("nepali-romanized")
object NepaliRomanized : Composer {
    override val id = "nepali-romanized"
    override val label = "Nepali Romanized"

    /**
     * How many chars of field text the composer may look at.
     *
     * 32 covers the longest realistic Nepali word with room to spare, while
     * keeping the per-keystroke reverse+forward scan trivially cheap.
     */
    override val toRead = 32

    override fun getActions(precedingText: String, toInsert: String): Pair<Int, String> {
        if (toInsert.length != 1 || !isAsciiLetter(toInsert[0])) {
            return 0 to toInsert
        }
        val boundary = wordStart(precedingText)
        val wordLen = precedingText.length - boundary
        val roman = reverseTransliterate(precedingText.substring(boundary)) + toInsert
        val newDevanagari = RomanizedEngine.transliterate(roman, isComplete = false).devanagari
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
        if (boundary == composingText.length) return composingText
        val roman = reverseTransliterate(composingText.substring(boundary))
        if (roman.isEmpty()) return composingText
        val exactMatch = LexiconRepository.get().getExactMatch(roman)
        val resolved = exactMatch ?: RomanizedEngine.transliterate(roman, isComplete = true).devanagari
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
                out.append(devanagari[i])
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
     *
     * First rule wins per target in [TransliterationRules.ALL_RULES] order,
     * which prefers the primary (usually lowercase) spelling — the one the
     * forward engine itself would consume.
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
