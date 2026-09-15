package com.nikit.nepalikeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.clipboard.ClipItem
import com.nikit.nepalikeyboard.ui.theme.KeyboardTheme
import com.nikit.nepalikeyboard.ui.theme.KeyboardTypography

/**
 * =============================================================================
 * THE CLIPBOARD PANEL
 * =============================================================================
 *
 * Replaces the key grid while
 * [com.nikit.nepalikeyboard.ime.InputMode.CLIPBOARD] is active. A header, a
 * scrolling list of captured entries, and an empty state.
 *
 * ### Why paste rather than edit
 *
 * Tapping an entry inserts its text at the cursor. It does not open an editor.
 * A clipboard strip inside a keyboard is a recall surface, not a document
 * manager — the user's intent is always "put that text here", and any
 * intermediate screen is a step between them and it.
 *
 * ### Why pinning exists
 *
 * The history is capped and swept oldest-first. Without a pin, the one entry a
 * user actually wants — a bank account number, an address, a code they paste
 * daily — is guaranteed to be evicted eventually. Pinning is what makes the
 * history usable for recurring content rather than only for recent content.
 *
 * ### Privacy
 *
 * The header states, in one line, that the history never leaves the device.
 * That claim is true and enforced by the manifest rather than by policy — the
 * app declares no `INTERNET` permission at all — but a user has no way to
 * verify a claim they cannot see, so it is stated where the data lives.
 */
@Composable
fun ClipboardPanel(
    items: List<ClipItem>,
    onPaste: (ClipItem) -> Unit,
    onPin: (ClipItem, Boolean) -> Unit,
    onDelete: (ClipItem) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KeyboardTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.keyboardSurface)
    ) {
        PanelHeader(
            title = stringResource(R.string.tab_clipboard),
            note = stringResource(R.string.clipboard_privacy_note),
            actionLabel = if (items.isEmpty()) null else stringResource(R.string.clipboard_clear_all),
            onAction = onClearAll,
            onClose = onClose
        )

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.clipboard_empty),
                    style = KeyboardTypography.SuggestionHint,
                    color = colors.tabInactive
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = items,
                    // The text is the entry's identity in the store, so it is
                    // the correct key here too: pinning or deleting an entry
                    // must not shuffle unrelated rows' state.
                    key = { item -> item.text }
                ) { item ->
                    ClipboardRow(
                        item = item,
                        onPaste = { onPaste(item) },
                        onPin = { pinned -> onPin(item, pinned) },
                        onDelete = { onDelete(item) }
                    )
                }
            }
        }
    }
}

/**
 * The panel's header: title, privacy note, a clear-all action, and a close
 * button.
 *
 * Shared in shape with the emoji panel's tab strip so the two panels do not
 * feel like different apps, but not literally shared — the emoji panel's header
 * is a search field, and abstracting over "header that is sometimes a field and
 * sometimes a row of buttons" produces a worse component than two clear ones.
 */
@Composable
private fun PanelHeader(
    title: String,
    note: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onClose: () -> Unit
) {
    val colors = KeyboardTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = KeyboardTypography.Suggestion,
            color = colors.keyText,
            maxLines = 1
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = note,
            style = KeyboardTypography.SuggestionHint,
            color = colors.tabInactive,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (actionLabel != null) {
            Text(
                text = actionLabel,
                style = KeyboardTypography.SuggestionHint,
                color = colors.accentBackground,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .semantics { contentDescription = actionLabel }
                    .pointerInput(actionLabel) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onAction()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }

        // Close. Present on both panels so the way out is always in the same
        // place, rather than "tap the tab you came from" on one panel and a
        // button on the other.
        Text(
            text = "\u2715",
            style = KeyboardTypography.ModifierKey,
            color = colors.tabInactive,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .semantics { contentDescription = "Close" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onClose()
                    }
                }
                .padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

/**
 * One clipboard entry.
 *
 * The whole row pastes; the two icon buttons on the right pin and delete. That
 * split is deliberate: the row is the frequent action and gets the large
 * target, while pin and delete are rare, destructive-adjacent, and get small
 * targets that are hard to hit by accident.
 */
@Composable
private fun ClipboardRow(
    item: ClipItem,
    onPaste: () -> Unit,
    onPin: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val colors = KeyboardTheme.colors
    val currentOnPaste by rememberUpdatedState(onPaste)
    var pressed by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (pressed) {
                    colors.keyBackgroundPressed
                } else if (item.pinned) {
                    colors.modifierBackground
                } else {
                    colors.keyBackground
                }
            )
            .padding(start = 11.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- The paste target ------------------------------------------
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = item.text }
                .pointerInput(item.text) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        currentOnPaste()
                        waitForUpOrCancellation()
                        pressed = false
                    }
                }
        ) {
            Text(
                text = item.text.replace('\n', ' '),
                style = KeyboardTypography.Suggestion,
                color = colors.keyText,
                // Two lines is the point at which a phone number, an address,
                // and a sentence are all distinguishable at a glance, without
                // letting a pasted article take over the panel.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.label.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (item.pinned) {
                        "${item.label} \u00B7 ${stringResource(R.string.clipboard_pinned)}"
                    } else {
                        item.label
                    },
                    style = KeyboardTypography.SuggestionHint,
                    color = colors.tabInactive,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ---- Pin -------------------------------------------------------
        RowIconButton(
            icon = Icons.Filled.PushPin,
            contentDescription = stringResource(
                if (item.pinned) R.string.clipboard_unpin else R.string.clipboard_pin
            ),
            active = item.pinned,
            onPress = { onPin(!item.pinned) }
        )

        // ---- Delete ----------------------------------------------------
        RowIconButton(
            icon = Icons.Filled.DeleteOutline,
            contentDescription = stringResource(R.string.clipboard_delete),
            active = false,
            onPress = onDelete
        )
    }
}

/**
 * A compact icon button for a row's trailing controls.
 *
 * Not Material's `IconButton`, for the same reason the key grid does not use
 * `Button`: the Material component imposes a 48 dp touch target that would make
 * each row 48 dp taller than its content, and the targets here are intentionally
 * small because the row beside them is the action the user actually wants.
 */
@Composable
private fun RowIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    onPress: () -> Unit
) {
    val colors = KeyboardTheme.colors
    val currentOnPress by rememberUpdatedState(onPress)

    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(contentDescription) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    currentOnPress()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) colors.accentBackground else colors.tabInactive,
            modifier = Modifier.size(17.dp)
        )
    }
}
