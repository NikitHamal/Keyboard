package np.com.nepalikeyboard.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import np.com.nepalikeyboard.data.SettingsSnapshot
import np.com.nepalikeyboard.ime.ImeRuntime
import np.com.nepalikeyboard.ui.theme.ExpressiveMotion
import np.com.nepalikeyboard.ui.theme.NepaliKeyboardTheme
import np.com.nepalikeyboard.ui.theme.devanagariStyle
import np.com.nepalikeyboard.ui.theme.displayStyle
import np.com.nepalikeyboard.ui.theme.latinStyle
import np.com.nepalikeyboard.util.CrashLog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    TYPING("Typing", Icons.Rounded.Keyboard),
    LOOK("Look", Icons.Rounded.Palette),
    WORDS("Words", Icons.Rounded.Book),
    MORE("More", Icons.Rounded.MoreHoriz),
}

/**
 * The settings app: one Activity, one Compose tree, Material 3 Expressive styling.
 *
 * The Home tab is the setup guide *and* the dashboard. It reads the platform's input-method state through
 * [rememberImeStatus] (lifecycle-resume + ContentObserver + slow poll) so the steps flip to "done" the moment the
 * user comes back from the system keyboard screens.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = ImeRuntime.get(applicationContext)
        setContent {
            val snapshot by runtime.settings.snapshotState.collectAsStateWithLifecycle()
            NepaliKeyboardTheme(snapshot = snapshot) {
                SettingsShell(snapshot = snapshot)
            }
        }
    }
}

@Composable
private fun SettingsShell(snapshot: SettingsSnapshot) {
    val writer = rememberSettingsWriter()
    val scheme = MaterialTheme.colorScheme
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { Tab.entries.toList() }
    val current = tabs[selected.coerceIn(0, tabs.lastIndex)]

    Scaffold(
        containerColor = scheme.surface,
        bottomBar = {
            NavigationBar(containerColor = scheme.surfaceContainer) {
                for ((index, tab) in tabs.withIndex()) {
                    NavigationBarItem(
                        selected = index == selected,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, style = latinStyle(12.sp, FontWeight.Medium)) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = current,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = ExpressiveMotion.springy()))
                    .togetherWith(fadeOut(tween(90)) + scaleOut(targetScale = 0.98f, animationSpec = tween(90)))
            },
            label = "tab",
        ) { tab ->
            when (tab) {
                Tab.HOME -> HomeScreen(writer = writer)
                Tab.TYPING -> Page("Typing", "Suggestions, behaviour and gestures") { TypingScreen() }
                Tab.LOOK -> LookScreen(snapshot, writer)
                Tab.WORDS -> Page("Words", "Your personal dictionary and learning") { DictionaryScreen(writer = writer) }
                Tab.MORE -> MoreScreen(snapshot, writer)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Shared page chrome
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun Page(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title, subtitle)
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 10.dp)) {
        Text(text = title, style = displayStyle(36.sp, FontWeight.Medium), color = scheme.onSurface)
        Text(text = subtitle, style = latinStyle(14.sp), color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun PillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((index, label) in labels.withIndex()) {
            val on = index == selected
            val bg by animateColorAsState(if (on) scheme.secondaryContainer else scheme.surfaceContainerHigh, tween(180), label = "pillBg")
            val fg by animateColorAsState(if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant, tween(180), label = "pillFg")
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(text = label, style = latinStyle(13.5.sp, FontWeight.SemiBold), color = fg, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LookScreen(snapshot: SettingsSnapshot, writer: SettingsWriter) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Page("Look", "Colours, shapes and layout") {
        Column(modifier = Modifier.fillMaxSize()) {
            PillTabs(listOf("Theme", "Layout"), sub) { sub = it }
            Box(modifier = Modifier.weight(1f)) {
                if (sub == 0) ThemeScreen(snapshot = snapshot, writer = writer) else LayoutScreen(snapshot = snapshot, writer = writer)
            }
        }
    }
}

@Composable
private fun MoreScreen(snapshot: SettingsSnapshot, writer: SettingsWriter) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Page("More", "Feedback, privacy and diagnostics") {
        Column(modifier = Modifier.fillMaxSize()) {
            PillTabs(listOf("Feedback", "Privacy", "About", "Diagnostics"), sub) { sub = it }
            Box(modifier = Modifier.weight(1f)) {
                when (sub) {
                    0 -> FeedbackScreen(snapshot = snapshot, writer = writer)
                    1 -> PrivacyScreen(writer = writer)
                    2 -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) { AboutScreen() }
                    else -> DiagnosticsScreen()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Home: guided setup + live engine dashboard
// ---------------------------------------------------------------------------------------------------------------

/** A softly lobed circle - the "cookie" shape from the Material 3 Expressive shape library. */
private class CookieShape(private val lobes: Int = 9, private val depth: Float = 0.07f) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) / 2f / (1f + depth)
        val steps = 160
        for (i in 0..steps) {
            val t = i.toFloat() / steps * 2f * PI.toFloat()
            val rr = r * (1f + depth * cos(lobes * t))
            val x = cx + rr * cos(t)
            val y = cy + rr * sin(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

private enum class StepState { DONE, CURRENT, LOCKED }

@Composable
private fun HomeScreen(writer: SettingsWriter) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val runtime = remember(context) { ImeRuntime.get(context) }
    val status by rememberImeStatus()
    val tlmReady by runtime.tlmReady.collectAsStateWithLifecycle()
    val lexReady by runtime.lexiconReady.collectAsStateWithLifecycle()
    var learned by remember { mutableIntStateOf(0) }
    var tryText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runtime.ensureLexicon()
        runtime.ensureTlm()
        while (true) {
            learned = try {
                runtime.learning.learnedWordsSnapshot(500).size
            } catch (_: Throwable) {
                0
            }
            delay(2000)
        }
    }
    LaunchedEffect(status.ready) { if (status.ready) writer.setOnboardingCompleted(true) }

    val enableState = if (status.enabled) StepState.DONE else StepState.CURRENT
    val selectState = when {
        status.selected -> StepState.DONE
        status.enabled -> StepState.CURRENT
        else -> StepState.LOCKED
    }
    val typeState = if (status.ready) StepState.CURRENT else StepState.LOCKED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Hero(ready = status.ready, enabled = status.enabled, onEnable = { AppCatalog.openInputMethodSettings(context) }, onSelect = { AppCatalog.showInputMethodPicker(context) })

        SettingsSection("Set up") {
            StepCard(1, "Turn on Nepali Keyboard", "Enable it in Settings → Languages & input → On-screen keyboard.", enableState, if (status.enabled) null else "Open settings") {
                AppCatalog.openInputMethodSettings(context)
            }
            StepCard(2, "Switch to it", "Pick Nepali Keyboard as your active keyboard. Tap the keyboard icon at the corner of the screen any time to switch back.", selectState, if (status.selected) null else "Choose keyboard") {
                AppCatalog.showInputMethodPicker(context)
            }
            StepCard(3, "Start typing", "Type in Roman letters - namaste becomes नमस्ते. Pick from the suggestion chips or just press space.", typeState, null) {}
        }

        SettingsSection("Try it") {
            TextField(
                value = tryText,
                onValueChange = { tryText = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                placeholder = { Text("Tap here and type: mero naam ram ho", style = latinStyle(14.sp)) },
                textStyle = devanagariStyle(18.sp, FontWeight.Medium),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    focusedContainerColor = scheme.surfaceContainerHigh,
                    unfocusedContainerColor = scheme.surfaceContainer,
                ),
                minLines = 2,
            )
        }

        SettingsSection("Engine") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("TinyLM", if (tlmReady) "Ready" else "Loading", tlmReady, Modifier.weight(1f))
                StatTile("Dictionary", if (lexReady) "${(runtime.lexicon?.size ?: 0)}" else "…", lexReady, Modifier.weight(1f))
                StatTile("Learned", "$learned", true, Modifier.weight(1f))
            }
            SettingsCard {
                Column {
                    Text("On-device, offline, private", style = latinStyle(15.5.sp, FontWeight.Medium), color = scheme.onSurface)
                    Text(
                        "A two-stage tiny language model (character transducer + sentence-context scorer) runs on your phone - 2.5 M parameters, no network permission, nothing leaves the keyboard.",
                        style = latinStyle(12.5.sp),
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Hero(ready: Boolean, enabled: Boolean, onEnable: () -> Unit, onSelect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val headline = when {
        ready -> "You're all set"
        enabled -> "One more tap"
        else -> "Let's get you typing"
    }
    val body = when {
        ready -> "Nepali Keyboard is active. Open any app and start typing in Roman letters."
        enabled -> "Choose Nepali Keyboard as your input method to finish."
        else -> "Enable the keyboard in system settings - it takes ten seconds."
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(40.dp))
            .background(scheme.primaryContainer),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 56.dp, y = (-48).dp)
                .size(190.dp)
                .graphicsLayer { rotationZ = angle }
                .clip(CookieShape(11, 0.08f))
                .background(scheme.tertiaryContainer.copy(alpha = 0.75f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-40).dp, y = 44.dp)
                .size(120.dp)
                .graphicsLayer { rotationZ = -angle }
                .clip(CookieShape(7, 0.1f))
                .background(scheme.secondaryContainer.copy(alpha = 0.6f)),
        )
        Column(modifier = Modifier.padding(26.dp)) {
            Text(text = "नमस्ते", style = devanagariStyle(46.sp, FontWeight.SemiBold), color = scheme.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(text = headline, style = displayStyle(26.sp, FontWeight.Medium), color = scheme.onPrimaryContainer)
            Text(text = body, style = latinStyle(14.5.sp), color = scheme.onPrimaryContainer.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp, end = 32.dp))
            Spacer(Modifier.height(18.dp))
            if (!ready) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(scheme.primary)
                        .clickable { if (enabled) onSelect() else onEnable() }
                        .padding(horizontal = 26.dp, vertical = 15.dp),
                ) {
                    Text(
                        text = if (enabled) "Choose keyboard" else "Enable keyboard",
                        style = latinStyle(15.sp, FontWeight.SemiBold),
                        color = scheme.onPrimary,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try it below ↓", style = latinStyle(14.5.sp, FontWeight.SemiBold), color = scheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
private fun StepCard(number: Int, title: String, body: String, state: StepState, action: String?, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val badgeBg by animateColorAsState(
        when (state) {
            StepState.DONE -> scheme.primary
            StepState.CURRENT -> scheme.tertiaryContainer
            StepState.LOCKED -> scheme.surfaceContainerHighest
        },
        tween(250), label = "stepBadge",
    )
    val badgeFg = when (state) {
        StepState.DONE -> scheme.onPrimary
        StepState.CURRENT -> scheme.onTertiaryContainer
        StepState.LOCKED -> scheme.onSurfaceVariant
    }
    SettingsCard(onClick = if (action != null && state == StepState.CURRENT) onAction else null) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CookieShape(8, 0.06f))
                    .background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                if (state == StepState.DONE) {
                    Icon(Icons.Rounded.Check, contentDescription = "Done", tint = badgeFg, modifier = Modifier.size(22.dp))
                } else {
                    Text("$number", style = displayStyle(20.sp, FontWeight.SemiBold), color = badgeFg)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = latinStyle(16.sp, FontWeight.SemiBold),
                    color = if (state == StepState.LOCKED) scheme.onSurfaceVariant else scheme.onSurface,
                )
                Text(body, style = latinStyle(12.5.sp), color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                if (action != null && state == StepState.CURRENT) {
                    Text(action + "  →", style = latinStyle(13.5.sp, FontWeight.SemiBold), color = scheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, good: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (good) scheme.secondaryContainer else scheme.surfaceContainerHigh, tween(300), label = "tileBg")
    Column(
        modifier = modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(value, style = displayStyle(22.sp, FontWeight.SemiBold), color = if (good) scheme.onSecondaryContainer else scheme.onSurfaceVariant, maxLines = 1)
        Text(label, style = latinStyle(12.sp, FontWeight.Medium), color = if (good) scheme.onSecondaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Diagnostics: crash log
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var log by remember { mutableStateOf(CrashLog.read(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            log = CrashLog.read(context)
            delay(2500)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        SettingsSection("Crash log") {
            SettingsCard {
                SelectionContainer {
                    Text(
                        text = log.ifBlank { "No crashes recorded. If the keyboard ever closes unexpectedly, the reason will appear here." },
                        style = latinStyle(11.5.sp).copy(fontFamily = FontFamily.Monospace),
                        color = if (log.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                    )
                }
            }
            if (log.isNotBlank()) {
                SettingActionRow("Copy log", onClick = { clipboard.setText(AnnotatedString(log)) })
                SettingActionRow(
                    "Share log",
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log)
                        context.startActivity(Intent.createChooser(send, "Share crash log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                )
                SettingActionRow("Clear log", onClick = { CrashLog.clear(context); log = "" })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
