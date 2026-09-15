package np.com.nepalikeyboard.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.density
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull
import np.com.nepalikeyboard.engine.unicode.Devanagari
import np.com.nepalikeyboard.ime.ImePanel
import np.com.nepalikeyboard.ime.KeyboardActionSink
import np.com.nepalikeyboard.ui.theme.ExpressiveMotion
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.devanagariStyle
import np.com.nepalikeyboard.ui.theme.latinStyle

/** Everything the pointer state machine needs from settings. */
@Immutable
data class KeyboardGestureConfig(
    val cursorDragEnabled: Boolean,
    val swipeDeleteEnabled: Boolean,
    val longPressEnabled: Boolean,
    val cursorGlideSpeed: Float,
    val feedback: FeedbackConfig,
) {
    companion object {
        val Default = KeyboardGestureConfig(
            cursorDragEnabled = true,
            swipeDeleteEnabled = true,
            longPressEnabled = true,
            cursorGlideSpeed = 1f,
            feedback = FeedbackConfig.Off,
        )
    }
}

/**
 * Mutable, hoisted interaction state for the key grid.
 *
 * Lives outside the composable so the pointer loop can mutate it without
 * capturing lambdas (which would allocate on every pointer event). Compose reads
 * it as ordinary snapshot state, and because the pressed index is read once per
 * surface and passed down as a Boolean, a press only recomposes the two keys
 * whose state actually changed.
 */
@Stable
class KeyboardInteractionState {
    var pressedIndex by mutableIntStateOf(-1)
    var longPressKey by mutableStateOf<KeyDef?>(null)
    var longPressOptions by mutableStateOf<List<String>>(emptyList())
    var longPressSelected by mutableIntStateOf(0)
    var glidingCursor by mutableStateOf(false)
    var glidingDelete by mutableStateOf(false)

    fun clearLongPress() {
        longPressKey = null
        longPressOptions = emptyList()
        longPressSelected = 0
    }

    fun clearAll() {
        pressedIndex = -1
        clearLongPress()
        glidingCursor = false
        glidingDelete = false
    }
}

/**
 * The key grid.
 *
 * Rendering: one custom [Layout] whose measure policy writes every key rectangle
 * into [KeyboardGeometry]. Placement happens once per size change, and hit
 * testing during a gesture is pure integer arithmetic against those arrays.
 *
 * Input: a single pointer handler for the whole keyboard (not one per key)
 * implements press, slide-to-select, long-press popups, space-bar caret gliding
 * and swipe-to-delete in one state machine - see [keyboardGestures].
 */
@Composable
fun KeyboardSurface(
    layout: KeyboardLayout,
    interaction: KeyboardInteractionState,
    sink: KeyboardActionSink,
    gestureConfig: KeyboardGestureConfig,
    feedback: KeyboardFeedback,
    actionLabel: String,
    scriptLabel: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    val geometry = remember { KeyboardGeometry() }

    // Holding the latest values in snapshot state keeps the pointerInput block
    // stable (it never restarts) while still seeing fresh settings and callbacks.
    val sinkState = rememberUpdatedState(sink)
    val layoutState = rememberUpdatedState(layout)
    val configState = rememberUpdatedState(gestureConfig)
    val feedbackState = rememberUpdatedState(feedback)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.keyboardBackground),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val rowCount = layout.rows.size.coerceAtLeast(1)
            val keyHeightDp: Dp = maxHeight / rowCount
            val devanagari = layout.id == LayoutId.DEVANAGARI || layout.id == LayoutId.DEVANAGARI_ALT
            val labelSize: TextUnit = (keyHeightDp.value * if (devanagari) 0.42f else 0.40f)
                .coerceIn(MIN_LABEL_SP, MAX_LABEL_SP)
                .sp
            val actionSize: TextUnit = (keyHeightDp.value * 0.26f).coerceIn(9f, 15f).sp

            KeyGrid(
                layout = layout,
                geometry = geometry,
                interaction = interaction,
                tokens = tokens,
                sinkState = sinkState,
                layoutState = layoutState,
                configState = configState,
                feedbackState = feedbackState,
                labelSize = labelSize,
                actionSize = actionSize,
                actionLabel = actionLabel,
                scriptLabel = scriptLabel,
            )
        }

        val heldKey = interaction.longPressKey
        if (heldKey != null) {
            LongPressOverlay(
                key = heldKey,
                options = interaction.longPressOptions,
                selectedIndex = interaction.longPressSelected,
                tokens = tokens,
            )
        }
    }
}

@Composable
private fun KeyGrid(
    layout: KeyboardLayout,
    geometry: KeyboardGeometry,
    interaction: KeyboardInteractionState,
    tokens: KeyboardTokens,
    sinkState: State<KeyboardActionSink>,
    layoutState: State<KeyboardLayout>,
    configState: State<KeyboardGestureConfig>,
    feedbackState: State<KeyboardFeedback>,
    labelSize: TextUnit,
    actionSize: TextUnit,
    actionLabel: String,
    scriptLabel: String,
) {
    val pressedIndex = interaction.pressedIndex
    val glidingCursor = interaction.glidingCursor
    val glidingDelete = interaction.glidingDelete

    val keys = layout.keys
    val measurePolicy: MeasurePolicy = remember(geometry, layout) { keyGridMeasurePolicy(geometry, layout) }
    val gestureModifier = Modifier.keyboardGestures(
        geometry = geometry,
        interaction = interaction,
        layoutState = layoutState,
        sinkState = sinkState,
        configState = configState,
        feedbackState = feedbackState,
    )

    Layout(
        content = {
            for (index in keys.indices) {
                val key = keys[index]
                KeyCap(
                    key = key,
                    pressed = pressedIndex == index,
                    gliding = (glidingCursor && key.kind == KeyKind.SPACE) ||
                        (glidingDelete && key.kind == KeyKind.BACKSPACE),
                    tokens = tokens,
                    labelSize = labelSize,
                    actionSize = actionSize,
                    actionLabel = actionLabel,
                    scriptLabel = scriptLabel,
                )
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .then(gestureModifier),
        measurePolicy = measurePolicy,
    )
}

/**
 * Measure policy for the key grid.
 *
 * Runs only when the layer or the available size changes: it records every key
 * rectangle into [geometry] and places the placeables at exactly those
 * coordinates, so visuals and hit-test data can never drift apart.
 */
private fun keyGridMeasurePolicy(geometry: KeyboardGeometry, layout: KeyboardLayout): MeasurePolicy =
    MeasurePolicy { measurables, constraints ->
        val width = constraints.maxWidth.coerceAtLeast(0)
        val height = constraints.maxHeight.coerceAtLeast(0)
        geometry.update(layout, width, height)

        val placeables = arrayOfNulls<Placeable>(measurables.size)
        for (index in measurables.indices) {
            placeables[index] = measurables[index].measure(
                Constraints.fixed(
                    geometry.width(index).coerceAtLeast(0),
                    geometry.height(index).coerceAtLeast(0),
                ),
            )
        }
        layout(width, height) {
            for (index in placeables.indices) {
                val placeable = placeables[index] ?: continue
                placeable.place(geometry.left(index), geometry.top(index))
            }
        }
    }

// ---------------------------------------------------------------------------
// Key cap
// ---------------------------------------------------------------------------

@Composable
private fun KeyCap(
    key: KeyDef,
    pressed: Boolean,
    gliding: Boolean,
    tokens: KeyboardTokens,
    labelSize: TextUnit,
    actionSize: TextUnit,
    actionLabel: String,
    scriptLabel: String,
) {
    val scale by animateFloatAsState(
        targetValue = if (pressed) tokens.keyPressedScale else 1f,
        animationSpec = ExpressiveMotion.keyPressSpring,
        label = "keyScale",
    )

    val functionKey = key.kind != KeyKind.CHARACTER &&
        key.kind != KeyKind.COMMA && key.kind != KeyKind.PERIOD

    val background = when {
        key.kind == KeyKind.SPACE || key.kind == KeyKind.ENTER ->
            if (pressed) tokens.accentKeyPressed else tokens.accentKeyBackground

        functionKey -> if (pressed) tokens.functionKeyPressed else tokens.functionKeyBackground
        else -> if (pressed) tokens.keyBackgroundPressed else tokens.keyBackground
    }

    val labelColor = when {
        key.kind == KeyKind.SPACE || key.kind == KeyKind.ENTER -> tokens.accentKeyLabel
        functionKey -> tokens.functionKeyLabel
        else -> tokens.keyLabel
    }

    val gap = tokens.keyGap / 2

    Box(
        modifier = Modifier
            .padding(horizontal = gap, vertical = gap)
            .fillMaxSize()
            .scale(scale)
            .clip(
                RoundedCornerShape(
                    if (key.kind == KeyKind.SPACE || key.kind == KeyKind.ENTER) {
                        tokens.functionCornerRadius
                    } else {
                        tokens.keyCornerRadius
                    },
                ),
            )
            .background(if (gliding) tokens.accentKeyBackground else background)
            .semantics { contentDescription = keyAccessibilityLabel(key, actionLabel, scriptLabel) },
        contentAlignment = Alignment.Center,
    ) {
        KeyContent(
            key = key,
            labelSize = labelSize,
            actionSize = actionSize,
            actionLabel = actionLabel,
            scriptLabel = scriptLabel,
            color = labelColor,
        )
    }
}

@Composable
private fun KeyContent(
    key: KeyDef,
    labelSize: TextUnit,
    actionSize: TextUnit,
    actionLabel: String,
    scriptLabel: String,
    color: Color,
) {
    val label = key.label

    // Any key that carries a printable label renders it, in the font the script
    // demands. Space and Enter are the only keys whose label is overridden.
    if (label.isNotEmpty() && key.kind != KeyKind.SPACE && key.kind != KeyKind.ENTER) {
        val style = when {
            // A label decides its own font: the Devanagari shift key that pages to
            // the extras layer carries an independent vowel, and it must not be
            // drawn with the Latin family.
            key.labelStyle == KeyLabelStyle.DEVANAGARI || containsDevanagari(label) ->
                devanagariStyle(labelSize, FontWeight.Medium)

            key.labelStyle == KeyLabelStyle.ACTION -> latinStyle(actionSize * 1.1f, FontWeight.SemiBold)
            else -> latinStyle(labelSize, FontWeight.Medium)
        }
        Text(
            text = label,
            style = style,
            color = color,
            softWrap = false,
            maxLines = 1,
            // Matras and conjunct stacks draw outside the Latin line box; never
            // clip them or the key cap would shear the glyph.
            overflow = TextOverflow.Visible,
        )
        return
    }

    when (key.kind) {
        KeyKind.SPACE -> Text(
            text = scriptLabel,
            style = latinStyle(actionSize, FontWeight.Medium),
            color = color,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        KeyKind.ENTER -> Text(
            text = actionLabel,
            style = latinStyle(actionSize * 1.2f, FontWeight.SemiBold),
            color = color,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        KeyKind.SHIFT -> KeyIcon(
            // `KeyboardCapslock` is the shift glyph in the *core* Material icons
            // set. The extended set's `Shift` would draw the same arrow, but it
            // never shipped in this BOM, so using it would be an unresolved
            // reference for a cosmetic difference at most.
            icon = Icons.Filled.KeyboardCapslock,
            color = color,
            labelSize = labelSize,
        )

        KeyKind.BACKSPACE -> KeyIcon(
            icon = Icons.Filled.Backspace,
            color = color,
            labelSize = labelSize,
        )

        KeyKind.EMOJI -> KeyIcon(
            icon = Icons.Filled.EmojiEmotions,
            color = color,
            labelSize = labelSize,
        )

        KeyKind.CLIPBOARD -> KeyIcon(
            icon = Icons.Filled.ContentPaste,
            color = color,
            labelSize = labelSize,
        )

        KeyKind.SETTINGS -> KeyIcon(
            icon = Icons.Filled.Settings,
            color = color,
            labelSize = labelSize,
        )

        KeyKind.ONE_HANDED -> KeyIcon(
            icon = Icons.Filled.ViewWeek,
            color = color,
            labelSize = labelSize,
        )

        KeyKind.HIDE -> KeyIcon(
            icon = Icons.Filled.KeyboardArrowDown,
            color = color,
            labelSize = labelSize,
        )

        else -> {
            val resolved = if (key.labelRes != 0) stringResource(key.labelRes) else ""
            Text(
                text = resolved,
                style = latinStyle(actionSize * 1.05f, FontWeight.SemiBold),
                color = color,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun KeyIcon(icon: ImageVector, color: Color, labelSize: TextUnit) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(iconSizeFor(labelSize)),
    )
}

private fun keyAccessibilityLabel(key: KeyDef, actionLabel: String, scriptLabel: String): String = when (key.kind) {
    KeyKind.CHARACTER -> key.label
    KeyKind.COMMA -> ","
    KeyKind.PERIOD -> "."
    KeyKind.SPACE -> scriptLabel
    KeyKind.ENTER -> actionLabel
    else -> key.label.ifEmpty { key.kind.name.lowercase() }
}

private fun iconSizeFor(labelSize: TextUnit): Dp =
    (labelSize.value * 1.15f).coerceIn(14f, 26f).dp

// ---------------------------------------------------------------------------
// Long-press popup
// ---------------------------------------------------------------------------

/**
 * Option strip shown while a key with alternatives is held.
 *
 * Rendered as one full-width row of equal-weight chips, so the pointer x
 * coordinate maps linearly onto the option index: no per-option geometry, no
 * measurement, no allocation while the finger moves.
 */
@Composable
private fun LongPressOverlay(
    key: KeyDef,
    options: List<String>,
    selectedIndex: Int,
    tokens: KeyboardTokens,
) {
    if (options.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.sidePadding, vertical = 2.dp),
    ) {
        Text(
            text = key.label,
            style = latinStyle(11.sp, FontWeight.SemiBold),
            color = tokens.suggestionText,
            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.stripHeight)
                .clip(RoundedCornerShape(tokens.functionCornerRadius))
                .background(tokens.panelSurface),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (index in options.indices) {
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(3.dp)
                        .clip(RoundedCornerShape(tokens.keyCornerRadius))
                        .background(if (selected) tokens.suggestionChipSelected else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = options[index],
                        style = latinStyle(20.sp, FontWeight.Medium),
                        color = if (selected) tokens.suggestionChipSelectedText else tokens.suggestionText,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Gesture state machine
// ---------------------------------------------------------------------------

/**
 * Entire-keyboard pointer handling.
 *
 * One `pointerInput` node serves every key. Outcomes, in priority order:
 *  1. **tap** - press feedback on down, action on up, slide-to-select in between;
 *  2. **long press** - option strip for keys with alternatives, plus caps lock
 *     and layout-sheet actions for function keys;
 *  3. **space-bar glide** - horizontal drag moves the caret through
 *     `InputConnection.setSelection`;
 *  4. **swipe-to-delete** - leftward drag from Backspace consumes whole words.
 *
 * Allocation discipline: the loop keeps its state in locals, indexes
 * `event.changes` with a for-loop instead of `firstOrNull { }` (which would
 * allocate a capturing lambda on every pointer event), and never builds a Rect,
 * an Offset or a list.
 */
private fun Modifier.keyboardGestures(
    geometry: KeyboardGeometry,
    interaction: KeyboardInteractionState,
    layoutState: State<KeyboardLayout>,
    sinkState: State<KeyboardActionSink>,
    configState: State<KeyboardGestureConfig>,
    feedbackState: State<KeyboardFeedback>,
): Modifier {
    // `LocalDensity` can only be read from a composable, and the pointer handler
    // below is not one. Reading it here and closing over the resolved scale
    // keeps the hot path free of a composition-local lookup on every event.
    val densityScale = LocalDensity.current.density
    return this.pointerInput(geometry, interaction) {
        val slop = viewConfiguration.touchSlop
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startIndex = geometry.hitTest(down.position.x, down.position.y)
        if (startIndex < 0) return@awaitEachGesture

        val layout = layoutState.value
        val sink = sinkState.value
        val config = configState.value
        val feedback = feedbackState.value
        val startKey = layout.keyAt(startIndex) ?: return@awaitEachGesture

        interaction.pressedIndex = startIndex
        feedback.onKeyPress(config.feedback)
        sink.onKeyPressed(startKey)

        val options = if (config.longPressEnabled) startKey.longPress else emptyList()
        val coreLongPress = config.longPressEnabled && hasCoreLongPressAction(startKey.kind)
        val repeating = startKey.kind == KeyKind.BACKSPACE
        var longPressFired = false
        var gestureConsumed = false
        var slideIndex = startIndex
        var slideKey = startKey
        var totalDx = 0f
        var cursorAccumulator = 0f
        var deleteAccumulator = 0f
        var deadline: Long = when {
            repeating -> REPEAT_FIRST_MS
            options.isNotEmpty() || coreLongPress -> longPressTimeout
            else -> NO_DEADLINE
        }

        val glideSpeed = config.cursorGlideSpeed.coerceIn(0.4f, 2.5f)
        val cursorStepPx = (CURSOR_STEP_DP * densityScale) / glideSpeed
        val deleteStepPx = DELETE_STEP_DP * densityScale

        while (true) {
            val event = if (deadline == NO_DEADLINE) {
                awaitPointerEvent()
            } else {
                withTimeoutOrNull(deadline) { awaitPointerEvent() }
            }

            if (event == null) {
                if (repeating && !gestureConsumed) {
                    // Backspace auto-repeat: one more grapheme cluster per tick,
                    // with the same tactile/audio feedback as a fresh press.
                    feedback.onKeyPress(config.feedback)
                    sink.onKeyPressed(startKey)
                    deadline = REPEAT_NEXT_MS
                    continue
                }
                // Long-press timeout elapsed with the finger still down. The action
                // itself fires on release, once the user has had the chance to
                // slide onto a popup option.
                longPressFired = true
                interaction.longPressKey = startKey
                interaction.longPressOptions = options
                interaction.longPressSelected = 0
                feedback.onLongPress(config.feedback)
                deadline = NO_DEADLINE
                continue
            }

            var active: PointerInputChange? = null
            for (index in 0 until event.changes.size) {
                val candidate = event.changes[index]
                if (candidate.id == down.id) {
                    active = candidate
                    break
                }
            }
            val change = active ?: continue

            if (change.changedToUpIgnoreConsumed()) {
                interaction.pressedIndex = -1
                when {
                    longPressFired -> {
                        val chosen = interaction.longPressOptions.getOrNull(interaction.longPressSelected)
                        interaction.clearLongPress()
                        // Positional actions first, then key-local actions: the key
                        // itself stays exactly where the finger is.
                        onKeyLongPressAction(startKey, chosen, sink)
                    }

                    !gestureConsumed -> sink.onKeyReleased(slideKey)

                    else -> sink.onGestureFinished()
                }
                interaction.clearAll()
                return@awaitEachGesture
            }

            val dx = change.position.x - change.previousPosition.x
            val dy = change.position.y - change.previousPosition.y
            totalDx += dx

            if (longPressFired) {
                val optionCount = interaction.longPressOptions.size
                if (optionCount > 0 && geometry.widthPx > 0) {
                    // Pointer x maps linearly onto the equal-weight chips below.
                    val fraction = (change.position.x / geometry.widthPx).coerceIn(0f, 0.9999f)
                    interaction.longPressSelected =
                        (fraction * optionCount).toInt().coerceIn(0, optionCount - 1)
                }
                change.consume()
                continue
            }

            if (!gestureConsumed && abs(totalDx) > slop) {
                when (slideKey.kind) {
                    KeyKind.SPACE -> if (config.cursorDragEnabled) {
                        gestureConsumed = true
                        interaction.glidingCursor = true
                    }

                    KeyKind.BACKSPACE -> if (config.swipeDeleteEnabled && totalDx < 0f) {
                        gestureConsumed = true
                        interaction.glidingDelete = true
                    }

                    else -> Unit
                }
            }

            if (gestureConsumed) {
                when (slideKey.kind) {
                    KeyKind.SPACE -> {
                        cursorAccumulator += dx
                        while (cursorAccumulator >= cursorStepPx) {
                            cursorAccumulator -= cursorStepPx
                            sink.onCursorDrag(1)
                            feedback.onCursorStep(config.feedback)
                        }
                        while (cursorAccumulator <= -cursorStepPx) {
                            cursorAccumulator += cursorStepPx
                            sink.onCursorDrag(-1)
                            feedback.onCursorStep(config.feedback)
                        }
                    }

                    KeyKind.BACKSPACE -> {
                        deleteAccumulator += dx
                        while (deleteAccumulator <= -deleteStepPx) {
                            deleteAccumulator += deleteStepPx
                            sink.onSwipeDelete(1)
                            feedback.onDeleteStep(config.feedback)
                        }
                    }

                    else -> Unit
                }
            } else {
                val movedIndex = geometry.nearestKeyIndex(change.position.x, change.position.y, slideIndex)
                if (movedIndex != slideIndex) {
                    val movedKey = layout.keyAt(movedIndex)
                    if (movedKey != null) {
                        slideIndex = movedIndex
                        slideKey = movedKey
                        interaction.pressedIndex = movedIndex
                        sink.onKeySlid(movedKey)
                    }
                }
                if (abs(dy) > slop * 2f) {
                    // A clearly vertical drag abandons the press.
                    interaction.pressedIndex = -1
                }
            }
            change.consume()
            }
        }
    }
}

private fun containsDevanagari(text: String): Boolean {
    for (index in text.indices) {
        if (Devanagari.isDevanagariBlock(text[index])) return true
    }
    return false
}

private fun hasCoreLongPressAction(kind: KeyKind): Boolean = when (kind) {
    KeyKind.SHIFT, KeyKind.LAYOUT_SWITCH -> true
    else -> false
}

/**
 * Resolves a long-press release.
 *
 * [option] is the popup character the finger settled on, or null when the gesture
 * was a plain hold. Component actions (caps lock, the layout sheet) take
 * precedence for their own keys so the same physical key keeps one identity.
 */
private fun onKeyLongPressAction(key: KeyDef, option: String?, sink: KeyboardActionSink) {
    when (key.kind) {
        KeyKind.LAYOUT_SWITCH -> sink.onPanelRequested(ImePanel.LAYOUT)
        else -> sink.onKeyLongPressed(key, option)
    }
}

private const val NO_DEADLINE = -1L
private const val REPEAT_FIRST_MS = 400L
private const val REPEAT_NEXT_MS = 55L
private const val MIN_LABEL_SP = 11f
private const val MAX_LABEL_SP = 24f
private const val CURSOR_STEP_DP = 7f
private const val DELETE_STEP_DP = 22f
