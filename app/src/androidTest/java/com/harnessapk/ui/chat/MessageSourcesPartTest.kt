package com.harnessapk.ui.chat

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harnessapk.wiki.MessageWikiCitation
import com.harnessapk.wiki.WikiCitationVerificationState
import com.harnessapk.wiki.WikiRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessageSourcesPartTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun narrowLargeFontSourcePanelExpandsAndOpensExactCitation() {
        val opened = mutableListOf<String>()
        val state = MessageSourcesUiState(
            wikiGroups = listOf(
                MessageWikiSourceGroup(
                    wikiTitle = "资治通鉴",
                    citations = listOf(citation("citation-1", 1, "卷一")),
                ),
            ),
            agentSources = listOf("资料 1 · 人物年谱 · 1901 年"),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                MaterialTheme {
                    Box(modifier = Modifier.width(320.dp)) {
                        SelectionContainer {
                            MessageSourcesPart(
                                state = state,
                                onOpenWikiCitation = opened::add,
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("引用 1 · 资治通鉴 1 · 人物资料 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("展开参考资料").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("卷一").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("资料 1 · 人物年谱 · 1901 年").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(listOf("citation-1"), opened)
        }
    }

    private fun citation(id: String, ordinal: Int, sourceTitle: String) = MessageWikiCitation(
        id = id,
        messageId = "message-1",
        displayOrdinal = ordinal,
        ref = WikiRef("history.zztj", 1),
        wikiTitle = "资治通鉴",
        documentId = "document-1",
        sectionId = "section-1",
        chunkId = "chunk-1",
        sourceTitle = sourceTitle,
        sectionPath = "卷一 / 起始",
        locatorLabel = "第一段",
        originalTextSnapshot = "臣光曰：以史为鉴。",
        originalTextSha256 = "a".repeat(64),
        answerRangesJson = "[[0,1]]",
        verificationState = WikiCitationVerificationState.VERIFIED,
        createdAt = 0L,
    )
}
