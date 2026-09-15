package np.com.nepalikeyboard.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.ime.ImeRuntime
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * Privacy statement and the single destructive action in the app.
 *
 * The claims here are structural, not promises: the manifest declares no
 * permission at all, the dictionaries and statistical models are compiled into
 * the APK, and the IME's own code paths for sensitive fields never call the
 * learning or suggestion machinery.
 */
@Composable
fun PrivacyScreen(
    writer: SettingsWriter,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val runtime = remember(context) { ImeRuntime.get(context) }
    val tokens = LocalKeyboardTokens.current
    var erased by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        SettingsSection(stringResource(R.string.privacy_title)) {
            Statement(stringResource(R.string.privacy_no_internet))
            Statement(stringResource(R.string.privacy_on_device))
            Statement(stringResource(R.string.privacy_no_retention))
            Statement(stringResource(R.string.privacy_secure_fields))
        }

        SettingsSection(stringResource(R.string.privacy_reset_title)) {
            SettingInfoRow(
                title = stringResource(R.string.privacy_reset_title),
                value = "",
                description = stringResource(R.string.privacy_reset_body),
            )
            SettingActionRow(
                label = stringResource(R.string.privacy_reset_action),
                onClick = {
                    runtime.learning.clearLearning()
                    runtime.clipboard.clearAll()
                    runtime.emoji.clear()
                    writer.resetAll()
                    erased = true
                },
                emphasised = true,
            )
            if (erased) {
                Text(
                    text = stringResource(R.string.privacy_reset_done),
                    style = latinStyle(12.sp, FontWeight.SemiBold),
                    color = tokens.iconTintActive,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        SettingsSection(stringResource(R.string.onboarding_welcome_title)) {
            SettingActionRow(
                label = stringResource(R.string.nav_setup),
                onClick = { writer.setOnboardingCompleted(false) },
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Statement(text: String) {
    SettingInfoRow(title = text, value = "")
}
