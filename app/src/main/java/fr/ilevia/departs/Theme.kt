package fr.ilevia.departs

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5FD6), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF), onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF4A5A7A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE5F7), onSecondaryContainer = Color(0xFF0F1B33),
    tertiary = Color(0xFF8A4B00), tertiaryContainer = Color(0xFFFFDCBE), onTertiaryContainer = Color(0xFF2D1600),
    background = Color(0xFFF4F6FB), onBackground = Color(0xFF14161C),
    surface = Color(0xFFF4F6FB), onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFE3E7F1), onSurfaceVariant = Color(0xFF4A4F5E),
    outline = Color(0xFF7A8090), outlineVariant = Color(0xFFD5DAE6),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFEEF1F8), surfaceContainerHigh = Color(0xFFE8ECF4),
    surfaceContainerHighest = Color(0xFFE2E6F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB9FF), onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF1B4494), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB4C3E3), onSecondary = Color(0xFF1E2F4D),
    secondaryContainer = Color(0xFF334565), onSecondaryContainer = Color(0xFFDDE5F7),
    tertiary = Color(0xFFFFB870), tertiaryContainer = Color(0xFF693700), onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF0E1015), onBackground = Color(0xFFE4E6EE),
    surface = Color(0xFF0E1015), onSurface = Color(0xFFE4E6EE),
    surfaceVariant = Color(0xFF2B2F3A), onSurfaceVariant = Color(0xFFB4B9C8),
    outline = Color(0xFF8B91A1), outlineVariant = Color(0xFF2F3340),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF5E1612), onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF16181F), surfaceContainerLow = Color(0xFF1A1D25),
    surfaceContainer = Color(0xFF20232C), surfaceContainerHigh = Color(0xFF262A34),
    surfaceContainerHighest = Color(0xFF2C303B),
)

private val base = Typography()
private val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun IleviaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
