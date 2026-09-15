package com.nepali.keyboard.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nepali.keyboard.R

val PoppinsFont = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal)
)

val DevanagariFont = FontFamily(
    Font(R.font.noto_sans_devanagari, FontWeight.Normal)
)

val KeyboardTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = PoppinsFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = PoppinsFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelLarge = TextStyle(
        fontFamily = DevanagariFont,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = DevanagariFont,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp
    )
)
