package com.harnessapk.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {
    @Test
    fun lightThemeUsesApprovedWarmAccessibleTokens() {
        val scheme = warmLightColorScheme()

        assertEquals(Color(0xFF9F5167), scheme.primary)
        assertEquals(Color(0xFFFFF9F7), scheme.onPrimary)
        assertEquals(Color(0xFFFFD9E2), scheme.primaryContainer)
        assertEquals(Color(0xFF3F071D), scheme.onPrimaryContainer)
        assertEquals(Color(0xFFFAF7F6), scheme.background)
        assertEquals(Color(0xFFFFFDFC), scheme.surface)
        assertEquals(Color(0xFF211A19), scheme.onBackground)
    }

    @Test
    fun darkThemeUsesApprovedWarmAccessibleTokens() {
        val scheme = warmDarkColorScheme()

        assertEquals(Color(0xFFFFB3AA), scheme.primary)
        assertEquals(Color(0xFF5F1410), scheme.onPrimary)
        assertEquals(Color(0xFF17181A), scheme.background)
        assertEquals(Color(0xFF202124), scheme.surface)
        assertEquals(Color(0xFFF3EDEC), scheme.onBackground)
    }

    @Test
    fun supportingContainersStayInsideTheWarmNeutralPalette() {
        val light = warmLightColorScheme()
        val dark = warmDarkColorScheme()

        assertEquals(Color(0xFFF7DDD8), light.secondaryContainer)
        assertEquals(Color(0xFFDCE5DF), light.tertiaryContainer)
        assertEquals(Color(0xFF59413D), dark.secondaryContainer)
        assertEquals(Color(0xFF3C4841), dark.tertiaryContainer)
    }

    @Test
    fun typeAndSpacingKeepReadableComfortableDefaults() {
        assertEquals(17.sp, HarnessTypography.bodyLarge.fontSize)
        assertEquals(14.sp, HarnessTypography.bodySmall.fontSize)
        assertEquals(48.dp, HarnessSpacing.minimumTouchTarget)
        assertEquals(56.dp, HarnessSpacing.primaryControlHeight)
    }
}
