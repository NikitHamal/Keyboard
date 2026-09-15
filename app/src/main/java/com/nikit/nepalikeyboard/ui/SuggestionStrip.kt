package com.nikit.nepalikeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.ime.InputMode
import com.nikit.nepalikeyboard.ime.KeyboardKey
import com.nikit.nepalikeyboard.ime.KeyboardUiState
import com.nikit.nepalikeyboard.ime.KeyboardViewModel
import com.nikit.nepalikeyboard.ime.OneHandedSide
import com.nikit.nepalikeyboard.ime.ServiceActions
import com.nikit.nepalikeyboard.lexicon.model.Suggestion
import com.nikit.nepalikeyboard.lexicon.model.SuggestionSource
import com.nikit.nepalikeyboard.ui.theme.KeyboardTheme
import com.nikit.nepalikeyboard.ui.theme.KeyboardTypography

/**
 * =============================================================================
 * THE SUGGESTION STRIP
 * =============================================================================
 *
 * The row above the keys that shows, in order:
 *
 *   1. the Devanagari rendering of what the user has typed so far, as a
 *      read-only preview,
 *   2. the literal Romanized input, so the user can escape transliteration
 *      for one word,
 *   3. the ranked lexicon alternatives.
 *
 * A chevron at the leading edge swaps the strip for the feature toolbar —
 * emoji, clipboard, one-handed controls, settings — which is where every
 * affordance that is not a candidate lives. That is the platform convention,
 * and it keeps the bottom of the keyboard a pure key grid.
 *
 * ### Why the literal option exists and why it is not last
 *
 * The transliteration engine is deterministic, but Devanagari orthography is
 * not: `bhasa` could be भास or भाष, and only the user knows which word they
 * mean. When the engine guesses wrong, the user needs a one-tap escape hatch
 * that inserts exactly the Roman characters they typed. Putting it anywhere but
 * adjacent to the preview would mean hunting for it.
 *
 * It is rendered in a distinct style — Poppins, not Devanagari, on a recessed
 * background — so that a user who does not need it can tell at a glance
 * that it is not a Devanagari candidate. It is a single line: an earlier
 * two-line rendering with a "Literal" caption clipped the caption on 44 dp
 * strips and read as a rendering bug.
 *
 * ### Visual differentiation by source
 *
 * Candidates from the lexicon are rendered in the Devanagari face at full
 * weight. Candidates the user has previously accepted are given a subtle accent
 * tint, which is a quiet signal that this word is one of *theirs* — it is what
 * makes the learning feature feel present without adding chrome.
 *
 * ### Height stability
 *
 * The strip reserves its height whether or not there are candidates, because a
 * strip that collapses and expands as the user types would move the key grid up
 * and down under their fingers — the single most disorienting behaviour a
 * keyboard can have. When there is nothing to show, the strip displays the
 * mode hint instead.
 */
@Composable
fun SuggestionStrip(
    suggestions: List<Suggestion>,
    composingPreview: String,
    composingInput: String,
    modeHint: String,
    showSuggestions: Boolean,
    toolbarExpanded: Boolean,
    onToolbarToggle: () -> Unit,
    uiState: KeyboardUiState,
    viewModel: KeyboardViewModel,
    serviceActions: ServiceActions,
    modifier: Modifier = Modifier,
    onSuggestionCommitted: (String) -> Unit
) {
    val colors = KeyboardTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(StripHeight)
            .background(colors.stripSurface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarToggle(
            expanded = toolbarExpanded,
            onToggle = onToolbarToggle
        )

        if (toolbarExpanded) {
            ToolbarStrip(
                uiState = uiState,
                viewModel = viewModel,
                serviceActions = serviceActions,
                onCollapse = onToolbarToggle,
                modifier = Modifier.weight(1f)
            )
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                when {
                    !showSuggestions -> {
                        // The user has turned suggestions off. Show only the composing
                        // preview, which still needs to be visible — otherwise they
                        // would be typing blind into a composing region.
                        StripContent(
                            preview = composingPreview,
                            hint = modeHint
                        )
                    }

                    suggestions.isEmpty() && composingPreview.isEmpty() -> {
                        StripContent(preview = "", hint = modeHint)
                    }

                    else -> {
                        val scrollState = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .horizontalScroll(scrollState)
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            // The live preview always occupies the first, non-tappable
                            // slot when something is composing.
                            if (composingPreview.isNotEmpty()) {
                                PreviewChip(text = composingPreview)
                                ChipDivider()
                            }

                            for ((index, suggestion) in suggestions.withIndex()) {
                                SuggestionChip(
                                    suggestion = suggestion,
                                    onCommit = onSuggestionCommitted
                                )
                                if (index != suggestions.lastIndex) ChipDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reserved height for the strip.
 *
 * 44 dp comfortably fits a 20 sp Devanagari preview with its 29 sp line height
 * plus vertical padding. The Devanagari line height is generous on purpose —
 * see the typography notes — and a shorter strip would clip a candrabindu.
 */
private val StripHeight = 44.dp

/**
 * The empty-state content: a single line of hint text, centred.
 */
@Composable
private fun StripContent(preview: String, hint: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = KeyboardTypography.ComposingPreview,
                color = KeyboardTheme.colors.keyText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = hint,
                style = KeyboardTypography.SuggestionHint.copy(fontWeight = FontWeight.Medium),
                color = KeyboardTheme.colors.tabInactive,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The non-interactive preview of the current composition.
 *
 * Rendered with reduced opacity and no press handling, because tapping it is
 * meaningless: the text it shows is already in the field, inside the composing
 * region. Making it look tappable would invite taps that do nothing.
 */
@Composable
private fun PreviewChip(text: String) {
    Box(
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .widthIn(max = 220.dp)
    ) {
        Text(
            text = text,
            style = KeyboardTypography.ComposingPreview,
            color = KeyboardTheme.colors.keyText.copy(alpha = 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A hairline separator between strip entries. */
@Composable
private fun ChipDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(20.dp)
            .background(KeyboardTheme.colors.keyBorder)
    )
}

/**
 * One tappable suggestion.
 *
 * ### Press-on-down, like the keys
 *
 * Same rationale as [KeyboardKeyView]: a suggestion must commit on press, not on
 * release. Typing "namaste" and tapping the suggestion should land नमस्ते
 * instantly.
 *
 * ### The source-dependent styling
 *
 * * [SuggestionSource.LITERAL] — Latin face on a recessed background.
 *   Visually distinct because it is a different *kind* of answer.
 * * [SuggestionSource.LEARNED] — accent-tinted text, so a word the user has
 *   typed before is recognisable as theirs.
 * * Everything else — the Devanagari face at full strength.
 */
@Composable
private fun SuggestionChip(
    suggestion: Suggestion,
    onCommit: (String) -> Unit
) {
    val colors = KeyboardTheme.colors
    val isLiteral = suggestion.source == SuggestionSource.LITERAL
    val isLearned = suggestion.source == SuggestionSource.LEARNED

    var pressed by remember { mutableStateOf(false) }
    val currentOnCommit by rememberUpdatedState(onCommit)

    val background = when {
        pressed -> colors.keyBackgroundPressed
        isLiteral -> colors.modifierBackground.copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    val textColor = when {
        isLearned -> colors.accentBackground
        else -> colors.keyText
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics {
                contentDescription = if (isLiteral) {
                    "Insert Romanized text ${suggestion.text}"
                } else {
                    "Insert word ${suggestion.text}"
                }
            }
            .pointerInput(suggestion.text) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    currentOnCommit(suggestion.text)
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = suggestion.text,
            style = if (isLiteral) {
                KeyboardTypography.Suggestion.copy(
                    fontFamily = KeyboardTypography.SuggestionHint.fontFamily
                )
            } else {
                KeyboardTypography.Suggestion
            },
            color = textColor,
            fontWeight = if (isLearned) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The chevron at the strip's leading edge that swaps candidates for the
 * feature toolbar and back.
 *
 * Press-on-down like the chips, so expanding the toolbar feels as instant as
 * committing a suggestion.
 */
@Composable
private fun ToolbarToggle(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val currentOnToggle by rememberUpdatedState(onToggle)
    var pressed by remember { mutableStateOf(false) }
    val colors = KeyboardTheme.colors
    // Resolved outside `semantics {}`: that block is not a composable
    // context, so `stringResource` cannot be called inside it.
    val toggleDescription = stringResource(
        if (expanded) R.string.cd_toolbar_collapse else R.string.cd_toolbar_expand
    )

    Box(
        modifier = Modifier
            .width(40.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) colors.keyBackgroundPressed else Color.Transparent)
            .semantics { contentDescription = toggleDescription }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    currentOnToggle()
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (expanded) {
                Icons.AutoMirrored.Filled.KeyboardArrowLeft
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            },
            contentDescription = null,
            tint = colors.tabInactive,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * The feature toolbar: emoji, clipboard, one-handed controls, settings.
 *
 * Replaces the candidate list while expanded, which is the platform
 * convention — the toolbar is a mode of the strip, not an extra row, so the
 * key grid never moves when it opens. Collapses automatically when the user
 * picks a panel (handled by the caller), and every button here collapses it
 * explicitly through [onCollapse] first so the strip is showing candidates
 * again by the time the new mode draws.
 */
@Composable
private fun ToolbarStrip(
    uiState: KeyboardUiState,
    viewModel: KeyboardViewModel,
    serviceActions: ServiceActions,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxHeight()
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        ToolbarButton(
            icon = Icons.Filled.EmojiEmotions,
            contentDescription = stringResource(R.string.cd_emoji_panel),
            onPress = {
                onCollapse()
                viewModel.switchMode(InputMode.EMOJI)
            }
        )
        ToolbarButton(
            icon = Icons.Filled.ContentPaste,
            contentDescription = stringResource(R.string.cd_clipboard_panel),
            onPress = {
                onCollapse()
                viewModel.switchMode(InputMode.CLIPBOARD)
            }
        )
        ChipDivider()
        ToolbarButton(
            icon = if (uiState.oneHanded == OneHandedSide.NONE) {
                Icons.Outlined.ExpandLess
            } else {
                Icons.Outlined.ExpandMore
            },
            contentDescription = stringResource(R.string.cd_one_handed_toggle),
            active = uiState.oneHanded != OneHandedSide.NONE,
            onPress = { viewModel.onOneHandedToggled() }
        )
        if (uiState.oneHanded != OneHandedSide.NONE) {
            ToolbarButton(
                icon = Icons.Outlined.SwapHoriz,
                contentDescription = stringResource(R.string.settings_one_handed),
                onPress = { viewModel.onOneHandedSwapped() }
            )
            ToolbarButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.cd_cursor_left),
                onPress = { viewModel.onKeyPressed(KeyboardKey.CursorLeft) }
            )
            ToolbarButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.cd_cursor_right),
                onPress = { viewModel.onKeyPressed(KeyboardKey.CursorRight) }
            )
        }
        ChipDivider()
        ToolbarButton(
            icon = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.settings_title),
            onPress = {
                onCollapse()
                serviceActions.openAppSettings()
            }
        )
    }
}

/**
 * One icon button in the toolbar.
 *
 * Same press-on-down contract as the suggestion chips: no ripple, no
 * release latency, one-key invalidation.
 */
@Composable
private fun ToolbarButton(
    icon: ImageVector,
    contentDescription: String,
    onPress: () -> Unit,
    active: Boolean = false
) {
    val currentOnPress by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val colors = KeyboardTheme.colors

    Box(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) colors.keyBackgroundPressed else Color.Transparent)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    currentOnPress()
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) colors.accentBackground else colors.tabInactive,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * A compact, single-line rendering of the strip used by the settings app's
 * sandbox preview, where vertical space is at a premium.
 */
@Composable
fun SuggestionStripCompact(
    suggestions: List<Suggestion>,
    modifier: Modifier = Modifier,
    onSuggestionCommitted: (String) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (suggestions.isEmpty()) {
            Spacer(modifier = Modifier.weight(1f))
        } else {
            for (suggestion in suggestions.take(3)) {
                SuggestionChip(suggestion = suggestion, onCommit = onSuggestionCommitted)
            }
        }
    }
}
