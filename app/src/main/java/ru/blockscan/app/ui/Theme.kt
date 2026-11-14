package ru.blockscan.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.blockscan.app.App
import ru.blockscan.app.domain.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C86), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFF0), onPrimaryContainer = Color(0xFF00363B),
    secondary = Color(0xFF3B5BA9), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE4FA), onSecondaryContainer = Color(0xFF0B1F4D),
    tertiary = Color(0xFF7A5900), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA3), onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFF4FAFB), onBackground = Color(0xFF0F1A1D),
    surface = Color(0xFFF4FAFB), onSurface = Color(0xFF0F1A1D),
    surfaceVariant = Color(0xFFDAE5E8), onSurfaceVariant = Color(0xFF3B494D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEDF4F6),
    surfaceContainer = Color(0xFFE7EFF1),
    surfaceContainerHigh = Color(0xFFE1E9EC),
    surfaceContainerHighest = Color(0xFFDBE4E7),
    outline = Color(0xFF6B7B80), outlineVariant = Color(0xFFBFC9CC),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0B3B3F), onPrimaryContainer = Color(0xFFB8F2EC),
    secondary = Color(0xFF9DB4F0), onSecondary = Color(0xFF0B1F4D),
    secondaryContainer = Color(0xFF223463), onSecondaryContainer = Color(0xFFDCE4FA),
    tertiary = Color(0xFFF3C14B), onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200), onTertiaryContainer = Color(0xFFFFDEA3),
    background = Color(0xFF0B1418), onBackground = Color(0xFFE3EDEF),
    surface = Color(0xFF0B1418), onSurface = Color(0xFFE3EDEF),
    surfaceVariant = Color(0xFF1B2A30), onSurfaceVariant = Color(0xFFB4C4C9),
    surfaceContainerLowest = Color(0xFF060E11),
    surfaceContainerLow = Color(0xFF111C21),
    surfaceContainer = Color(0xFF152127),
    surfaceContainerHigh = Color(0xFF1B2A30),
    surfaceContainerHighest = Color(0xFF22333A),
    outline = Color(0xFF7F9096), outlineVariant = Color(0xFF3B494D),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val settings by (context.applicationContext as App).container.settings.flow.collectAsStateWithLifecycle()
    val useDynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        useDynamic && dark -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
