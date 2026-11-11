package np.com.nepalikeyboard.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.scale
import np.com.nepalikeyboard.ui.theme.ExpressiveMotion
import np.com.nepalikeyboard.ui.theme.displayStyle

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
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
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
// Building blocks - Material 3 Expressive: oversized radii, tonal containers, springy press + shape morph
// ---------------------------------------------------------------------------

@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = latinStyle(11.5.sp, FontWeight.SemiBold, letterSpacing = 1.2.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 10.dp, top = 20.dp, bottom = 8.dp),
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
    containerColor: androidx.compose.ui.graphics.Color = LocalKeyboardTokens.current.panelSurface,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, ExpressiveMotion.snappy(), label = "cardScale")
    val corner by animateDpAsState(if (pressed) 14.dp else 22.dp, ExpressiveMotion.snappy(), label = "cardCorner")
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .scale(scale)
            .clip(shape)
            .background(containerColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
    val scheme = MaterialTheme.colorScheme
    SettingsCard(modifier = modifier, onClick = { onCheckedChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = latinStyle(15.5.sp, FontWeight.Medium), color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (description != null) {
                    Text(
                        text = description,
                        style = latinStyle(12.5.sp),
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                thumbContent = if (checked) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
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
    val scheme = MaterialTheme.colorScheme
    SettingsCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = latinStyle(15.5.sp, FontWeight.Medium), color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (description != null) {
                    Text(
                        text = description,
                        style = latinStyle(12.5.sp),
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(scheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(text = value, style = latinStyle(12.5.sp, FontWeight.SemiBold), color = scheme.onSecondaryContainer, maxLines = 1)
            }
        }
    }
}

/**
 * Adaptive choice group. Options keep their natural width and wrap onto the next line instead of being squeezed
 * into equal slots (which truncated labels like "System default"). The selected option morphs into a full pill.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> SettingChoiceRow(
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    devanagari: (T) -> Boolean = { false },
) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (option in options) {
            val isSelected = option == selected
            val text = labelOf(option)
            val corner by animateDpAsState(if (isSelected) 20.dp else 12.dp, ExpressiveMotion.snappy(), label = "segCorner")
            val bg by animateColorAsState(if (isSelected) scheme.primaryContainer else scheme.surfaceContainerHigh, ExpressiveMotion.smooth(180), label = "segBg")
            val fg by animateColorAsState(if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant, ExpressiveMotion.smooth(180), label = "segFg")
            Box(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(corner))
                    .background(bg)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = if (devanagari(option)) devanagariStyle(14.sp, FontWeight.Normal) else latinStyle(13.5.sp, if (isSelected) FontWeight.Medium else FontWeight.Normal),
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
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
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 14.dp else 28.dp, ExpressiveMotion.snappy(), label = "btnCorner")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(corner))
            .background(if (emphasised) scheme.primary else scheme.secondaryContainer)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = latinStyle(15.sp, FontWeight.SemiBold),
            color = if (emphasised) scheme.onPrimary else scheme.onSecondaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SettingStatusDot(active: Boolean, tokens: KeyboardTokens) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (active) scheme.primary else scheme.surfaceContainerHighest, ExpressiveMotion.smooth(220), label = "dotBg")
    val size by animateDpAsState(if (active) 26.dp else 22.dp, ExpressiveMotion.bouncy(), label = "dotSize")
    Box(
        modifier = Modifier
            .size(26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            if (active) Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}
