package np.com.nepalikeyboard.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.data.SettingsSnapshot
import np.com.nepalikeyboard.ime.ImeRuntime
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.NepaliKeyboardTheme
import np.com.nepalikeyboard.ui.theme.latinStyle

/** The settings tabs, in the order they appear in the header strip. */
private enum class SettingsTab(val labelRes: Int) {
    SETUP(R.string.nav_setup),
    TYPING(R.string.nav_typing),
    LAYOUT(R.string.nav_layout),
    THEME(R.string.nav_theme),
    FEEDBACK(R.string.nav_feedback),
    DICTIONARY(R.string.nav_dictionary),
    PRIVACY(R.string.nav_privacy),
}

/**
 * The settings app: one Activity, one Compose tree.
 *
 * First launch shows the onboarding wizard; afterwards the tabbed shell. The
 * selected tab is a single saved integer rather than a navigation graph - there
 * are seven sibling screens with no nesting, no arguments and no deep links, so
 * a graph would add a dependency without adding behaviour.
 *
 * The Activity reads the same [ImeRuntime] singleton the IME service uses, which
 * is what makes the app live: writing a preference here changes what the next
 * keyboard snapshot contains, and learned words or clipboard entries written by
 * the keyboard show up here as soon as they are emitted.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = ImeRuntime.get(applicationContext)
        setContent {
            val snapshot by runtime.settings.snapshotState.collectAsStateWithLifecycle()
            NepaliKeyboardTheme(snapshot = snapshot) {
                if (snapshot.onboardingCompleted) {
                    SettingsShell(snapshot = snapshot)
                } else {
                    val writer = rememberSettingsWriter()
                    OnboardingScreen(
                        onFinished = { writer.setOnboardingCompleted(true) },
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsShell(snapshot: SettingsSnapshot) {
    val tokens = LocalKeyboardTokens.current
    val writer = rememberSettingsWriter()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { SettingsTab.entries.toList() }
    val current = tabs[selected.coerceIn(0, tabs.lastIndex)]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.keyboardBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = latinStyle(20.sp, FontWeight.SemiBold),
                color = tokens.suggestionText,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.settings_subtitle),
                style = latinStyle(12.sp),
                color = tokens.iconTint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            TabStrip(
                tabs = tabs,
                selectedIndex = selected,
                scrollState = rememberScrollState(),
                onSelect = { index -> selected = index },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (current) {
                SettingsTab.SETUP -> SetupScreen()
                SettingsTab.TYPING -> TypingScreen()
                SettingsTab.LAYOUT -> LayoutScreen(snapshot = snapshot, writer = writer)
                SettingsTab.THEME -> ThemeScreen(snapshot = snapshot, writer = writer)
                SettingsTab.FEEDBACK -> FeedbackScreen(snapshot = snapshot, writer = writer)
                SettingsTab.DICTIONARY -> DictionaryScreen(writer = writer)
                SettingsTab.PRIVACY -> PrivacyScreen(writer = writer)
            }
        }
    }
}

@Composable
private fun TabStrip(
    tabs: List<SettingsTab>,
    selectedIndex: Int,
    scrollState: ScrollState,
    onSelect: (Int) -> Unit,
) {
    val tokens = LocalKeyboardTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in tabs.indices) {
            val tab = tabs[index]
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(tokens.functionCornerRadius))
                    .background(if (selected) tokens.accentKeyBackground else tokens.panelSurface)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    style = latinStyle(12.sp, FontWeight.SemiBold),
                    color = if (selected) tokens.accentKeyLabel else tokens.suggestionText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Setup tab.
 *
 * The platform state is polled while this tab is on screen, because the user is
 * expected to leave for the system's keyboard screens and come straight back: a
 * one-shot read would report a state that is already stale by the time they
 * return. The rest of the tab is a read-out of what the engine actually loaded.
 */
@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val runtime = remember(context) { ImeRuntime.get(context) }
    val tokens = LocalKeyboardTokens.current
    val lexiconReady by runtime.lexiconReady.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf(AppCatalog.status(context)) }
    var dictionarySize by remember { mutableIntStateOf(0) }
    var learned by remember { mutableIntStateOf(0) }

    LaunchedEffect(lexiconReady) {
        if (lexiconReady) dictionarySize = runtime.lexicon?.size ?: 0
    }
    LaunchedEffect(Unit) {
        while (true) {
            status = AppCatalog.status(context)
            learned = runtime.learning.learnedWordsSnapshot(SETUP_LEARNED_PROBE).size
            delay(SETUP_POLL_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        SettingsSection(stringResource(R.string.nav_setup)) {
            StatusCard(
                title = stringResource(R.string.status_enabled),
                active = status.enabled,
                description = stringResource(R.string.onboarding_enable_body),
                tokens = tokens,
            )
            StatusCard(
                title = stringResource(R.string.status_selected),
                active = status.selected,
                description = stringResource(R.string.onboarding_select_body),
                tokens = tokens,
            )
            SettingActionRow(
                label = stringResource(R.string.onboarding_enable_action),
                onClick = { AppCatalog.openInputMethodSettings(context) },
                emphasised = true,
            )
            SettingActionRow(
                label = stringResource(R.string.onboarding_select_action),
                onClick = { AppCatalog.showInputMethodPicker(context) },
            )
        }

        SettingsSection(stringResource(R.string.settings_subtitle)) {
            SettingInfoRow(
                title = stringResource(R.string.sandbox_engine_ready),
                value = if (lexiconReady) {
                    dictionarySize.toString()
                } else {
                    stringResource(R.string.sandbox_engine_loading)
                },
                description = stringResource(R.string.dictionary_body),
            )
            SettingInfoRow(
                title = stringResource(R.string.dictionary_learned_header),
                value = learned.toString(),
                description = stringResource(R.string.privacy_no_retention),
            )
        }

        AboutScreen()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(
    title: String,
    active: Boolean,
    description: String,
    tokens: KeyboardTokens,
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingStatusDot(active = active, tokens = tokens)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = latinStyle(14.sp, FontWeight.Medium),
                    color = tokens.suggestionText,
                )
                Text(
                    text = description,
                    style = latinStyle(11.sp),
                    color = tokens.iconTint,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(if (active) R.string.status_enabled else R.string.status_disabled),
                style = latinStyle(11.sp, FontWeight.SemiBold),
                color = if (active) tokens.iconTintActive else tokens.iconTint,
                maxLines = 1,
            )
        }
    }
}

private const val SETUP_POLL_MS = 800L
private const val SETUP_LEARNED_PROBE = 64
