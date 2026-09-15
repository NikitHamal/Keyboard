package np.com.nepalikeyboard.ime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import np.com.nepalikeyboard.data.ClipboardRepository
import np.com.nepalikeyboard.data.EmojiHistoryRepository
import np.com.nepalikeyboard.data.LearningRepository
import np.com.nepalikeyboard.data.SettingsRepository
import np.com.nepalikeyboard.engine.Lexicon
import np.com.nepalikeyboard.util.KeyboardLog

/**
 * Process-wide services shared by the IME, the settings activity and the
 * sandbox.
 *
 * The lexicon is loaded exactly once per process, lazily, off the main thread.
 * Until it is ready the keyboard still works (deterministic transliteration and
 * literal candidates are produced without the dictionary) and the suggestion
 * strip simply shows fewer chips - it never blocks or shows a spinner.
 */
class ImeRuntime private constructor(
    private val context: Context,
) {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository = SettingsRepository.get(context)
    val clipboard: ClipboardRepository = ClipboardRepository.get(context)
    val emoji: EmojiHistoryRepository = EmojiHistoryRepository.get(context)
    val learning: LearningRepository = LearningRepository.get(context)

    @Volatile
    var lexicon: Lexicon? = null
        private set

    private val _lexiconReady = MutableStateFlow(false)
    val lexiconReady: StateFlow<Boolean> = _lexiconReady.asStateFlow()

    private val loadingLock = Any()
    @Volatile
    private var loading = false

    /**
     * Starts the one-time asset parse if it has not run yet. Safe to call from
     * every keystroke; the guard is a volatile read.
     */
    fun ensureLexicon() {
        if (lexicon != null || loading) return
        synchronized(loadingLock) {
            if (lexicon != null || loading) return
            loading = true
        }
        scope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                val loaded = Lexicon.load(context)
                lexicon = loaded
                _lexiconReady.value = true
                KeyboardLog.d(
                    "Lexicon loaded: ${loaded.size} words, ${loaded.bigrams.size} bigram heads " +
                        "in ${android.os.SystemClock.elapsedRealtime() - started} ms",
                )
            } catch (error: Exception) {
                // A failure here must not take the keyboard down: transliteration
                // and literal input keep working without the dictionary.
                KeyboardLog.e("Lexicon load failed", error)
                _lexiconReady.value = false
            } finally {
                loading = false
            }
        }
    }

    /** Blocking variant used by the settings sandbox and unit tests. */
    suspend fun awaitLexicon(): Lexicon? {
        ensureLexicon()
        lexicon?.let { return it }
        var waited = 0L
        while (lexicon == null && waited < LEXICON_WAIT_MS) {
            kotlinx.coroutines.delay(16L)
            waited += 16L
        }
        return lexicon
    }

    companion object {
        private const val LEXICON_WAIT_MS = 2_000L

        @Volatile
        private var instance: ImeRuntime? = null

        fun get(context: Context): ImeRuntime {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: ImeRuntime(context.applicationContext).also { instance = it }
            }
        }
    }
}
