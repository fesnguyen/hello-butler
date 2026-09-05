package com.hellobutler.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ButlerInk = Color(0xFF17201D)
val ButlerForest = Color(0xFF285E4B)
val ButlerCream = Color(0xFFF8F6EF)
val ButlerAmber = Color(0xFFC6843B)

private val ButlerColors = lightColorScheme(
    primary = ButlerForest, onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EEE1), onPrimaryContainer = Color(0xFF12392D),
    secondary = Color(0xFF66583F), secondaryContainer = Color(0xFFE9DCC6),
    tertiary = ButlerAmber, background = ButlerCream, onBackground = ButlerInk,
    surface = Color(0xFFFFFDF7), onSurface = ButlerInk,
    surfaceVariant = Color(0xFFE9ECE6), outline = Color(0xFF747B75),
    outlineVariant = Color(0xFFD4D8D2), error = Color(0xFFA93D3D),
)

private val ButlerTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 38.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

@Composable
fun HelloButlerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ButlerColors, typography = ButlerTypography, content = content)
}
