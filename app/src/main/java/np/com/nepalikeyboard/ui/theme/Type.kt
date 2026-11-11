package np.com.nepalikeyboard.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import np.com.nepalikeyboard.R

/**
 * Bundled font families.
 *
 * Both families are shipped inside `res/font` as real TTFs. No downloadable
 * fonts are used anywhere in the project: the keyboard must render identically
 * on a device that has never been online.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: FontWeight): Font = Font(
    resId = res,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Figtree (variable, one 60 KB file for every weight): friendly, open, highly legible UI + key-cap face. */
val UiFamily: FontFamily = FontFamily(
    variable(R.font.figtree, FontWeight.Light),
    variable(R.font.figtree, FontWeight.Normal),
    variable(R.font.figtree, FontWeight.Medium),
    variable(R.font.figtree, FontWeight.SemiBold),
    variable(R.font.figtree, FontWeight.Bold),
    variable(R.font.figtree, FontWeight.ExtraBold),
)

/** Outfit (variable): geometric display face for headlines, hero numbers and the wordmark. */
val DisplayFamily: FontFamily = FontFamily(
    variable(R.font.outfit, FontWeight.Medium),
    variable(R.font.outfit, FontWeight.SemiBold),
    variable(R.font.outfit, FontWeight.Bold),
    variable(R.font.outfit, FontWeight.ExtraBold),
)

/** Noto Sans Devanagari (variable, one file): light, even strokes at any weight from Light to Bold. */
val NotoSansDevanagariFamily: FontFamily = FontFamily(
    variable(R.font.noto_sans_devanagari_vf, FontWeight.Light),
    variable(R.font.noto_sans_devanagari_vf, FontWeight.Normal),
    variable(R.font.noto_sans_devanagari_vf, FontWeight.Medium),
    variable(R.font.noto_sans_devanagari_vf, FontWeight.SemiBold),
    variable(R.font.noto_sans_devanagari_vf, FontWeight.Bold),
)

/**
 * Devanagari needs more vertical room than Latin: matras above the shirorekha
 * (ि ी ै ो ौ ं ँ) and conjunct stacks below the baseline (क्ष त्र द्ध) draw far
 * outside the Latin em box. Everything that renders Devanagari on a key cap or
 * in the candidate strip goes through [devanagariStyle] so the line box is
 * always tall enough that Compose's text clip never shears a matra.
 */
fun devanagariStyle(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    lineHeightMultiplier: Float = 1.42f,
    letterSpacing: TextUnit = 0.sp,
): TextStyle = TextStyle(
    fontFamily = NotoSansDevanagariFamily,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size * lineHeightMultiplier,
    letterSpacing = letterSpacing,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/** Latin key-cap / UI style with an explicit line box (Figtree). */
fun latinStyle(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    lineHeightMultiplier: Float = 1.28f,
    letterSpacing: TextUnit = 0.sp,
): TextStyle = TextStyle(
    fontFamily = UiFamily,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size * lineHeightMultiplier,
    letterSpacing = letterSpacing,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/** Headline style in Outfit - hero titles, section titles, big numbers. */
fun displayStyle(
    size: TextUnit,
    weight: FontWeight = FontWeight.SemiBold,
    lineHeightMultiplier: Float = 1.18f,
    letterSpacing: TextUnit = 0.sp,
): TextStyle = TextStyle(
    fontFamily = DisplayFamily,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size * lineHeightMultiplier,
    letterSpacing = letterSpacing,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * Application typography. The full M3 role set is declared so that no role
 * silently falls back to Roboto and breaks the Figtree/Outfit-led visual language.
 */
val KeyboardTypography: Typography = Typography(
    displayLarge = displayStyle(57.sp, FontWeight.Normal, 1.12f, (-0.25).sp),
    displayMedium = displayStyle(45.sp, FontWeight.Normal, 1.16f),
    displaySmall = displayStyle(36.sp, FontWeight.Normal, 1.22f),
    headlineLarge = displayStyle(32.sp, FontWeight.Medium, 1.25f),
    headlineMedium = displayStyle(28.sp, FontWeight.Medium, 1.28f),
    headlineSmall = displayStyle(24.sp, FontWeight.Medium, 1.32f),
    titleLarge = displayStyle(22.sp, FontWeight.Medium, 1.27f),
    titleMedium = latinStyle(16.sp, FontWeight.Medium, 1.5f, 0.15.sp),
    titleSmall = latinStyle(14.sp, FontWeight.Medium, 1.43f, 0.1.sp),
    bodyLarge = latinStyle(16.sp, FontWeight.Normal, 1.5f, 0.15.sp),
    bodyMedium = latinStyle(14.sp, FontWeight.Normal, 1.43f, 0.25.sp),
    bodySmall = latinStyle(12.sp, FontWeight.Normal, 1.33f, 0.4.sp),
    labelLarge = latinStyle(14.sp, FontWeight.Medium, 1.43f, 0.1.sp),
    labelMedium = latinStyle(12.sp, FontWeight.Medium, 1.33f, 0.5.sp),
    labelSmall = latinStyle(11.sp, FontWeight.Medium, 1.45f, 0.5.sp),
)
