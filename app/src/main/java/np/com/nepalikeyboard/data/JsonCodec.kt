package np.com.nepalikeyboard.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Single JSON configuration for every on-device store.
 *
 * `ignoreUnknownKeys` is essential: a newer build must be able to read state
 * written by an older one without wiping the user's learned words.
 */
internal val KeyboardJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
    coerceInputValues = true
}

// ---------------------------------------------------------------------------
// Personal dictionary / learning payloads
// ---------------------------------------------------------------------------

@Serializable
internal data class PersonalWordDto(
    val word: String,
    val roman: String = "",
    val hits: Int = 1,
    val createdAt: Long = 0L,
    /** true = added by the user, false = learned automatically. */
    val personal: Boolean = true,
)

@Serializable
internal data class BigramDto(
    val first: String,
    val second: String,
    val count: Int,
)

@Serializable
internal data class LearningPayloadDto(
    val version: Int = 1,
    val words: List<PersonalWordDto> = emptyList(),
    val bigrams: List<BigramDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Clipboard payload
// ---------------------------------------------------------------------------

@Serializable
internal data class ClipboardEntryDto(
    val text: String,
    val timestamp: Long,
    val pinned: Boolean = false,
)

@Serializable
internal data class ClipboardPayloadDto(
    val version: Int = 1,
    val items: List<ClipboardEntryDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Emoji recents payload
// ---------------------------------------------------------------------------

@Serializable
internal data class EmojiRecentsDto(
    val version: Int = 1,
    val emojis: List<String> = emptyList(),
)
