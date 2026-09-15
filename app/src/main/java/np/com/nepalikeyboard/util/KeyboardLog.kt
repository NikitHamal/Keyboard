package np.com.nepalikeyboard.util

import android.util.Log
import np.com.nepalikeyboard.BuildConfig

/**
 * Thin logging facade.
 *
 * Debug builds log to logcat; release builds compile out the call sites through
 * [BuildConfig.DEBUG] (a `const` for the release variant, so the branch is
 * folded away by R8).
 *
 * Security note: never log composed text, clipboard contents or learned words.
 * Only structural events (state swaps, load failures, timings) are logged.
 */
object KeyboardLog {

    private const val TAG = "NepaliKeyboard"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun w(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(TAG, message, throwable)
    }

    /** Always logs; reserved for conditions that must be visible in the field. */
    fun always(message: String) {
        Log.i(TAG, message)
    }
}
