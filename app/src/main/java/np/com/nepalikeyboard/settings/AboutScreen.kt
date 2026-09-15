package np.com.nepalikeyboard.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.com.nepalikeyboard.BuildConfig
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.devanagariStyle
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * About: version, build provenance, and the one-paragraph explanation of what
 * this keyboard is (offline, on-device, no telemetry).
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val tokens = LocalKeyboardTokens.current
    // Section, not screen: this is embedded at the bottom of the Setup tab, so
    // it must not create a second scroll container.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        SettingsSection(stringResource(R.string.app_name)) {
            SettingInfoRow(
                title = stringResource(R.string.app_name),
                value = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                description = stringResource(R.string.about_build, BuildConfig.BUILD_TYPE),
            )
            SettingInfoRow(
                title = stringResource(R.string.settings_subtitle),
                value = if (BuildConfig.DEBUG) "debug" else "release",
            )
            SettingInfoRow(
                title = stringResource(R.string.ime_service_label),
                value = BuildConfig.APPLICATION_ID,
            )
        }

        SettingsSection(stringResource(R.string.language_nepali)) {
            Text(
                text = "\u0928\u092e\u0938\u094d\u0924\u0947",
                style = devanagariStyle(28.sp, FontWeight.SemiBold).copy(color = tokens.iconTintActive),
            )
            Text(
                text = stringResource(R.string.settings_subtitle),
                style = latinStyle(12.sp),
                color = tokens.iconTint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}
