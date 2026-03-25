package net.muratov.intercom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppScheme = darkColorScheme(
    primary = Color(0xFF69C7A3),
    secondary = Color(0xFF7AB6FF),
    tertiary = Color(0xFFF7C66A),
    background = Color(0xFF08111C),
    surface = Color(0xFF0F1A2A),
)

@Composable
fun IntercomTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AppScheme,
        content = content,
    )
}
