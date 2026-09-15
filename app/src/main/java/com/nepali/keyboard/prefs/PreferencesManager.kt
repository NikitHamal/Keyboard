package com.nepali.keyboard.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "keyboard_preferences")

enum class KeyboardTheme {
    DYNAMIC,
    LIGHT,
    DARK,
    AMOLED
}

enum class OneHandedMode {
    OFF,
    LEFT,
    RIGHT
}

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_HAPTIC = booleanPreferencesKey("haptic_feedback")
        val KEY_SOUND = booleanPreferencesKey("keypress_sound")
        val KEY_AUTO_CAPS = booleanPreferencesKey("auto_capitalization")
        val KEY_HEIGHT = intPreferencesKey("keyboard_height_dp")
        val KEY_THEME = stringPreferencesKey("keyboard_theme")
        val KEY_ONE_HANDED = stringPreferencesKey("one_handed_mode")
        val KEY_RECENT_EMOJIS = stringSetPreferencesKey("recent_emojis")
    }

    val hapticEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HAPTIC] ?: true
    }

    val soundEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SOUND] ?: true
    }

    val autoCapsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_CAPS] ?: true
    }

    val keyboardHeightDp: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_HEIGHT] ?: 280
    }

    val keyboardTheme: Flow<KeyboardTheme> = context.dataStore.data.map { prefs ->
        val themeStr = prefs[KEY_THEME] ?: KeyboardTheme.DYNAMIC.name
        runCatching { KeyboardTheme.valueOf(themeStr) }.getOrDefault(KeyboardTheme.DYNAMIC)
    }

    val oneHandedMode: Flow<OneHandedMode> = context.dataStore.data.map { prefs ->
        val modeStr = prefs[KEY_ONE_HANDED] ?: OneHandedMode.OFF.name
        runCatching { OneHandedMode.valueOf(modeStr) }.getOrDefault(OneHandedMode.OFF)
    }

    val recentEmojis: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_RECENT_EMOJIS]?.toList() ?: listOf("😊", "🙏", "🇳🇵", "❤️", "👍")
    }

    suspend fun setHapticEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_HAPTIC] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SOUND] = enabled }
    }

    suspend fun setAutoCapsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_CAPS] = enabled }
    }

    suspend fun setKeyboardHeight(heightDp: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_HEIGHT] = heightDp }
    }

    suspend fun setTheme(theme: KeyboardTheme) {
        context.dataStore.edit { prefs -> prefs[KEY_THEME] = theme.name }
    }

    suspend fun setOneHandedMode(mode: OneHandedMode) {
        context.dataStore.edit { prefs -> prefs[KEY_ONE_HANDED] = mode.name }
    }

    suspend fun addRecentEmoji(emoji: String) {
        context.dataStore.edit { prefs ->
            val set = prefs[KEY_RECENT_EMOJIS]?.toMutableSet() ?: mutableSetOf()
            set.remove(emoji)
            set.add(emoji)
            if (set.size > 30) {
                val list = set.toList()
                prefs[KEY_RECENT_EMOJIS] = list.subList(list.size - 30, list.size).toSet()
            } else {
                prefs[KEY_RECENT_EMOJIS] = set
            }
        }
    }
}
