package np.com.nepalikeyboard.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Candidate chips.
 *
 * Ordering is decided by `CandidateEngine` and never re-sorted here: the literal
 * roman input sits first, then the deterministic transliteration, then lexical
 * and statistical alternatives. Tapping a chip commits exactly its `text`.
 *
 * Three states are rendered instead of a list when there is nothing to offer:
 * the secure-field hint, the live transliteration preview (so a user typing
 * romanized Nepali always sees what the engine would insert, even with
 * suggestions switched off), and an empty spacer.
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
                modifier = modifier.padding(horizontal = tokens.sidePadding + 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = preview,
                    style = devanagariStyle(20.sp, FontWeight.SemiBold),
                    color = tokens.suggestionText,
                    maxLines = 1,
                )
            }
        } else {
            Box(modifier)
        }
        return
    }

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = tokens.sidePadding, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in candidates.indices) {
            CandidateChip(
                candidate = candidates[index],
                primary = index == 1,
                tokens = tokens,
                onClick = { sink.onCandidateSelected(candidates[index]) },
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
    val background = when {
        primary && !literal -> tokens.suggestionChipSelected
        else -> tokens.suggestionChip
    }
    val labelColor = if (primary && !literal) tokens.suggestionChipSelectedText else tokens.suggestionText
    val hintColor = if (primary && !literal) tokens.suggestionChipSelectedText else tokens.iconTint
    val labelStyle = if (candidate.usesDevanagari) {
        devanagariStyle(17.sp, if (primary) FontWeight.SemiBold else FontWeight.Medium)
    } else {
        latinStyle(14.sp, if (primary) FontWeight.SemiBold else FontWeight.Medium)
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            Text(
                text = candidate.display,
                style = labelStyle,
                color = labelColor,
                maxLines = 1,
            )
            val hint = candidate.hint
            if (hint != null && hint != candidate.display) {
                Text(
                    text = hint,
                    style = latinStyle(10.sp),
                    color = hintColor,
                    maxLines = 1,
                )
            }
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
