package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val TeenaDarkColorScheme = darkColorScheme(
    primary = NeuralCyan,
    onPrimary = ObsidianVoid,
    primaryContainer = NeuralCyanContainer,
    onPrimaryContainer = NeuralCyan,
    secondary = NeuralViolet,
    onSecondary = TextPrimaryDark,
    secondaryContainer = NeuralVioletContainer,
    onSecondaryContainer = Color(0xFFDDD6FE),
    tertiary = NeuralMint,
    onTertiary = ObsidianVoid,
    tertiaryContainer = NeuralMintContainer,
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = ObsidianVoid,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianCard,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianCard,
    surfaceContainerHigh = ObsidianElevated,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF1E2B48),
    error = StatusCrimson,
    onError = TextPrimaryDark,
    errorContainer = StatusCrimsonContainer,
    onErrorContainer = Color(0xFFFECACA)
)

private val TeenaLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFFAFE),
    onPrimaryContainer = Color(0xFF083344),
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    background = LightBg,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightCard,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White
)

val TeenaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TeenaTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) TeenaDarkColorScheme else TeenaLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = TeenaTypography,
        shapes = TeenaShapes,
        content = content
    )
}
