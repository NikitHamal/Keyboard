package np.com.nepalikeyboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import np.com.nepalikeyboard.data.SettingsRepository
import np.com.nepalikeyboard.ime.ImeRuntime
import np.com.nepalikeyboard.ui.theme.KeyboardTokens
import np.com.nepalikeyboard.ui.theme.LocalKeyboardTokens
import np.com.nepalikeyboard.ui.theme.devanagariStyle
import np.com.nepalikeyboard.ui.theme.latinStyle

/**
 * Fire-and-forget writer for every settings screen.
 *
 * All writes go through the same [SettingsRepository] the keyboard observes, so
 * a change made here is live in the IME window the next time a snapshot is
 * emitted - no manual refresh, no duplicated state, and no screen that can drift
 * from the keyboard's behaviour.
 */
@Stable
class SettingsWriter(
    private val scope: CoroutineScope,
    private val runtime: ImeRuntime,
) {
    val repository: SettingsRepository get() = runtime.settings

    fun write(block: suspend (SettingsRepository) -> Unit) {
        val repo = runtime.settings
        scope.launch { block(repo) }
    }

    fun setOnboardingCompleted(value: Boolean) = write { it.setOnboardingCompleted(value) }

    fun resetAll() = write { it.resetToDefaults() }

    fun addPersonalWord(word: String, roman: String) {
        runtime.learning.addPersonalWord(word.trim(), roman.trim())
        scope.launch { runtime.learning.flush() }
    }

    fun removePersonalWord(word: String) {
        runtime.learning.removePersonalWord(word)
        scope.launch { runtime.learning.flush() }
    }

    fun clearLearning() {
        runtime.learning.clearLearning()
        scope.launch { runtime.learning.flush() }
    }
}

@Composable
fun rememberSettingsWriter(): SettingsWriter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) { SettingsWriter(scope, ImeRuntime.get(context)) }
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = LocalKeyboardTokens.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = latinStyle(11.sp, FontWeight.SemiBold),
            color = tokens.iconTintActive,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        content()
    }
}

@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tokens = LocalKeyboardTokens.current
    val shape = RoundedCornerShape(tokens.functionCornerRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(tokens.panelSurface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        content()
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    SettingsCard(modifier = modifier, onClick = { onCheckedChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = latinStyle(14.sp, FontWeight.Medium),
                    color = tokens.suggestionText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = latinStyle(11.sp),
                        color = tokens.iconTint,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun SettingInfoRow(
    title: String,
    value: String,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKeyboardTokens.current
    SettingsCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = latinStyle(14.sp, FontWeight.Medium),
                    color = tokens.suggestionText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = latinStyle(11.sp),
                        color = tokens.iconTint,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = value,
                style = latinStyle(12.sp, FontWeight.SemiBold),
                color = tokens.iconTint,
                maxLines = 1,
            )
        }
    }
}

/**
 * Horizontal single-choice row: one pill per option, the selected one tinted.
 *
 * [labelOf] is a `@Composable` lambda rather than a plain one so that callers can
 * resolve their labels with `stringResource`, which is itself composable. Making
 * it plain would force every caller to hoist the resource lookups into a
 * precomputed map, which is both boilerplate and a correctness hazard: a map
 * built once would keep serving the old language after a locale change.
 */
@Composable
fun <T> SettingChoiceRow(
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    devanagari: (T) -> Boolean = { false },
) {
    val tokens = LocalKeyboardTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (option in options) {
            val isSelected = option == selected
            val text = labelOf(option)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(tokens.functionCornerRadius))
                    .background(if (isSelected) tokens.accentKeyBackground else tokens.panelSurface)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = if (devanagari(option)) {
                        devanagariStyle(14.sp, FontWeight.Medium)
                    } else {
                        latinStyle(12.sp, FontWeight.Medium)
                    },
                    color = if (isSelected) tokens.accentKeyLabel else tokens.suggestionText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SettingActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    val tokens = LocalKeyboardTokens.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(tokens.functionCornerRadius))
            .background(if (emphasised) tokens.accentKeyBackground else tokens.panelSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = latinStyle(14.sp, FontWeight.SemiBold),
            color = if (emphasised) tokens.accentKeyLabel else tokens.iconTintActive,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SettingStatusDot(active: Boolean, tokens: KeyboardTokens) {
    Box(
        modifier = Modifier
            .width(10.dp)
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) tokens.iconTintActive else tokens.divider),
    )
}
