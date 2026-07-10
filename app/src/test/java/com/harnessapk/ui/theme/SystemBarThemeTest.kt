package com.harnessapk.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemBarThemeTest {
    @Test
    fun lightAndDarkResourcesUseMatchingSystemBarIconAppearance() {
        val lightStyle = File("src/main/res/values/styles.xml").readText()
        val darkStyle = File("src/main/res/values-night/styles.xml").readText()

        assertTrue(lightStyle.contains("<item name=\"android:windowLightStatusBar\">true</item>"))
        assertTrue(lightStyle.contains("<item name=\"android:windowLightNavigationBar\">true</item>"))
        assertTrue(darkStyle.contains("<item name=\"android:windowLightStatusBar\">false</item>"))
        assertTrue(darkStyle.contains("<item name=\"android:windowLightNavigationBar\">false</item>"))
    }
}
