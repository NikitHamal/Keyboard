package np.com.nepalikeyboard.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import np.com.nepalikeyboard.util.KeyboardLog

private val Context.learningDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nepali_keyboard_learning",
)

/** A user-curated dictionary entry. */
@Immutable
data class PersonalWord(
    val word: String,
    val roman: String = "",
    val hits: Int = 1,
    val createdAt: Long = 0L,
)

/**
 * Adaptive, entirely offline learning model.
 *
 * Two signals are maintained:
 *  - unigram counts: how often each Devanagari word was committed;
 *  - bigram counts: how often word B followed word A.
 *
 * Both feed the candidate ranking in [np.com.nepalikeyboard.engine.CandidateEngine].
 * Writes happen on the main thread (keystroke path) through `synchronized`
 * blocks and allocation-free `HashMap` updates; reads happen on
 * Dispatchers.Default from the candidate worker. Nothing here can block for
 * longer than a map lookup.
 *
 * Sensitive (password) fields never call into this class at all.
 */
class LearningRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val lock = Any()
    private val wordCounts = HashMap<String, Int>(256)
    private val bigramsByFirst = HashMap<String, HashMap<String, Int>>(256)
    private val personalWords = LinkedHashMap<String, PersonalWord>(32)

    private val _personalState = MutableStateFlow<List<PersonalWord>>(emptyList())
    val personalState: StateFlow<List<PersonalWord>> = _personalState.asStateFlow()

    private val _learnedCountState = MutableStateFlow(0)
    val learnedCountState: StateFlow<Int> = _learnedCountState.asStateFlow()

    private val flushRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { restore() }
        scope.launch {
            for (ignored in flushRequests) {
                delay(FLUSH_DEBOUNCE_MS)
                flush()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Recording (main thread, allocation-light)
    // -----------------------------------------------------------------------

    /** Records a committed word. Returns the new hit count for that word. */
    fun recordWord(word: String): Int {
        if (word.isEmpty()) return 0
        val count: Int
        synchronized(lock) {
            count = (wordCounts[word] ?: 0) + 1
            wordCounts[word] = count
            if (wordCounts.size > MAX_LEARNED_WORDS) pruneLocked()
        }
        _learnedCountState.value = learnedCountLocked()
        requestFlush()
        return count
    }

    /** Records that [second] directly followed [first] in committed text. */
    fun recordBigram(first: String, second: String) {
        if (first.isEmpty() || second.isEmpty()) return
        synchronized(lock) {
            val bucket = bigramsByFirst.getOrPut(first) { HashMap(4) }
            val count = (bucket[second] ?: 0) + 1
            bucket[second] = count
            if (bigramsByFirst.size > MAX_BIGRAM_HEADS) {
                // Drop the least-connected head to stay bounded.
                val weakest = bigramsByFirst.minByOrNull { entry -> entry.value.values.sum() }
                if (weakest != null && weakest.key != first) bigramsByFirst.remove(weakest.key)
            }
        }
        requestFlush()
    }

    fun hitCount(word: String): Int = synchronized(lock) { wordCounts[word] ?: 0 }

    fun isPersonal(word: String): Boolean = synchronized(lock) { personalWords.containsKey(word) }

    /**
     * Ranking bonus for a candidate word, derived from personal dictionary
     * membership and observed usage. Bounded so that a heavily used word cannot
     * swamp a much more frequent dictionary word.
     */
    fun personalBoost(word: String): Int = synchronized(lock) {
        val personal = personalWords[word]
        if (personal != null) return@synchronized PERSONAL_BASE + personal.hits * 4
        val hits = wordCounts[word] ?: return@synchronized 0
        (hits * 3).coerceAtMost(MAX_LEARNED_BOOST)
    }

    /** Bigram score for `first -> second`, 0 when unseen. */
    fun bigramScore(first: String, second: String): Int = synchronized(lock) {
        bigramsByFirst[first]?.get(second) ?: 0
    }

    /** Most likely successors of [first], best first. */
    fun nextWords(first: String, limit: Int): List<PersonalWord> {
        val bucket = synchronized(lock) { bigramsByFirst[first]?.entries?.map { it.key to it.value } } ?: return emptyList()
        return bucket.asSequence()
            .sortedByDescending { it.second }
            .take(limit)
            .map { (word, count) -> PersonalWord(word = word, hits = count) }
            .toList()
    }

    /** Learned words ending with [suffixHint] are of no interest; kept for API symmetry. */
    fun learnedWordsSnapshot(limit: Int): List<PersonalWord> = synchronized(lock) {
        wordCounts.entries.asSequence()
            .sortedByDescending { it.value }
            .take(limit)
            .map { PersonalWord(word = it.key, hits = it.value) }
            .toList()
    }

    // -----------------------------------------------------------------------
    // Personal dictionary
    // -----------------------------------------------------------------------

    fun addPersonalWord(word: String, roman: String = "") {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) {
            val existing = personalWords[trimmed]
            personalWords[trimmed] = PersonalWord(
                word = trimmed,
                roman = roman.trim().ifEmpty { existing?.roman.orEmpty() },
                hits = (existing?.hits ?: 0) + 1,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
            wordCounts[trimmed] = (wordCounts[trimmed] ?: 0) + 1
        }
        publishPersonal()
        requestFlush()
    }

    fun removePersonalWord(word: String) {
        synchronized(lock) { personalWords.remove(word) }
        publishPersonal()
        requestFlush()
    }

    /** Forgets everything learned and the personal dictionary. */
    fun clearLearning() {
        synchronized(lock) {
            wordCounts.clear()
            bigramsByFirst.clear()
            personalWords.clear()
        }
        publishPersonal()
        _learnedCountState.value = 0
        requestFlush()
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private fun publishPersonal() {
        _personalState.value = synchronized(lock) {
            personalWords.values.sortedByDescending { it.hits }
        }
        _learnedCountState.value = learnedCountLocked()
    }

    private fun learnedCountLocked(): Int = synchronized(lock) { wordCounts.size }

    private fun requestFlush() {
        flushRequests.trySend(Unit)
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        val payload = synchronized(lock) {
            val learned = wordCounts.entries.asSequence()
                .sortedByDescending { it.value }
                .take(MAX_PERSISTED_LEARNED)
                .map { PersonalWordDto(word = it.key, hits = it.value, personal = false) }
                .toList()
            val curated = personalWords.values.map { PersonalWordDto(it.word, it.roman, it.hits, it.createdAt, personal = true) }
            val pairs = ArrayList<BigramDto>(512)
            for ((first, bucket) in bigramsByFirst) {
                for ((second, count) in bucket) {
                    if (count >= MIN_PERSISTED_BIGRAM) pairs += BigramDto(first, second, count)
                }
            }
            LearningPayloadDto(words = curated + learned, bigrams = pairs)
        }
        try {
            context.learningDataStore.edit { prefs ->
                prefs[PAYLOAD_KEY] = KeyboardJson.encodeToString(LearningPayloadDto.serializer(), payload)
            }
        } catch (error: Exception) {
            KeyboardLog.w("Learning persist failed: ${error.message}")
        }
    }

    private suspend fun restore() = withContext(Dispatchers.IO) {
        val payload = try {
            val prefs = context.learningDataStore.data.first()
            val raw = prefs[PAYLOAD_KEY] ?: return@withContext
            KeyboardJson.decodeFromString(LearningPayloadDto.serializer(), raw)
        } catch (error: Exception) {
            KeyboardLog.w("Learning restore failed: ${error.message}")
            return@withContext
        }
        synchronized(lock) {
            for (dto in payload.words) {
                if (dto.word.isBlank()) continue
                wordCounts[dto.word] = dto.hits.coerceAtLeast(1)
                if (dto.personal) {
                    personalWords[dto.word] = PersonalWord(dto.word, dto.roman, dto.hits, dto.createdAt)
                }
            }
            for (dto in payload.bigrams) {
                if (dto.first.isBlank() || dto.second.isBlank()) continue
                bigramsByFirst.getOrPut(dto.first) { HashMap(4) }[dto.second] = dto.count
            }
        }
        publishPersonal()
    }

    /** Keeps the strongest [KEEP_RATIO] of learned words when the cap is hit. */
    private fun pruneLocked() {
        val survivors = wordCounts.entries
            .sortedByDescending { it.value }
            .take((MAX_LEARNED_WORDS * KEEP_RATIO).toInt())
            .map { it.key }
            .toHashSet()
        wordCounts.keys.retainAll(survivors)
        bigramsByFirst.keys.retainAll(survivors)
        for (bucket in bigramsByFirst.values) bucket.keys.retainAll(survivors)
    }

    companion object {
        private const val MAX_LEARNED_WORDS = 4_000
        private const val MAX_BIGRAM_HEADS = 3_000
        private const val MAX_PERSISTED_LEARNED = 2_500
        private const val MIN_PERSISTED_BIGRAM = 2
        private const val KEEP_RATIO = 0.75f
        private const val PERSONAL_BASE = 900
        private const val MAX_LEARNED_BOOST = 600
        private const val FLUSH_DEBOUNCE_MS = 1_500L
        private val PAYLOAD_KEY = stringPreferencesKey("learning_json")

        @Volatile
        private var instance: LearningRepository? = null

        fun get(context: Context): LearningRepository {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: LearningRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
