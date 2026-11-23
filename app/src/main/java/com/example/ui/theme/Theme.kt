package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

@Immutable
data class StudioPalette(
    val isDark: Boolean,
    val vocalAccent: Color,
    val vocalContainer: Color,
    val instrumentalAccent: Color,
    val instrumentalContainer: Color,
    val successAccent: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color,
    val mutedText: Color
)

val LocalStudioPalette = staticCompositionLocalOf {
    StudioPalette(
        isDark = true,
        vocalAccent = VocalRoseDark,
        vocalContainer = VocalContainerDark,
        instrumentalAccent = CyanPrimaryDark,
        instrumentalContainer = CyanContainerDark,
        successAccent = MintTertiaryDark,
        surfaceElevated = DarkSurfaceElevated,
        borderSubtle = DarkBorderSubtle,
        mutedText = DarkOnSurfaceMuted
    )
}

private val StudioDarkColorScheme = darkColorScheme(
    primary = CyanPrimaryDark,
    onPrimary = OnCyanPrimaryDark,
    primaryContainer = CyanContainerDark,
    onPrimaryContainer = CyanPrimaryDark,
    secondary = VocalRoseDark,
    onSecondary = OnVocalRoseDark,
    secondaryContainer = VocalContainerDark,
    onSecondaryContainer = VocalRoseDark,
    tertiary = MintTertiaryDark,
    onTertiary = OnMintTertiaryDark,
    tertiaryContainer = MintContainerDark,
    onTertiaryContainer = MintTertiaryDark,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceMuted,
    outline = DarkBorderSubtle,
    error = ErrorDark,
    onError = OnErrorDark
)

private val StudioLightColorScheme = lightColorScheme(
    primary = CyanPrimaryLight,
    onPrimary = OnCyanPrimaryLight,
    primaryContainer = CyanContainerLight,
    onPrimaryContainer = CyanPrimaryLight,
    secondary = VocalRoseLight,
    onSecondary = OnVocalRoseLight,
    secondaryContainer = VocalContainerLight,
    onSecondaryContainer = VocalRoseLight,
    tertiary = MintTertiaryLight,
    onTertiary = OnMintTertiaryLight,
    tertiaryContainer = MintContainerLight,
    onTertiaryContainer = MintTertiaryLight,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceMuted,
    outline = LightBorderSubtle,
    error = ErrorLight,
    onError = OnErrorLight
)

val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun VocalStudioTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    val colorScheme = if (useDark) StudioDarkColorScheme else StudioLightColorScheme
    val studioPalette = if (useDark) {
        StudioPalette(
            isDark = true,
            vocalAccent = VocalRoseDark,
            vocalContainer = VocalContainerDark,
            instrumentalAccent = CyanPrimaryDark,
            instrumentalContainer = CyanContainerDark,
            successAccent = MintTertiaryDark,
            surfaceElevated = DarkSurfaceElevated,
            borderSubtle = DarkBorderSubtle,
            mutedText = DarkOnSurfaceMuted
        )
    } else {
        StudioPalette(
            isDark = false,
            vocalAccent = VocalRoseLight,
            vocalContainer = VocalContainerLight,
            instrumentalAccent = CyanPrimaryLight,
            instrumentalContainer = CyanContainerLight,
            successAccent = MintTertiaryLight,
            surfaceElevated = LightSurfaceElevated,
            borderSubtle = LightBorderSubtle,
            mutedText = LightOnSurfaceMuted
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDark
        }
    }

    CompositionLocalProvider(LocalStudioPalette provides studioPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = StudioShapes,
            content = content
        )
    }
}
