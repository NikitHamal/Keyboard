package np.com.nepalikeyboard.data

import android.content.Context
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

private val Context.emojiDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nepali_keyboard_emoji",
)

/**
 * Recently used emoji, most-recent-first, persisted through DataStore.
 *
 * Bounded to [MAX_RECENTS] entries so the strip never grows without limit, and
 * de-duplicated so a much-loved emoji bubbles to the front instead of filling
 * the row.
 */
class EmojiHistoryRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    private val flushRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { restore() }
        scope.launch {
            for (ignored in flushRequests) {
                delay(FLUSH_DEBOUNCE_MS)
                persist()
            }
        }
    }

    fun record(emoji: String) {
        if (emoji.isEmpty()) return
        val current = _recents.value
        if (current.isNotEmpty() && current[0] == emoji) return
        _recents.value = (listOf(emoji) + current.filterNot { it == emoji }).take(MAX_RECENTS)
        flushRequests.trySend(Unit)
    }

    fun clear() {
        _recents.value = emptyList()
        flushRequests.trySend(Unit)
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        try {
            val payload = EmojiRecentsDto(emojis = _recents.value)
            context.emojiDataStore.edit { prefs ->
                prefs[RECENTS_KEY] = KeyboardJson.encodeToString(EmojiRecentsDto.serializer(), payload)
            }
        } catch (error: Exception) {
            KeyboardLog.w("Emoji recents persist failed: ${error.message}")
        }
    }

    private suspend fun restore() = withContext(Dispatchers.IO) {
        try {
            val prefs = context.emojiDataStore.data.first()
            val raw = prefs[RECENTS_KEY] ?: return@withContext
            val payload = KeyboardJson.decodeFromString(EmojiRecentsDto.serializer(), raw)
            _recents.value = payload.emojis.take(MAX_RECENTS)
        } catch (error: Exception) {
            KeyboardLog.w("Emoji recents restore failed: ${error.message}")
        }
    }

    companion object {
        private const val MAX_RECENTS = 48
        private const val FLUSH_DEBOUNCE_MS = 800L
        private val RECENTS_KEY = stringPreferencesKey("recents_json")

        @Volatile
        private var instance: EmojiHistoryRepository? = null

        fun get(context: Context): EmojiHistoryRepository {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: EmojiHistoryRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
