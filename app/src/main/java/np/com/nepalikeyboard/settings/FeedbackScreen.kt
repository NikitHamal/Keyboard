package np.com.nepalikeyboard.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.data.FeedbackStrength
import np.com.nepalikeyboard.data.SettingsSnapshot
import np.com.nepalikeyboard.keyboard.FeedbackConfig
import np.com.nepalikeyboard.keyboard.KeyboardFeedback

/**
 * Haptics and sound.
 *
 * The "try it" row plays the exact feedback a key press produces - same
 * [KeyboardFeedback] object, same constants - so the choice can be judged
 * without leaving the screen. Haptics deliberately use
 * `HapticFeedbackConstants`, which needs no permission at all; nothing here can
 * touch the vibrator API.
 */
@Composable
fun FeedbackScreen(
    snapshot: SettingsSnapshot,
    writer: SettingsWriter,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val feedback = remember(view) { KeyboardFeedback(view) }
    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }
    val config = FeedbackConfig(
        hapticsEnabled = snapshot.hapticsEnabled,
        soundEnabled = snapshot.soundEnabled,
        strength = snapshot.hapticStrength,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        SettingsSection(stringResource(R.string.setting_haptics)) {
            SettingSwitchRow(
                title = stringResource(R.string.setting_haptics),
                description = stringResource(R.string.setting_haptics_desc),
                checked = snapshot.hapticsEnabled,
                onCheckedChange = { value -> writer.write { it.setHapticsEnabled(value) } },
            )
            SettingChoiceRow(
                options = FeedbackStrength.entries.toList(),
                selected = snapshot.hapticStrength,
                labelOf = { strength ->
                    stringResource(
                        when (strength) {
                            FeedbackStrength.LIGHT -> R.string.feedback_light
                            FeedbackStrength.MEDIUM -> R.string.feedback_medium
                            FeedbackStrength.STRONG -> R.string.feedback_strong
                        },
                    )
                },
                onSelect = { strength -> writer.write { it.setHapticStrength(strength) } },
            )
            SettingActionRow(
                label = stringResource(R.string.setting_haptic_strength),
                onClick = { feedback.onKeyPress(config) },
            )
        }

        SettingsSection(stringResource(R.string.setting_sound)) {
            SettingSwitchRow(
                title = stringResource(R.string.setting_sound),
                description = stringResource(R.string.setting_sound_desc),
                checked = snapshot.soundEnabled,
                onCheckedChange = { value -> writer.write { it.setSoundEnabled(value) } },
            )
        }
    }
}
