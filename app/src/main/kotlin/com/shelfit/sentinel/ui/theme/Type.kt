package com.shelfit.sentinel.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.shelfit.sentinel.R

/**
 * Hanken Grotesk, the design's face, as one variable font.
 *
 * A single 132 KB `wght`-axis TTF rather than six static files: the design uses weights
 * from 200 (the ambient clock) to 700, and the variable font carries them all for less
 * than the size of two static cuts. Bundled as a resource, not a downloadable font —
 * this app must render its shelf face with no network and no Google provider present.
 *
 * License: SIL OFL — see `docs/licenses/HankenGrotesk-OFL.txt`.
 */
private fun hanken(weight: FontWeight) = Font(
    resId = R.font.hanken_grotesk,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Hanken = FontFamily(
    hanken(FontWeight.W200),
    hanken(FontWeight.W300),
    hanken(FontWeight.W400),
    hanken(FontWeight.W500),
    hanken(FontWeight.W600),
    hanken(FontWeight.W700),
)

/**
 * The artboards' type scale mapped onto Material's roles, so screens can keep asking
 * `MaterialTheme.typography` for a style and receive the design's answer.
 *
 * The mapping is by *use*, not by name similarity: `labelSmall` is the spaced-caps
 * section label because that is what every screen already uses it for.
 */
val SentinelTypography = Typography(
    // Screen name in the top bar: 18 / 600.
    titleLarge = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W600,
        fontSize = 18.sp,
    ),
    // Card headline ("Sensor Mode is on", "Good separation"): 16.5 / 700.
    titleMedium = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W700,
        fontSize = 16.5.sp,
    ),
    // Row titles and body: 14.5 / 400.
    bodyLarge = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W400,
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
    ),
    // Key–value rows and explanatory copy: 13.5 / 400.
    bodyMedium = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W400,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    // Secondary line under a row title: 12 / 400.
    bodySmall = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    // Buttons: 14.5 / 600.
    labelLarge = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W600,
        fontSize = 14.5.sp,
    ),
    // Pills and small chips: 11.5 / 700.
    labelMedium = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W700,
        fontSize = 11.5.sp,
    ),
    // Spaced-caps section label: 10.5 / 700 / .14em. Screens render it uppercased.
    labelSmall = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W700,
        fontSize = 10.5.sp,
        letterSpacing = 0.14.em,
    ),
    // Large readouts ("−31 dB", stat cells): thin and wide, per the artboards.
    headlineMedium = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W200,
        fontSize = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W600,
        fontSize = 23.sp,
    ),
    // "Clap now" — the calibration stage headline: 25 / 600.
    headlineLarge = TextStyle(
        fontFamily = Hanken,
        fontWeight = FontWeight.W600,
        fontSize = 25.sp,
    ),
)
