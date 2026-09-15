package np.com.nepalikeyboard.data

import android.content.ClipData
import android.content.ClipboardManager
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
import java.io.IOException

private val Context.clipboardDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nepali_keyboard_clipboard",
)

/** One captured clipboard entry. Immutable and Compose-stable. */
@Immutable
data class ClipboardEntry(
    val text: String,
    val timestampMillis: Long,
    val pinned: Boolean = false,
) {
    /** Stable identity for list keys; text is unique by construction. */
    val id: String get() = text
}

/**
 * On-device clipboard history.
 *
 * Captures what the user copies anywhere on the device while the keyboard is
 * visible. Reading the primary clip needs no permission for the focused IME,
 * and nothing is ever transmitted. Observation is explicitly started/stopped by
 * the IME so the listener is not held while the keyboard is hidden.
 *
 * Sensitive fields: the IME calls [suspendObservation] semantics by simply not
 * starting observation for password editors, and never surfaces history there.
 */
class ClipboardRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _entries = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val entries: StateFlow<List<ClipboardEntry>> = _entries.asStateFlow()

    private val clipboardManager: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onPrimaryClipChanged() }

    private val flushRequests = Channel<Unit>(Channel.CONFLATED)
    private var observing = false
    private var lastCaptured: String? = null

    init {
        scope.launch { restore() }
        scope.launch {
            for (ignored in flushRequests) {
                // Coalesce bursts of copies into a single write.
                delay(FLUSH_DEBOUNCE_MS)
                persist()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Observation lifecycle
    // -----------------------------------------------------------------------

    /** Begins listening for clipboard changes. Safe to call repeatedly. */
    fun startObservation() {
        if (observing) return
        val manager = clipboardManager ?: return
        try {
            manager.addPrimaryClipChangedListener(clipListener)
            observing = true
            // Capture whatever is already on the clipboard when the keyboard opens.
            onPrimaryClipChanged()
        } catch (error: SecurityException) {
            KeyboardLog.w("Clipboard observation denied: ${error.message}")
        }
    }

    /** Stops listening; called when the input view is hidden. */
    fun stopObservation() {
        if (!observing) return
        val manager = clipboardManager ?: return
        try {
            manager.removePrimaryClipChangedListener(clipListener)
        } catch (_: IllegalArgumentException) {
            // Already removed; nothing to do.
        }
        observing = false
    }

    private fun onPrimaryClipChanged() {
        val manager = clipboardManager ?: return
        val text = try {
            val clip: ClipData? = manager.primaryClip
            if (clip == null || clip.itemCount == 0) null else {
                val item = clip.getItemAt(0)
                item.coerceToText(context)?.toString()
            }
        } catch (_: SecurityException) {
            // Android 10+ restricts clipboard reads to focused apps. The IME
            // normally has focus, but a transient state must not crash.
            null
        } ?: return

        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == lastCaptured) return
        lastCaptured = trimmed
        capture(trimmed, System.currentTimeMillis())
    }

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    fun capture(text: String, timestampMillis: Long = System.currentTimeMillis()) {
        val current = _entries.value
        if (current.isNotEmpty() && current[0].text == text && !current[0].pinned) {
            _entries.value = listOf(current[0].copy(timestampMillis = timestampMillis)) + current.drop(1)
        } else {
            val withoutDuplicate = current.filterNot { it.text == text }
            val entry = ClipboardEntry(text = text, timestampMillis = timestampMillis)
            val updated = (listOf(entry) + withoutDuplicate).take(MAX_ENTRIES)
            _entries.value = updated
        }
        requestFlush()
    }

    fun remove(entry: ClipboardEntry) {
        _entries.value = _entries.value.filterNot { it.id == entry.id }
        requestFlush()
    }

    fun togglePin(entry: ClipboardEntry) {
        _entries.value = _entries.value.map { item ->
            if (item.id == entry.id) item.copy(pinned = !item.pinned) else item
        }
        requestFlush()
    }

    fun clearAll() {
        _entries.value = emptyList()
        lastCaptured = null
        requestFlush()
    }

    /**
     * Pushes [text] back to the system clipboard (used by the panel's copy
     * action) and records it locally.
     */
    fun publishToSystemClipboard(text: String) {
        val manager = clipboardManager ?: return
        try {
            manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        } catch (_: SecurityException) {
            KeyboardLog.w("Unable to write primary clip")
        }
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private fun requestFlush() {
        flushRequests.trySend(Unit)
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        val payload = ClipboardPayloadDto(
            items = _entries.value.map { entry ->
                ClipboardEntryDto(
                    text = entry.text,
                    timestamp = entry.timestampMillis,
                    pinned = entry.pinned,
                )
            },
        )
        try {
            context.clipboardDataStore.edit { prefs ->
                prefs[ITEMS_KEY] = KeyboardJson.encodeToString(ClipboardPayloadDto.serializer(), payload)
            }
        } catch (error: IOException) {
            KeyboardLog.w("Clipboard persist failed: ${error.message}")
        }
    }

    private suspend fun restore() = withContext(Dispatchers.IO) {
        val payload = try {
            val prefs = context.clipboardDataStore.data.first()
            val raw = prefs[ITEMS_KEY] ?: return@withContext
            KeyboardJson.decodeFromString(ClipboardPayloadDto.serializer(), raw)
        } catch (error: Exception) {
            KeyboardLog.w("Clipboard restore failed: ${error.message}")
            return@withContext
        }
        val restored = payload.items
            .asSequence()
            .filter { it.text.isNotBlank() }
            .take(MAX_ENTRIES)
            .map { ClipboardEntry(it.text, it.timestamp, it.pinned) }
            .toList()
        _entries.value = restored
    }

    companion object {
        private const val MAX_ENTRIES = 40
        private const val FLUSH_DEBOUNCE_MS = 1_200L
        private const val CLIP_LABEL = "Nepali Keyboard"
        private val ITEMS_KEY = stringPreferencesKey("items_json")

        @Volatile
        private var instance: ClipboardRepository? = null

        fun get(context: Context): ClipboardRepository {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: ClipboardRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
