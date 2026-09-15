package np.com.nepalikeyboard

import android.app.Application
import np.com.nepalikeyboard.ime.ImeRuntime
import np.com.nepalikeyboard.util.KeyboardLog

/**
 * Application entry point.
 *
 * The work done here is deliberately tiny: the app has no network stack, no
 * analytics SDK, no background services and no content providers, so there is
 * nothing to initialise beyond warming the one asset that actually costs
 * something - the bundled Nepali lexicon.
 *
 * Warming the lexicon at process start means the first time the keyboard is
 * shown the radix trie is already in memory, and the suggestion strip is
 * populated from the very first keystroke. The parse itself happens on
 * [ImeRuntime]'s `Dispatchers.Default` scope, so `onCreate` returns immediately
 * and the main thread never touches the JSON.
 *
 * This class is registered as `android:name=".NepaliKeyboardApp"` in
 * `AndroidManifest.xml`.
 */
class NepaliKeyboardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val runtime = ImeRuntime.get(applicationContext)
        // Idempotent and non-blocking: repeated calls are a volatile read.
        runtime.ensureLexicon()
        KeyboardLog.d("NepaliKeyboardApp initialised")
    }

    /**
     * The settings DataStore and the lexicon both reload themselves lazily, so
     * there is nothing to tear down here. Kept explicit to document the intent.
     */
    override fun onTerminate() {
        KeyboardLog.d("NepaliKeyboardApp terminating")
        super.onTerminate()
    }
}
