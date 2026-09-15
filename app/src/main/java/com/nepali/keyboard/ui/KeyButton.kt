package com.nepali.keyboard.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class KeySpec(
    val label: String,
    val value: String = label,
    val weight: Float = 1f,
    val isSpecial: Boolean = false
)

@Composable
fun KeyButton(
    keySpec: KeySpec,
    hapticEnabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (String) -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }

    val backgroundColor = if (keySpec.isSpecial) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    val textColor = if (keySpec.isSpecial) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = modifier
            .padding(3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                if (hapticEnabled) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYPRESS)
                }
                onClick(keySpec.value)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = keySpec.label,
            color = textColor,
            fontSize = if (keySpec.label.length > 2) 13.sp else 18.sp,
            fontFamily = DevanagariFont,
            textAlign = TextAlign.Center
        )
    }
}
