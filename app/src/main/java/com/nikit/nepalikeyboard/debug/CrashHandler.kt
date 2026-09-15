package com.nikit.nepalikeyboard.debug

import android.app.Application
import android.content.Context
import android.util.Log
import java.lang.ref.WeakReference

/**
 * =============================================================================
 * CRASH HANDLER
 * =============================================================================
 *
 * Installs a process-wide `UncaughtExceptionHandler` that records the trace via
 * [CrashStore] before the process dies, then delegates to the platform handler
 * so the system still shows its own dialog and the app still exits normally.
 *
 * ### Why we delegate rather than swallow
 *
 * It is tempting to catch the exception and keep running — "the keyboard must
 * never die". That is the wrong instinct. A thread that has thrown an uncaught
 * exception is in an arbitrary state; the main thread may be halfway through
 * attaching a view, holding a lock, or mutating the ViewModel. Continuing would
 * turn a clean, reproducible crash into a hung keyboard or a corrupted state.
 * We record, then we let Android do what Android does.
 *
 * ### The handoff problem, and how this solves it
 *
 * An `InputMethodService` crash is invisible. By the time the trace is on disk
 * the process is gone, so there is nothing left to draw a screen with. The
 * report can only be *shown* on the next launch. This class therefore leaves a
 * durable breadcrumb — the report file itself — and the app checks for it when
 * the settings screen opens. See [CrashStore.lastCrash] and
 * `SettingsActivity`'s pending-crash check.
 *
 * ### Storage of the previous handler
 *
 * Some test harnesses and profilers install their own handler and rely on being
 * called. We chain to whatever was installed before us rather than to
 * `Thread.getDefaultUncaughtExceptionHandler()` unconditionally at crash time,
 * so we never silently disable someone else's reporter.
 */
object CrashHandler {

    private const val TAG = "CrashHandler"

    /**
     * Held weakly on purpose. A strong reference to the `Application` from a
     * static field is the classic leak, and it would keep the process alive
     * after the system asked it to die — which for an IME means the user sees a
     * stale keyboard welded to their screen.
     */
    private var appRef: WeakReference<Application>? = null

    /** Set once so a double install cannot cause infinite chaining. */
    @Volatile
    private var installed = false

    /**
     * Installs the handler. Safe to call more than once; later calls are no-ops.
     *
     * Must be called as early as possible in `Application.onCreate` — before
     * any other initialiser — because anything that runs before it is outside
     * our protection.
     */
    fun install(application: Application) {
        if (installed) return
        installed = true
        appRef = WeakReference(application)

        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handle(thread, throwable)

            // Chain onward. If `previous` is null the runtime's own handler will
            // be used by omission, which is the correct default behaviour.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Re-dispatch to the platform default so the process still dies
                // with a proper tombstone rather than merely returning from the
                // handler and leaving a half-dead process.
                exitProcess()
            }
        }

        Log.d(TAG, "Crash handler installed (previous=${previous?.javaClass?.name ?: "none"})")
    }

    /**
     * Records the crash and marks it as pending.
     *
     * Extracted so it can be unit-tested and so the lambda above stays trivial.
     * Wrapped in its own try/catch: if *this* throws we would recurse into the
     * handler and abort the process with no information at all.
     */
    private fun handle(thread: Thread, throwable: Throwable) {
        val app = appRef?.get()
        if (app == null) {
            Log.e(TAG, "Uncaught exception before Application was available", throwable)
            return
        }

        try {
            // Mirror to logcat first. If the disk write fails, logcat is the
            // only surviving evidence, so it must not be contingent on it.
            Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)

            val report = CrashStore.record(app, thread, throwable)
            PendingCrash.mark(app, report)
        } catch (t: Throwable) {
            Log.e(TAG, "Crash handler itself failed", t)
        }
    }

    /**
     * Terminates with the conventional exit code for an uncaught exception.
     *
     * `Runtime.getRuntime().halt(10)` rather than `exitProcess(10)`: `exit`
     * runs shutdown hooks on a thread we know is in an undefined state, and a
     * hook that blocks would leave the process alive but wedged. `halt` is
     * immediate, which is what a crash handler should do. Code 10 is what the
     * Android runtime itself uses.
     */
    private fun exitProcess() {
        Runtime.getRuntime().halt(10)
    }
}

/**
 * A one-shot flag telling the *next* launch that the previous one died.
 *
 * The crash screen is worthless if the user has to go looking for it. This is
 * how the app knows to surface it unprompted: the handler sets it, and
 * `SettingsActivity` consumes it the next time it starts.
 *
 * Kept in `SharedPreferences` rather than DataStore because the setter runs on
 * a dying thread. `SharedPreferences.Editor.commit()` is a synchronous write
 * with an in-memory change applied first, so it survives the process being
 * killed immediately afterwards — which a coroutine-based write would not.
 */
object PendingCrash {

    private const val PREFS = "nk_pending_crash"
    private const val KEY_PENDING = "pending"

    fun mark(context: Context, report: String) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING, true)
                // The report is stored, but the source of truth for display is
                // the file; this copy exists so the flag and its payload can
                // never desynchronise.
                .putString("report_preview", report.take(4096))
                .commit()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not mark pending crash", t)
        }
    }

    fun isPending(context: Context): Boolean =
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PENDING, false)
        } catch (t: Throwable) {
            false
        }

    /** Clears the flag once the user has seen the report. */
    fun consume(context: Context) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PENDING)
                .remove("report_preview")
                .commit()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not consume pending crash", t)
        }
    }

    private const val TAG = "PendingCrash"
}

/**
 * Guards against the app being killed while the crash screen is on top.
 *
 * A crash screen that is itself the thing crashing produces an infinite relaunch
 * loop — each launch shows the screen, the screen throws for the same reason,
 * the handler records it, and the loop tightens. This counts consecutive
 * showings within a short window and suppresses the automatic display past a
 * threshold, so the user can still reach Settings manually.
 */
object CrashScreenGuard {

    private const val PREFS = "nk_crash_guard"
    private const val KEY_COUNT = "count"
    private const val KEY_LAST = "last_shown_at"

    /** Window within which repeat showings count as a loop. */
    private const val WINDOW_MS = 60_000L

    /** After this many rapid showings we stop auto-opening. */
    private const val LIMIT = 3

    fun shouldAutoShow(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST, 0L)
        val count = prefs.getInt(KEY_COUNT, 0)

        val withinWindow = (now - last) < WINDOW_MS
        val nextCount = if (withinWindow) count + 1 else 1

        prefs.edit()
            .putInt(KEY_COUNT, nextCount)
            .putLong(KEY_LAST, now)
            .commit()

        return nextCount <= LIMIT
    }
}
