package com.example.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeonCyan = Color(0xFF00E5FF)
val DeepNavy = Color(0xFF0A0E17)
val DarkSurface = Color(0xFF131A29)
val NeonPurple = Color(0xFFB388FF)
val ElectricBlue = Color(0xFF2979FF)
val TextWhite = Color(0xFFECEFF1)
val TextMuted = Color(0xFF90A4AE)

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = DeepNavy,
    primaryContainer = Color(0xFF004D5A),
    onPrimaryContainer = Color(0xFF80F0FF),
    secondary = NeonPurple,
    onSecondary = DeepNavy,
    background = DeepNavy,
    onBackground = TextWhite,
    surface = DarkSurface,
    onSurface = TextWhite,
    surfaceVariant = Color(0xFF1E283D),
    onSurfaceVariant = TextMuted
)

@Composable
fun AffyTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
