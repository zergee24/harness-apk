package com.harnessapk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun warmLightColorScheme() = lightColorScheme(
    primary = Color(0xFFA83F39),
    onPrimary = Color(0xFFFFF9F7),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF3D0806),
    secondary = Color(0xFF6E5E5A),
    onSecondary = Color(0xFFFFFBFA),
    background = Color(0xFFFAF7F6),
    onBackground = Color(0xFF211A19),
    surface = Color(0xFFFFFDFC),
    onSurface = Color(0xFF211A19),
    surfaceVariant = Color(0xFFF1E5E2),
    onSurfaceVariant = Color(0xFF564341),
    outline = Color(0xFF887370),
    outlineVariant = Color(0xFFDBC1BD),
    error = Color(0xFFBA1A1A),
)

internal fun warmDarkColorScheme() = darkColorScheme(
    primary = Color(0xFFFFB3AA),
    onPrimary = Color(0xFF5F1410),
    primaryContainer = Color(0xFF862824),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFD8C2BD),
    onSecondary = Color(0xFF3B2D2A),
    background = Color(0xFF17181A),
    onBackground = Color(0xFFF3EDEC),
    surface = Color(0xFF202124),
    onSurface = Color(0xFFF3EDEC),
    surfaceVariant = Color(0xFF343437),
    onSurfaceVariant = Color(0xFFD8C2BD),
    outline = Color(0xFFA98C87),
    outlineVariant = Color(0xFF554440),
    error = Color(0xFFFFB4AB),
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

internal object HarnessSpacing {
    val minimumTouchTarget = 48.dp
    val primaryControlHeight = 56.dp
    val pageHorizontal = 16.dp
    val section = 24.dp
    val item = 12.dp
}

@Composable
fun HarnessApkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) warmDarkColorScheme() else warmLightColorScheme(),
        typography = HarnessTypography,
        shapes = HarnessShapes,
        content = content,
    )
}
