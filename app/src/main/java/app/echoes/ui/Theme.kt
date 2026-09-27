package app.echoes.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.echoes.R

object Palette {
    val bg = Color(0xFF0B0D10)
    val surface = Color(0xFF14171C)
    val surfaceHigh = Color(0xFF1D2128)
    val line = Color(0xFF262B33)
    val text = Color(0xFFF2F4F7)
    val muted = Color(0xFF8C94A3)
    val mint = Color(0xFF6CF0B0)
    val pink = Color(0xFFFF7AB6)
    val amber = Color(0xFFFFC46B)
}

val Grotesk = FontFamily(
    Font(R.font.grotesk_regular, FontWeight.Normal),
    Font(R.font.grotesk_medium, FontWeight.Medium),
    Font(R.font.grotesk_bold, FontWeight.SemiBold),
    Font(R.font.grotesk_bold, FontWeight.Bold),
)

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
)

@Composable
fun EchoesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.mint,
            onPrimary = Color(0xFF002A17),
            secondary = Palette.pink,
            background = Palette.bg,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.muted,
            surfaceContainer = Palette.surface,
            surfaceContainerHigh = Palette.surfaceHigh,
            surfaceContainerHighest = Palette.surfaceHigh,
            outline = Palette.line,
            outlineVariant = Palette.line,
        ),
        typography = typography,
    ) {
        CompositionLocalProvider(LocalContentColor provides Palette.text, content = content)
    }
}
