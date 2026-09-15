package np.com.nepalikeyboard.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.ime.ImePanel
import np.com.nepalikeyboard.ime.ImeUiState
import np.com.nepalikeyboard.ime.KeyboardActionSink
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.latinStyle
import np.com.nepalikeyboard.util.EmojiCatalog
import np.com.nepalikeyboard.util.EmojiCategory
import np.com.nepalikeyboard.util.EmojiEntry

/**
 * Searchable, categorised emoji picker.
 *
 * The catalog is a compiled-in table (no assets, no fonts of its own - emoji
 * glyphs come from the system font), so filtering is a linear scan over a few
 * hundred entries and never touches disk. Recents come from DataStore and are
 * owned by the controller; this panel only reads them.
 */
@Composable
fun EmojiPanel(
    state: ImeUiState,
    sink: KeyboardActionSink,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    val query = state.emojiQuery
    val searching = query.isNotBlank()
    val entries: List<EmojiEntry> = when {
        searching -> remember(query) { EmojiCatalog.search(query) }
        state.emojiCategory == EmojiCategory.RECENT ->
            remember(state.emojiRecents) {
                state.emojiRecents.map { emoji -> EmojiEntry(emoji = emoji, keywords = emoji) }
            }

        else -> remember(state.emojiCategory) { EmojiCatalog.entriesFor(state.emojiCategory) }
    }

    Column(modifier = modifier.fillMaxSize().background(tokens.panelBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.toolbarHeight)
                .padding(horizontal = tokens.sidePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PanelBackButton(
                label = stringResource(R.string.key_letters),
                tokens = tokens,
                onClick = { sink.onPanelRequested(ImePanel.EMOJI) },
            )
            EmojiSearchField(
                query = query,
                tokens = tokens,
                onQueryChanged = { sink.onEmojiQueryChanged(it) },
                modifier = Modifier.weight(1f),
            )
            if (state.emojiCategory == EmojiCategory.RECENT && !searching) {
                PanelBackButton(
                    label = stringResource(R.string.emoji_recent_clear),
                    tokens = tokens,
                    onClick = { sink.onClearEmojiRecents() },
                )
            }
        }

        if (!searching) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = tokens.sidePadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (category in EmojiCategory.entries) {
                    CategoryChip(
                        label = stringResource(categoryLabelRes(category)),
                        selected = category == state.emojiCategory,
                        tokens = tokens,
                        onClick = { sink.onEmojiCategoryChanged(category) },
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.emoji_empty),
                    style = latinStyle(12.sp),
                    color = tokens.iconTint,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 40.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(count = entries.size, key = { index -> entries[index].emoji + index }) { index ->
                    val entry = entries[index]
                    EmojiCell(entry = entry, tokens = tokens, onClick = { sink.onEmojiSelected(entry.emoji) })
                }
            }
        }
    }
}

@Composable
private fun EmojiCell(entry: EmojiEntry, tokens: KeyboardTokens, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(tokens.keyCornerRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = entry.emoji,
            style = latinStyle(22.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun EmojiSearchField(
    query: String,
    tokens: KeyboardTokens,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(tokens.toolbarHeight - 8.dp)
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(tokens.panelSurface)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (query.isEmpty()) {
            Text(
                text = stringResource(R.string.emoji_search_hint),
                style = latinStyle(12.sp),
                color = tokens.iconTint,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChanged,
            singleLine = true,
            textStyle = latinStyle(13.sp).copy(color = tokens.suggestionText),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    tokens: KeyboardTokens,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(if (selected) tokens.suggestionChipSelected else tokens.functionKeyBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
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

/**
 * Panel close chip.
 *
 * [label] is shown to the user; the panel it closes is passed as the click
 * action so the toggle in the controller always resolves to "close".
 */
@Composable
private fun PanelBackButton(
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

private fun categoryLabelRes(category: EmojiCategory): Int = when (category) {
    EmojiCategory.RECENT -> R.string.emoji_category_recent
    EmojiCategory.SMILEYS -> R.string.emoji_category_smileys
    EmojiCategory.PEOPLE -> R.string.emoji_category_people
    EmojiCategory.ANIMALS -> R.string.emoji_category_animals
    EmojiCategory.FOOD -> R.string.emoji_category_food
    EmojiCategory.TRAVEL -> R.string.emoji_category_travel
    EmojiCategory.ACTIVITIES -> R.string.emoji_category_activities
    EmojiCategory.OBJECTS -> R.string.emoji_category_objects
    EmojiCategory.SYMBOLS -> R.string.emoji_category_symbols
    EmojiCategory.FLAGS -> R.string.emoji_category_flags
}
