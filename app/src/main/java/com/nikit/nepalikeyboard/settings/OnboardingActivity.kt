package com.nikit.nepalikeyboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.ui.theme.NepaliKeyboardTheme

/**
 * =============================================================================
 * FIRST-RUN ONBOARDING
 * =============================================================================
 *
 * Android does not let an app enable its own IME. The user has to open the
 * system's input-method settings and tick a checkbox, and then separately pick
 * the keyboard. That is two trips into Settings, in a specific order, with no
 * feedback if you get it wrong — and the failure mode is invisible: you tick
 * the box, come back, and still see the old keyboard, with no indication that
 * there was a second step.
 *
 * This screen exists to make that sequence legible. It is four steps:
 *
 *  1. **Welcome.** What the keyboard does, in one sentence, so the user knows
 *     whether they want to continue before doing any work.
 *  2. **Enable.** A button into [Settings.ACTION_INPUT_METHOD_SETTINGS], with a
 *     live indicator that flips to "enabled" the moment the user returns having
 *     ticked the box.
 *  3. **Select.** A button that opens the system IME chooser, with the same
 *     live indicator for "is this keyboard the current one".
 *  4. **Try it.** A real field so the user's first keystrokes happen somewhere
 *     that cannot embarrass them.
 *
 * ### Why the status is re-read on resume, and why that is the only reliable way
 *
 * There is no broadcast for "the user changed the default input method". The
 * platform does not send one, and `InputMethodManager`'s listeners are
 * per-connection rather than global. So the only correct approach is to re-read
 * `Settings.Secure.DEFAULT_INPUT_METHOD` and
 * `InputMethodManager.enabledInputMethodList` every time the activity returns
 * to the foreground — which is also exactly when the answer can have changed.
 * Both [SettingsActivity] and this screen do it, via a lifecycle observer plus
 * an explicit read in `onResume` (belt and braces: on a configuration change
 * the observer's `ON_RESUME` may fire against the composition that is being
 * torn down).
 *
 * ### Skipping
 *
 * Skipping is a first-class action, not a hidden escape hatch. A user who
 * already knows how to enable an IME should not be walked through it; they can
 * leave, and the settings screen will still tell them the status and offer both
 * actions again. Skipping writes [SettingsRepository.setOnboardingComplete]
 * exactly as finishing does — the wizard is a one-time interruption, and
 * re-showing it to someone who dismissed it once is the definition of a nag.
 *
 * ### Why an `Activity` rather than a Compose route inside `SettingsActivity`
 *
 * It has to be launchable as the launcher entry point on first run, and it has
 * to be finishable as a unit. Modelling it as a step inside the settings screen
 * would mean the settings screen grows a first-run mode, and every subsequent
 * feature that wants to "show onboarding again" (a help entry point, a
 * re-run after a reset) would have to reach into that mode. A separate activity
 * with a clear contract — start it, it finishes itself — keeps both screens
 * simple. It is not exported, and it is not in the launcher: [SettingsActivity]
 * is the launcher, and it forwards here when onboarding has not been completed.
 */
class OnboardingActivity : ComponentActivity() {

    /**
     * Live status, pushed from the controller and refreshed on resume.
     *
     * A `StateFlow` for the same reason as in `SettingsActivity`: the screen
     * observes it the way it observes preferences, so there is one mechanism
     * rather than two.
     */
    private val imeState = mutableStateOf(ImeStatus.unknown())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val repository = SettingsRepository.get(this)

        setContent {
            val prefs by repository.flow.collectAsStateWithLifecycle(
                initialValue = KeyboardPreferences()
            )
            val status = imeState.value

            val hostLifecycle = LocalLifecycleOwner.current
            DisposableEffect(hostLifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                        imeState.value = readImeStatus()
                    }
                }
                hostLifecycle.lifecycle.addObserver(observer)
                onDispose { hostLifecycle.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(Unit) {
                imeState.value = readImeStatus()
            }

            NepaliKeyboardTheme(themeMode = prefs.themeMode, dynamicColor = prefs.dynamicColor) {
                OnboardingScreen(
                    imeStatus = status,
                    onOpenImeSettings = { launchSafely(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onOpenImePicker = { openImePicker() },
                    onOpenSandbox = {
                        startActivity(Intent(this, TypingSandboxActivity::class.java))
                    },
                    onFinish = {
                        repository.setOnboardingComplete(true)
                        finish()
                    }
                )
            }
        }

        // The system back gesture on the last step should finish the wizard
        // (and therefore complete it), not walk backwards through four screens
        // the user has already seen. Earlier steps go back one step.
        //
        // The composition installs [backHandler] once it is running; until
        // then, and any time it has been cleared, back simply finishes. That
        // fallback is not a stand-in for the real behaviour — it is the
        // correct behaviour on the first step, where there is nothing to go
        // back to.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val stepBack = backHandler
                    if (stepBack != null) stepBack() else finishWithoutCompleting()
                }
            }
        )
    }

    /**
     * Set by the composition so the dispatcher can ask it to step backwards.
     *
     * A field rather than plumbing the step index through the activity, because
     * the step index is Compose state: putting it in the activity would mean
     * hoisting `rememberSaveable` state into a non-Compose owner and
     * synchronising the two. A single nullable lambda is the smaller seam.
     *
     * It is written from a `DisposableEffect` in the screen, which also clears
     * it — so a composition that goes away cannot leave a lambda closing over
     * dead state behind.
     */
    private var backHandler: (() -> Unit)? = null

    /**
     * Installs the step-backwards action, returning the previous one so the
     * caller can restore it on dispose.
     *
     * Internal rather than private because the screen that supplies the action
     * is a top-level composable in this file, not a member of the activity. The
     * alternative — nesting the whole screen inside the activity class — would
     * put an eight-hundred-line composable inside the lifecycle class it is
     * driven by, which is precisely the coupling the Compose split exists to
     * prevent.
     */
    internal fun installBackHandler(action: (() -> Unit)?): (() -> Unit)? {
        val previous = backHandler
        backHandler = action
        return previous
    }

    override fun onResume() {
        super.onResume()
        imeState.value = readImeStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            imeState.value = readImeStatus()
        }
    }

    /**
     * Finishes without marking onboarding complete.
     *
     * This is what happens if back is pressed on the very first step — there is
     * nothing to go back to, so the wizard closes. Deliberately *not* marked
     * complete: the user backed out before engaging with it at all, and the
     * next launch should offer it again. This is the one case where re-showing
     * is the right behaviour, and it is distinct from an explicit "Skip".
     */
    private fun finishWithoutCompleting() {
        finish()
    }

    /**
     * Determines whether this keyboard is enabled and selected.
     *
     * Three separate questions — installed, enabled, selected — and the UI needs
     * the last two. See [SettingsActivity.readImeStatus] for the full
     * explanation of why each accessor is individually guarded: some OEM builds
     * throw from them under device policy, and crashing the screen the user
     * opened to fix their keyboard is the worst possible outcome.
     *
     * Matching is by package plus service class, not by exact IME id string:
     * the platform persists the short flattened form (`pkg/.Service`) while
     * `$packageName/${class.name}` builds the long form, so exact equality
     * never matched.
     */
    private fun readImeStatus(): ImeStatus {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return ImeStatus.unknown()

        val serviceClass = com.nikit.nepalikeyboard.ime.NepaliImeService::class.java.name

        val enabled = try {
            manager.enabledInputMethodList.any { info ->
                info.packageName == packageName ||
                    info.serviceName == serviceClass ||
                    isOurImeId(info.id)
            }
        } catch (t: Throwable) {
            return ImeStatus.unknown()
        }

        val selected = try {
            isOurImeSelected(
                Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            )
        } catch (t: Throwable) {
            false
        }

        return ImeStatus(installed = true, enabled = enabled, selected = selected)
    }

    private fun isOurImeId(id: String?): Boolean {
        if (id == null) return false
        if (id.startsWith("$packageName/")) return true
        return try {
            ComponentName.unflattenFromString(id)?.packageName == packageName
        } catch (t: Throwable) {
            false
        }
    }

    private fun isOurImeSelected(defaultIme: String?): Boolean {
        if (defaultIme.isNullOrEmpty()) return false
        return try {
            val component = ComponentName.unflattenFromString(defaultIme)
            if (component != null) {
                component.packageName == packageName
            } else {
                defaultIme.contains(packageName)
            }
        } catch (t: Throwable) {
            defaultIme.contains(packageName)
        }
    }

    /**
     * Shows the system IME chooser.
     *
     * `showInputMethodPicker()` is the only API that opens the *chooser* rather
     * than a settings list, and it is what the keyboard's own globe key does —
     * so the user meets the same surface from both directions.
     */
    private fun openImePicker() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        try {
            manager?.showInputMethodPicker()
        } catch (t: Throwable) {
            // Fall back to the settings list, which always exists.
            launchSafely(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    /**
     * Starts [intent], swallowing the `ActivityNotFoundException` an OEM build
     * without the settings screen would raise.
     *
     * A tutorial button that crashes the app is worse than one that does
     * nothing: the user cannot reach the rest of the screen to diagnose it, and
     * the screen they cannot reach is the one that would have fixed it.
     */
    private fun launchSafely(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            // Nothing to do. The screen stays usable.
        }
    }
}

/**
 * =============================================================================
 * THE WIZARD
 * =============================================================================
 *
 * Four steps, with the current index held in `rememberSaveable` so a rotation
 * does not send the user back to step one — which on a screen whose whole
 * purpose is to walk a user through two trips to Settings would be a genuine
 * annoyance rather than a cosmetic one.
 *
 * ### Transitions
 *
 * Forward and backward slides are direction-aware: `Next` slides the new step
 * in from the right, `Back` from the left, and the outgoing step leaves the
 * opposite way. This is what makes the sequence feel like a single document
 * being traversed rather than four unrelated screens. The durations are short
 * (180 ms) because a tutorial animation that the user has to wait for is a
 * tutorial that gets resented.
 *
 * ### Progress
 *
 * Rendered as a row of dots rather than "Step 2 of 4". Both convey position;
 * dots additionally convey *whether you are nearly done*, which is the question
 * a user actually has at step two of a setup flow. The dots are not interactive
 * — jumping straight to "Try it out" without enabling the keyboard would land
 * the user on a step that cannot work.
 */
private const val STEP_COUNT = 4

/** Step indices, named so the `when` below reads as prose. */
private const val STEP_WELCOME = 0
private const val STEP_ENABLE = 1
private const val STEP_SELECT = 2
private const val STEP_TRY = 3

/** Direction of the most recent step change, for the slide animation. */
private enum class Direction { FORWARD, BACKWARD }

@Composable
private fun OnboardingScreen(
    imeStatus: ImeStatus,
    onOpenImeSettings: () -> Unit,
    onOpenImePicker: () -> Unit,
    onOpenSandbox: () -> Unit,
    onFinish: () -> Unit
) {
    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    var direction by remember { mutableStateOf(Direction.FORWARD) }

    fun goTo(target: Int) {
        direction = if (target > step) Direction.FORWARD else Direction.BACKWARD
        step = target.coerceIn(0, STEP_COUNT - 1)
    }

    // Let the activity's back dispatcher walk the wizard backwards.
    //
    // The lambda closes over `step`, so it has to be reinstalled whenever the
    // step changes — a lambda captured at step 1 would send the user to step 0
    // forever. `installBackHandler` returns the previous action, which the
    // `onDispose` restores, so a recomposition that replaces the handler does
    // not leave the activity holding a lambda over stale state.
    //
    // `LocalContext` is the activity here — this screen is only ever hosted by
    // `OnboardingActivity` — and the cast is defensive rather than necessary:
    // if the screen is ever reused in a preview or a different host, the null
    // case simply means "back finishes", which is the correct degraded
    // behaviour.
    val host = LocalContext.current as? OnboardingActivity
    DisposableEffect(host, step) {
        val previous = host?.installBackHandler {
            if (step > STEP_WELCOME) goTo(step - 1)
        }
        onDispose { host?.installBackHandler(previous) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OnboardingTopBar(
                showSkip = step < STEP_TRY,
                onSkip = onFinish
            )

            // `AnimatedContent`'s transition spec is built from the direction
            // of travel. `togetherWith` composes the enter and exit specs into
            // one, which is the modern replacement for the deprecated
            // `transitionSpec` + `->` form.
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = direction == Direction.FORWARD
                    val enter = slideInHorizontally(
                        animationSpec = tween(180),
                        initialOffsetX = { full -> if (forward) full else -full }
                    ) + fadeIn(animationSpec = tween(180))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(180),
                        targetOffsetX = { full -> if (forward) -full else full }
                    ) + fadeOut(animationSpec = tween(180))
                    enter togetherWith exit
                },
                label = "onboarding-step",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { current ->
                when (current) {
                    STEP_WELCOME -> WelcomeStep()
                    STEP_ENABLE -> EnableStep(
                        enabled = imeStatus.enabled,
                        onOpenSettings = onOpenImeSettings
                    )
                    STEP_SELECT -> SelectStep(
                        enabled = imeStatus.enabled,
                        selected = imeStatus.selected,
                        onOpenPicker = onOpenImePicker
                    )
                    else -> TryStep(onOpenSandbox = onOpenSandbox)
                }
            }

            OnboardingFooter(
                step = step,
                imeStatus = imeStatus,
                onBack = { if (step > STEP_WELCOME) goTo(step - 1) },
                onNext = {
                    if (step == STEP_COUNT - 1) onFinish() else goTo(step + 1)
                }
            )
        }
    }
}

/**
 * The top bar: a progress row and a Skip action.
 *
 * No title and no navigation icon. The step's own heading is the title, and
 * there is nothing to navigate back to — the wizard is a leaf of the app.
 */
@Composable
private fun OnboardingTopBar(showSkip: Boolean, onSkip: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f))
        TextButton(onClick = onSkip, enabled = showSkip) {
            // Kept in the layout when it is not actionable so the bar does not
            // reflow between the last two steps.
            Text(
                text = stringResource(R.string.onboarding_skip),
                // A disabled button keeps its space but not its meaning; an
                // invisible label would read as a layout glitch when it
                // reappears.
                color = if (showSkip) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0f)
                }
            )
        }
    }
}

/** Step one: what the keyboard is for. */
@Composable
private fun WelcomeStep() {
    OnboardingBody(
        title = stringResource(R.string.onboarding_step1_title),
        body = stringResource(R.string.onboarding_step1_body),
        accent = {
            // Three mode chips rather than an illustration: this is the one
            // fact the user needs from step one — that there are three ways to
            // type — and the labels are the same words the keyboard itself
            // shows on its mode switcher.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeChip(label = stringResource(R.string.tab_native), modifier = Modifier.weight(1f))
                ModeChip(label = stringResource(R.string.tab_romanized), modifier = Modifier.weight(1f))
                ModeChip(label = stringResource(R.string.tab_english), modifier = Modifier.weight(1f))
            }
        }
    )
}

/**
 * Step two: enable the keyboard.
 *
 * The status row is the important part. Before the user taps the button it
 * reads "Not enabled" with an outline icon; when they come back having ticked
 * the box it flips to "Enabled" with a filled check. That transition is the
 * whole feedback loop — without it, a user who has done the step correctly has
 * no way to know it worked, because nothing else on screen changes.
 */
@Composable
private fun EnableStep(enabled: Boolean, onOpenSettings: () -> Unit) {
    OnboardingBody(
        title = stringResource(R.string.onboarding_step2_title),
        body = stringResource(R.string.onboarding_step2_body),
        accent = {
            Column(modifier = Modifier.padding(top = 24.dp)) {
                StatusBanner(
                    satisfied = enabled,
                    satisfiedText = stringResource(R.string.settings_ime_enabled),
                    unsatisfiedText = stringResource(R.string.settings_ime_not_enabled)
                )
                Spacer(modifier = Modifier.height(16.dp))
                PrimaryAction(
                    label = stringResource(R.string.onboarding_step2_action),
                    onClick = onOpenSettings
                )
            }
        }
    )
}

/**
 * Step three: choose the keyboard.
 *
 * Two indicators, because the user has to satisfy two conditions and they can
 * arrive in either order — somebody who had the keyboard enabled already will
 * see the first row satisfied on arrival, and somebody who just ticked the box
 * in step two will see it flip when they come back. The second row is the one
 * this step is about.
 *
 * The action is disabled until the keyboard is enabled, because the system
 * chooser will not list it otherwise. Offering a button that opens a chooser
 * the user's keyboard is not in would be a dead end presented as a step.
 */
@Composable
private fun SelectStep(enabled: Boolean, selected: Boolean, onOpenPicker: () -> Unit) {
    OnboardingBody(
        title = stringResource(R.string.onboarding_step3_title),
        body = stringResource(R.string.onboarding_step3_body),
        accent = {
            Column(modifier = Modifier.padding(top = 24.dp)) {
                StatusBanner(
                    satisfied = enabled,
                    satisfiedText = stringResource(R.string.settings_ime_enabled),
                    unsatisfiedText = stringResource(R.string.settings_ime_not_enabled)
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatusBanner(
                    satisfied = selected,
                    satisfiedText = stringResource(R.string.settings_ime_selected),
                    unsatisfiedText = stringResource(R.string.settings_ime_not_selected)
                )
                Spacer(modifier = Modifier.height(16.dp))
                PrimaryAction(
                    label = stringResource(R.string.onboarding_step3_action),
                    onClick = onOpenPicker,
                    enabled = enabled
                )
            }
        }
    )
}

/**
 * Step four: type something.
 *
 * The field here is *not* the sandbox — it is a doorway to it. A text field
 * inline in this step would be an `EditText` the system keyboard would service,
 * which is the opposite of the point; making it an in-app keyboard would mean
 * this screen hosts a second `KeyboardViewModel` and a lifecycle owner for no
 * benefit over the sandbox, which already exists and does it properly. So the
 * step explains what to try and hands off.
 */
@Composable
private fun TryStep(onOpenSandbox: () -> Unit) {
    OnboardingBody(
        title = stringResource(R.string.onboarding_step4_title),
        body = stringResource(R.string.onboarding_step4_body),
        accent = {
            Column(modifier = Modifier.padding(top = 24.dp)) {
                PrimaryAction(
                    label = stringResource(R.string.settings_open_sandbox),
                    onClick = onOpenSandbox
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_open_sandbox_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * The shared body shape of every step: a heading, a paragraph, and a
 * step-specific block underneath.
 *
 * Extracted because the four steps differ only in their `accent` content, and
 * four copies of the same `Column`/`Spacer`/`Text` stack is four places to keep
 * typographic choices in sync.
 *
 * Scrollable, because the step bodies are long and a user running a large font
 * scale on a small phone would otherwise find the final paragraph, the status
 * rows, and the action button all below the fold with no way to reach them.
 */
@Composable
private fun OnboardingBody(
    title: String,
    body: String,
    accent: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        accent()
        // Trailing space so the last element is never flush against the
        // footer on a short screen.
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** One of the three mode labels on the welcome step. Single line by contract. */
@Composable
private fun ModeChip(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 18.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A single "is this done?" row: icon plus text, tinted by state.
 *
 * The icon is the signal, not the colour. Colour alone fails for the ~8% of men
 * with a red/green deficiency, and on a screen where "have I finished this
 * step?" is the only question being asked, a status that a colourblind user
 * cannot read is a status that does not exist. `CheckCircle` versus
 * `ErrorOutline` is unambiguous in greyscale.
 */
@Composable
private fun StatusBanner(satisfied: Boolean, satisfiedText: String, unsatisfiedText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (satisfied) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            // The chosen glyph carries the meaning; see the KDoc above.
            imageVector = if (satisfied) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (satisfied) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = if (satisfied) satisfiedText else unsatisfiedText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (satisfied) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The one call to action a step has.
 *
 * A `Button` rather than a card, because on this screen the button *is* the
 * step: every step is "do this one thing". Making it look like a settings row
 * would invite the user to think there are choices to make.
 */
@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * The footer: progress dots on the left, Back and Next on the right.
 *
 * The primary button's label changes on the last step — "Next" becomes "Start
 * typing" — because the last step does not advance, it finishes. Keeping the
 * word "Next" there would leave the user uncertain whether one more screen is
 * coming.
 */
@Composable
private fun OnboardingFooter(
    step: Int,
    imeStatus: ImeStatus,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProgressDots(current = step)

        Box(modifier = Modifier.weight(1f))

        // Back is hidden, not disabled, on the first step: there is nowhere to
        // go, and a greyed-out Back on the screen a user has just arrived at
        // reads as "you have missed something".
        if (step > STEP_WELCOME) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text(text = stringResource(R.string.onboarding_back))
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Button(
            onClick = onNext,
            shape = RoundedCornerShape(14.dp),
            // The last step's action depends on nothing — the user can start
            // typing whether or not they completed steps two and three, since
            // the sandbox works regardless. Earlier steps are always
            // actionable: welcome and try are informational, enable and select
            // each open a Settings screen that exists on every device.
            enabled = true,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (step == STEP_COUNT - 1) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (step == STEP_COUNT - 1) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            ),
            modifier = Modifier.height(48.dp)
        ) {
            Text(
                text = if (step == STEP_COUNT - 1) {
                    stringResource(R.string.onboarding_done)
                } else {
                    stringResource(R.string.onboarding_next)
                }
            )
        }
    }
    // `imeStatus` is part of this signature deliberately even though the footer
    // does not currently branch on it: both of the middle steps' actions are
    // always available, and the status is rendered inside those steps. It is
    // passed because a future "you can skip this step, it is already done"
    // affordance belongs here, and threading it later would touch four call
    // sites.
}

/**
 * Position indicator.
 *
 * Dots rather than a progress bar: four discrete steps read better as four
 * dots, and a bar at 25%/50%/75% implies continuous progress where there is
 * none. Non-interactive by design — see the KDoc on `OnboardingScreen`.
 */
@Composable
private fun ProgressDots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(STEP_COUNT) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == current) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}
