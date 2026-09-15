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

package com.nikit.nepalikeyboard.ime.nlp.nepali

import android.content.Context
import com.nikit.nepalikeyboard.ime.core.Subtype
import com.nikit.nepalikeyboard.ime.editor.EditorContent
import com.nikit.nepalikeyboard.ime.nlp.SuggestionCandidate
import com.nikit.nepalikeyboard.ime.nlp.SuggestionProvider
import com.nikit.nepalikeyboard.ime.nlp.WordSuggestionCandidate
import com.nikit.nepalikeyboard.ime.text.composing.NepaliRomanized
import com.nikit.nepalikeyboard.nepali.lexicon.LexiconRepository

/**
 * Suggestion provider for the Nepali (Romanized) subtype.
 *
 * The composing text at this point is Devanagari (the [NepaliRomanized]
 * composer already transliterated the keystrokes), so the provider
 * reverse-maps the trailing word back to its Romanized form and queries the
 * offline [LexiconRepository] (radix trie over the bundled `ne_lexicon.json`
 * plus in-memory learned words) for completions.
 *
 * Returned candidates carry the Devanagari word as [WordSuggestionCandidate.text]
 * with the Romanized input as the gloss. Nothing is ever auto-committed:
 * transliteration is deterministic but Devanagari orthography is not, so the
 * user always picks.
 */
class NepaliLexiconProvider(context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "com.nikit.nepalikeyboard.nlp.providers.nepali.lexicon"
    }

    private val appContext = context.applicationContext
    private val repository = LexiconRepository.get()

    override val providerId = ProviderId

    override suspend fun create() {
        // Nothing one-time to set up; the lexicon loads per-subtype below.
    }

    override suspend fun preload(subtype: Subtype) {
        repository.ensureLoaded(appContext)
    }

    override suspend fun destroy() {
        // The repository is a shared singleton with no native bindings.
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val composing = content.composingText
        if (composing.isBlank()) return emptyList()
        var i = composing.length
        while (i > 0 && NepaliRomanized.isDevanagariWordChar(composing[i - 1])) i--
        if (i == composing.length) return emptyList()
        val roman = NepaliRomanized.reverseTransliterate(composing.substring(i))
        if (roman.isBlank()) return emptyList()
        repository.ensureLoaded(appContext)
        return repository.suggest(roman, limit = maxCandidateCount).mapIndexed { index, suggestion ->
            WordSuggestionCandidate(
                text = suggestion.text,
                secondaryText = if (suggestion.isLiteral) null else roman,
                confidence = 1.0 / (1.0 + index),
                isEligibleForAutoCommit = false,
                isEligibleForUserRemoval = true,
                sourceProvider = this,
            )
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // An explicit tap is user-directed learning, including in private
        // sessions: nothing is trained speculatively, only accepted words.
        repository.rememberWord(candidate.text.toString())
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // No un-learning: a reverted auto-commit was never ours to begin with,
        // because nothing here is eligible for auto-commit.
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        // User-removal of learned words is not offered in v1.
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        // Feeds glide typing, which is meaningfully English/Latin-only: gliding
        // over a QWERTY grid cannot produce Romanized Nepali. Empty disables it
        // for this subtype instead of offering Latin words mid-transliteration.
        return emptyList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        // Same glide interop as above: no frequency model outside the trie.
        return 0.0
    }
}
