package np.com.nepalikeyboard.ui.theme

import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import np.com.nepalikeyboard.data.LayoutPreference
import np.com.nepalikeyboard.data.SettingsSnapshot
import np.com.nepalikeyboard.data.ThemeMode

/**
 * Keyboard-specific design tokens.
 *
 * Material 3 describes app surfaces, but a keyboard needs an extra set of roles
 * that M3 has no names for: pressed key fills, function-key tiers, the accent
 * tier used by Space/Enter, and candidate-chip colours. They live here as one
 * stable token object so that key rendering reads a single snapshot and never
 * recomposes due to unrelated theme work.
 */
@Immutable
data class KeyboardTokens(
    val keyboardBackground: Color,
    val keyBackground: Color,
    val keyBackgroundPressed: Color,
    val keyLabel: Color,
    val functionKeyBackground: Color,
    val functionKeyPressed: Color,
    val functionKeyLabel: Color,
    val accentKeyBackground: Color,
    val accentKeyPressed: Color,
    val accentKeyLabel: Color,
    val suggestionBackground: Color,
    val suggestionText: Color,
    val suggestionChip: Color,
    val suggestionChipSelected: Color,
    val suggestionChipSelectedText: Color,
    val divider: Color,
    val iconTint: Color,
    val iconTintActive: Color,
    val panelBackground: Color,
    val panelSurface: Color,
    val keyCornerRadius: Dp,
    val functionCornerRadius: Dp,
    val keyGap: Dp,
    val sidePadding: Dp,
    val keyPressedScale: Float,
    val keyShadowElevation: Dp,
    val stripHeight: Dp,
    val toolbarHeight: Dp,
    val keyLabelSizeScale: Float = 1.0f,
)

private val BaseKeyRadius = 7.dp
private val BaseFunctionRadius = 12.dp
private val BaseKeyGap = 5.dp
private val BaseSidePadding = 4.dp

/** Fallback tokens used before the theme is applied (previews, tests). */
fun defaultKeyboardTokens(scheme: ColorScheme = schemeFrom(HimalayaPalette, dark = false)): KeyboardTokens =
    buildTokens(scheme, roundnessScale = 1f)

private fun buildTokens(scheme: ColorScheme, roundnessScale: Float): KeyboardTokens {
    val radius = BaseKeyRadius * roundnessScale
    val fnRadius = BaseFunctionRadius * roundnessScale
    return KeyboardTokens(
        keyboardBackground = scheme.surfaceContainerLowest,
        keyBackground = scheme.surfaceContainerHigh,
        keyBackgroundPressed = scheme.surfaceContainerHighest,
        keyLabel = scheme.onSurface,
        functionKeyBackground = scheme.surfaceContainer,
        functionKeyPressed = scheme.surfaceContainerHighest,
        functionKeyLabel = scheme.onSurfaceVariant,
        accentKeyBackground = scheme.primaryContainer,
        accentKeyPressed = lerp(scheme.primaryContainer, scheme.primary, 0.35f),
        accentKeyLabel = scheme.onPrimaryContainer,
        suggestionBackground = scheme.surfaceContainerLow,
        suggestionText = scheme.onSurface,
        suggestionChip = scheme.surfaceContainer,
        suggestionChipSelected = scheme.primaryContainer,
        suggestionChipSelectedText = scheme.onPrimaryContainer,
        divider = scheme.outlineVariant,
        iconTint = scheme.onSurfaceVariant,
        iconTintActive = scheme.primary,
        panelBackground = scheme.surfaceContainerLowest,
        panelSurface = scheme.surfaceContainer,
        keyCornerRadius = radius,
        functionCornerRadius = fnRadius,
        keyGap = BaseKeyGap,
        sidePadding = BaseSidePadding,
        keyPressedScale = 0.94f,
        keyShadowElevation = 1.dp,
        stripHeight = 44.dp,
        toolbarHeight = 36.dp,
    )
}

val LocalKeyboardTokens = staticCompositionLocalOf { defaultKeyboardTokens() }

/**
 * M3 Expressive motion.
 *
 * Expressive emphasises springy, physical motion over linear fades. These specs
 * are the single source of truth for every keyboard animation so that key
 * feedback, panel transitions and the suggestion strip all share one rhythm.
 */
object ExpressiveMotion {
    fun <T> springy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.74f,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> snappy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.62f,
        stiffness = Spring.StiffnessMedium,
    )

    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    fun <T> smooth(durationMillis: Int = 240): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis)

    val keyPressSpring: SpringSpec<Float> = spring(
        dampingRatio = 0.55f,
        stiffness = Spring.StiffnessHigh,
    )
}

/** Expressive shape scale: generous radii, softened corners on large surfaces. */
val ExpressiveShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/**
 * Resolves the active [ColorScheme] for a settings snapshot.
 *
 * Order of precedence: Material You (API 31+, if enabled) -> curated palette.
 * [ThemeMode.AMOLED] additionally collapses every surface to true black.
 */
fun resolveColorScheme(
    snapshot: SettingsSnapshot,
    systemInDarkTheme: Boolean,
    dynamicColorSupported: Boolean,
    dynamicScheme: (Boolean) -> ColorScheme,
): ColorScheme {
    val dark = when (snapshot.themeMode) {
        ThemeMode.SYSTEM -> systemInDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val base = if (snapshot.dynamicColor && dynamicColorSupported) {
        dynamicScheme(dark)
    } else {
        schemeFrom(paletteAnchors(snapshot.palette), dark)
    }
    return if (snapshot.themeMode == ThemeMode.AMOLED) amoledify(base) else base
}

/**
 * Theme wrapper used by both the Settings activity and the IME window.
 *
 * The IME calls this from inside its own composition (the service window has no
 * Activity theme), which is why everything is resolved here rather than in XML.
 */
@Composable
fun NepaliKeyboardTheme(
    snapshot: SettingsSnapshot,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = remember(snapshot.themeMode, snapshot.dynamicColor, snapshot.palette, systemDark, dynamicSupported) {
        resolveColorScheme(
            snapshot = snapshot,
            systemInDarkTheme = systemDark,
            dynamicColorSupported = dynamicSupported,
            dynamicScheme = { dark ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            },
        )
    }

    val tokens = remember(
        colorScheme,
        snapshot.keyRoundnessScale,
    ) { buildTokens(colorScheme, snapshot.keyRoundnessScale) }

    ApplyStatusBarAppearance(dark = colorScheme.isDarkAppearance(snapshot, systemDark))

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KeyboardTypography,
        shapes = ExpressiveShapes,
    ) {
        CompositionLocalProvider(
            LocalKeyboardTokens provides tokens,
            content = content,
        )
    }
}

private fun ColorScheme.isDarkAppearance(snapshot: SettingsSnapshot, systemDark: Boolean): Boolean =
    when (snapshot.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

/**
 * Aligns the system bars with the app theme. No-op inside the IME window
 * (which is not owned by an Activity).
 */
@Composable
private fun ApplyStatusBarAppearance(dark: Boolean) {
    val view = LocalView.current
    val window = remember(view) { (view.context as? android.app.Activity)?.window }
    androidx.compose.runtime.SideEffect {
        if (window != null && !view.isInEditMode) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
}

/** Convenience: the default layout preference for a fresh install. */
val DefaultLayoutPreference: LayoutPreference = LayoutPreference.ROMAN
