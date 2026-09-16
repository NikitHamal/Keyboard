/*
 * Copyright (C) 2026 Nikit Hamal / The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nikit.nepalikeyboard.app.settings.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikit.nepalikeyboard.app.FlorisPreferenceStore
import com.nikit.nepalikeyboard.app.enumDisplayEntriesOf
import com.nikit.nepalikeyboard.ime.voice.GeminiLiveVoiceManager
import com.nikit.nepalikeyboard.ime.voice.GeminiTranslateTarget
import com.nikit.nepalikeyboard.ime.voice.GeminiVoiceMode
import com.nikit.nepalikeyboard.lib.compose.FlorisScreen
import com.nikit.nepalikeyboard.lib.util.launchUrl
import dev.patrickgold.jetpref.datastore.ui.ExperimentalJetPrefDatastoreUi
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.launch

private val CrimsonPrimary = Color(0xFFC0152B)
private val CrimsonDark = Color(0xFF800D1C)

@OptIn(ExperimentalJetPrefDatastoreUi::class)
@Composable
fun GeminiVoiceScreen() = FlorisScreen {
    title = "Gemini Live Voice & Translate"
    previewFieldVisible = true

    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var hasMicPermission by remember {
        mutableStateOf(GeminiLiveVoiceManager.hasRecordAudioPermission(context))
    }

    val requestMicPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    content {
        val apiKey by prefs.geminiVoice.apiKey.asFlow().collectAsState(initial = "")
        val activeModel by prefs.geminiVoice.model.asFlow().collectAsState(initial = "gemini-2.0-flash-exp")

        // -------------------------------------------------------------
        // Hero Card
        // -------------------------------------------------------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Brush.linearGradient(
                            colors = listOf(CrimsonPrimary, CrimsonDark)
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Gemini Live AI Voice",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = "Real-time speech transcribe & translation",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Powered by Google AI Studio Gemini Live models. Directly speaks and writes Nepali and English with ultra-low latency, or translates between them live in your keyboard.",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.95f),
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // Permission Status Banner
        // -------------------------------------------------------------
        if (!hasMicPermission) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Microphone Permission Required",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "Needed to stream your voice for real-time typing.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { requestMicPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonPrimary),
                    ) {
                        Text("Grant", fontSize = 12.sp)
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // API Key Settings Group
        // -------------------------------------------------------------
        PreferenceGroup(title = "Google AI Studio API Key") {
            Preference(
                icon = Icons.Default.Key,
                title = "Gemini API Key",
                summary = if (apiKey.isBlank()) {
                    "Not configured — Tap to enter your API key"
                } else {
                    "Configured (${apiKey.take(4)}...${apiKey.takeLast(4)})"
                },
                onClick = { showApiKeyDialog = true },
            )
            Preference(
                icon = Icons.Default.OpenInNew,
                title = "Get Free Gemini API Key",
                summary = "Generate a key from Google AI Studio (aistudio.google.com)",
                onClick = { context.launchUrl("https://aistudio.google.com/app/apikey") },
            )
        }

        // -------------------------------------------------------------
        // Model & Live Options
        // -------------------------------------------------------------
        PreferenceGroup(title = "Live Model Configuration") {
            Preference(
                icon = Icons.Default.Security,
                title = "Active Model",
                summary = activeModel,
                onClick = { showModelDialog = true },
            )
            ListPreference(
                listPref = prefs.geminiVoice.mode,
                title = "Default Voice Mode",
                entries = listPrefEntries {
                    entry(
                        key = GeminiVoiceMode.TRANSCRIBE,
                        label = "Live Transcribe (Speech to Text)",
                        description = "Directly type what you speak in Devanagari or English",
                    )
                    entry(
                        key = GeminiVoiceMode.TRANSLATE,
                        label = "Live Translate (Voice Translation)",
                        description = "Translate spoken words real-time into English or Nepali",
                    )
                },
            )
            ListPreference(
                listPref = prefs.geminiVoice.translateTarget,
                title = "Translation Direction",
                entries = listPrefEntries {
                    entry(
                        key = GeminiTranslateTarget.NEPALI_TO_ENGLISH,
                        label = "🇳🇵 Nepali → 🇬🇧 English",
                        description = "Speak Nepali, type English text",
                    )
                    entry(
                        key = GeminiTranslateTarget.ENGLISH_TO_NEPALI,
                        label = "🇬🇧 English → 🇳🇵 Nepali",
                        description = "Speak English, type Nepali text",
                    )
                },
                enabledIf = { prefs.geminiVoice.mode.get() == GeminiVoiceMode.TRANSLATE },
            )
            SwitchPreference(
                pref = prefs.geminiVoice.autoStopSilence,
                title = "Auto-stop on Silence",
                summary = "Automatically finish transcribing after 3.5 seconds of silence",
            )
        }
    }

    // -----------------------------------------------------------------
    // API Key Dialog
    // -----------------------------------------------------------------
    if (showApiKeyDialog) {
        var keyInput by remember { mutableStateOf(prefs.geminiVoice.apiKey.get()) }
        var isVisible by remember { mutableStateOf(false) }

        JetPrefAlertDialog(
            title = "Google AI Studio API Key",
            confirmLabel = "Save",
            dismissLabel = "Cancel",
            onDismiss = { showApiKeyDialog = false },
            onConfirm = {
                scope.launch {
                    prefs.geminiVoice.apiKey.set(keyInput.trim())
                }
                showApiKeyDialog = false
            },
        ) {
            Column {
                Text(
                    text = "Your API key is stored locally on this device only and sent securely to Google Generative Language Live WebSocket API.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Gemini API Key") },
                    singleLine = true,
                    visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = { isVisible = !isVisible }) {
                        Text(if (isVisible) "Hide" else "Show", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Model Selection Dialog
    // -----------------------------------------------------------------
    if (showModelDialog) {
        val modelPresets = listOf(
            "gemini-3.5-transcribe-live" to "Gemini 3.5 Transcribe Live (Official Live STT)",
            "gemini-3.5-live-translate" to "Gemini 3.5 Live Translate (Official Live Translation)",
            "gemini-3.8-live" to "Gemini 3.8 Live (General Live Audio)",
            "gemini-2.0-flash-exp" to "Gemini 2.0 Flash (Fast & Proven Live Bidi)",
        )
        var customModelInput by remember { mutableStateOf(prefs.geminiVoice.model.get()) }

        JetPrefAlertDialog(
            title = "Select Gemini Live Model",
            confirmLabel = "Save",
            dismissLabel = "Cancel",
            onDismiss = { showModelDialog = false },
            onConfirm = {
                scope.launch {
                    prefs.geminiVoice.model.set(customModelInput.trim())
                }
                showModelDialog = false
            },
        ) {
            Column {
                Text(
                    text = "Choose a model preset or enter a custom model identifier:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))

                for ((presetId, presetLabel) in modelPresets) {
                    val isSelected = customModelInput == presetId
                    Button(
                        onClick = { customModelInput = presetId },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) CrimsonPrimary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = presetLabel,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = customModelInput,
                    onValueChange = { customModelInput = it },
                    label = { Text("Custom Model ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
