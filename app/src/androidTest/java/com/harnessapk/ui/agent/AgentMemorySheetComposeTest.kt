package com.harnessapk.ui.agent

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import com.harnessapk.agentmemory.AgentMemory
import com.harnessapk.agentmemory.AgentMemoryKind
import com.harnessapk.ui.theme.HarnessApkTheme
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentMemorySheetComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateKeepsOnlyExistingProfileInformationAndNoCreateAction() {
        composeRule.setContent {
            HarnessApkTheme {
                sheet(memories = emptyList())
            }
        }

        composeRule.onNodeWithText("人物资料").assertIsDisplayed()
        composeRule.onNodeWithText("固定版本：v7").assertIsDisplayed()
        composeRule.onNodeWithText("资料覆盖：2/3").assertIsDisplayed()
        composeRule.onNodeWithText("发布者指纹：fingerprint").assertIsDisplayed()
        composeRule.onNodeWithText("关系记忆").assertIsDisplayed()
        composeRule.onNodeWithText("还没有关系记忆").assertIsDisplayed()
        composeRule.onNodeWithText("新增").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("关系记忆更多").assertDoesNotExist()
    }

    @Test
    fun editValidatesInlineAndDeleteRunsWithoutConfirmation() {
        val memories = mutableStateOf(
            listOf(
                memory("address", AgentMemoryKind.ADDRESS_PREFERENCE, "叫我同志"),
                memory("preference", AgentMemoryKind.USER_PREFERENCE, "默认中文"),
            ),
        )
        val edits = mutableListOf<Pair<String, String>>()
        val deletes = mutableListOf<String>()
        composeRule.setContent {
            HarnessApkTheme {
                sheet(
                    memories = memories.value,
                    onEdit = { memory, content ->
                        edits += memory.id to content
                        memories.value = memories.value.map {
                            if (it.id == memory.id) it.copy(content = content) else it
                        }
                        true
                    },
                    onDelete = { memory ->
                        deletes += memory.id
                        memories.value = memories.value.filterNot { it.id == memory.id }
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("编辑 称呼偏好").performClick()
        composeRule.onNodeWithContentDescription("编辑关系记忆内容").performTextReplacement("   ")
        composeRule.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("关系记忆列表").performScrollToIndex(2)
        composeRule.onNodeWithText("内容不能为空").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("编辑关系记忆内容").performTextReplacement("叫我老朋友")
        composeRule.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("address" to "叫我老朋友"), edits)
        composeRule.onNodeWithText("叫我老朋友").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("删除 稳定偏好").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("preference"), deletes)
        composeRule.onNodeWithText("默认中文").assertDoesNotExist()
        composeRule.onNodeWithText("确认删除").assertDoesNotExist()
    }

    @Test
    fun clearUsesOneScopedConfirmationAndKeepsSheetOpen() {
        val memories = mutableStateOf(listOf(memory("shared", AgentMemoryKind.SHARED_HISTORY, "一起完成迁移")))
        var clearCalls = 0
        composeRule.setContent {
            HarnessApkTheme {
                sheet(
                    memories = memories.value,
                    onClear = {
                        clearCalls += 1
                        memories.value = emptyList()
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("关系记忆更多").performClick()
        composeRule.onNodeWithText("清空关系记忆").performClick()
        composeRule.onNodeWithText(agentMemoryClearConfirmationText()).assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(0, clearCalls)

        composeRule.onNodeWithContentDescription("关系记忆更多").performClick()
        composeRule.onNodeWithText("清空关系记忆").performClick()
        composeRule.onNodeWithText("清空").performClick()
        composeRule.waitForIdle()

        assertEquals(1, clearCalls)
        composeRule.onNodeWithText("人物资料").assertIsDisplayed()
        composeRule.onNodeWithText("还没有关系记忆").assertIsDisplayed()
    }

    @Test
    fun sourcesResolveExactlyAndLargeFontContentRemainsReachable() {
        val opened = mutableListOf<Pair<String, String>>()
        val memories = listOf(
            memory(
                "available",
                AgentMemoryKind.RELATIONSHIP_EVENT,
                "逐渐建立信任",
                sourceConversationId = "conversation-source",
                sourceMessageId = "message-source",
            ),
            memory("missing", AgentMemoryKind.SHARED_HISTORY, "来源已删除"),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HarnessApkTheme {
                    sheet(
                        memories = memories,
                        sourceAvailable = { it.id == "available" },
                        onOpenSource = { memory ->
                            opened += memory.sourceConversationId to memory.sourceMessageId
                        },
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithText("来源不可用").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("查看来源").performClick()
        composeRule.onNodeWithText("来源不可用").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("逐渐建立信任").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("来源已删除").performScrollTo().assertIsDisplayed()

        assertEquals(listOf("conversation-source" to "message-source"), opened)
        composeRule.onNodeWithText("人物资料").assertIsDisplayed()
    }

    @Test
    fun editCancelKeepsOriginalAndRepositoryFailureStaysInline() {
        val original = memory("preference", AgentMemoryKind.USER_PREFERENCE, "默认中文")
        var editCalls = 0
        composeRule.setContent {
            HarnessApkTheme {
                sheet(
                    memories = listOf(original),
                    onEdit = { memory, _ ->
                        editCalls += 1
                        assertEquals(original.sourceConversationId, memory.sourceConversationId)
                        assertEquals(original.sourceMessageId, memory.sourceMessageId)
                        throw IllegalStateException("secret backend detail")
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("编辑 稳定偏好").performClick()
        composeRule.onNodeWithContentDescription("编辑关系记忆内容").performTextReplacement("改为简体中文")
        composeRule.onNodeWithText("取消").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("关系记忆列表").performScrollToIndex(2)
        composeRule.onNodeWithText("默认中文").performScrollTo().assertIsDisplayed()
        assertEquals(0, editCalls)

        composeRule.onNodeWithContentDescription("编辑 稳定偏好").performClick()
        composeRule.onNodeWithContentDescription("编辑关系记忆内容").performTextReplacement("改为简体中文")
        composeRule.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(1, editCalls)
        composeRule.onNodeWithTag("关系记忆列表").performScrollToIndex(2)
        composeRule.onNodeWithText("保存失败，请重试").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("secret backend detail").assertDoesNotExist()
    }

    @Test
    fun cancellationDoesNotBecomeAnInlineFailure() {
        val original = memory("preference", AgentMemoryKind.USER_PREFERENCE, "默认中文")
        composeRule.setContent {
            HarnessApkTheme {
                sheet(
                    memories = listOf(original),
                    onEdit = { _, _ -> throw CancellationException("composition closed") },
                )
            }
        }

        composeRule.onNodeWithContentDescription("编辑 稳定偏好").performClick()
        composeRule.onNodeWithContentDescription("编辑关系记忆内容").performTextReplacement("改为简体中文")
        composeRule.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("保存失败，请重试").assertDoesNotExist()
        composeRule.onNodeWithText("composition closed").assertDoesNotExist()
        composeRule.onNodeWithText("人物资料").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun sheet(
        memories: List<AgentMemory>,
        sourceAvailable: suspend (AgentMemory) -> Boolean = { true },
        onOpenSource: (AgentMemory) -> Unit = {},
        onEdit: suspend (AgentMemory, String) -> Boolean = { _, _ -> true },
        onDelete: suspend (AgentMemory) -> Boolean = { true },
        onClear: suspend () -> Boolean = { true },
    ) {
        AgentMemorySheet(
            agentId = "agent-a",
            version = 7,
            installedCorpusCount = 2,
            requiredCorpusCount = 3,
            publisherFingerprint = "fingerprint",
            memories = memories,
            sourceAvailable = sourceAvailable,
            onOpenSource = onOpenSource,
            onEdit = onEdit,
            onDelete = onDelete,
            onClear = onClear,
            onDismiss = {},
        )
    }

    private fun memory(
        id: String,
        kind: AgentMemoryKind,
        content: String,
        sourceConversationId: String = "conversation-$id",
        sourceMessageId: String = "message-$id",
    ) = AgentMemory(
        id = id,
        agentId = "agent-a",
        kind = kind,
        content = content,
        sourceConversationId = sourceConversationId,
        sourceMessageId = sourceMessageId,
        confidence = 0.9,
        userEdited = false,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
