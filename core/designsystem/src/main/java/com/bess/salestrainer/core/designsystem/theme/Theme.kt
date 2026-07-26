package com.bess.salestrainer.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = BessBlue40,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = BessBlue90,
    onPrimaryContainer = BessBlue10,
    secondary = BessTeal40,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = BessTeal90,
    onSecondaryContainer = BessBlue10,
    tertiary = BessAmber40,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = BessAmber90,
    onTertiaryContainer = BessBlue10,
    error = BessError40,
    errorContainer = BessError90,
    background = BessNeutral99,
    onBackground = BessNeutral10,
    surface = BessNeutral99,
    onSurface = BessNeutral10,
    surfaceVariant = BessNeutral95,
    onSurfaceVariant = BessBlue20,
)

private val DarkColors = darkColorScheme(
    primary = BessBlue80,
    onPrimary = BessBlue20,
    primaryContainer = BessBlue30,
    onPrimaryContainer = BessBlue90,
    secondary = BessTeal80,
    onSecondary = BessBlue20,
    secondaryContainer = BessTeal40,
    onSecondaryContainer = BessTeal90,
    tertiary = BessAmber80,
    onTertiary = BessBlue20,
    tertiaryContainer = BessAmber40,
    onTertiaryContainer = BessAmber90,
    error = BessError80,
    errorContainer = BessError40,
    background = BessNeutral10,
    onBackground = BessNeutral90,
    surface = BessNeutral10,
    onSurface = BessNeutral90,
    surfaceVariant = BessBlue20,
    onSurfaceVariant = BessNeutral90,
)

/**
 * App theme. Dynamic color is disabled to keep a stable, brand-consistent look
 * and predictable accessibility contrast across devices.
 */
@Composable
fun BessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BessTypography,
        content = content,
    )
}
