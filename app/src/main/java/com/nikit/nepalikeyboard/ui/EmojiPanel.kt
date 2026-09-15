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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.ui.theme.KeyboardTheme
import com.nikit.nepalikeyboard.ui.theme.KeyboardTypography

/**
 * =============================================================================
 * THE EMOJI PANEL
 * =============================================================================
 *
 * Replaces the key grid while [com.nikit.nepalikeyboard.ime.InputMode.EMOJI] is
 * active. Three bands, top to bottom: a search field, a category tab strip, and
 * a scrolling grid of emoji.
 *
 * ### Why search and categories are both present
 *
 * Categorised tabs are how you browse — they need no keyboard input to work,
 * which matters enormously here: this panel *is* the keyboard, and the user
 * cannot type into a search box until they have found what they want to type.
 * Search is the escape hatch for the case where browsing fails, and it is
 * reachable because the user can switch back to a key mode, type the query, and
 * switch back — which the mode cycle makes a two-tap round trip.
 *
 * ### Why recents is a tab rather than a section
 *
 * A "recently used" row pinned above the grid would be the fourth band and
 * would cost a row of grid height permanently, for content that is empty on a
 * fresh install. As a tab it costs nothing until it has content, and it appears
 * at the front of the strip where the eye lands first.
 *
 * ### Emoji are drawn as text, not as images
 *
 * The system emoji font is a colour font, so a `Text` with the emoji glyph
 * renders in full colour on every device. Shipping images would add megabytes
 * to the APK and immediately be visually stale against the platform set on a
 * newer device. Drawing them as text also means the picker inherits the
 * platform's emoji rendering, which is what the user sees when the emoji is
 * finally inserted — so what the picker shows is exactly what the field gets.
 */
@Composable
fun EmojiPanel(
    recentEmoji: List<String>,
    categories: List<String>,
    activeCategory: String,
    query: String,
    onCategorySelected: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onEmojiSelected: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KeyboardTheme.colors.keyboardSurface)
    ) {
        EmojiSearchField(
            query = query,
            onQueryChanged = onQueryChanged
        )

        CategoryTabs(
            recentEmoji = recentEmoji,
            categories = categories,
            activeCategory = activeCategory,
            onCategorySelected = onCategorySelected
        )

        EmojiGrid(
            recentEmoji = recentEmoji,
            activeCategory = activeCategory,
            query = query,
            onEmojiSelected = onEmojiSelected,
            onNoResults = onClose,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The search field.
 *
 * A `BasicTextField` rather than a Material `TextField`: the Material component
 * carries a 56 dp minimum height, a label, a supporting-text slot, and an
 * indicator line — four things this panel has no room for and no use for. The
 * keyboard's own search box needs to be a single 34 dp line.
 *
 * The field is backed by the *system* IME, which is a slightly odd thing to say
 * about a keyboard app, but it is the correct behaviour: tapping here hands
 * focus to another input method and the user searches in their own language.
 * That is why this field has no `KeyboardOptions` constraints — the emoji names
 * include Romanized Nepali, so a numeric or Latin-only constraint would be
 * wrong.
 */
@Composable
private fun EmojiSearchField(
    query: String,
    onQueryChanged: (String) -> Unit
) {
    val colors = KeyboardTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.stripSurface)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = colors.tabInactive,
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(7.dp))

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.emoji_search_hint),
                    style = KeyboardTypography.SuggestionHint,
                    color = colors.tabInactive,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                singleLine = true,
                textStyle = KeyboardTypography.Suggestion,
                cursorBrush = SolidColor(colors.accentBackground),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                tint = colors.tabInactive,
                modifier = Modifier
                    .size(16.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onQueryChanged("")
                        }
                    }
            )
        }
    }
}

/**
 * The category tab strip.
 *
 * Horizontally scrollable rather than evenly divided: nine tabs on a phone
 * would each be about 40 dp wide, which is narrower than the label "Symbols"
 * and forces either truncation or an unreadable font size. Scrolling costs one
 * gesture and keeps every label legible.
 */
@Composable
private fun CategoryTabs(
    recentEmoji: List<String>,
    categories: List<String>,
    activeCategory: String,
    onCategorySelected: (String) -> Unit
) {
    val colors = KeyboardTheme.colors

    // Recents is prepended only when it has content. See the file header.
    val visible = remember(recentEmoji, categories) {
        if (recentEmoji.isEmpty()) categories else listOf(RECENT_CATEGORY_ID) + categories
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (id in visible) {
            val selected = id == activeCategory
            val label = when (id) {
                RECENT_CATEGORY_ID -> stringResource(R.string.emoji_category_recent)
                else -> {
                    val index = categories.indexOf(id)
                    if (index >= 0) {
                        EmojiCatalog.CATEGORIES.getOrNull(index)
                            ?.let { stringResource(it.labelRes) }
                            ?: id
                    } else {
                        id
                    }
                }
            }

            Text(
                text = label,
                style = KeyboardTypography.SuggestionHint,
                color = if (selected) colors.accentBackground else colors.tabInactive,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (selected) colors.modifierBackground else Color.Transparent
                    )
                    .semantics { contentDescription = label }
                    .pointerInput(id) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onCategorySelected(id)
                        }
                    }
                    .padding(vertical = 6.dp)
            )
        }
    }
}

/**
 * The scrolling grid of emoji.
 *
 * A search query takes priority over the category selection: with a query
 * present, the grid shows matches from the whole catalogue and the tabs are
 * ignored. The alternative — searching within the selected category — is a
 * strictly worse default, because a user who types "flag" wants the flag, not
 * "no results in Smileys".
 */
@Composable
private fun EmojiGrid(
    recentEmoji: List<String>,
    activeCategory: String,
    query: String,
    onEmojiSelected: (String) -> Unit,
    onNoResults: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KeyboardTheme.colors
    val searching = query.isNotBlank()

    val glyphs: List<String> = remember(query, activeCategory, recentEmoji) {
        if (searching) {
            EmojiCatalog.search(query)
        } else if (activeCategory == RECENT_CATEGORY_ID) {
            recentEmoji
        } else {
            val index = EmojiCatalog.CATEGORIES.indexOfFirst { it.id == activeCategory }
            if (index >= 0) EmojiCatalog.CATEGORIES[index].entries.map { it.glyph } else emptyList()
        }
    }

    if (glyphs.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.suggestion_no_results),
                style = KeyboardTypography.SuggestionHint,
                color = colors.tabInactive
            )
        }
        return
    }

    // Pair each glyph with its position once, up front. The grid needs a stable
    // key per item, and the glyph itself is not unique — a few emoji
    // legitimately appear in two categories, and search results dedupe by
    // glyph, so two rows can legitimately carry the same character. Doing the
    // index lookup inside the `key` lambda would be O(n) per item and therefore
    // O(n²) every time the grid recomposes, which for a 500-entry catalogue is
    // visible.
    val keyed: List<Pair<Int, String>> = remember(glyphs) {
        glyphs.mapIndexed { index, glyph -> index to glyph }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(EMOJI_COLUMNS),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(
            items = keyed,
            key = { (index, glyph) -> "$index:$glyph" }
        ) { (_, glyph) ->
            EmojiCell(
                glyph = glyph,
                onPress = { onEmojiSelected(glyph) }
            )
        }
    }
}

/**
 * Number of emoji per row.
 *
 * Nine rather than eight: at 360 dp of usable width, nine cells give 38 dp per
 * cell after padding, which is still comfortably above the 24 dp minimum a
 * thumb can reliably hit for a non-critical target — and emoji taps are
 * non-critical, because a mis-tap inserts a wrong emoji that the user can
 * simply backspace over. Eight columns would make every cell 43 dp and show a
 * quarter fewer emoji per screen.
 */
private const val EMOJI_COLUMNS = 9

/** Tab identifier for the recents pseudo-category. */
const val RECENT_CATEGORY_ID = "__recent__"

/**
 * A single emoji cell.
 *
 * Press-on-down, like every other key in this keyboard, so the picker feels
 * like the keyboard it replaces rather than like a list of menu items.
 */
@Composable
private fun EmojiCell(
    glyph: String,
    onPress: () -> Unit
) {
    val currentOnPress by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val colors = KeyboardTheme.colors

    Box(
        modifier = Modifier
            .height(EMOJI_CELL_HEIGHT)
            .clip(RoundedCornerShape(7.dp))
            .background(if (pressed) colors.keyBackgroundPressed else Color.Transparent)
            .pointerInput(glyph) {
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
        Text(
            text = glyph,
            style = KeyboardTypography.Emoji,
            maxLines = 1
        )
    }
}

/** Cell height. Chosen to fit the 24 sp emoji with its ascender and descender. */
private val EMOJI_CELL_HEIGHT = 36.dp
