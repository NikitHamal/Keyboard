package com.nepali.keyboard.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nepali.keyboard.engine.LexiconEngine
import com.nepali.keyboard.ime.ImeInputController
import com.nepali.keyboard.ime.KeyboardMode
import com.nepali.keyboard.prefs.KeyboardTheme
import com.nepali.keyboard.prefs.OneHandedMode
import com.nepali.keyboard.prefs.PreferencesManager
import kotlinx.coroutines.launch

@Composable
fun KeyboardView(
    service: Context,
    controller: ImeInputController,
    lexiconEngine: LexiconEngine,
    prefsManager: PreferencesManager
) {
    val scope = rememberCoroutineScope()
    val hapticEnabled by prefsManager.hapticEnabled.collectAsState(initial = true)
    val keyboardTheme by prefsManager.keyboardTheme.collectAsState(initial = KeyboardTheme.DYNAMIC)
    val keyboardHeightDp by prefsManager.keyboardHeightDp.collectAsState(initial = 280)
    val oneHandedMode by prefsManager.oneHandedMode.collectAsState(initial = OneHandedMode.OFF)
    val recentEmojis by prefsManager.recentEmojis.collectAsState(initial = emptyList())

    val composingText by controller.composingText.collectAsState()
    val isPassword by controller.isPasswordField.collectAsState()

    var keyboardMode by remember { mutableStateOf(KeyboardMode.ROMANIZED) }
    var isShifted by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showClipboard by remember { mutableStateOf(false) }

    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }

    // Fetch transliteration & lexical candidates when composingText changes
    androidx.compose.runtime.LaunchedEffect(composingText) {
        if (composingText.isNotBlank() && !isPassword && keyboardMode == KeyboardMode.ROMANIZED) {
            candidates = lexiconEngine.getCandidates(composingText)
        } else {
            candidates = emptyList()
        }
    }

    NepaliKeyboardTheme(themeSetting = keyboardTheme) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(keyboardHeightDp.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            val contentComposable: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier = modifier) {
                    if (showEmojiPicker) {
                        EmojiPickerView(
                            recentEmojis = recentEmojis,
                            onEmojiSelect = { emoji ->
                                controller.commitCandidate(emoji)
                                scope.launch { prefsManager.addRecentEmoji(emoji) }
                            },
                            onClose = { showEmojiPicker = false }
                        )
                    } else if (showClipboard) {
                        ClipboardView(
                            onPasteText = { text -> controller.commitCandidate(text) },
                            onClose = { showClipboard = false }
                        )
                    } else {
                        // Candidate Suggestion Strip
                        if (keyboardMode == KeyboardMode.ROMANIZED && !isPassword) {
                            CandidateStrip(
                                candidates = candidates,
                                composingText = composingText,
                                onSelectCandidate = { candidate ->
                                    controller.commitCandidate(candidate)
                                }
                            )
                        }

                        // Main Key Layouts
                        KeyLayouts(
                            mode = keyboardMode,
                            isShifted = isShifted,
                            hapticEnabled = hapticEnabled,
                            controller = controller,
                            onModeChange = { newMode -> keyboardMode = newMode },
                            onToggleShift = { isShifted = !isShifted },
                            onOpenEmoji = { showEmojiPicker = true },
                            onOpenClipboard = { showClipboard = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Apply One-Handed Mode Layout
            when (oneHandedMode) {
                OneHandedMode.OFF -> {
                    contentComposable(Modifier.fillMaxWidth())
                }
                OneHandedMode.LEFT -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        contentComposable(Modifier.weight(0.85f))
                        Box(
                            modifier = Modifier
                                .weight(0.15f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
                OneHandedMode.RIGHT -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .weight(0.15f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        contentComposable(Modifier.weight(0.85f))
                    }
                }
            }
        }
    }
}
