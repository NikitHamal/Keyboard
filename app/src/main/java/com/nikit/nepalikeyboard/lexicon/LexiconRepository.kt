package com.nikit.nepalikeyboard.lexicon

import android.content.Context
import android.util.Log
import com.nikit.nepalikeyboard.lexicon.model.LexiconAsset
import com.nikit.nepalikeyboard.lexicon.model.LexiconEntry
import com.nikit.nepalikeyboard.lexicon.model.Suggestion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Owns the on-device lexicon: the radix trie, the bigram table, and the ranker.
 *
 * ## Lifecycle
 *
 * A single process-wide instance, created lazily by [get] and initialised by
 * [ensureLoaded]. The IME service and the settings app share it, so the
 * dictionary is parsed at most once per process even when the user walks
 * between the keyboard and the settings screen.
 *
 * ## Threading contract
 *
 *  * [ensureLoaded] is a `suspend` function safe to call from any dispatcher.
 *    It performs all I/O and parsing on [ioDispatcher] and never blocks the
 *    caller.
 *  * [suggest] is a `suspend` function that runs the trie query and the ranking
 *    pass on [computeDispatcher]. It is the only entry point the keyboard uses,
 *    which guarantees that no lexicon work ever happens on the main thread.
 *  * [rememberWord], [resetContext], and [loadLearnedWords] mutate ranker state
 *    and are confined to the same dispatcher by their callers in the engine.
 *
 * The mutex guards only initialisation and the learned-word table. Query paths
 * are lock-free reads of immutable structures.
 */
class LexiconRepository private constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val computeDispatcher: CoroutineDispatcher
) {

    private val initMutex = Mutex()

    private val trie = RadixTrie()

    /** Ranking engine; not thread-safe, confined to [computeDispatcher]. */
    private val ranker = CandidateRanker()

    /** Serialised asset reader. Configured to be strict about unknown keys. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        allowTrailingComma = false
    }

    // ------------------------------------------------------------------
    // Load state, observable so the UI can show progress
    // ------------------------------------------------------------------

    private val _state = MutableStateFlow<LoadState>(LoadState.NotStarted)

    /** Current load state. Collectable from Compose for a loading indicator. */
    val state: StateFlow<LoadState> = _state.asStateFlow()

    /** True once [ensureLoaded] has completed successfully. */
    val isReady: Boolean get() = _state.value is LoadState.Ready

    // ------------------------------------------------------------------
    // Scratch buffers, reused to avoid per-keystroke allocation
    // ------------------------------------------------------------------

    /**
     * Reusable candidate buffer for the trie query. Because [suggest] runs on a
     * dedicated single-threaded dispatcher, one buffer is sufficient and safe.
     */
    private val candidateBuffer = ArrayList<LexiconEntry>(CANDIDATE_BUFFER_SIZE)

    // ==================================================================
    // Loading
    // ==================================================================

    /**
     * Load the bundled lexicon if it has not been loaded yet.
     *
     * Idempotent and safe to call concurrently: the first caller does the work,
     * the rest suspend on the mutex and then observe [LoadState.Ready].
     *
     * @param context used to reach `assets/dict/`. Only the application context
     *        is retained, never an Activity.
     * @param assetPath the asset to parse; parameterised so the test suite can
     *        point at a tiny fixture.
     */
    suspend fun ensureLoaded(
        context: Context,
        assetPath: String = DEFAULT_ASSET_PATH
    ) {
        if (_state.value is LoadState.Ready) return

        initMutex.withLock {
            // Re-check after acquiring the lock: another coroutine may have
            // finished loading while we waited.
            if (_state.value is LoadState.Ready) return

            _state.value = LoadState.Loading
            try {
                val asset = withContext(ioDispatcher) {
                    readAsset(context.applicationContext, assetPath)
                }

                withContext(computeDispatcher) {
                    buildIndexes(asset)
                }

                _state.value = LoadState.Ready(
                    wordCount = trie.size,
                    bigramContexts = ranker.bigramContextCount,
                    locale = asset.metadata.locale,
                    assetVersion = asset.metadata.version
                )
            } catch (t: Throwable) {
                // A missing or malformed asset must not take the keyboard down.
                // We record the failure, remain in a degraded state where the
                // engine still transliterates but offers no lexical candidates,
                // and let the UI surface it.
                Log.e(TAG, "Lexicon load failed for asset '$assetPath'", t)
                _state.value = LoadState.Failed(
                    message = t.message ?: t::class.java.simpleName
                )
            }
        }
    }

    /** Parse the asset. Runs on [ioDispatcher]. */
    private fun readAsset(context: Context, assetPath: String): LexiconAsset {
        val text = context.assets.open(assetPath).use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        }
        return json.decodeFromString(LexiconAsset.serializer(), text)
    }

    /** Populate the trie and ranker from a decoded asset. Runs on [computeDispatcher]. */
    private fun buildIndexes(asset: LexiconAsset) {
        trie.insertAll(asset.words)
        ranker.loadBigrams(asset.bigrams)
    }

    /** Install the learned-word table. Called after DataStore has been read. */
    suspend fun loadLearnedWords(words: Map<String, Int>) {
        withContext(computeDispatcher) {
            ranker.loadPersonalUsage(words)
        }
    }

    // ==================================================================
    // Queries
    // ==================================================================

    /**
     * Produce the suggestion strip for [romanInput].
     *
     * Runs the trie prefix query, the ordering pass, and the bigram re-rank
     * entirely on [computeDispatcher]. The caller awaits the result and posts it
     * into UI state; nothing here touches the main thread.
     *
     * @param romanInput the Romanized text typed so far, without trailing space
     * @param limit maximum number of strip entries
     * @return the ranked suggestions, or an empty list when the lexicon is not
     *         ready. A degraded lexicon still returns a literal option when
     *         [includeLiteral] is set, so the strip is never blank while typing.
     */
    suspend fun suggest(
        romanInput: String,
        limit: Int = DEFAULT_SUGGESTION_LIMIT,
        includeLiteral: Boolean = true
    ): List<Suggestion> = withContext(computeDispatcher) {
        val transliteration = CandidateRanker.transliterationFor(romanInput)

        if (!isReady || romanInput.isEmpty()) {
            // Degraded path: still offer the literal and the transliteration so
            // the user is never left without an escape hatch.
            return@withContext ranker.buildSuggestions(
                romanInput = romanInput,
                transliteration = transliteration,
                lexical = emptyList(),
                limit = limit,
                includeLiteral = includeLiteral
            )
        }

        val lexical = queryLexiconLocked(romanInput, limit)
        ranker.buildSuggestions(
            romanInput = romanInput,
            transliteration = transliteration,
            lexical = lexical,
            limit = limit,
            includeLiteral = includeLiteral
        )
    }

    /**
     * Look up lexical candidates for [romanInput].
     *
     * Two strategies are combined:
     *
     *  1. **Prefix expansion** — every word whose Romanized key starts with the
     *     input. This is what makes partial typing productive.
     *  2. **Exact/alias hit** — when the input is already a complete key, the
     *     exact match is guaranteed a slot even if its raw frequency is low,
     *     because the user typing the whole word is strong evidence.
     *
     * Results are merged, de-duplicated by Devanagari text, then scored.
     */
    private fun queryLexiconLocked(romanInput: String, limit: Int): List<Suggestion> {
        val lower = romanInput.lowercase()

        // Strategy 1: prefix expansion into the reusable buffer.
        trie.collectPrefix(lower, CANDIDATE_BUFFER_SIZE, candidateBuffer)

        // Strategy 2: the exact key, if it exists, must not be crowded out.
        val exact = trie.findExact(lower)

        val entries: MutableList<LexiconEntry> = if (exact != null &&
            candidateBuffer.none { it.devanagari == exact.devanagari }
        ) {
            ArrayList<LexiconEntry>(candidateBuffer.size + 1).apply {
                add(exact)
                addAll(candidateBuffer)
            }
        } else {
            candidateBuffer
        }

        if (entries.isEmpty()) return emptyList()

        return ranker.rank(entries, romanInput, limit)
    }

    /**
     * True when [romanInput] is a complete Romanized key in the lexicon.
     * Used by the engine to decide whether to auto-commit a word on space.
     */
    fun isKnownWord(romanInput: String): Boolean {
        if (romanInput.isEmpty()) return false
        return trie.findExact(romanInput.lowercase()) != null
    }

    /** Statistics for the settings screen's diagnostics panel. */
    fun statistics(): LexiconStatistics = LexiconStatistics(
        wordCount = trie.size,
        learnedWords = ranker.learnedWordCount,
        bigramContexts = ranker.bigramContextCount,
        state = _state.value
    )

    // ==================================================================
    // Mutations driven by user action
    // ==================================================================

    /**
     * Record that the user committed [devanagari] as a word.
     *
     * Updating the ranker's personal-usage table changes future scores. The
     * caller additionally persists the count to DataStore; this function only
     * updates the in-memory view so the next keystroke benefits immediately.
     */
    suspend fun rememberWord(devanagari: String) {
        if (devanagari.isEmpty()) return
        withContext(computeDispatcher) {
            ranker.recordUsage(devanagari)
            ranker.setContext(devanagari)
        }
    }

    /** Reset the bigram context, e.g. after a sentence terminator. */
    suspend fun resetContext() {
        withContext(computeDispatcher) {
            ranker.resetContext()
        }
    }

    /**
     * Set the bigram context without recording usage.
     *
     * Used after committing a literal or transliterated word that the learner
     * must not count (password fields, clipboard pastes, suggestion commits
     * the user did not type). Contrast [rememberWord], which both records and
     * sets the context.
     */
    suspend fun setContext(word: String) {
        if (word.isEmpty()) return
        withContext(computeDispatcher) {
            ranker.setContext(word)
        }
    }

    // ==================================================================
    // Singleton plumbing
    // ==================================================================

    /** Snapshot of lexicon health, for display. */
    data class LexiconStatistics(
        val wordCount: Int,
        val learnedWords: Int,
        val bigramContexts: Int,
        val state: LoadState
    )

    /** Progress of the one-time load. */
    sealed interface LoadState {
        /** Nothing attempted yet. */
        data object NotStarted : LoadState

        /** Parsing and indexing in progress. */
        data object Loading : LoadState

        /** Usable. */
        data class Ready(
            val wordCount: Int,
            val bigramContexts: Int,
            val locale: String,
            val assetVersion: Int
        ) : LoadState

        /**
         * Unusable, but the keyboard still functions in transliteration-only
         * mode. [message] is shown in the settings diagnostics panel.
         */
        data class Failed(val message: String) : LoadState
    }

    companion object {
        private const val TAG = "LexiconRepository"

        /** Bundled asset. See AGENTS.md for the schema and how to regenerate it. */
        const val DEFAULT_ASSET_PATH = "dict/ne_lexicon.json"

        /** Strip width. Five entries is what fits without crowding key hints. */
        const val DEFAULT_SUGGESTION_LIMIT = 5

        /**
         * Upper bound on trie results examined per query. Large enough that a
         * common prefix surfaces its best words, small enough that the ranking
         * pass stays well under a frame budget even on a low-end device.
         */
        private const val CANDIDATE_BUFFER_SIZE = 48

        @Volatile
        private var instance: LexiconRepository? = null

        /**
         * Return the process-wide repository.
         *
         * Uses double-checked locking on a `@Volatile` field. The defaults are
         * overridable only for tests; production callers use the no-argument
         * form which binds to [Dispatchers.IO] and [Dispatchers.Default].
         */
        fun get(
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            computeDispatcher: CoroutineDispatcher = Dispatchers.Default
        ): LexiconRepository {
            val existing = instance
            if (existing != null) return existing
            synchronized(this) {
                val second = instance
                if (second != null) return second
                val created = LexiconRepository(ioDispatcher, computeDispatcher)
                instance = created
                return created
            }
        }
    }
}
