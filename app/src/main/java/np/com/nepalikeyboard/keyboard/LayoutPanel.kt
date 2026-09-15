package np.com.nepalikeyboard.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.data.OneHandedSide
import np.com.nepalikeyboard.data.SettingsRepository
import np.com.nepalikeyboard.ime.ImePanel
import np.com.nepalikeyboard.ime.ImeRuntime
import np.com.nepalikeyboard.ime.ImeUiState
import np.com.nepalikeyboard.ime.KeyboardActionSink
import np.com.nepalikeyboard.ime.KeyboardMode
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.devanagariStyle
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * The in-keyboard layout sheet.
 *
 * Holds the handful of controls people change while typing: script, one-handed
 * mode, keyboard height, and the suggestion/toolbar/feedback toggles. Everything
 * else lives in the settings app; this sheet is not a second settings app, it is
 * the top of the funnel.
 *
 * Toggles write straight to [SettingsRepository] through a private writer. The
 * controller observes the same repository, so a tap here updates the very
 * keyboard the user is looking at without any extra plumbing - and the settings
 * app shows the same new value the next time it renders.
 */
@Composable
fun LayoutPanel(
    state: ImeUiState,
    sink: KeyboardActionSink,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    val writer = rememberPanelSettingsWriter()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.panelBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.toolbarHeight)
                .padding(horizontal = tokens.sidePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PanelAction(
                label = stringResource(R.string.key_letters),
                tokens = tokens,
                onClick = { sink.onPanelRequested(ImePanel.LAYOUT) },
            )
            Text(
                text = stringResource(R.string.layout_sheet_title),
                style = latinStyle(12.sp, FontWeight.SemiBold),
                color = tokens.iconTintActive,
                maxLines = 1,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = tokens.sidePadding + 2.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionTitle(stringResource(R.string.setting_default_layout), tokens)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModeChip(KeyboardMode.ROMAN, state, sink, tokens, Modifier.weight(1f))
                ModeChip(KeyboardMode.NATIVE, state, sink, tokens, Modifier.weight(1f))
                ModeChip(KeyboardMode.ENGLISH, state, sink, tokens, Modifier.weight(1f))
            }

            SectionTitle(stringResource(R.string.setting_one_handed), tokens)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OneHandedChip(OneHandedSide.OFF, state, sink, tokens, Modifier.weight(1f))
                OneHandedChip(OneHandedSide.LEFT, state, sink, tokens, Modifier.weight(1f))
                OneHandedChip(OneHandedSide.RIGHT, state, sink, tokens, Modifier.weight(1f))
            }
            LabeledSlider(
                title = stringResource(R.string.setting_one_handed),
                value = state.oneHandedWidthFraction.coerceIn(0.6f, 0.92f),
                valueRange = 0.6f..0.92f,
                tokens = tokens,
                onValueChangeFinished = { value ->
                    writer.write { setOneHandedWidthFraction(value) }
                },
            )

            SectionTitle(stringResource(R.string.setting_keyboard_height), tokens)
            LabeledSlider(
                title = stringResource(R.string.setting_keyboard_height),
                value = state.keyboardHeightScale.coerceIn(0.7f, 1.5f),
                valueRange = 0.7f..1.5f,
                tokens = tokens,
                // Height is applied through the sink so the input view resizes
                // immediately; the controller is what persists it.
                onValueChange = { value -> sink.onKeyboardHeightRequested(value) },
            )

            SectionTitle(stringResource(R.string.nav_layout), tokens)
            ToggleChip(
                label = stringResource(R.string.setting_number_row),
                checked = state.numberRow,
                tokens = tokens,
                onClick = { writer.write { setNumberRow(!state.numberRow) } },
            )
            ToggleChip(
                label = stringResource(R.string.setting_devanagari_numerals),
                checked = state.devanagariNumerals,
                tokens = tokens,
                onClick = { writer.write { setDevanagariNumerals(!state.devanagariNumerals) } },
            )
            ToggleChip(
                label = stringResource(R.string.setting_suggestions),
                checked = state.suggestionsVisible,
                tokens = tokens,
                onClick = { writer.write { setShowSuggestions(!state.suggestionsVisible) } },
            )
            ToggleChip(
                label = stringResource(R.string.setting_show_toolbar),
                checked = state.toolbarVisible,
                tokens = tokens,
                onClick = { writer.write { setShowToolbar(!state.toolbarVisible) } },
            )

            SectionTitle(stringResource(R.string.setting_haptics), tokens)
            ToggleChip(
                label = stringResource(R.string.setting_haptics),
                checked = state.feedback.hapticsEnabled,
                tokens = tokens,
                onClick = { writer.write { setHapticsEnabled(!state.feedback.hapticsEnabled) } },
            )
            ToggleChip(
                label = stringResource(R.string.setting_sound),
                checked = state.feedback.soundEnabled,
                tokens = tokens,
                onClick = { writer.write { setSoundEnabled(!state.feedback.soundEnabled) } },
            )

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelAction(
                    label = stringResource(R.string.key_language_switch),
                    tokens = tokens,
                    onClick = { sink.onSwitchInputMethod() },
                )
                PanelAction(
                    label = stringResource(R.string.settings_title),
                    tokens = tokens,
                    onClick = { sink.onOpenSettings() },
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String, tokens: KeyboardTokens) {
    Text(
        text = text,
        style = latinStyle(10.sp, FontWeight.SemiBold),
        color = tokens.iconTint,
        maxLines = 1,
    )
}

@Composable
private fun ModeChip(
    mode: KeyboardMode,
    state: ImeUiState,
    sink: KeyboardActionSink,
    tokens: KeyboardTokens,
    modifier: Modifier = Modifier,
) {
    val selected = state.mode == mode
    val label = when (mode) {
        KeyboardMode.ROMAN -> stringResource(R.string.language_roman)
        KeyboardMode.NATIVE -> stringResource(R.string.language_nepali)
        KeyboardMode.ENGLISH -> stringResource(R.string.language_english)
    }
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(if (selected) tokens.suggestionChipSelected else tokens.functionKeyBackground)
            .clickable { sink.onModeSelected(mode) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (mode == KeyboardMode.NATIVE) {
                devanagariStyle(14.sp, FontWeight.Medium)
            } else {
                latinStyle(11.sp, FontWeight.Medium)
            },
            color = if (selected) tokens.suggestionChipSelectedText else tokens.functionKeyLabel,
            maxLines = 1,
        )
    }
}

@Composable
private fun OneHandedChip(
    side: OneHandedSide,
    state: ImeUiState,
    sink: KeyboardActionSink,
    tokens: KeyboardTokens,
    modifier: Modifier = Modifier,
) {
    val selected = state.oneHanded == side
    val label = when (side) {
        OneHandedSide.OFF -> stringResource(R.string.one_handed_off)
        OneHandedSide.LEFT -> stringResource(R.string.one_handed_left)
        OneHandedSide.RIGHT -> stringResource(R.string.one_handed_right)
    }
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(if (selected) tokens.suggestionChipSelected else tokens.functionKeyBackground)
            .clickable { sink.onOneHandedChanged(side) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = latinStyle(11.sp, FontWeight.Medium),
            color = if (selected) tokens.suggestionChipSelectedText else tokens.functionKeyLabel,
            maxLines = 1,
        )
    }
}

@Composable
private fun ToggleChip(
    label: String,
    checked: Boolean,
    tokens: KeyboardTokens,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(if (checked) tokens.suggestionChipSelected else tokens.functionKeyBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = latinStyle(11.sp, FontWeight.Medium),
            color = if (checked) tokens.suggestionChipSelectedText else tokens.functionKeyLabel,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = stringResource(if (checked) R.string.common_on else R.string.common_off),
            style = latinStyle(10.sp, FontWeight.SemiBold),
            color = if (checked) tokens.suggestionChipSelectedText else tokens.iconTint,
            maxLines = 1,
        )
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    tokens: KeyboardTokens,
    onValueChangeFinished: (Float) -> Unit,
    onValueChange: ((Float) -> Unit)? = null,
) {
    val current = remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { current.floatValue = value }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = latinStyle(10.sp, FontWeight.Medium),
            color = tokens.functionKeyLabel,
            modifier = Modifier.width(84.dp),
            maxLines = 1,
        )
        Slider(
            value = current.floatValue,
            onValueChange = { updated ->
                current.floatValue = updated
                onValueChange?.invoke(updated)
            },
            onValueChangeFinished = { onValueChangeFinished(current.floatValue) },
            valueRange = valueRange,
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
        )
        Text(
            text = "${(current.floatValue * 100).toInt()}%",
            style = latinStyle(10.sp, FontWeight.SemiBold),
            color = tokens.iconTintActive,
            maxLines = 1,
        )
    }
}

@Composable
private fun PanelAction(
    label: String,
    tokens: KeyboardTokens,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(tokens.toolbarHeight - 8.dp)
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(tokens.functionKeyBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = latinStyle(11.sp, FontWeight.SemiBold),
            color = tokens.functionKeyLabel,
            maxLines = 1,
        )
    }
}

/** Minimal settings writer for the keyboard window. */
private class PanelSettingsWriter(
    private val scope: CoroutineScope,
    private val repository: SettingsRepository,
) {
    fun write(block: suspend SettingsRepository.() -> Unit) {
        scope.launch { repository.block() }
    }
}

@Composable
private fun rememberPanelSettingsWriter(): PanelSettingsWriter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        PanelSettingsWriter(scope, ImeRuntime.get(context).settings)
    }
}
