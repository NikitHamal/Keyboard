package np.com.nepalikeyboard.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import np.com.nepalikeyboard.ui.theme.devanagariStyle
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
    val context = LocalContext.current
    val runtime = remember(context) { ImeRuntime.get(context) }
    LaunchedEffect(Unit) { runtime.ensureTlm() }
    val tlmReady by runtime.tlmReady.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { SettingsTab.entries.toList() }
    val current = tabs[selected.coerceIn(0, tabs.lastIndex)]
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.keyboardBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeroHeader(tlmReady = tlmReady)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                        (fadeIn(tween(180)) + slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { dir * it / 8 })
                            .togetherWith(fadeOut(tween(100)))
                    },
                    label = "settingsTab",
                ) { tab ->
                    Box(modifier = Modifier.fillMaxSize().padding(bottom = 84.dp)) {
                        when (tab) {
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
        }
        FloatingNav(
            tabs = tabs,
            selectedIndex = selected,
            onSelect = { index -> selected = index },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
        )
    }
}

/** Expressive hero: tonal container with oversized corners, the product name and live engine status. */
@Composable
private fun HeroHeader(tlmReady: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalKeyboardTokens.current
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
            .background(scheme.primaryContainer)
            .statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 20.dp),
    ) {
        Text(
            text = "\u0928\u0947\u092A\u093E\u0932\u0940 \u0915\u0940\u092C\u094B\u0930\u094D\u0921",
            style = devanagariStyle(30.sp, FontWeight.Bold),
            color = scheme.onPrimaryContainer,
        )
        Text(
            text = stringResource(R.string.settings_title),
            style = latinStyle(15.sp, FontWeight.SemiBold),
            color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(scheme.primary.copy(alpha = 0.14f))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (tlmReady) scheme.primary else scheme.tertiary.copy(alpha = alpha)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (tlmReady) {
                    stringResource(R.string.tinylm_ready)
                } else {
                    stringResource(R.string.tinylm_loading)
                },
                style = latinStyle(12.sp, FontWeight.SemiBold),
                color = scheme.onPrimaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.settings_subtitle),
            style = latinStyle(12.sp),
            color = tokens.iconTint,
            modifier = Modifier.padding(top = 10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun SettingsTab.icon(): ImageVector = when (this) {
    SettingsTab.SETUP -> Icons.Filled.Keyboard
    SettingsTab.TYPING -> Icons.Filled.Edit
    SettingsTab.LAYOUT -> Icons.Filled.Dashboard
    SettingsTab.THEME -> Icons.Filled.Palette
    SettingsTab.FEEDBACK -> Icons.Filled.Vibration
    SettingsTab.DICTIONARY -> Icons.Filled.Book
    SettingsTab.PRIVACY -> Icons.Filled.Lock
}

/** M3E floating toolbar: the selected destination expands into an icon + label pill with a spring. */
@Composable
private fun FloatingNav(
    tabs: List<SettingsTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .shadow(10.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(scheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .animateContentSize(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in tabs.indices) {
            val tab = tabs[index]
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                targetValue = if (selected) scheme.primary else Color.Transparent,
                animationSpec = tween(200),
                label = "navBg",
            )
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(index) }
                    .padding(horizontal = if (selected) 14.dp else 10.dp)
                    .animateContentSize(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = tab.icon(),
                    contentDescription = stringResource(tab.labelRes),
                    tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(tab.labelRes),
                        style = latinStyle(13.sp, FontWeight.SemiBold),
                        color = scheme.onPrimary,
                        maxLines = 1,
                    )
                }
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
