package np.com.nepalikeyboard.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny local crash recorder. The keyboard has no network access, so a crash used to vanish without a trace.
 *
 *  * Java/Kotlin crashes are written (with the stack trace) to `files/crash.log` before the process dies.
 *  * Native crashes / ANRs / low-memory kills cannot be caught in-process, so on API 30+ the platform's
 *    `ApplicationExitInfo` history is appended at the next start.
 *
 * The log is shown, copyable and shareable in Settings -> More -> Crash log.
 */
object CrashLog {
    private const val FILE = "crash.log"
    private const val MAX_BYTES = 48 * 1024
    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                append(app, "FATAL on thread '${thread.name}'\n${error.stackTraceToString()}")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
        try {
            recordPriorExits(app)
        } catch (_: Throwable) {
        }
    }

    /** Non-fatal problems (engine load failures, caught coroutine errors). */
    fun note(context: Context, what: String, error: Throwable? = null) {
        try {
            append(context.applicationContext, "NOTE $what" + (error?.let { "\n" + it.stackTraceToString() } ?: ""))
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    private fun append(context: Context, body: String) {
        val f = File(context.filesDir, FILE)
        val header = "[$stamp] v${versionOf(context)} Android ${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}\n"
        var text = (if (f.exists()) f.readText() else "") + header + body.trim() + "\n\n"
        if (text.length > MAX_BYTES) text = text.takeLast(MAX_BYTES)
        f.writeText(text)
    }

    private fun versionOf(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun recordPriorExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val seen = File(context.filesDir, "exit.seen")
        val last = seen.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
        var newest = last
        for (info in am.getHistoricalProcessExitReasons(context.packageName, 0, 6)) {
            if (info.timestamp <= last) continue
            newest = maxOf(newest, info.timestamp)
            val abnormal = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                -> true
                else -> false
            }
            if (!abnormal) continue
            val sb = StringBuilder("PRIOR EXIT process=${info.processName} reason=${reasonName(info.reason)} status=${info.status} importance=${info.importance} rss=${info.rss / 1024}KB\n")
            info.description?.let { sb.append("description: ").append(it).append('\n') }
            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == ApplicationExitInfo.REASON_ANR) {
                try {
                    info.traceInputStream?.bufferedReader()?.use { r -> sb.append(r.readText().take(6000)) }
                } catch (_: Throwable) {
                }
            }
            append(context, sb.toString())
        }
        if (newest > last) seen.writeText(newest.toString())
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCES"
        else -> r.toString()
    }

    fun read(context: Context): String = try {
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty()
    } catch (_: Throwable) {
        ""
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE).delete()
        } catch (_: Throwable) {
        }
    }
}
