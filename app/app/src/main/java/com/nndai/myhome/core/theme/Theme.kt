package com.nndai.myhome.core.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Full color schemes: every slot is set explicitly so Material3 components
// (AlertDialog, DropdownMenu, TextField...) never fall back to the default
// purple/pink baseline palette.
private val DarkColorScheme = darkColorScheme(
    primary = CyanBlue,
    onPrimary = DeepNavy,
    primaryContainer = CyanBlueVariant,
    onPrimaryContainer = LightText,
    secondary = GreenOk,
    onSecondary = DeepNavy,
    secondaryContainer = GreenDark,
    onSecondaryContainer = LightText,
    tertiary = OrangeWarning,
    onTertiary = DeepNavy,
    tertiaryContainer = OrangeDeep,
    onTertiaryContainer = LightText,
    error = RedError,
    onError = DeepNavy,
    errorContainer = RedDarkContainer,
    onErrorContainer = RedLightContainer,
    background = DeepNavy,
    onBackground = LightText,
    surface = CardSurface,
    onSurface = LightText,
    surfaceVariant = ElevatedSurface,
    onSurfaceVariant = SecondaryText,
    surfaceDim = DeepNavy,
    surfaceBright = ElevatedSurface,
    surfaceContainerLowest = Color(0xFF0A121A),
    surfaceContainerLow = Color(0xFF16222F),
    surfaceContainer = CardSurface,
    surfaceContainerHigh = ElevatedSurface,
    surfaceContainerHighest = DividerColor,
    inverseSurface = ElevatedSurface,
    inverseOnSurface = LightText,
    inversePrimary = CyanBlue,
    outline = DividerColor,
    outlineVariant = DimText,
    scrim = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnPrimary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = Color(0xFF3E2723),
    error = LightError,
    onError = LightOnError,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightElevatedSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceDim = Color(0xFFDBDFE6),
    surfaceBright = LightSurface,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightElevatedSurface,
    surfaceContainerHigh = Color(0xFFE4E8EF),
    surfaceContainerHighest = Color(0xFFD9DEE6),
    inverseSurface = LightElevatedSurface,
    inverseOnSurface = LightOnSurface,
    inversePrimary = LightPrimary,
    outline = LightOutline,
    outlineVariant = LightOnSurfaceVariant,
    scrim = Color.Black
)

@Composable
fun RemotePumpTheme(
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
