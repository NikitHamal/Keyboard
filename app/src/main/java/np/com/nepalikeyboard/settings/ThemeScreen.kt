package np.com.nepalikeyboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.data.PaletteId
import np.com.nepalikeyboard.data.SettingsSnapshot
import np.com.nepalikeyboard.data.ThemeMode
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.latinStyle
import np.com.nepalikeyboard.ui.theme.paletteAnchors
import np.com.nepalikeyboard.ui.theme.schemeFrom

/**
 * Theme customisation: Material You, the four curated fallback palettes, and the
 * one non-colour knob (key rounding).
 *
 * The palette swatches are generated from the very same derivation the keyboard
 * uses ([schemeFrom]), so what the swatch shows is exactly what the keys will
 * look like - no hand-maintained colour list that can drift.
 */
@Composable
fun ThemeScreen(
    snapshot: SettingsSnapshot,
    writer: SettingsWriter,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        SettingsSection(stringResource(R.string.setting_theme_mode)) {
            SettingChoiceRow(
                options = ThemeMode.entries.toList(),
                selected = snapshot.themeMode,
                labelOf = { mode ->
                    stringResource(
                        when (mode) {
                            ThemeMode.SYSTEM -> R.string.theme_system
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.DARK -> R.string.theme_dark
                            ThemeMode.AMOLED -> R.string.theme_amoled
                        },
                    )
                },
                onSelect = { mode -> writer.write { it.setThemeMode(mode) } },
            )
        }

        SettingsSection(stringResource(R.string.setting_dynamic_color)) {
            SettingSwitchRow(
                title = stringResource(R.string.setting_dynamic_color),
                description = stringResource(R.string.setting_dynamic_color_desc),
                checked = snapshot.dynamicColor,
                onCheckedChange = { value -> writer.write { it.setDynamicColor(value) } },
            )
        }

        SettingsSection(stringResource(R.string.setting_palette)) {
            SettingInfoRow(
                title = stringResource(R.string.setting_palette),
                value = "",
                description = stringResource(R.string.setting_palette_desc),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (palette in PaletteId.entries) {
                    PaletteSwatch(
                        palette = palette,
                        selected = !snapshot.dynamicColor && snapshot.palette == palette,
                        dark = snapshot.isDark,
                        onClick = { writer.write { it.setPalette(palette) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        SettingsSection(stringResource(R.string.setting_key_rounding)) {
            KeyRoundnessSlider(
                title = stringResource(R.string.setting_key_rounding),
                scale = snapshot.keyRoundnessScale,
                onCommit = { value -> writer.write { it.setKeyRoundnessScale(value) } },
            )
        }

        SettingsSection(stringResource(R.string.nav_theme)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(tokens.functionCornerRadius))
                    .background(tokens.keyboardBackground)
                    .padding(8.dp),
            ) {
                Column {
                    Text(
                        text = SAMPLE_LABEL,
                        style = latinStyle(14.sp, FontWeight.Medium),
                        color = tokens.keyLabel,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PreviewKey(background = tokens.keyBackground, label = "A", color = tokens.keyLabel)
                        PreviewKey(
                            background = tokens.functionKeyBackground,
                            label = "123",
                            color = tokens.functionKeyLabel,
                        )
                        PreviewKey(
                            background = tokens.accentKeyBackground,
                            label = "space",
                            color = tokens.accentKeyLabel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewKey(background: Color, label: String, color: Color) {
    val tokens = LocalKeyboardTokens.current
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(tokens.keyCornerRadius))
            .background(background)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = latinStyle(12.sp, FontWeight.Medium), color = color)
    }
}

@Composable
private fun PaletteSwatch(
    palette: PaletteId,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    val scheme = remember(palette, dark) { schemeFrom(paletteAnchors(palette), dark) }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(tokens.functionCornerRadius))
                .background(scheme.primaryContainer)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) scheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(tokens.functionCornerRadius),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = paletteLabel(palette),
                style = latinStyle(10.sp, FontWeight.SemiBold),
                color = scheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun paletteLabel(palette: PaletteId): String = stringResource(
    when (palette) {
        PaletteId.HIMALAYA -> R.string.palette_himalaya
        PaletteId.RHODODENDRON -> R.string.palette_rhododendron
        PaletteId.MUSTARD -> R.string.palette_mustard
        PaletteId.INDIGO -> R.string.palette_indigo
    },
)

private const val SAMPLE_LABEL = "\u0928\u092e\u0938\u094d\u0924\u0947"
