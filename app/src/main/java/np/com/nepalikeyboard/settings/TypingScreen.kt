package np.com.nepalikeyboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.keyboard.KeyboardHost
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * Live, offline typing sandbox.
 *
 * The keyboard below is the real [KeyboardHost] - the same renderer, gesture
 * machine, phonetic engine and candidate ranker the IME window uses - pointed at
 * an in-memory buffer. Nothing typed here reaches an app, the clipboard or the
 * personal dictionary, and the buffer is dropped when the screen leaves
 * composition (see [SandboxController]).
 */
@Composable
fun TypingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { SandboxController(context.applicationContext, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val sandbox by controller.sandbox.collectAsStateWithLifecycle()
    val tokens = LocalKeyboardTokens.current

    DisposableEffect(controller) {
        onDispose { controller.clear() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.sandbox_title),
            style = latinStyle(16.sp, FontWeight.SemiBold),
            color = tokens.suggestionText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            text = stringResource(R.string.sandbox_body),
            style = latinStyle(12.sp),
            color = tokens.iconTint,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        SandboxTargetField(
            text = sandbox.text,
            caret = sandbox.caret,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(12.dp),
        )

        SandboxStats(
            romanBuffer = state.romanBuffer,
            preview = state.composingPreview,
            candidateCount = state.candidates.size,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingActionRow(
                label = stringResource(R.string.sandbox_clear),
                onClick = { controller.clear() },
                modifier = Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((SANDBOX_KEYBOARD_DP * state.keyboardHeightScale).dp)
                .padding(horizontal = 2.dp, vertical = 2.dp),
        ) {
            KeyboardHost(state = state, sink = controller)
        }
    }
}

@Composable
private fun SandboxTargetField(text: String, caret: Int, modifier: Modifier = Modifier) {
    val tokens = LocalKeyboardTokens.current
    val safeCaret = caret.coerceIn(0, text.length)
    val withCaret = buildString(text.length + 1) {
        append(text, 0, safeCaret)
        append('\u2502')
        append(text, safeCaret, text.length)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(tokens.panelSurface)
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (text.isEmpty()) {
                Text(
                    text = stringResource(R.string.sandbox_hint),
                    style = latinStyle(14.sp),
                    color = tokens.iconTint,
                )
            } else {
                Text(
                    text = withCaret,
                    style = latinStyle(16.sp).copy(color = tokens.suggestionText),
                )
            }
        }
    }
}

@Composable
private fun SandboxStats(
    romanBuffer: String,
    preview: String,
    candidateCount: Int,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Engineering read-out: what the roman buffer holds, what the phonetic
        // engine renders from it, and how many candidates the ranker returned.
        StatChip(stringResource(R.string.sandbox_literal), romanBuffer.ifEmpty { "-" }, tokens)
        StatChip(stringResource(R.string.sandbox_candidates), preview.ifEmpty { "-" }, tokens)
        StatChip(stringResource(R.string.sandbox_engine_ready), candidateCount.toString(), tokens)
    }
}

@Composable
private fun StatChip(label: String, value: String, tokens: KeyboardTokens) {
    Column {
        Text(
            text = label,
            style = latinStyle(9.sp, FontWeight.SemiBold),
            color = tokens.iconTint,
        )
        Text(
            text = value,
            style = latinStyle(13.sp, FontWeight.Medium),
            color = tokens.suggestionText,
        )
    }
}

private const val SANDBOX_KEYBOARD_DP = 250f
