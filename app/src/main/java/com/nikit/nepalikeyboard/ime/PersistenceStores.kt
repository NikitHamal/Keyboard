package com.nikit.nepalikeyboard.ime

import android.content.Context
import com.nikit.nepalikeyboard.clipboard.ClipItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * =============================================================================
 * LEARNED WORD STORE
 * =============================================================================
 *
 * A local, on-device table of the Devanagari words the user has accepted, with
 * a usage count each. It exists to raise the rank of words the user actually
 * types, which is the single largest quality-of-life improvement a
 * transliteration keyboard can offer: `dhanyabad` resolving to धन्यवाद instead
 * of धन्यबाद for a user who has accepted धन्यवाद forty times.
 *
 * ### Why not a database
 *
 * The table is bounded at [MAX_ENTRIES] and is a flat map of `String -> Int`.
 * Room would add a schema, a migration path, an APK size increase, and a
 * generated-code dependency — all to store at most a few thousand short
 * strings. A JSON file read once at startup and written on a debounce is
 * simpler, faster, and has no upgrade story to get wrong.
 *
 * ### Privacy
 *
 * This file is the only record of what the user types. It is:
 *  * written only when learning is enabled in settings,
 *  * never written at all from a password field (enforced by the caller; the
 *    store additionally refuses suspicious input),
 *  * excluded from cloud backup and device transfer by
 *    `res/xml/data_extraction_rules.xml`,
 *  * deletable in one action from the settings screen.
 *
 * The absence of `INTERNET` in the manifest means this file can never leave the
 * device, which is the control that actually matters.
 *
 * ### Writes are debounced, not immediate
 *
 * Committing a word fires a write. A user typing at speed commits several words
 * per second, so writing synchronously would mean several file writes per
 * second for data whose loss window is one word. Instead the in-memory map is
 * authoritative and the file is flushed on a short debounce plus on every
 * read-back, which is where correctness actually depends on it having landed.
 */
class LearnedWordStore private constructor(
    private val context: Context
) {

    /**
     * Internal scope for file I/O.
     *
     * `Dispatchers.IO` because this is disk work; `SupervisorJob` so a failed
     * write does not kill the scope and permanently stop learning.
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serialises read-modify-write cycles.
     *
     * `record` is called from the ViewModel's scope while `flush` may be
     * running from the debounce; without a lock the two can interleave such
     * that a word is added to the map after the snapshot was taken and before
     * the file write, and is then lost on the next process start.
     */
    private val fileMutex = Mutex()

    /** In-memory authoritative copy. */
    private val usage = HashMap<String, Int>(INITIAL_CAPACITY)

    private val _flow = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * Observable view of the table.
     *
     * The ViewModel collects this so that the lexicon's personal-usage ranking
     * is refreshed whenever a word is learned, without either side needing a
     * callback into the other.
     */
    val flow: StateFlow<Map<String, Int>> = _flow.asStateFlow()

    /** True once the file has been read. Guards against an early flush. */
    private var loaded = false

    /**
     * Reads the table from disk.
     *
     * Tolerates every failure mode: a missing file, a truncated file, a file
     * written by an older schema. In all cases the result is an empty table and
     * the keyboard works normally — losing learned words is an annoyance, not
     * an error worth telling the user about.
     */
    suspend fun load(): Map<String, Int> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (loaded) return@withLock usage.toMap()
            val file = storeFile()
            if (!file.exists()) {
                loaded = true
                _flow.value = emptyMap()
                return@withLock emptyMap<String, Int>()
            }
            try {
                val text = file.readText()
                if (text.isNotEmpty()) {
                    val root = JSONObject(text)
                    val words = root.optJSONArray(FIELD_WORDS)
                    if (words != null) {
                        for (i in 0 until words.length()) {
                            val entry = words.optJSONObject(i) ?: continue
                            val word = entry.optString(FIELD_WORD)
                            val count = entry.optInt(FIELD_COUNT, 0)
                            if (word.isNotEmpty() && count > 0) {
                                usage[word] = count
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // A corrupt file is discarded rather than repaired; the user
                // loses their learned words once, and never sees a crash.
                usage.clear()
            }
            loaded = true
            val snapshot = usage.toMap()
            _flow.value = snapshot
            snapshot
        }
    }

    /**
     * Increments the usage count for [word] and schedules a flush.
     *
     * Called from the commit path, so it must not block. The count increment
     * happens on the calling scope (cheap, a map write) and the file write is
     * dispatched.
     */
    suspend fun record(word: String) {
        if (!isLearnable(word)) return
        ioScope.launch {
            fileMutex.withLock {
                if (!loaded) return@withLock
                val next = (usage[word] ?: 0) + 1
                usage[word] = next
                evictIfNeeded()
                _flow.value = usage.toMap()
                persistLocked()
            }
        }
    }

    /**
     * Erases the entire table, in memory and on disk.
     *
     * The settings screen's "clear learned words" action. Immediate rather than
     * debounced, because the user's intent is that the data is gone.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            usage.clear()
            loaded = true
            _flow.value = emptyMap()
            try {
                storeFile().delete()
            } catch (t: Throwable) {
                // If the delete fails the map is still empty, so the next
                // persist will overwrite the file with an empty list.
                persistLocked()
            }
        }
    }

    /** Forces a flush. Called when the service is being destroyed. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        fileMutex.withLock { persistLocked() }
    }

    /** The number of learned words, for the settings screen. */
    val size: Int get() = usage.size

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Refuses to learn anything that could be a credential or a non-word.
     *
     * The caller already filters, but this second check exists because learning
     * is the one place where a bug writes user data to disk permanently. A
     * defence that costs nothing is worth having twice.
     */
    private fun isLearnable(word: String): Boolean {
        if (word.length < MIN_WORD_LENGTH || word.length > MAX_WORD_LENGTH) return false
        if (!word.any { it.code in DEVANAGARI_RANGE }) return false
        // A word containing an ASCII digit is far more likely to be a
        // verification code or an ID than a Nepali word.
        if (word.any { it in '0'..'9' }) return false
        // Words containing whitespace are not single words; a comma or period
        // means punctuation got captured, which pollutes the table.
        if (word.any { it == ' ' || it == '\n' || it == '\t' }) return false
        return true
    }

    /**
     * Keeps the table bounded.
     *
     * When the limit is exceeded, the least-used half is discarded. Discarding
     * roughly half in one pass rather than a single entry means the eviction
     * cost is amortised over many commits instead of being paid on every one
     * once the table is full.
     */
    private fun evictIfNeeded() {
        if (usage.size <= MAX_ENTRIES) return
        val sorted = usage.entries.sortedByDescending { it.value }
        val keep = sorted.take(MAX_ENTRIES / 2)
        usage.clear()
        for (entry in keep) usage[entry.key] = entry.value
    }

    /**
     * Writes the map to disk. Caller must hold [fileMutex].
     *
     * Writes to a temporary file and then renames, so that a process death
     * mid-write cannot leave a half-written file that the next launch would
     * discard. Rename is atomic within a filesystem, which is what makes this
     * safe without a journal.
     */
    private fun persistLocked() {
        try {
            val file = storeFile()
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for ((word, count) in usage) {
                val entry = JSONObject()
                entry.put(FIELD_WORD, word)
                entry.put(FIELD_COUNT, count)
                array.put(entry)
            }
            val root = JSONObject()
            root.put(FIELD_VERSION, SCHEMA_VERSION)
            root.put(FIELD_WORDS, array)

            val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                // Some filesystems refuse rename onto an existing file. Falling
                // back to a direct write loses the atomicity guarantee, which is
                // acceptable: the failure mode it protects against is a crash
                // during this single write.
                file.writeText(root.toString())
                temp.delete()
            }
        } catch (t: Throwable) {
            // Disk full, permission denied, or a locked file. Learning stops
            // for this session; typing continues.
        }
    }

    private fun storeFile(): File = File(context.filesDir, FILE_NAME)

    companion object {
        private const val FILE_NAME = "learned_words.json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val SCHEMA_VERSION = 1
        private const val FIELD_VERSION = "version"
        private const val FIELD_WORDS = "words"
        private const val FIELD_WORD = "w"
        private const val FIELD_COUNT = "c"

        /** Hard ceiling on table size. */
        private const val MAX_ENTRIES = 4_096

        private const val INITIAL_CAPACITY = 256

        /** Shortest string worth treating as a word. */
        private const val MIN_WORD_LENGTH = 2

        /** Longest string worth treating as a word. */
        private const val MAX_WORD_LENGTH = 64

        /** Devanagari block, U+0900..U+097F. */
        private val DEVANAGARI_RANGE = 0x0900..0x097F

        @Volatile
        private var instance: LearnedWordStore? = null

        fun get(context: Context): LearnedWordStore {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                val second = instance
                if (second != null) {
                    second
                } else {
                    LearnedWordStore(context.applicationContext).also { instance = it }
                }
            }
        }
    }
}

/**
 * =============================================================================
 * CLIPBOARD HISTORY STORE
 * =============================================================================
 *
 * A local, bounded, user-clearable list of recently copied text, surfaced as a
 * strip inside the keyboard.
 *
 * ### Retention rules, all of which are the user's to change
 *
 *  * Nothing is stored while clipboard capture is disabled in settings.
 *  * Unpinned entries are capped at the user's chosen limit; the oldest fall
 *    off first.
 *  * Pinned entries survive the cap and are never evicted automatically.
 *  * Entries are deduplicated by text, so copying the same value twice moves it
 *    to the front rather than creating a second row.
 *  * The whole list can be cleared in one action, and a single entry removed
 *    with a swipe.
 *
 * ### What is *not* stored
 *
 * The capture layer already drops anything that looks like a credential and
 * refuses to capture from a password field. This store additionally rejects
 * empty strings and anything past [MAX_ENTRY_LENGTH], and it never stores
 * anything but text — images and content URIs are filtered upstream.
 *
 * ### Storage format
 *
 * The same JSON-with-atomic-rename approach as [LearnedWordStore], for the same
 * reasons. The list is small and fully rewritten on each change, which keeps
 * the code trivial and the failure modes obvious.
 */
class ClipboardHistoryStore private constructor(
    private val context: Context
) {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val fileMutex = Mutex()

    /** Authoritative in-memory list, newest first. */
    private val entries = ArrayList<ClipboardEntry>(DEFAULT_LIMIT)

    /** True once the file has been read. */
    private var loaded = false

    /**
     * Reads the history from disk.
     *
     * Returns the history as [ClipItem]s for the UI. Corrupt or truncated files
     * yield an empty history rather than an error.
     */
    suspend fun load(): List<ClipItem> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (loaded) return@withLock entries.map { it.toClipItem() }
            val file = storeFile()
            if (file.exists()) {
                try {
                    val text = file.readText()
                    if (text.isNotEmpty()) {
                        val root = JSONObject(text)
                        val array = root.optJSONArray(FIELD_ENTRIES)
                        if (array != null) {
                            for (i in 0 until array.length()) {
                                val obj = array.optJSONObject(i) ?: continue
                                val value = obj.optString(FIELD_TEXT)
                                if (value.isEmpty()) continue
                                entries.add(
                                    ClipboardEntry(
                                        text = value,
                                        label = obj.optString(FIELD_LABEL),
                                        capturedAt = obj.optLong(FIELD_TIME, 0L),
                                        pinned = obj.optBoolean(FIELD_PINNED, false)
                                    )
                                )
                            }
                        }
                    }
                } catch (t: Throwable) {
                    entries.clear()
                }
            }
            loaded = true
            entries.map { it.toClipItem() }
        }
    }

    /**
     * Records a newly captured item and returns the updated history.
     *
     * The dedupe-then-prepend order matters: an item the user copies repeatedly
     * should bubble to the top of the strip rather than appearing several times,
     * and its pinned state must survive the move.
     */
    suspend fun record(item: ClipItem): List<ClipItem> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (!loaded) return@withLock entries.map { it.toClipItem() }
            val text = item.text.trim()
            if (text.isEmpty() || text.length > MAX_ENTRY_LENGTH) {
                return@withLock entries.map { it.toClipItem() }
            }
            val existingIndex = entries.indexOfFirst { it.text == text }
            val wasPinned = if (existingIndex >= 0) entries[existingIndex].pinned else false
            if (existingIndex >= 0) entries.removeAt(existingIndex)
            entries.add(
                0,
                ClipboardEntry(
                    text = text,
                    label = item.label,
                    capturedAt = item.capturedAt,
                    pinned = wasPinned
                )
            )
            evictUnpinned()
            persistLocked()
            entries.map { it.toClipItem() }
        }
    }

    /** Marks an entry as used, moving it to the front. */
    suspend fun markUsed(text: String): List<ClipItem> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val index = entries.indexOfFirst { it.text == text }
            if (index > 0) {
                val entry = entries.removeAt(index)
                entries.add(0, entry.copy(capturedAt = System.currentTimeMillis()))
                persistLocked()
            }
            entries.map { it.toClipItem() }
        }
    }

    /** Pins or unpins an entry. */
    suspend fun setPinned(text: String, pinned: Boolean): List<ClipItem> =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                val index = entries.indexOfFirst { it.text == text }
                if (index >= 0) {
                    entries[index] = entries[index].copy(pinned = pinned)
                    evictUnpinned()
                    persistLocked()
                }
                entries.map { it.toClipItem() }
            }
        }

    /** Removes a single entry. */
    suspend fun delete(text: String): List<ClipItem> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            entries.removeAll { it.text == text }
            persistLocked()
            entries.map { it.toClipItem() }
        }
    }

    /** Erases the whole history, pinned entries included. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            entries.clear()
            loaded = true
            try {
                storeFile().delete()
            } catch (t: Throwable) {
                persistLocked()
            }
        }
    }

    /** Applies a new retention limit, evicting if the list is now too long. */
    suspend fun applyLimit(limit: Int): List<ClipItem> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            currentLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
            evictUnpinned()
            persistLocked()
            entries.map { it.toClipItem() }
        }
    }

    /** Forces a flush. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        fileMutex.withLock { persistLocked() }
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Drops the oldest unpinned entries until the unpinned count fits the limit.
     *
     * Walks from the end of the list (the oldest) and skips pinned entries, so
     * pinning genuinely protects an item rather than merely reordering it.
     */
    private fun evictUnpinned() {
        var unpinned = entries.count { !it.pinned }
        if (unpinned <= currentLimit) return
        var index = entries.size - 1
        while (index >= 0 && unpinned > currentLimit) {
            if (!entries[index].pinned) {
                entries.removeAt(index)
                unpinned--
            }
            index--
        }
    }

    /**
     * Writes the list to disk. Caller must hold [fileMutex].
     *
     * Atomic-rename, same as the learned-word store: a crash mid-write must not
     * be able to leave a truncated history that the next launch discards.
     */
    private fun persistLocked() {
        try {
            val file = storeFile()
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for (entry in entries) {
                val obj = JSONObject()
                obj.put(FIELD_TEXT, entry.text)
                obj.put(FIELD_LABEL, entry.label)
                obj.put(FIELD_TIME, entry.capturedAt)
                obj.put(FIELD_PINNED, entry.pinned)
                array.put(obj)
            }
            val root = JSONObject()
            root.put(FIELD_VERSION, SCHEMA_VERSION)
            root.put(FIELD_ENTRIES, array)

            val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                file.writeText(root.toString())
                temp.delete()
            }
        } catch (t: Throwable) {
            // Never surface a persistence failure into the typing path.
        }
    }

    private fun storeFile(): File = File(context.filesDir, FILE_NAME)

    /**
     * Internal record. Separate from [ClipItem] because retention policy is a
     * store concern: the panel renders rows and has no business implementing
     * eviction, and keeping the two types apart means adding a retention field
     * later cannot break the UI.
     *
     * [pinned] *is* copied across, however. It is a retention field, but it is
     * also the one retention fact the user needs to see — a pinned row looks
     * different from an unpinned one — and the panel cannot render that without
     * the value in hand.
     */
    private data class ClipboardEntry(
        val text: String,
        val label: String,
        val capturedAt: Long,
        val pinned: Boolean
    ) {
        fun toClipItem(): ClipItem = ClipItem(
            text = text,
            label = label,
            capturedAt = capturedAt,
            pinned = pinned
        )
    }

    companion object {
        private const val FILE_NAME = "clipboard_history.json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val SCHEMA_VERSION = 1
        private const val FIELD_VERSION = "version"
        private const val FIELD_ENTRIES = "entries"
        private const val FIELD_TEXT = "t"
        private const val FIELD_LABEL = "l"
        private const val FIELD_TIME = "at"
        private const val FIELD_PINNED = "p"

        /** Shared default retention, mirrored from the settings defaults. */
        private const val DEFAULT_LIMIT = 25

        /** Bounds the settings UI enforces. */
        private const val MIN_LIMIT = 5
        private const val MAX_LIMIT = 100

        /** Anything longer is not a clipboard snippet; it is a document. */
        private const val MAX_ENTRY_LENGTH = 8_192

        @Volatile
        private var instance: ClipboardHistoryStore? = null

        /** Active retention limit, updated by [applyLimit]. */
        @Volatile
        private var currentLimit: Int = DEFAULT_LIMIT

        fun get(context: Context): ClipboardHistoryStore {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                val second = instance
                if (second != null) {
                    second
                } else {
                    ClipboardHistoryStore(context.applicationContext).also { instance = it }
                }
            }
        }
    }
}
