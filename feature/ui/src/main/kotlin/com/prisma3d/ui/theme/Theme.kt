package com.prisma3d.ui.theme

import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.util.Supplier

enum class GizmoMode {
    Translate,
    Rotate,
    Scale,
    None
}

val LocalGizmoMode: CompositionLocal<GizmoMode> = compositionLocalOf { GizmoMode.None }

enum class ThemeMode {
    System,
    Light,
    Dark
}

data class PrismaThemeMode(
    val mode: ThemeMode = ThemeMode.System
)

val LocalPrismaThemeMode: CompositionLocal<PrismaThemeMode> = compositionLocalOf { PrismaThemeMode() }

object PrismaColors {
    val DarkBackground = Color(0xFF1E1E1E)
    val DarkSurface = Color(0xFF252525)
    val DarkSurfaceVariant = Color(0xFF2D2D2D)
    val DarkOutline = Color(0xFF3A3A3A)
    
    val AccentTeal = Color(0xFF00BFA6)
    val AccentTealVariant = Color(0xFF009B88)
    val AccentBlue = Color(0xFF007AFF)
    val AccentBlueVariant = Color(0xFF0056CC)
    
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFFB0B0B0)
    val TextDisabled = Color(0xFF6A6A6A)
    val TextOnAccent = Color.Black
    
    val Error = Color(0xFFCF6679)
    val ErrorContainer = Color(0xFF93000A)
    
    val LightBackground = Color(0xFFF5F5F5)
    val LightSurface = Color.White
    val LightSurfaceVariant = Color(0xFFE0E0E0)
    val LightOutline = Color(0xFF757575)
    
    val LightTextPrimary = Color(0xFF1A1A1A)
    val LightTextSecondary = Color(0xFF4A4A4A)
    val LightTextDisabled = Color(0xFF9E9E9E)
    val LightTextOnAccent = Color.White
}

val PrismaDarkColorScheme: ColorScheme = darkColorScheme(
    primary = PrismaColors.AccentTeal,
    primaryContainer = PrismaColors.AccentTealVariant,
    secondary = PrismaColors.AccentBlue,
    secondaryContainer = PrismaColors.AccentBlueVariant,
    tertiary = PrismaColors.AccentTeal.copy(alpha = 0.8f),
    tertiaryContainer = PrismaColors.AccentBlue.copy(alpha = 0.8f),
    
    surface = PrismaColors.DarkSurface,
    surfaceVariant = PrismaColors.DarkSurfaceVariant,
    surfaceContainerHighest = PrismaColors.DarkOutline,
    
    background = PrismaColors.DarkBackground,
    
    onPrimary = PrismaColors.TextOnAccent,
    onPrimaryContainer = PrismaColors.TextPrimary,
    onSecondary = PrismaColors.TextOnAccent,
    onSecondaryContainer = PrismaColors.TextPrimary,
    onTertiary = PrismaColors.TextOnAccent,
    onTertiaryContainer = PrismaColors.TextPrimary,
    
    onSurface = PrismaColors.TextPrimary,
    onSurfaceVariant = PrismaColors.TextSecondary,
    
    onBackground = PrismaColors.TextPrimary,
    
    outline = PrismaColors.DarkOutline,
    outlineVariant = PrismaColors.DarkOutline.copy(alpha = 0.5f),
    
    inverseSurface = PrismaColors.LightSurface,
    inverseOnSurface = PrismaColors.LightTextPrimary,
    inversePrimary = PrismaColors.AccentTealVariant,
    
    error = PrismaColors.Error,
    errorContainer = PrismaColors.ErrorContainer,
    onError = PrismaColors.TextOnAccent,
    onErrorContainer = PrismaColors.TextPrimary,
    
    scrim = Color.Black,
    
    surfaceTint = PrismaColors.AccentTeal,
    
    primaryFixed = PrismaColors.AccentTeal,
    primaryFixedDim = PrismaColors.AccentTealVariant,
    onPrimaryFixed = PrismaColors.TextOnAccent,
    onPrimaryFixedVariant = PrismaColors.TextOnAccent,
    
    secondaryFixed = PrismaColors.AccentBlue,
    secondaryFixedDim = PrismaColors.AccentBlueVariant,
    onSecondaryFixed = PrismaColors.TextOnAccent,
    onSecondaryFixedVariant = PrismaColors.TextOnAccent,
    
    tertiaryFixed = PrismaColors.AccentTeal.copy(alpha = 0.8f),
    tertiaryFixedDim = PrismaColors.AccentBlue.copy(alpha = 0.8f),
    onTertiaryFixed = PrismaColors.TextOnAccent,
    onTertiaryFixedVariant = PrismaColors.TextOnAccent,
)

val PrismaLightColorScheme: ColorScheme = lightColorScheme(
    primary = PrismaColors.AccentTealVariant,
    primaryContainer = PrismaColors.AccentTeal,
    secondary = PrismaColors.AccentBlueVariant,
    secondaryContainer = PrismaColors.AccentBlue,
    tertiary = PrismaColors.AccentTealVariant.copy(alpha = 0.8f),
    tertiaryContainer = PrismaColors.AccentBlueVariant.copy(alpha = 0.8f),
    
    surface = PrismaColors.LightSurface,
    surfaceVariant = PrismaColors.LightSurfaceVariant,
    surfaceContainerHighest = PrismaColors.LightOutline,
    
    background = PrismaColors.LightBackground,
    
    onPrimary = PrismaColors.LightTextOnAccent,
    onPrimaryContainer = PrismaColors.LightTextPrimary,
    onSecondary = PrismaColors.LightTextOnAccent,
    onSecondaryContainer = PrismaColors.LightTextPrimary,
    onTertiary = PrismaColors.LightTextOnAccent,
    onTertiaryContainer = PrismaColors.LightTextPrimary,
    
    onSurface = PrismaColors.LightTextPrimary,
    onSurfaceVariant = PrismaColors.LightTextSecondary,
    
    onBackground = PrismaColors.LightTextPrimary,
    
    outline = PrismaColors.LightOutline,
    outlineVariant = PrismaColors.LightOutline.copy(alpha = 0.5f),
    
    inverseSurface = PrismaColors.DarkSurface,
    inverseOnSurface = PrismaColors.TextPrimary,
    inversePrimary = PrismaColors.AccentTeal,
    
    error = PrismaColors.Error,
    errorContainer = PrismaColors.ErrorContainer,
    onError = PrismaColors.TextOnAccent,
    onErrorContainer = PrismaColors.TextPrimary,
    
    scrim = Color.Black,
    
    surfaceTint = PrismaColors.AccentTealVariant,
    
    primaryFixed = PrismaColors.AccentTeal,
    primaryFixedDim = PrismaColors.AccentTealVariant,
    onPrimaryFixed = PrismaColors.TextOnAccent,
    onPrimaryFixedVariant = PrismaColors.TextOnAccent,
    
    secondaryFixed = PrismaColors.AccentBlue,
    secondaryFixedDim = PrismaColors.AccentBlueVariant,
    onSecondaryFixed = PrismaColors.TextOnAccent,
    onSecondaryFixedVariant = PrismaColors.TextOnAccent,
    
    tertiaryFixed = PrismaColors.AccentTeal.copy(alpha = 0.8f),
    tertiaryFixedDim = PrismaColors.AccentBlue.copy(alpha = 0.8f),
    onTertiaryFixed = PrismaColors.TextOnAccent,
    onTertiaryFixedVariant = PrismaColors.TextOnAccent,
)

val PrismaShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerSize(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerSize(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerSize(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerSize(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerSize(24.dp),
)

val PrismaTypography = Typography(
    displayLarge = androidx.compose.ui.text.TextStyle(
        fontSize = 57.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = androidx.compose.ui.text.TextStyle(
        fontSize = 45.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontSize = 36.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontSize = 32.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = androidx.compose.ui.text.TextStyle(
        fontSize = 28.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontSize = 24.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontSize = 22.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontSize = 16.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = androidx.compose.ui.text.TextStyle(
        fontSize = 14.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontSize = 16.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontSize = 14.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = androidx.compose.ui.text.TextStyle(
        fontSize = 12.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontSize = 14.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = androidx.compose.ui.text.TextStyle(
        fontSize = 12.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = androidx.compose.ui.text.TextStyle(
        fontSize = 11.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
)

@androidx.compose.runtime.Composable
fun PrismaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode = ThemeMode.System,
    content: @androidx.compose.runtime.Composable () -> Unit
) {
    val effectiveDarkTheme = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> darkTheme
    }
    
    val colorScheme = if (effectiveDarkTheme) PrismaDarkColorScheme else PrismaLightColorScheme
    val shapes = PrismaShapes
    val typography = PrismaTypography
    
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        typography = typography,
        content = content
    )
}

@androidx.compose.runtime.Composable
fun RememberPrismaThemeMode(): androidx.compose.runtime.MutableState<ThemeMode> {
    return rememberSaveable(saver = ThemeModeSaver) {
        mutableStateOf(ThemeMode.System)
    }
}

object ThemeModeSaver : Saver<ThemeMode, String> {
    override fun save(value: ThemeMode): String = value.name
    override fun restore(value: String): ThemeMode = ThemeMode.valueOf(value)
}

@androidx.compose.runtime.Composable
fun ProvidePrismaThemeMode(
    themeMode: androidx.compose.runtime.MutableState<ThemeMode>,
    content: @androidx.compose.runtime.Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPrismaThemeMode provides PrismaThemeMode(themeMode.value),
        content = content
    )
}

@androidx.compose.runtime.Composable
fun ProvideGizmoMode(
    gizmoMode: GizmoMode,
    content: @androidx.compose.runtime.Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGizmoMode provides gizmoMode,
        content = content
    )
}

@androidx.compose.runtime.Composable
fun isSystemInDarkTheme(): Boolean {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}