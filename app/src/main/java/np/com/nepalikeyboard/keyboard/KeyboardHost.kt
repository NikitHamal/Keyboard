package np.com.nepalikeyboard.keyboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.data.OneHandedSide
import np.com.nepalikeyboard.ime.ImePanel
import np.com.nepalikeyboard.ime.ImeUiState
import np.com.nepalikeyboard.ime.KeyboardActionSink
import np.com.nepalikeyboard.ime.KeyboardMode
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * The complete keyboard window.
 *
 * Vertical composition, top to bottom:
 * ```
 * +--------------------------------------------------+
 * | suggestion strip (candidates, or the secure hint) |
 * | toolbar (script, layout, one-handed, emoji,       |
 * |          clipboard, settings, hide)               |
 * +--------------------------------------------------+
 * | emoji / clipboard / layout panel  OR  the keys    |
 * +--------------------------------------------------+
 * ```
 *
 * Everything renders from [ImeUiState]; the only state owned here is the
 * gesture holder and the haptics helper, both created once per input view. The
 * panels are swapped in place of the key grid rather than pushed as new screens,
 * so the keyboard height never changes between layers.
 */
@Composable
fun KeyboardHost(
    state: ImeUiState,
    sink: KeyboardActionSink,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    val view = LocalView.current
    val feedback = remember(view) { KeyboardFeedback(view) }
    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }

    val interaction = remember { KeyboardInteractionState() }
    val gestureConfig = remember(
        state.swipeDeleteEnabled,
        state.cursorDragEnabled,
        state.longPressSymbols,
        state.cursorGlideSpeed,
        state.feedback,
    ) {
        KeyboardGestureConfig(
            swipeDeleteEnabled = state.swipeDeleteEnabled,
            cursorDragEnabled = state.cursorDragEnabled,
            longPressEnabled = state.longPressSymbols,
            cursorGlideSpeed = state.cursorGlideSpeed,
            feedback = state.feedback,
        )
    }

    val scriptLabel = when (state.mode) {
        KeyboardMode.ROMAN -> stringResource(R.string.language_roman)
        KeyboardMode.NATIVE -> stringResource(R.string.language_nepali)
        KeyboardMode.ENGLISH -> stringResource(R.string.language_english)
    }
    val actionLabel = state.customActionLabel ?: stringResource(state.actionLabelRes)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.keyboardBackground),
    ) {
        // Both rows are settings-controlled. Hiding the strip is how a user turns
        // suggestions off; hiding the toolbar hands every secondary action back to
        // the settings app. Neither row leaves a gap when it is off.
        Column(modifier = Modifier.fillMaxWidth()) {
            if (state.suggestionsVisible || state.isSecure) {
                SuggestionStrip(
                    state = state,
                    sink = sink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tokens.stripHeight),
                )
            }
            if (state.toolbarVisible) {
                KeyboardToolbar(
                    state = state,
                    sink = sink,
                    scriptLabel = scriptLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tokens.toolbarHeight),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (state.panel) {
                ImePanel.EMOJI -> EmojiPanel(
                    state = state,
                    sink = sink,
                    modifier = Modifier.fillMaxSize(),
                )

                ImePanel.CLIPBOARD -> ClipboardPanel(
                    state = state,
                    sink = sink,
                    modifier = Modifier.fillMaxSize(),
                )

                ImePanel.LAYOUT -> LayoutPanel(
                    state = state,
                    sink = sink,
                    modifier = Modifier.fillMaxSize(),
                )

                ImePanel.NONE -> OneHandedFrame(
                    side = state.oneHanded,
                    widthFraction = state.oneHandedWidthFraction,
                    tokens = tokens,
                    onExpand = { sink.onOneHandedChanged(OneHandedSide.OFF) },
                    onSwitchSide = {
                        sink.onOneHandedChanged(
                            if (state.oneHanded == OneHandedSide.LEFT) {
                                OneHandedSide.RIGHT
                            } else {
                                OneHandedSide.LEFT
                            },
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val layout = state.layout
                    if (layout == null) {
                        Box(Modifier.fillMaxSize())
                    } else {
                        KeyboardSurface(
                            layout = layout,
                            interaction = interaction,
                            sink = sink,
                            gestureConfig = gestureConfig,
                            feedback = feedback,
                            actionLabel = actionLabel,
                            scriptLabel = scriptLabel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Toolbar
// ---------------------------------------------------------------------------

@Composable
private fun KeyboardToolbar(
    state: ImeUiState,
    sink: KeyboardActionSink,
    scriptLabel: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    Row(
        modifier = modifier.padding(horizontal = tokens.sidePadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToolbarTextButton(
            label = scriptLabel,
            description = stringResource(R.string.key_layout),
            tokens = tokens,
            onClick = { sink.onCycleModeRequested() },
        )
        Spacer(Modifier.weight(1f))
        ToolbarIconButton(
            icon = Icons.Filled.Tune,
            description = stringResource(R.string.layout_sheet_title),
            tokens = tokens,
            onClick = { sink.onPanelRequested(ImePanel.LAYOUT) },
        )
        if (state.oneHanded == OneHandedSide.OFF) {
            ToolbarIconButton(
                icon = Icons.Filled.ViewWeek,
                description = stringResource(R.string.key_one_handed),
                tokens = tokens,
                onClick = { sink.onOneHandedChanged(OneHandedSide.LEFT) },
            )
        } else {
            ToolbarIconButton(
                icon = Icons.Filled.Fullscreen,
                description = stringResource(R.string.key_expand),
                tokens = tokens,
                onClick = { sink.onOneHandedChanged(OneHandedSide.OFF) },
            )
        }
        ToolbarIconButton(
            icon = Icons.Filled.EmojiEmotions,
            description = stringResource(R.string.key_emoji),
            tokens = tokens,
            onClick = { sink.onPanelRequested(ImePanel.EMOJI) },
        )
        ToolbarIconButton(
            icon = Icons.Filled.ContentPaste,
            description = stringResource(R.string.key_clipboard),
            tokens = tokens,
            onClick = { sink.onPanelRequested(ImePanel.CLIPBOARD) },
        )
        ToolbarIconButton(
            icon = Icons.Filled.Settings,
            description = stringResource(R.string.key_settings),
            tokens = tokens,
            onClick = { sink.onOpenSettings() },
        )
        ToolbarIconButton(
            icon = Icons.Filled.KeyboardArrowDown,
            description = stringResource(R.string.key_hide),
            tokens = tokens,
            onClick = { sink.onHideKeyboard() },
        )
    }
}

@Composable
private fun ToolbarTextButton(
    label: String,
    description: String,
    tokens: KeyboardTokens,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(tokens.keyCornerRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = latinStyle(12.sp, FontWeight.SemiBold),
            color = tokens.iconTintActive,
            maxLines = 1,
        )
    }
}

@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    description: String,
    tokens: KeyboardTokens,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(tokens.toolbarHeight - 6.dp)
            .clip(RoundedCornerShape(tokens.keyCornerRadius))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.iconTint,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// One-handed frame
// ---------------------------------------------------------------------------

/**
 * Pins the key grid to one side and dims the other side into dead space.
 *
 * A fraction, not a fixed width, so the result is proportional on every screen.
 * The two handles expand back to full width and flip the side without leaving
 * the keyboard.
 */
@Composable
private fun OneHandedFrame(
    side: OneHandedSide,
    widthFraction: Float,
    tokens: KeyboardTokens,
    onExpand: () -> Unit,
    onSwitchSide: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (side == OneHandedSide.OFF) {
        content()
        return
    }
    val fraction = widthFraction.coerceIn(0.55f, 0.95f)
    val dead = 1f - fraction

    Row(modifier = modifier.fillMaxSize()) {
        if (side == OneHandedSide.RIGHT) Spacer(Modifier.weight(dead))
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(fraction),
        ) {
            content()
            Column(
                modifier = Modifier
                    .align(if (side == OneHandedSide.LEFT) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HandleButton(
                    icon = Icons.Filled.Fullscreen,
                    description = stringResource(R.string.key_expand),
                    tokens = tokens,
                    onClick = onExpand,
                )
                HandleButton(
                    icon = Icons.Filled.SwapHoriz,
                    description = stringResource(R.string.key_switch_side),
                    tokens = tokens,
                    onClick = onSwitchSide,
                )
            }
        }
        if (side == OneHandedSide.LEFT) Spacer(Modifier.weight(dead))
    }
}

@Composable
private fun HandleButton(
    icon: ImageVector,
    description: String,
    tokens: KeyboardTokens,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(tokens.functionKeyBackground)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.functionKeyLabel,
            modifier = Modifier.size(14.dp),
        )
    }
}
