package fr.niakimbo.trust.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import fr.niakimbo.trust.R
import fr.niakimbo.trust.data.ThemeMode

// --- Couleurs : tons chauds, un seul accent or/ambre discret ---

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD4A955),
    onPrimary = Color(0xFF1E1606),
    primaryContainer = Color(0xFF3A2E15),
    onPrimaryContainer = Color(0xFFF1DDB0),
    secondary = Color(0xFFB8AE9C),
    onSecondary = Color(0xFF1C1A16),
    background = Color(0xFF0F0E0C),
    onBackground = Color(0xFFEDE6D8),
    surface = Color(0xFF0F0E0C),
    onSurface = Color(0xFFEDE6D8),
    surfaceVariant = Color(0xFF26231E),
    onSurfaceVariant = Color(0xFFA39A89),
    surfaceContainerLowest = Color(0xFF0B0A08),
    surfaceContainerLow = Color(0xFF151310),
    surfaceContainer = Color(0xFF1A1814),
    surfaceContainerHigh = Color(0xFF221F1A),
    surfaceContainerHighest = Color(0xFF2B2721),
    outline = Color(0xFF4A443A),
    outlineVariant = Color(0xFF302C25),
    error = Color(0xFFE5948A),
    onError = Color(0xFF2A0D09),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8C6A1F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF0E1BC),
    onPrimaryContainer = Color(0xFF2B2006),
    secondary = Color(0xFF6B6354),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F4EC),
    onBackground = Color(0xFF1C1A16),
    surface = Color(0xFFF8F4EC),
    onSurface = Color(0xFF1C1A16),
    surfaceVariant = Color(0xFFEAE3D6),
    onSurfaceVariant = Color(0xFF615A4D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3EEE4),
    surfaceContainer = Color(0xFFEFE9DE),
    surfaceContainerHigh = Color(0xFFE9E2D5),
    surfaceContainerHighest = Color(0xFFE3DBCD),
    outline = Color(0xFFB3AA99),
    outlineVariant = Color(0xFFD8D0C1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

// --- Polices : Inter pour l'interface, Literata pour le texte biblique (fichiers variables) ---

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val InterFamily = FontFamily(
    variable(R.font.inter, 400),
    variable(R.font.inter, 500),
    variable(R.font.inter, 600),
    variable(R.font.inter, 700),
)

/** Police du texte biblique (utilisée à partir de l'étape 4). */
val ScriptureFamily = FontFamily(
    variable(R.font.literata, 400),
    variable(R.font.literata, 500),
    variable(R.font.literata, 600),
)

private val base = Typography()

private fun TextStyle.inter() = copy(fontFamily = InterFamily)

private val TrustTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = ScriptureFamily),
    displayMedium = base.displayMedium.copy(fontFamily = ScriptureFamily),
    displaySmall = base.displaySmall.copy(fontFamily = ScriptureFamily),
    headlineLarge = base.headlineLarge.copy(fontFamily = ScriptureFamily, fontWeight = FontWeight.Medium),
    headlineMedium = base.headlineMedium.copy(fontFamily = ScriptureFamily, fontWeight = FontWeight.Medium),
    headlineSmall = base.headlineSmall.copy(fontFamily = ScriptureFamily, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.inter().copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.inter().copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.inter().copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.inter(),
    bodyMedium = base.bodyMedium.inter(),
    bodySmall = base.bodySmall.inter(),
    labelLarge = base.labelLarge.inter().copy(fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.inter().copy(fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.inter().copy(fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

@Composable
fun TrustTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = TrustTypography,
        content = content,
    )
}
