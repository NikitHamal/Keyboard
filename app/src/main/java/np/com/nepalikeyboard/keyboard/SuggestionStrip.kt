package np.com.nepalikeyboard.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.engine.Candidate
import np.com.nepalikeyboard.engine.CandidateKind
import np.com.nepalikeyboard.ime.ImeUiState
import np.com.nepalikeyboard.ime.KeyboardActionSink
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.devanagariStyle
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * Suggestion strip, Gboard style with Material 3 Expressive accents.
 *
 * Ordering is decided by the engine and never re-sorted here: the TinyLM pick comes first and is
 * drawn as a filled stadium chip, alternatives follow as quiet text separated by hairlines, and the
 * literal Latin input is last. A chain icon marks a chip that will be glued to the previous word
 * (padayog). Tapping a chip commits exactly its `text`.
 */
@Composable
fun SuggestionStrip(
    state: ImeUiState,
    sink: KeyboardActionSink,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    if (state.isSecure) {
        StripNotice(text = stringResource(R.string.secure_field_notice), tokens = tokens, modifier = modifier)
        return
    }
    val candidates = state.candidates
    if (candidates.isEmpty()) {
        val preview = state.composingPreview
        if (preview.isNotEmpty()) {
            Box(
                modifier = modifier.padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.suggestionChipSelected)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = preview,
                        style = devanagariStyle(19.sp, FontWeight.Normal),
                        color = tokens.suggestionChipSelectedText,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Box(modifier)
        }
        return
    }
    val primaryIndex = if (candidates.size > 1 &&
        candidates[0].kind == CandidateKind.LITERAL &&
        candidates[1].kind == CandidateKind.TRANSLITERATION
    ) {
        1
    } else {
        0
    }
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in candidates.indices) {
            val candidate = candidates[index]
            if (index > 0 && index != primaryIndex && index - 1 != primaryIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .size(width = 1.dp, height = 18.dp)
                        .background(tokens.divider),
                )
            }
            CandidateChip(
                candidate = candidate,
                primary = index == primaryIndex && candidate.kind != CandidateKind.LITERAL,
                tokens = tokens,
                onClick = { sink.onCandidateSelected(candidate) },
            )
        }
    }
}

@Composable
private fun CandidateChip(
    candidate: Candidate,
    primary: Boolean,
    tokens: KeyboardTokens,
    onClick: () -> Unit,
) {
    val literal = candidate.kind == CandidateKind.LITERAL
    val labelColor = when {
        primary -> tokens.suggestionChipSelectedText
        literal -> tokens.iconTint
        else -> tokens.suggestionText
    }
    val labelStyle = if (candidate.usesDevanagari) {
        devanagariStyle(if (primary) 19.sp else 17.sp, FontWeight.Normal)
    } else {
        latinStyle(15.sp, if (primary) FontWeight.Medium else FontWeight.Normal)
    }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) tokens.suggestionChipSelected else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (primary) 12.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (candidate.joinPrev) {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.size(13.dp),
                )
                Box(Modifier.width(3.dp))
            }
            Text(
                text = candidate.display,
                style = labelStyle,
                color = labelColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StripNotice(text: String, tokens: KeyboardTokens, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.sidePadding + 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = latinStyle(12.sp),
            color = tokens.iconTint,
            maxLines = 1,
        )
    }
}
