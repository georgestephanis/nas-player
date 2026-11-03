package dev.atv.nasplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

private val Accent = Color(0xFF4FC3F7)
private val Background = Color(0xFF0E1116)
private val SurfaceColor = Color(0xFF1A1F27)

@Composable
fun NasTheme(content: @Composable () -> Unit) {
    // TV Material for focusable TV components, Material 3 for text input.
    M3Theme(
        colorScheme = m3DarkColorScheme(
            primary = Accent,
            background = Background,
            surface = SurfaceColor,
            surfaceVariant = SurfaceColor,
        )
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                onPrimary = Color.Black,
                background = Background,
                surface = SurfaceColor,
                surfaceVariant = Color(0xFF252B35),
            ),
            content = content,
        )
    }
}
