package np.com.nepalikeyboard.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
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
val PoppinsFamily: FontFamily = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

val NotoSansDevanagariFamily: FontFamily = FontFamily(
    Font(R.font.noto_sans_devanagari_regular, FontWeight.Normal),
    Font(R.font.noto_sans_devanagari_medium, FontWeight.Medium),
    Font(R.font.noto_sans_devanagari_bold, FontWeight.Bold),
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
    weight: FontWeight = FontWeight.Medium,
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

/** Latin key-cap / UI style with an explicit line box, Poppins only. */
fun latinStyle(
    size: TextUnit,
    weight: FontWeight = FontWeight.Medium,
    lineHeightMultiplier: Float = 1.28f,
    letterSpacing: TextUnit = 0.sp,
): TextStyle = TextStyle(
    fontFamily = PoppinsFamily,
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
 * silently falls back to Roboto and breaks the Poppins-led visual language.
 */
val KeyboardTypography: Typography = Typography(
    displayLarge = latinStyle(57.sp, FontWeight.Normal, 1.12f, (-0.25).sp),
    displayMedium = latinStyle(45.sp, FontWeight.Normal, 1.16f),
    displaySmall = latinStyle(36.sp, FontWeight.Normal, 1.22f),
    headlineLarge = latinStyle(32.sp, FontWeight.SemiBold, 1.25f),
    headlineMedium = latinStyle(28.sp, FontWeight.SemiBold, 1.28f),
    headlineSmall = latinStyle(24.sp, FontWeight.SemiBold, 1.32f),
    titleLarge = latinStyle(22.sp, FontWeight.SemiBold, 1.27f),
    titleMedium = latinStyle(16.sp, FontWeight.SemiBold, 1.5f, 0.15.sp),
    titleSmall = latinStyle(14.sp, FontWeight.Medium, 1.43f, 0.1.sp),
    bodyLarge = latinStyle(16.sp, FontWeight.Normal, 1.5f, 0.15.sp),
    bodyMedium = latinStyle(14.sp, FontWeight.Normal, 1.43f, 0.25.sp),
    bodySmall = latinStyle(12.sp, FontWeight.Normal, 1.33f, 0.4.sp),
    labelLarge = latinStyle(14.sp, FontWeight.Medium, 1.43f, 0.1.sp),
    labelMedium = latinStyle(12.sp, FontWeight.Medium, 1.33f, 0.5.sp),
    labelSmall = latinStyle(11.sp, FontWeight.Medium, 1.45f, 0.5.sp),
)
