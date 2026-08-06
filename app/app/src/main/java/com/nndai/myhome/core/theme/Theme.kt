package com.nndai.myhome.core.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = CyanBlue,
    onPrimary = DeepNavy,
    primaryContainer = CyanBlueVariant,
    onPrimaryContainer = LightText,
    secondary = GreenOk,
    onSecondary = DeepNavy,
    secondaryContainer = GreenDark,
    onSecondaryContainer = LightText,
    error = RedError,
    onError = DeepNavy,
    background = DeepNavy,
    onBackground = LightText,
    surface = CardSurface,
    onSurface = LightText,
    surfaceVariant = ElevatedSurface,
    onSurfaceVariant = SecondaryText,
    inverseSurface = ElevatedSurface,
    inverseOnSurface = LightText,
    inversePrimary = CyanBlue,
    outline = DividerColor,
    outlineVariant = DimText
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
    error = LightError,
    onError = LightOnError,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightElevatedSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    inverseSurface = LightElevatedSurface,
    inverseOnSurface = LightOnSurface,
    inversePrimary = LightPrimary,
    outline = LightOutline,
    outlineVariant = LightOnSurfaceVariant
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
