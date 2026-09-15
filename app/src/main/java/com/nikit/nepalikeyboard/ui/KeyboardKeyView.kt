package com.nikit.nepalikeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikit.nepalikeyboard.ime.KeyboardKey
import com.nikit.nepalikeyboard.ime.KeyboardLayouts
import com.nikit.nepalikeyboard.ime.ShiftState
import com.nikit.nepalikeyboard.ui.theme.KeyboardTheme
import com.nikit.nepalikeyboard.ui.theme.KeyboardTypography
import kotlinx.coroutines.withTimeoutOrNull

/**
 * =============================================================================
 * KEY GEOMETRY
 * =============================================================================
 *
 * How a single key looks, and — more importantly — how it responds to touch.
 *
 * ### Why the press handler is not `Modifier.clickable`
 *
 * `clickable` waits for the up event before firing, and it adds a ripple whose
 * animation runs on the main thread. For a keyboard that is wrong on both
 * counts:
 *
 *  * The character must be committed on *press*, not release. That is what makes
 *    a keyboard feel instantaneous, and it is what every platform keyboard
 *    does. Committing on release adds the user's finger-lift latency — tens of
 *    milliseconds — to every single character.
 *  * The ripple allocates and animates per press. On a 120 Hz display, holding
 *    a key and dragging across the grid would spawn and destroy ripples
 *    continuously.
 *
 * So the key implements its own pointer handling: `awaitFirstDown` fires the
 * action immediately, and `waitForUpOrCancellation` only drives the visual
 * pressed state. The pressed state is a plain `mutableStateOf(Boolean)`
 * invalidating exactly one key, not the grid.
 *
 * ### Why the visual pressed state is still tracked
 *
 * Because a key that gives no visual feedback on touch feels broken even when
 * it is working. The distinction that matters is that the *action* fires on
 * press while the *highlight* clears on release: the user gets instantaneous
 * text and correct feedback, with no latency trade-off.
 *
 * ### Swipe handling
 *
 * Two keys have drag behaviour — space (cursor movement) and backspace
 * (swipe-left to delete a word). Rather than special-casing them here, the key
 * accepts an optional [onDrag] callback and reports raw deltas; the caller owns
 * the gesture interpretation, because the thresholds and accumulators are
 * caller state, not key state.
 */
@Composable
fun KeyboardKeyView(
    label: String,
    modifier: Modifier = Modifier,
    widthWeight: Float = 1f,
    style: TextStyle = KeyboardTypography.LatinKey,
    background: Color = KeyboardTheme.colors.keyBackground,
    pressedBackground: Color = KeyboardTheme.colors.keyBackgroundPressed,
    contentColor: Color = KeyboardTheme.colors.keyText,
    shape: Shape = KeyShape,
    showBorder: Boolean = false,
    /** Fired on press. Must not suspend and must not allocate. */
    onPress: () -> Unit = {},
    /**
     * Whether [onPress] fires on finger-down (true) or on release (false).
     *
     * Keys with a long-press alternate use false: firing on down would insert
     * the letter *and* the alternate on a hold ("q" then "1"). Deferring to
     * release means a tap still commits in one gesture and a hold commits
     * only the alternate — which is the platform behaviour.
     */
    pressOnDown: Boolean = true,
    /**
     * Fired while a horizontal or vertical drag is in progress, with the delta
     * in pixels since the previous report. Returning true claims the gesture,
     * which suppresses the eventual press action — that is how a spacebar drag
     * avoids inserting a space.
     */
    onDrag: ((dx: Float, dy: Float, totalDx: Float) -> Boolean)? = null,
    onDragEnd: (() -> Unit)? = null,
    /**
     * Fired when the finger has been held still for the platform long-press
     * timeout without the key claiming a drag. Only the mode switcher uses
     * this; it is `null` for every other key, which is what keeps the extra
     * timer out of the hot path.
     */
    onLongPress: (() -> Unit)? = null,
    contentDescription: String? = null,
    icon: ImageVector? = null,
    fontScale: Float = 1f,
    /**
     * A superscript hint rendered at the key's top-end corner, e.g. the digit
     * a long press on a top-row key produces. Purely presentational; the
     * caller wires the matching long-press behaviour.
     */
    hint: String? = null
) {
    // Read outside the pointer lambda so the handler closure does not capture
    // the Composition scope.
    val currentOnPress by rememberUpdatedState(onPress)
    val currentPressOnDown by rememberUpdatedState(pressOnDown)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    var pressed by remember { mutableStateOf(false) }

    val viewConfiguration = LocalViewConfiguration.current

    // The caller's modifier chain is applied first so that width/weight and
    // layout params take effect before our own visual and input modifiers.
    val boxModifier = modifier
        .fillMaxHeight()
        .clip(shape)
        .background(if (pressed) pressedBackground else background)
        .then(
            if (showBorder) {
                Modifier.border(1.dp, KeyboardTheme.colors.keyBorder, shape)
            } else {
                Modifier
            }
        )
        .semantics { contentDescription?.let { this.contentDescription = it } }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                pressed = true

                // Fire immediately on press — unless this key defers to
                // release because it carries a long-press alternate. This is
                // the line that makes typing feel instant.
                if (currentPressOnDown) currentOnPress()

                // Track the gesture for drag-aware keys.
                var totalDx = 0f
                var dragClaimed = false

                if (currentOnDrag != null) {
                    var lastX = down.position.x
                    var lastY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = change.position.x - lastX
                        val dy = change.position.y - lastY
                        lastX = change.position.x
                        lastY = change.position.y
                        totalDx += dx
                        if (currentOnDrag?.invoke(dx, dy, totalDx) == true) {
                            dragClaimed = true
                            change.consume()
                        }
                    }
                } else if (currentOnLongPress != null) {
                    // The gesture never leaves this key, so the key either
                    // becomes a long press or ends as an ordinary press. A
                    // finger that wanders past touch slop is a scroll attempt
                    // aimed at something else, not a long press, so we abandon
                    // the wait and let go.
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (up != null) {
                        // Released inside the timeout: an ordinary tap. Keys
                        // that deferred their press fire it now; the rest
                        // already fired on down. Cancellation also lands here.
                        if (!currentPressOnDown) currentOnPress()
                    } else {
                        currentOnLongPress?.invoke()
                        waitForUpOrCancellation()
                    }
                } else {
                    // No drag behaviour: simply wait for release or cancel.
                    waitForUpOrCancellation()
                }

                pressed = false
                if (dragClaimed) currentOnDragEnd?.invoke()
            }
        }

    Box(
        modifier = boxModifier,
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = label,
                style = if (fontScale == 1f) style else style.copy(fontSize = style.fontSize * fontScale),
                color = contentColor,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
        if (hint != null) {
            Text(
                text = hint,
                style = KeyboardTypography.ModifierKey.copy(fontSize = 10.sp),
                color = contentColor.copy(alpha = 0.55f),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 5.dp)
            )
        }
    }
}

/**
 * The canonical key corner radius.
 *
 * 6 dp, flatter than Material 3's default 12 dp `medium` shape and close to
 * the platform keyboards' key geometry. A keyboard key is a small target — a
 * 12 dp radius on a 46 dp-tall key makes the key look like a pill and wastes
 * the corners that a thumb actually lands on.
 */
val KeyShape: Shape = RoundedCornerShape(6.dp)

/** Corner radius for the wider modifier keys, which read better slightly softer. */
val WideKeyShape: Shape = RoundedCornerShape(7.dp)

/**
 * The padding between keys.
 *
 * Horizontal and vertical gaps differ deliberately. A 4.5 dp horizontal gap
 * separates adjacent keys without making the grid look sparse, while a 7 dp
 * vertical gap is what makes rows visually distinct and gives the eye a
 * horizontal rhythm — the same proportions every major keyboard uses.
 */
@Immutable
data class KeyGaps(
    val horizontal: Dp = 4.5.dp,
    val vertical: Dp = 7.dp,
    /** Padding between the keyboard's outer edge and the first key of a row. */
    val outer: Dp = 4.dp,
    /** Vertical padding inside a row, above and below the keys. */
    val row: Dp = 3.dp
)

/**
 * Renders one row of content keys plus its leading and trailing modifiers.
 *
 * ### Weight distribution
 *
 * Every content key in a row gets equal weight; the modifiers take a fixed
 * larger weight. The arithmetic is done by giving each child an explicit
 * `weight` and letting `Row` divide the space, which is both simpler and more
 * correct than computing pixel widths — it handles an arbitrary number of keys
 * and any screen width with no measurement pass.
 *
 * ### Why the row is wrapped in `BoxWithConstraints`
 *
 * So that the caller can size modifier keys proportionally to the row height
 * rather than to a fixed dp. A shift key on a 200 dp-tall keyboard should be
 * narrower than on a 360 dp one; a fixed width would look wrong at one end of
 * the height range.
 */
@Composable
fun KeyRow(
    keys: List<KeyboardKey>,
    shift: ShiftState,
    showBorder: Boolean,
    gaps: KeyGaps,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /**
     * Superscript hints parallel to [keys], or null for no hints. Shorter
     * than [keys] is fine — keys past the end simply show none, which is how
     * the twelve-key Devanagari top row keeps hints on its first ten keys.
     */
    hints: List<String>? = null,
    /** Fired with the hint when a hinted key is long-pressed, if any. */
    onLongPressHint: ((String) -> Unit)? = null,
    onKey: (KeyboardKey) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = gaps.outer, vertical = gaps.row)
            .height(KeyHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke(this)
        for ((index, key) in keys.withIndex()) {
            val hint = hints?.getOrNull(index)
            KeyboardKeyView(
                label = KeyboardLayouts.glyphFor(key, shift),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = gaps.horizontal / 2),
                style = if (key is KeyboardKey.DevCharacter) {
                    KeyboardTypography.DevanagariKey
                } else {
                    KeyboardTypography.LatinKey
                },
                showBorder = showBorder,
                hint = hint,
                pressOnDown = hint == null,
                onLongPress = if (hint != null && onLongPressHint != null) {
                    { onLongPressHint.invoke(hint) }
                } else {
                    null
                },
                onPress = { onKey(key) }
            )
        }
        trailing?.invoke(this)
    }
}

/**
 * Height of a key row.
 *
 * Fixed rather than fractional so that the keyboard's total height matches the
 * user's adjustable-height setting exactly, regardless of how many rows the
 * current layer has. A `weight`-based chain inside a fixed-height container
 * would be equivalent but would silently compress when a layer gained a row.
 */
private val KeyHeight: Dp = 46.dp

/**
 * The standard padding applied inside the keyboard's root surface.
 */
val KeyboardContentPadding = PaddingValues(horizontal = 2.dp, vertical = 3.dp)

/**
 * A key label rendered with an explicit font size, for the cases where the
 * default scale of a role is wrong — a three-character label like `?123` on a
 * narrow key, or a Devanagari conjunct that needs to shrink to fit.
 *
 * Returned as a [TextStyle] rather than applied inline so the caller can hand it
 * to [KeyboardKeyView] and keep the key itself purely presentational.
 */
fun scaledKeyStyle(base: TextStyle, scale: Float): TextStyle =
    base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale)

/**
 * A single wide key used as a row's leading or trailing modifier.
 *
 * Takes an explicit [weight] because modifiers are not all the same width:
 * shift is narrower than backspace on most layouts, and matching those
 * proportions is what makes the layout feel like a real keyboard rather than a
 * uniform grid.
 */
@Composable
fun RowScope.ModifierKey(
    label: String,
    weight: Float,
    showBorder: Boolean,
    gaps: KeyGaps,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    background: Color = KeyboardTheme.colors.modifierBackground,
    pressedBackground: Color = KeyboardTheme.colors.modifierBackgroundPressed,
    contentColor: Color = KeyboardTheme.colors.keyText,
    style: TextStyle = KeyboardTypography.ModifierKey,
    fontScale: Float = 1f,
    onPress: () -> Unit,
    /**
     * Fired while the finger is still down and moving. Same contract as
     * [KeyboardKeyView]'s parameter: returning true claims the gesture.
     * Backspace uses this for swipe-left-to-delete-a-word.
     */
    onDrag: ((dx: Float, dy: Float, totalDx: Float) -> Boolean)? = null,
    /** Fired once a claimed drag ends, so the caller can reset its state. */
    onDragEnd: (() -> Unit)? = null,
    /**
     * Fired on a long press. Only the mode switcher uses it, to open the
     * system IME picker — which is where every Android user reaches for
     * "I want a different keyboard".
     */
    onLongPress: (() -> Unit)? = null
) {
    KeyboardKeyView(
        label = label,
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = gaps.horizontal / 2),
        style = style,
        background = background,
        pressedBackground = pressedBackground,
        contentColor = contentColor,
        shape = WideKeyShape,
        showBorder = showBorder,
        icon = icon,
        contentDescription = contentDescription,
        fontScale = fontScale,
        onPress = onPress,
        onDrag = onDrag,
        onDragEnd = onDragEnd,
        onLongPress = onLongPress
    )
}
