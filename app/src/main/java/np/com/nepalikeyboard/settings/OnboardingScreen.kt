package np.com.nepalikeyboard.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.devanagariStyle
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * First-run wizard.
 *
 * Android deliberately provides no API to enable or select an input method, so
 * the wizard cannot do it for the user - it deep-links into the two system
 * screens that can, and polls the platform state to tick each step off as it
 * happens. The polling stops as soon as the wizard leaves composition, and the
 * whole flow can be skipped: the keyboard still works the moment it is selected,
 * with no setup required beyond the system's own.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tokens = LocalKeyboardTokens.current
    var step by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(ImeStatus()) }

    LaunchedEffect(step) {
        status = AppCatalog.status(context)
        if (step != 1 && step != 2) return@LaunchedEffect
        // The user is in another app's settings screen, so there is nothing to
        // observe - only to poll. A slow tick keeps the wizard honest without
        // waking the CPU up for nothing.
        while (true) {
            delay(POLL_INTERVAL_MS)
            status = AppCatalog.status(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.onboarding_step_of, step + 1, STEP_COUNT),
                style = latinStyle(11.sp, FontWeight.SemiBold),
                color = tokens.iconTint,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.onboarding_skip),
                style = latinStyle(12.sp, FontWeight.SemiBold),
                color = tokens.iconTintActive,
                modifier = Modifier
                    .clickable(onClick = onFinished)
                    .padding(8.dp),
                maxLines = 1,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = when (step) {
                    0 -> stringResource(R.string.onboarding_welcome_title)
                    1 -> stringResource(R.string.onboarding_enable_title)
                    2 -> stringResource(R.string.onboarding_select_title)
                    else -> stringResource(R.string.onboarding_done_title)
                },
                style = latinStyle(22.sp, FontWeight.SemiBold),
                color = tokens.suggestionText,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (step) {
                    0 -> stringResource(R.string.onboarding_welcome_body)
                    1 -> stringResource(R.string.onboarding_enable_body)
                    2 -> stringResource(R.string.onboarding_select_body)
                    else -> stringResource(R.string.onboarding_done_body)
                },
                style = latinStyle(14.sp),
                color = tokens.iconTint,
            )

            if (step >= 1) {
                Spacer(Modifier.height(16.dp))
                StatusRow(
                    label = stringResource(
                        if (status.enabled) R.string.status_enabled else R.string.status_disabled,
                    ),
                    active = status.enabled,
                    tokens = tokens,
                )
                Spacer(Modifier.height(6.dp))
                StatusRow(
                    label = stringResource(
                        if (status.selected) R.string.status_selected else R.string.status_not_selected,
                    ),
                    active = status.selected,
                    tokens = tokens,
                )
            }

            if (step == 3) {
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = SAMPLE_DEVANAGARI,
                        style = devanagariStyle(24.sp, FontWeight.Medium).copy(color = tokens.iconTintActive),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (step > 0 && step < STEP_COUNT - 1) {
                SettingActionRow(
                    label = stringResource(R.string.onboarding_back),
                    onClick = { step -= 1 },
                    modifier = Modifier.weight(1f),
                )
            }
            when (step) {
                0 -> SettingActionRow(
                    label = stringResource(R.string.onboarding_next),
                    onClick = { step = 1 },
                    modifier = Modifier.weight(1f),
                    emphasised = true,
                )

                1 -> SettingActionRow(
                    label = stringResource(R.string.onboarding_enable_action),
                    onClick = { AppCatalog.openInputMethodSettings(context) },
                    modifier = Modifier.weight(1f),
                    emphasised = true,
                )

                2 -> SettingActionRow(
                    label = stringResource(R.string.onboarding_select_action),
                    onClick = { AppCatalog.showInputMethodPicker(context) },
                    modifier = Modifier.weight(1f),
                    emphasised = true,
                )

                else -> SettingActionRow(
                    label = stringResource(R.string.onboarding_finish),
                    onClick = onFinished,
                    modifier = Modifier.weight(1f),
                    emphasised = true,
                )
            }
        }
        if (step == 1 || step == 2) {
            Spacer(Modifier.height(6.dp))
            SettingActionRow(
                label = stringResource(R.string.onboarding_next),
                onClick = { step += 1 },
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, active: Boolean, tokens: KeyboardTokens) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingStatusDot(active = active, tokens = tokens)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = latinStyle(13.sp, FontWeight.Medium),
            color = if (active) tokens.suggestionText else tokens.iconTint,
        )
    }
}

private const val STEP_COUNT = 4
private const val POLL_INTERVAL_MS = 700L
private const val SAMPLE_DEVANAGARI = "\u0928\u092e\u0938\u094d\u0924\u0947 \u0928\u0947\u092a\u093e\u0932"
