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

package com.nikit.nepalikeyboard.ime.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikit.nepalikeyboard.FlorisImeService
import com.nikit.nepalikeyboard.app.FlorisPreferenceStore
import com.nikit.nepalikeyboard.ime.ImeUiMode
import com.nikit.nepalikeyboard.ime.keyboard.FlorisImeSizing
import com.nikit.nepalikeyboard.ime.media.KeyboardLikeButton
import com.nikit.nepalikeyboard.ime.text.keyboard.TextKeyData
import com.nikit.nepalikeyboard.ime.theme.FlorisImeUi
import com.nikit.nepalikeyboard.keyboardManager
import kotlinx.coroutines.launch
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggRow

private val CrimsonPrimary = Color(0xFFC0152B)
private val CrimsonSecondary = Color(0xFFE83A4E)
private val CrimsonDark = Color(0xFF800D1C)
private val WaveColor = Color(0xFFFF6B6B)

@Composable
fun VoiceInputLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs = FlorisPreferenceStore.get()
    val scope = rememberCoroutineScope()

    val isListening by GeminiLiveVoiceManager.isListening.collectAsState()
    val isConnecting by GeminiLiveVoiceManager.isConnecting.collectAsState()
    val amplitude by GeminiLiveVoiceManager.amplitude.collectAsState()
    val statusMessage by GeminiLiveVoiceManager.statusMessage.collectAsState()
    val errorMessage by GeminiLiveVoiceManager.errorMessage.collectAsState()
    val previewText by GeminiLiveVoiceManager.currentPreviewText.collectAsState()

    val mode by prefs.geminiVoice.mode.asFlow().collectAsState(initial = GeminiVoiceMode.TRANSCRIBE)
    val translateTarget by prefs.geminiVoice.translateTarget.asFlow().collectAsState(initial = GeminiTranslateTarget.NEPALI_TO_ENGLISH)
    val apiKey by prefs.geminiVoice.apiKey.asFlow().collectAsState(initial = "")

    // Automatically start listening when entering Voice mode if API key is configured
    LaunchedEffect(apiKey) {
        if (apiKey.isNotBlank() && !isListening && !isConnecting) {
            GeminiLiveVoiceManager.startListening(context)
        }
    }

    // Stop listening when leaving Voice mode
    DisposableEffect(Unit) {
        onDispose {
            GeminiLiveVoiceManager.stopListening()
        }
    }

    SnyggColumn(
        elementName = FlorisImeUi.Smartbar.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight()),
    ) {
        // Top Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Back to Keyboard button
            IconButton(
                onClick = {
                    GeminiLiveVoiceManager.stopListening()
                    keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Keyboard",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Mode selector chips (Transcribe vs Translate)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Transcribe Chip
                val isTranscribe = mode == GeminiVoiceMode.TRANSCRIBE
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isTranscribe) CrimsonPrimary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            if (!isTranscribe) {
                                scope.launch {
                                    prefs.geminiVoice.mode.set(GeminiVoiceMode.TRANSCRIBE)
                                    if (isListening) {
                                        GeminiLiveVoiceManager.stopListening()
                                        GeminiLiveVoiceManager.startListening(context)
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "🎙️ Transcribe",
                        fontSize = 12.sp,
                        fontWeight = if (isTranscribe) FontWeight.Bold else FontWeight.Normal,
                        color = if (isTranscribe) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Translate Chip
                val isTranslate = mode == GeminiVoiceMode.TRANSLATE
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isTranslate) CrimsonPrimary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            if (!isTranslate) {
                                scope.launch {
                                    prefs.geminiVoice.mode.set(GeminiVoiceMode.TRANSLATE)
                                    if (isListening) {
                                        GeminiLiveVoiceManager.stopListening()
                                        GeminiLiveVoiceManager.startListening(context)
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "🌐 Translate",
                        fontSize = 12.sp,
                        fontWeight = if (isTranslate) FontWeight.Bold else FontWeight.Normal,
                        color = if (isTranslate) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Settings button
            IconButton(
                onClick = {
                    GeminiLiveVoiceManager.stopListening()
                    FlorisImeService.launchSettings()
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Gemini Voice Settings",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Translation direction bar if in Translate mode
        if (mode == GeminiVoiceMode.TRANSLATE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = when (translateTarget) {
                    GeminiTranslateTarget.NEPALI_TO_ENGLISH -> "🇳🇵 Nepali → 🇬🇧 English"
                    GeminiTranslateTarget.ENGLISH_TO_NEPALI -> "🇬🇧 English → 🇳🇵 Nepali"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(CrimsonDark.copy(alpha = 0.15f))
                        .border(1.dp, CrimsonPrimary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable {
                            val nextTarget = if (translateTarget == GeminiTranslateTarget.NEPALI_TO_ENGLISH) {
                                GeminiTranslateTarget.ENGLISH_TO_NEPALI
                            } else {
                                GeminiTranslateTarget.NEPALI_TO_ENGLISH
                            }
                            scope.launch {
                                prefs.geminiVoice.translateTarget.set(nextTarget)
                                if (isListening) {
                                    GeminiLiveVoiceManager.stopListening()
                                    GeminiLiveVoiceManager.startListening(context)
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = CrimsonPrimary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Switch direction",
                            tint = CrimsonPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        // Main Center Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (apiKey.isBlank()) {
                // Prompt to configure API key
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .clickable { FlorisImeService.launchSettings() }
                        .padding(16.dp),
                ) {
                    Text(
                        text = "🔑 Google AI Studio Key Required",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = CrimsonPrimary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap here to enter your Gemini API key in Settings to activate real-time Transcribe & Live Translation.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Live Mic & Visualizer UI
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Preview text line
                    if (previewText.isNotBlank()) {
                        Text(
                            text = previewText,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                        )
                    }

                    // Status / Error message
                    Text(
                        text = errorMessage ?: statusMessage,
                        fontSize = 12.sp,
                        color = if (errorMessage != null) CrimsonPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (errorMessage != null) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )

                    // Big Pulsing Mic Button
                    val infiniteTransition = rememberInfiniteTransition()
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = if (isListening) 1.15f else 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    )
                    val micScale by animateFloatAsState(
                        targetValue = if (isListening) 1f + (amplitude * 0.35f) else 1f,
                    )
                    val buttonBg by animateColorAsState(
                        targetValue = when {
                            isListening -> CrimsonPrimary
                            isConnecting -> CrimsonSecondary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .scale(if (isListening) pulseScale * micScale else 1f)
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(buttonBg)
                            .clickable {
                                GeminiLiveVoiceManager.toggleListening(context)
                            },
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(34.dp),
                            )
                        } else {
                            Icon(
                                imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = if (isListening) "Stop Listening" else "Start Listening",
                                tint = if (isListening || isConnecting) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }

                    // Audio Sound Wave Bars
                    if (isListening) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(20.dp),
                        ) {
                            val factors = listOf(0.4f, 0.8f, 1.2f, 0.9f, 0.5f)
                            for (factor in factors) {
                                val barHeight = (4.dp + (24.dp * (amplitude * factor).coerceIn(0f, 1f)))
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(barHeight)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(WaveColor),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Navigation & Editing Row
        SnyggRow(
            elementName = FlorisImeUi.MediaBottomRow.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.keyboardRowBaseHeight * 0.85f),
        ) {
            // Switch to Text Keyboard (ABC)
            KeyboardLikeButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                inputEventDispatcher = keyboardManager.inputEventDispatcher,
                keyData = TextKeyData.IME_UI_MODE_TEXT,
                modifier = Modifier.fillMaxHeight(),
            ) {
                Text(
                    text = "ABC",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Space key
            KeyboardLikeButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                inputEventDispatcher = keyboardManager.inputEventDispatcher,
                keyData = TextKeyData.SPACE,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Text(
                    text = "Space",
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Backspace key
            KeyboardLikeButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                inputEventDispatcher = keyboardManager.inputEventDispatcher,
                keyData = TextKeyData.DELETE,
                modifier = Modifier.fillMaxHeight(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = "Backspace",
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Done / Enter key
            KeyboardLikeButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                inputEventDispatcher = keyboardManager.inputEventDispatcher,
                keyData = TextKeyData.ENTER,
                modifier = Modifier.fillMaxHeight(),
            ) {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = "Done",
                )
            }
        }
    }
}
