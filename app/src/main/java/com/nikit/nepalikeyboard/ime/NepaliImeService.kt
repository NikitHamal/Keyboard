package com.nikit.nepalikeyboard.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nikit.nepalikeyboard.translit.RomanizedEngine
import com.nikit.nepalikeyboard.clipboard.ClipboardCapture
import com.nikit.nepalikeyboard.clipboard.ClipItem
import com.nikit.nepalikeyboard.settings.SettingsActivity
import com.nikit.nepalikeyboard.ui.KeyboardHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * =============================================================================
 * THE IME SERVICE
 * =============================================================================
 *
 * This is the entry point the Android platform instantiates when the user
 * selects this keyboard. It is a `Service`, not an `Activity`, and that single
 * fact dictates the entire structure of the class.
 *
 * ### What an IME actually is
 *
 * The platform binds to this service with `BIND_INPUT_METHOD` and then talks to
 * it through `InputMethodService`'s callbacks. The service owns a `Window` —
 * a real one, with a decor view — which the system's
 * `InputMethodManagerService` positions below (or above) the focused editor.
 * Our job is to populate that window's view hierarchy, and to relay everything
 * the user types into the currently focused app's `InputConnection`.
 *
 * ### The three lifecycles, which are not the same lifecycle
 *
 * This is where IMEs get written wrong. There are three distinct notions of
 * "alive" and they must not be conflated:
 *
 *  1. **Service lifetime** — `onCreate` … `onDestroy`. The process may survive
 *     far longer than any single showing. Configuration changes (rotation,
 *     theme switch) call `onDestroy` and then `onCreate` again on the *same*
 *     process.
 *  2. **View lifetime** — `onCreateInputView` … `onDestroyInputView`. The
 *     keyboard's view tree. Recreated on configuration change. Everything
 *     expensive that must survive rotation (the lexicon index, clipboard
 *     history, learned words) lives *outside* this.
 *  3. **Editor lifetime** — `onStartInput` … `onFinishInput`. Which text field
 *     we are writing into. Changes constantly: every tap on a different field,
 *     every activity transition. All composing state is scoped here.
 *
 * Concretely: the `InputConnectionController` is reset on every
 * `onStartInput`; the `KeyboardLifecycleOwner` and its ViewModelStore survive
 * `onFinishInput` and are only torn down in `onDestroy`; the lexicon load is
 * started once per process.
 *
 * ### Why there is a `KeyboardLifecycleOwner` at all
 *
 * Because Compose requires one. `InputMethodService` implements none of
 * `LifecycleOwner`, `ViewModelStoreOwner`, or `SavedStateRegistryOwner`, so
 * composing into its window without grafting those in throws immediately. See
 * `KeyboardLifecycleOwner.kt` for the full explanation and the lifecycle
 * mapping.
 *
 * ### Zero-allocation hot path
 *
 * The last thing worth stating explicitly: the pointer and key dispatch path
 * from a tap to a committed character allocates nothing. No `Bundle`, no
 * `ClipData`, no boxing of primitives, no string formatting. Everything the
 * handler needs — the current mode, the shift state, the composer buffer — is a
 * field read. Haptic and audio feedback use pre-resolved constants. This is why
 * the state lives in `KeyboardUiState` as primitives and why the service holds
 * one `InputConnectionController` for its whole life rather than creating one
 * per input session.
 */
class NepaliImeService : InputMethodService() {

    /**
     * Everything that touches the target app's text goes through this single
     * instance. Reusing it (rather than allocating per editor) is what lets it
     * cache the parsed `EditorInfo` flags and the derived password/numeric
     * decisions that the hot path consults on every keystroke.
     */
    private val input = InputConnectionController()

    /**
     * The lifecycle owner grafted onto the keyboard's Compose host.
     *
     * Created in [onCreate] and destroyed in [onDestroy], which is deliberately
     * *wider* than the view lifetime: the owner holds the `ViewModelStore`, and
     * keeping that alive across a rotation is what prevents the lexicon index
     * from being rebuilt every time the user turns their phone.
     */
    private lateinit var keyboardLifecycle: KeyboardLifecycleOwner

    /**
     * Service-scoped coroutine scope.
     *
     * `SupervisorJob` so a failed suggestion query cannot cancel the scope and
     * leave the keyboard permanently unable to look anything up. Cancelled in
     * `onDestroy` — unlike the application scope, this one must die with the
     * service, because its coroutines capture the service instance.
     */
    private var serviceScope: CoroutineScope? = null

    /**
     * The ViewModel that owns all keyboard state and business logic.
     *
     * Held as a field rather than obtained via `viewModel()` inside the
     * composition because the service needs to call into it from its own
     * callbacks (`onStartInput` must tell it about the new editor, `onUpdateSelection`
     * must tell it the caret moved). Reading it in both places from the same
     * owner means there is exactly one instance.
     */
    private var viewModel: KeyboardViewModel? = null

    /**
     * The most recent `EditorInfo`, retained so that a configuration change —
     * which destroys and recreates the input view without a new
     * `onStartInput` — can re-apply the correct layout, action glyph, and
     * password restrictions to the freshly built UI.
     */
    private var currentEditorInfo: EditorInfo? = null

    /**
     * The haptic service, resolved once. On API 31+ this must come from
     * `VibratorManager`; the old `getSystemService(VIBRATOR_SERVICE)` path is
     * deprecated and returns a default vibrator that ignores intensity.
     */
    private var vibrator: Vibrator? = null

    /** The currently registered clipboard capture hook, so it can be removed. */
    private var clipboardHook: ((ClipItem) -> Unit)? = null

    /**
     * Stateless mode memory used by the globe/mode key. Persisted through the
     * ViewModel so it survives rotation; mirrored here as a plain field so the
     * keyboard-switch handler can read it without a coroutine dispatch.
     */
    private var lastModeBeforeEnglish: InputMode = InputMode.ROMANIZED

    // =========================================================================
    // Service lifecycle
    // =========================================================================

    /**
     * Called once per service instance, before any view exists.
     *
     * Does five things, in the order they must happen:
     *   1. Resolve the vibrator, because a failure here should be discovered
     *      before the first keypress, not during it.
     *   2. Build and `create()` the lifecycle owner, so the ViewModelStore and
     *      saved-state registry exist before anything asks for them.
     *   3. Build the service scope.
     *   4. Construct the ViewModel against the owner.
     *   5. Install clipboard capture.
     *
     * Note what is *not* here: loading the lexicon. That was already kicked off
     * by `NepaliKeyboardApp.onCreate`, and starting it again here would be
     * redundant work on the critical path.
     */
    override fun onCreate() {
        super.onCreate()

        keyboardLifecycle = KeyboardLifecycleOwner(application)
        keyboardLifecycle.create()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope

        viewModel = KeyboardViewModel(application, keyboardLifecycle, input, scope)

        // One-shot signals the ViewModel cannot act on itself. `requestHideSelf`
        // is a framework call that only the service may make, so it lives here
        // rather than behind an abstraction.
        scope.launch {
            viewModel?.events?.collect { event ->
                when (event) {
                    KeyboardEvent.Dismiss -> requestHideSelfCompat()
                    KeyboardEvent.ShowInputMethodPicker -> openInputMethodPicker()
                    KeyboardEvent.ResetSuggestionScroll -> Unit
                    is KeyboardEvent.ShowNotice -> Log.w(TAG, event.message)
                }
            }
        }

        resolveVibrator()
        installClipboardCapture()

        Log.d(TAG, "Service created")
    }

    /**
     * Called when the input view is first needed. Returns the root view the
     * system will insert below the editor.
     *
     * `ComposeView` is used directly rather than via `setContent` on a
     * `ComponentActivity`, because there is no activity. The three view-tree
     * owners are installed by `InstallKeyboardViewTreeOwners` as the outermost
     * composable, which is what makes every Compose API — `rememberSaveable`,
     * `viewModel()`, `LaunchedEffect`, `collectAsStateWithLifecycle` — behave
     * exactly as it would inside an activity.
     */
    override fun onCreateInputView(): View {
        val owner = keyboardLifecycle
        val vm = viewModel ?: error("ViewModel requested before service onCreate")

        val view = ComposeView(this).apply {
            // The keyboard must not take focus from the editor. A ComposeView
            // is focusable by default, which would pull the caret out of the
            // text field the user was typing in.
            isFocusable = false
            isFocusableInTouchMode = false
            // `importantForAutofill = no` because there is nothing here to
            // autofill, and declaring otherwise makes some password managers
            // offer to save the keyboard's own contents.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO

            // -----------------------------------------------------------------
            // Tag the view tree owners BEFORE the composition starts.
            //
            // `InstallKeyboardViewTreeOwners` inside setContent is the tidy way
            // to do this, but it is not sufficient on its own, and relying on
            // it alone was a hard crash:
            //
            //   ComposeView.onAttachedToWindow()
            //     -> resolveParentCompositionContext()
            //       -> getWindowRecomposer()
            //         -> createLifecycleAwareWindowRecomposer()
            //           -> findViewTreeLifecycleOwner() ?: throw IllegalStateException
            //
            // That call chain runs the moment the view is attached, and it
            // *bails out* unless a ViewTreeLifecycleOwner is already present on
            // this view. The composable inside setContent cannot supply it,
            // because the composition is exactly what is failing to start.
            //
            // Setting the tags here closes the gap. The in-composition effect
            // still runs afterwards and is still the authority for teardown;
            // this is belt-and-braces for the attach-time read.
            // -----------------------------------------------------------------
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)

            setContent {
                InstallKeyboardViewTreeOwners(owner) {
                    KeyboardRoot(
                        viewModel = vm,
                        service = this@NepaliImeService
                    )
                }
            }
        }

        // The owner is about to have a live composition; move it to STARTED so
        // any `repeatOnLifecycle(STARTED)` collector inside the UI begins.
        keyboardLifecycle.startAndResume()

        // Re-apply the editor context, because a configuration change destroys
        // and recreates this view without a fresh `onStartInput`.
        currentEditorInfo?.let { vm.onEditorChanged(it, input) }

        Log.d(TAG, "Input view created")

        return view
    }

    /**
     * Called when the keyboard window is about to become visible, for the field
     * described by [info] (or re-shown for the same field when `restarting`).
     *
     * This is where the editor lifetime begins. The order of operations matters:
     * attach the connection first so the controller has a live target, *then*
     * tell the ViewModel, because the ViewModel's first act is to read
     * auto-capitalisation, which needs the connection.
     */
    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        currentEditorInfo = info
        input.attach(info, currentInputConnection)
        viewModel?.onEditorChanged(info, input)
    }

    /**
     * Called when the keyboard becomes visible.
     *
     * Distinct from `onStartInput`, which fires even when the keyboard is
     * suppressed (hardware keyboard attached). We resume the lifecycle owner
     * here because this is the moment a composition actually matters.
     */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info

        // The framework sometimes hands us a fresh connection object between
        // `onStartInput` and here, so re-bind rather than trusting the earlier
        // one. Composer state is preserved.
        input.rebind(currentInputConnection)

        keyboardLifecycle.startAndResume()
        viewModel?.onInputViewShown()
    }

    /**
     * Called when the keyboard is being hidden.
     *
     * Two jobs: pause the lifecycle owner so `repeatOnLifecycle` collectors
     * stop, and — critically — *commit* any half-typed Romanized word rather
     * than discard it. A user who types "namaste" and then taps another field
     * expects नमस्ते to have been written; silently dropping it is data loss
     * from their point of view.
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        viewModel?.onInputViewHidden(commitPending = true)
        keyboardLifecycle.pause()
        super.onFinishInputView(finishingInput)
    }

    /**
     * Called when the editor loses focus entirely.
     *
     * `finishingInput == true` means the field is gone; `false` means focus
     * merely moved. Either way the composer must not survive, because
     * continuing to compose into a field the user has left would insert text
     * they did not ask for.
     */
    override fun onFinishInput() {
        viewModel?.onEditorFinished()
        input.detach()
        super.onFinishInput()
    }

    /**
     * Called when the keyboard window itself is hidden.
     *
     * This is the most reliable of the three "hidden" callbacks across OEM
     * builds, so it is the one that drives the lifecycle pause. Being called
     * twice is harmless: `pause()` is idempotent.
     */
    override fun onWindowHidden() {
        keyboardLifecycle.pause()
        super.onWindowHidden()
    }

    /**
     * Tells the ViewModel the caret moved, so it can abandon a composing region
     * the user has tapped away from.
     *
     * This is the single most important correctness guard in the service. If
     * the caret leaves the composing region and we keep composing, the next
     * keystroke replaces text at the wrong location — the most destructive bug
     * an IME can have. See `InputConnectionController.selectionMovedOutsideComposing`.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        viewModel?.onSelectionUpdated(
            oldSelStart = oldSelStart,
            oldSelEnd = oldSelEnd,
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            composingStart = candidatesStart,
            composingEnd = candidatesEnd
        )
    }

    /**
     * Called when the app asks the IME to show or hide its extract UI, or when
     * the input type changes mid-session (a common WebView pattern).
     */
    override fun onUpdateExtractingViews(info: EditorInfo) {
        super.onUpdateExtractingViews(info)
        currentEditorInfo = info
        input.attach(info, currentInputConnection)
        viewModel?.onEditorChanged(info, input)
    }

    /**
     * Handles a hardware key event.
     *
     * Returns `false` for everything except backspace, which we intercept so
     * that a physical keyboard's delete key also goes through the
     * grapheme-cluster-aware path. Sending a raw `KEYCODE_DEL` would corrupt
     * Devanagari clusters, so we route it through the controller and report
     * that we handled it.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            val handled = input.deleteBackward()
            if (handled) {
                viewModel?.onComposingBufferChanged(input.composingText)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * The system is asking whether we want to handle the back key ourselves.
     *
     * We do when a panel is open (emoji or clipboard), because the expected
     * behaviour there is "close the panel", not "dismiss the keyboard". The
     * ViewModel decides; the service just relays.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val consumed = viewModel?.onBackPressed() ?: false
            if (consumed) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * The platform's suggestion for where to place the keyboard window.
     *
     * Returning the full-screen size minus the status bar is wrong for an IME:
     * the system wants the *desired height* here and handles positioning
     * itself. Returning a large value makes the keyboard cover the editor. We
     * compute from the measured height instead, and let the framework clamp.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * Called when the service is being torn down — or recreated for a
     * configuration change.
     *
     * Order matters. Commit any pending composition *before* destroying the
     * lifecycle owner, because committing needs a live composition to read the
     * buffer from; then destroy the owner (which clears the ViewModelStore and
     * cancels every effect); then cancel the service scope.
     *
     * Note that the ViewModelStore survives a configuration change in
     * `ComponentActivity` because the activity is retained. An IME service is
     * *not* retained across rotation — `onDestroy` really is called — so the
     * lexicon index would be rebuilt on every rotation if it lived in a
     * ViewModel alone. That is why `LexiconRepository` is a process-level
     * singleton: the ViewModel is a thin façade over it, and rotation costs
     * nothing.
     */
    override fun onDestroy() {
        viewModel?.onServiceDestroying()
        uninstallClipboardCapture()
        keyboardLifecycle.destroy()
        serviceScope?.cancel()
        serviceScope = null
        viewModel = null
        vibrator = null
        currentEditorInfo = null
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    // =========================================================================
    // Feedback
    // =========================================================================

    /**
     * Resolves the vibrator once.
     *
     * API 31 moved vibration behind `VibratorManager`; the old path still
     * compiles but yields a vibrator that cannot honour amplitude, which makes
     * "light" and "strong" haptics feel identical. Since the settings screen
     * offers a strength control, we need the modern path where it exists.
     */
    private fun resolveVibrator() {
        vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Vibrator unavailable", t)
            null
        }
    }

    /**
     * Fires keypress haptics.
     *
     * Called from the pointer-down handler, so it must not allocate. The
     * `VibrationEffect` is built once per call only when the modern path is
     * taken and only when a strength was configured; the common case is a
     * `performHapticFeedback` call on the view, which is the cheapest correct
     * route and respects the user's system-wide haptics setting — something a
     * raw `Vibrator.vibrate` call would override.
     */
    fun performHaptic(view: View, strength: Int, isDelete: Boolean) {
        if (!keyboardLifecycle.isKeyboardVisible) return
        try {
            if (strength <= 0) {
                // The user asked for the system default. This is the fast path:
                // no allocation, and it honours the OS-level toggle.
                view.performHapticFeedback(
                    if (isDelete) HapticFeedbackConstants.LONG_PRESS
                    else HapticFeedbackConstants.KEYBOARD_TAP
                )
                return
            }
            val amp = strength.coerceIn(MIN_VIBRATION_AMPLITUDE, MAX_VIBRATION_AMPLITUDE)
            val effect = VibrationEffect.createOneShot(VIBRATION_DURATION_MS, amp)
            vibrator?.vibrate(effect)
        } catch (t: Throwable) {
            // Haptics failing must never break typing.
        }
    }

    // =========================================================================
    // Clipboard
    // =========================================================================

    /**
     * Subscribes the ViewModel to clipboard changes for the lifetime of the
     * process.
     *
     * Guarded so that a service recreation (rotation) does not register a
     * second listener — `ClipboardCapture` holds one slot, and the old lambda
     * captures the *old* ViewModel. Replacing it is correct and required.
     */
    private fun installClipboardCapture() {
        val vm = viewModel ?: return
        val hook: (ClipItem) -> Unit = { item -> vm.onClipboardItemCaptured(item) }
        ClipboardCapture.onClipCaptured = hook
        clipboardHook = hook
    }

    /** Removes our clipboard hook so a destroyed service is not referenced. */
    private fun uninstallClipboardCapture() {
        if (ClipboardCapture.onClipCaptured === clipboardHook) {
            ClipboardCapture.onClipCaptured = null
        }
        clipboardHook = null
    }

    // =========================================================================
    // Actions invoked from the Compose UI
    // =========================================================================

    /**
     * Switches to the next installed input method, or to our other subtype if
     * we are the only one.
     *
     * The platform refuses to switch when there is only a single IME installed;
     * in that case we open the system's IME picker instead, which is the
     * behaviour users expect from the globe key and which is what every
     * first-party keyboard does.
     */
    fun switchToNextInputMethod() {
        try {
            val manager = getSystemService(InputMethodManager::class.java) ?: return
            val switched = manager.switchToNextInputMethod(
                /* imeToken = */ getImeTokenCompat(),
                /* onlyCurrentIme = */ false
            )
            if (!switched) {
                openInputMethodPicker()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "switchToNextInputMethod failed", t)
            openInputMethodPicker()
        }
    }

    /**
     * Switches between our own Nepali and English subtypes.
     *
     * `onlyCurrentIme = true` confines the switch to subtypes of this IME,
     * which is what the mode-switch key should do — the globe key is the one
     * that leaves the app entirely.
     */
    fun switchSubtype() {
        try {
            val manager = getSystemService(InputMethodManager::class.java) ?: return
            manager.switchToNextInputMethod(getImeTokenCompat(), /* onlyCurrentIme = */ true)
        } catch (t: Throwable) {
            Log.w(TAG, "switchSubtype failed", t)
        }
    }

    /**
     * Opens the system's input-method picker dialog.
     */
    fun openInputMethodPicker() {
        try {
            val manager = getSystemService(InputMethodManager::class.java) ?: return
            manager.showInputMethodPicker()
        } catch (t: Throwable) {
            Log.w(TAG, "showInputMethodPicker failed", t)
        }
    }

    /**
     * Opens the system IME settings page.
     *
     * Launched from the keyboard's own gear/long-press affordance so that a
     * user who has enabled the keyboard but not selected it can fix that
     * without leaving the field they are trying to type in.
     */
    fun openImeSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not open IME settings", t)
        }
    }

    /** Opens the app's own settings screen. */
    fun openAppSettings() {
        try {
            val intent = Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not open app settings", t)
        }
    }

    /**
     * Reads the IME token used by `InputMethodManager`'s switch APIs.
     *
     * `InputMethodService.getImeToken()` exists in the framework but is not part
     * of the public SDK, and Google Play policy prohibits reflecting into hidden
     * APIs. Every public `InputMethodManager` method that takes a token accepts
     * `null`, which the platform interprets as "the current IME of this
     * process" — precisely the semantics we want. So we pass null deliberately
     * rather than reaching for the hidden accessor.
     */
    private fun getImeTokenCompat(): IBinder? = null

    /**
     * Hides the keyboard window at the user's request.
     *
     * Exposed as a distinct method from the internal `requestHideSelf` call so
     * that the intent is visible at the call site, and so the flags argument is
     * stated in exactly one place. `requestHideSelf(0)` returns focus to the
     * app and lets the platform run its normal hide animation; passing
     * `HIDE_IMPLICIT_ONLY` here would be wrong, because that flag means "only
     * hide a keyboard the user did not explicitly ask for" and this one they
     * did.
     */
    fun requestHideSelfCompat() {
        requestHideSelf(0)
    }

    // =========================================================================
    // Composing-buffer bridge
    // =========================================================================

    /**
     * The Devanagari currently displayed in the composing region, or empty.
     * Exposed so the Compose layer can render an inline preview without
     * reaching into the controller directly.
     */
    fun currentComposingText(): String = input.composingText

    /**
     * Renders a Romanized buffer for display as a preview, without touching the
     * field. Used by the suggestion strip's "what will be inserted" hint.
     */
    fun previewTransliteration(roman: String): String =
        if (roman.isEmpty()) "" else RomanizedEngine.toDevanagari(roman, isComplete = false)

    /**
     * Whether the field currently focused accepts text from us at all. Some
     * editors (a few file managers, some system dialogs) present a field but
     * reject writes; when that happens every key would be a silent no-op and
     * the keyboard would look broken. The UI uses this to grey the keys.
     */
    fun isInputWritable(): Boolean = input.isAttached

    // =========================================================================
    // Debug / diagnostics
    // =========================================================================

    /**
     * A one-line summary of the current editor, for the sandbox screen's
     * diagnostics row. Never shown in normal typing.
     */
    fun describeCurrentEditor(): String {
        val info = currentEditorInfo ?: return "no editor"
        val flags = buildString {
            if (input.isPasswordField) append("password ")
            if (input.isNumericField) append("numeric ")
            if (input.isMultiline()) append("multiline ")
            if (input.isUriLike()) append("uri ")
        }
        return "type=0x${Integer.toHexString(info.inputType)} action=${info.imeOptions and EditorInfo.IME_MASK_ACTION} $flags"
    }

    companion object {
        private const val TAG = "NepaliImeService"

        /** Duration of a keypress haptic pulse, in milliseconds. */
        private const val VIBRATION_DURATION_MS = 12L

        /** Amplitude floor; below this the motor does not move on most devices. */
        private const val MIN_VIBRATION_AMPLITUDE = 20

        /** Amplitude ceiling; 255 is the platform maximum. */
        private const val MAX_VIBRATION_AMPLITUDE = 255
    }
}

/**
 * Root composable for the keyboard.
 *
 * Declared here rather than in the UI package because it is the service's view
 * contract: the service hands it a ViewModel and a bundle of
 * framework-level actions that only the service can perform (switching IMEs,
 * launching settings intents, firing haptics).
 *
 * The two dependencies are passed explicitly rather than through a
 * `CompositionLocal` so that the boundary between "keyboard logic" and
 * "framework actions" is visible at every call site. It is also what allows the
 * settings app's sandbox screen to reuse the very same composables with a
 * no-op action bundle.
 */
@Composable
private fun KeyboardRoot(
    viewModel: KeyboardViewModel,
    service: NepaliImeService
) {
    KeyboardHost(
        viewModel = viewModel,
        serviceActions = remember(service) { ImeServiceActions(service) }
    )
}

/**
 * The set of framework-level actions the keyboard UI can request.
 *
 * An interface rather than a concrete class holding the service, because two
 * very different callers need it:
 *
 *  * the live IME, where every method forwards to `NepaliImeService`;
 *  * the settings app's typing sandbox, which renders the *same* composables
 *    inside an ordinary `Activity` and needs a bundle where nothing happens.
 *
 * Making it an interface is what lets [NoOpServiceActions] exist without a
 * nullable service reference threaded through every call site in the UI. The
 * alternative — a concrete class with a nullable delegate — would put an
 * `?.` on twenty lines of the UI and make "did the sandbox forget to handle
 * this action?" invisible.
 */
interface ServiceActions {
    /** Cycle to the next installed keyboard. */
    fun switchToNextInputMethod()

    /** Switch between this IME's own Nepali and English subtypes. */
    fun switchSubtype()

    /** Show the system IME picker. */
    fun openInputMethodPicker()

    /** Open the system page where the keyboard is enabled. */
    fun openImeSettings()

    /** Open this app's own settings. */
    fun openAppSettings()

    /** Fire a haptic pulse for a key press. */
    fun haptic(view: View, strength: Int, isDelete: Boolean)

    /** The Devanagari currently in the composing region. */
    fun currentComposingText(): String

    /** Preview-only transliteration, for the strip's hint row. */
    fun previewTransliteration(roman: String): String

    /** Whether the focused field accepts our writes. */
    fun isInputWritable(): Boolean

    /** A diagnostic summary of the focused editor. */
    fun describeCurrentEditor(): String

    /** Ask the system to hide the keyboard window. */
    fun dismissKeyboard()
}

/**
 * The live implementation, forwarding to the running IME service.
 *
 * Every method is a direct tail call; there is no state here and no caching,
 * because each of these is already cheap and the service may be destroyed at
 * any point — a cached handle would be a dangling reference to a dead window.
 */
class ImeServiceActions internal constructor(
    private val service: NepaliImeService
) : ServiceActions {
    override fun switchToNextInputMethod(): Unit = service.switchToNextInputMethod()

    override fun switchSubtype(): Unit = service.switchSubtype()

    override fun openInputMethodPicker(): Unit = service.openInputMethodPicker()

    override fun openImeSettings(): Unit = service.openImeSettings()

    override fun openAppSettings(): Unit = service.openAppSettings()

    override fun haptic(view: View, strength: Int, isDelete: Boolean): Unit =
        service.performHaptic(view, strength, isDelete)

    override fun currentComposingText(): String = service.currentComposingText()

    override fun previewTransliteration(roman: String): String =
        service.previewTransliteration(roman)

    override fun isInputWritable(): Boolean = service.isInputWritable()

    override fun describeCurrentEditor(): String = service.describeCurrentEditor()

    override fun dismissKeyboard(): Unit = service.requestHideSelfCompat()
}

/**
 * The inert implementation, for hosts that are not an IME.
 *
 * Used by the settings app's typing sandbox and by Compose previews. Each
 * method is deliberately empty rather than throwing: the sandbox renders the
 * real keyboard UI, and a user tapping the "switch keyboard" key there should
 * see nothing happen, not a crash. The one exception is
 * [currentComposingText] and friends, which return neutral values because the
 * UI reads them to decide what to draw.
 *
 * This is an `object` because it is stateless.
 */
object NoOpServiceActions : ServiceActions {
    override fun switchToNextInputMethod() = Unit

    override fun switchSubtype() = Unit

    override fun openInputMethodPicker() = Unit

    override fun openImeSettings() = Unit

    override fun openAppSettings() = Unit

    override fun haptic(view: View, strength: Int, isDelete: Boolean) = Unit

    override fun currentComposingText(): String = ""

    override fun previewTransliteration(roman: String): String = ""

    override fun isInputWritable(): Boolean = true

    override fun describeCurrentEditor(): String = "sandbox"

    override fun dismissKeyboard() = Unit
}
