package com.harnessapk.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.harnessapk.HarnessApkApplication
import com.harnessapk.remote.ZcodeWebRemoteStore
import com.harnessapk.ui.remote.ZcodeWebRemoteScreen
import androidx.compose.material3.Surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harnessapk.ui.settings.SettingsScreen
import com.harnessapk.ui.theme.ModeTheme
import com.harnessapk.ui.work.WorkHomeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReleaseSafetyUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun failedWebPageCanChangeLinkWithoutLosingTheSavedConnection() {
        val app = ApplicationProvider.getApplicationContext<HarnessApkApplication>()
        val preferenceName = "zcode-ui-test-${System.nanoTime()}"
        val isolatedContext = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        }
        val store = ZcodeWebRemoteStore(isolatedContext)
        val previous = "https://zcode.invalid/remote/test"
        assertTrue(store.save(previous))
        try {
            composeRule.setContent {
                ModeTheme(MainMode.WORK) {
                    Surface {
                        ZcodeWebRemoteScreen(app.container, store, { _, _ -> false }, {})
                    }
                }
            }
            if (android.webkit.WebView.getCurrentWebViewPackage() == null) {
                composeRule.onNodeWithText("网页组件不可用").assertIsDisplayed()
                composeRule.onNodeWithText("打开系统设置").assertIsDisplayed()
                composeRule.onNodeWithText("重试").performClick()
                composeRule.onNodeWithText("网页组件不可用").assertIsDisplayed()
                assertEquals(previous, store.url.value)
                return
            }
            composeRule.waitUntil(40_000) {
                composeRule.onAllNodesWithText("页面加载失败").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("更换链接").performClick()
            composeRule.onNodeWithText("粘贴 zcode 远程连接").performTextReplacement("无效链接")
            composeRule.onNodeWithText("保存并打开").assertIsNotEnabled()
            composeRule.onNodeWithText("取消").performClick()
            assertEquals(previous, store.url.value)
            composeRule.onNodeWithText("重试当前链接").assertIsDisplayed()
        } finally {
            app.deleteSharedPreferences(preferenceName)
        }
    }

    @Test
    fun groupedSettingsCanSwitchToSimpleModeAndKeepWorkingActions() {
        val simple = mutableStateOf(false)
        var updates = 0
        var imports = 0
        composeRule.setContent {
            ModeTheme(MainMode.ME) {
                SettingsScreen(
                    contentPadding = PaddingValues(0.dp),
                    onOpenProviders = {}, onOpenSearch = {}, onOpenVoice = {},
                    onOpenGit = {}, onOpenSkills = {}, onOpenAgentPackages = {},
                    onOpenWikiLibrary = {}, onOpenUpdates = { updates++ },
                    onOpenConfigPackage = { imports++ }, simpleMode = simple.value,
                    onSimpleModeChange = { simple.value = it },
                )
            }
        }
        composeRule.onNodeWithText("设备与同步").assertIsDisplayed()
        composeRule.runOnIdle { simple.value = true }
        composeRule.onNodeWithText("模型配置").assertDoesNotExist()
        composeRule.onNodeWithText("项目与资料").assertDoesNotExist()
        composeRule.onNodeWithText("检查更新").performClick()
        composeRule.onNodeWithText("配置包").performClick()
        assertEquals(1, updates)
        assertEquals(1, imports)
    }

    @Test
    fun landscapeWorkHubKeepsAllEntriesVisibleAndDshRoutesCorrectly() {
        var backend: String? = null
        var openedWorkbench = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ModeTheme(MainMode.WORK) {
                    Box(Modifier.requiredSize(680.dp, 212.dp).testTag("work-test-viewport")) {
                        WorkHomeScreen(PaddingValues(0.dp), { backend = it }, { openedWorkbench = true })
                    }
                }
            }
        }
        val viewport = composeRule.onNodeWithTag("work-test-viewport").fetchSemanticsNode().boundsInRoot
        listOf("dashboard", "codex", "zcode", "dsh", "workbench").forEach {
            val bounds = composeRule.onNodeWithTag("work-home-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$it must fit entirely above the navigation bar", bounds.bottom <= viewport.bottom && bounds.top >= viewport.top)
        }
        composeRule.onNodeWithTag("work-home-dsh").performClick()
        assertEquals("dsh", backend)
        composeRule.onNodeWithTag("work-home-workbench").performClick()
        assertEquals(true, openedWorkbench)
    }
}
