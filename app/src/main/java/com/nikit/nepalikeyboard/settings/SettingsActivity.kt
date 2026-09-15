package com.nikit.nepalikeyboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nikit.nepalikeyboard.BuildConfig
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.debug.CrashActivity
import com.nikit.nepalikeyboard.debug.CrashScreenGuard
import com.nikit.nepalikeyboard.debug.PendingCrash
import com.nikit.nepalikeyboard.ime.InputMode
import com.nikit.nepalikeyboard.ime.OneHandedSide
import com.nikit.nepalikeyboard.lexicon.LexiconRepository
import com.nikit.nepalikeyboard.ui.theme.NepaliKeyboardTheme
import com.nikit.nepalikeyboard.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * =============================================================================
 * SETTINGS
 * =============================================================================
 *
 * The app's only user-facing screen, and the entry point the launcher icon
 * opens. Everything the keyboard can be configured to do is here.
 *
 * ### Why this activity also opens on `ACCESSIBILITY_SETTINGS`-style intents
 *
 * It does not — and that is worth stating because it is the obvious wrong
 * design. The system IME switcher launches this activity with an explicit
 * component intent when the user taps the gear beside our name, and the
 * launcher launches it too. Both land on the same screen, which is correct:
 * a user who came from the keyboard switcher wants to configure the keyboard.
 *
 * ### Why the IME-enabled state is re-read on every resume
 *
 * There is no broadcast for "the user enabled an input method". The only
 * reliable signal is that our activity was resumed — the settings screen they
 * enabled us from is now behind us. So the state is re-read in `onResume` and
 * in a lifecycle observer, and the setup card reflects reality rather than a
 * cached guess. Getting this wrong is how keyboards end up showing "Not
 * enabled" to a user who just enabled them.
 */
class SettingsActivity : ComponentActivity() {

    /**
     * Live IME status, pushed from the controller and refreshed on resume.
     *
     * Exposed as a `StateFlow` so the Compose screen can observe it the same
     * way it observes preferences, rather than needing a second mechanism.
     */
    private val imeState = MutableStateFlow(ImeStatus.unknown())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val repository = SettingsRepository.get(this)

        // Surface a crash from the previous run before anything else.
        //
        // This is the other half of CrashHandler: an IME that dies leaves no
        // window behind to explain itself, so the report has to be shown on the
        // *next* launch. Forwarding here — rather than composing an overlay —
        // keeps the crash UI in one place and means it renders even if this
        // screen's own composition is the thing that was crashing.
        //
        // CrashScreenGuard bounds this: if the crash screen itself keeps dying
        // (a crash loop), three rapid showings is enough and we stop
        // auto-forwarding rather than trapping the user in a loop.
        if (savedInstanceState == null &&
            PendingCrash.isPending(this) &&
            CrashScreenGuard.shouldAutoShow(this)
        ) {
            startActivity(Intent(this, CrashActivity::class.java))
        }

        // First launch: hand off to the setup wizard.
        //
        // Done here rather than by making OnboardingActivity the launcher,
        // because the launcher alias would have to be removed after setup and
        // there is no clean way to do that — Android gives no API to change
        // which activity an intent filter resolves to at runtime. Forwarding
        // from the stable launcher keeps the manifest static and the decision
        // data-driven, and it means the user can always reach Settings even if
        // they dismissed the wizard.
        //
        // `savedInstanceState == null` guards the rotation case: without it, a
        // user who rotated the settings screen on their very first run would be
        // bounced into the wizard again, because `onboardingComplete` is still
        // false and `onCreate` runs again.
        //
        // The read is off the main thread because DataStore is a disk read and
        // `runBlocking` here would add its latency to every cold start of the
        // launcher activity. The forwarding therefore happens a frame or two
        // after the settings screen has begun to compose; the fade-in covers it
        // and the alternative — a splash of indeterminate length — is worse.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                val completed = try {
                    repository.load().onboardingComplete
                } catch (t: Throwable) {
                    // If preferences cannot be read we must not trap the user in
                    // a wizard they may already have finished. Assume complete.
                    true
                }
                if (!completed) {
                    startActivity(Intent(this@SettingsActivity, OnboardingActivity::class.java))
                }
            }
        }

        setContent {
            val prefs by repository.flow.collectAsStateWithLifecycle(
                initialValue = KeyboardPreferences()
            )
            val status by imeState.collectAsStateWithLifecycle()

            // Re-read the IME status whenever we come back to the foreground.
            // See the class KDoc for why there is no better signal.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                        imeState.value = readImeStatus()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // And once at composition, for the case where the activity is
            // created already resumed.
            LaunchedEffect(Unit) {
                imeState.value = readImeStatus()
            }

            // Auto-update: check for new versions on first composition and
            // observe the state for the banner.
            val updateManager = remember { UpdateManager.get(this) }
            val updateState by updateManager.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                updateManager.checkForUpdate()
            }

            NepaliKeyboardTheme(themeMode = prefs.themeMode, dynamicColor = prefs.dynamicColor) {
                SettingsScreen(
                    prefs = prefs,
                    imeStatus = status,
                    updateState = updateState,
                    onCheckUpdate = { updateManager.reset(); lifecycleScope.launch { updateManager.checkForUpdate() } },
                    onDownloadUpdate = { url -> lifecycleScope.launch { updateManager.downloadApk(url) } },
                    onInstallUpdate = { updateManager.installApk(this@SettingsActivity) },
                    onDismissUpdate = { updateManager.reset() },
                    onPreferenceChange = { change -> change(repository) },
                    onOpenImeSettings = { openImeSettings() },
                    onOpenImePicker = { openImePicker() },
                    onOpenSandbox = { startActivity(Intent(this, TypingSandboxActivity::class.java)) },
                    onOpenDebug = { startActivity(Intent(this, CrashActivity::class.java)) },
                    onOpenOnboarding = { startActivity(Intent(this, OnboardingActivity::class.java)) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Belt and braces with the lifecycle observer: on configuration change
        // the composition is rebuilt and the observer's ON_RESUME may have
        // already fired against the old composition.
        imeState.value = readImeStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Returning from the system IME settings restores window focus before
        // ON_RESUME is delivered to the new composition on some OEM builds.
        // Refreshing here is what makes the checklist flip live.
        if (hasFocus) {
            imeState.value = readImeStatus()
        }
    }

    /**
     * Determines whether this keyboard is installed, enabled, and active.
     *
     * Three separate questions, and the UI needs all three:
     *
     *  * **Installed** — always true if this code is running.
     *  * **Enabled** — the user has ticked us in the system's input-method list.
     *    Without it we are not offered anywhere and nothing else works.
     *  * **Selected** — we are the *current* keyboard. A user can have several
     *    enabled and only one selected, so "enabled" is not sufficient to
     *    explain why the keyboard did not appear.
     *
     * Matching is by package plus service class, not by exact IME id string:
     * the platform stores the id flattened to short form
     * (`pkg/.ime.Service`) while `$packageName/${class.name}` builds the long
     * form (`pkg/pkg.ime.Service`). Comparing exact strings therefore never
     * matched and the screen permanently showed "Not enabled".
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
            // Some OEM builds throw from these accessors when the IME service
            // has been disabled by device policy. Treat as "unknown" rather than
            // crashing the settings screen the user opened to fix it.
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

    /**
     * True when an IME id string refers to this app, in either flattened form.
     *
     * Accepts the long form, the short form, and anything unflattenable that
     * still names our package — OEM builds are inconsistent about which form
     * they persist.
     */
    private fun isOurImeId(id: String?): Boolean {
        if (id == null) return false
        if (id.startsWith("$packageName/")) return true
        return try {
            ComponentName.unflattenFromString(id)?.packageName == packageName
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * True when the system's default-IME string names this keyboard.
     *
     * Parsed via [ComponentName.unflattenFromString] rather than exact string
     * equality, so both `pkg/.Service` and `pkg/pkg.Service` match.
     */
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
     * Opens the system's list of input methods.
     *
     * `Settings.ACTION_INPUT_METHOD_SETTINGS` on API 26+ is the canonical
     * destination. There is an older per-IME intent
     * (`ACTION_INPUT_METHOD_SUBTYPE_SETTINGS`) that some OEMs require, but it
     * is deprecated and on modern devices forwards to the same screen; using it
     * would add a branch for no gain.
     */
    private fun openImeSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchSafely(intent)
    }

    /**
     * Shows the system IME chooser.
     *
     * `InputMethodManager.showInputMethodPicker()` is the only API that opens
     * the *chooser* rather than a settings list, and it is what the globe key
     * does too — so the user sees the same surface from both entry points.
     */
    private fun openImePicker() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        try {
            manager.showInputMethodPicker()
        } catch (t: Throwable) {
            // Fall back to the settings list, which always exists.
            openImeSettings()
        }
    }

    /**
     * Starts [intent], swallowing the `ActivityNotFoundException` an OEM build
     * without the settings screen would raise.
     *
     * A settings shortcut that crashes the app is worse than one that does
     * nothing: the user cannot reach the rest of the screen to diagnose it.
     */
    private fun launchSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            // Nothing to do. The screen stays usable.
        }
    }
}

/**
 * The three facts the setup card needs about this keyboard.
 *
 * A data class rather than three booleans passed separately so that the
 * "unknown" case — where the platform refused to answer — is representable. An
 * `unknown` status renders differently from "not enabled", because telling a
 * user their keyboard is off when we simply could not tell would send them
 * hunting for a problem that may not exist.
 */
data class ImeStatus(
    val installed: Boolean,
    val enabled: Boolean,
    val selected: Boolean
) {
    companion object {
        /** The platform would not tell us; render neutrally. */
        fun unknown(): ImeStatus = ImeStatus(installed = true, enabled = false, selected = false)
    }
}

/**
 * The settings screen.
 *
 * A single `LazyColumn` of sections rather than a navigation graph. The whole
 * surface is roughly four screens' worth of controls and every one of them is
 * reachable by scrolling; a nav graph would add a back stack, a toolbar with a
 * title that changes, and four composables that each need the preferences
 * passed down — for a saving of nothing, since the list is short enough that
 * scrolling to a setting takes one gesture.
 *
 * @param onPreferenceChange receives a lambda that applies a change to the
 *        repository. Written this way so the screen does not need a reference
 *        to the repository itself, which keeps it previewable and testable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    prefs: KeyboardPreferences,
    imeStatus: ImeStatus,
    updateState: UpdateManager.State,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (String) -> Unit,
    onInstallUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onPreferenceChange: ((SettingsRepository) -> Unit) -> Unit,
    onOpenImeSettings: () -> Unit,
    onOpenImePicker: () -> Unit,
    onOpenSandbox: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenOnboarding: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) }
            )
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding(),
                bottom = insets.calculateBottomPadding() + 24.dp
            )
        ) {
            // Update banner — shown only when there's actionable state.
            if (updateState !is UpdateManager.State.Idle &&
                updateState !is UpdateManager.State.UpToDate
            ) {
                item {
                    UpdateBanner(
                        state = updateState,
                        onCheck = onCheckUpdate,
                        onDownload = onDownloadUpdate,
                        onInstall = onInstallUpdate,
                        onDismiss = onDismissUpdate
                    )
                }
            }

            item {
                SetupSection(
                    status = imeStatus,
                    onOpenImeSettings = onOpenImeSettings,
                    onOpenImePicker = onOpenImePicker,
                    onOpenSandbox = onOpenSandbox,
                    onOpenDebug = onOpenDebug,
                    onOpenOnboarding = onOpenOnboarding
                )
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_layout))

                // Default input mode. Presented as a row of three choices
                // rather than a dropdown, because there are three and they are
                // the single most consequential setting on the screen.
                ChoiceRow(
                    title = stringResource(R.string.settings_default_mode),
                    options = listOf(
                        InputMode.ROMANIZED to stringResource(R.string.tab_romanized),
                        InputMode.DEVANAGARI to stringResource(R.string.tab_native),
                        InputMode.ENGLISH to stringResource(R.string.tab_english)
                    ),
                    selected = prefs.defaultMode,
                    onSelect = { mode ->
                        onPreferenceChange { it.setDefaultMode(mode) }
                    }
                )

                // Keyboard height. The label shows the live value so the slider
                // has a number to aim at; the sandbox below is where the change
                // is actually seen.
                SliderRow(
                    title = stringResource(R.string.settings_keyboard_height),
                    valueLabel = stringResource(
                        R.string.settings_keyboard_height_value,
                        prefs.keyboardHeightDp
                    ),
                    value = prefs.keyboardHeightDp.toFloat(),
                    range = KeyboardPreferences.HEIGHT_RANGE_DP.first.toFloat()..
                        KeyboardPreferences.HEIGHT_RANGE_DP.last.toFloat(),
                    steps = 0,
                    onValueChange = { raw ->
                        onPreferenceChange { it.setKeyboardHeightDp(raw.toInt()) }
                    }
                )
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_typing))

                SwitchRow(
                    title = stringResource(R.string.settings_auto_capitalize),
                    subtitle = stringResource(R.string.settings_auto_capitalize_desc),
                    checked = prefs.autoCapitalise,
                    onCheckedChange = { onPreferenceChange { repo -> repo.setAutoCapitalise(it) } }
                )

                SwitchRow(
                    title = stringResource(R.string.settings_double_space_period),
                    subtitle = stringResource(R.string.settings_double_space_period_desc),
                    checked = prefs.doubleSpacePeriod,
                    onCheckedChange = { onPreferenceChange { repo -> repo.setDoubleSpacePeriod(it) } }
                )

                SwitchRow(
                    title = stringResource(R.string.settings_auto_correct),
                    subtitle = stringResource(R.string.settings_auto_correct_desc),
                    checked = prefs.showSuggestions,
                    onCheckedChange = { onPreferenceChange { repo -> repo.setShowSuggestions(it) } }
                )

                SwitchRow(
                    title = stringResource(R.string.settings_learn_words),
                    subtitle = stringResource(R.string.settings_learn_words_desc),
                    checked = prefs.learnWords,
                    onCheckedChange = { onPreferenceChange { repo -> repo.setLearnWords(it) } }
                )
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_appearance))

                ChoiceRow(
                    title = stringResource(R.string.settings_theme_mode),
                    options = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                        ThemeMode.AMOLED to stringResource(R.string.settings_theme_amoled)
                    ),
                    selected = prefs.themeMode,
                    onSelect = { mode -> onPreferenceChange { it.setThemeMode(mode) } }
                )

                // Dynamic colour is offered but disabled below API 31, where the
                // platform has no wallpaper-derived palette to hand us.
                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        stringResource(R.string.settings_dynamic_color_desc)
                    } else {
                        stringResource(R.string.settings_dynamic_color_unavailable)
                    },
                    checked = prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    onCheckedChange = { onPreferenceChange { repo -> repo.setDynamicColor(it) } }
                )

                SwitchRow(
                    title = stringResource(R.string.settings_key_borders),
                    subtitle = null,
                    checked = prefs.showKeyBorders,
                    onCheckedChange = { onPreferenceChange { repo -> repo.setShowKeyBorders(it) } }
                )
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_feedback))

                SwitchRow(
                    title = stringResource(R.string.settings_haptic),
                    subtitle = null,
                    checked = prefs.hapticsEnabled,
                    onCheckedChange = { onPreferenceChange { repo -> repo.setHapticsEnabled(it) } }
                )

                // The strength slider is only meaningful with haptics on; it is
                // disabled rather than hidden so the layout does not jump when
                // the switch above it is toggled.
                SliderRow(
                    title = stringResource(R.string.settings_haptic_strength),
                    valueLabel = if (prefs.hapticStrength == KeyboardPreferences.HAPTIC_SYSTEM_DEFAULT) {
                        stringResource(R.string.settings_haptic_system_default)
                    } else {
                        prefs.hapticStrength.toString()
                    },
                    value = prefs.hapticStrength
                        .takeIf { it > 0 }
                        ?.toFloat()
                        ?: KeyboardPreferences.HAPTIC_STRENGTH_RANGE.first.toFloat(),
                    range = KeyboardPreferences.HAPTIC_STRENGTH_RANGE.first.toFloat()..
                        KeyboardPreferences.HAPTIC_STRENGTH_RANGE.last.toFloat(),
                    steps = 0,
                    enabled = prefs.hapticsEnabled,
                    onValueChange = { raw ->
                        onPreferenceChange { it.setHapticStrength(raw.toInt()) }
                    }
                )

                SwitchRow(
                    title = stringResource(R.string.settings_sound),
                    subtitle = stringResource(R.string.settings_sound_desc),
                    checked = prefs.soundEnabled,
                    onCheckedChange = { onPreferenceChange { repo -> repo.setSoundEnabled(it) } }
                )
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_clipboard))

                SwitchRow(
                    title = stringResource(R.string.settings_clipboard_history),
                    subtitle = stringResource(R.string.settings_clipboard_history_desc),
                    checked = prefs.clipboardHistoryEnabled,
                    onCheckedChange = { onPreferenceChange { repo -> repo.setClipboardHistoryEnabled(it) } }
                )

                SliderRow(
                    title = stringResource(R.string.settings_clipboard_limit),
                    valueLabel = prefs.clipboardHistoryLimit.toString(),
                    value = prefs.clipboardHistoryLimit.toFloat(),
                    range = KeyboardPreferences.CLIPBOARD_LIMIT_RANGE.first.toFloat()..
                        KeyboardPreferences.CLIPBOARD_LIMIT_RANGE.last.toFloat(),
                    steps = 0,
                    enabled = prefs.clipboardHistoryEnabled,
                    onValueChange = { raw ->
                        onPreferenceChange { it.setClipboardHistoryLimit(raw.toInt()) }
                    }
                )

                // The one-handed setting lives here rather than under Layout
                // because it is a reachability affordance, and because Layout
                // already carries three controls.
                ChoiceRow(
                    title = stringResource(R.string.settings_one_handed),
                    options = listOf(
                        OneHandedSide.NONE to stringResource(R.string.settings_one_handed_off),
                        OneHandedSide.LEFT to stringResource(R.string.settings_one_handed_left),
                        OneHandedSide.RIGHT to stringResource(R.string.settings_one_handed_right)
                    ),
                    selected = prefs.oneHandedSide,
                    onSelect = { side -> onPreferenceChange { it.saveOneHandedSide(side) } }
                )
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_privacy))
                PrivacySection()
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_about))
                AboutSection(context = context)
            }
        }
    }
}

/**
 * Update banner shown at the top of the settings screen when the auto-updater
 * has actionable state: a new version available, a download in progress, or a
 * downloaded APK ready to install.
 *
 * Not shown for [UpdateManager.State.Idle] or [UpdateManager.State.UpToDate] —
 * those are silent states that don't need UI real estate.
 */
@Composable
private fun UpdateBanner(
    state: UpdateManager.State,
    onCheck: () -> Unit,
    onDownload: (String) -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            when (state) {
                is UpdateManager.State.Checking -> {
                    Text(
                        text = stringResource(R.string.update_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                is UpdateManager.State.UpdateAvailable -> {
                    Text(
                        text = stringResource(R.string.update_available, state.version),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { onDownload(state.downloadUrl) }) {
                            Text(stringResource(R.string.update_download))
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_dismiss))
                        }
                    }
                }
                is UpdateManager.State.Downloading -> {
                    Text(
                        text = stringResource(R.string.update_downloading, state.progress),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is UpdateManager.State.Downloaded -> {
                    Text(
                        text = stringResource(R.string.update_install),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onInstall) {
                        Text(stringResource(R.string.update_install))
                    }
                }
                is UpdateManager.State.Installing -> {
                    Text(
                        text = stringResource(R.string.update_installing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                is UpdateManager.State.Error -> {
                    Text(
                        text = stringResource(R.string.update_error, state.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onCheck) {
                        Text(stringResource(R.string.update_check_again))
                    }
                }
                else -> { /* Idle and UpToDate are not shown */ }
            }
        }
    }
}

/**
 * The setup card: the three-state indicator plus the two actions that resolve
 * it.
 *
 * ### Why this is first and why it shows a state, not just a button
 *
 * "My keyboard is installed but nothing happens when I tap a text field" is the
 * single most common support question for any IME, and the answer is always one
 * of three things: not enabled, enabled but not selected, or the user is in a
 * field that suppresses soft input. Showing all three states as a checklist
 * means the user can see which one is false without reading anything.
 */
@Composable
private fun SetupSection(
    status: ImeStatus,
    onOpenImeSettings: () -> Unit,
    onOpenImePicker: () -> Unit,
    onOpenSandbox: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenOnboarding: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_section_setup),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(10.dp))

        StatusRow(
            satisfied = status.enabled,
            text = if (status.enabled) {
                stringResource(R.string.settings_ime_enabled)
            } else {
                stringResource(R.string.settings_ime_not_enabled)
            }
        )

        Spacer(modifier = Modifier.height(4.dp))

        StatusRow(
            satisfied = status.selected,
            text = if (status.selected) {
                stringResource(R.string.settings_ime_selected)
            } else {
                stringResource(R.string.settings_ime_not_selected)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        ActionCard(
            title = stringResource(R.string.settings_enable_ime),
            subtitle = stringResource(R.string.settings_enable_ime_desc),
            onClick = onOpenImeSettings
        )

        if (status.enabled && !status.selected) {
            Spacer(modifier = Modifier.height(8.dp))
            ActionCard(
                title = stringResource(R.string.settings_select_ime),
                subtitle = null,
                onClick = onOpenImePicker
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        ActionCard(
            title = stringResource(R.string.settings_open_sandbox),
            subtitle = stringResource(R.string.settings_open_sandbox_desc),
            onClick = onOpenSandbox
        )

        // The wizard is offered permanently, not only on first run. Two reasons:
        // a user who skipped it and later cannot remember which of the two
        // Settings screens to open has somewhere to look, and a user who
        // disabled the keyboard months ago and now cannot work out why gets the
        // same guided two-step sequence as a first-time installer. It is
        // idempotent — every step re-reads live status — so re-running it costs
        // the user nothing.
        Spacer(modifier = Modifier.height(8.dp))

        ActionCard(
            title = stringResource(R.string.settings_rerun_setup),
            subtitle = stringResource(R.string.settings_rerun_setup_desc),
            onClick = onOpenOnboarding
        )

        // Diagnostics, always reachable.
        //
        // Not hidden behind a debug build flag. The failure this exists for —
        // the keyboard dying without explanation — happens on the *release*
        // APK the user actually installed, so a diagnostics entry that only
        // existed in debug would be useless exactly when it is needed. It also
        // gives a user who has never had a crash a place to confirm that, which
        // is itself a useful answer to "is the keyboard actually broken?".
        Spacer(modifier = Modifier.height(8.dp))

        ActionCard(
            title = stringResource(R.string.settings_open_debug),
            subtitle = stringResource(R.string.settings_open_debug_desc),
            onClick = onOpenDebug
        )
    }
}

/** One line of the setup checklist. Single line by contract: no wrapping. */
@Composable
private fun StatusRow(satisfied: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (satisfied) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (satisfied) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = text,
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
 * A tappable card that leads somewhere.
 *
 * Used for all three setup actions, and deliberately not a Material `Card` —
 * `Card` carries tonal elevation and a 12 dp shape that reads as content
 * rather than as a control. A settings shortcut should look like a row you can
 * press.
 */
@Composable
private fun ActionCard(
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = Icons.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** A section title. Single line by contract. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp)
    )
}

/**
 * A labelled switch.
 *
 * [enabled] is separate from [checked] on purpose: several settings here are
 * meaningless while another is off (haptic strength while haptics are off), and
 * showing them greyed out rather than removed keeps the list from reflowing as
 * the user toggles things above.
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/**
 * A labelled slider with its current value shown on the right.
 *
 * The value label is not optional. A slider with no number is impossible to set
 * deliberately — a user who wants "about 300 dp" has no way to tell when they
 * have arrived, and the haptic strength in particular has a meaningful absolute
 * scale that a bare thumb position cannot convey.
 */
@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled
        )
    }
}

/**
 * A single-select list rendered as full-width rows.
 *
 * Previously a horizontal row of equally-weighted chips. With four options
 * ("Follow system" … "AMOLED black") each chip was ~80 dp wide on a 360 dp
 * phone, so labels wrapped mid-phrase ("Follow" / "system") and the grid
 * looked broken. Full-width rows give every label the whole line: titles stay
 * on one line with ellipsis, there is no wrapping, no overlap, and the
 * control reads as a modern single-select group.
 *
 * ### Why the options are `List<Pair<T, String>>`
 *
 * So the caller supplies the label. Resolving a `ThemeMode` to a string needs a
 * `@Composable` context for `stringResource`, which a generic helper inside
 * this file cannot provide for an arbitrary enum without a mapping table that
 * would duplicate the strings anyway.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((value, label) in options) {
                val isSelected = value == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelect(value) }
                    )
                }
            }
        }
    }
}

/**
 * The privacy statement.
 *
 * Stated in full rather than summarised, because the claim is unusual and a
 * one-line summary of an unusual claim reads as marketing. The strongest
 * sentence here is the first one: the app declares no `INTERNET` permission, so
 * the platform — not our good intentions — prevents transmission.
 */
@Composable
private fun PrivacySection() {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(R.string.settings_privacy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        val bullets = listOf(
            R.string.settings_privacy_bullet_offline,
            R.string.settings_privacy_bullet_notelemetry,
            R.string.settings_privacy_bullet_password,
            R.string.settings_privacy_bullet_clipboard,
            R.string.settings_privacy_bullet_backup
        )
        for (bullet in bullets) {
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = "\u2022",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(bullet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Version, font licence, and live dictionary statistics.
 *
 * The statistics are read once, on entry. They are diagnostic rather than
 * settable, and the only reason to expose them is that "is the dictionary
 * actually loaded?" is otherwise unanswerable from the UI — a user reporting
 * bad suggestions can read the number instead of describing a symptom.
 */
@Composable
private fun AboutSection(context: Context) {
    var stats by remember { mutableStateOf<LexiconRepository.LexiconStatistics?>(null) }

    LaunchedEffect(Unit) {
        stats = try {
            LexiconRepository.get().statistics()
        } catch (t: Throwable) {
            null
        }
    }

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.settings_about_licenses),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val snapshot = stats
        if (snapshot != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.settings_export_lexicon),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${snapshot.wordCount} words \u00B7 ${snapshot.bigramContexts} bigram contexts" +
                    " \u00B7 ${snapshot.learnedWords} learned",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
