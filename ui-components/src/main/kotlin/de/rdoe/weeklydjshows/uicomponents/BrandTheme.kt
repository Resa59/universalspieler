package de.rdoe.weeklydjshows.uicomponents

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BrandNavy = Color(0xFF063653)
val BrandPink = Color(0xFFF05D6E)
val BrandGreen = Color(0xFF16A085)
val LegacyBlue = Color(0xFF0099CB)
val LegacyOrange = Color(0xFFFF7000)
val WarmSurface = Color(0xFFFFFEFB)

private val LightColors = lightColorScheme(
    primary = LegacyBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F2FA),
    onPrimaryContainer = Color(0xFF003544),
    secondary = BrandNavy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3EAF7),
    tertiary = LegacyOrange,
    background = WarmSurface,
    surface = WarmSurface,
    surfaceVariant = Color(0xFFF3F0E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF72D5F3),
    secondary = Color(0xFF9BCDE8),
    tertiary = Color(0xFFFFB06C),
    surface = Color(0xFF111518),
    background = Color(0xFF0D1114),
)

@Composable
fun WeeklyDjTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
