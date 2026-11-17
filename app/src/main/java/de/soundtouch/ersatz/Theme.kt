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

// Warme, freundliche Farben: Koralle als Akzent, ruhiges Blaugrau als Ergänzung.
private val Coral = Color(0xFFE8604C)
private val CoralDark = Color(0xFFFFB4A6)

private val Light = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD3),
    onPrimaryContainer = Color(0xFF3E0500),
    secondary = Color(0xFF4F6372),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E5F5),
    onSecondaryContainer = Color(0xFF0B1D2A),
    tertiary = Color(0xFF6B5E10),
    tertiaryContainer = Color(0xFFF5E38A),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231917),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231917),
    surfaceVariant = Color(0xFFF5DDD9),
    onSurfaceVariant = Color(0xFF534340),
    surfaceContainerLow = Color(0xFFFFF0ED),
    surfaceContainer = Color(0xFFFCEAE6),
    surfaceContainerHigh = Color(0xFFF6E4E0),
    outline = Color(0xFF857370),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = CoralDark,
    onPrimary = Color(0xFF5F1509),
    primaryContainer = Color(0xFF7E2B1D),
    onPrimaryContainer = Color(0xFFFFDAD3),
    secondary = Color(0xFFB7C9D9),
    onSecondary = Color(0xFF213341),
    secondaryContainer = Color(0xFF384A59),
    onSecondaryContainer = Color(0xFFD3E5F5),
    tertiary = Color(0xFFD8C770),
    tertiaryContainer = Color(0xFF524600),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DFDB),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DFDB),
    surfaceVariant = Color(0xFF534340),
    onSurfaceVariant = Color(0xFFD8C2BE),
    surfaceContainerLow = Color(0xFF231917),
    surfaceContainer = Color(0xFF271D1B),
    surfaceContainerHigh = Color(0xFF322826),
    outline = Color(0xFFA08C89),
    error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
