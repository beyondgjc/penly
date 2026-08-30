package com.beyondguo.penly.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * v1 与小程序一致仅提供浅色主题（深色模式后续版本再做双套色板）。
 */
private val LightColors = lightColorScheme(
    primary = PenGreen,
    onPrimary = PenCard,
    primaryContainer = PenGreenSoft,
    onPrimaryContainer = PenGreenDark,
    secondary = PenInfo,
    background = PenBg,
    onBackground = PenText2,
    surface = PenCard,
    onSurface = PenText2,
    surfaceVariant = PenBgSoft,
    onSurfaceVariant = PenText3,
    error = PenDanger,
    outline = PenText4,
)

@Composable
fun PenlyTheme(content: @Composable () -> Unit) {
    // v2 视觉仅提供浅色主题（与小程序观感一致），深色模式待后续版本
    MaterialTheme(
        colorScheme = LightColors,
        typography = PenlyTypography,
        content = content,
    )
}
