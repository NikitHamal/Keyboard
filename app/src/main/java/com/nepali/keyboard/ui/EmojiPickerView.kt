package com.nepali.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val EmojiCategories = listOf(
    "Smileys" to listOf("😊", "😂", "😃", "😍", "🥰", "😎", "🤔", "😅", "😭", "🙏", "🇳🇵", "👍", "❤️", "🔥", "✨", "🎉", "🙌", "🤩", "💩", "🥳"),
    "Gestures" to listOf("👍", "👎", "👏", "🙌", "🤝", "🤛", "🤜", "👊", "✊", "🤞", "🤟", "🤘", "👌", "🤌", "🤏", "👈", "👉", "👆", "👇", "✋"),
    "Hearts" to listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "☮️"),
    "Flags" to listOf("🇳🇵", "🇮🇳", "🇺🇸", "🇬🇧", "🇨🇦", "🇦🇺", "🇯🇵", "🇨🇳", "🇩🇪", "🇫🇷", "🇧🇷", "🇲🇽", "🇰🇷", "🇷🇺", "🇮🇹", "🇪🇸", "🇿🇦", "🇸🇬", "🇹🇭", "🇻🇳")
)

@Composable
fun EmojiPickerView(
    recentEmojis: List<String>,
    onEmojiSelect: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Emoji",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "✖",
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clickable { onClose() }
                    .padding(8.dp)
            )
        }

        // Category Tabs
        TabRow(
            selectedTabIndex = selectedCategoryIndex,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            EmojiCategories.forEachIndexed { index, category ->
                Tab(
                    selected = selectedCategoryIndex == index,
                    onClick = { selectedCategoryIndex = index },
                    text = { Text(category.first, fontSize = 12.sp) }
                )
            }
        }

        val emojisToDisplay = EmojiCategories.getOrNull(selectedCategoryIndex)?.second ?: emptyList()

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            items(emojisToDisplay) { emoji ->
                Text(
                    text = emoji,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clickable { onEmojiSelect(emoji) }
                        .padding(8.dp)
                )
            }
        }
    }
}
