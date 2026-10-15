package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AffyColorScheme = darkColorScheme(
    primary = CyanCore,
    onPrimary = SpaceBlack,
    primaryContainer = SurfaceVariantDark,
    onPrimaryContainer = CyanAccent,
    secondary = NeonViolet,
    onSecondary = SpaceBlack,
    tertiary = EmeraldActive,
    background = SpaceBlack,
    onBackground = TextPrimary,
    surface = ObsidianDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = TextSecondary,
    error = CrimsonError,
    onError = SpaceBlack
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // AFFY is a futuristic dark-first experience
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AffyColorScheme,
        typography = Typography,
        content = content
    )
}
