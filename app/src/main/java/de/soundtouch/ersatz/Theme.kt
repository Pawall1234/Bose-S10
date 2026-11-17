package de.soundtouch.ersatz

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Schlicht und edel: Schwarz, Weiß und Grautöne.
private val Light = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E6),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF5C5C5C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF3A3A3A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDADADA),
    onTertiaryContainer = Color(0xFF111111),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEAEAEA),
    onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F4F4),
    surfaceContainer = Color(0xFFEFEFEF),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE2E2E2),
    outline = Color(0xFFB5B5B5),
    outlineVariant = Color(0xFFD6D6D6),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF2DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color.White,
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF2E2E2E),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFF9E9E9E),
    onTertiary = Color(0xFF0A0A0A),
    tertiaryContainer = Color(0xFF3A3A3A),
    onTertiaryContainer = Color.White,
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFF9E9E9E),
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF212121),
    surfaceContainerHighest = Color(0xFF2B2B2B),
    outline = Color(0xFF5E5E5E),
    outlineVariant = Color(0xFF3A3A3A),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light, lineHeight = 40.sp, letterSpacing = 0.5.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(50),
    small = RoundedCornerShape(50),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
