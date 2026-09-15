package com.dsh.gequbao.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1E88FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E7FF),
    onPrimaryContainer = Color(0xFF00203F),
    secondary = Color(0xFF2FA36B),
    surface = Color(0xFFFDFDFD),
    surfaceVariant = Color(0xFFF1F3F6),
    background = Color(0xFFF7F8FA),
    onSurface = Color(0xFF17181A),
    onSurfaceVariant = Color(0xFF6B7076),
    outline = Color(0xFFD8DCE2)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7DB8FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF0F3D68),
    onPrimaryContainer = Color(0xFFD5E7FF),
    secondary = Color(0xFF6BD8A1),
    surface = Color(0xFF17181C),
    surfaceVariant = Color(0xFF23262B),
    background = Color(0xFF101114),
    onSurface = Color(0xFFE9EAEC),
    onSurfaceVariant = Color(0xFF9BA1A8),
    outline = Color(0xFF3A3E45)
)

@Composable
fun AppTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        content = content
    )
}
