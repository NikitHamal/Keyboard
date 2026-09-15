package np.com.nepalikeyboard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nepali_keyboard_settings",
)

/**
 * Preferences owner for the whole app.
 *
 * Two access paths exist on purpose:
 *  - [snapshotFlow] / [snapshotState] for Compose UI (lifecycle aware).
 *  - [current] for the IME keystroke path: a plain `@Volatile` snapshot that can
 *    be read synchronously without touching a coroutine or DataStore.
 */
class SettingsRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    @Volatile
    var current: SettingsSnapshot = SettingsSnapshot.Default
        private set

    val snapshotState: StateFlow<SettingsSnapshot> = settingsDataStore.data
        .catch { throwable ->
            // A corrupt preferences file must never brick the keyboard: fall back
            // to defaults and keep emitting so the UI stays functional.
            if (throwable is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw throwable
        }
        .map { prefs -> read(prefs) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsSnapshot.Default,
        )

    val snapshotFlow: Flow<SettingsSnapshot> get() = snapshotState

    init {
        // Keep the volatile mirror hot for the IME fast path.
        scope.launch {
            snapshotState.collect { value -> current = value }
        }
    }

    // -----------------------------------------------------------------------
    // Writers. Each is a suspend function so callers control the dispatcher.
    // -----------------------------------------------------------------------

    suspend fun setThemeMode(value: ThemeMode) = write(Keys.THEME_MODE, value.name)

    suspend fun setDynamicColor(value: Boolean) = write(Keys.DYNAMIC_COLOR, value)

    suspend fun setPalette(value: PaletteId) = write(Keys.PALETTE, value.name)

    suspend fun setKeyboardHeightScale(value: Float) =
        write(Keys.HEIGHT_SCALE, value.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE))

    suspend fun setKeyRoundnessScale(value: Float) =
        write(Keys.ROUNDNESS_SCALE, value.coerceIn(MIN_ROUNDNESS, MAX_ROUNDNESS))

    suspend fun setDefaultLayout(value: LayoutPreference) = write(Keys.DEFAULT_LAYOUT, value.name)

    suspend fun setNumberRow(value: Boolean) = write(Keys.NUMBER_ROW, value)

    suspend fun setOneHanded(value: OneHandedSide) = write(Keys.ONE_HANDED, value.name)

    suspend fun setOneHandedWidthFraction(value: Float) =
        write(Keys.ONE_HANDED_WIDTH, value.coerceIn(0.6f, 0.92f))

    suspend fun setShowSuggestions(value: Boolean) = write(Keys.SHOW_SUGGESTIONS, value)

    suspend fun setShowToolbar(value: Boolean) = write(Keys.SHOW_TOOLBAR, value)

    suspend fun setAutoCapitalize(value: Boolean) = write(Keys.AUTO_CAPITALIZE, value)

    suspend fun setAutoCorrect(value: Boolean) = write(Keys.AUTO_CORRECT, value)

    suspend fun setDevanagariNumerals(value: Boolean) = write(Keys.DEVANAGARI_NUMERALS, value)

    suspend fun setHapticsEnabled(value: Boolean) = write(Keys.HAPTICS_ENABLED, value)

    suspend fun setHapticStrength(value: FeedbackStrength) = write(Keys.HAPTIC_STRENGTH, value.name)

    suspend fun setSoundEnabled(value: Boolean) = write(Keys.SOUND_ENABLED, value)

    suspend fun setCursorDragEnabled(value: Boolean) = write(Keys.CURSOR_DRAG, value)

    suspend fun setSwipeDeleteEnabled(value: Boolean) = write(Keys.SWIPE_DELETE, value)

    suspend fun setCursorGlideSpeed(value: Float) =
        write(Keys.CURSOR_GLIDE_SPEED, value.coerceIn(0.4f, 2.5f))

    suspend fun setLongPressSymbols(value: Boolean) = write(Keys.LONG_PRESS_SYMBOLS, value)

    suspend fun setClipboardEnabled(value: Boolean) = write(Keys.CLIPBOARD_ENABLED, value)

    suspend fun setOnboardingCompleted(value: Boolean) = write(Keys.ONBOARDING_DONE, value)

    /** Restores every preference to its compiled-in default. */
    suspend fun resetToDefaults() {
        settingsDataStore.edit { prefs ->
            prefs.clear()
            prefs[Keys.SCHEMA_VERSION] = SettingsSnapshot.SETTINGS_SCHEMA_VERSION
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private suspend fun <T> write(key: Preferences.Key<T>, value: T) {
        settingsDataStore.edit { prefs -> prefs[key] = value }
    }

    private fun read(prefs: Preferences): SettingsSnapshot {
        val defaults = SettingsSnapshot.Default
        val version = prefs[Keys.SCHEMA_VERSION] ?: SettingsSnapshot.SETTINGS_SCHEMA_VERSION
        return SettingsSnapshot(
            themeMode = prefs[Keys.THEME_MODE].toEnumOr(defaults.themeMode),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            palette = prefs[Keys.PALETTE].toEnumOr(defaults.palette),
            keyboardHeightScale = (prefs[Keys.HEIGHT_SCALE] ?: defaults.keyboardHeightScale)
                .coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE),
            keyRoundnessScale = (prefs[Keys.ROUNDNESS_SCALE] ?: defaults.keyRoundnessScale)
                .coerceIn(MIN_ROUNDNESS, MAX_ROUNDNESS),
            defaultLayout = prefs[Keys.DEFAULT_LAYOUT].toEnumOr(defaults.defaultLayout),
            numberRow = prefs[Keys.NUMBER_ROW] ?: defaults.numberRow,
            oneHanded = prefs[Keys.ONE_HANDED].toEnumOr(defaults.oneHanded),
            oneHandedWidthFraction = (prefs[Keys.ONE_HANDED_WIDTH] ?: defaults.oneHandedWidthFraction)
                .coerceIn(0.6f, 0.92f),
            showSuggestions = prefs[Keys.SHOW_SUGGESTIONS] ?: defaults.showSuggestions,
            showToolbar = prefs[Keys.SHOW_TOOLBAR] ?: defaults.showToolbar,
            autoCapitalize = prefs[Keys.AUTO_CAPITALIZE] ?: defaults.autoCapitalize,
            autoCorrect = prefs[Keys.AUTO_CORRECT] ?: defaults.autoCorrect,
            devanagariNumerals = prefs[Keys.DEVANAGARI_NUMERALS] ?: defaults.devanagariNumerals,
            hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: defaults.hapticsEnabled,
            hapticStrength = prefs[Keys.HAPTIC_STRENGTH].toEnumOr(defaults.hapticStrength),
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: defaults.soundEnabled,
            cursorDragEnabled = prefs[Keys.CURSOR_DRAG] ?: defaults.cursorDragEnabled,
            swipeDeleteEnabled = prefs[Keys.SWIPE_DELETE] ?: defaults.swipeDeleteEnabled,
            cursorGlideSpeed = (prefs[Keys.CURSOR_GLIDE_SPEED] ?: defaults.cursorGlideSpeed)
                .coerceIn(0.4f, 2.5f),
            longPressSymbols = prefs[Keys.LONG_PRESS_SYMBOLS] ?: defaults.longPressSymbols,
            clipboardEnabled = prefs[Keys.CLIPBOARD_ENABLED] ?: defaults.clipboardEnabled,
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: defaults.onboardingCompleted,
            schemaVersion = version,
        )
    }

    object Keys {
        val SCHEMA_VERSION = intPreferencesKey("schema_version")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val PALETTE = stringPreferencesKey("palette")
        val HEIGHT_SCALE = floatPreferencesKey("height_scale")
        val ROUNDNESS_SCALE = floatPreferencesKey("roundness_scale")
        val DEFAULT_LAYOUT = stringPreferencesKey("default_layout")
        val NUMBER_ROW = booleanPreferencesKey("number_row")
        val ONE_HANDED = stringPreferencesKey("one_handed")
        val ONE_HANDED_WIDTH = floatPreferencesKey("one_handed_width")
        val SHOW_SUGGESTIONS = booleanPreferencesKey("show_suggestions")
        val SHOW_TOOLBAR = booleanPreferencesKey("show_toolbar")
        val AUTO_CAPITALIZE = booleanPreferencesKey("auto_capitalize")
        val AUTO_CORRECT = booleanPreferencesKey("auto_correct")
        val DEVANAGARI_NUMERALS = booleanPreferencesKey("devanagari_numerals")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val HAPTIC_STRENGTH = stringPreferencesKey("haptic_strength")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val CURSOR_DRAG = booleanPreferencesKey("cursor_drag")
        val SWIPE_DELETE = booleanPreferencesKey("swipe_delete")
        val CURSOR_GLIDE_SPEED = floatPreferencesKey("cursor_glide_speed")
        val LONG_PRESS_SYMBOLS = booleanPreferencesKey("long_press_symbols")
        val CLIPBOARD_ENABLED = booleanPreferencesKey("clipboard_enabled")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    companion object {
        const val MIN_HEIGHT_SCALE = 0.70f
        const val MAX_HEIGHT_SCALE = 1.30f
        const val MIN_ROUNDNESS = 0.5f
        const val MAX_ROUNDNESS = 1.6f

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
