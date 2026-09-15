package np.com.nepalikeyboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.data.PersonalWord
import np.com.nepalikeyboard.ime.ImeRuntime
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.devanagariStyle
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * Personal dictionary.
 *
 * Shows what the keyboard has learned on this device (frequency-ranked) and
 * lets the user curate it: add a word by hand, delete one, or wipe the lot.
 * Everything here is local-only state - the same store the IME writes to when a
 * word is committed twice - and nothing in it is ever transmitted, because the
 * app holds no network permission in the first place.
 */
@Composable
fun DictionaryScreen(
    writer: SettingsWriter,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val runtime = remember(context) { ImeRuntime.get(context) }
    val personal by runtime.learning.personalState.collectAsStateWithLifecycle()
    val learnedCount by runtime.learning.learnedCountState.collectAsStateWithLifecycle()
    val tokens = LocalKeyboardTokens.current

    var word by rememberSaveable { mutableStateOf("") }
    var roman by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        SettingsSection(stringResource(R.string.dictionary_title)) {
            SettingInfoRow(
                title = stringResource(R.string.dictionary_title),
                value = learnedCount.toString(),
                description = stringResource(R.string.dictionary_body),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DictionaryField(
                    value = word,
                    placeholder = stringResource(R.string.dictionary_word_hint),
                    tokens = tokens,
                    devanagari = true,
                    onValueChange = { word = it },
                    modifier = Modifier.weight(1f),
                )
                DictionaryField(
                    value = roman,
                    placeholder = stringResource(R.string.dictionary_roman_hint),
                    tokens = tokens,
                    devanagari = false,
                    onValueChange = { roman = it },
                    modifier = Modifier.weight(1f),
                )
                SettingActionRow(
                    label = stringResource(R.string.dictionary_add),
                    onClick = {
                        if (word.isNotBlank()) {
                            writer.addPersonalWord(word, roman)
                            word = ""
                            roman = ""
                        }
                    },
                    modifier = Modifier.weight(1f),
                    emphasised = true,
                )
            }

            if (personal.isEmpty()) {
                Text(
                    text = stringResource(R.string.dictionary_empty),
                    style = latinStyle(12.sp),
                    color = tokens.iconTint,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.dictionary_learned_header),
                    style = latinStyle(11.sp, FontWeight.SemiBold),
                    color = tokens.iconTintActive,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                for (entry in personal) {
                    PersonalWordRow(
                        entry = entry,
                        tokens = tokens,
                        onRemove = { writer.removePersonalWord(entry.word) },
                    )
                }
            }
        }

        SettingsSection(stringResource(R.string.dictionary_clear_learning)) {
            SettingActionRow(
                label = stringResource(R.string.dictionary_clear_learning),
                onClick = { writer.clearLearning() },
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PersonalWordRow(
    entry: PersonalWord,
    tokens: KeyboardTokens,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(tokens.panelSurface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.word,
                style = devanagariStyle(16.sp, FontWeight.Medium).copy(color = tokens.suggestionText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.roman}  x${entry.hits}",
                style = latinStyle(10.sp),
                color = tokens.iconTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(tokens.keyCornerRadius))
                .clickable(onClick = onRemove)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.dictionary_remove),
                style = latinStyle(11.sp, FontWeight.SemiBold),
                color = tokens.iconTintActive,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DictionaryField(
    value: String,
    placeholder: String,
    tokens: KeyboardTokens,
    devanagari: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = if (devanagari) {
        devanagariStyle(14.sp, FontWeight.Medium)
    } else {
        latinStyle(13.sp, FontWeight.Medium)
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(tokens.panelSurface)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style.copy(color = tokens.suggestionText),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = style,
                        color = tokens.iconTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
        )
    }
}
