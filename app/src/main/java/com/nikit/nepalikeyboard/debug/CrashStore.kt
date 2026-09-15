package com.nikit.nepalikeyboard.debug

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * =============================================================================
 * CRASH STORE
 * =============================================================================
 *
 * Persists the most recent uncaught exceptions to disk so that a crash which
 * happens *outside* our own UI — and an IME crash always does, because the
 * keyboard has no screen of its own — is still inspectable afterwards.
 *
 * ### Why this exists at all
 *
 * The user's report was "the keyboard never opens, it crashes and I see no
 * crash logs". Both halves of that are explicable and both are addressed here:
 *
 *  * An `InputMethodService` is not an `Activity`. When it throws, the system
 *    shows a generic "Application Error" dialog that names the package and
 *    nothing else, then kills the process. There is no in-app surface to show
 *    the trace, and on many OEM ROMs the trace never reaches the user-visible
 *    log either.
 *  * A release build is minified by R8. Unless `SourceFile`/`LineNumberTable`
 *    are kept, the trace the user *does* see is a wall of single letters, which
 *    is indistinguishable from "no information at all". See proguard-rules.pro.
 *
 * So we write the trace to `filesDir/crashes/` the moment it happens, and the
 * debug screen reads it back. The write is done with plain `File` I/O rather
 * than DataStore deliberately: `UncaughtExceptionHandler` runs on the thread
 * that is already dying, and the process may be killed microseconds later. A
 * suspend-based API would simply never complete.
 *
 * ### Privacy
 *
 * Nothing leaves the device. There is no INTERNET permission to leave it with —
 * see the privacy contract in AndroidManifest.xml. These files are written to
 * internal storage and are readable only by this app and, on a debuggable
 * build, by `adb run-as`.
 */
object CrashStore {

    private const val TAG = "CrashStore"

    /** Directory under `filesDir`. Internal storage, app-private. */
    private const val DIR_NAME = "crashes"

    /**
     * How many traces to retain. Each is a few kilobytes, so this is far below
     * any storage concern; the cap exists so that a crash loop cannot fill the
     * disk. Oldest-first eviction.
     */
    private const val MAX_FILES = 10

    /** Single file name — the "last crash", overwritten every time. */
    private const val LAST_FILE = "last_crash.txt"

    /**
     * Guards the read-modify-write of the directory listing. The handler can in
     * principle run on more than one thread if a crash occurs during a crash.
     */
    private val lock = Any()

    /**
     * Formats a timestamp as a filename-safe stamp, e.g. `2026-09-15_19-47-13`.
     *
     * Deliberately not `SimpleDateFormat` on a shared instance: it is not
     * thread-safe, and the whole point of this class is to be called from a
     * thread that is about to die. A fresh instance per call avoids the hazard
     * entirely.
     */
    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    /**
     * Builds the human-readable report: exception, message, full stack, plus
     * the device facts that explain ROM-specific behaviour.
     *
     * The header matters more than it looks. IME bugs are overwhelmingly
     * device- and version-specific — the bug that produced this class was a
     * window-hosting difference in one OEM's framework — so a report without
     * `Build.*` is a report that cannot be acted on.
     */
    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val writer = StringWriter()
        val printer = PrintWriter(writer)
        printer.println("===== Nepali Keyboard crash report =====")
        printer.println("time      : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        printer.println("thread    : ${thread.name}")
        printer.println("exception : ${throwable.javaClass.name}")
        printer.println("message   : ${throwable.message}")
        printer.println()
        printer.println("--- device ---")
        printer.println("brand     : ${Build.BRAND}")
        printer.println("model     : ${Build.MODEL}")
        printer.println("device    : ${Build.DEVICE}")
        printer.println("manufacturer: ${Build.MANUFACTURER}")
        printer.println("android   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        printer.println("build id  : ${Build.DISPLAY}")
        printer.println()
        printer.println("--- stack trace ---")
        throwable.printStackTrace(printer)
        printer.flush()
        return writer.toString()
    }

    /**
     * Writes [throwable] to disk and returns the report that was written.
     *
     * Never throws. An exception handler that itself throws produces an
     * immediate, unrecoverable process abort, which would turn a diagnosable
     * crash into a silent one — the exact failure mode this class exists to
     * eliminate.
     */
    fun record(context: Context, thread: Thread, throwable: Throwable): String {
        val report = try {
            buildReport(thread, throwable)
        } catch (t: Throwable) {
            "Nepali Keyboard crash report\n(failed to format report: $t)\n"
        }

        try {
            val dir = directory(context)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Could not create crash directory ${dir.absolutePath}")
                return report
            }

            // `last_crash.txt` is what the debug screen shows; it is written
            // first because it is the one that must survive the process dying.
            File(dir, LAST_FILE).writeText(report)

            // Then a dated copy for history, subject to the retention cap.
            File(dir, "crash_${stamp()}.txt").writeText(report)
            prune(dir)
        } catch (t: Throwable) {
            // Storage full, permission denied, SELinux — all uninteresting
            // compared to the crash we are already handling.
            Log.e(TAG, "Could not persist crash report", t)
        }

        return report
    }

    /**
     * The most recent report, or null if nothing has ever crashed.
     *
     * Returns null before `Context` is available too, which is the case if the
     * process is killed during `Application.onCreate` — nothing can be shown
     * then anyway.
     */
    fun lastCrash(context: Context): String? {
        return try {
            val file = File(directory(context), LAST_FILE)
            if (file.exists()) file.readText() else null
        } catch (t: Throwable) {
            Log.e(TAG, "Could not read last crash", t)
            null
        }
    }

    /**
     * Every stored report, newest first.
     *
     * Sorted by last-modified rather than by filename so that the ordering
     * stays correct even if the device clock was adjusted between crashes.
     */
    fun allCrashes(context: Context): List<CrashEntry> {
        return try {
            val files = directory(context)
                .listFiles { file -> file.isFile && file.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            files.mapNotNull { file ->
                try {
                    CrashEntry(
                        name = file.name,
                        modifiedAt = file.lastModified(),
                        report = file.readText()
                    )
                } catch (t: Throwable) {
                    null
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not list crashes", t)
            emptyList()
        }
    }

    /** Deletes every stored report. Backs the debug screen's "Clear" action. */
    fun clear(context: Context) {
        try {
            directory(context).listFiles()?.forEach { it.delete() }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not clear crashes", t)
        }
    }

    /** True if a crash has ever been recorded. */
    fun hasCrash(context: Context): Boolean =
        try {
            File(directory(context), LAST_FILE).exists()
        } catch (t: Throwable) {
            false
        }

    /**
     * The crash directory.
     *
     * Not cached in a field: `Context` here is always the `Application`, but
     * caching a `File` would go stale across a backup/restore or a user profile
     * switch, and the cost of recomputing is a single path join.
     */
    private fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME)

    /** Enforces [MAX_FILES], oldest first. */
    private fun prune(dir: File) {
        synchronized(lock) {
            val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".txt") }
                ?.sortedBy { it.lastModified() }
                ?: return

            val excess = files.size - MAX_FILES
            if (excess > 0) {
                files.take(excess).forEach { it.delete() }
            }
        }
    }
}

/**
 * One stored crash report.
 *
 * A plain data holder rather than a `File` so that the UI never touches the
 * filesystem — a crash screen that itself performs I/O can deadlock behind the
 * same lock that produced the crash.
 */
data class CrashEntry(
    val name: String,
    val modifiedAt: Long,
    val report: String
)
