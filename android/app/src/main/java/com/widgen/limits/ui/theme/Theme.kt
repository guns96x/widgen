package com.widgen.limits.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF0B0D11)
val SurfaceDark = Color(0xFF14171E)
val SurfaceElevated = Color(0xFF1C212B)
val BorderDark = Color(0xFF282F3D)
val TextPrimary = Color(0xFFF3F4F6)
val TextSecondary = Color(0xFF9CA3AF)

val GeminiCyan = Color(0xFF00E5FF)
val ClaudePurple = Color(0xFFA855F7)
val StatusGreen = Color(0xFF10B981)
val StatusAmber = Color(0xFFF59E0B)
val StatusRose = Color(0xFFEF4444)

private val DarkColorScheme = darkColorScheme(
    primary = GeminiCyan,
    secondary = ClaudePurple,
    background = BgDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceElevated,
    outline = BorderDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)

@Composable
fun AntigravityLimitsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
