package np.com.nepalikeyboard.keyboard

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.data.ClipboardEntry
import np.com.nepalikeyboard.ime.ImeUiState
import np.com.nepalikeyboard.ime.KeyboardActionSink
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * Clipboard history.
 *
 * Rows are ordered pinned-first then newest-first, one tap pastes. The history
 * itself is maintained by `ClipboardRepository`, which only observes the system
 * clipboard while the keyboard is up and never while a sensitive field has
 * focus - so this panel shows exactly what the user could already have pasted by
 * hand, and nothing else.
 */
@Composable
fun ClipboardPanel(
    state: ImeUiState,
    sink: KeyboardActionSink,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    val entries = state.clipboardEntries

    Column(modifier = modifier.fillMaxSize().background(tokens.panelBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.toolbarHeight)
                .padding(horizontal = tokens.sidePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PanelChip(
                label = stringResource(R.string.key_letters),
                tokens = tokens,
                onClick = { sink.onPanelRequested(ImePanel.CLIPBOARD) },
            )
            Text(
                text = stringResource(R.string.clipboard_header),
                style = latinStyle(12.sp, FontWeight.SemiBold),
                color = tokens.iconTintActive,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (state.clipboardEnabled && entries.isNotEmpty()) {
                PanelChip(
                    label = stringResource(R.string.clipboard_clear_all),
                    tokens = tokens,
                    onClick = { sink.onClipboardCleared() },
                )
            }
        }

        if (!state.clipboardEnabled) {
            PanelMessage(text = stringResource(R.string.clipboard_secure_notice), tokens = tokens)
            return@Column
        }
        if (entries.isEmpty()) {
            PanelMessage(text = stringResource(R.string.clipboard_empty), tokens = tokens)
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items = entries, key = { entry -> entry.id }) { entry ->
                ClipboardRow(
                    entry = entry,
                    tokens = tokens,
                    onPaste = { sink.onClipboardEntrySelected(entry) },
                    onPin = { sink.onClipboardEntryPinned(entry) },
                    onRemove = { sink.onClipboardEntryRemoved(entry) },
                )
            }
        }
    }
}

@Composable
private fun ClipboardRow(
    entry: ClipboardEntry,
    tokens: KeyboardTokens,
    onPaste: () -> Unit,
    onPin: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(tokens.functionKeyBackground)
            .clickable(onClick = onPaste)
            .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.text,
                style = latinStyle(13.sp),
                color = tokens.suggestionText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DateUtils.getRelativeTimeSpanString(entry.timestampMillis).toString(),
                style = latinStyle(9.sp),
                color = tokens.iconTint,
                maxLines = 1,
            )
        }
        RowAction(
            icon = Icons.Filled.PushPin,
            description = stringResource(R.string.clipboard_pin_item),
            tokens = tokens,
            highlighted = entry.pinned,
            onClick = onPin,
        )
        RowAction(
            icon = Icons.Filled.Delete,
            description = stringResource(R.string.clipboard_remove_item),
            tokens = tokens,
            highlighted = false,
            onClick = onRemove,
        )
        Spacer(Modifier.width(2.dp))
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tokens: KeyboardTokens,
    highlighted: Boolean,
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
            tint = if (highlighted) tokens.iconTintActive else tokens.iconTint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PanelChip(
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

@Composable
private fun PanelMessage(text: String, tokens: KeyboardTokens) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = latinStyle(12.sp),
            color = tokens.iconTint,
        )
    }
}
