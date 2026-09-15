package com.nikit.nepalikeyboard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.settings.ThemeMode

/**
 * =============================================================================
 * TYPOGRAPHY
 * =============================================================================
 *
 * Two bundled typefaces, declared explicitly rather than through downloadable
 * fonts.
 *
 * ### Why bundled and not downloadable
 *
 * The downloadable-font API requires `androidx.compose.ui:ui-text-google-fonts`,
 * a Google Play Services check at runtime, and — crucially — a *network fetch*
 * the first time a font is used. This application declares no `INTERNET`
 * permission, so a downloadable font could never resolve. It would fall back to
 * the system font, silently, and the Devanagari conjuncts would render with the
 * wrong metrics. Bundling is not a preference here; it is the only option that
 * works under the privacy contract.
 *
 * ### Why separate families
 *
 * Latin and Devanagari have different vertical metrics. If both scripts resolve
 * through one family, a line containing both ("नेपाल Nepal") gets a line height
 * that clips the matras above the baseline on some devices, because the Latin
 * font's ascent is shallower than what Devanagari needs to render visarga and
 * candrabindu without collision.
 *
 * So we declare them separately and let the platform do its per-script fallback:
 * the primary family covers Latin, and Devanagari code points fall through to
 * the Noto family, which the system picks automatically from
 * `res/font/` when the requested glyph is absent. Compose's fallback happens at
 * the shaping layer, so ligatures and conjuncts are still formed correctly.
 *
 * ### The vertical-clipping guard
 *
 * [KeyboardTypography] applies generous `lineHeight` values — 1.45× for
 * Devanagari display sizes — because Noto Sans Devanagari's tallest marks
 * (चन्द्रबिन्दु above, ् below) exceed the font's nominal em box. A tighter line
 * height clips them, and a clipped candrabindu is not merely ugly: it changes
 * which letter the reader perceives.
 */

/** Poppins, the Latin face. */
val PoppinsFamily: FontFamily = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.poppins_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.poppins_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold, FontStyle.Normal),
    Font(R.font.poppins_black, FontWeight.Black, FontStyle.Normal)
)

/**
 * Noto Sans Devanagari, the Devanagari face.
 *
 * Declared as a full family even though it ships as a single variable font:
 * Compose's `Font` resource loader resolves the variable axes through the
 * declared weights, so listing the weights here is what lets a bold key label
 * actually pick the bold instance rather than synthesising a fake bold, which
 * on Devanagari smears the matras into the consonants.
 */
val NotoDevanagariFamily: FontFamily = FontFamily(
    Font(R.font.noto_sans_devanagari, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.noto_sans_devanagari, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.noto_sans_devanagari, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.noto_sans_devanagari, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.noto_sans_devanagari, FontWeight.ExtraBold, FontStyle.Normal),
    Font(R.font.noto_sans_devanagari, FontWeight.Black, FontStyle.Normal)
)

/**
 * Composite family that puts Devanagari first.
 *
 * Used for any text that is expected to contain Devanagari — the composing
 * preview, the suggestion strip, the native key labels. Because Compose tries
 * each family in order and falls through on a missing glyph, listing Noto first
 * means Devanagari shapes through Noto and Latin falls through to Poppins, with
 * no per-character logic anywhere in the UI.
 */
val DevanagariFirstFamily: FontFamily = FontFamily(
    Font(R.font.noto_sans_devanagari, FontWeight.Normal),
    Font(R.font.noto_sans_devanagari, FontWeight.Medium),
    Font(R.font.noto_sans_devanagari, FontWeight.SemiBold),
    Font(R.font.noto_sans_devanagari, FontWeight.Bold),
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold)
)

/**
 * The keyboard's type scale.
 *
 * Deliberately flatter than Material 3's default scale: a keyboard has four
 * text roles (key label, modifier label, preview, suggestion) and inventing
 * more would produce inconsistency without benefit.
 */
object KeyboardTypography {
    /** Large Devanagari key glyphs. */
    val DevanagariKey = TextStyle(
        fontFamily = DevanagariFirstFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        // 1.45x because the tallest marks exceed the em box. See the file header.
        lineHeight = 38.sp
    )

    /** Latin key glyphs, slightly smaller — Poppins has a larger x-height. */
    val LatinKey = TextStyle(
        fontFamily = PoppinsFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp
    )

    /** Small caps label for modifier keys (`?123`, `ABC`). */
    val ModifierKey = TextStyle(
        fontFamily = PoppinsFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )

    /** Symbol key glyph, e.g. a globe or a gear. */
    val SymbolKey = TextStyle(
        fontFamily = PoppinsFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 26.sp
    )

    /** The transliterated preview above the suggestions. */
    val ComposingPreview = TextStyle(
        fontFamily = DevanagariFirstFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 29.sp
    )

    /** A suggestion chip. */
    val Suggestion = TextStyle(
        fontFamily = DevanagariFirstFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp
    )

    /** The literal-input hint in the strip. */
    val SuggestionHint = TextStyle(
        fontFamily = PoppinsFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )

    /** Emoji glyph. */
    val Emoji = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp
    )
}

/**
 * =============================================================================
 * COLOUR
 * =============================================================================
 *
 * The palette is seeded on Kathmandu crimson, the colour of the national flag's
 * crimson field, which gives the keyboard an identity that is recognisably
 * Nepali without being decorative. Every role is derived from that seed, so the
 * scheme is chromatically coherent rather than a set of hand-picked colours.
 *
 * ### Keyboard-specific roles
 *
 * Material 3's roles do not cover a keyboard's needs. A keyboard has:
 *  * letter keys (surface-like, high contrast against the label),
 *  * modifier keys (visually recessed, signalling "this does something
 *    structural"),
 *  * accent keys (the Enter key and the active layout tab),
 *  * a pressed state for each of the three.
 *
 * Those are expressed through [KeyboardColors], provided via a
 * `CompositionLocal` rather than forced into M3's semantics, because reusing
 * e.g. `primaryContainer` for "modifier key" would make the keyboard's
 * appearance depend on unrelated Material component choices.
 */

/**
 * The keyboard-specific colour roles.
 *
 * Every field is resolved per theme by [keyboardColorsFor].
 */
data class KeyboardColors(
    /** Unpressed letter key fill. */
    val keyBackground: Color,
    /** Letter key fill while pressed. */
    val keyBackgroundPressed: Color,
    /** Modifier key fill (shift, backspace, `?123`). */
    val modifierBackground: Color,
    /** Modifier key fill while pressed. */
    val modifierBackgroundPressed: Color,
    /** Accent key fill (Enter, active layout tab). */
    val accentBackground: Color,
    /** Accent key fill while pressed. */
    val accentBackgroundPressed: Color,
    /** Label colour on letter and modifier keys. */
    val keyText: Color,
    /** Label colour on accent keys. */
    val keyTextOnAccent: Color,
    /** Hairline around a key, drawn only when the user asks for borders. */
    val keyBorder: Color,
    /** Background of the key area itself. */
    val keyboardSurface: Color,
    /** Background of the suggestion strip. */
    val stripSurface: Color,
    /** Selected-tab indicator. */
    val tabIndicator: Color,
    /** Unselected tab label. */
    val tabInactive: Color,
    /** The key press ripple, if the platform ripple is unsuitable. */
    val ripple: Color
)

/**
 * The light palette's keyboard roles.
 *
 * Keys are pure white on a very slightly warm surface, which is the convention
 * across every major keyboard and gives the maximum contrast between a key and
 * the background it sits on. Modifiers are a warm grey one step darker so that
 * shift and backspace read as recessed without needing a border or an icon
 * weight change.
 */
private val LightKeyboardColors = KeyboardColors(
    keyBackground = Color(0xFFFFFFFF),
    keyBackgroundPressed = Color(0xFFE4D3D2),
    modifierBackground = Color(0xFFE3D1D0),
    modifierBackgroundPressed = Color(0xFFCFBDBC),
    accentBackground = Color(0xFF8F1D2C),
    accentBackgroundPressed = Color(0xFF6B0F1C),
    keyText = Color(0xFF231919),
    keyTextOnAccent = Color(0xFFFFFFFF),
    keyBorder = Color(0x1F000000),
    keyboardSurface = Color(0xFFF3E3E1),
    stripSurface = Color(0xFFFCEAE8),
    tabIndicator = Color(0xFF8F1D2C),
    tabInactive = Color(0xFF5C4A48),
    ripple = Color(0x248F1D2C)
)

/**
 * The dark palette's keyboard roles.
 *
 * Note the moderation: key surfaces are `#3A3030` rather than near-black, and
 * the accent is *lightened* to `#FFB3B2`. Both choices are deliberate. A
 * keyboard with black keys on a black background has no perceptible key
 * boundaries, and a saturated dark crimson on a dark surface fails contrast
 * requirements for the label. Lightening the accent for dark mode is what
 * Material 3's tonal palette does for `primary` in dark schemes, and the same
 * reasoning applies here.
 */
private val DarkKeyboardColors = KeyboardColors(
    keyBackground = Color(0xFF3A3030),
    keyBackgroundPressed = Color(0xFF544646),
    modifierBackground = Color(0xFF2B2222),
    modifierBackgroundPressed = Color(0xFF423636),
    accentBackground = Color(0xFFFFB3B2),
    accentBackgroundPressed = Color(0xFFE09594),
    keyText = Color(0xFFF2E7E6),
    keyTextOnAccent = Color(0xFF5F0D1B),
    keyBorder = Color(0x1FFFFFFF),
    keyboardSurface = Color(0xFF211A1A),
    stripSurface = Color(0xFF2A2121),
    tabIndicator = Color(0xFFFFB3B2),
    tabInactive = Color(0xFFB9A8A7),
    ripple = Color(0x33FFB3B2)
)

/**
 * The AMOLED palette's keyboard roles.
 *
 * The key area is true black so that those pixels are switched off on an OLED
 * panel. Keys themselves stay `#141414` rather than black: if they were also
 * black there would be no boundary at all between a key and the background, and
 * the grid would be invisible. So the *gaps and padding* go to black while the
 * keys retain the minimum luminance that keeps the geometry legible.
 */
private val AmoledKeyboardColors = KeyboardColors(
    keyBackground = Color(0xFF141414),
    keyBackgroundPressed = Color(0xFF2C2C2C),
    modifierBackground = Color(0xFF0C0A0A),
    modifierBackgroundPressed = Color(0xFF232020),
    accentBackground = Color(0xFFFFB3B2),
    accentBackgroundPressed = Color(0xFFE09594),
    keyText = Color(0xFFEDE3E2),
    keyTextOnAccent = Color(0xFF5F0D1B),
    keyBorder = Color(0x26FFFFFF),
    keyboardSurface = Color(0xFF000000),
    stripSurface = Color(0xFF0A0A0A),
    tabIndicator = Color(0xFFFFB3B2),
    tabInactive = Color(0xFF9E9090),
    ripple = Color(0x33FFB3B2)
)

/**
 * Provides [KeyboardColors] down the composition.
 *
 * `staticCompositionLocalOf` rather than `compositionLocalOf`: the palette
 * changes only when the theme changes, which is a rare, whole-tree event. The
 * dynamic variant would track reads and invalidate individual readers, which is
 * wasted bookkeeping for a value that never changes mid-session.
 */
val LocalKeyboardColors = staticCompositionLocalOf { LightKeyboardColors }

/**
 * Accessor mirroring `MaterialTheme.colorScheme`.
 */
object KeyboardTheme {
    val colors: KeyboardColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKeyboardColors.current
}

/**
 * The keyboard's theme wrapper.
 *
 * ### Dynamic colour
 *
 * On Android 12+ we can derive the whole palette from the user's wallpaper via
 * `dynamicLightColorScheme` / `dynamicDarkColorScheme`. It is gated on the user
 * preference *and* on the platform version, and it is applied to the Material
 * scheme only — [KeyboardColors] stays on our crimson palette in all cases.
 * That is intentional: extracting a keyboard's key/modifier/accent triad from a
 * wallpaper scheme produces results that are frequently unusable (a pastel
 * accent on a pastel key has no contrast), whereas Material components elsewhere
 * in the app adapt gracefully. So dynamic colour tints the app's chrome and the
 * accent keys, while the key geometry keeps a palette chosen for legibility.
 *
 * @param themeMode the user's light/dark/AMOLED selection
 * @param dynamicColor whether to use the wallpaper-derived scheme where possible
 */
@Composable
fun NepaliKeyboardTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val materialScheme = when {
        dynamicColor && supportsDynamic && dark -> dynamicDarkColorScheme(context)
        dynamicColor && supportsDynamic -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xFFFFB3B2),
            onPrimary = Color(0xFF5F0D1B),
            primaryContainer = Color(0xFF73141F),
            onPrimaryContainer = Color(0xFFFFDADA),
            secondary = Color(0xFFE7BDB8),
            onSecondary = Color(0xFF442925),
            secondaryContainer = Color(0xFF5D3F3B),
            onSecondaryContainer = Color(0xFFFFDADA),
            tertiary = Color(0xFFE1C38E),
            onTertiary = Color(0xFF3F2E04),
            background = Color(0xFF1A1111),
            onBackground = Color(0xFFF2E7E6),
            surface = Color(0xFF1A1111),
            onSurface = Color(0xFFF2E7E6),
            surfaceVariant = Color(0xFF534341),
            onSurfaceVariant = Color(0xFFD8C2C0),
            outline = Color(0xFFA08C8A),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005)
        )
        else -> lightColorScheme(
            primary = Color(0xFF8F1D2C),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDADA),
            onPrimaryContainer = Color(0xFF3F0009),
            secondary = Color(0xFF775652),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFDADA),
            onSecondaryContainer = Color(0xFF2C1513),
            tertiary = Color(0xFF715B2E),
            onTertiary = Color(0xFFFFFFFF),
            background = Color(0xFFFFF8F7),
            onBackground = Color(0xFF231919),
            surface = Color(0xFFFFF8F7),
            onSurface = Color(0xFF231919),
            surfaceVariant = Color(0xFFF5DDDB),
            onSurfaceVariant = Color(0xFF534341),
            outline = Color(0xFF857371),
            error = Color(0xFFBA1A1A),
            onError = Color(0xFFFFFFFF)
        )
    }

    val keyboardColors = when {
        themeMode == ThemeMode.AMOLED -> AmoledKeyboardColors
        dark -> DarkKeyboardColors
        else -> LightKeyboardColors
    }

    CompositionLocalProvider(LocalKeyboardColors provides keyboardColors) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = MaterialTheme.typography.copy(
                // Both bundled faces are pulled into the Material scale so that
                // any component we render — a Slider label, a Switch caption —
                // matches the keyboard's own text without per-call-site font
                // overrides.
                bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = DevanagariFirstFamily),
                bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = DevanagariFirstFamily),
                bodySmall = MaterialTheme.typography.bodySmall.copy(fontFamily = PoppinsFamily),
                titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = DevanagariFirstFamily),
                titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = DevanagariFirstFamily),
                labelLarge = MaterialTheme.typography.labelLarge.copy(fontFamily = PoppinsFamily),
                labelMedium = MaterialTheme.typography.labelMedium.copy(fontFamily = PoppinsFamily),
                labelSmall = MaterialTheme.typography.labelSmall.copy(fontFamily = PoppinsFamily)
            ),
            content = content
        )
    }
}
