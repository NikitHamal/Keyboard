package np.com.nepalikeyboard.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.data.LayoutPreference
import np.com.nepalikeyboard.data.OneHandedSide
import np.com.nepalikeyboard.data.SettingsSnapshot

/**
 * Layout and behaviour customisation.
 *
 * Every row writes straight to the shared settings repository, which the IME
 * observes: flip a switch here, focus any text field, and the keyboard is
 * already behaving that way.
 */
@Composable
fun LayoutScreen(
    snapshot: SettingsSnapshot,
    writer: SettingsWriter,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        SettingsSection(stringResource(R.string.setting_default_layout)) {
            SettingChoiceRow(
                options = LayoutPreference.entries.toList(),
                selected = snapshot.defaultLayout,
                labelOf = { preference -> stringResource(preferenceLabel(preference)) },
                onSelect = { preference -> writer.write { it.setDefaultLayout(preference) } },
                devanagari = { preference -> preference == LayoutPreference.NATIVE },
            )
            SettingInfoRow(
                title = stringResource(R.string.setting_default_layout),
                value = stringResource(
                    when (snapshot.defaultLayout) {
                        LayoutPreference.ROMAN -> R.string.language_roman
                        LayoutPreference.NATIVE -> R.string.language_nepali
                        LayoutPreference.ENGLISH -> R.string.language_english
                    },
                ),
                description = stringResource(R.string.setting_default_layout_desc),
            )
        }

        SettingsSection(stringResource(R.string.nav_layout)) {
            SettingSwitchRow(
                title = stringResource(R.string.setting_number_row),
                description = stringResource(R.string.setting_number_row_desc),
                checked = snapshot.numberRow,
                onCheckedChange = { value -> writer.write { it.setNumberRow(value) } },
            )
            SettingSwitchRow(
                title = stringResource(R.string.setting_devanagari_numerals),
                description = stringResource(R.string.setting_devanagari_numerals_desc),
                checked = snapshot.devanagariNumerals,
                onCheckedChange = { value -> writer.write { it.setDevanagariNumerals(value) } },
            )
            SettingSwitchRow(
                title = stringResource(R.string.setting_show_toolbar),
                description = stringResource(R.string.setting_show_toolbar_desc),
                checked = snapshot.showToolbar,
                onCheckedChange = { value -> writer.write { it.setShowToolbar(value) } },
            )
        }

        SettingsSection(stringResource(R.string.setting_keyboard_height)) {
            KeyboardHeightSlider(
                title = stringResource(R.string.setting_keyboard_height),
                description = stringResource(R.string.setting_keyboard_height_desc),
                scale = snapshot.keyboardHeightScale,
                onCommit = { scale -> writer.write { it.setKeyboardHeightScale(scale) } },
            )
        }

        SettingsSection(stringResource(R.string.setting_one_handed)) {
            SettingChoiceRow(
                options = OneHandedSide.entries.toList(),
                selected = snapshot.oneHanded,
                labelOf = { side ->
                    stringResource(
                        when (side) {
                            OneHandedSide.OFF -> R.string.one_handed_off
                            OneHandedSide.LEFT -> R.string.one_handed_left
                            OneHandedSide.RIGHT -> R.string.one_handed_right
                        },
                    )
                },
                onSelect = { side -> writer.write { it.setOneHanded(side) } },
            )
            OneHandedWidthSlider(
                title = stringResource(R.string.setting_one_handed),
                description = stringResource(R.string.setting_one_handed_desc),
                fraction = snapshot.oneHandedWidthFraction,
                onCommit = { value -> writer.write { it.setOneHandedWidthFraction(value) } },
            )
        }

        SettingsSection(stringResource(R.string.setting_show_gestures_header)) {
            SettingSwitchRow(
                title = stringResource(R.string.setting_cursor_drag),
                description = stringResource(R.string.setting_cursor_drag_desc),
                checked = snapshot.cursorDragEnabled,
                onCheckedChange = { value -> writer.write { it.setCursorDragEnabled(value) } },
            )
            SettingSwitchRow(
                title = stringResource(R.string.setting_swipe_delete),
                description = stringResource(R.string.setting_swipe_delete_desc),
                checked = snapshot.swipeDeleteEnabled,
                onCheckedChange = { value -> writer.write { it.setSwipeDeleteEnabled(value) } },
            )
            SettingSwitchRow(
                title = stringResource(R.string.setting_long_press_symbols),
                description = stringResource(R.string.setting_long_press_symbols_desc),
                checked = snapshot.longPressSymbols,
                onCheckedChange = { value -> writer.write { it.setLongPressSymbols(value) } },
            )
            CursorSensitivitySlider(
                title = stringResource(R.string.setting_cursor_sensitivity),
                speed = snapshot.cursorGlideSpeed,
                onCommit = { value -> writer.write { it.setCursorGlideSpeed(value) } },
            )
        }

        SettingsSection(stringResource(R.string.setting_suggestions)) {
            SettingSwitchRow(
                title = stringResource(R.string.setting_suggestions),
                description = stringResource(R.string.setting_suggestions_desc),
                checked = snapshot.showSuggestions,
                onCheckedChange = { value -> writer.write { it.setShowSuggestions(value) } },
            )
            SettingSwitchRow(
                title = stringResource(R.string.setting_auto_capitalization),
                description = stringResource(R.string.setting_auto_capitalization_desc),
                checked = snapshot.autoCapitalize,
                onCheckedChange = { value -> writer.write { it.setAutoCapitalize(value) } },
            )
            SettingSwitchRow(
                title = stringResource(R.string.setting_auto_correct),
                description = stringResource(R.string.setting_auto_correct_desc),
                checked = snapshot.autoCorrect,
                onCheckedChange = { value -> writer.write { it.setAutoCorrect(value) } },
            )
            SettingSwitchRow(
                title = stringResource(R.string.clipboard_header),
                description = stringResource(R.string.clipboard_secure_notice),
                checked = snapshot.clipboardEnabled,
                onCheckedChange = { value -> writer.write { it.setClipboardEnabled(value) } },
            )
        }
    }
}

private fun preferenceLabel(preference: LayoutPreference): Int = when (preference) {
    LayoutPreference.ROMAN -> R.string.language_roman
    LayoutPreference.NATIVE -> R.string.language_nepali
    LayoutPreference.ENGLISH -> R.string.language_english
}
