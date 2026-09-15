package com.nikit.nepalikeyboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nikit.nepalikeyboard.ime.InputMode
import com.nikit.nepalikeyboard.ime.OneHandedSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * =============================================================================
 * USER PREFERENCES
 * =============================================================================
 *
 * An immutable snapshot of everything the user can configure. Passed around as
 * a value so a keystroke handler can read fourteen preferences from one object
 * with no I/O and no suspension — which matters because several of them
 * (haptics, learning, double-space) are consulted on every single key.
 *
 * Every property has a default that matches the shipped behaviour, so a fresh
 * install and a corrupted preferences file both produce a working keyboard.
 */
data class KeyboardPreferences(
    /** Mode the keyboard opens in. */
    val defaultMode: InputMode = InputMode.ROMANIZED,

    /** Wrap the keyboard toward one edge for thumb reach. */
    val oneHandedSide: OneHandedSide = OneHandedSide.NONE,

    /**
     * Keyboard height in density-independent pixels, measured as the height of
     * the three key rows plus the suggestion strip. The adjustable-height
     * setting maps to this.
     */
    val keyboardHeightDp: Int = DEFAULT_KEYBOARD_HEIGHT_DP,

    /** Capitalise the first letter after a sentence terminator. */
    val autoCapitalise: Boolean = true,

    /** Two quick spaces insert ". " and start a new sentence. */
    val doubleSpacePeriod: Boolean = true,

    /** Show the transliteration and lexicon strip above the keys. */
    val showSuggestions: Boolean = true,

    /**
     * Raise the rank of words the user accepts, locally.
     *
     * Turned off, the learned-word table is neither written nor read. This is a
     * genuine privacy control, not a cosmetic one: with it off, nothing the user
     * types is retained anywhere.
     */
    val learnWords: Boolean = true,

    /** Vibrate on key press. */
    val hapticsEnabled: Boolean = true,

    /**
     * Vibration amplitude, `0..255`, or [HAPTIC_SYSTEM_DEFAULT] to defer to the
     * OS-wide haptics setting. Zero means "use the system default" rather than
     * "off" — off is expressed by [hapticsEnabled].
     */
    val hapticStrength: Int = HAPTIC_SYSTEM_DEFAULT,

    /** Play the system key-click sound. */
    val soundEnabled: Boolean = false,

    /** Draw a hairline border around each key. */
    val showKeyBorders: Boolean = false,

    /**
     * Material You dynamic colour, where the platform supports it.
     *
     * Off by default: a wallpaper-derived palette recolours the settings
     * chrome away from the keyboard's crimson identity (and on some devices
     * reads as an unrelated blue theme), while the key geometry intentionally
     * never follows it. Users who prefer wallpaper tinting can opt in.
     */
    val dynamicColor: Boolean = false,

    /** Theme selection. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    /** Keep a local clipboard history visible inside the keyboard. */
    val clipboardHistoryEnabled: Boolean = true,

    /** Maximum unpinned clipboard entries retained. */
    val clipboardHistoryLimit: Int = DEFAULT_CLIPBOARD_LIMIT,

    /**
     * Surround inserted emoji with spaces.
     *
     * Correct for prose in either script, but wrong inside a word or a
     * hashtag, so it is a user choice rather than a constant.
     */
    val emojiAsSpacedTokens: Boolean = true,

    /** True once the onboarding flow has been completed. */
    val onboardingComplete: Boolean = false
) {
    companion object {
        /**
         * Sentinel for "let the platform decide the vibration amplitude".
         * Chosen as `-1` so it cannot collide with a real amplitude.
         */
        const val HAPTIC_SYSTEM_DEFAULT = -1

        /** Default height of the key area, in dp. */
        const val DEFAULT_KEYBOARD_HEIGHT_DP = 268

        /** Default number of unpinned clipboard entries retained. */
        const val DEFAULT_CLIPBOARD_LIMIT = 25

        /** Clamp bounds the settings UI enforces on the height slider. */
        val HEIGHT_RANGE_DP = 200..360

        /** Clamp bounds the settings UI enforces on the clipboard limit. */
        val CLIPBOARD_LIMIT_RANGE = 5..100

        /** Clamp bounds on the haptic amplitude. */
        val HAPTIC_STRENGTH_RANGE = 20..255
    }
}

/**
 * Which colour scheme to apply.
 *
 * `AMOLED` is not merely "very dark": it forces the surfaces to true `#000000`
 * so that pixels are switched off entirely on a panel that supports it. That is
 * a materially different power outcome from a dark grey, which is why it is a
 * separate option rather than a fourth darkness level under [DARK].
 */
enum class ThemeMode {
    /** Follow the system light/dark setting. */
    SYSTEM,

    /** Always light. */
    LIGHT,

    /** Always dark. */
    DARK,

    /** Always dark, with true-black surfaces. */
    AMOLED;

    companion object {
        fun fromOrdinalOrSystem(value: Int): ThemeMode {
            val values = entries
            return if (value in values.indices) values[value] else SYSTEM
        }
    }
}

/**
 * =============================================================================
 * PREFERENCES REPOSITORY
 * =============================================================================
 *
 * A DataStore-backed store for [KeyboardPreferences].
 *
 * ### Why DataStore rather than SharedPreferences
 *
 * `SharedPreferences` commits are synchronous and can block the calling thread
 * by tens of milliseconds. In an IME that is a dropped frame on a keystroke,
 * and an IME is precisely the app where a dropped frame is most visible. Its
 * `apply()` is asynchronous but offers no flow, so observers must register
 * listeners manually and there is no ordering guarantee. DataStore gives us a
 * `Flow` with transactional writes and no blocking API at all.
 *
 * ### Why writes are fire-and-forget
 *
 * Every setter launches on an internal scope and returns immediately. The UI
 * already reflects the change optimistically (Compose state updated before the
 * write), so there is nothing for the caller to await. A failed write is logged
 * and the in-memory value stands — losing a preference change is a far smaller
 * problem than stalling the keyboard to report the failure.
 *
 * ### Single instance
 *
 * DataStore forbids two instances over the same file; it throws
 * `IllegalStateException: There are multiple DataStores active for the same
 * file`. Since the keyboard, the settings activity, and the sandbox all run in
 * one process and all want these preferences, a process-level singleton is
 * mandatory rather than merely convenient.
 */
class SettingsRepository private constructor(
    private val context: Context
) {

    /**
     * Internal scope for writes.
     *
     * `Dispatchers.IO` because DataStore touches the filesystem.
     * `SupervisorJob` so one failed write does not poison the scope for every
     * later write — a mistake that would silently make all settings
     * non-persistent for the rest of the session.
     */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A cold flow of the current preferences.
     *
     * `catch` swallows the `IOException` DataStore raises when the file is
     * unreadable (a corrupt file after an unclean shutdown, or a permission
     * problem on a managed device). Emitting defaults in that case is right:
     * the user gets a working keyboard with shipped settings rather than a
     * crash loop on every launch.
     */
    val flow: Flow<KeyboardPreferences> = context.settingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs.toKeyboardPreferences() }

    /** Reads the current values once. */
    suspend fun load(): KeyboardPreferences = flow.first()

    /**
     * Ensures DataStore has been opened, without waiting for a value.
     *
     * Called from `NepaliKeyboardApp` so the first keystroke's preference read
     * does not pay the file-open cost. Note that this deliberately does *not*
     * block: it launches and returns.
     */
    fun prime() {
        writeScope.launch {
            try {
                flow.first()
            } catch (t: Throwable) {
                // Nothing to do; defaults will be used.
            }
        }
    }

    // =========================================================================
    // Setters
    //
    // Each writes through DataStore's transactional `edit`, which serialises
    // concurrent writers for us. No setter is blocking.
    // =========================================================================

    fun setDefaultMode(mode: InputMode) = write { it[PrefKeys.DEFAULT_MODE] = mode.ordinal }

    fun saveOneHandedSide(side: OneHandedSide) = write { it[PrefKeys.ONE_HANDED] = side.ordinal }

    fun setKeyboardHeightDp(dp: Int) = write {
        it[PrefKeys.KEYBOARD_HEIGHT] = dp.coerceIn(
            KeyboardPreferences.HEIGHT_RANGE_DP.first,
            KeyboardPreferences.HEIGHT_RANGE_DP.last
        )
    }

    fun setAutoCapitalise(enabled: Boolean) = write { it[PrefKeys.AUTO_CAPITALISE] = enabled }

    fun setDoubleSpacePeriod(enabled: Boolean) = write { it[PrefKeys.DOUBLE_SPACE] = enabled }

    fun setShowSuggestions(enabled: Boolean) = write { it[PrefKeys.SHOW_SUGGESTIONS] = enabled }

    fun setLearnWords(enabled: Boolean) = write { it[PrefKeys.LEARN_WORDS] = enabled }

    fun setHapticsEnabled(enabled: Boolean) = write { it[PrefKeys.HAPTICS] = enabled }

    fun setHapticStrength(strength: Int) = write {
        it[PrefKeys.HAPTIC_STRENGTH] = if (strength == KeyboardPreferences.HAPTIC_SYSTEM_DEFAULT) {
            KeyboardPreferences.HAPTIC_SYSTEM_DEFAULT
        } else {
            strength.coerceIn(
                KeyboardPreferences.HAPTIC_STRENGTH_RANGE.first,
                KeyboardPreferences.HAPTIC_STRENGTH_RANGE.last
            )
        }
    }

    fun setSoundEnabled(enabled: Boolean) = write { it[PrefKeys.SOUND] = enabled }

    fun setShowKeyBorders(enabled: Boolean) = write { it[PrefKeys.KEY_BORDERS] = enabled }

    fun setDynamicColor(enabled: Boolean) = write { it[PrefKeys.DYNAMIC_COLOR] = enabled }

    fun setThemeMode(mode: ThemeMode) = write { it[PrefKeys.THEME_MODE] = mode.ordinal }

    fun setClipboardHistoryEnabled(enabled: Boolean) = write { it[PrefKeys.CLIPBOARD_ENABLED] = enabled }

    fun setClipboardHistoryLimit(limit: Int) = write {
        it[PrefKeys.CLIPBOARD_LIMIT] = limit.coerceIn(
            KeyboardPreferences.CLIPBOARD_LIMIT_RANGE.first,
            KeyboardPreferences.CLIPBOARD_LIMIT_RANGE.last
        )
    }

    fun setEmojiAsSpacedTokens(enabled: Boolean) = write { it[PrefKeys.EMOJI_SPACED] = enabled }

    fun setOnboardingComplete(complete: Boolean) = write { it[PrefKeys.ONBOARDING_DONE] = complete }

    // =========================================================================
    // Emoji recents
    //
    // Stored as a single delimited string rather than a set of preferences keys
    // because DataStore's `stringSetPreferencesKey` gives no ordering, and the
    // whole point of "recently used" is that order is the data.
    // =========================================================================

    suspend fun loadRecentEmoji(): List<String> {
        val stored = context.settingsDataStore.data
            .catch { emit(emptyPreferences()) }
            .first()[PrefKeys.RECENT_EMOJI]
            ?: return emptyList()
        if (stored.isEmpty()) return emptyList()
        return stored.split(EMOJI_SEPARATOR).filter { it.isNotEmpty() }
    }

    fun saveRecentEmoji(emoji: List<String>) {
        writeScope.launch {
            try {
                context.settingsDataStore.edit { prefs ->
                    prefs[PrefKeys.RECENT_EMOJI] = emoji.joinToString(EMOJI_SEPARATOR)
                }
            } catch (t: Throwable) {
                // Recents are a convenience; losing them is not worth surfacing.
            }
        }
    }

    private fun write(block: (MutablePreferences) -> Unit) {
        writeScope.launch {
            try {
                context.settingsDataStore.edit(block)
            } catch (t: Throwable) {
                // A failed preference write must never break typing.
            }
        }
    }

    companion object {
        /**
         * Name of the DataStore file. Changing this orphans every existing
         * user's settings, so it is effectively immutable.
         */
        internal const val DATASTORE_NAME = "nepali_keyboard_settings"

        /**
         * Separator for the emoji recents list.
         *
         * U+001F (unit separator) cannot occur inside an emoji sequence,
         * unlike a comma, a space, or the zero-width joiner that emoji
         * themselves use.
         */
        internal const val EMOJI_SEPARATOR = "\u001F"

        /**
         * The process-wide instance.
         *
         * `@Volatile` plus double-checked locking: the keyboard service and
         * the settings activity can both call `get` from different threads
         * within the same millisecond, and DataStore throws if two instances
         * are created over one file.
         */
        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                val second = instance
                if (second != null) {
                    second
                } else {
                    SettingsRepository(context.applicationContext).also { instance = it }
                }
            }
        }
    }

    /**
     * Maps the raw preference bag onto the typed model, applying defaults for
     * any key the user has never set.
     *
     * Every read goes through the same clamp and ordinal-parse helpers the
     * setters use, so a hand-edited or migrated file cannot produce an
     * out-of-range height or an invalid enum ordinal.
     */
    private fun Preferences.toKeyboardPreferences(): KeyboardPreferences {
        val defaults = KeyboardPreferences()
        return KeyboardPreferences(
            defaultMode = this[PrefKeys.DEFAULT_MODE]
                ?.let { InputMode.fromOrdinalOrRomanized(it) }
                ?: defaults.defaultMode,
            oneHandedSide = this[PrefKeys.ONE_HANDED]
                ?.let { OneHandedSide.fromOrdinalOrNone(it) }
                ?: defaults.oneHandedSide,
            keyboardHeightDp = this[PrefKeys.KEYBOARD_HEIGHT]
                ?.coerceIn(
                    KeyboardPreferences.HEIGHT_RANGE_DP.first,
                    KeyboardPreferences.HEIGHT_RANGE_DP.last
                )
                ?: defaults.keyboardHeightDp,
            autoCapitalise = this[PrefKeys.AUTO_CAPITALISE] ?: defaults.autoCapitalise,
            doubleSpacePeriod = this[PrefKeys.DOUBLE_SPACE] ?: defaults.doubleSpacePeriod,
            showSuggestions = this[PrefKeys.SHOW_SUGGESTIONS] ?: defaults.showSuggestions,
            learnWords = this[PrefKeys.LEARN_WORDS] ?: defaults.learnWords,
            hapticsEnabled = this[PrefKeys.HAPTICS] ?: defaults.hapticsEnabled,
            hapticStrength = this[PrefKeys.HAPTIC_STRENGTH] ?: defaults.hapticStrength,
            soundEnabled = this[PrefKeys.SOUND] ?: defaults.soundEnabled,
            showKeyBorders = this[PrefKeys.KEY_BORDERS] ?: defaults.showKeyBorders,
            dynamicColor = this[PrefKeys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            themeMode = this[PrefKeys.THEME_MODE]
                ?.let { ThemeMode.fromOrdinalOrSystem(it) }
                ?: defaults.themeMode,
            clipboardHistoryEnabled = this[PrefKeys.CLIPBOARD_ENABLED]
                ?: defaults.clipboardHistoryEnabled,
            clipboardHistoryLimit = this[PrefKeys.CLIPBOARD_LIMIT]
                ?.coerceIn(
                    KeyboardPreferences.CLIPBOARD_LIMIT_RANGE.first,
                    KeyboardPreferences.CLIPBOARD_LIMIT_RANGE.last
                )
                ?: defaults.clipboardHistoryLimit,
            emojiAsSpacedTokens = this[PrefKeys.EMOJI_SPACED] ?: defaults.emojiAsSpacedTokens,
            onboardingComplete = this[PrefKeys.ONBOARDING_DONE] ?: defaults.onboardingComplete
        )
    }
}

/**
 * The single DataStore instance for the process.
 *
 * Declared as a file-level extension property so the `preferencesDataStore`
 * delegate is created exactly once. Putting the delegate inside the class would
 * create a new one per instance, which is precisely the mistake that produces
 * the "multiple DataStores active for the same file" crash.
 *
 * It is a `Context` extension so that both the repository and any future caller
 * resolve the same delegate from the same receiver type.
 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsRepository.DATASTORE_NAME
)

/**
 * The preference keys, gathered in one object.
 *
 * Namespaced separately from [SettingsRepository] so that both the class and
 * the file-level mapping function can read them without either reaching into
 * the other's private scope. The string values are the on-disk names: changing
 * one silently resets that setting for every existing user, so they are
 * effectively immutable.
 */
internal object PrefKeys {
    val DEFAULT_MODE = intPreferencesKey("default_mode")
    val ONE_HANDED = intPreferencesKey("one_handed_side")
    val KEYBOARD_HEIGHT = intPreferencesKey("keyboard_height_dp")
    val AUTO_CAPITALISE = booleanPreferencesKey("auto_capitalise")
    val DOUBLE_SPACE = booleanPreferencesKey("double_space_period")
    val SHOW_SUGGESTIONS = booleanPreferencesKey("show_suggestions")
    val LEARN_WORDS = booleanPreferencesKey("learn_words")
    val HAPTICS = booleanPreferencesKey("haptics_enabled")
    val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
    val SOUND = booleanPreferencesKey("sound_enabled")
    val KEY_BORDERS = booleanPreferencesKey("show_key_borders")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val THEME_MODE = intPreferencesKey("theme_mode")
    val CLIPBOARD_ENABLED = booleanPreferencesKey("clipboard_enabled")
    val CLIPBOARD_LIMIT = intPreferencesKey("clipboard_limit")
    val EMOJI_SPACED = booleanPreferencesKey("emoji_as_spaced_tokens")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_complete")
    val RECENT_EMOJI = stringPreferencesKey("recent_emoji")
}
