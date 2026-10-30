package com.harnessapk.ui.remote

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.ui.MainMode
import com.harnessapk.ui.theme.ModeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream

/** No requests reach ChatGPT; no real account cookies or storage are touched. Requires an unlocked device. */
class CodexWebScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private lateinit var web: OfflineWebView
    private var browserOpens = 0

    private fun showScreen() {
        compose.setContent {
            ModeTheme(MainMode.WORK) {
                CodexWebScreen(
                    onFileChooser = { _, _ -> false },
                    onOpenBrowser = { browserOpens++ },
                    onExit = {},
                    webViewFactory = { OfflineWebView(it).also { view -> web = view } },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun mainFrame403CanNavigateToLoginAndOldOverlayIsRemoved() {
        showScreen()
        compose.runOnIdle { web.httpError() }
        compose.onNodeWithText("页面加载失败").assertIsDisplayed()
        compose.onNodeWithText("重新登录").performClick()
        compose.runOnIdle {
            assertEquals(CodexLoginUrl, web.currentUrl)
            web.finishPage()
        }
        compose.onNodeWithText("页面加载失败").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(CodexWebUrl, CodexLoginUrl), web.loads) }
    }

    @Test
    fun persistent403KeepsErrorWithoutAutomaticRetriesAndHasBrowserFallback() {
        showScreen()
        compose.runOnIdle { web.httpError(); web.finishPage() }
        compose.onNodeWithText("页面加载失败").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, web.loads.size) }
        compose.onNodeWithText("重试当前页").performClick()
        compose.runOnIdle { web.httpError(); web.finishPage() }
        compose.onNodeWithText("在浏览器中打开").performClick()
        compose.runOnIdle { assertEquals(1, browserOpens); assertEquals(1, web.reloads) }
        compose.onNodeWithText("查看原网页").performClick()
        compose.onNodeWithText("页面加载失败").assertDoesNotExist()
    }

    @Test
    fun subresourceAndOldPageErrorsDoNotBlockNewLoginPage() {
        showScreen()
        compose.runOnIdle { web.httpError(mainFrame = false) }
        compose.onNodeWithText("页面加载失败").assertDoesNotExist()
        compose.onNodeWithContentDescription("Codex 登录与打开方式").performClick()
        compose.onNodeWithText("重新登录").performClick()
        compose.runOnIdle { web.httpError(url = CodexWebUrl); web.finishPage() }
        compose.onNodeWithText("页面加载失败").assertDoesNotExist()
    }

    @Test
    fun failedLoginIsReportedEvenWhenWebViewStillExposesPreviousPageUrl() {
        showScreen()
        compose.onNodeWithContentDescription("Codex 登录与打开方式").performClick()
        compose.onNodeWithText("重新登录").performClick()
        compose.runOnIdle {
            assertEquals(CodexWebUrl, web.url)
            assertEquals(CodexLoginUrl, web.currentUrl)
            web.httpError()
            web.finishPage()
        }
        compose.onNodeWithText("页面加载失败").assertIsDisplayed()
    }

    @Test
    fun resetRequiresConfirmationAndCancelDoesNotNavigateOrTouchSession() {
        showScreen()
        compose.onNodeWithContentDescription("Codex 登录与打开方式").performClick()
        compose.onNodeWithText("重置 Codex 登录状态…").performClick()
        compose.onNodeWithText("重置 Codex 登录状态？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(listOf(CodexWebUrl), web.loads) }
        compose.onNodeWithText("重置 Codex 登录状态？").assertDoesNotExist()
    }

    @Test
    fun unavailableWebViewStillAllowsBrowserFallback() {
        compose.setContent {
            ModeTheme(MainMode.WORK) {
                CodexWebScreen(
                    onFileChooser = { _, _ -> false },
                    onOpenBrowser = { browserOpens++ },
                    onExit = {},
                    webViewFactory = { null },
                )
            }
        }
        compose.onNodeWithText("在浏览器中打开 Codex").performClick()
        compose.runOnIdle { assertEquals(1, browserOpens) }
    }

    private class OfflineWebView(context: Context) : WebView(context) {
        var currentUrl: String? = null
        val loads = mutableListOf<String>()
        var reloads = 0

        // A real WebView may still expose the old committed page during a failed login load.
        override fun getUrl(): String? = loads.firstOrNull()

        override fun loadUrl(url: String) {
            currentUrl = url
            loads += url
            webViewClient.onPageStarted(this, url, null)
        }

        override fun reload() {
            reloads++
            webViewClient.onPageStarted(this, currentUrl, null)
        }

        fun finishPage() = webViewClient.onPageFinished(this, currentUrl)

        fun httpError(mainFrame: Boolean = true, url: String = currentUrl!!) {
            val request = object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(url)
                override fun isForMainFrame(): Boolean = mainFrame
                override fun isRedirect(): Boolean = false
                override fun hasGesture(): Boolean = false
                override fun getMethod(): String = "GET"
                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            }
            webViewClient.onReceivedHttpError(
                this, request,
                WebResourceResponse("text/html", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf())),
            )
        }
    }
}
