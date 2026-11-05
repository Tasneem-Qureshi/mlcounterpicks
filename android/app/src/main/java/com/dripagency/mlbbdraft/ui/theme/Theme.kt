package com.dripagency.mlbbdraft.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// AMOLED: true-black background and surfaces; cards/nav bar lift to a very dark grey
// so edges stay visible without lighting up pixels.
private val Black = Color(0xFF000000)
private val Lift1 = Color(0xFF0A0A0A)
private val Lift2 = Color(0xFF111111)
private val Lift3 = Color(0xFF181818)
private val Lift4 = Color(0xFF202020)

private val Scheme = darkColorScheme(
    primary = Color(0xFF59C3FF),
    onPrimary = Color(0xFF00223A),
    primaryContainer = Color(0xFF0E2C40),
    onPrimaryContainer = Color(0xFFBFE6FF),
    secondary = Color(0xFFFFC95C),
    onSecondary = Color(0xFF2E2000),
    secondaryContainer = Color(0xFF3A2D00),
    onSecondaryContainer = Color(0xFFFFE8B0),
    background = Black,
    onBackground = Color(0xFFE6EAF0),
    surface = Black,
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Lift2,
    onSurfaceVariant = Color(0xFF9AA4B2),
    surfaceContainerLowest = Black,
    surfaceContainerLow = Lift1,
    surfaceContainer = Lift2,
    surfaceContainerHigh = Lift3,
    surfaceContainerHighest = Lift4,
    surfaceTint = Black,            // no tonal tint → elevated surfaces stay black-based
    outline = Color(0xFF3A3F47),
    outlineVariant = Color(0xFF22262C),
    error = Color(0xFFFF8A65),
)

@Composable
fun MlbbDraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
