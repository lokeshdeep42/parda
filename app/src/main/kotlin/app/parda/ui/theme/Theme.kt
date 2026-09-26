package app.parda.ui.theme

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.parda.core.policy.DarkPatternKind

/** The "Frost" direction from the Parda UI theme canvas. */
object Frost {
    val Ground = Color(0xFFDDE2EA)
    val Paper = Color(0xFFF4F4F2)
    val Ink = Color(0xFF16181D)
    val Ink2 = Color(0xFF555B66)
    val Ink3 = Color(0xFF6B707A)
    val Night = Color(0xFF1C1E24)
    val NightInk2 = Color(0xFFB4B8C2)
    val Accent = Color(0xFFAFC3F2)
    val Peach = Color(0xFFF2CDBE)
    val Glass = Color.White.copy(alpha = 0.55f)
    val GlassStrong = Color.White.copy(alpha = 0.8f)
    val GlassEdge = Color.White.copy(alpha = 0.75f)
    val Ok = Color(0xFF17804A)
    val OkDot = Color(0xFF1F9D55)
    val WarnBg = Color(0xFFFFF1CC)
    val WarnInk = Color(0xFF7A5A00)
    val AlertBg = Color(0xFFFDE7E2)
    val AlertInk = Color(0xFFA1321A)

    fun patternColor(kind: DarkPatternKind): Color = when (kind) {
        DarkPatternKind.BASKET_SNEAKING -> Color(0xFF8FB4F5)
        DarkPatternKind.FALSE_URGENCY -> Color(0xFFF2D36B)
        DarkPatternKind.DRIP_PRICING -> Color(0xFFF4AE7A)
        DarkPatternKind.CONFIRM_SHAMING -> Color(0xFFEE7F7F)
        DarkPatternKind.SUBSCRIPTION_TRAP -> Color(0xFFB7A6EE)
    }
}

// Manrope in the design; the system sans is used so the app ships no font download path.
private val typography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
)

/**
 * Devanagari titles broke inside words ("फ़ायरवॉ / ल"): the tight tracking of the Latin design
 * and Android's default breaking of large text do not suit it. Hindi gets normal tracking, and
 * titles in every language break simply, only between words.
 */
private fun localized(base: Typography, hindi: Boolean): Typography {
    fun TextStyle.fit() = copy(lineBreak = LineBreak.Simple, letterSpacing = if (hindi) 0.sp else letterSpacing)
    return base.copy(
        headlineLarge = base.headlineLarge.fit(),
        headlineMedium = base.headlineMedium.fit(),
        titleLarge = base.titleLarge.fit(),
    )
}

@Composable
fun PardaTheme(content: @Composable () -> Unit) {
    val hindi = LocalConfiguration.current.locales[0].language == "hi"
    val typography = remember(hindi) { localized(typography, hindi) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Frost.Night,
            onPrimary = Color.White,
            background = Frost.Ground,
            onBackground = Frost.Ink,
            surface = Frost.Paper,
            onSurface = Frost.Ink,
            onSurfaceVariant = Frost.Ink2,
        ),
        typography = typography,
        content = content,
    )
}
