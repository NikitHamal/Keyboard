package com.nepali.keyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nepali.keyboard.prefs.KeyboardTheme
import com.nepali.keyboard.prefs.OneHandedMode
import com.nepali.keyboard.prefs.PreferencesManager
import com.nepali.keyboard.ui.NepaliKeyboardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefsManager = PreferencesManager(applicationContext)

        setContent {
            val themeSetting by prefsManager.keyboardTheme.collectAsState(initial = KeyboardTheme.DYNAMIC)

            NepaliKeyboardTheme(themeSetting = themeSetting) {
                MainSettingsScreen(prefsManager = prefsManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsScreen(prefsManager: PreferencesManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val hapticEnabled by prefsManager.hapticEnabled.collectAsState(initial = true)
    val soundEnabled by prefsManager.soundEnabled.collectAsState(initial = true)
    val autoCapsEnabled by prefsManager.autoCapsEnabled.collectAsState(initial = true)
    val heightDp by prefsManager.keyboardHeightDp.collectAsState(initial = 280)
    val themeSetting by prefsManager.keyboardTheme.collectAsState(initial = KeyboardTheme.DYNAMIC)
    val oneHandedSetting by prefsManager.oneHandedMode.collectAsState(initial = OneHandedMode.OFF)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Step-by-Step Onboarding Wizard
            OnboardingSection(context = context)

            Spacer(modifier = Modifier.height(16.dp))

            // Typing Test Preview Sandbox
            TypingPreviewSandbox()

            Spacer(modifier = Modifier.height(16.dp))

            // Preferences Controls
            Text(
                text = stringResource(R.string.pref_category_preferences),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Haptic Switch
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.pref_haptic_feedback),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = hapticEnabled,
                    onCheckedChange = { scope.launch { prefsManager.setHapticEnabled(it) } }
                )
            }

            // Sound Switch
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.pref_keypress_sound),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = soundEnabled,
                    onCheckedChange = { scope.launch { prefsManager.setSoundEnabled(it) } }
                )
            }

            // Auto Caps Switch
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.pref_auto_capitalization),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoCapsEnabled,
                    onCheckedChange = { scope.launch { prefsManager.setAutoCapsEnabled(it) } }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Keyboard Height Slider
            Text(
                text = "${stringResource(R.string.pref_keyboard_height)}: ${heightDp}dp",
                style = MaterialTheme.typography.bodyMedium
            )
            var sliderValue by remember(heightDp) { mutableFloatStateOf(heightDp.toFloat()) }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    scope.launch { prefsManager.setKeyboardHeight(sliderValue.toInt()) }
                },
                valueRange = 220f..360f,
                steps = 14
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Theme Dropdown Selector
            var themeExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = themeExpanded,
                onExpandedChange = { themeExpanded = !themeExpanded }
            ) {
                OutlinedTextField(
                    value = themeSetting.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.pref_theme)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = themeExpanded,
                    onDismissRequest = { themeExpanded = false }
                ) {
                    KeyboardTheme.entries.forEach { theme ->
                        DropdownMenuItem(
                            text = { Text(theme.name) },
                            onClick = {
                                scope.launch { prefsManager.setTheme(theme) }
                                themeExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // One Handed Mode Selector
            var oneHandedExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = oneHandedExpanded,
                onExpandedChange = { oneHandedExpanded = !oneHandedExpanded }
            ) {
                OutlinedTextField(
                    value = oneHandedSetting.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("One Handed Mode") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = oneHandedExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = oneHandedExpanded,
                    onDismissRequest = { oneHandedExpanded = false }
                ) {
                    OneHandedMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.name) },
                            onClick = {
                                scope.launch { prefsManager.setOneHandedMode(mode) }
                                oneHandedExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Privacy Declaration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.privacy_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "This keyboard operates 100% offline. Zero internet permissions requested, zero telemetry collected, and zero keystroke data retained.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun OnboardingSection(context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.enable_ime_step))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showInputMethodPicker()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.select_ime_step))
            }
        }
    }
}

@Composable
fun TypingPreviewSandbox() {
    var previewText by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sandbox_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = previewText,
                onValueChange = { previewText = it },
                placeholder = { Text(stringResource(R.string.sandbox_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
