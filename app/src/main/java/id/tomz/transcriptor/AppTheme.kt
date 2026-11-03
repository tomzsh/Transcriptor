package id.tomz.transcriptor

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily

enum class ThemeMode(@androidx.annotation.StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark)
}

internal fun transcriptorColorScheme(darkTheme: Boolean) = if (darkTheme) darkColorScheme(
    primary = Color(0xFF8ED8C4), onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005044), onPrimaryContainer = Color(0xFFAAF4DF),
    secondary = Color(0xFFB4CCC2), onSecondary = Color(0xFF20362E),
    secondaryContainer = Color(0xFF364D44), onSecondaryContainer = Color(0xFFD0E8DD),
    tertiary = Color(0xFFE4C18B), onTertiary = Color(0xFF412D08),
    background = Color(0xFF101613), onBackground = Color(0xFFE0E5E0),
    surface = Color(0xFF101613), onSurface = Color(0xFFE0E5E0),
    surfaceVariant = Color(0xFF3F4944), onSurfaceVariant = Color(0xFFBFC9C2),
    surfaceContainerLowest = Color(0xFF0B100E), surfaceContainerLow = Color(0xFF181D1A),
    surfaceContainer = Color(0xFF1C211E), surfaceContainerHigh = Color(0xFF262B28),
    surfaceContainerHighest = Color(0xFF303632), outline = Color(0xFF89938C), outlineVariant = Color(0xFF3F4944),
) else lightColorScheme(
    primary = Color(0xFF006B58), onPrimary = Color.White,
    primaryContainer = Color(0xFFB4EFDC), onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4D6359), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8DC), onSecondaryContainer = Color(0xFF10251D),
    tertiary = Color(0xFF795923), onTertiary = Color.White,
    background = Color(0xFFFAFBF9), onBackground = Color(0xFF19221E),
    surface = Color(0xFFFAFBF9), onSurface = Color(0xFF19221E),
    surfaceVariant = Color(0xFFDEE5DE), onSurfaceVariant = Color(0xFF414943),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F4EF),
    surfaceContainer = Color(0xFFEAEFE9), surfaceContainerHigh = Color(0xFFE4E9E3),
    surfaceContainerHighest = Color(0xFFDFE4DE), outline = Color(0xFF717A73), outlineVariant = Color(0xFFC1C9C1),
)

@Composable
internal fun TranscriptorTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val base = Typography()
    val activity = LocalActivity.current
    val view = LocalView.current
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = transcriptorColorScheme(dark),
        typography = base.copy(
            // System sans keeps the complete Android Arabic and multilingual fallback chain.
            headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
            headlineMedium = base.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
            headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
            titleLarge = base.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
            titleMedium = base.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
            titleSmall = base.titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.sp),
            bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
            labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
            labelMedium = base.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
            labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        ),
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(16.dp)),
    ) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content) }
}
