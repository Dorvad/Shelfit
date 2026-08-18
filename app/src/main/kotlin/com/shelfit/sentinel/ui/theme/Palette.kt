package com.shelfit.sentinel.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The design's own vocabulary, from the hi-fi artboards (`Shelfit_HiFi_Blue`).
 *
 * Kept as one immutable object rather than scattered constants because the design ships
 * three gradient directions — Sapphire, Ocean, Midnight — that differ *only* in these
 * values. Swapping the whole palette is how a theme variant stays a one-line change.
 *
 * Naming follows the design tokens, not Material's roles, so a value can be traced
 * straight back to the artboard that defined it.
 */
@Immutable
data class ShelfPalette(
    /** Screen gradient, top to bottom: `linear-gradient(172deg, g1, g2 46%, g3)`. */
    val gradientTop: Color,
    val gradientMid: Color,
    val gradientBottom: Color,

    /** Accent gradient ends — buttons, toggles, checked marks, meter fill. */
    val accentStart: Color,
    val accentEnd: Color,

    /** Flat accent for section labels and text buttons. */
    val accent: Color,

    /** Status-good and the listening dot. The colour the app "breathes" in. */
    val cyan: Color,

    /** The ambient face's radial glow. Already carries its design alpha. */
    val ambientGlow: Color,

    /** Text tiers: primary, secondary, hint. */
    val text: Color,
    val textDim: Color,
    val textFaint: Color,

    /** Hairline separators. */
    val line: Color,

    /** Attention without alarm — the health screen's "worth fixing". */
    val warn: Color,
) {
    /** Standard card fill and border: `rgba(126,166,255,.06)` on `.13`. */
    val cardFill: Color get() = Color(0x0F7EA6FF)
    val cardLine: Color get() = Color(0x217EA6FF)

    /** Hero card border — brighter than the standard card. */
    val heroLine: Color get() = Color(0x4782B9FF)

    /** Outline button border and label: `rgba(150,185,240,.32)` / `#CFE0FF`. */
    val outline: Color get() = Color(0x5296B9F0)
    val outlineText: Color get() = Color(0xFFCFE0FF)

    /** Ink used on top of the accent gradient (buttons, checkboxes). */
    val onAccent: Color get() = Color(0xFF051129)

    /** The near-black ambient screen ground. Deliberately not pure black. */
    val ambientGround: Color get() = Color(0xFF010309)
}

/** The shipped direction — the "Blue" in the design file's name. */
val Sapphire = ShelfPalette(
    gradientTop = Color(0xFF152A52),
    gradientMid = Color(0xFF0C1A36),
    gradientBottom = Color(0xFF070F22),
    accentStart = Color(0xFF4E8DF6),
    accentEnd = Color(0xFF7CC4FF),
    accent = Color(0xFF7FB2FF),
    cyan = Color(0xFF86E2FF),
    ambientGlow = Color(0x8C2B58B9),
    text = Color(0xFFEAF1FF),
    textDim = Color(0xFF98ACD3),
    textFaint = Color(0xFF61789F),
    line = Color(0x247EA6FF),
    warn = Color(0xFFFFC38B),
)

/** Teal variant. Unused by default; a palette swap away. */
val Ocean = ShelfPalette(
    gradientTop = Color(0xFF0D2C44),
    gradientMid = Color(0xFF0A1D32),
    gradientBottom = Color(0xFF061323),
    accentStart = Color(0xFF2FA9D9),
    accentEnd = Color(0xFF66E3EA),
    accent = Color(0xFF5FC8E8),
    cyan = Color(0xFF74EAD4),
    ambientGlow = Color(0x8C166082),
    text = Color(0xFFEAF1FF),
    textDim = Color(0xFF98ACD3),
    textFaint = Color(0xFF61789F),
    line = Color(0x247EA6FF),
    warn = Color(0xFFFFC38B),
)

/** Indigo variant. Unused by default; a palette swap away. */
val Midnight = ShelfPalette(
    gradientTop = Color(0xFF1C2254),
    gradientMid = Color(0xFF12163B),
    gradientBottom = Color(0xFF0A0C26),
    accentStart = Color(0xFF7D8FFF),
    accentEnd = Color(0xFFAFC8FF),
    accent = Color(0xFF96A8FF),
    cyan = Color(0xFFAEC2FF),
    ambientGlow = Color(0x804C56C8),
    text = Color(0xFFEAF1FF),
    textDim = Color(0xFF98ACD3),
    textFaint = Color(0xFF61789F),
    line = Color(0x247EA6FF),
    warn = Color(0xFFFFC38B),
)

val LocalShelfPalette = staticCompositionLocalOf { Sapphire }
