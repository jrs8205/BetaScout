package org.jarsi.betascout.ui.theme

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Brand palette: BetaScout green with green-tinted neutral surfaces, so the app
// has one recognizable look on every device (dynamic color is opt-in).

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6CCBA9),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFF89F8C7),
    secondary = Color(0xFFB0CCBE),
    onSecondary = Color(0xFF1C352A),
    secondaryContainer = Color(0xFF334B3F),
    onSecondaryContainer = Color(0xFFCCE8DA),
    tertiary = Color(0xFFA4CDDE),
    onTertiary = Color(0xFF063542),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF44483F),
    onSurfaceVariant = Color(0xFFC8CCC2),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF8F998F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9DF3CD),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9D9),
    onSecondaryContainer = Color(0xFF0A1F16),
    tertiary = Color(0xFF3E6374),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6FBF5),
    onBackground = Color(0xFF181D1A),
    surface = Color(0xFFF6FBF5),
    onSurface = Color(0xFF181D1A),
    surfaceVariant = Color(0xFFDBE5DD),
    onSurfaceVariant = Color(0xFF404943),
    surfaceContainer = Color(0xFFEAEFEA),
    surfaceContainerHigh = Color(0xFFE4EAE4),
    surfaceContainerHighest = Color(0xFFDEE4DF),
    outline = Color(0xFF707973),
)

/** Rounded, friendly shape scale: cards land on medium/large, chips on the pills. */
private val BetaScoutShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Default type scale with heavy titles — the wordmark and card heroes carry
 *  the expressive look without a bundled font. */
private val BetaScoutTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
    )
}

@Composable
fun BetaScoutTheme(
    useDynamicColor: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = BetaScoutShapes,
        typography = BetaScoutTypography,
        content = content,
    )
}
