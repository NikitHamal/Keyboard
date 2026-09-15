package np.com.nepalikeyboard.engine

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.com.nepalikeyboard.util.KeyboardLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One bundled dictionary entry. */
@Immutable
class LexiconEntry(
    val word: String,
    val roman: String,
    val frequency: Int,
    val altRoman: List<String> = emptyList(),
)

/**
 * Fully offline lexical model.
 *
 * Structure:
 *  - `entries`: the word list, indexed by payload id;
 *  - `romanTrie`: radix tree over roman keys  -> payload id (typing path);
 *  - `devanagariTrie`: radix tree over the words -> payload id (native path);
 *  - `frequencyIndex`: word -> frequency for O(1) scoring of arbitrary strings;
 *  - bigrams are held by [BiGramModel] in the same load pass.
 *
 * All four structures are built once, on `Dispatchers.Default`, and are
 * immutable afterwards; the candidate worker reads them without locking.
 */
class Lexicon private constructor(
    private val entries: Array<LexiconEntry>,
    private val romanTrie: CompactTrie,
    private val devanagariTrie: CompactTrie,
    private val frequencyIndex: HashMap<String, Int>,
    val bigrams: BiGramModel,
) {

    val size: Int get() = entries.size

    fun entryAt(index: Int): LexiconEntry = entries[index]

    // -----------------------------------------------------------------------
    // Lookups
    // -----------------------------------------------------------------------

    /**
     * Completions for a romanized prefix, best first. An exact match is always
     * ranked above its own descendants, because "nepal" should offer नेपाल
     * before नेपाली.
     */
    fun candidatesForRoman(prefix: CharSequence, limit: Int = DEFAULT_LIMIT): List<LexiconEntry> {
        if (prefix.isEmpty()) return emptyList()
        val node = romanTrie.walk(prefix)
        if (node == CompactTrie.NONE) return emptyList()
        val collector = RankedCollector(limit)
        val exactPayload = romanTrie.payloadOf(node)
        if (exactPayload != CompactTrie.NONE) {
            collector.offer(exactPayload, frequencyOfIndex(exactPayload) + EXACT_MATCH_BONUS)
        }
        collectCompletions(romanTrie, node, 0, collector, intArrayOf(TRAVERSAL_BUDGET))
        return collector.toList(entries)
    }

    /** Completions for a Devanagari prefix (native layout path). */
    fun candidatesForDevanagari(prefix: CharSequence, limit: Int = DEFAULT_LIMIT): List<LexiconEntry> {
        if (prefix.isEmpty()) return emptyList()
        val node = devanagariTrie.walk(prefix)
        if (node == CompactTrie.NONE) return emptyList()
        val collector = RankedCollector(limit)
        val exactPayload = devanagariTrie.payloadOf(node)
        if (exactPayload != CompactTrie.NONE) {
            collector.offer(exactPayload, frequencyOfIndex(exactPayload) + EXACT_MATCH_BONUS)
        }
        collectCompletions(devanagariTrie, node, 0, collector, intArrayOf(TRAVERSAL_BUDGET))
        return collector.toList(entries)
    }

    /** Exact roman key lookup, used by auto-correct. */
    fun entryForRoman(key: String): LexiconEntry? {
        val node = romanTrie.walk(key)
        if (node == CompactTrie.NONE) return null
        val payload = romanTrie.payloadOf(node)
        return if (payload == CompactTrie.NONE) null else entries[payload]
    }

    /** Exact Devanagari lookup, used to label bigram predictions. */
    fun entryForWord(word: String): LexiconEntry? {
        val node = devanagariTrie.walk(word)
        if (node == CompactTrie.NONE) return null
        val payload = devanagariTrie.payloadOf(node)
        return if (payload == CompactTrie.NONE) null else entries[payload]
    }

    fun frequencyOf(word: String): Int = frequencyIndex[word] ?: 0

    fun isKnownWord(word: String): Boolean = frequencyIndex.containsKey(word)

    /** Highest-frequency words, used as a cold-start prediction list. */
    fun topWords(limit: Int): List<LexiconEntry> = entries.asSequence()
        .sortedByDescending { it.frequency }
        .take(limit)
        .toList()

    // -----------------------------------------------------------------------
    // Traversal
    // -----------------------------------------------------------------------

    private fun collectCompletions(
        trie: CompactTrie,
        node: Int,
        depth: Int,
        collector: RankedCollector,
        budget: IntArray,
    ) {
        if (budget[0] <= 0 || depth > MAX_KEY_LENGTH) return
        val payload = trie.terminalPayload[node]
        if (payload != CompactTrie.NONE) collector.offer(payload, frequencyOfIndex(payload))
        val start = trie.childStart[node]
        val end = start + trie.childCount[node]
        var index = start
        while (index < end) {
            budget[0]--
            if (budget[0] <= 0) return
            collectCompletions(trie, trie.childNodes[index], depth + 1, collector, budget)
            index++
        }
    }

    private fun frequencyOfIndex(index: Int): Int = entries[index].frequency

    /**
     * Fixed-capacity, insertion-sorted collector.
     *
     * Avoids sorting a list and avoids boxing: candidates are inserted in
     * descending score order into two primitive arrays, so the DFS allocates
     * exactly two small arrays per query regardless of traversal size.
     */
    private class RankedCollector(private val capacity: Int) {
        private val payloads = IntArray(capacity) { CompactTrie.NONE }
        private val scores = IntArray(capacity)
        private var size = 0

        fun offer(payload: Int, score: Int) {
            if (payload == CompactTrie.NONE) return
            for (index in 0 until size) {
                if (payloads[index] == payload) return
            }
            if (size == capacity && score <= scores[capacity - 1]) return
            var insertAt = size
            for (index in 0 until size) {
                if (score > scores[index]) {
                    insertAt = index
                    break
                }
            }
            if (insertAt >= capacity) return
            var cursor = if (size < capacity) size else capacity - 1
            while (cursor > insertAt) {
                payloads[cursor] = payloads[cursor - 1]
                scores[cursor] = scores[cursor - 1]
                cursor--
            }
            payloads[insertAt] = payload
            scores[insertAt] = score
            if (size < capacity) size++
        }

        fun toList(entries: Array<LexiconEntry>): List<LexiconEntry> {
            if (size == 0) return emptyList()
            val result = ArrayList<LexiconEntry>(size)
            for (index in 0 until size) {
                val payload = payloads[index]
                if (payload != CompactTrie.NONE) result += entries[payload]
            }
            return result
        }
    }

    // -----------------------------------------------------------------------
    // Asset loading
    // -----------------------------------------------------------------------

    @Serializable
    internal data class LexiconWordDto(
        /** Devanagari word. */
        val w: String,
        /** Primary roman key. */
        val k: String,
        /** Relative frequency (rank-derived, 1..100000). */
        val f: Int,
        /** Optional alternative roman spellings that map to the same word. */
        val alts: List<String> = emptyList(),
    )

    @Serializable
    internal data class LexiconBigramDto(
        val a: String,
        val b: String,
        val f: Int,
    )

    @Serializable
    internal data class LexiconAssetDto(
        val version: Int = 1,
        val source: String = "",
        val words: List<LexiconWordDto> = emptyList(),
        val bigrams: List<LexiconBigramDto> = emptyList(),
    )

    companion object {
        const val ASSET_PATH = "lexicon/nepali_lexicon.json"

        private const val DEFAULT_LIMIT = 8
        private const val EXACT_MATCH_BONUS = 4_000
        private const val TRAVERSAL_BUDGET = 4_000
        private const val MAX_KEY_LENGTH = 16

        private val assetJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /**
         * Parses the bundled asset and builds every index. Must be called on a
         * background dispatcher: the JSON parse plus trie construction happens
         * once per process, and the result is cached by
         * [np.com.nepalikeyboard.ime.ImeRuntime].
         */
        suspend fun load(context: Context): Lexicon = withContext(Dispatchers.Default) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val asset = try {
                val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
                assetJson.decodeFromString(LexiconAssetDto.serializer(), text)
            } catch (error: Exception) {
                KeyboardLog.e("Lexicon asset missing or corrupt: ${error.message}")
                LexiconAssetDto()
            }
            build(asset).also { lexicon ->
                KeyboardLog.d(
                    "Lexicon ready: ${lexicon.size} words in " +
                        "${android.os.SystemClock.elapsedRealtime() - startedAt} ms",
                )
            }
        }

        /** Synchronous builder, exposed for unit tests and the sandbox. */
        internal fun build(asset: LexiconAssetDto): Lexicon {
            val entryList = ArrayList<LexiconEntry>(asset.words.size)
            val romanKeys = LinkedHashMap<String, Int>(asset.words.size * 2)
            val devanagariKeys = LinkedHashMap<String, Int>(asset.words.size)
            val frequencyIndex = HashMap<String, Int>(asset.words.size * 2)

            for (dto in asset.words) {
                val word = dto.w.trim()
                val roman = dto.k.trim().lowercase()
                if (word.isEmpty() || roman.isEmpty()) continue
                if (!isAsciiKey(roman)) continue
                val alts = dto.alts.asSequence()
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && isAsciiKey(it) && it != roman }
                    .distinct()
                    .toList()
                val index = entryList.size
                entryList += LexiconEntry(
                    word = word,
                    roman = roman,
                    frequency = dto.f.coerceAtLeast(1),
                    altRoman = alts,
                )
                if (!romanKeys.containsKey(roman)) romanKeys[roman] = index
                for (alt in alts) {
                    if (!romanKeys.containsKey(alt)) romanKeys[alt] = index
                }
                if (!devanagariKeys.containsKey(word)) devanagariKeys[word] = index
                // Keep the strongest frequency if a word is declared twice.
                val existing = frequencyIndex[word]
                if (existing == null || existing < dto.f) frequencyIndex[word] = dto.f.coerceAtLeast(1)
            }

            val romanBuilder = CompactTrieBuilder(romanKeys.size)
            for ((key, payload) in romanKeys) romanBuilder.insert(key, payload)

            val devanagariBuilder = CompactTrieBuilder(devanagariKeys.size)
            for ((key, payload) in devanagariKeys) devanagariBuilder.insert(key, payload)

            val bigramTable = HashMap<String, HashMap<String, Int>>(asset.bigrams.size)
            for (dto in asset.bigrams) {
                val first = dto.a.trim()
                val second = dto.b.trim()
                if (first.isEmpty() || second.isEmpty()) continue
                bigramTable.getOrPut(first) { HashMap(4) }[second] = dto.f.coerceAtLeast(1)
            }

            return Lexicon(
                entries = entryList.toTypedArray(),
                romanTrie = romanBuilder.build(),
                devanagariTrie = devanagariBuilder.build(),
                frequencyIndex = frequencyIndex,
                bigrams = BiGramModel(bigramTable),
            )
        }

        /** The trie is ASCII-only, which keeps node fan-out tiny and lookups fast. */
        private fun isAsciiKey(key: String): Boolean {
            for (index in key.indices) {
                val ch = key[index]
                if (ch !in 'a'..'z' && ch != '\'' ) return false
            }
            return true
        }
    }
}
