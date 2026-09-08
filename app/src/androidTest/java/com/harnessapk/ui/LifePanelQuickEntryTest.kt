package com.harnessapk.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.harnessapk.HarnessApkApplication
import com.harnessapk.chat.LifeConversationOverviewState
import com.harnessapk.ui.theme.HarnessApkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LifePanelQuickEntryTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context as HarnessApkApplication).container

    @Before
    fun resetHomeSelection() {
        container.homeModeStore.reset()
        runBlocking { container.settingsStore.setSimpleMode(false) }
    }

    @Test
    fun homeOffersInputImmediatelyAndAnEmptyQuestionStaysOutOfHistory() {
        openHome()
        composeRule.onNodeWithText("想问点什么？").assertDoesNotExist()
        composeRule.onNodeWithText("最近聊过").assertDoesNotExist()
        composeRule.onNodeWithTag("chat-input").assertIsDisplayed()
        val id = requireNotNull(container.homeModeStore.lifeConversationId.value)
        val items = runBlocking {
            container.lifeConversationOverviewRepository.observe().first { it is LifeConversationOverviewState.Content }
        } as LifeConversationOverviewState.Content
        assertFalse(items.items.any { it.conversationId == id })
        assertEquals(id, HomeModeStore(context).lifeConversationId.value)
    }

    @Test
    fun historyAndSettingsReturnToTheSameSavedDraft() {
        openHome()
        val id = container.homeModeStore.lifeConversationId.value
        enterDraft("明天的安排还没有写完")
        composeRule.onNodeWithText("历史", substring = false).performClick()
        composeRule.onNodeWithText("历史记录", substring = false).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        waitForInput()
        composeRule.onNodeWithTag("chat-input").assertTextContains("明天的安排还没有写完")
        composeRule.onNodeWithTag("nav-ME").performClick()
        composeRule.onNodeWithTag("nav-LIFE").performClick()
        waitForInput()
        composeRule.onNodeWithTag("chat-input").assertTextContains("明天的安排还没有写完")
        assertEquals(id, container.homeModeStore.lifeConversationId.value)
        assertEquals("明天的安排还没有写完", container.conversationDraftStore.load(requireNotNull(id)).text)
    }

    @Test
    fun newQuestionKeepsThePreviousDraftAndClearsOnlyTheNewInput() {
        openHome()
        val previous = requireNotNull(container.homeModeStore.lifeConversationId.value)
        enterDraft("保留这条草稿")
        composeRule.onNodeWithContentDescription("新问题").performClick()
        composeRule.waitUntil(10_000) { container.homeModeStore.lifeConversationId.value != previous }
        waitForInput()
        assertNotEquals(previous, container.homeModeStore.lifeConversationId.value)
        assertEquals("", composeRule.onNodeWithTag("chat-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("保留这条草稿", container.conversationDraftStore.load(previous).text)
    }

    @Test
    fun returningAfterArchivingTheCurrentQuestionOpensANewActiveQuestion() {
        openHome()
        val previous = requireNotNull(container.homeModeStore.lifeConversationId.value)
        composeRule.onNodeWithText("历史", substring = false).performClick()
        composeRule.onNodeWithText("历史记录", substring = false).assertIsDisplayed()
        runBlocking { container.chatRepository.archiveConversation(previous) }
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitUntil(10_000) { container.homeModeStore.lifeConversationId.value != previous }
        waitForInput()
        val current = requireNotNull(container.homeModeStore.lifeConversationId.value)
        assertFalse(requireNotNull(runBlocking { container.chatRepository.conversation(current) }).isArchived)
    }

    @Test
    fun agentToolRemainsReachableWithoutAWelcomePanel() {
        openHome()
        composeRule.onNodeWithContentDescription("生活工具").performClick()
        composeRule.onNodeWithText("智能体", substring = false).performClick()
        composeRule.onNodeWithText("智能体包", substring = false).assertIsDisplayed()
    }

    @Test
    fun wikiToolRemainsReachableWithoutAWelcomePanel() {
        openHome()
        composeRule.onNodeWithContentDescription("生活工具").performClick()
        composeRule.onNodeWithText("知识库", substring = false).performClick()
        composeRule.onNodeWithText("Wiki 知识库", substring = false).assertIsDisplayed()
    }

    @Test
    fun activityRemainsReachableFromBothLifeAndWork() {
        openHome()
        composeRule.onNodeWithContentDescription("生活工具").performClick()
        composeRule.onNodeWithText("任务动态", substring = false).performClick()
        composeRule.onNodeWithText("需要处理", substring = false).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        waitForInput()
        composeRule.onNodeWithTag("nav-WORK").performClick()
        composeRule.onNodeWithContentDescription("个待处理任务", substring = true).performClick()
        composeRule.onNodeWithText("需要处理", substring = false).assertIsDisplayed()
    }

    @Test
    fun narrowLargeTextKeepsHistoryAndNewQuestionReachable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HarnessApkTheme { Box(Modifier.width(320.dp)) { HarnessApkApp() } }
            }
        }
        waitForInput()
        val width = with(composeRule.density) { 320.dp.toPx() }
        val history = composeRule.onNodeWithText("历史", substring = false).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val create = composeRule.onNodeWithContentDescription("新问题").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(history.left >= 0)
        assertTrue(create.right <= width)
        composeRule.onNodeWithContentDescription("生活工具").assertIsDisplayed()
    }

    private fun openHome() {
        composeRule.setContent { HarnessApkTheme { HarnessApkApp() } }
        waitForInput()
    }

    private fun waitForInput() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun enterDraft(text: String) {
        composeRule.onNodeWithTag("chat-input").performTextReplacement(text)
        composeRule.waitForIdle()
    }
}
