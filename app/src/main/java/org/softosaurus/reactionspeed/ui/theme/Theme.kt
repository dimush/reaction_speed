package org.softosaurus.reactionspeed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The game's own identity: grass green, a red target accent and warm stone/sand neutrals —
 * the palette the playfield has been painted in since 1.0.
 *
 * Dynamic color is deliberately **not** used: the wallpaper-derived palette would repaint the
 * shell in colours that clash with the fixed grass-and-stone artwork of the game screen.
 */

// Grass.
private val GrassGreen = Color(0xFF3F6B36)
private val GrassGreenLight = Color(0xFF8CC97C)
private val GrassContainerLight = Color(0xFFC6E8B8)
private val GrassContainerDark = Color(0xFF2A4A24)

// Target.
private val TargetRed = Color(0xFFB3261E)
private val TargetRedLight = Color(0xFFFF8A7D)
private val TargetContainerLight = Color(0xFFFFDAD4)
private val TargetContainerDark = Color(0xFF7A1C16)

// Warm stone and sand.
private val SandLight = Color(0xFFFAF4E8)
private val SandSurfaceLight = Color(0xFFF2E9D8)
private val StoneDark = Color(0xFF14170F)
private val StoneSurfaceDark = Color(0xFF232717)
private val StoneInkLight = Color(0xFF1B1C17)
private val SandInkDark = Color(0xFFE4E3D7)
private val StoneOutlineLight = Color(0xFF7A7767)
private val StoneOutlineDark = Color(0xFF938F80)

private val LightColors = lightColorScheme(
    primary = GrassGreen,
    onPrimary = Color.White,
    primaryContainer = GrassContainerLight,
    onPrimaryContainer = Color(0xFF0B2007),
    secondary = Color(0xFF56624B),
    onSecondary = Color.White,
    secondaryContainer = SandSurfaceLight,
    onSecondaryContainer = Color(0xFF141E0D),
    tertiary = TargetRed,
    onTertiary = Color.White,
    tertiaryContainer = TargetContainerLight,
    onTertiaryContainer = Color(0xFF410002),
    error = TargetRed,
    onError = Color.White,
    errorContainer = TargetContainerLight,
    onErrorContainer = Color(0xFF410002),
    background = SandLight,
    onBackground = StoneInkLight,
    surface = SandLight,
    onSurface = StoneInkLight,
    surfaceVariant = SandSurfaceLight,
    onSurfaceVariant = Color(0xFF45483C),
    surfaceBright = Color(0xFFFDF8EE),
    surfaceDim = Color(0xFFDFD8C7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5EDDD),
    surfaceContainer = Color(0xFFF0E8D6),
    surfaceContainerHigh = Color(0xFFEAE1CD),
    surfaceContainerHighest = Color(0xFFE4DAC3),
    outline = StoneOutlineLight,
    outlineVariant = Color(0xFFD5CDB9),
)

private val DarkColors = darkColorScheme(
    primary = GrassGreenLight,
    onPrimary = Color(0xFF123405),
    primaryContainer = GrassContainerDark,
    onPrimaryContainer = Color(0xFFC6E8B8),
    secondary = Color(0xFFBDCBB0),
    onSecondary = Color(0xFF283420),
    secondaryContainer = Color(0xFF3E4A35),
    onSecondaryContainer = Color(0xFFD9E7CB),
    tertiary = TargetRedLight,
    onTertiary = Color(0xFF5F1509),
    tertiaryContainer = TargetContainerDark,
    onTertiaryContainer = Color(0xFFFFDAD4),
    error = TargetRedLight,
    onError = Color(0xFF5F1509),
    errorContainer = TargetContainerDark,
    onErrorContainer = Color(0xFFFFDAD4),
    background = StoneDark,
    onBackground = SandInkDark,
    surface = StoneDark,
    onSurface = SandInkDark,
    surfaceVariant = StoneSurfaceDark,
    onSurfaceVariant = Color(0xFFC5C8B8),
    surfaceBright = Color(0xFF3A3E31),
    surfaceDim = Color(0xFF14170F),
    surfaceContainerLowest = Color(0xFF0E1109),
    surfaceContainerLow = Color(0xFF1C2014),
    surfaceContainer = Color(0xFF1F2318),
    surfaceContainerHigh = Color(0xFF2A2E22),
    surfaceContainerHighest = Color(0xFF35392C),
    outline = StoneOutlineDark,
    outlineVariant = Color(0xFF45483C),
)

@Composable
fun ReactionSpeedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
