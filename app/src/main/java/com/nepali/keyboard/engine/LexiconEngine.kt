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
        collectWords(current, StringBuilder(prefix), results, limit)
        return results.sortedByDescending { it.second }
    }

    private fun collectWords(node: TrieNode, currentWord: StringBuilder, results: MutableList<Pair<String, Int>>, limit: Int) {
        if (node.isWord) {
            results.add(Pair(currentWord.toString(), node.frequency))
        }
        for ((ch, child) in node.children) {
            currentWord.append(ch)
            collectWords(child, currentWord, results, limit)
            currentWord.deleteCharAt(currentWord.length - 1)
        }
    }
}

/**
 * Lexicon engine providing statistical ranking and candidates asynchronously.
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

        // 3. Trie / Bigram statistical lookups
        val topPhonetic = phoneticMatches.firstOrNull() ?: ""
        if (topPhonetic.isNotEmpty()) {
            val trieMatches = trie.searchPrefix(topPhonetic, limit = 5)
            for ((word, _) in trieMatches) {
                if (!candidates.contains(word)) {
                    candidates.add(word)
                }
            }
        }

        // Apply bigram boost if previous word exists
        if (previousWord != null && bigrams.containsKey(previousWord)) {
            val nextWordMap = bigrams[previousWord] ?: emptyMap()
            candidates.sortByDescending { word ->
                nextWordMap[word] ?: 0
            }
        }

        return@withContext candidates
    }
}
