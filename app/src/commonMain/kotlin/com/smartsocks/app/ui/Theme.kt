package com.smartsocks.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.smartsocks.core.settings.AppTheme

/** Colours outside the Material scheme: link states and the pressure scale. */
@Immutable
class SockColors(
    val ok: Color,
    val busy: Color,
    val error: Color,
    val idle: Color,
    /** An unloaded sensor; the pressure scale runs from here to the accent ([ColorScheme.primary]). */
    val sensorEmpty: Color,
    val insole: Color,
    val insoleEdge: Color,
    val cop: Color,
)

// One accent (ember orange) on warm neutrals.
private val Ember = Color(0xFFE0552B)
private val EmberDark = Color(0xFFFF7A50)

private val LightScheme = lightColorScheme(
    primary = Ember,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBDDD1),
    onPrimaryContainer = Color(0xFF3B0D00),
    secondary = Color(0xFF5D5A55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E3DD),
    onSecondaryContainer = Color(0xFF1D1B18),
    background = Color(0xFFF3F2EF),
    onBackground = Color(0xFF1B1B1A),
    surface = Color(0xFFF3F2EF),
    onSurface = Color(0xFF1B1B1A),
    surfaceVariant = Color(0xFFE7E5E0),
    onSurfaceVariant = Color(0xFF5E5B56),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF9F7),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDEBE7),
    surfaceContainerHighest = Color(0xFFE5E3DE),
    outline = Color(0xFFBDB9B2),
    outlineVariant = Color(0xFFDAD7D1),
    error = Color(0xFFC8372D),
)

private val DarkScheme = darkColorScheme(
    primary = EmberDark,
    onPrimary = Color(0xFF2B0B00),
    primaryContainer = Color(0xFF5C2310),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFB8B4AD),
    onSecondary = Color(0xFF22201D),
    secondaryContainer = Color(0xFF34322F),
    onSecondaryContainer = Color(0xFFE8E5DF),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEAE8E4),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEAE8E4),
    surfaceVariant = Color(0xFF2A2926),
    onSurfaceVariant = Color(0xFFA9A59E),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF171716),
    surfaceContainer = Color(0xFF1C1C1B),
    surfaceContainerHigh = Color(0xFF242322),
    surfaceContainerHighest = Color(0xFF2E2D2B),
    outline = Color(0xFF55524D),
    outlineVariant = Color(0xFF3A3835),
    error = Color(0xFFFF6B5E),
)

private val LightSock = SockColors(
    ok = Color(0xFF23915F),
    busy = Color(0xFFC98A12),
    error = Color(0xFFC8372D),
    idle = Color(0xFF9A968F),
    sensorEmpty = Color(0xFFDCD9D3),
    insole = Color(0xFFFFFFFF),
    insoleEdge = Color(0xFFCFCBC4),
    cop = Color(0xFF1B1B1A),
)

private val DarkSock = SockColors(
    ok = Color(0xFF4CC48A),
    busy = Color(0xFFE7AE3C),
    error = Color(0xFFFF6B5E),
    idle = Color(0xFF77736D),
    sensorEmpty = Color(0xFF3A3835),
    insole = Color(0xFF1F1E1D),
    insoleEdge = Color(0xFF46433F),
    cop = Color(0xFFF4F2EE),
)

val LocalSockColors = staticCompositionLocalOf { LightSock }

private val NumberFeatures = "tnum"

private fun typography(): Typography {
    val base = Typography()
    return base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = NumberFeatures),
        labelMedium = base.labelMedium.copy(fontFeatureSettings = NumberFeatures),
        bodyMedium = base.bodyMedium.copy(fontFeatureSettings = NumberFeatures),
    )
}

/** Large tabular numbers (balance percentages). */
val NumberStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = NumberFeatures)

@Composable
fun isDarkTheme(theme: AppTheme): Boolean = when (theme) {
    AppTheme.SYSTEM -> isSystemInDarkTheme()
    AppTheme.LIGHT -> false
    AppTheme.DARK -> true
}

@Composable
fun SmartSocksTheme(dark: Boolean, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalSockColors provides if (dark) DarkSock else LightSock) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = typography(),
            content = content,
        )
    }
}
