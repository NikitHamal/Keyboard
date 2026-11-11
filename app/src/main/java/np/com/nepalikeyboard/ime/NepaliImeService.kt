package np.com.nepalikeyboard.ime

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import np.com.nepalikeyboard.keyboard.KeyboardHost
import np.com.nepalikeyboard.settings.SettingsActivity
import np.com.nepalikeyboard.ui.theme.NepaliKeyboardTheme
import np.com.nepalikeyboard.util.CrashLog
import np.com.nepalikeyboard.util.KeyboardLog

/**
 * The input method service.
 *
 * Composition host
 * ----------------
 * The Compose tree lives inside a cached [ComposeView] whose owners are supplied
 * by [ComposeOwnerBridge]. The view is created once in [onCreateInputView] and
 * re-attached on every keyboard show; the composition is disposed only when the
 * service dies, and the lifecycle is paused in [onFinishInputView] so nothing
 * collects state while the keyboard is off screen.
 *
 * Input connection contract
 * -------------------------
 * * [onStartInput] digests `EditorInfo` and reports the editor to the reducer;
 * * [onStartInputView] resumes the bridge and warms the lexicon;
 * * [onUpdateSelection] feeds selection/composing changes back so a stale roman
 *   buffer can never be applied to text the user has edited elsewhere;
 * * [onFinishInput] tears the session down (composition, clipboard observer,
 *   word context) so nothing leaks between editors or apps.
 *
 * No network permission is declared by this app, so this service is structurally
 * incapable of transmitting anything it sees.
 */
class NepaliImeService : InputMethodService(), ImeHost {

    private val serviceScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate + CoroutineName("NepaliIme") +
                kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
                    // A failed collector must degrade a feature, never kill the keyboard process.
                    KeyboardLog.e("IME coroutine failed", error)
                    CrashLog.note(applicationContext, "IME coroutine failed", error)
                },
        )

    private lateinit var runtime: ImeRuntime
    private lateinit var keyboardController: KeyboardController

    private var bridge: ComposeOwnerBridge? = null
    private var inputRoot: FrameLayout? = null
    private val navInsetPx = androidx.compose.runtime.mutableIntStateOf(0)
    private var currentDescriptor: EditorDescriptor = EditorDescriptor.Text

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        runtime = ImeRuntime.get(this)
        keyboardController = KeyboardController(host = this, runtime = runtime, scope = serviceScope)
        KeyboardLog.d("ImeService created")
    }

    override fun onCreateInputView(): View {
        inputRoot?.let { return it }
        return try {
            buildInputView().also { inputRoot = it }
        } catch (error: Throwable) {
            // Never let a view-construction failure kill the process: record it and show a readable fallback.
            KeyboardLog.e("onCreateInputView failed", error)
            CrashLog.note(this, "onCreateInputView failed", error)
            fallbackView(error)
        }
    }

    private fun buildInputView(): FrameLayout {
        // InputMethodService re-wraps the input view in WRAP_CONTENT layout params, so the height is enforced in
        // onMeasure rather than through LayoutParams (otherwise Compose could be offered the whole screen).
        val root = FixedHeightFrame(this, currentKeyboardHeightPx()).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // Android 15 (targetSdk 35) lays the IME window out edge-to-edge, i.e. under the navigation bar. If the
            // framework did not already inset us, extend the keyboard by the bar height and let Compose pad for it
            // (the keyboard background keeps painting underneath, exactly like Gboard).
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                if (bottom != navInsetPx.intValue) {
                    navInsetPx.intValue = bottom
                    extraBottom = bottom
                }
                insets
            }
        }
        // Compose resolves its window Recomposer from the TOP of the view tree (the IME window's DecorView, which is
        // an ancestor of this root). Owners installed only on `root` are invisible from there and the first
        // composition crashes with "ViewTreeLifecycleOwner not found from DecorView". Install on both.
        val decor: View? = window?.window?.decorView
        val owner = ComposeOwnerBridge().also {
            it.attach(root, decor)
            bridge = it
        }
        val composeView = SafeComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            // Keep the composition alive across keyboard hide/show cycles; it is
            // disposed only when the service (and therefore the bridge) dies.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
            setContent {
                val uiState by keyboardController.state.collectAsStateWithLifecycle()
                val navInset = navInsetPx.intValue
                NepaliKeyboardTheme(snapshot = uiState.theme) {
                    KeyboardHost(
                        state = uiState,
                        sink = keyboardController,
                        bottomInsetPx = navInset,
                    )
                }
            }
        }
        root.addView(composeView)
        return root
    }

    private fun fallbackView(error: Throwable): View = android.widget.TextView(this).apply {
        text = "Keyboard failed to start:\n${error::class.java.simpleName}: ${error.message}\n(see Settings > More > Crash log)"
        setPadding(32, 32, 32, 32)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.DKGRAY)
        textSize = 13f
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        currentDescriptor = EditorDescriptor.from(info)
        // `restarting` is deliberately not treated as a fresh session: the same
        // editor is still focused, so the caret and learned context stay valid.
        keyboardController.editorChanged()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (info != null) {
            currentDescriptor = EditorDescriptor.from(info)
        }
        bridge?.onInputViewShown()
        applyCurrentHeight()
        keyboardController.onInputViewShown()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        bridge?.onInputViewShown()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        bridge?.onInputViewHidden()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        bridge?.onInputViewHidden()
        keyboardController.onInputViewHidden()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        keyboardController.editorFinished()
    }

    override fun onDestroy() {
        bridge?.destroy()
        bridge = null
        inputRoot = null
        serviceScope.cancel()
        KeyboardLog.d("ImeService destroyed")
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        keyboardController.onSelectionChanged(
            selectionStart = newSelStart,
            selectionEnd = newSelEnd,
            composingStart = candidatesStart,
            composingEnd = candidatesEnd,
        )
    }

    /** Never take over the screen: the keyboard stays a bottom sheet. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: android.view.inputmethod.CursorAnchorInfo?) {
        // Not used: the keyboard does not position UI relative to the caret.
    }

    // -----------------------------------------------------------------------
    // Hardware keys
    // -----------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.isSystem) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                keyboardController.hardwareBackspace()
                return true
            }

            KeyEvent.KEYCODE_FORWARD_DEL -> {
                keyboardController.hardwareForwardDelete()
                return true
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                keyboardController.hardwareEnter()
                return true
            }

            KeyEvent.KEYCODE_SPACE -> {
                keyboardController.hardwareSpace()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // -----------------------------------------------------------------------
    // ImeHost
    // -----------------------------------------------------------------------

    override val inputConnection: InputConnection?
        get() = try {
            currentInputConnection
        } catch (_: RuntimeException) {
            null
        }

    override val editorDescriptor: EditorDescriptor
        get() = currentDescriptor

    override fun performEditorAction() {
        val connection = inputConnection
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_UNSPECIFIED
        try {
            when (action) {
                EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED ->
                    connection?.performEditorAction(EditorInfo.IME_ACTION_DONE)

                else -> connection?.performEditorAction(action)
            }
        } catch (_: RuntimeException) {
            KeyboardLog.w("performEditorAction failed")
        }
    }

    override fun hideInputView() {
        try {
            requestHideSelf(0)
        } catch (_: RuntimeException) {
            KeyboardLog.w("requestHideSelf failed")
        }
    }

    override fun openSettings() {
        try {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (error: RuntimeException) {
            KeyboardLog.e("Unable to open settings", error)
        }
    }

    override fun applyKeyboardHeightScale(scale: Float) {
        val root = inputRoot as? FixedHeightFrame ?: return
        root.fixedHeight = (baseKeyboardHeightPx() * scale).toInt()
    }

    override fun switchToNextInputMethod() {
        // A picker is the only mechanism that also lets the user *stay* on this
        // keyboard, and it needs no extra permission.
        try {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showInputMethodPicker()
        } catch (error: RuntimeException) {
            KeyboardLog.w("showInputMethodPicker failed: ${error.message}")
        }
    }

    // -----------------------------------------------------------------------
    // Sizing
    // -----------------------------------------------------------------------

    private fun applyCurrentHeight() {
        applyKeyboardHeightScale(runtime.settings.current.keyboardHeightScale)
    }

    private fun currentKeyboardHeightPx(): Int =
        (baseKeyboardHeightPx() * runtime.settings.current.keyboardHeightScale).toInt()

    /**
     * Base keyboard height in pixels.
     *
     * Portrait uses 44% of the screen (the sweet spot between a usable key size
     * and leaving context visible), landscape 50% of a much shorter screen, and
     * the result is clamped to 240dp..380dp so that a very small or very large
     * display still produces thumb-sized keys.
     */
    private fun baseKeyboardHeightPx(): Int {
        val metrics = resources.displayMetrics
        val portrait = metrics.heightPixels >= metrics.widthPixels
        val fraction = if (portrait) PORTRAIT_FRACTION else LANDSCAPE_FRACTION
        val raw = (metrics.heightPixels * fraction).toInt()
        val minPx = with(resources.displayMetrics) { 232f * density }.toInt()
        val maxPx = with(resources.displayMetrics) { 340f * density }.toInt()
        return raw.coerceIn(minPx, maxPx)
    }

    /**
     * ComposeView whose attach step cannot take the process down: the first composition runs inside
     * `onAttachedToWindow`, outside any of our try/catch blocks. A failure is written to the crash log instead.
     */
    private class SafeComposeView(context: Context) : AbstractComposeView(context) {
        private val content = androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)

        fun setContent(body: @androidx.compose.runtime.Composable () -> Unit) {
            content.value = body
        }

        @androidx.compose.runtime.Composable
        override fun Content() {
            content.value?.invoke()
        }

        override fun onAttachedToWindow() {
            try {
                super.onAttachedToWindow()
            } catch (error: Throwable) {
                KeyboardLog.e("ComposeView attach failed", error)
                CrashLog.note(context, "ComposeView attach failed", error)
            }
        }
    }

    /** Frame that always measures to an exact height, whatever LayoutParams the framework assigns. */
    private class FixedHeightFrame(context: Context, initialHeight: Int) : FrameLayout(context) {
        var fixedHeight: Int = initialHeight
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                }
            }

        /** Navigation-bar height this frame must additionally cover (0 when the framework already insets us). */
        var extraBottom: Int = 0
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                }
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(fixedHeight + extraBottom, View.MeasureSpec.EXACTLY),
            )
        }
    }

    private companion object {
        const val PORTRAIT_FRACTION = 0.345f
        const val LANDSCAPE_FRACTION = 0.50f
    }
}
