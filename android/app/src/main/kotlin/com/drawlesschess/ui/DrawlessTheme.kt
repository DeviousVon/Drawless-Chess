package com.drawlesschess.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF56C7A5),
    onPrimary = Color(0xFF052019),
    secondary = Color(0xFFE4C75A),
    background = Color(0xFF0C1216),
    surface = Color(0xFF151E24),
    surfaceVariant = Color(0xFF24323A),
    onSurface = Color(0xFFF2F5F6),
    error = Color(0xFFFF806D),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF236B58),
    onPrimary = Color.White,
    secondary = Color(0xFF765B00),
    background = Color(0xFFF4F7F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE1E9ED),
    onSurface = Color(0xFF172026),
    error = Color(0xFFB3261E),
)

@Composable
fun DrawlessTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
