package np.com.nepalikeyboard.keyboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
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
        if (state.suggestionsVisible || state.isSecure || state.toolbarVisible) {
            TopBar(
                state = state,
                sink = sink,
                scriptLabel = scriptLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tokens.stripHeight),
            )
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

/**
 * One Gboard-style bar: a round toggle on the left swaps the suggestion strip for the tool row and
 * back. Typing collapses the tools again so suggestions are never hidden while composing.
 */
@Composable
private fun TopBar(
    state: ImeUiState,
    sink: KeyboardActionSink,
    scriptLabel: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    var expanded by remember { mutableStateOf(false) }
    val showStrip = state.suggestionsVisible || state.isSecure
    val canToggle = state.toolbarVisible && showStrip
    val toolsShown = state.toolbarVisible && (expanded || !showStrip)
    val typing = state.romanBuffer.isNotEmpty()
    LaunchedEffect(typing) { if (typing) expanded = false }
    val rotation by animateFloatAsState(
        targetValue = if (toolsShown) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "toolbarToggle",
    )
    Row(
        modifier = modifier.padding(horizontal = tokens.sidePadding + 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canToggle) {
            Box(
                modifier = Modifier
                    .size(tokens.stripHeight - 8.dp)
                    .clip(CircleShape)
                    .background(tokens.functionKeyBackground)
                    .clickable { expanded = !expanded }
                    .semantics { contentDescription = "Tools" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = tokens.iconTint,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(rotation),
                )
            }
            Box(Modifier.size(width = 6.dp, height = 1.dp))
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            AnimatedContent(
                targetState = toolsShown,
                transitionSpec = {
                    (fadeIn(tween(160)) + slideInHorizontally(tween(220)) { if (targetState) it / 5 else -it / 5 })
                        .togetherWith(fadeOut(tween(110)) + slideOutHorizontally(tween(160)) { if (targetState) -it / 5 else it / 5 })
                },
                label = "topBarContent",
            ) { tools ->
                if (tools) {
                    KeyboardToolbar(
                        state = state,
                        sink = sink,
                        scriptLabel = scriptLabel,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    SuggestionStrip(
                        state = state,
                        sink = sink,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyboardToolbar(
    state: ImeUiState,
    sink: KeyboardActionSink,
    scriptLabel: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToolbarTextButton(
            label = scriptLabel,
            description = stringResource(R.string.key_layout),
            tokens = tokens,
            onClick = { sink.onCycleModeRequested() },
        )
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
            .height(34.dp)
            .clip(CircleShape)
            .background(tokens.suggestionChipSelected)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = latinStyle(12.sp, FontWeight.SemiBold),
            color = tokens.suggestionChipSelectedText,
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
            .size(tokens.toolbarHeight - 2.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.iconTint,
            modifier = Modifier.size(21.dp),
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
