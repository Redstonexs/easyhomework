package com.easyhomework.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF2F2F2F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2F2F2),
    onSecondaryContainer = Color(0xFF111111),
    tertiary = AccentCyan,
    onTertiary = Color.White,
    tertiaryContainer = AccentCyan.copy(alpha = 0.16f),
    onTertiaryContainer = AccentCyan,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = LightCard,
    surfaceContainerHigh = LightSurfaceVariant,
    surfaceContainerHighest = Color(0xFFDADADA),
    error = AccentRed,
    onError = Color.White,
    errorContainer = AccentRed.copy(alpha = 0.12f),
    onErrorContainer = AccentRed,
    outline = LightTextTertiary,
    outlineVariant = Color(0xFFD6D6D6),
    inverseSurface = Color(0xFF111111),
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFFE8E8E8),
    scrim = Color.Black,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF242424),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFE0E0E0),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF171717),
    onSecondaryContainer = Color.White,
    tertiary = AccentCyan,
    onTertiary = Color.White,
    tertiaryContainer = AccentCyan.copy(alpha = 0.20f),
    onTertiaryContainer = AccentCyan,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF101010),
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceContainerHighest = Color(0xFF303030),
    error = Color(0xFFF2B8B5),
    onError = Color.Black,
    errorContainer = Color(0xFF3C1D1B),
    onErrorContainer = Color(0xFFF2B8B5),
    outline = TextTertiary,
    outlineVariant = Color(0xFF3A3A3A),
    inverseSurface = TextPrimary,
    inverseOnSurface = DarkBackground,
    inversePrimary = Color(0xFF111111),
    scrim = Color.Black,
)

@Composable
fun EasyHomeworkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
