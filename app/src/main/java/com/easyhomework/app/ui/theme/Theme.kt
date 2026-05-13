package com.easyhomework.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryPurple,
    onPrimary = Color.White,
    primaryContainer = PrimaryPurple.copy(alpha = 0.3f),
    onPrimaryContainer = PrimaryPurple,
    secondary = PrimaryBlue,
    onSecondary = Color.White,
    secondaryContainer = PrimaryBlue.copy(alpha = 0.2f),
    onSecondaryContainer = PrimaryBlue,
    tertiary = AccentCyan,
    onTertiary = Color.White,
    tertiaryContainer = AccentCyan.copy(alpha = 0.2f),
    onTertiaryContainer = AccentCyan,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Color(0xFF0D0D18),
    surfaceContainerLow = Color(0xFF141425),
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceContainerHighest = Color(0xFF2A2A48),
    error = AccentRed,
    onError = Color.White,
    errorContainer = AccentRed.copy(alpha = 0.15f),
    onErrorContainer = AccentRed,
    outline = TextTertiary,
    outlineVariant = Color(0xFF333350),
    inverseSurface = TextPrimary,
    inverseOnSurface = DarkBackground,
    inversePrimary = PrimaryPurple.copy(alpha = 0.8f),
    scrim = Color.Black,
)

@Composable
fun EasyHomeworkTheme(
    content: @Composable () -> Unit,
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBackground.toArgb()
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
