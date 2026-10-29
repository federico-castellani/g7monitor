package it.federico.g7monitor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Glucose range colours: fixed, independent from the wallpaper-based scheme.
 * [meter]: blood glucose meter results, a hue none of the ranges use so they stand out on the chart.
 */
@Immutable
data class RangeColors(val low: Color, val inRange: Color, val high: Color, val stale: Color, val meter: Color)

private val LightRange = RangeColors(
    low = Color(0xFFD93A3F), inRange = Color(0xFF1F9D62), high = Color(0xFFD98A00), stale = Color(0xFF8A9099),
    meter = Color(0xFF8E44D9),
)
private val DarkRange = RangeColors(
    low = Color(0xFFFF6B6F), inRange = Color(0xFF4CD597), high = Color(0xFFFFC145), stale = Color(0xFF6E757E),
    meter = Color(0xFFC9A2FF),
)

val LocalRangeColors = staticCompositionLocalOf { LightRange }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F6B63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBFEDE6),
    secondary = Color(0xFF4A635F),
    surface = Color(0xFFF7F8FA),
    background = Color(0xFFF7F8FA),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6EE7C8),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0F4F47),
    surface = Color(0xFF101214),
    background = Color(0xFF101214),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displayLarge = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.04).em, lineHeight = 100.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun G7Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalRangeColors provides if (dark) DarkRange else LightRange) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
