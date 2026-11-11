package np.com.nepalikeyboard.engine

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import np.com.nepalikeyboard.data.LearningRepository
import np.com.nepalikeyboard.engine.unicode.Devanagari
import np.com.nepalikeyboard.tlm.HybridEngine
import np.com.nepalikeyboard.tlm.TlmStale
import np.com.nepalikeyboard.util.WorkerThreads

/**
 * Asynchronous candidate pipeline.
 *
 * The keystroke path never computes anything here: it calls [submitRoman] or
 * [submitPrediction], which only enqueue a request on a `CONFLATED` channel
 * (lock free, drops stale work when the user types faster than the worker can
 * rank). A single worker coroutine consumes requests on
 * [Dispatchers.Default], builds the candidate list and hands it back on the main
 * dispatcher.
 *
 * Ranking blends four signals, all offline:
 *  1. bundled relative frequency (log-scaled);
 *  2. bundled bigram score for `previousWord -> candidate`;
 *  3. learned bigram/unigram counts from [LearningRepository];
 *  4. exact-key match bonus, so a fully typed word wins over its completions.
 *
 * Sensitive editors never reach this class; the IME stops submitting entirely.
 */
class CandidateEngine(
    scope: CoroutineScope,
    private val learning: LearningRepository,
    private val lexiconProvider: () -> Lexicon?,
    private val tlmProvider: () -> HybridEngine? = { null },
    private val callback: Callback,
) {

    /** Implemented by the IME controller; always invoked on the main thread. */
    interface Callback {
        fun onCandidatesReady(generation: Long, candidates: List<Candidate>, predictionOnly: Boolean, isFinal: Boolean)
    }

    private sealed interface Request {
        data class Roman(
            val generation: Long,
            val buffer: String,
            val previousWord: String?,
            val devanagariDigits: Boolean,
            val leftContext: String,
            val submittedAt: Long = System.nanoTime(),
        ) : Request

        data class Prediction(
            val generation: Long,
            val previousWord: String?,
            val devanagariPrefix: String?,
        ) : Request
    }

    private val requests = Channel<Request>(Channel.CONFLATED)
    private val latestGeneration = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile
    private var pendingDeep = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The newest result the worker finished, readable from any thread (see [awaitPublished]). */
    private class Published(val generation: Long, val candidates: List<Candidate>, val isFinal: Boolean)

    @Volatile
    private var published: Published? = null
    private val publishLock = ReentrantLock()
    private val publishedChanged = publishLock.newCondition()

    init {
        scope.launch(WorkerThreads.inference) {
            for (request in requests) {
                try {
                    when (request) {
                        is Request.Roman -> processRoman(request)
                        is Request.Prediction -> if (latestGeneration.get() == request.generation) {
                            post(request.generation, handlePrediction(request), predictionOnly = true, isFinal = true)
                        }
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: TlmStale) {
                    // A newer keystroke superseded this request mid-flight; its own request is already queued.
                } catch (error: Throwable) {
                    // One bad request must not kill the worker for the rest of the session.
                    np.com.nepalikeyboard.util.KeyboardLog.e("candidate worker request failed", error)
                }
            }
        }
    }

    /**
     * Phase 1 is cheap (lexicon + rescoring, no beam search) so the composing text and strip update within a few ms of
     * the key press; phase 2 (beam search for unseen words) only runs if nobody typed meanwhile and aborts the moment
     * somebody does.
     */
    private fun processRoman(request: Request.Roman) {
        if (latestGeneration.get() != request.generation) return
        pendingDeep = false
        val shallow = handleRoman(request, deep = false)
        np.com.nepalikeyboard.util.TlmStats.record(System.nanoTime() - request.submittedAt)
        val needsDeep = pendingDeep && latestGeneration.get() == request.generation
        publish(request.generation, shallow, isFinal = !needsDeep)
        post(request.generation, shallow, predictionOnly = false, isFinal = !needsDeep)
        if (needsDeep) {
            pendingDeep = false
            val deep = handleRoman(request, deep = true)
            publish(request.generation, deep, isFinal = true)
            post(request.generation, deep, predictionOnly = false, isFinal = true)
        }
    }

    private fun publish(generation: Long, candidates: List<Candidate>, isFinal: Boolean) {
        publishLock.lock()
        try {
            published = Published(generation, candidates, isFinal)
            publishedChanged.signalAll()
        } finally {
            publishLock.unlock()
        }
    }

    /**
     * The candidates the worker computed for [generation], waiting at most [timeoutMs]. Used by Space so it commits the
     * model's pick for the word on screen without ever running the model on the main thread. Null on timeout.
     */
    /** True when the worker has finished EVERYTHING (including any beam search) for [generation]. */
    fun isFinal(generation: Long): Boolean {
        val p = published ?: return false
        return p.generation == generation && p.isFinal
    }

    fun awaitPublished(generation: Long, timeoutMs: Long): List<Candidate>? {
        published?.let { if (it.generation == generation) return it.candidates }
        if (timeoutMs <= 0L) return null
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        publishLock.lock()
        try {
            while (true) {
                val p = published
                if (p != null && p.generation == generation) return p.candidates
                if (nanos <= 0L) return null
                nanos = publishedChanged.awaitNanos(nanos)
            }
        } finally {
            publishLock.unlock()
        }
    }

    /** Queues work for a romanized buffer. Never blocks, never allocates lists. */
    fun submitRoman(
        generation: Long,
        buffer: String,
        previousWord: String?,
        devanagariDigits: Boolean,
        leftContext: String = "",
    ) {
        latestGeneration.set(generation)
        np.com.nepalikeyboard.tlm.TlmTuning.lastKeystrokeNanos = System.nanoTime()
        requests.trySend(
            Request.Roman(
                generation = generation,
                buffer = buffer,
                previousWord = previousWord,
                devanagariDigits = devanagariDigits,
                leftContext = leftContext,
            ),
        )
    }

    /** Queues next-word prediction work for an empty composition. */
    fun submitPrediction(generation: Long, previousWord: String?, devanagariPrefix: String? = null) {
        latestGeneration.set(generation)
        requests.trySend(
            Request.Prediction(
                generation = generation,
                previousWord = previousWord,
                devanagariPrefix = devanagariPrefix,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Worker
    // -----------------------------------------------------------------------

    /** Hands a result to the main thread without making the worker wait for it. */
    private fun post(generation: Long, candidates: List<Candidate>, predictionOnly: Boolean, isFinal: Boolean) {
        mainHandler.post { callback.onCandidatesReady(generation, candidates, predictionOnly, isFinal) }
    }

    private fun handleRoman(request: Request.Roman, deep: Boolean = true): List<Candidate> {
        val buffer = request.buffer
        if (buffer.isEmpty()) return emptyList()

        val tlm = tlmProvider()
        if (tlm != null && isTlmWord(buffer)) {
            try {
                return handleTlm(tlm, request, deep)
            } catch (error: TlmStale) {
                throw error
            } catch (error: Throwable) {
                // fall through to the deterministic path
                np.com.nepalikeyboard.util.KeyboardLog.e("TinyLM suggest failed", error)
            }
        }

        val lexicon = lexiconProvider()
        val lowercase = PhoneticEngine.asciiLowercase(buffer)
        val transliteration = PhoneticEngine.transliterate(buffer, request.devanagariDigits)
        val exactEntry = lexicon?.entryForRoman(lowercase)

        val result = ArrayList<Candidate>(MAX_CANDIDATES)

        // (1) Literal romanized input - tap to keep the Latin text as typed.
        result += Candidate(text = buffer, kind = CandidateKind.LITERAL, score = Int.MAX_VALUE)

        // (2) The Devanagari word the keyboard would insert.
        //
        // Deterministic transliteration cannot recover information the
        // romanization threw away: "nepal" transliterates to नेपल because the
        // written form has a long ा that the ASCII key does not mark. When the
        // buffer is the exact key of a bundled word, the dictionary spelling *is*
        // the intended transliteration, so it is pinned here - which makes the
        // strip, the space bar and auto-correct all agree on one word. Unknown
        // words (names, transliterated English) keep the literal rendering.
        val devanagari = exactEntry?.word ?: transliteration
        if (devanagari.isNotEmpty() && devanagari != buffer) {
            result += Candidate(
                text = devanagari,
                hint = buffer,
                kind = CandidateKind.TRANSLITERATION,
                score = LITERAL_TRANSLITERATION_SCORE,
            )
        }

        // (3) Lexical + personal alternatives, ranked.
        val ranked = ArrayList<Candidate>(MAX_CANDIDATES)
        val seen = HashSet<String>(MAX_CANDIDATES * 2)

        val personal = learning.personalState.value
        for (word in personal) {
            val romanKey = word.roman
            val matchesRoman = romanKey.isNotEmpty() && romanKey.lowercase().startsWith(lowercase)
            val matchesDevanagari = transliteration.isNotEmpty() && word.word.startsWith(transliteration)
            if (!matchesRoman && !matchesDevanagari) continue
            if (!seen.add(word.word)) continue
            ranked += Candidate(
                text = word.word,
                hint = romanKey.takeIf { it.isNotEmpty() },
                kind = CandidateKind.PERSONAL,
                score = scoreOf(
                    frequency = lexicon?.frequencyOf(word.word) ?: 0,
                    bigram = bigramScore(request.previousWord, word.word),
                    boost = learning.personalBoost(word.word),
                    exactKey = false,
                ),
            )
        }

        if (lexicon != null) {
            val prefixNode = lexicon.candidatesForRoman(lowercase, LIMIT)
            for (entry in prefixNode) {
                if (!seen.add(entry.word)) continue
                val isExact = exactEntry?.word == entry.word
                ranked += Candidate(
                    text = entry.word,
                    hint = entry.roman,
                    kind = CandidateKind.LEXICAL,
                    score = scoreOf(
                        frequency = entry.frequency,
                        bigram = bigramScore(request.previousWord, entry.word),
                        boost = learning.personalBoost(entry.word),
                        exactKey = isExact,
                    ),
                )
            }
        }

        ranked.sortWith(BY_SCORE_DESCENDING)
        for (candidate in ranked) {
            if (result.size >= MAX_CANDIDATES) break
            // Never repeat the literal or the transliteration chip.
            if (candidate.text == buffer || candidate.text == devanagari) continue
            result += candidate
        }
        return result
    }

    private fun isTlmWord(buffer: String): Boolean {
        for (c in buffer) if (!(c in 'a'..'z' || c in 'A'..'Z' || c == '\'')) return false
        return buffer.length <= 34
    }

    /** TinyLM path: stage-1 ranked words, stage-2 context rerank, Padayog flags; literal chip last. */
    private fun handleTlm(tlm: HybridEngine, request: Request.Roman, deep: Boolean): List<Candidate> {
        val buffer = request.buffer
        val generation = request.generation
        val suggestion = tlm.suggest(
            request.leftContext, buffer, MAX_CANDIDATES - 1, deep = deep,
            stale = { latestGeneration.get() != generation },
        )
        pendingDeep = !deep && suggestion.pending
        val result = ArrayList<Candidate>(MAX_CANDIDATES)
        val seen = HashSet<String>()
        val keepLatin = tlm.keepsLatin(buffer)
        if (keepLatin) result += Candidate(text = buffer, kind = CandidateKind.LITERAL, score = Int.MAX_VALUE)
        for ((index, word) in suggestion.words.withIndex()) {
            if (!seen.add(word)) continue
            result += Candidate(
                text = word,
                hint = if (index == 0) buffer else null,
                kind = if (index == 0) CandidateKind.TRANSLITERATION else CandidateKind.LEXICAL,
                score = 10_000 - index,
                joinPrev = suggestion.joins.getOrElse(index) { false },
            )
        }
        // A word the user taught the keyboard for exactly this roman key goes right behind the top pick.
        val lowercase = PhoneticEngine.asciiLowercase(buffer)
        val personal = learning.personalState.value.firstOrNull {
            it.roman.isNotEmpty() && it.roman.lowercase() == lowercase && it.word !in seen
        }
        if (personal != null) {
            result.add(minOf(1, result.size), Candidate(text = personal.word, hint = personal.roman, kind = CandidateKind.PERSONAL, score = 9_000))
        }
        if (!keepLatin) result += Candidate(text = buffer, kind = CandidateKind.LITERAL, score = Int.MIN_VALUE)
        return if (result.size > MAX_CANDIDATES) ArrayList(result.subList(0, MAX_CANDIDATES)) else result
    }

    private fun handlePrediction(request: Request.Prediction): List<Candidate> {
        val lexicon = lexiconProvider()
        val previous = request.previousWord
        val prefix = request.devanagariPrefix
        val result = ArrayList<Candidate>(MAX_CANDIDATES)
        val seen = HashSet<String>(MAX_CANDIDATES * 2)

        if (!prefix.isNullOrEmpty() && lexicon != null) {
            // Completing a Devanagari word typed on the native layout.
            for (entry in lexicon.candidatesForDevanagari(prefix, LIMIT)) {
                if (!seen.add(entry.word)) continue
                result += Candidate(
                    text = entry.word,
                    hint = entry.roman,
                    kind = CandidateKind.LEXICAL,
                    score = scoreOf(entry.frequency, 0, learning.personalBoost(entry.word), false),
                )
            }
            return result.sortedWith(BY_SCORE_DESCENDING).take(MAX_CANDIDATES)
        }

        if (previous.isNullOrEmpty()) {
            // Cold start: offer the highest-frequency words of the lexicon.
            val top = lexicon?.topWords(COLD_START_LIMIT).orEmpty()
            for (entry in top) {
                if (!seen.add(entry.word)) continue
                result += Candidate(
                    text = entry.word,
                    hint = entry.roman,
                    kind = CandidateKind.PREDICTION,
                    score = scoreOf(entry.frequency, 0, learning.personalBoost(entry.word), false),
                )
            }
            return result
        }

        if (lexicon != null) {
            for ((word, count) in lexicon.bigrams.successors(previous, LIMIT)) {
                if (!seen.add(word)) continue
                result += Candidate(
                    text = word,
                    // Bigram keys and values are Devanagari, so the romanization
                    // hint comes from the Devanagari index, not the roman trie.
                    hint = lexicon.entryForWord(word)?.roman,
                    kind = CandidateKind.PREDICTION,
                    score = scoreOf(
                        frequency = lexicon.frequencyOf(word),
                        bigram = count * BUNDLED_BIGRAM_WEIGHT,
                        boost = learning.personalBoost(word),
                        exactKey = false,
                    ),
                )
            }
        }

        for (word in learning.nextWords(previous, LIMIT)) {
            if (!seen.add(word.word)) continue
            result += Candidate(
                text = word.word,
                kind = CandidateKind.PREDICTION,
                score = scoreOf(
                    frequency = lexicon?.frequencyOf(word.word) ?: 0,
                    bigram = word.hits * LEARNED_BIGRAM_WEIGHT,
                    boost = learning.personalBoost(word.word),
                    exactKey = false,
                ),
            )
        }

        result.sortWith(BY_SCORE_DESCENDING)
        return if (result.size > MAX_CANDIDATES) ArrayList(result.subList(0, MAX_CANDIDATES)) else result
    }

    private fun bigramScore(previousWord: String?, candidate: String): Int {
        if (previousWord.isNullOrEmpty()) return 0
        val bundled = lexiconProvider()?.bigrams?.score(previousWord, candidate) ?: 0
        val learned = learning.bigramScore(previousWord, candidate)
        return bundled * BUNDLED_BIGRAM_WEIGHT + learned * LEARNED_BIGRAM_WEIGHT
    }

    private fun scoreOf(frequency: Int, bigram: Int, boost: Int, exactKey: Boolean): Int {
        val frequencyScore = if (frequency <= 0) 0 else (Math.log(frequency.toDouble() + 1.0) * FREQUENCY_SCALE).toInt()
        return frequencyScore + bigram + boost + if (exactKey) EXACT_KEY_BONUS else 0
    }

    companion object {
        const val MAX_CANDIDATES = 8
        private const val LIMIT = 8
        private const val COLD_START_LIMIT = 6
        private const val FREQUENCY_SCALE = 1_000.0
        private const val EXACT_KEY_BONUS = 2_000
        private const val BUNDLED_BIGRAM_WEIGHT = 40
        private const val LEARNED_BIGRAM_WEIGHT = 35
        private const val LITERAL_TRANSLITERATION_SCORE = Int.MAX_VALUE - 1

        private val BY_SCORE_DESCENDING = Comparator<Candidate> { a, b -> b.score.compareTo(a.score) }

        /** Devanagari digits helper reused by the IME when committing numbers. */
        fun localizeDigits(text: String, devanagari: Boolean): String =
            if (devanagari) Devanagari.digitsToDevanagari(text) else text
    }
}
