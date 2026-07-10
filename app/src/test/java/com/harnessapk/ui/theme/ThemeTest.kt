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

        assertEquals(Color(0xFFA83F39), scheme.primary)
        assertEquals(Color(0xFFFFF9F7), scheme.onPrimary)
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
    fun typeAndSpacingKeepReadableComfortableDefaults() {
        assertEquals(17.sp, HarnessTypography.bodyLarge.fontSize)
        assertEquals(14.sp, HarnessTypography.bodySmall.fontSize)
        assertEquals(48.dp, HarnessSpacing.minimumTouchTarget)
        assertEquals(56.dp, HarnessSpacing.primaryControlHeight)
    }
}
