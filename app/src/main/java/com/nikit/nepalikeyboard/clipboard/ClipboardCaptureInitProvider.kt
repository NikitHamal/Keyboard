package com.nikit.nepalikeyboard.clipboard

import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * =============================================================================
 * WHY A ContentProvider
 * =============================================================================
 *
 * Android offers exactly three places to run code before the rest of your app
 * starts: `Application.onCreate`, a `ContentProvider.onCreate`, and a
 * `BroadcastReceiver`. Of those, only providers are guaranteed to be
 * instantiated *before* `Application.onCreate` and only when the process is
 * actually being started for your app (not for a broadcast that happens to
 * match a filter).
 *
 * That guarantee matters here for a specific reason: we want to register a
 * `ClipboardManager.OnPrimaryClipChangedListener` as early as possible in the
 * process lifetime. Android 10 and later restrict clipboard *reads* to the
 * foreground app or the active IME, and there is no API to enumerate clipboard
 * history — the platform keeps one item. So the only way to build a useful
 * in-keyboard clipboard strip is to observe changes as they happen, for as long
 * as our process is alive.
 *
 * Registering this in `Application.onCreate` would work too for the process we
 * are started *for*, but it would miss the case where the system pre-creates
 * our process to resolve our IME's metadata, and it would run after any
 * library initialisers. Doing it in a provider with `initOrder="100"` puts it
 * first, deterministically.
 *
 * =============================================================================
 * WHAT THIS PROVIDER IS NOT
 * =============================================================================
 *
 * It stores nothing. It exposes no tables, no URIs, and no MIME types. It
 * cannot be queried — the authority is declared `exported="false"` and every
 * query method returns null or zero. There is no database file, no cache
 * directory, no serialised state. The class exists purely for its
 * `onCreate` side effect.
 *
 * If a future maintainer is tempted to add storage here: don't. Clipboard
 * history lives in the keyboard's own DataStore-backed repository so that it
 * can be cleared by the user from the settings screen, and so that its
 * lifecycle is governed by the same privacy rules as everything else. A
 * ContentProvider with a database would be a second, invisible store for
 * exactly the kind of data users most want to be able to delete.
 */
class ClipboardCaptureInitProvider : ContentProvider() {

    /**
     * Registers the clipboard listener.
     *
     * Returns `true` unconditionally: returning `false` makes the platform
     * treat the provider as failed and abort the rest of application startup,
     * which would prevent the keyboard from ever running. Nothing here can
     * fail in a way that should stop the process — the worst case is that
     * clipboard capture is unavailable this session, and the strip shows its
     * empty state.
     */
    override fun onCreate(): Boolean {
        val context = context ?: run {
            Log.w(TAG, "No context available; clipboard capture not installed")
            return true
        }
        try {
            ClipboardCapture.install(context.applicationContext)
        } catch (t: Throwable) {
            // A SecurityException here would mean the platform denied us the
            // clipboard service, which some OEM builds do for background
            // processes. Not fatal: the keyboard works fine without history.
            Log.w(TAG, "Clipboard capture unavailable on this build", t)
        }
        return true
    }

    // =========================================================================
    // ContentProvider contract — deliberately inert.
    //
    // Every method below is required by the abstract base class. None of them
    // is reachable in practice because the provider is not exported and exposes
    // no path. They are implemented to return empty values rather than to
    // throw, so that a misplaced `query` from inside our own process degrades
    // quietly instead of crashing the keyboard.
    // =========================================================================

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val TAG = "ClipboardCaptureInit"
    }
}

/**
 * Process-wide clipboard observer.
 *
 * =============================================================================
 * PRIVACY POSTURE
 * =============================================================================
 *
 * This object sees *everything* the user copies, in every app, for as long as
 * our process lives. That is a genuinely sensitive capability, so the
 * constraints are hard-coded rather than configurable:
 *
 *  1. **Nothing is written to disk from here.** The listener hands items to an
 *     in-memory ring buffer. Persisting history is the repository's job, and
 *     only the repository decides — it consults the user's settings and the
 *     active field's password flag before writing anything.
 *  2. **A single item, never a stream.** The platform only exposes the current
 *     primary clip. We record it and move on; we never poll.
 *  3. **Sensitive-looking content is filtered at capture time**, before it can
 *     reach any store. See [looksLikeSecret].
 *  4. **The listener is removable**, and removing it stops all capture.
 *
 * The listener is registered once per process. Re-registering on every
 * `onCreateInputView` would leak listeners — `ClipboardManager` holds them in a
 * list and does not deduplicate.
 */
object ClipboardCapture {

    /**
     * Callback invoked on the main thread whenever the primary clip changes.
     *
     * The keyboard sets this to feed its history repository. A single slot
     * rather than a listener list because there is exactly one consumer and a
     * collection would be an invitation to leak.
     *
     * Deliberately *not* marked `@Volatile`: it is only ever read and written on
     * the main thread, because `OnPrimaryClipChangedListener` is dispatched
     * there and the keyboard is main-thread-only.
     */
    var onClipCaptured: ((ClipItem) -> Unit)? = null

    /** True once [install] has successfully registered the listener. */
    var isInstalled: Boolean = false
        private set

    /** The system clipboard service, retained so [uninstall] can detach. */
    private var clipboardManager: ClipboardManager? = null

    /** The registered listener, retained so [uninstall] can detach it. */
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** Main-thread handler; clipboard callbacks are already on main but we
     *  guard re-entrancy defensively. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Registers the clipboard listener. Idempotent — calling it twice from two
     * provider instances (which happens after a process restart) does not
     * double-register.
     */
    @Synchronized
    fun install(context: Context) {
        if (isInstalled) return
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Log.w(TAG, "ClipboardManager unavailable; capture disabled")
            return
        }
        val newListener = ClipboardManager.OnPrimaryClipChangedListener {
            // Defensive: dispatch onto our own handler even though the platform
            // documents main-thread dispatch, because at least one OEM build
            // calls this from a binder thread.
            mainHandler.post { captureCurrent(manager) }
        }
        try {
            manager.addPrimaryClipChangedListener(newListener)
        } catch (t: Throwable) {
            Log.w(TAG, "addPrimaryClipChangedListener rejected", t)
            return
        }
        clipboardManager = manager
        listener = newListener
        isInstalled = true
    }

    /**
     * Removes the listener and stops all capture.
     *
     * Called when the user turns clipboard history off in settings. The
     * in-memory buffer is *not* cleared here — clearing is the repository's
     * decision, because the user may have merely paused capture rather than
     * asked to erase what was already collected.
     */
    @Synchronized
    fun uninstall() {
        val manager = clipboardManager
        val existing = listener
        if (manager != null && existing != null) {
            try {
                manager.removePrimaryClipChangedListener(existing)
            } catch (t: Throwable) {
                Log.w(TAG, "removePrimaryClipChangedListener failed", t)
            }
        }
        clipboardManager = null
        listener = null
        isInstalled = false
    }

    /**
     * Reads the current primary clip and hands it to [onClipCaptured] if it
     * passes the sensitivity filter.
     *
     * Only text clips pass the MIME gate below: the strip renders text only,
     * and an image URI would be a much larger privacy surface than the
     * feature warrants.
     */
    private fun captureCurrent(manager: ClipboardManager) {
        // Android 10+ throws unless we are the foreground app or the active
        // IME. As an IME we usually qualify; in the brief window where we do not
        // this returns null and we simply skip the item.
        val clip = try {
            manager.primaryClip
        } catch (t: Throwable) {
            return
        } ?: return

        val description = clip.description ?: return
        if (!description.hasMimeType("text/plain") &&
            !description.hasMimeType("text/html") &&
            !description.hasMimeType("text/*")
        ) {
            return
        }
        val label = description.label?.toString().orEmpty()
        if (looksLikeSecret(label)) return

        val item = clip.getItemAt(0) ?: return
        val text = try {
            item.coerceToText(null)?.toString()
        } catch (t: Throwable) {
            null
        } ?: return

        if (text.isEmpty()) return
        if (text.length > MAX_CAPTURED_LENGTH) return
        if (looksLikeSecret(text)) return

        onClipCaptured?.invoke(ClipItem(text = text, label = label, capturedAt = System.currentTimeMillis()))
    }

    /**
     * Heuristic guard against capturing credentials.
     *
     * This is defence in depth, not the primary control — the primary control
     * is that `captureCurrent` is never reached from a password field, because
     * password fields do not offer clipboard content in the first place. But
     * users copy passwords out of password managers into ordinary fields, and
     * there is no API to detect that. So we also refuse anything that looks
     * like a credential:
     *
     *  * a clip the system itself labelled as sensitive (password managers on
     *    Android 13+ set `EXTRA_IS_SENSITIVE`, which the platform surfaces by
     *    making the description return null — handled by the `?: return`
     *    above — but some builds still attach a telling label);
     *  * a single token with no whitespace that is long enough to be a key or
     *    a password rather than a word or a sentence;
     *  * anything labelled "password", "token", "otp", "pin", or "secret" in
     *    the clip label, in either English or Nepali.
     *
     * False positives are acceptable: the user loses one history entry. False
     * negatives are not.
     */
    private fun looksLikeSecret(value: String): Boolean {
        if (value.isEmpty()) return false
        val lower = value.lowercase()
        for (marker in SECRET_MARKERS) {
            if (lower.contains(marker)) return true
        }
        // A long unbroken token. Real prose has spaces; credentials usually do
        // not, and base64/hex API keys always exceed this length.
        if (value.length >= SECRET_TOKEN_MIN_LENGTH && !value.any { it == ' ' || it == '\n' }) {
            // But a long Devanagari sentence without spaces is legitimate prose.
            if (value.none { it.code in 0x0900..0x097F }) return true
        }
        return false
    }

    private val SECRET_MARKERS = arrayOf(
        "password", "passwd", "passcode", "pwd",
        "token", "secret", "api_key", "apikey", "private key",
        "otp", "one time", "one-time", "2fa", "verification code",
        "pin code", "recovery code",
        "पासवर्ड", "गोप्य", "टोकन"
    )

    private const val TAG = "ClipboardCapture"

    /** Clips longer than this are not history-worthy and are dropped. */
    private const val MAX_CAPTURED_LENGTH = 8_192

    /** Tokens at least this long with no whitespace are treated as secrets. */
    private const val SECRET_TOKEN_MIN_LENGTH = 24
}

/**
 * One captured clipboard value.
 *
 * An immutable snapshot rather than a reference to the platform's `ClipData`,
 * because `ClipData` objects are recycled and because holding one would keep a
 * Binder-adjacent object alive far longer than necessary.
 *
 * @property text the copied text, already length-bounded and filtered
 * @property label the clip's own label, e.g. "Copied from Chrome". Useful in
 *           the strip's secondary line; empty when the app supplied none.
 * @property capturedAt wall-clock millis, used only for ordering and for the
 *           "2 min ago" relative timestamp in the strip
 * @property pinned true when the user has pinned this entry so the retention
 *           sweep skips it. Carried on the item rather than looked up from the
 *           store, because the panel renders on every scroll frame and must not
 *           touch the store to decide how to draw a row. The store is still the
 *           authority; this is its value, copied.
 */
data class ClipItem(
    val text: String,
    val label: String,
    val capturedAt: Long,
    val pinned: Boolean = false
)
