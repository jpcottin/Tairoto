package com.jpcottin.drifttank.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours of the water: deep teal glass over the tank, sea-glass highlights, warm sand accents. */
object Ocean {
    val Abyss = Color(0xFF041A20)
    val Deep = Color(0xFF06232B)
    val Teal = Color(0xFF0E4A52)
    val SeaGlass = Color(0xFF7FE3D2)
    val Foam = Color(0xFFE6FBF6)
    val Sand = Color(0xFFE9C98B)
    val Coral = Color(0xFFFF8A65)
    val Mist = Color(0xFF9FC3C2)

    /** Frosted glass panels floating over the tank. */
    val Glass = Color(0x99062A31)
    val GlassStrong = Color(0xE6062A31)
    val GlassEdge = Color(0x33E6FBF6)
}

val GlassBorder = BorderStroke(1.dp, Ocean.GlassEdge)

private val scheme = darkColorScheme(
    primary = Ocean.SeaGlass,
    onPrimary = Ocean.Abyss,
    primaryContainer = Ocean.Teal,
    onPrimaryContainer = Ocean.Foam,
    secondary = Ocean.Sand,
    onSecondary = Ocean.Abyss,
    secondaryContainer = Color(0xFF3B3220),
    onSecondaryContainer = Ocean.Sand,
    tertiary = Ocean.Coral,
    background = Ocean.Abyss,
    onBackground = Ocean.Foam,
    surface = Ocean.Deep,
    onSurface = Ocean.Foam,
    surfaceVariant = Color(0xFF123A41),
    onSurfaceVariant = Ocean.Mist,
    surfaceContainerLowest = Ocean.Abyss,
    surfaceContainerLow = Color(0xFF082A32),
    surfaceContainer = Color(0xFF0A3038),
    surfaceContainerHigh = Color(0xFF0D3840),
    surfaceContainerHighest = Color(0xFF114149),
    outline = Color(0xFF3F6E72),
    outlineVariant = Color(0xFF214A50),
)

private val base = Typography()

private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    labelMedium = base.labelMedium.copy(letterSpacing = 0.8.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
)

/** Monospace figures for the performance overlay. */
val StatsText = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = Ocean.Foam)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun DriftTankTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
