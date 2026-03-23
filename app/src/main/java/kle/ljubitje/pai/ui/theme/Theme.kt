package kle.ljubitje.pai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val PaiBlue = Color(0xFF58A6FF)
private val PaiGreen = Color(0xFF3FB950)
private val PaiBackground = Color(0xFF0D1117)
private val PaiSurface = Color(0xFF161B22)
private val PaiOnSurface = Color(0xFFE6EDF3)
private val PaiMuted = Color(0xFF8B949E)

private val PaiColorScheme = darkColorScheme(
    primary = PaiBlue,
    onPrimary = Color.White,
    secondary = PaiGreen,
    onSecondary = Color.White,
    background = PaiBackground,
    onBackground = PaiOnSurface,
    surface = PaiSurface,
    onSurface = PaiOnSurface,
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = PaiMuted,
    outline = Color(0xFF30363D),
)

private val PaiTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        color = PaiOnSurface
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        color = PaiOnSurface
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        color = PaiOnSurface
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = PaiOnSurface
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = PaiMuted
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
)

@Composable
fun PAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PaiColorScheme,
        typography = PaiTypography,
        content = content
    )
}
