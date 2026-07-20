package com.harnessapk.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemBarThemeTest {
    @Test
    fun lightAndDarkResourcesUseMatchingSystemBarIconAppearance() {
        val lightBaseStyle = File("src/main/res/values/styles.xml").readText()
        val darkBaseStyle = File("src/main/res/values-night/styles.xml").readText()
        val lightNavigationStyle = File("src/main/res/values-v27/styles.xml").readText()
        val darkNavigationStyle = File("src/main/res/values-night-v27/styles.xml").readText()

        assertTrue(lightBaseStyle.contains("<item name=\"android:windowLightStatusBar\">true</item>"))
        assertTrue(darkBaseStyle.contains("<item name=\"android:windowLightStatusBar\">false</item>"))
        assertTrue(lightNavigationStyle.contains("<item name=\"android:windowLightNavigationBar\">true</item>"))
        assertTrue(darkNavigationStyle.contains("<item name=\"android:windowLightNavigationBar\">false</item>"))
    }
}
