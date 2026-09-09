package com.cardrw.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = CardBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E6F5),
    onPrimaryContainer = Color(0xFF062333),
    secondary = CardBlueLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3F2FD),
    onSecondaryContainer = Color(0xFF0B3D5C),
    tertiary = CardTeal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1F0E0),
    onTertiaryContainer = Color(0xFF0B3D24),
    background = CardSurface,
    onBackground = Color(0xFF12202C),
    surface = Color.White,
    onSurface = Color(0xFF12202C),
    surfaceVariant = Color(0xFFE8EEF3),
    onSurfaceVariant = Color(0xFF4A5B6A),
    outline = Color(0xFF8A9AAB),
    outlineVariant = Color(0xFFCDD6DE),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = CardBlueLight,
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF0B3D5C),
    onPrimaryContainer = Color(0xFFD4E6F5),
    secondary = Color(0xFF7EC2F5),
    onSecondary = Color(0xFF00344F),
    secondaryContainer = Color(0xFF1A3144),
    onSecondaryContainer = Color(0xFFD4E6F5),
    tertiary = Color(0xFF6FCF97),
    onTertiary = Color(0xFF0B3D24),
    tertiaryContainer = Color(0xFF1B4D34),
    onTertiaryContainer = Color(0xFFD1F0E0),
    background = Color(0xFF0A1620),
    onBackground = Color(0xFFE4EDF4),
    surface = Color(0xFF122233),
    onSurface = Color(0xFFE4EDF4),
    surfaceVariant = Color(0xFF1A3144),
    onSurfaceVariant = Color(0xFFB7C5D1),
    outline = Color(0xFF6B7C8C),
    outlineVariant = Color(0xFF2A4154),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun CardRwTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
