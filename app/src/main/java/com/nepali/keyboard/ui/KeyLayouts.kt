package com.nepali.keyboard.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.nepali.keyboard.ime.ImeInputController
import com.nepali.keyboard.ime.KeyboardMode

// English QWERTY
val EnglishRow1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map { KeySpec(it) }
val EnglishRow2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map { KeySpec(it) }
val EnglishRow3 = listOf("z", "x", "c", "v", "b", "n", "m").map { KeySpec(it) }

// Complete Devanagari Native Primary Layout
val DevanagariPrimaryRow1 = listOf("ौ", "ै", "ा", "ी", "ू", "ब", "ह", "ग", "द", "ज").map { KeySpec(it) }
val DevanagariPrimaryRow2 = listOf("ो", "े", "्", "ि", "ु", "प", "र", "क", "त", "च").map { KeySpec(it) }
val DevanagariPrimaryRow3 = listOf("ं", "म", "न", "व", "ल", "स", "य").map { KeySpec(it) }

// Complete Devanagari Native Shifted Layout (Vowels, Aspirated Consonants, Conjuncts, Modifiers)
val DevanagariShiftedRow1 = listOf("औ", "ऐ", "आ", "ई", "ऊ", "भ", "ङ", "घ", "ध", "झ").map { KeySpec(it) }
val DevanagariShiftedRow2 = listOf("ओ", "ए", "अ", "इ", "उ", "फ", "ृ", "ख", "थ", "छ").map { KeySpec(it) }
val DevanagariShiftedRow3 = listOf("ँ", "ण", "ञ", "ष", "श", "ः", "्").map { KeySpec(it) }

// Symbols
val SymbolRow1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { KeySpec(it) }
val SymbolRow2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")").map { KeySpec(it) }
val SymbolRow3 = listOf("*", "\"", "'", ":", ";", "!", "?").map { KeySpec(it) }

// Nepali Numerals
val NepaliNumberRow = listOf("१", "२", "३", "४", "५", "६", "७", "८", "९", "०").map { KeySpec(it) }

@Composable
fun KeyLayouts(
    mode: KeyboardMode,
    isShifted: Boolean,
    hapticEnabled: Boolean,
    controller: ImeInputController,
    onModeChange: (KeyboardMode) -> Unit,
    onToggleShift: () -> Unit,
    onOpenEmoji: () -> Unit,
    onOpenClipboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val row1 = when (mode) {
        KeyboardMode.ROMANIZED, KeyboardMode.ENGLISH_QWERTY -> if (isShifted) EnglishRow1.map { KeySpec(it.label.uppercase()) } else EnglishRow1
        KeyboardMode.NEPALI_NATIVE -> if (isShifted) DevanagariShiftedRow1 else DevanagariPrimaryRow1
        KeyboardMode.SYMBOLS -> SymbolRow1
        KeyboardMode.NUMBERS -> NepaliNumberRow
    }

    val row2 = when (mode) {
        KeyboardMode.ROMANIZED, KeyboardMode.ENGLISH_QWERTY -> if (isShifted) EnglishRow2.map { KeySpec(it.label.uppercase()) } else EnglishRow2
        KeyboardMode.NEPALI_NATIVE -> if (isShifted) DevanagariShiftedRow2 else DevanagariPrimaryRow2
        KeyboardMode.SYMBOLS -> SymbolRow2
        KeyboardMode.NUMBERS -> SymbolRow2
    }

    val row3 = when (mode) {
        KeyboardMode.ROMANIZED, KeyboardMode.ENGLISH_QWERTY -> if (isShifted) EnglishRow3.map { KeySpec(it.label.uppercase()) } else EnglishRow3
        KeyboardMode.NEPALI_NATIVE -> if (isShifted) DevanagariShiftedRow3 else DevanagariPrimaryRow3
        KeyboardMode.SYMBOLS -> SymbolRow3
        KeyboardMode.NUMBERS -> SymbolRow3
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Row 1
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            row1.forEach { key ->
                KeyButton(
                    keySpec = key,
                    hapticEnabled = hapticEnabled,
                    modifier = Modifier.weight(key.weight),
                    onClick = { controller.handleTextInsert(it, mode == KeyboardMode.ROMANIZED) }
                )
            }
        }

        // Row 2
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            row2.forEach { key ->
                KeyButton(
                    keySpec = key,
                    hapticEnabled = hapticEnabled,
                    modifier = Modifier.weight(key.weight),
                    onClick = { controller.handleTextInsert(it, mode == KeyboardMode.ROMANIZED) }
                )
            }
        }

        // Row 3 (Shift/Mode, Keys, Backspace)
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val shiftLabel = if (isShifted) "⇧" else "⇧"
            KeyButton(
                keySpec = KeySpec(shiftLabel, isSpecial = true, weight = 1.5f),
                hapticEnabled = hapticEnabled,
                modifier = Modifier.weight(1.5f),
                onClick = { onToggleShift() }
            )

            row3.forEach { key ->
                KeyButton(
                    keySpec = key,
                    hapticEnabled = hapticEnabled,
                    modifier = Modifier.weight(key.weight),
                    onClick = { controller.handleTextInsert(it, mode == KeyboardMode.ROMANIZED) }
                )
            }

            // Swipe to Delete from Backspace Key
            var backspaceDragAccumulator by remember { mutableFloatStateOf(0f) }
            KeyButton(
                keySpec = KeySpec("⌫", isSpecial = true, weight = 1.5f),
                hapticEnabled = hapticEnabled,
                modifier = Modifier
                    .weight(1.5f)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { backspaceDragAccumulator = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                backspaceDragAccumulator += dragAmount
                                if (backspaceDragAccumulator < -40f) {
                                    controller.handleSwipeDeleteWord()
                                    backspaceDragAccumulator = 0f
                                }
                            }
                        )
                    },
                onClick = { controller.handleBackspace() }
            )
        }

        // Bottom Control Row (Mode Switch, Emoji, Spacebar Drag Cursor, Period, IME Action)
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val modeLabel = when (mode) {
                KeyboardMode.ROMANIZED -> "ने/EN"
                KeyboardMode.NEPALI_NATIVE -> "नेपाली"
                KeyboardMode.ENGLISH_QWERTY -> "EN"
                KeyboardMode.SYMBOLS, KeyboardMode.NUMBERS -> "ABC"
            }

            KeyButton(
                keySpec = KeySpec(modeLabel, isSpecial = true, weight = 1.5f),
                hapticEnabled = hapticEnabled,
                modifier = Modifier.weight(1.5f),
                onClick = {
                    val nextMode = when (mode) {
                        KeyboardMode.ROMANIZED -> KeyboardMode.NEPALI_NATIVE
                        KeyboardMode.NEPALI_NATIVE -> KeyboardMode.ENGLISH_QWERTY
                        KeyboardMode.ENGLISH_QWERTY -> KeyboardMode.SYMBOLS
                        KeyboardMode.SYMBOLS, KeyboardMode.NUMBERS -> KeyboardMode.ROMANIZED
                    }
                    onModeChange(nextMode)
                }
            )

            KeyButton(
                keySpec = KeySpec("😊", isSpecial = true, weight = 1f),
                hapticEnabled = hapticEnabled,
                modifier = Modifier.weight(1f),
                onClick = { onOpenEmoji() }
            )

            KeyButton(
                keySpec = KeySpec("📋", isSpecial = true, weight = 1f),
                hapticEnabled = hapticEnabled,
                modifier = Modifier.weight(1f),
                onClick = { onOpenClipboard() }
            )

            // Spacebar with drag gesture cursor control
            var spaceDragAccumulator by remember { mutableFloatStateOf(0f) }
            KeyButton(
                keySpec = KeySpec("Space", " ", isSpecial = false, weight = 4f),
                hapticEnabled = hapticEnabled,
                modifier = Modifier
                    .weight(4f)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { spaceDragAccumulator = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                spaceDragAccumulator += dragAmount
                                if (spaceDragAccumulator > 30f) {
                                    controller.moveCursorBy(1)
                                    spaceDragAccumulator = 0f
                                } else if (spaceDragAccumulator < -30f) {
                                    controller.moveCursorBy(-1)
                                    spaceDragAccumulator = 0f
                                }
                            }
                        )
                    },
                onClick = { controller.insertSpace() }
            )

            KeyButton(
                keySpec = KeySpec(".", ".", isSpecial = false, weight = 1f),
                hapticEnabled = hapticEnabled,
                modifier = Modifier.weight(1f),
                onClick = { controller.handleTextInsert(".", false) }
            )

            KeyButton(
                keySpec = KeySpec("↵", isSpecial = true, weight = 1.5f),
                hapticEnabled = hapticEnabled,
                modifier = Modifier.weight(1.5f),
                onClick = { controller.performImeAction() }
            )
        }
    }
}
