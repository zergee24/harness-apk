package com.harnessapk.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownUpdatePlannerTest {
    @Test
    fun parseMarkdownUpdatePlanResponseReadsFencedJsonWithMultipleUpdates() {
        val plan = parseMarkdownUpdatePlanResponse(
            """
            下面是更新计划：

            ```json
            {
              "updates": [
                {
                  "operation": "update",
                  "path": "requirements/prd.md",
                  "title": "PRD",
                  "reason": "补充验收标准",
                  "markdown": "# PRD\n\n## 验收标准\n\n- 可审核 diff"
                },
                {
                  "operation": "create",
                  "path": "sessions/review.md",
                  "title": "会话沉淀",
                  "reason": "沉淀本轮讨论",
                  "markdown": "# 会话沉淀\n\n多文件更新"
                }
              ]
            }
            ```
            """.trimIndent(),
        )

        assertEquals(2, plan.proposals.size)
        assertEquals(MarkdownUpdateOperation.UPDATE, plan.proposals[0].operation)
        assertEquals("requirements/prd.md", plan.proposals[0].path)
        assertEquals("补充验收标准", plan.proposals[0].reason)
        assertEquals(MarkdownUpdateOperation.CREATE, plan.proposals[1].operation)
    }

    @Test
    fun buildMarkdownDiffMarksRemovedAddedAndContextLines() {
        val diff = buildMarkdownDiff(
            oldMarkdown = "# PRD\n\n旧目标\n保留行",
            newMarkdown = "# PRD\n\n新目标\n保留行\n新增验收",
        )

        assertTrue(diff.any { it.type == MarkdownDiffLineType.CONTEXT && it.text == "# PRD" })
        assertTrue(diff.any { it.type == MarkdownDiffLineType.REMOVED && it.text == "旧目标" })
        assertTrue(diff.any { it.type == MarkdownDiffLineType.ADDED && it.text == "新目标" })
        assertTrue(diff.any { it.type == MarkdownDiffLineType.ADDED && it.text == "新增验收" })
    }

    @Test
    fun buildMarkdownDiffDoesNotRemoveBlankLineForNewFile() {
        val diff = buildMarkdownDiff(
            oldMarkdown = "",
            newMarkdown = "# 新文档\n\n内容",
        )

        assertTrue(diff.none { it.type == MarkdownDiffLineType.REMOVED })
        assertEquals(MarkdownDiffLineType.ADDED, diff.first().type)
    }

    @Test
    fun markdownDiffStatsCountsAddedAndRemovedLines() {
        val stats = markdownDiffStats(
            buildMarkdownDiff(
                oldMarkdown = "# PRD\n\n旧目标\n保留行",
                newMarkdown = "# PRD\n\n新目标\n保留行\n新增验收",
            ),
        )

        assertEquals(2, stats.addedLineCount)
        assertEquals(1, stats.removedLineCount)
    }

    @Test
    fun retainedProposalSummaryCountsKeptAndWithdrawnItems() {
        val proposals = listOf(
            proposal("requirements/prd.md"),
            proposal("sessions/review.md"),
        )

        assertEquals("保留 1 项，撤回 1 项", markdownReviewSummary(proposals, retainedIndexes = setOf(0)))
    }

    @Test
    fun buildMarkdownFileChangePlanningMessagesUsesUserRequestAsSource() {
        val messages = buildMarkdownFileChangePlanningMessages(
            projectName = "Harness",
            projectContext = "移动端长期项目",
            markdowns = emptyList(),
            userRequest = "写一份 PRD",
        )

        assertTrue(messages.last().text.contains("本轮用户文件变更请求："))
        assertTrue(messages.last().text.contains("写一份 PRD"))
        assertTrue(messages.last().text.contains("现有 Markdown：\n- 无"))
    }

    private fun proposal(path: String): MarkdownUpdateProposal =
        MarkdownUpdateProposal(
            operation = MarkdownUpdateOperation.UPDATE,
            path = path,
            title = path,
            reason = "测试",
            markdown = "# ${path}",
        )
}
