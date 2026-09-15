package np.com.nepalikeyboard.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * A labelled slider that only writes when the drag ends.
 *
 * Dragging updates a local mirror so the value label tracks the thumb with no
 * DataStore write per frame; the commit happens once, on release. The mirror is
 * re-synced whenever the underlying setting changes from anywhere else, so two
 * screens can never disagree about the value.
 */
@Composable
fun SettingSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val tokens = LocalKeyboardTokens.current
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { local = value }

    SettingsCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = latinStyle(14.sp, FontWeight.Medium),
                color = tokens.suggestionText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = latinStyle(11.sp),
                    color = tokens.iconTint,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Slider(
                value = local,
                onValueChange = { local = it },
                onValueChangeFinished = { onCommit(local) },
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
            Text(
                text = valueLabel(local),
                style = latinStyle(12.sp, FontWeight.SemiBold),
                color = tokens.iconTintActive,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
            )
        }
    }
}

/** Keyboard height: a multiplier of the device-derived base height. */
@Composable
fun KeyboardHeightSlider(
    scale: Float,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    title: String,
) {
    SettingSliderRow(
        title = title,
        description = description,
        value = scale,
        valueRange = 0.7f..1.5f,
        steps = 7,
        valueLabel = { value -> "${(value * 100).toInt()}%" },
        onCommit = { onCommit(it.coerceIn(0.7f, 1.5f)) },
        modifier = modifier,
    )
}

/** Key corner radius scale, the one visual knob that is not a colour. */
@Composable
fun KeyRoundnessSlider(
    scale: Float,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
) {
    SettingSliderRow(
        title = title,
        description = description,
        value = scale,
        valueRange = 0.5f..2f,
        steps = 5,
        valueLabel = { value -> "${(value * 100).toInt()}%" },
        onCommit = { onCommit(it.coerceIn(0.5f, 2f)) },
        modifier = modifier,
    )
}

/** Space-bar glide sensitivity: how many pixels of travel make one caret step. */
@Composable
fun CursorSensitivitySlider(
    speed: Float,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
) {
    SettingSliderRow(
        title = title,
        description = description,
        value = speed,
        valueRange = 0.4f..2.5f,
        steps = 6,
        valueLabel = { value -> "${(value * 100).toInt()}%" },
        onCommit = { onCommit(it.coerceIn(0.4f, 2.5f)) },
        modifier = modifier,
    )
}

/** One-handed keyboard width as a fraction of the screen. */
@Composable
fun OneHandedWidthSlider(
    fraction: Float,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
) {
    SettingSliderRow(
        title = title,
        description = description,
        value = fraction,
        valueRange = 0.6f..0.92f,
        steps = 7,
        valueLabel = { value -> "${(value * 100).toInt()}%" },
        onCommit = { onCommit(it.coerceIn(0.6f, 0.92f)) },
        modifier = modifier,
    )
}
