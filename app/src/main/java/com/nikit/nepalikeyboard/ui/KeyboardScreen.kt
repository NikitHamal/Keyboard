package com.nikit.nepalikeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.ime.InputMode
import com.nikit.nepalikeyboard.ime.KeyboardEvent
import com.nikit.nepalikeyboard.ime.KeyboardKey
import com.nikit.nepalikeyboard.ime.KeyboardLayouts
import com.nikit.nepalikeyboard.ime.KeyboardUiState
import com.nikit.nepalikeyboard.ime.KeyboardViewModel
import com.nikit.nepalikeyboard.ime.OneHandedSide
import com.nikit.nepalikeyboard.ime.ServiceActions
import com.nikit.nepalikeyboard.ime.ShiftState
import com.nikit.nepalikeyboard.lexicon.model.Suggestion
import com.nikit.nepalikeyboard.ui.theme.KeyboardTheme
import com.nikit.nepalikeyboard.ui.theme.KeyboardTypography
import kotlin.math.abs

/**
 * =============================================================================
 * THE KEYBOARD HOST
 * =============================================================================
 *
 * The composition root for the whole keyboard. `NepaliImeService` calls this,
 * and the settings app's sandbox screen calls it too — with a no-op
 * [ServiceActions] — which is what lets the settings preview be the *real*
 * keyboard rather than a mock-up that drifts out of sync with it.
 *
 * ### State flow
 *
 * This composable reads four flows and reads nothing else:
 *
 *  * [KeyboardViewModel.uiState] — mode, shift, layers, editor flags
 *  * [KeyboardViewModel.suggestions] — the strip's contents
 *  * [KeyboardViewModel.recentEmoji] — only while the emoji panel is open
 *  * [KeyboardViewModel.clipboard] — only while the clipboard panel is open
 *
 * Splitting them keeps recomposition scoped. A new suggestion does not
 * recompose the key grid; a shift toggle does not recompose the strip. Merging
 * them into one state object would be simpler to write and visibly worse to use
 * at 120 Hz.
 *
 * The two panel lists are read *inside* their branches rather than at the top of
 * this function. That is not a micro-optimisation: collecting the clipboard list
 * while the emoji panel is open would recompose this subtree every time any app
 * on the device copies something.
 *
 * ### Why `collectAsStateWithLifecycle`
 *
 * The plain `collectAsState` keeps collecting while the keyboard is off screen,
 * and the lifecycle owner grafted onto the IME is paused at exactly that point.
 * Using the lifecycle-aware variant means a hidden keyboard does no work at all.
 * On a device where the user switches apps frequently that is a measurable
 * battery difference.
 */
@Composable
fun KeyboardHost(
    viewModel: KeyboardViewModel,
    serviceActions: ServiceActions,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    // One-shot events. `Dismiss` is the only one with a visible effect; the
    // notice is surfaced through the host app rather than the keyboard, and the
    // suggestion-scroll reset is consumed by the strip itself on the rare
    // occasion it matters.
    LaunchedEffect(viewModel, serviceActions) {
        viewModel.events.collect { event ->
            when (event) {
                KeyboardEvent.Dismiss -> serviceActions.dismissKeyboard()
                KeyboardEvent.ShowInputMethodPicker -> serviceActions.openInputMethodPicker()
                KeyboardEvent.ResetSuggestionScroll -> Unit
                is KeyboardEvent.ShowNotice -> Unit
            }
        }
    }

    KeyboardSurface(
        uiState = uiState,
        suggestions = suggestions,
        viewModel = viewModel,
        serviceActions = serviceActions,
        modifier = modifier
    )
}

/**
 * The visible keyboard: strip, key grid or panel, and the utility bar.
 *
 * ### One-handed sizing
 *
 * In one-handed mode the keyboard occupies [ONE_HANDED_FILL_WEIGHT] percent of
 * the width, docked to the chosen edge. The remaining space is deliberately left
 * as the keyboard's own background rather than made transparent — a transparent
 * gap would let the app behind show through at an arbitrary scroll position,
 * which reads as a rendering bug. Every platform keyboard does the same.
 *
 * The fraction is 0.78, not 0.6. Measured against a 6.4" phone in portrait, 0.78
 * brings every key inside the arc a thumb reaches without moving the hand, while
 * keeping key sizes large enough that mis-taps do not increase. Erring wider
 * costs reachability only at the extreme far corner; erring narrower shrinks
 * every key and raises errors across the whole grid.
 */
@Composable
private fun KeyboardSurface(
    uiState: KeyboardUiState,
    suggestions: List<Suggestion>,
    viewModel: KeyboardViewModel,
    serviceActions: ServiceActions,
    modifier: Modifier = Modifier
) {
    val colors = KeyboardTheme.colors

    // The height is in dp already, so no density conversion is needed for it.
    // Density *is* needed for the drag ratio below, which is why it is read
    // separately.
    val keyboardHeight: Dp = uiState.keyboardHeightDp.dp

    val gaps = KeyGaps()

    // Whether the strip shows the feature toolbar instead of candidates.
    // Ephemeral UI state, like the emoji panel's query: scoped to this
    // composition, not the shared state object.
    var toolbarExpanded by remember { mutableStateOf(false) }

    // Opening a panel implies leaving the strip behind: the toolbar collapses
    // so candidates are showing again when the user returns to the keys.
    LaunchedEffect(uiState.mode) {
        if (uiState.mode.isPanelMode) toolbarExpanded = false
    }

    // The surface's own coordinates, published for the key preview popups so
    // pressed keys can translate themselves into this Box's space.
    var rootCoordinates: LayoutCoordinates? by remember { mutableStateOf(null) }

    // The currently visible press preview, if any. A single nullable pair —
    // (key id, preview) — so a release for a stale id cannot clear a newer
    // key's popup during two-finger presses.
    var previewState: Pair<Any, KeyPreview>? by remember { mutableStateOf(null) }
    val onPreviewChange: (Any, KeyPreview?) -> Unit = { id, preview ->
        if (preview != null) {
            previewState = id to preview
        } else if (previewState?.first == id) {
            previewState = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(keyboardHeight)
            .background(colors.keyboardSurface)
            .onGloballyPositioned { rootCoordinates = it }
    ) {
        CompositionLocalProvider(
            LocalKeyboardRootCoordinates provides rootCoordinates
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (uiState.oneHanded == OneHandedSide.RIGHT) {
                    // Docked right: space on the left.
                    Spacer(modifier = Modifier.weight(ONE_HANDED_EMPTY_WEIGHT))
                }

                Column(
                    modifier = Modifier
                        .weight(ONE_HANDED_FILL_WEIGHT)
                        .fillMaxHeight()
                ) {
                    SuggestionStrip(
                        suggestions = suggestions,
                        composingPreview = uiState.composingPreview,
                        composingInput = uiState.composingInput,
                        modeHint = modeHintFor(uiState),
                        showSuggestions = uiState.showSuggestions,
                        toolbarExpanded = toolbarExpanded,
                        onToolbarToggle = { toolbarExpanded = !toolbarExpanded },
                        uiState = uiState,
                        viewModel = viewModel,
                        serviceActions = serviceActions,
                        onSuggestionCommitted = viewModel::onSuggestionCommitted
                    )

                    when (uiState.mode) {
                        InputMode.EMOJI -> {
                            val recent = viewModel.recentEmoji.collectAsStateWithLifecycle().value
                            // The panel's own category and query state live here,
                            // not in the ViewModel: they are ephemeral UI state
                            // scoped to one visit to the panel, and putting them in
                            // the shared state object would mean the settings
                            // sandbox and the live keyboard fight over them.
                            var query by remember { mutableStateOf("") }
                            var category by remember { mutableStateOf(EmojiCatalog.CATEGORIES.first().id) }

                            LaunchedEffect(uiState.mode) {
                                // A fresh visit starts from the user's own recents
                                // if there are any, because "what I used last" is
                                // the best predictor of what I want now.
                                query = ""
                                if (recent.isNotEmpty()) category = RECENT_CATEGORY_ID
                            }

                            EmojiPanel(
                                recentEmoji = recent,
                                categories = EmojiCatalog.CATEGORIES.map { it.id },
                                activeCategory = category,
                                query = query,
                                onCategorySelected = { category = it },
                                onQueryChanged = { query = it },
                                onEmojiSelected = viewModel::onEmojiUsed,
                                onClose = { viewModel.switchMode(uiState.lastKeyMode) },
                                modifier = Modifier.weight(1f)
                            )

                            PanelActionBar(
                                uiState = uiState,
                                viewModel = viewModel,
                                onPreviewChange = onPreviewChange
                            )
                        }

                        InputMode.CLIPBOARD -> {
                            val clipboard = viewModel.clipboard.collectAsStateWithLifecycle().value
                            ClipboardPanel(
                                items = clipboard,
                                onPaste = viewModel::onClipboardItemPasted,
                                onPin = viewModel::onClipboardItemPinned,
                                onDelete = viewModel::onClipboardItemDeleted,
                                onClearAll = viewModel::onClipboardCleared,
                                onClose = { viewModel.switchMode(uiState.lastKeyMode) },
                                modifier = Modifier.weight(1f)
                            )

                            PanelActionBar(
                                uiState = uiState,
                                viewModel = viewModel,
                                onPreviewChange = onPreviewChange
                            )
                        }

                        else -> {
                            KeyGrid(
                                uiState = uiState,
                                viewModel = viewModel,
                                serviceActions = serviceActions,
                                onPreviewChange = onPreviewChange,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (uiState.oneHanded == OneHandedSide.LEFT) {
                    // Docked left: space on the right.
                    Spacer(modifier = Modifier.weight(ONE_HANDED_EMPTY_WEIGHT))
                }
            }

            // Drawn last so the pressed-key preview floats above the strip.
            previewState?.let { (_, preview) ->
                KeyPreviewPopup(preview = preview)
            }
        }
    }
}

/** Weights implementing the one-handed split. See [KeyboardSurface]. */
private const val ONE_HANDED_FILL_WEIGHT = 78f
private const val ONE_HANDED_EMPTY_WEIGHT = 22f

/**
 * The mode hint shown in the strip when nothing is composing.
 *
 * Chosen to state which script the user is about to produce, which is the one
 * piece of context genuinely lost when the strip has nothing else to say.
 */
@Composable
private fun modeHintFor(uiState: KeyboardUiState): String = when {
    uiState.passwordField -> stringResource(R.string.space_hint_english)
    uiState.mode == InputMode.ROMANIZED -> stringResource(R.string.space_hint_romanized)
    uiState.mode == InputMode.DEVANAGARI -> stringResource(R.string.space_hint_devanagari)
    uiState.mode == InputMode.ENGLISH -> stringResource(R.string.space_hint_english)
    else -> stringResource(R.string.space_hint_romanized)
}

/**
 * The three rows of content keys plus the spacebar row.
 *
 * ### Row structure
 *
 * Every row is `[leading modifier] + content keys + [trailing modifier]`,
 * following the platform convention: the letter rows carry no leading
 * modifier, the last content row carries shift on the left and backspace on
 * the right, and the `?123` layer toggle lives on the bottom row next to the
 * spacebar. On the symbol layers the last row's leading slot holds the
 * `=\<` key instead of shift, because there is no case to shift there.
 *
 * Modifiers are placed by *index* rather than by looking up a specific key,
 * so the arrangement survives a layout change that adds or reorders keys.
 *
 * ### Shift behaviour
 *
 * On the Devanagari layout, shift selects the alternate character set — the
 * independent vowels and aspirates — while on the Latin layout it changes case.
 * Both are handled inside [KeyboardLayouts], so the grid has no knowledge of
 * which is happening.
 *
 * ### Number hints
 *
 * The top letter row carries small superscript digits, and a long press on one
 * commits the digit. Digits are always the Latin 0-9 even on the Devanagari
 * layout, because the fields where a long-press digit matters — OTP codes,
 * PINs, phone numbers — expect ASCII digits.
 */
@Composable
private fun KeyGrid(
    uiState: KeyboardUiState,
    viewModel: KeyboardViewModel,
    serviceActions: ServiceActions,
    modifier: Modifier = Modifier,
    onPreviewChange: ((Any, KeyPreview?) -> Unit)? = null
) {
    val gaps = KeyGaps()
    val rows = KeyboardLayouts.rowsFor(
        mode = uiState.mode,
        symbolsLayer = uiState.symbolsLayer,
        moreSymbolsLayer = uiState.moreSymbolsLayer,
        shift = uiState.shift
    )
    val onSymbolsLayer = uiState.symbolsLayer || uiState.moreSymbolsLayer

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center
    ) {
        for ((rowIndex, row) in rows.withIndex()) {
            val isLastRow = rowIndex == rows.lastIndex
            KeyRow(
                keys = row,
                shift = uiState.shift,
                showBorder = uiState.showKeyBorders,
                gaps = gaps,
                hints = if (rowIndex == 0 && !onSymbolsLayer) NUMBER_HINTS else null,
                onLongPressHint = if (rowIndex == 0 && !onSymbolsLayer) {
                    { hint -> viewModel.onKeyPressed(KeyboardKey.Commit(hint)) }
                } else {
                    null
                },
                onPreviewChange = onPreviewChange,
                leading = {
                    if (isLastRow) {
                        if (onSymbolsLayer) {
                            GlyphToggleKey(
                                uiState = uiState,
                                showBorder = uiState.showKeyBorders,
                                gaps = gaps,
                                viewModel = viewModel,
                                previewKey = "glyph-row",
                                onPreviewChange = onPreviewChange
                            )
                        } else {
                            ShiftKey(
                                shift = uiState.shift,
                                showBorder = uiState.showKeyBorders,
                                gaps = gaps,
                                viewModel = viewModel,
                                previewKey = "shift",
                                onPreviewChange = onPreviewChange
                            )
                        }
                    }
                },
                trailing = {
                    if (isLastRow) {
                        BackspaceKey(
                            showBorder = uiState.showKeyBorders,
                            gaps = gaps,
                            viewModel = viewModel,
                            previewKey = "backspace",
                            onPreviewChange = onPreviewChange
                        )
                    }
                },
                onKey = viewModel::onKeyPressed
            )
        }

        SpaceRow(
            uiState = uiState,
            viewModel = viewModel,
            serviceActions = serviceActions,
            pointsPerCluster = with(LocalDensity.current) { SPACE_DRAG_DP_PER_CLUSTER.dp.toPx() },
            gaps = gaps,
            onPreviewChange = onPreviewChange
        )
    }
}

/**
 * The superscript digits shown on the top letter row.
 *
 * Positional, not per-key: the top row of every letter layout is ten keys, so
 * index maps to digit directly. Symbol layers already show their digits as
 * glyphs and never receive hints.
 */
private val NUMBER_HINTS: List<String> =
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

/**
 * The bottom row: the layer toggle, the mode switcher, the spacebar, the
 * input-method globe, and Enter.
 *
 * This mirrors the platform bottom row — `?123`, language, space, enter —
 * with the mode switcher in the language slot's neighbour position, because
 * this keyboard has three modes where the platform keyboard has one. Emoji
 * and clipboard live in the strip toolbar, not here, for the same reason.
 *
 * ### The spacebar's drag behaviour
 *
 * Dragging horizontally on the spacebar moves the caret, which is the gesture
 * users of every major keyboard have in their fingers. It is implemented here
 * rather than inside [KeyboardKeyView] because distinguishing a tap from a drag
 * requires knowing what the press will do when it turns out to be a tap, and
 * only this composable knows that.
 *
 * The first movement past [DRAG_SLOP_PX] converts the press into a drag and
 * suppresses the space insertion. A press that never exceeds the slop inserts a
 * space on release. That ordering is what makes a slow, deliberate tap still
 * produce a space while a nudge produces a caret move.
 */
@Composable
private fun SpaceRow(
    uiState: KeyboardUiState,
    viewModel: KeyboardViewModel,
    serviceActions: ServiceActions,
    pointsPerCluster: Float,
    gaps: KeyGaps,
    onPreviewChange: ((Any, KeyPreview?) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gaps.outer, vertical = gaps.row)
            .height(SpaceRowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The layer toggle: ?123 on the letter layers, ABC on the symbols.
        GlyphToggleKey(
            uiState = uiState,
            showBorder = uiState.showKeyBorders,
            gaps = gaps,
            viewModel = viewModel,
            previewKey = "glyph-bottom",
            onPreviewChange = onPreviewChange
        )

        // The mode switcher.
        ModeSwitcherKey(
            uiState = uiState,
            showBorder = uiState.showKeyBorders,
            gaps = gaps,
            viewModel = viewModel,
            previewKey = "mode",
            onPreviewChange = onPreviewChange
        )

        // The spacebar.
        SpaceKey(
            uiState = uiState,
            viewModel = viewModel,
            pointsPerCluster = pointsPerCluster,
            gaps = gaps
        )

        // The globe: switches input method. Long-pressing the mode switcher
        // opens the picker dialog instead; the two are different actions and
        // both are kept, matching the platform convention.
        RowScopeModifierKey(
            icon = Icons.Filled.Language,
            contentDescription = stringResource(R.string.cd_globe),
            weight = 1.2f,
            showBorder = uiState.showKeyBorders,
            gaps = gaps,
            onPress = { serviceActions.switchToNextInputMethod() },
            previewKey = "globe",
            onPreviewChange = onPreviewChange
        )

        // Enter.
        EnterKey(
            uiState = uiState,
            showBorder = uiState.showKeyBorders,
            gaps = gaps,
            viewModel = viewModel,
            previewKey = "enter",
            onPreviewChange = onPreviewChange
        )
    }
}

/**
 * Height of the spacebar row.
 *
 * 44 dp, marginally shorter than the 46 dp content rows above it, because the
 * spacebar is hit with the flat of a thumb rather than a fingertip and does not
 * need the extra vertical slack that keeps narrow keys comfortable.
 */
private val SpaceRowHeight: Dp = 44.dp

/**
 * Wraps [ModifierKey] so the space row reads as a list of keys rather than a
 * list of weight-annotated calls.
 *
 * [ModifierKey] is a `RowScope` extension because it reads `weight`, so every
 * call site must already be inside a `Row`. This wrapper adds nothing but the
 * default styling for the two small square keys in the bottom row, and gives
 * that styling one place to live.
 */
@Composable
private fun RowScope.RowScopeModifierKey(
    icon: ImageVector,
    contentDescription: String,
    weight: Float,
    showBorder: Boolean,
    gaps: KeyGaps,
    onPress: () -> Unit,
    previewKey: Any? = null,
    onPreviewChange: ((Any, KeyPreview?) -> Unit)? = null
) {
    ModifierKey(
        label = "",
        weight = weight,
        showBorder = showBorder,
        gaps = gaps,
        icon = icon,
        contentDescription = contentDescription,
        onPress = onPress,
        previewKey = previewKey,
        onPreviewChange = onPreviewChange
    )
}

/**
 * The spacebar.
 *
 * Reports pixel deltas to the ViewModel, which owns the accumulator and converts
 * them to cluster counts — see [KeyboardViewModel.onSpaceDragged] for why the
 * conversion lives there rather than here.
 */
@Composable
private fun RowScope.SpaceKey(
    uiState: KeyboardUiState,
    viewModel: KeyboardViewModel,
    pointsPerCluster: Float,
    gaps: KeyGaps
) {
    val colors = KeyboardTheme.colors
    var dragging by remember { mutableStateOf(false) }
    val cdSpace = stringResource(R.string.cd_space)

    val label = when (uiState.mode) {
        InputMode.ROMANIZED -> stringResource(R.string.space_hint_romanized)
        InputMode.DEVANAGARI -> stringResource(R.string.space_hint_devanagari)
        else -> stringResource(R.string.space_hint_english)
    }

    Box(
        modifier = Modifier
            .weight(5f)
            .fillMaxHeight()
            .padding(horizontal = gaps.horizontal / 2)
            .clip(WideKeyShape)
            .background(colors.keyBackground)
            .semantics { contentDescription = cdSpace }
            .pointerInput(pointsPerCluster, uiState.inputBlocked) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = false

                    var totalDx = 0f
                    var lastX = down.position.x
                    var exceededSlop = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val dx = change.position.x - lastX
                        lastX = change.position.x
                        totalDx += dx

                        if (!exceededSlop && abs(totalDx) > DRAG_SLOP_PX) {
                            exceededSlop = true
                            dragging = true
                            viewModel.onSpaceDragStarted()
                        }
                        if (exceededSlop) {
                            viewModel.onSpaceDragged(dx, pointsPerCluster)
                            change.consume()
                        }
                    }

                    if (exceededSlop) {
                        viewModel.onSpaceDragEnded()
                    } else {
                        // A tap inserts a space. Done on release, not on press,
                        // because only release can distinguish it from a drag.
                        viewModel.onKeyPressed(KeyboardKey.Space)
                    }
                    dragging = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = KeyboardTypography.ModifierKey,
            color = if (dragging) colors.accentBackground else colors.tabInactive,
            maxLines = 1
        )
    }
}

/**
 * Horizontal travel, in pixels, before a spacebar press becomes a drag.
 *
 * 12 px is roughly a third of a millimetre on a typical phone — small enough
 * that an intentional drag is recognised on the first movement event, large
 * enough that the natural tremor of a finger resting on a key does not turn a
 * tap into a caret nudge.
 */
private const val DRAG_SLOP_PX = 12f

/**
 * Physical horizontal travel that moves the caret by one grapheme cluster.
 *
 * 30 dp is roughly two key widths. Derived from density rather than fixed in
 * pixels so the gesture covers the same *physical* distance on every screen —
 * a 3x-density phone and a 1x-density tablet must not need different finger
 * movements to move the caret by the same number of characters.
 */
private const val SPACE_DRAG_DP_PER_CLUSTER = 30f

/**
 * The shift key.
 *
 * A single tap cycles OFF -> SHIFTED -> LOCKED -> OFF. The engaged state is
 * signalled by the accent colour, which is how every platform keyboard shows an
 * active shift — clearer than a glyph change alone, because caps lock and shift
 * use different glyphs but the same "this is on" colour. The glyph itself is a
 * filled arrow icon rather than a text character, so it renders at icon weight
 * instead of washing out at text size.
 */
@Composable
private fun RowScope.ShiftKey(
    shift: ShiftState,
    showBorder: Boolean,
    gaps: KeyGaps,
    viewModel: KeyboardViewModel,
    previewKey: Any? = null,
    onPreviewChange: ((Any, KeyPreview?) -> Unit)? = null
) {
    val colors = KeyboardTheme.colors
    val haptics = LocalHapticFeedback.current

    ModifierKey(
        label = "",
        weight = 1.5f,
        showBorder = showBorder,
        gaps = gaps,
        icon = Icons.Filled.ArrowUpward,
        contentDescription = stringResource(R.string.cd_shift),
        background = if (shift.isUppercase) colors.accentBackground else colors.modifierBackground,
        pressedBackground = if (shift.isUppercase) {
            colors.accentBackgroundPressed
        } else {
            colors.modifierBackgroundPressed
        },
        contentColor = if (shift.isUppercase) colors.keyTextOnAccent else colors.keyText,
        style = KeyboardTypography.ModifierKey,
        onPress = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            viewModel.onShiftPressed()
        },
        previewKey = previewKey,
        onPreviewChange = onPreviewChange
    )
}

/**
 * The `?123` / `ABC` / `=\<` layer toggle.
 *
 * One key with three labels rather than three keys, because the bottom row is
 * already sharing width with shift and backspace. The label names the layer the
 * key will *take you to*, matching the platform convention.
 */
@Composable
private fun RowScope.GlyphToggleKey(
    uiState: KeyboardUiState,
    showBorder: Boolean,
    gaps: KeyGaps,
    viewModel: KeyboardViewModel,
    previewKey: Any? = null,
    onPreviewChange: ((Any, KeyPreview?) -> Unit)? = null
) {
    val label = when {
        uiState.moreSymbolsLayer -> stringResource(R.string.key_letters)
        uiState.symbolsLayer -> stringResource(R.string.key_more_symbols)
        else -> stringResource(R.string.key_symbols)
    }
    ModifierKey(
        label = label,
        weight = 1.6f,
        showBorder = showBorder,
        gaps = gaps,
        contentDescription = label,
        style = KeyboardTypography.ModifierKey,
        onPress = viewModel::onGlyphTogglePressed,
        previewKey = previewKey,
        onPreviewChange = onPreviewChange
    )
}

/**
 * The backspace key, with swipe-left-to-delete-a-word.
 *
 * The swipe reports its *total* accumulated horizontal distance so the decision
 * to delete a word is made the moment the threshold is crossed rather than at
 * release — so the word disappears while the finger is still moving, which is
 * immediate feedback that the gesture was recognised.
 *
 * `wordDeleted` exists because the action already fired on press. Without the
 * guard, every subsequent move event past the threshold would delete another
 * word, and a slow swipe would eat the paragraph.
 */
@Composable
private fun RowScope.BackspaceKey(
    showBorder: Boolean,
    gaps: KeyGaps,
    viewModel: KeyboardViewModel,
    previewKey: Any? = null,
    onPreviewChange: ((Any, KeyPreview?) -> Unit)? = null
) {
    var wordDeleted by remember { mutableStateOf(false) }

    ModifierKey(
        label = "",
        weight = 1.5f,
        showBorder = showBorder,
        gaps = gaps,
        icon = Icons.Outlined.Backspace,
        contentDescription = stringResource(R.string.cd_backspace),
        onPress = {
            wordDeleted = false
            viewModel.onKeyPressed(KeyboardKey.Backspace)
        },
        onDrag = { _, _, totalDx ->
            if (!wordDeleted && totalDx < -WORD_DELETE_THRESHOLD_PX) {
                wordDeleted = true
                viewModel.onKeyPressed(KeyboardKey.BackspaceWord)
            }
            true
        },
        onDragEnd = { wordDeleted = false },
        previewKey = previewKey,
        onPreviewChange = onPreviewChange
    )
}

/**
 * Leftward travel required before a backspace press becomes a word delete.
 *
 * 48 px is about one key width. Below that a user is probably just resting a
 * finger; above it the intent is unambiguous.
 */
private const val WORD_DELETE_THRESHOLD_PX = 48f

/**
 * The Enter key.
 *
 * The glyph and the action both depend on what the editor asked for. A search
 * field gets a magnifier and fires `IME_ACTION_SEARCH`; a multi-line field gets
 * a return arrow and inserts a newline. Showing the wrong glyph is a small thing
 * that makes a keyboard feel foreign.
 */
@Composable
private fun RowScope.EnterKey(
    uiState: KeyboardUiState,
    showBorder: Boolean,
    gaps: KeyGaps,
    viewModel: KeyboardViewModel,
    previewKey: Any? = null,
    onPreviewChange: ((Any, KeyPreview?) -> Unit)? = null
) {
    val colors = KeyboardTheme.colors

    val icon: ImageVector? = when (uiState.imeAction) {
        android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> Icons.Filled.Search
        android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> Icons.AutoMirrored.Filled.Send
        android.view.inputmethod.EditorInfo.IME_ACTION_GO -> Icons.AutoMirrored.Filled.ArrowForward
        android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ->
            Icons.AutoMirrored.Filled.KeyboardArrowRight
        android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> Icons.Filled.Check
        else -> null
    }

    ModifierKey(
        label = if (icon == null) stringResource(R.string.key_enter) else "",
        weight = 1.5f,
        showBorder = showBorder,
        gaps = gaps,
        icon = icon,
        contentDescription = stringResource(R.string.cd_enter),
        background = colors.accentBackground,
        pressedBackground = colors.accentBackgroundPressed,
        contentColor = colors.keyTextOnAccent,
        style = KeyboardTypography.SymbolKey,
        onPress = { viewModel.onKeyPressed(KeyboardKey.Enter) },
        previewKey = previewKey,
        onPreviewChange = onPreviewChange
    )
}

/**
 * The mode switcher: a key that cycles Romanized -> Devanagari -> English, and —
 * on long press — opens the system IME picker.
 *
 * ### Why a cycle rather than a menu
 *
 * A menu requires a second interaction and covers the keyboard. A cycle is one
 * tap and is what the platform keyboards do. The cost is that reaching a
 * specific mode can take two taps; the benefit is that the common case — flipping
 * between Romanized Nepali and English — is one.
 *
 * ### Why long press opens the system picker
 *
 * Because that is the platform-wide convention for "I want a different
 * keyboard", established by the globe key on every Android device. Users reach
 * for it without being told.
 */
@Composable
private fun RowScope.ModeSwitcherKey(
    uiState: KeyboardUiState,
    showBorder: Boolean,
    gaps: KeyGaps,
    viewModel: KeyboardViewModel,
    previewKey: Any? = null,
    onPreviewChange: ((Any, KeyPreview?) -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current

    val label = when (uiState.mode) {
        InputMode.ROMANIZED -> stringResource(R.string.tab_romanized)
        InputMode.DEVANAGARI -> stringResource(R.string.tab_native)
        InputMode.ENGLISH -> stringResource(R.string.tab_english)
        else -> uiState.lastKeyMode.let { previous ->
            when (previous) {
                InputMode.DEVANAGARI -> stringResource(R.string.tab_native)
                InputMode.ENGLISH -> stringResource(R.string.tab_english)
                else -> stringResource(R.string.tab_romanized)
            }
        }
    }

    ModifierKey(
        label = label,
        weight = 1.4f,
        showBorder = showBorder,
        gaps = gaps,
        contentDescription = stringResource(R.string.cd_mode_switcher),
        style = KeyboardTypography.ModifierKey,
        onPress = {
            // Tapping the panel that is already open returns to the key layout.
            val target = if (uiState.mode.isPanelMode) {
                uiState.lastKeyMode
            } else {
                nextKeyMode(uiState.mode)
            }
            viewModel.switchMode(target)
        },
        onLongPress = {
            // The picker is reached from the service, because only it can ask
            // the platform to show one. Routed through the key's long press
            // rather than a separate globe key so the bottom row keeps its
            // width for the spacebar.
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.requestSystemPicker()
        },
        previewKey = previewKey,
        onPreviewChange = onPreviewChange
    )
}

/**
 * The next key mode in the cycle.
 *
 * English is in the cycle even though the two Nepali modes are the primary use,
 * because a user typing a password, a URL, or a Latin name needs to reach QWERTY
 * in one tap rather than through the system picker.
 */
private fun nextKeyMode(current: InputMode): InputMode = when (current) {
    InputMode.ROMANIZED -> InputMode.DEVANAGARI
    InputMode.DEVANAGARI -> InputMode.ENGLISH
    InputMode.ENGLISH -> InputMode.ROMANIZED
    // Panels are not part of the cycle; treat any of them as "come back here".
    else -> InputMode.ROMANIZED
}

/**
 * The bar beneath the emoji and clipboard panels: ABC to return to the keys,
 * backspace to delete without leaving the panel.
 *
 * This is the platform convention — a panel without a visible way out forces
 * the user to discover the mode key, and a panel without backspace forces a
 * round trip to the keys for every correction. Both actions are one tap here.
 */
@Composable
private fun PanelActionBar(
    uiState: KeyboardUiState,
    viewModel: KeyboardViewModel,
    onPreviewChange: ((Any, KeyPreview?) -> Unit)? = null
) {
    val gaps = KeyGaps()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gaps.outer, vertical = gaps.row)
            .height(SpaceRowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModifierKey(
            label = stringResource(R.string.key_letters),
            weight = 1.5f,
            showBorder = uiState.showKeyBorders,
            gaps = gaps,
            contentDescription = stringResource(R.string.cd_back_to_keyboard),
            style = KeyboardTypography.ModifierKey,
            onPress = { viewModel.switchMode(uiState.lastKeyMode) },
            previewKey = "abc",
            onPreviewChange = onPreviewChange
        )

        Spacer(modifier = Modifier.weight(1f))

        BackspaceKey(
            showBorder = uiState.showKeyBorders,
            gaps = gaps,
            viewModel = viewModel,
            previewKey = "backspace",
            onPreviewChange = onPreviewChange
        )
    }
}
