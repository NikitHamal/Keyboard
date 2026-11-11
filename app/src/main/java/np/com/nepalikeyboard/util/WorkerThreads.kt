package np.com.nepalikeyboard.util

import android.os.Process
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Process-wide inference thread.
 *
 * Candidate ranking must not queue behind unrelated `Dispatchers.Default` work and must not be starved by the UI
 * thread's neighbours, so every keystroke's TinyLM request runs on one dedicated thread lifted to display priority.
 */
object WorkerThreads {

    /** Raises the calling thread to the priority of the UI render thread. */
    fun boost() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {
            // Priority is an optimisation only.
        }
    }

    val inference: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread({
                boost()
                task.run()
            }, "tlm-worker").also { it.isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
