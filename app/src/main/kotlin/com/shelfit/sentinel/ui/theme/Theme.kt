package com.shelfit.sentinel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The app commits to one visual world: a deep blue night, always.
 *
 * Deliberately not system-theme-following and not Material You. The product is a phone
 * that sits on a shelf pretending to be a decor object, and a decor object does not turn
 * white at sunrise or recolour itself to match the wallpaper. The hi-fi design is built
 * on that premise; a light variant of it would be a different design, not a theme flip.
 *
 * Two layers are provided:
 *
 * - [LocalShelfPalette] — the design's own tokens, used by everything in `ui/components`
 *   and the screens. This is the source of truth.
 * - A Material [darkColorScheme] mapped *from* the palette, so any stock Material widget
 *   still on a screen (text fields, progress indicators) lands inside the same world
 *   instead of Material baseline purple.
 */
@Composable
fun SentinelTheme(
    palette: ShelfPalette = Sapphire,
    content: @Composable () -> Unit,
) {
    val colorScheme = darkColorScheme(
        primary = palette.accentEnd,
        onPrimary = palette.onAccent,
        primaryContainer = palette.accentStart,
        onPrimaryContainer = palette.text,
        secondary = palette.accent,
        onSecondary = palette.onAccent,
        tertiary = palette.cyan,
        onTertiary = palette.onAccent,
        background = palette.gradientBottom,
        onBackground = palette.text,
        // Screens paint their own gradient; Material surfaces sit transparent-ish above it.
        surface = palette.gradientBottom,
        onSurface = palette.text,
        surfaceVariant = palette.cardFill,
        onSurfaceVariant = palette.textDim,
        surfaceContainer = palette.cardFill,
        surfaceContainerHigh = palette.cardFill,
        surfaceContainerHighest = Color34,
        outline = palette.outline,
        outlineVariant = palette.line,
        // The design has no red: attention is amber, and losing data is the only true error.
        error = palette.warn,
        onError = palette.onAccent,
    )

    CompositionLocalProvider(LocalShelfPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SentinelTypography,
            content = content,
        )
    }
}

/** Meter tracks and other "well" fills: `rgba(126,166,255,.12)`. */
private val Color34 = androidx.compose.ui.graphics.Color(0x1F7EA6FF)

/** Shorthand the screens read the design tokens through. */
object Shelf {
    val palette: ShelfPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalShelfPalette.current
}
