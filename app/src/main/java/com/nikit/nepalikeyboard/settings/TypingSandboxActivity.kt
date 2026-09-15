package com.nikit.nepalikeyboard.settings

import android.app.Application
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.ime.InputConnectionController
import com.nikit.nepalikeyboard.ime.KeyboardViewModel
import com.nikit.nepalikeyboard.ime.NoOpServiceActions
import com.nikit.nepalikeyboard.ime.rememberKeyboardLifecycleOwner
import com.nikit.nepalikeyboard.ui.KeyboardHost
import com.nikit.nepalikeyboard.ui.theme.KeyboardTheme
import com.nikit.nepalikeyboard.ui.theme.NepaliKeyboardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * =============================================================================
 * THE TYPING SANDBOX
 * =============================================================================
 *
 * The problem this screen solves: a user who has just installed an IME cannot
 * tell whether it is broken or merely not selected. Every other app's text
 * field is the only place a keyboard can be tested, and testing there means
 * committing real text into somebody else's document — a chat message, a search
 * box, a form. If the transliteration engine produces something wrong, the user
 * has to delete it from an app they were trying to use.
 *
 * So there is a field here, inside our own app, that stores nothing.
 *
 * ### What makes this different from a plain `TextField`
 *
 * The obvious implementation — a Compose `TextField` in a `Column` — exercises
 * the *system* keyboard, not ours. It would tell the user nothing. What has to
 * happen instead is that the real keyboard UI runs inside *this* activity, and
 * its [InputConnectionController] is pointed at a real [EditText] so that every
 * composing span, deletion, cursor move and commit goes through the same code
 * path the IME uses.
 *
 * That means four things must be wired by hand, because an `Activity` has no
 * `InputMethodService` to do them:
 *
 *  1. **A [KeyboardViewModel]** — constructed with a `KeyboardLifecycleOwner`
 *     and a private [InputConnectionController]. Deliberately *not* the
 *     service's ViewModel: this one must not share composer state, because the
 *     user may be mid-word in a real app while the sandbox is open behind it.
 *
 *  2. **A live [InputConnection] into this screen's own [EditText].** Obtained
 *     from `EditText.onCreateInputConnection(...)` — the same method the
 *     framework calls when an IME attaches. This is the detail that makes the
 *     sandbox real rather than a mock-up: the keyboard writes through the same
 *     in-process connection object a real IME would use.
 *
 *  3. **The edit target as an [AndroidView]**, not a Compose `TextField`. A
 *     Compose text field creates its `InputConnection` internally and does not
 *     expose it; we need the raw `EditText` so we can call
 *     `onCreateInputConnection` ourselves. It is restyled to match the Compose
 *     surface so the seam is invisible.
 *
 *  4. **The keyboard docked at the bottom**, rendered by [KeyboardHost] with
 *     [NoOpServiceActions]. Every key works; only the actions that genuinely
 *     need a service (switching keyboards, hiding the window) do nothing —
 *     which is correct here, because the sandbox is not the active IME, so
 *     there is no window to hide and no other subtype to switch to.
 *
 * ### What is deliberately NOT wired
 *
 *  * **No IME-status check.** The sandbox does not care whether the user has
 *    enabled the keyboard in system settings; it works either way, which is the
 *    point. Someone who has not enabled it yet can still see what it does.
 *  * **No clipboard capture, no learned words.** The sandbox explicitly does
 *    not contribute to the user's model. Typing experiments must not pollute
 *    the dictionary, and copies made while testing must not appear in the
 *    keyboard's clipboard strip.
 *  * **No `onUpdateSelection` callback.** There is no service to deliver one,
 *    so caret movement is observed through the field's own text watcher
 *    instead. This covers taps, selections and programmatic moves, which is
 *    everything that matters for a test field.
 *
 * ### Retention
 *
 * Nothing leaves memory. The field is an in-memory [EditText]; the ViewModel is
 * scoped to the activity and dies with it; the lifecycle owner is disposed when
 * the composition goes away. Leave the screen and every character is gone —
 * which is what the on-screen note promises.
 */
class TypingSandboxActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val repository = SettingsRepository.get(this)

        setContent {
            val prefs by repository.flow.collectAsStateWithLifecycle(
                initialValue = KeyboardPreferences()
            )

            NepaliKeyboardTheme(themeMode = prefs.themeMode, dynamicColor = prefs.dynamicColor) {
                TypingSandboxScreen(onBack = { finish() })
            }
        }
    }
}

/**
 * The sandbox's whole visible surface: a toolbar, a field, a retention note,
 * and the keyboard.
 *
 * ### Layout: why a `Column` and not a bottom-aligned `Box`
 *
 * The keyboard is docked and the field takes whatever height is left, so the
 * field is `weight(1f)` inside a `Column`. A `Box` with the keyboard aligned to
 * `BottomCenter` would also dock it, but it would *overlay* the text instead of
 * shrinking the field — and then a user who increases the keyboard height in
 * settings, or switches one-handed mode on, would find the caret hidden behind
 * their own keyboard. Weighting the field makes the resize automatic.
 *
 * ### Why the system IME is suppressed
 *
 * The field is a real `EditText`, so the platform will try to show the *system*
 * keyboard for it. Two keyboards fighting over the bottom of the screen is
 * unusable. `android:windowSoftInputMode="adjustResize|stateAlwaysVisible"` is
 * declared on the activity, and the toolbar is the only thing that takes inset
 * padding, so the platform's attempt to shift the window upward is neutralised
 * and only our own keyboard moves anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypingSandboxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    // A lifecycle owner of our own, already driven to CREATED by the remember
    // helper. It is created inside the composition and therefore precedes any
    // ViewModel that needs it — which is exactly the ordering guarantee that
    // `InstallKeyboardViewTreeOwners` relies on in the IME.
    val owner = rememberKeyboardLifecycleOwner(application)

    // The sandbox's private controller. Never shared with the service's
    // instance: sharing would let the sandbox clobber the composing state of a
    // field the user is typing into in another app.
    val controller = remember { InputConnectionController() }

    // Activity-scoped coroutine scope for the ViewModel. `Main.immediate`
    // because every mutation it makes lands in Compose state synchronously; the
    // lexicon queries it launches hop to `Dispatchers.Default` themselves.
    val scope = remember {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    val viewModel = remember(owner, controller, scope) {
        KeyboardViewModel(
            application = application,
            lifecycleOwner = owner,
            input = controller,
            scope = scope
        )
    }

    // Drive the owner's lifecycle from the activity's, so that
    // `collectAsStateWithLifecycle` inside the keyboard suspends while the
    // sandbox is backgrounded and every `LaunchedEffect` is cancelled — the
    // same behaviour the live IME gets from its service callbacks.
    val hostLifecycle = LocalLifecycleOwner.current
    DisposableEffect(hostLifecycle, owner, scope) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> owner.startAndResume()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> owner.pause()
                else -> Unit
            }
        }
        hostLifecycle.lifecycle.addObserver(observer)
        onDispose {
            hostLifecycle.lifecycle.removeObserver(observer)
            // Tear down in dependency order: the owner first (it clears the
            // ViewModelStore), then the scope that was feeding it.
            owner.destroy()
            scope.cancel()
        }
    }

    // Text held in Compose state so the hint and the keyboard's own
    // word-composition logic can read it, but *written* only through the
    // EditText: the keyboard commits through the InputConnection, and a
    // parallel Compose-owned copy would go stale the moment a key is pressed.
    var fieldText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    // The live EditText, kept so the Clear action can reach it directly.
    var field by remember { mutableStateOf<EditText?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.sandbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            // Cleared through the field, not through the
                            // ViewModel: the composer must not be told "the
                            // user deleted everything" for a button press, or
                            // it would try to re-derive a word from nothing.
                            field?.let { target ->
                                target.text?.clear()
                                fieldText = TextFieldValue("")
                            }
                        }
                    ) {
                        Text(text = stringResource(R.string.sandbox_clear))
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Only the top padding is consumed: the keyboard at the bottom
                // is ours, so reserving the navigation-bar strip would leave a
                // dead band between the keyboard and the screen edge.
                .padding(top = padding.calculateTopPadding())
                .windowInsetsPadding(
                    WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
                )
        ) {
            SandboxField(
                value = fieldText,
                onFieldCreated = { raw ->
                    field = raw
                    bindSandboxEditor(raw, viewModel, controller, context, onTextChanged = { text, selection ->
                        fieldText = TextFieldValue(text = text, selection = selection)
                    })
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            SandboxNote()

            // The real keyboard. `NoOpServiceActions` because there is no IME
            // service behind this host — but every key still works, because the
            // keys write through `controller`, not through the service.
            KeyboardHost(
                viewModel = viewModel,
                serviceActions = NoOpServiceActions,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Attaches the keyboard's controller to [raw], the sandbox's [EditText].
 *
 * This is the sandbox's equivalent of `NepaliImeService.onStartInput` plus
 * `onUpdateSelection` rolled into one. The steps, and why each is needed:
 *
 *  1. **Synthesise an [EditorInfo].** A real IME is handed one by the platform;
 *     here there is no platform handoff, so we build the one that makes the
 *     sandbox most instructive. Multiline text with `CAP_SENTENCES` and a
 *     `SEND` action means: auto-capitalisation is live, Enter inserts a newline
 *     instead of committing, and the action key renders as "send" — the
 *     combination that exercises the most of the keyboard's conditional
 *     behaviour at once.
 *
 *  2. **Obtain the [InputConnection] from the view itself.**
 *     `EditText.onCreateInputConnection` returns the very object the framework
 *     would have handed us. Passing the same `EditorInfo` keeps the view's own
 *     expectations consistent with what the controller believes.
 *
 *  3. **Bind the controller, then tell the ViewModel.** Order matters: the
 *     ViewModel's first act on `onEditorChanged` is to read
 *     auto-capitalisation, which needs a live connection.
 *
 *  4. **Watch the text.** The sandbox has no `onUpdateSelection` callback, so
 *     the field's own [TextWatcher] is the only signal that the user tapped,
 *     selected or moved the caret. Mirroring both the text and the selection
 *     back into Compose state keeps the hint correct and gives the keyboard an
 *     accurate picture of the caret.
 *
 * @param onTextChanged receives the current text and the clamped selection.
 */
private fun bindSandboxEditor(
    raw: EditText,
    viewModel: KeyboardViewModel,
    controller: InputConnectionController,
    context: android.content.Context,
    onTextChanged: (String, TextRange) -> Unit
) {
    val info = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        // `initialSelStart`/`End` must be a valid, non-negative pair or the
        // controller's `hasInitialSelection` derivation and several editor
        // apps' own logic misread the field as having a pre-existing range.
        initialSelStart = 0
        initialSelEnd = 0
        initialCapsMode = 0
        hintText = context.getString(R.string.sandbox_hint)
        packageName = context.packageName
    }

    val connection: InputConnection? = raw.onCreateInputConnection(info)

    // Attach first, then inform the ViewModel — see step 3 above.
    controller.attach(info, connection)
    viewModel.onEditorChanged(info, controller)

    raw.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int
        ) = Unit

        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int
        ) = Unit

        override fun afterTextChanged(s: Editable?) {
            val caret = raw.selection
            // `selection` is -1 when nothing is selected *and* the view has no
            // caret yet (it happens on the very first layout pass). Clamping to
            // zero keeps `TextRange` legal, which it must be: a negative index
            // throws from `TextRange`'s constructor.
            val safeStart = if (caret >= 0) caret else 0
            val safeEnd = if (caret >= 0) caret else 0
            onTextChanged(s?.toString().orEmpty(), TextRange(safeStart, safeEnd))
        }
    })
}

/**
 * The sandbox's single text field.
 *
 * An [EditText] inside an [AndroidView] rather than a Compose text field, for
 * the reason given in the class KDoc: the `InputConnection` has to be reachable.
 *
 * `onFieldCreated` fires exactly once per `EditText` instance. It is a plain
 * callback rather than a `LaunchedEffect` because the binding work must happen
 * after the view exists but before the user can type into it, and
 * `AndroidView`'s `factory` is the only place that guarantee holds.
 *
 * The widget is restyled onto the Compose surface — transparent background, no
 * compound padding, the theme's own text colours — so the one non-Compose view
 * on screen does not read as a grey system rectangle dropped into the layout.
 * The hint is *not* set here: `SandboxField` draws its own greyed hint `Text`,
 * because the EditText's `hintText` in [EditorInfo] is a separate concern
 * (what the platform tells the IME) and having two hint mechanisms would let
 * them drift apart.
 */
@Composable
private fun SandboxField(
    value: TextFieldValue,
    onFieldCreated: (EditText) -> Unit,
    modifier: Modifier = Modifier
) {
    // `rememberUpdatedState` so a recomposition that produces a new lambda does
    // not require `AndroidView` to rebuild the EditText. Rebuilding would
    // detach the connection and lose the user's text mid-sentence.
    val currentOnCreated by rememberUpdatedState(onFieldCreated)
    val keyboardColors = KeyboardTheme.colors

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(keyboardColors.stripSurface)
            .padding(16.dp)
    ) {
        if (value.text.isEmpty()) {
            Text(
                text = stringResource(R.string.sandbox_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                EditText(context).apply {
                    // Transparent: the Compose background provides the card.
                    // The framework's default is a themed underline that would
                    // draw a line across the middle of our rounded shape.
                    background = null
                    setPadding(0, 0, 0, 0)
                    setTextColor(keyboardColors.keyText.toArgbCompat())
                    textSize = 18f
                    // Multiline and vertically scrolling: a long test
                    // paragraph must not be clipped to one line.
                    isSingleLine = false
                    maxLines = Int.MAX_VALUE
                    setHorizontallyScrolling(false)
                    gravity = Gravity.TOP or Gravity.START
                    // The caret stays visible; the selection handles do not
                    // compete with the keyboard's own cursor-drag gesture
                    // because dragging the spacebar moves the caret directly.
                    isCursorVisible = true
                    currentOnCreated(this)
                }
            }
        )
    }
}

/**
 * The retention note under the field.
 *
 * Permanently visible rather than a dismissible banner. A user experimenting
 * with transliteration needs to know *before* they type that nothing will be
 * kept; and a note that can be dismissed is a note that will be dismissed,
 * after which the screen looks exactly like a normal text box that might be
 * saving everything.
 */
@Composable
private fun SandboxNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start
    ) {
        // A thin accent rule instead of an icon: it reads as a footnote rather
        // than a warning, which is the right register for "nothing is stored".
        Spacer(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.sandbox_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start
        )
    }
}

/**
 * `Color.toArgb()`, named locally so the call site above reads clearly.
 *
 * Compose's `Color` is a packed `ULong`; `toArgb()` is the supported narrowing
 * to the `@ColorInt` int that `EditText.setTextColor` wants. Wrapping it in a
 * plain function keeps the two `android.graphics`/`androidx.compose.ui.graphics`
 * `Color` types from colliding in the import list — the file needs exactly one
 * of them, and it is Compose's.
 */
private fun androidx.compose.ui.graphics.Color.toArgbCompat(): Int = toArgb()
