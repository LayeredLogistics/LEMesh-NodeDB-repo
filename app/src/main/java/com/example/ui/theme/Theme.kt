package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TacticalDarkColorScheme = darkColorScheme(
    primary = NodeDirectGreen,
    onPrimary = TacticalCanvas,
    primaryContainer = TacticalSurfaceElevated,
    onPrimaryContainer = TextPrimary,
    secondary = NodeMultiHopSky,
    onSecondary = TacticalCanvas,
    secondaryContainer = TacticalSurfaceBright,
    onSecondaryContainer = TextPrimary,
    tertiary = NodeSelfAmber,
    onTertiary = TacticalCanvas,
    background = TacticalCanvas,
    onBackground = TextPrimary,
    surface = TacticalSurface,
    onSurface = TextPrimary,
    surfaceVariant = TacticalSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = TacticalOutline,
    outlineVariant = TacticalOutlineSubtle,
    error = AlertErrorRed,
    onError = TextPrimary
)

@Composable
fun LEMeshTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TacticalDarkColorScheme,
        typography = Typography,
        content = content
    )
}
