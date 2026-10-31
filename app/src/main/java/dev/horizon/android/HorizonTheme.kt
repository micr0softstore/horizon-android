package dev.horizon.android

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val HorizonLightColors = lightColorScheme(
    primary = Color(0xFF17683D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F7C7),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF486455),
    secondaryContainer = Color(0xFFCBEBD7),
    tertiary = Color(0xFF38656E),
    tertiaryContainer = Color(0xFFBDEAF4),
    background = Color(0xFFF8FBF5),
    surface = Color(0xFFF8FBF5),
    surfaceVariant = Color(0xFFDEE8DC),
    onSurfaceVariant = Color(0xFF414942),
    error = Color(0xFFB3261E),
)

private val HorizonDarkColors = darkColorScheme(
    primary = Color(0xFF9BDBAB),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF005229),
    onPrimaryContainer = Color(0xFFB9F7C7),
    secondary = Color(0xFFB0CCB7),
    secondaryContainer = Color(0xFF314B3B),
    tertiary = Color(0xFFA2CED8),
    tertiaryContainer = Color(0xFF1E4D56),
    background = Color(0xFF101510),
    surface = Color(0xFF101510),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC1CAC0),
    error = Color(0xFFF2B8B5),
)

private val HorizonTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 62.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 39.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

private val HorizonShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Material You when Android supplies a wallpaper palette, with an intentional Horizon fallback. */
@Composable
fun HorizonTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> HorizonDarkColors
        else -> HorizonLightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = HorizonTypography,
        shapes = HorizonShapes,
        content = content,
    )
}

/** A single, named color role makes translucent game controls agree with the launcher palette. */
val HorizonOverlayTint: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primaryContainer
