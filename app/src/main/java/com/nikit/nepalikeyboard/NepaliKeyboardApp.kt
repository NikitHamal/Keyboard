package com.nikit.nepalikeyboard

import android.app.Application
import android.util.Log
import com.nikit.nepalikeyboard.debug.CrashHandler
import com.nikit.nepalikeyboard.lexicon.LexiconRepository
import com.nikit.nepalikeyboard.settings.SettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide entry point.
 *
 * The work done here is deliberately small. An IME's process is started by the
 * system the moment the user focuses a text field, and every millisecond spent
 * in `Application.onCreate` is a millisecond the user spends looking at a blank
 * space where their keyboard should be. So we do exactly two things:
 *
 *  1. Create the application-scoped coroutine scope that the keyboard UI and
 *     the lexicon repository share.
 *  2. Kick off lexicon loading *speculatively*, in the background, so that by
 *     the time the user has finished transitioning to the editor the dictionary
 *     is already warm. Nothing awaits this; failures are logged and the
 *     keyboard degrades to pure transliteration.
 *
 * Notice there is no analytics initialiser, no crash reporter, no ad SDK. That
 * is not an oversight — see the privacy contract in AndroidManifest.xml. This
 * class is the only place where a "framework SDK" would normally go, and it is
 * empty of them on purpose.
 */
class NepaliKeyboardApp : Application() {

    /**
     * Application-lifetime scope for work that must outlive any single
     * keyboard session: lexicon loading, learned-word persistence, clipboard
     * history writes.
     *
     * `SupervisorJob` so that one failed child (say, a corrupted learned-word
     * file) cannot tear down the scope and take the dictionary load with it.
     *
     * We do not cancel this scope. It dies with the process, which is exactly
     * the lifetime we want — the alternative, a scope tied to the IME service,
     * would thrash the cache every time the user switches away from the
     * keyboard.
     */
    val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            // Never surface a stack trace to the user's logcat as a crash; the
            // keyboard must keep accepting input even if background work dies.
            Log.e(TAG, "Uncaught exception in application scope", throwable)
        }
    )

    override fun onCreate() {
        super.onCreate()
        instanceRef = this

        // Install the crash handler FIRST, before any other initialiser.
        //
        // Ordering is the whole point: anything registered or constructed before
        // this line can throw outside our protection, and for an IME that means
        // an invisible death — a keyboard that simply never appears. The handler
        // itself does no disk I/O at install time, so it costs nothing here.
        CrashHandler.install(this)

        preloadLexicon()
    }

    /**
     * Starts dictionary loading without blocking startup and without awaiting
     * completion.
     *
     * `ensureLoaded` is idempotent and guarded by a mutex inside the
     * repository, so the IME service calling it again later is free — the
     * second caller simply observes the already-ready state. Having both
     * callers is intentional: if the process was started by our Settings
     * activity the eager load warms the cache for the first keystroke, and if
     * the process was started cold by the IME itself the service-side call is
     * the one that matters.
     */
    private fun preloadLexicon() {
        applicationScope.launch {
            try {
                LexiconRepository.get().ensureLoaded(this@NepaliKeyboardApp)
            } catch (t: Throwable) {
                // A missing or malformed asset must never crash the keyboard.
                // Transliteration still works with no lexicon; suggestions
                // simply collapse to the literal and transliterated forms.
                Log.e(TAG, "Lexicon preload failed; falling back to transliteration only", t)
            }
        }
        applicationScope.launch {
            try {
                SettingsRepository.get(this@NepaliKeyboardApp).prime()
            } catch (t: Throwable) {
                Log.e(TAG, "Settings priming failed; defaults will be used", t)
            }
        }
    }

    companion object {
        private const val TAG = "NepaliKeyboardApp"

        /**
         * Weak-ish handle used by the clipboard initialiser and by debugging
         * helpers. Marked `@Volatile` because it is written on the main thread
         * in `onCreate` and read from arbitrary threads afterwards.
         */
        @Volatile
        private var instanceRef: NepaliKeyboardApp? = null

        /**
         * The running application, or null if queried before `onCreate`.
         *
         * Callers that need an application scope should prefer this over a
         * global cast so that the null case is handled explicitly rather than
         * crashing with a `ClassCastException` in a background thread.
         */
        val instance: NepaliKeyboardApp? get() = instanceRef
    }
}
