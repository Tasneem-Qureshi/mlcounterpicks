package com.dripagency.mlbbdraft.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF59C3FF),
    onPrimary = Color(0xFF00223A),
    secondary = Color(0xFFFFC95C),
    onSecondary = Color(0xFF2E2000),
    background = Color(0xFF0B0F17),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF121826),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1B2335),
    onSurfaceVariant = Color(0xFF9AA4B2),
    error = Color(0xFFFF8A65),
)

@Composable
fun MlbbDraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
