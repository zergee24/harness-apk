package com.harnessapk.session

import com.harnessapk.chat.defaultReasoningEffort
import com.harnessapk.chat.modelForRequest
import com.harnessapk.chat.reasoningEffortForRequest
import com.harnessapk.chat.temperatureForModel
import com.harnessapk.common.AppDispatchers
import com.harnessapk.network.ChatRequest
import com.harnessapk.network.OpenAiCompatibleClient
import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.provider.ProviderRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

class MarkdownUpdatePlannerUseCase(
    private val providerRepository: ProviderRepository,
    private val client: OpenAiCompatibleClient,
    private val dispatchers: AppDispatchers,
) {
    suspend fun plan(
        projectName: String,
        projectContext: String,
        markdowns: List<MarkdownSnapshot>,
        assistantMarkdown: String,
        providerId: String?,
        modelOverride: String?,
        wikiCitations: WikiMarkdownCitationSet = WikiMarkdownCitationSet.EMPTY,
        wikiCoverage: WikiEvidenceCoverage = WikiEvidenceCoverage.NONE,
    ): MarkdownUpdatePlan = withContext(dispatchers.io) {
        val content = assistantMarkdown.trim()
        require(content.isNotBlank()) { "没有可沉淀的助手输出" }
        planWithSource(
            projectName = projectName,
            projectContext = projectContext,
            markdowns = markdowns,
            sourceText = portableAssistantMarkdownForPlanning(content, wikiCitations),
            sourceLabel = "本轮助手输出",
            wikiCitations = wikiCitations,
            wikiCoverage = wikiCoverage,
            providerId = providerId,
            modelOverride = modelOverride,
        )
    }

    suspend fun planFromUserRequest(
        projectName: String,
        projectContext: String,
        markdowns: List<MarkdownSnapshot>,
        userRequest: String,
        conversationContext: String = "",
        providerId: String?,
        modelOverride: String?,
        wikiCitations: WikiMarkdownCitationSet = WikiMarkdownCitationSet.EMPTY,
        wikiCoverage: WikiEvidenceCoverage = WikiEvidenceCoverage.NONE,
    ): MarkdownUpdatePlan = withContext(dispatchers.io) {
        val content = userRequest.trim()
        require(content.isNotBlank()) { "文件变更请求不能为空" }
        planWithSource(
            projectName = projectName,
            projectContext = projectContext,
            markdowns = markdowns,
            sourceText = content,
            sourceLabel = "本轮用户文件变更请求",
            conversationContext = portableConversationContextForPlanning(conversationContext, wikiCitations),
            wikiCitations = wikiCitations,
            wikiCoverage = wikiCoverage,
            providerId = providerId,
            modelOverride = modelOverride,
        )
    }

    private suspend fun planWithSource(
        projectName: String,
        projectContext: String,
        markdowns: List<MarkdownSnapshot>,
        sourceText: String,
        sourceLabel: String,
        conversationContext: String = "",
        wikiCitations: WikiMarkdownCitationSet,
        wikiCoverage: WikiEvidenceCoverage,
        providerId: String?,
        modelOverride: String?,
    ): MarkdownUpdatePlan {
        val provider = providerId?.let { providerRepository.providerWithKey(it) }
            ?: providerRepository.defaultProviderForText()
        val selectedModel = modelOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: provider.profile.defaultModel
        val requestModel = modelForRequest(selectedModel)
        val output = StringBuilder()
        client.streamChat(
            ChatRequest(
                baseUrl = provider.profile.baseUrl,
                apiKey = provider.apiKey,
                model = requestModel,
                messages = buildMarkdownUpdatePlanningMessages(
                    projectName = projectName,
                    projectContext = projectContext,
                    markdowns = markdowns,
                    sourceText = sourceText,
                    sourceLabel = sourceLabel,
                    conversationContext = conversationContext,
                    wikiCitations = wikiCitations,
                    wikiCoverage = wikiCoverage,
                ),
                temperature = temperatureForModel(requestModel),
                reasoningEffort = reasoningEffortForRequest(provider.profile, requestModel, defaultReasoningEffort()),
            ),
        ).collect {
            output.append(it.text)
        }
        return parseAndValidateMarkdownUpdatePlanResponse(
            response = output.toString(),
            wikiCitations = wikiCitations,
            wikiCoverage = wikiCoverage,
        )
    }
}

fun buildMarkdownUpdatePlanningMessages(
    projectName: String,
    projectContext: String,
    markdowns: List<MarkdownSnapshot>,
    assistantMarkdown: String,
    wikiCitations: WikiMarkdownCitationSet = WikiMarkdownCitationSet.EMPTY,
    wikiCoverage: WikiEvidenceCoverage = WikiEvidenceCoverage.NONE,
): List<OutgoingChatMessage> = buildMarkdownUpdatePlanningMessages(
    projectName = projectName,
    projectContext = projectContext,
    markdowns = markdowns,
    sourceText = portableAssistantMarkdownForPlanning(assistantMarkdown, wikiCitations),
    sourceLabel = "本轮助手输出",
    conversationContext = "",
    wikiCitations = wikiCitations,
    wikiCoverage = wikiCoverage,
)

fun buildMarkdownFileChangePlanningMessages(
    projectName: String,
    projectContext: String,
    markdowns: List<MarkdownSnapshot>,
    userRequest: String,
    conversationContext: String = "",
    wikiCitations: WikiMarkdownCitationSet = WikiMarkdownCitationSet.EMPTY,
    wikiCoverage: WikiEvidenceCoverage = WikiEvidenceCoverage.NONE,
): List<OutgoingChatMessage> = buildMarkdownUpdatePlanningMessages(
    projectName = projectName,
    projectContext = projectContext,
    markdowns = markdowns,
    sourceText = userRequest,
    sourceLabel = "本轮用户文件变更请求",
    conversationContext = portableConversationContextForPlanning(conversationContext, wikiCitations),
    wikiCitations = wikiCitations,
    wikiCoverage = wikiCoverage,
)

private fun buildMarkdownUpdatePlanningMessages(
    projectName: String,
    projectContext: String,
    markdowns: List<MarkdownSnapshot>,
    sourceText: String,
    sourceLabel: String,
    conversationContext: String,
    wikiCitations: WikiMarkdownCitationSet,
    wikiCoverage: WikiEvidenceCoverage,
): List<OutgoingChatMessage> = listOf(
    OutgoingChatMessage(
        role = "system",
        text = buildMarkdownPlanningSystemPrompt(wikiCitations, wikiCoverage),
    ),
    OutgoingChatMessage(
        role = "user",
        text = buildString {
            appendLine("项目：${projectName.ifBlank { "未命名项目" }}")
            if (projectContext.isNotBlank()) {
                appendLine()
                appendLine("项目上下文：")
                appendLine(projectContext.trim())
            }
            if (conversationContext.isNotBlank()) {
                appendLine()
                appendLine("会话上下文：")
                appendLine(conversationContext.trim().take(MAX_CONVERSATION_CONTEXT_CHARS))
            }
            if (wikiCitations.citations.isNotEmpty()) {
                appendLine()
                appendLine("可用 Wiki 脚注：")
                wikiCitations.portableFootnoteDefinitions().forEach(::appendLine)
            }
            wikiCoverage.promptSummary()?.let { summary ->
                appendLine()
                appendLine("比较覆盖信息：")
                append(summary)
            }
            appendLine()
            appendLine("现有 Markdown：")
            if (markdowns.isEmpty()) {
                appendLine("- 无")
            } else {
                markdowns.forEach { markdown ->
                    appendLine("- ${markdown.path}｜${markdown.title}")
                    appendLine("```markdown")
                    appendLine(markdown.markdown.take(MAX_MARKDOWN_CONTEXT_CHARS))
                    appendLine("```")
                }
            }
            appendLine()
            appendLine("$sourceLabel：")
            appendLine(sourceText.trim())
        },
    ),
)

private fun portableAssistantMarkdownForPlanning(
    assistantMarkdown: String,
    wikiCitations: WikiMarkdownCitationSet,
): String = WikiMarkdownCitationFormatter.toPortableMarkdown(assistantMarkdown, wikiCitations)

private fun portableConversationContextForPlanning(
    conversationContext: String,
    wikiCitations: WikiMarkdownCitationSet,
): String = WikiMarkdownCitationFormatter.toPortableMarkdown(conversationContext, wikiCitations)

private fun buildMarkdownPlanningSystemPrompt(
    wikiCitations: WikiMarkdownCitationSet,
    wikiCoverage: WikiEvidenceCoverage,
): String {
    if (wikiCitations.citations.isEmpty() && !wikiCoverage.hasComparisonContext) {
        return BASE_MARKDOWN_PLANNING_SYSTEM_PROMPT
    }
    return BASE_MARKDOWN_PLANNING_SYSTEM_PROMPT + "\n\n" + """
        若来源内容包含 [^hwiki-*] 脚注，保留相关事实所需的脚注引用和定义。
        只能使用“可用 Wiki 脚注”中给出的来源，不得编造或改写书名、卷目、版本和位置。
        若比较覆盖信息标记某一知识库无证据，只能写“当前检索未找到依据”，不能写“该书没有记载”或“两书一致”。
        禁止输出 harness-wiki://、引用 UUID、chunk ID 和应用内部路径。
    """.trimIndent()
}

private fun WikiMarkdownCitationSet.portableFootnoteDefinitions(): List<String> {
    if (citations.size > MAX_WIKI_FOOTNOTE_COUNT) {
        throw WikiMarkdownCitationException("项目 Markdown 最多支持 $MAX_WIKI_FOOTNOTE_COUNT 条 Wiki 引用")
    }
    return citations.map { citation ->
        "[^${footnoteLabel(citation)}]: ${formatWikiFootnoteDefinition(citation)}"
    }
}

private fun WikiEvidenceCoverage.promptSummary(): String? {
    if (!hasComparisonContext) return null
    return buildString {
        appendLine("- 请求比较 Wiki：${requestedComparisonRefs.size}")
        appendLine("- 已查询 Wiki：${queriedRefs.intersect(requestedComparisonRefs).size}")
        appendLine("- 已核验引用：${verifiedCitationCounts.values.sum()}")
        append("- 缺少可靠证据：${missingComparisonRefs.size}")
    }
}

private val BASE_MARKDOWN_PLANNING_SYSTEM_PROMPT =
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
    """.trimIndent()

private const val MAX_MARKDOWN_CONTEXT_CHARS = 6000
private const val MAX_CONVERSATION_CONTEXT_CHARS = 12000
private const val MAX_WIKI_FOOTNOTE_COUNT = 40
