package com.grm.rotagrm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val StatusOperacionalColors = lightColorScheme(
    primary = Color(0xFF2E3192),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDF8),
    onPrimaryContainer = Color(0xFF202265),
    secondary = Color(0xFF2E3192),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDF8),
    onSecondaryContainer = Color(0xFF202265),
    tertiary = Color(0xFF2E3192),
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF172033),
    surface = Color.White,
    onSurface = Color(0xFF172033),
    surfaceVariant = Color(0xFFF4F4FB),
    onSurfaceVariant = Color(0xFF474A60),
    outline = Color(0xFF74778A),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val OperationalTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, lineHeight = 14.sp),
)

/** Tema aprovado, sem cores dinâmicas e sem dados de demonstração. */
@Composable
fun StatusOperacionalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StatusOperacionalColors, typography = OperationalTypography, content = content)
}
