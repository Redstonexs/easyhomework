package com.easyhomework.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

data class NeutralPalette(
    val isDark: Boolean,
    val scrim: Int,
    val background: Int,
    val surface: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val tertiary: Int,
    val tertiaryContainer: Int,
    val success: Int,
    val successContainer: Int,
    val warning: Int,
    val error: Int,
    val errorContainer: Int,
)

fun neutralPalette(context: Context): NeutralPalette {
    val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return if (isDark) {
        NeutralPalette(
            isDark = true,
            scrim = Color.parseColor("#E6000000"),
            background = Color.BLACK,
            surface = Color.parseColor("#101010"),
            surfaceContainer = Color.parseColor("#171717"),
            surfaceContainerHigh = Color.parseColor("#242424"),
            surfaceContainerHighest = Color.parseColor("#303030"),
            onSurface = Color.parseColor("#F5F5F5"),
            onSurfaceVariant = Color.parseColor("#BDBDBD"),
            outline = Color.parseColor("#777777"),
            outlineVariant = Color.parseColor("#3A3A3A"),
            primary = Color.WHITE,
            onPrimary = Color.BLACK,
            tertiary = Color.parseColor("#90A4AE"),
            tertiaryContainer = Color.parseColor("#2F3A40"),
            success = Color.parseColor("#81A887"),
            successContainer = Color.parseColor("#243328"),
            warning = Color.parseColor("#C6A15B"),
            error = Color.parseColor("#F2B8B5"),
            errorContainer = Color.parseColor("#3C1D1B"),
        )
    } else {
        NeutralPalette(
            isDark = false,
            scrim = Color.parseColor("#99000000"),
            background = Color.WHITE,
            surface = Color.parseColor("#FAFAFA"),
            surfaceContainer = Color.parseColor("#F2F2F2"),
            surfaceContainerHigh = Color.parseColor("#E8E8E8"),
            surfaceContainerHighest = Color.parseColor("#DADADA"),
            onSurface = Color.parseColor("#111111"),
            onSurfaceVariant = Color.parseColor("#555555"),
            outline = Color.parseColor("#8A8A8A"),
            outlineVariant = Color.parseColor("#D6D6D6"),
            primary = Color.parseColor("#111111"),
            onPrimary = Color.WHITE,
            tertiary = Color.parseColor("#607D8B"),
            tertiaryContainer = Color.parseColor("#E4ECEF"),
            success = Color.parseColor("#4F7D59"),
            successContainer = Color.parseColor("#E4EEE6"),
            warning = Color.parseColor("#9A6A16"),
            error = Color.parseColor("#B3261E"),
            errorContainer = Color.parseColor("#F9DEDC"),
        )
    }
}
