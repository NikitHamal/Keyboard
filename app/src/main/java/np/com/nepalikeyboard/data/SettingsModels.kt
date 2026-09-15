package np.com.nepalikeyboard.data

import androidx.compose.runtime.Immutable

/**
 * Immutable, fully validated settings snapshot.
 *
 * The IME never reads DataStore directly on the keystroke path: it reads the
 * `@Volatile` snapshot cached by [SettingsRepository] so that no coroutine or
 * suspension is ever required to answer "is haptics on?" mid-typing.
 */
@Immutable
data class SettingsSnapshot(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val palette: PaletteId = PaletteId.HIMALAYA,
    val keyboardHeightScale: Float = 1.0f,
    val keyRoundnessScale: Float = 1.0f,
    val defaultLayout: LayoutPreference = LayoutPreference.ROMAN,
    val numberRow: Boolean = false,
    val oneHanded: OneHandedSide = OneHandedSide.OFF,
    val oneHandedWidthFraction: Float = 0.78f,
    val showSuggestions: Boolean = true,
    val showToolbar: Boolean = true,
    val autoCapitalize: Boolean = true,
    val autoCorrect: Boolean = false,
    val devanagariNumerals: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val hapticStrength: FeedbackStrength = FeedbackStrength.MEDIUM,
    val soundEnabled: Boolean = false,
    val cursorDragEnabled: Boolean = true,
    val swipeDeleteEnabled: Boolean = true,
    val cursorGlideSpeed: Float = 1.0f,
    val longPressSymbols: Boolean = true,
    val clipboardEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val schemaVersion: Int = SETTINGS_SCHEMA_VERSION,
) {
    val isDark: Boolean
        get() = themeMode == ThemeMode.DARK || themeMode == ThemeMode.AMOLED

    val isAmoled: Boolean
        get() = themeMode == ThemeMode.AMOLED

    companion object {
        /** Bump when a preference key is renamed or its semantics change. */
        const val SETTINGS_SCHEMA_VERSION: Int = 1

        val Default: SettingsSnapshot = SettingsSnapshot()
    }
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED,
}

enum class PaletteId {
    HIMALAYA,
    RHODODENDRON,
    MUSTARD,
    INDIGO,
}

enum class OneHandedSide {
    OFF,
    LEFT,
    RIGHT,
}

enum class FeedbackStrength(val amplitudeFraction: Float, val durationMs: Long) {
    LIGHT(0.35f, 12L),
    MEDIUM(0.6f, 18L),
    STRONG(1.0f, 26L),
}

/** Which script the keyboard opens with in a plain text field. */
enum class LayoutPreference {
    /** Romanized Nepali -> Devanagari (the primary USP). */
    ROMAN,

    /** Direct Devanagari key layout. */
    NATIVE,

    /** Plain ASCII QWERTY. */
    ENGLISH,
}

/** Reusable, allocation-free enum decoding for DataStore string values. */
internal inline fun <reified T : Enum<T>> String?.toEnumOr(default: T): T {
    if (this == null) return default
    return try {
        enumValueOf<T>(this)
    } catch (_: IllegalArgumentException) {
        default
    }
}
