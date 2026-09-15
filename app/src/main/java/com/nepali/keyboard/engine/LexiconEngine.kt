package com.nepali.keyboard.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Compact Trie node for storing Nepali lexical words and frequencies.
 */
private class TrieNode {
    val children = mutableMapOf<Char, TrieNode>()
    var frequency: Int = 0
    var isWord: Boolean = false
}

/**
 * Radix Tree / Compact Trie implementation for fast prefix matching and scoring.
 */
class RadixTrie {
    private val root = TrieNode()

    fun insert(word: String, frequency: Int) {
        var current = root
        for (ch in word) {
            current = current.children.getOrPut(ch) { TrieNode() }
        }
        current.isWord = true
        current.frequency = frequency
    }

    fun searchPrefix(prefix: String, limit: Int = 5): List<Pair<String, Int>> {
        var current = root
        for (ch in prefix) {
            val child = current.children[ch] ?: return emptyList()
            current = child
        }
        val results = mutableListOf<Pair<String, Int>>()
        collectWords(current, StringBuilder(prefix), results)
        return results.sortedByDescending { it.second }.take(limit)
    }

    fun allWords(): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()
        collectWords(root, StringBuilder(), results)
        return results
    }

    private fun collectWords(node: TrieNode, currentWord: StringBuilder, results: MutableList<Pair<String, Int>>) {
        if (node.isWord) {
            results.add(Pair(currentWord.toString(), node.frequency))
        }
        for ((ch, child) in node.children) {
            currentWord.append(ch)
            collectWords(child, currentWord, results)
            currentWord.deleteCharAt(currentWord.length - 1)
        }
    }
}

/** Bounded Levenshtein distance over grapheme-safe char sequences. */
internal fun levenshteinWithin(a: String, b: String, maxDistance: Int): Int {
    if (a == b) return 0
    if (kotlin.math.abs(a.length - b.length) > maxDistance) return maxDistance + 1
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        var rowMin = curr[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            if (curr[j] < rowMin) rowMin = curr[j]
        }
        if (rowMin > maxDistance) return maxDistance + 1
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[b.length]
}

/**
 * Lexicon engine providing statistical ranking and candidates asynchronously.
 *
 * Candidate order per keystroke: (1) literal Romanized input, (2) top
 * phonetic transliteration, (3) trie prefix completions, (4) fuzzy corrections
 * within edit distance 2 for residual short/long-vowel ambiguity
 * (nepal -> नेपाल, timi -> तिमी). Bigram scores boost, never reorder, the
 * literal and phonetic heads.
 */
class LexiconEngine(private val context: Context) {

    private val trie = RadixTrie()
    private val bigrams = mutableMapOf<String, MutableMap<String, Int>>()
    private var isLoaded = false

    suspend fun loadLexicon() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext
        try {
            val jsonString = context.assets.open("nepali_lexicon.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val wordsArray = jsonObject.optJSONArray("words")
            if (wordsArray != null) {
                for (i in 0 until wordsArray.length()) {
                    val obj = wordsArray.getJSONObject(i)
                    val word = obj.getString("word")
                    val freq = obj.getInt("freq")
                    trie.insert(word, freq)
                }
            }

            val bigramArray = jsonObject.optJSONArray("bigrams")
            if (bigramArray != null) {
                for (i in 0 until bigramArray.length()) {
                    val obj = bigramArray.getJSONObject(i)
                    val w1 = obj.getString("w1")
                    val w2 = obj.getString("w2")
                    val freq = obj.getInt("freq")
                    bigrams.getOrPut(w1) { mutableMapOf() }[w2] = freq
                }
            }
            isLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getCandidates(
        literalInput: String,
        previousWord: String? = null
    ): List<String> = withContext(Dispatchers.Default) {
        if (literalInput.isBlank()) return@withContext emptyList()

        val candidates = mutableListOf<String>()

        // 1. Literal Romanized input
        candidates.add(literalInput)

        // 2. Phonetic Transliteration
        val phoneticMatches = PhoneticEngine.getTransliterationCandidates(literalInput)
        for (match in phoneticMatches) {
            if (!candidates.contains(match)) {
                candidates.add(match)
            }
        }

        // 3. Trie prefix completions on the top phonetic form.
        val topPhonetic = phoneticMatches.firstOrNull() ?: ""
        if (topPhonetic.isNotEmpty()) {
            val trieMatches = trie.searchPrefix(topPhonetic, limit = 5)
            for ((word, _) in trieMatches) {
                if (!candidates.contains(word)) {
                    candidates.add(word)
                }
            }

            // 4. Fuzzy corrections for short/long-vowel ambiguity.
            val fuzzy = trie.allWords()
                .filter { (word, _) -> !candidates.contains(word) }
                .map { (word, freq) -> Triple(word, freq, levenshteinWithin(topPhonetic, word, 2)) }
                .filter { it.third in 1..2 }
                .sortedWith(compareBy({ it.third }, { -it.second }))
                .take(3)
            for ((word, _, _) in fuzzy) {
                candidates.add(word)
            }
        }

        // Bigram boost applies only to the lexical tail, preserving the
        // literal and phonetic heads.
        val nextWordMap = previousWord?.let { bigrams[it] } ?: emptyMap()
        if (nextWordMap.isNotEmpty() && candidates.size > 2) {
            val head = candidates.take(2)
            val tail = candidates.drop(2).sortedByDescending { nextWordMap[it] ?: 0 }
            candidates.clear()
            candidates.addAll(head)
            candidates.addAll(tail)
        }

        return@withContext candidates
    }
}
