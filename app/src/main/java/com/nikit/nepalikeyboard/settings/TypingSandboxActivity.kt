package com.nikit.nepalikeyboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
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
 * THE TYPING SANDBOX
 * =============================================================================
 *
 * A plain text field that summons the real system keyboard — whichever IME the
 * user currently has selected. Typing here exercises the installed keyboard
 * through the platform's own input connection, not through an embedded copy.
 *
 * Previously this screen hosted an embedded `KeyboardHost` with a private
 * controller. That tested a copy of the UI, not the IME the system would use,
 * and users reasonably expected the keyboard they had just enabled to appear.
 * Now the field is an ordinary `OutlinedTextField`: focusing it shows the
 * current system keyboard, which is ours when the user has selected it.
 *
 * The status card at the top explains when ours is not enabled or not selected
 * and offers both repair actions inline, so a user who sees a different
 * keyboard understands why.
 *
 * Nothing leaves memory. The field state is saveable across rotation but is
 * cleared with the activity; the screen never writes to preferences, the
 * lexicon, or clipboard history.
 */
class TypingSandboxActivity : ComponentActivity() {

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
                TypingSandboxScreen(
                    imeStatus = status,
                    onBack = { finish() },
                    onOpenImeSettings = { openImeSettings() },
                    onOpenImePicker = { openImePicker() }
                )
            }
        }
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

    private fun openImeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (t: Throwable) {
            Unit
        }
    }

    private fun openImePicker() {
        try {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showInputMethodPicker()
        } catch (t: Throwable) {
            openImeSettings()
        }
    }
}

/**
 * The sandbox surface: status card, real test field, retention note.
 *
 * The field is a standard Material text field so the platform shows the
 * current system keyboard for it. Focus is requested on entry and the software
 * keyboard is shown explicitly — on some OEM builds focus alone does not
 * summon it until the user taps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypingSandboxScreen(
    imeStatus: ImeStatus,
    onBack: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onOpenImePicker: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.sandbox_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
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
                        onClick = { text = "" }
                    ) {
                        Text(
                            text = stringResource(R.string.sandbox_clear),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SandboxStatusCard(
                status = imeStatus,
                onOpenImeSettings = onOpenImeSettings,
                onOpenImePicker = onOpenImePicker
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .focusRequester(focusRequester),
                label = {
                    Text(
                        text = stringResource(R.string.sandbox_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingText = {
                    Text(
                        text = stringResource(R.string.sandbox_tap_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                shape = RoundedCornerShape(16.dp)
            )

            SandboxNote()
        }
    }
}

/**
 * Live IME status with repair actions.
 *
 * Hidden entirely when the keyboard is both enabled and selected — then the
 * field below already summons ours and there is nothing to explain. Otherwise
 * it names the missing step on a single line each and offers the system
 * destination that fixes it.
 */
@Composable
private fun SandboxStatusCard(
    status: ImeStatus,
    onOpenImeSettings: () -> Unit,
    onOpenImePicker: () -> Unit
) {
    if (status.enabled && status.selected) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SandboxStatusRow(
            satisfied = status.enabled,
            text = if (status.enabled) {
                stringResource(R.string.settings_ime_enabled)
            } else {
                stringResource(R.string.settings_ime_not_enabled)
            }
        )
        SandboxStatusRow(
            satisfied = status.selected,
            text = if (status.selected) {
                stringResource(R.string.settings_ime_selected)
            } else {
                stringResource(R.string.settings_ime_not_selected)
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!status.enabled) {
                Button(
                    onClick = onOpenImeSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.sandbox_open_settings),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!status.selected) {
                if (!status.enabled) {
                    OutlinedButton(
                        onClick = onOpenImePicker,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.sandbox_choose_keyboard),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Button(
                        onClick = onOpenImePicker,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.sandbox_choose_keyboard),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** One status line. Single line by contract. */
@Composable
private fun SandboxStatusRow(satisfied: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (satisfied) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (satisfied) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
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
 * The retention note under the field.
 *
 * Permanently visible rather than dismissible: a user testing transliteration
 * needs to know before typing that nothing is kept.
 */
@Composable
private fun SandboxNote() {
    Text(
        text = stringResource(R.string.sandbox_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth()
    )
}
