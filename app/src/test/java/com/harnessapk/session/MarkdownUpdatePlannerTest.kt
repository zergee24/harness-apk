package com.harnessapk.session

import com.harnessapk.projectsearch.ProjectSourceAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownUpdatePlannerTest {
    @Test
    fun parseMarkdownUpdatePlanResponseReadsStructuredContextFacts() {
        val plan = parseMarkdownUpdatePlanResponse(
            """
            {
              "updates": [
                {
                  "operation": "update",
                  "path": "context.md",
                  "title": "项目上下文",
                  "reason": "记录已确认决策",
                  "markdown": "# 项目上下文\n\n继续使用本地 FTS"
                }
              ],
              "contextFacts": [
                {
                  "section": "KEY_DECISIONS",
                  "statement": "继续使用本地 FTS",
                  "evidenceIds": ["evidence-1"],
                  "operation": "UPSERT"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, plan.contextFacts.size)
        assertEquals(ContextSection.KEY_DECISIONS, plan.contextFacts.single().section)
        assertEquals("继续使用本地 FTS", plan.contextFacts.single().statement)
        assertEquals(listOf("evidence-1"), plan.contextFacts.single().evidenceIds)
        assertEquals(FactOperation.UPSERT, plan.contextFacts.single().operation)
    }

    @Test
    fun parseAndValidateKeepsContextUpdateOnlyWithAcceptedContextFact() {
        val plan = parseAndValidateMarkdownUpdatePlanResponse(
            response = contextFactResponse(evidenceIds = listOf("evidence-1")),
            allowedEvidence = listOf(
                ContextFactEvidence("evidence-1", ProjectSourceAuthority.USER_STATED, "a".repeat(64)),
            ),
        )

        assertEquals(listOf("context.md", "reports/review.md"), plan.proposals.map { it.path })
        assertEquals(1, plan.contextFacts.size)
        assertTrue(plan.contextFacts.single().semanticKey.isNotBlank())
    }

    @Test
    fun parseAndValidateBuildsContextMarkdownOnlyFromAcceptedFacts() {
        val response = contextFactResponse(evidenceIds = listOf("evidence-1"))
            .replace(
                "# 项目上下文\\n\\n继续使用本地 FTS",
                "# 项目上下文\\n\\n## 关键决策\\n\\n- 未经 Evidence 验证的模型注入",
            )

        val plan = parseAndValidateMarkdownUpdatePlanResponse(
            response = response,
            allowedEvidence = listOf(
                ContextFactEvidence("evidence-1", ProjectSourceAuthority.USER_STATED, "a".repeat(64)),
            ),
        )

        val contextMarkdown = plan.proposals.single { it.path == "context.md" }.markdown
        assertTrue(contextMarkdown.contains("- 继续使用本地 FTS"))
        assertFalse(contextMarkdown.contains("未经 Evidence 验证的模型注入"))
    }

    @Test
    fun parseAndValidatePreservesExistingContextAndAppendsFactInsideItsSection() {
        val existing = """
            # Harness 项目

            项目说明保持原样。

            ## 项目目标

            - 完成移动端闭环

            ## 关键决策

            - 不自动 Push

            ## 当前状态

            - M3 实施中

            ## 自定义附录

            这段自定义内容也必须保留。
        """.trimIndent() + "\n"

        val plan = parseAndValidateMarkdownUpdatePlanResponse(
            response = contextFactResponse(evidenceIds = listOf("evidence-1")),
            allowedEvidence = listOf(
                ContextFactEvidence("evidence-1", ProjectSourceAuthority.USER_STATED, "a".repeat(64)),
            ),
            existingContextMarkdown = existing,
        )

        val contextMarkdown = plan.proposals.single { it.path == "context.md" }.markdown
        assertTrue(contextMarkdown.startsWith("# Harness 项目\n\n项目说明保持原样。"))
        assertTrue(contextMarkdown.contains("- 不自动 Push\n\n- 继续使用本地 FTS\n\n## 当前状态"))
        assertTrue(contextMarkdown.contains("## 自定义附录\n\n这段自定义内容也必须保留。"))
    }

    @Test
    fun parseAndValidateDropsContextProposalWhenAcceptedFactAlreadyExistsInSection() {
        val existing = """
            # 项目上下文

            ## 关键决策

            - 继续使用本地 FTS
        """.trimIndent() + "\n"

        val plan = parseAndValidateMarkdownUpdatePlanResponse(
            response = contextFactResponse(evidenceIds = listOf("evidence-1")),
            allowedEvidence = listOf(
                ContextFactEvidence("evidence-1", ProjectSourceAuthority.USER_STATED, "a".repeat(64)),
            ),
            existingContextMarkdown = existing,
        )

        assertEquals(listOf("reports/review.md"), plan.proposals.map(MarkdownUpdateProposal::path))
        assertTrue(plan.contextFacts.isEmpty())
    }

    @Test
    fun parseAndValidateDropsContextUpdateWhenFactReferencesUnknownEvidence() {
        val plan = parseAndValidateMarkdownUpdatePlanResponse(
            response = contextFactResponse(evidenceIds = listOf("missing")),
            allowedEvidence = listOf(
                ContextFactEvidence("evidence-1", ProjectSourceAuthority.USER_STATED, "a".repeat(64)),
            ),
        )

        assertEquals(listOf("reports/review.md"), plan.proposals.map { it.path })
        assertTrue(plan.contextFacts.isEmpty())
    }

    @Test
    fun parseAndValidateDoesNotPersistFactWithoutContextProposal() {
        val response = contextFactResponse(evidenceIds = listOf("evidence-1"))
            .replace(
                Regex("""\{\s*"operation": "update",\s*"path": "context\.md".*?\},""", RegexOption.DOT_MATCHES_ALL),
                "",
            )
        val plan = parseAndValidateMarkdownUpdatePlanResponse(
            response = response,
            allowedEvidence = listOf(
                ContextFactEvidence("evidence-1", ProjectSourceAuthority.USER_STATED, "a".repeat(64)),
            ),
        )

        assertEquals(listOf("reports/review.md"), plan.proposals.map { it.path })
        assertTrue(plan.contextFacts.isEmpty())
    }

    @Test
    fun planningPromptListsAllowedEvidenceAndContextFactContract() {
        val evidence = ContextFactEvidence(
            id = "evidence-1",
            authority = ProjectSourceAuthority.REVIEWED_ARTIFACT,
            sourceSha256 = "a".repeat(64),
        )
        val messages = buildMarkdownUpdatePlanningMessages(
            projectName = "Harness",
            projectContext = "",
            markdowns = emptyList(),
            assistantMarkdown = "记录已确认决策",
            allowedEvidence = listOf(evidence),
            suppressedContextFactKeys = setOf("key_decisions:old"),
        )

        assertTrue(messages.first().text.contains("contextFacts"))
        assertTrue(messages.first().text.contains("context.md"))
        assertTrue(messages.first().text.contains("evidenceIds"))
        assertTrue(messages.last().text.contains("允许的项目 Evidence："))
        assertTrue(messages.last().text.contains("evidence-1｜REVIEWED_ARTIFACT｜${"a".repeat(64)}"))
        assertTrue(messages.last().text.contains("本轮禁止重复的 Context Fact key："))
        assertTrue(messages.last().text.contains("key_decisions:old"))
    }

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
    fun parseMarkdownUpdatePlanResponseRejectsTextFenceWithReadableError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parseMarkdownUpdatePlanResponse(
                """
                ```text
                test/
                ├── context.md
                └── README.md
                ```
                """.trimIndent(),
            )
        }

        assertEquals("LLM 未返回 Markdown 更新 JSON", error.message)
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
            conversationContext = "用户：讨论 App 项目方向\n助手：建议先做 Markdown 项目工作台",
        )

        assertTrue(messages.last().text.contains("会话上下文："))
        assertTrue(messages.last().text.contains("建议先做 Markdown 项目工作台"))
        assertTrue(messages.last().text.contains("本轮用户文件变更请求："))
        assertTrue(messages.last().text.contains("写一份 PRD"))
        assertTrue(messages.last().text.contains("现有 Markdown：\n- 无"))
    }

    @Test
    fun buildMarkdownUpdatePlanningMessagesPreservesNoWikiPayloadByteForByte() {
        val messages = buildMarkdownUpdatePlanningMessages(
            projectName = "Harness",
            projectContext = "移动端长期项目",
            markdowns = listOf(
                MarkdownSnapshot(
                    id = "markdown-1",
                    title = "PRD",
                    path = "requirements/prd.md",
                    markdown = "# PRD\n\n已有内容",
                ),
            ),
            assistantMarkdown = "补充 PRD 验收标准",
        )

        assertEquals(
            """
                你是项目 Markdown 自动管理器。你只能输出 JSON，不要输出解释。
                你需要根据助手输出，决定要创建或更新哪些 Markdown 文件。
                支持多文件更新；禁止删除文件；禁止输出非 Markdown 内容。
                JSON 格式：
                {
                  "updates": [
                    {
                      "operation": "create 或 update",
                      "path": "项目内相对路径，必须以 .md 结尾",
                      "title": "Markdown 标题",
                      "reason": "为什么这样更新",
                      "markdown": "完整 Markdown 内容"
                    }
                  ]
                }
            """.trimIndent(),
            messages.first().text,
        )
        assertEquals(
            """
                项目：Harness

                项目上下文：
                移动端长期项目

                现有 Markdown：
                - requirements/prd.md｜PRD
                ```markdown
                # PRD

                已有内容
                ```

                本轮助手输出：
                补充 PRD 验收标准
            """.trimIndent() + "\n",
            messages.last().text,
        )
    }

    @Test
    fun planningPromptPrioritizesMentionedPathWithinFileAndCharacterBudgets() {
        val markdowns = buildList {
            add(
                MarkdownSnapshot(
                    id = "context",
                    title = "项目上下文",
                    path = "context.md",
                    markdown = "# 项目上下文\n\n## 当前状态\n\n" + "上下文".repeat(4_000),
                ),
            )
            repeat(8) { index ->
                add(
                    MarkdownSnapshot(
                        id = "irrelevant-$index",
                        title = "无关记录 $index",
                        path = "notes/irrelevant-$index.md",
                        markdown = "# 无关记录 $index\n\n" + "无关内容".repeat(4_000),
                    ),
                )
            }
            add(
                MarkdownSnapshot(
                    id = "target",
                    title = "Room 迁移验收",
                    path = "docs/target.md",
                    markdown = "# Room 迁移验收\n\n目标内容" + "迁移证据".repeat(4_000),
                ),
            )
        }

        val prompt = buildMarkdownUpdatePlanningMessages(
            projectName = "Harness",
            projectContext = "",
            markdowns = markdowns,
            assistantMarkdown = "请更新 docs/target.md 的 Room 迁移验收结论",
        ).last().text

        assertTrue(prompt.contains("docs/target.md｜Room 迁移验收"))
        assertTrue(prompt.contains("context.md｜项目上下文"))
        assertTrue(prompt.split("```markdown").size - 1 <= 6)
        assertTrue("prompt chars=${prompt.length}", prompt.length <= 30_000)
    }

    @Test
    fun planningPromptReservesContextWhenRelevantCandidatesFillTheFileBudget() {
        val markdowns = buildList {
            add(MarkdownSnapshot("context", "项目上下文", "context.md", "# 项目上下文\n\n## 当前状态\n\nM3 实施中"))
            repeat(7) { index ->
                add(
                    MarkdownSnapshot(
                        id = "room-$index",
                        title = "Room 验收 $index",
                        path = "reports/room-$index.md",
                        markdown = "# Room 验收 $index\n\nRoom 迁移证据完整",
                    ),
                )
            }
        }

        val prompt = buildMarkdownUpdatePlanningMessages(
            projectName = "Harness",
            projectContext = "",
            markdowns = markdowns,
            assistantMarkdown = "总结 Room 迁移验收",
        ).last().text

        assertTrue(prompt.contains("context.md｜项目上下文"))
        assertTrue(prompt.split("```markdown").size - 1 <= 6)
    }

    private fun proposal(path: String): MarkdownUpdateProposal =
        MarkdownUpdateProposal(
            operation = MarkdownUpdateOperation.UPDATE,
            path = path,
            title = path,
            reason = "测试",
            markdown = "# ${path}",
        )

    private fun contextFactResponse(evidenceIds: List<String>): String =
        """
        {
          "updates": [
            {
              "operation": "update",
              "path": "context.md",
              "title": "项目上下文",
              "reason": "记录已确认决策",
              "markdown": "# 项目上下文\n\n继续使用本地 FTS"
            },
            {
              "operation": "create",
              "path": "reports/review.md",
              "title": "验收记录",
              "reason": "保留报告",
              "markdown": "# 验收记录"
            }
          ],
          "contextFacts": [
            {
              "section": "KEY_DECISIONS",
              "statement": "继续使用本地 FTS",
              "evidenceIds": [${evidenceIds.joinToString { "\"$it\"" }}],
              "operation": "UPSERT"
            }
          ]
        }
        """.trimIndent()
}
