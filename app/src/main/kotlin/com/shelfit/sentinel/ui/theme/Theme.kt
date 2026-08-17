package com.shelfit.sentinel.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback scheme for Android 10/11, which have no Material You colour extraction.
private val SentinelLightColors = lightColorScheme(
    primary = Color(0xFF00639B),
    secondary = Color(0xFF50606E),
    tertiary = Color(0xFF65587B),
)

private val SentinelDarkColors = darkColorScheme(
    primary = Color(0xFF97CBFF),
    secondary = Color(0xFFB7C9D9),
    tertiary = Color(0xFFCFC0E8),
)

@Composable
fun SentinelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> SentinelDarkColors
        else -> SentinelLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
