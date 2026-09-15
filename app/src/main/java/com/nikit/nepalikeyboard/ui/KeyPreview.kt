package com.nikit.nepalikeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * =============================================================================
 * KEY PRESS PREVIEW POPUP
 * =============================================================================
 *
 * The enlarged copy of the pressed key drawn above the finger while it is
 * down — the single most recognisable behaviour of the platform keyboards.
 *
 * The design follows FlorisBoard's `ime/popup/PopupUi` (`PopupBaseBox`):
 * a same-width box anchored so its bottom edge overlaps the pressed key's
 * top edge, carrying the same label or icon at a larger size. The
 * implementation here is original and talks only to this keyboard's own
 * [KeyboardKeyView] press state; it borrows the geometry, not the code.
 * FlorisBoard is Apache-2.0, © The FlorisBoard Contributors — see LICENSE.
 *
 * ### Plumbing
 *
 * Pixel positions are the problem a weight-based grid cannot solve on its
 * own: rows divide space proportionally, so no key knows its own rectangle.
 * Each key therefore reports its [LayoutCoordinates] through
 * `onGloballyPositioned`, and the surface publishes its own coordinates
 * through [LocalKeyboardRootCoordinates]. A key translates itself into
 * surface space with `localBoundingBoxOf` at press time and hands the
 * resulting [KeyPreview] up through `onPreviewChange`.
 *
 * Only one preview is ever visible. Presses report `(id, preview)` pairs and
 * releases report `(id, null)`; a release for a stale id is ignored, which is
 * what keeps two-finger presses from flickering.
 */
val LocalKeyboardRootCoordinates = staticCompositionLocalOf<LayoutCoordinates?> { null }

/**
 * Everything the popup needs to draw one pressed key.
 *
 * Carried as a single immutable snapshot so the overlay recomposes exactly
 * once per press and once per release.
 */
@Immutable
data class KeyPreview(
    /** Identity of the pressed key, for matching releases to presses. */
    val key: Any,
    /** The label to show, or empty when [icon] is set. */
    val label: String,
    /** The icon to show, or null for a text key. */
    val icon: ImageVector?,
    /** Fill of the pressed key, reused so the popup matches it. */
    val background: Color,
    /** Label/icon tint of the pressed key. */
    val contentColor: Color,
    /** Text style of the pressed key; rendered larger. */
    val textStyle: TextStyle,
    /** The pressed key's rectangle, in surface coordinates (pixels). */
    val bounds: Rect
)

/**
 * The popup overlay, drawn last in the surface so it floats above the strip.
 *
 * Same width as the pressed key, same height, bottom edge overlapping the
 * key's top edge by [POPUP_OVERLAP]. Left edges aligned: because the popup
 * is exactly key-sized it can never overflow the keyboard horizontally, so
 * no clamping pass is needed for the edge keys.
 */
@Composable
fun KeyPreviewPopup(
    preview: KeyPreview,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val width: Dp
    val height: Dp
    val offset: IntOffset
    with(density) {
        width = preview.bounds.width.toDp()
        height = preview.bounds.height.toDp()
        val overlap = POPUP_OVERLAP.toPx()
        offset = IntOffset(
            preview.bounds.left.roundToInt(),
            (preview.bounds.top - preview.bounds.height + overlap).roundToInt()
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart
    ) {
        Box(
            modifier = Modifier
                .offset { offset }
                .size(width, height)
                .shadow(POPUP_SHADOW, KeyShape)
                .clip(KeyShape)
                .background(preview.background),
            contentAlignment = Alignment.Center
        ) {
            if (preview.icon != null) {
                Icon(
                    imageVector = preview.icon,
                    contentDescription = null,
                    tint = preview.contentColor,
                    modifier = Modifier.size(POPUP_ICON_SIZE)
                )
            } else {
                Text(
                    text = preview.label,
                    style = preview.textStyle.copy(
                        fontSize = preview.textStyle.fontSize * POPUP_LABEL_SCALE
                    ),
                    color = preview.contentColor,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** How far the popup's bottom edge overlaps the pressed key's top edge. */
private val POPUP_OVERLAP: Dp = 6.dp

/** Elevation of the floating popup, above the resting keys. */
private val POPUP_SHADOW: Dp = 4.dp

/** Icon size inside the popup, larger than the 20 dp key icon. */
private val POPUP_ICON_SIZE: Dp = 26.dp

/** Label magnification inside the popup. */
private const val POPUP_LABEL_SCALE = 1.25f
