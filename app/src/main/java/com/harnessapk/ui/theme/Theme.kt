package com.harnessapk.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harnessapk.ui.MainMode

internal fun warmLightColorScheme() = lightColorScheme(
    primary = Color(0xFFD98278),
    onPrimary = Color(0xFF5F1410),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF3F0805),
    secondary = Color(0xFF6E5E5A),
    onSecondary = Color(0xFFFFFBFA),
    secondaryContainer = Color(0xFFF7DDD8),
    onSecondaryContainer = Color(0xFF2B1512),
    tertiary = Color(0xFF5F665F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE5DF),
    onTertiaryContainer = Color(0xFF18201C),
    background = Color(0xFFFAF7F6),
    onBackground = Color(0xFF211A19),
    surface = Color(0xFFFFFDFC),
    onSurface = Color(0xFF211A19),
    surfaceVariant = Color(0xFFF1E5E2),
    onSurfaceVariant = Color(0xFF564341),
    outline = Color(0xFF887370),
    outlineVariant = Color(0xFFDBC1BD),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceDim = Color(0xFFE6D8D5),
    surfaceBright = Color(0xFFFFFBFA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF8F6),
    surfaceContainer = Color(0xFFF8F1EF),
    surfaceContainerHigh = Color(0xFFF2EBE9),
    surfaceContainerHighest = Color(0xFFECE5E3),
)

internal fun warmDarkColorScheme() = darkColorScheme(
    primary = Color(0xFFFFB3AA),
    onPrimary = Color(0xFF5F1410),
    primaryContainer = Color(0xFF862824),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFD8C2BD),
    onSecondary = Color(0xFF3B2D2A),
    secondaryContainer = Color(0xFF59413D),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFBBC9C0),
    onTertiary = Color(0xFF26302A),
    tertiaryContainer = Color(0xFF3C4841),
    onTertiaryContainer = Color(0xFFD7E5DB),
    background = Color(0xFF17181A),
    onBackground = Color(0xFFF3EDEC),
    surface = Color(0xFF202124),
    onSurface = Color(0xFFF3EDEC),
    surfaceVariant = Color(0xFF343437),
    onSurfaceVariant = Color(0xFFD8C2BD),
    outline = Color(0xFFA98C87),
    outlineVariant = Color(0xFF554440),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceDim = Color(0xFF17181A),
    surfaceBright = Color(0xFF3A3A3D),
    surfaceContainerLowest = Color(0xFF121315),
    surfaceContainerLow = Color(0xFF1B1C1E),
    surfaceContainer = Color(0xFF202124),
    surfaceContainerHigh = Color(0xFF2A2B2D),
    surfaceContainerHighest = Color(0xFF343437),
)

internal fun techDarkColorScheme() = darkColorScheme(
    primary = Color(0xFF8CC9F0),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF004C6E),
    onPrimaryContainer = Color(0xFFC6E8FF),
    secondary = Color(0xFFB4C6D4),
    onSecondary = Color(0xFF1F303D),
    secondaryContainer = Color(0xFF364654),
    onSecondaryContainer = Color(0xFFD0E2F1),
    tertiary = Color(0xFFA7CDB8),
    onTertiary = Color(0xFF0D3526),
    tertiaryContainer = Color(0xFF254C3C),
    onTertiaryContainer = Color(0xFFC2E9D3),
    background = Color(0xFF101417),
    onBackground = Color(0xFFE2E5E8),
    surface = Color(0xFF161B1F),
    onSurface = Color(0xFFE2E5E8),
    surfaceVariant = Color(0xFF263139),
    onSurfaceVariant = Color(0xFFB9C3CB),
    outline = Color(0xFF6E7A84),
    outlineVariant = Color(0xFF3A454E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceDim = Color(0xFF101417),
    surfaceBright = Color(0xFF363C41),
    surfaceContainerLowest = Color(0xFF0B0E11),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2125),
    surfaceContainerHigh = Color(0xFF262B30),
    surfaceContainerHighest = Color(0xFF31363B),
)

internal val HarnessTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 29.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
)

internal val HarnessShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

internal val TechShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

internal object HarnessSpacing {
    val minimumTouchTarget = 48.dp
    val primaryControlHeight = 56.dp
    val pageHorizontal = 16.dp
    val section = 24.dp
    val item = 12.dp
}

@Composable
fun HarnessApkTheme(content: @Composable () -> Unit) {
    ModeTheme(MainMode.LIFE, content)
}

@Composable
fun ModeTheme(mode: MainMode, content: @Composable () -> Unit) {
    val targetScheme = when (mode) {
        MainMode.LIFE -> warmLightColorScheme()
        MainMode.WORK -> techDarkColorScheme()
        MainMode.ME -> techDarkColorScheme()
    }
    MaterialTheme(
        colorScheme = animateColorScheme(targetScheme),
        typography = HarnessTypography,
        shapes = when (mode) {
            MainMode.LIFE -> HarnessShapes
            MainMode.WORK -> TechShapes
            MainMode.ME -> TechShapes
        },
        content = content,
    )
}

private const val THEME_TRANSITION_MILLIS = 300

@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = THEME_TRANSITION_MILLIS)
    val primary by animateColorAsState(target.primary, spec, label = "theme-primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "theme-onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "theme-primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "theme-onPrimaryContainer")
    val secondary by animateColorAsState(target.secondary, spec, label = "theme-secondary")
    val onSecondary by animateColorAsState(target.onSecondary, spec, label = "theme-onSecondary")
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "theme-secondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "theme-onSecondaryContainer")
    val tertiary by animateColorAsState(target.tertiary, spec, label = "theme-tertiary")
    val onTertiary by animateColorAsState(target.onTertiary, spec, label = "theme-onTertiary")
    val tertiaryContainer by animateColorAsState(target.tertiaryContainer, spec, label = "theme-tertiaryContainer")
    val onTertiaryContainer by animateColorAsState(target.onTertiaryContainer, spec, label = "theme-onTertiaryContainer")
    val background by animateColorAsState(target.background, spec, label = "theme-background")
    val onBackground by animateColorAsState(target.onBackground, spec, label = "theme-onBackground")
    val surface by animateColorAsState(target.surface, spec, label = "theme-surface")
    val onSurface by animateColorAsState(target.onSurface, spec, label = "theme-onSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "theme-surfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "theme-onSurfaceVariant")
    val outline by animateColorAsState(target.outline, spec, label = "theme-outline")
    val outlineVariant by animateColorAsState(target.outlineVariant, spec, label = "theme-outlineVariant")
    val error by animateColorAsState(target.error, spec, label = "theme-error")
    val onError by animateColorAsState(target.onError, spec, label = "theme-onError")
    val errorContainer by animateColorAsState(target.errorContainer, spec, label = "theme-errorContainer")
    val onErrorContainer by animateColorAsState(target.onErrorContainer, spec, label = "theme-onErrorContainer")
    val surfaceDim by animateColorAsState(target.surfaceDim, spec, label = "theme-surfaceDim")
    val surfaceBright by animateColorAsState(target.surfaceBright, spec, label = "theme-surfaceBright")
    val surfaceContainerLowest by animateColorAsState(target.surfaceContainerLowest, spec, label = "theme-surfaceContainerLowest")
    val surfaceContainerLow by animateColorAsState(target.surfaceContainerLow, spec, label = "theme-surfaceContainerLow")
    val surfaceContainer by animateColorAsState(target.surfaceContainer, spec, label = "theme-surfaceContainer")
    val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, spec, label = "theme-surfaceContainerHigh")
    val surfaceContainerHighest by animateColorAsState(target.surfaceContainerHighest, spec, label = "theme-surfaceContainerHighest")
    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
}
