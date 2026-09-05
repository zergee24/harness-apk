package com.harnessapk.session

import com.harnessapk.chat.defaultReasoningEffort
import com.harnessapk.chat.modelForRequest
import com.harnessapk.chat.reasoningEffortForRequest
import com.harnessapk.chat.temperatureForModel
import com.harnessapk.common.AppDispatchers
import com.harnessapk.network.ChatRequest
import com.harnessapk.network.ChatStreamClient
import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.provider.ProviderRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

class MarkdownUpdatePlannerUseCase(
    private val providerRepository: ProviderRepository,
    private val client: ChatStreamClient,
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
        allowedEvidence: List<ContextFactEvidence> = emptyList(),
        suppressedContextFactKeys: Set<String> = emptySet(),
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
            allowedEvidence = allowedEvidence,
            suppressedContextFactKeys = suppressedContextFactKeys,
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
        allowedEvidence: List<ContextFactEvidence> = emptyList(),
        suppressedContextFactKeys: Set<String> = emptySet(),
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
            allowedEvidence = allowedEvidence,
            suppressedContextFactKeys = suppressedContextFactKeys,
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
        allowedEvidence: List<ContextFactEvidence>,
        suppressedContextFactKeys: Set<String>,
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
                    allowedEvidence = allowedEvidence,
                    suppressedContextFactKeys = suppressedContextFactKeys,
                ),
                temperature = temperatureForModel(requestModel),
                reasoningEffort = reasoningEffortForRequest(provider.profile, requestModel, defaultReasoningEffort()),
                apiProtocol = provider.profile.apiProtocol,
            ),
        ).collect {
            output.append(it.text)
        }
        return parseAndValidateMarkdownUpdatePlanResponse(
            response = output.toString(),
            wikiCitations = wikiCitations,
            wikiCoverage = wikiCoverage,
            allowedEvidence = allowedEvidence,
            suppressedContextFactKeys = suppressedContextFactKeys,
            existingContextMarkdown = markdowns.firstOrNull { it.path.isRootContextPath() }?.markdown,
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
    allowedEvidence: List<ContextFactEvidence> = emptyList(),
    suppressedContextFactKeys: Set<String> = emptySet(),
): List<OutgoingChatMessage> = buildMarkdownUpdatePlanningMessages(
    projectName = projectName,
    projectContext = projectContext,
    markdowns = markdowns,
    sourceText = portableAssistantMarkdownForPlanning(assistantMarkdown, wikiCitations),
    sourceLabel = "本轮助手输出",
    conversationContext = "",
    wikiCitations = wikiCitations,
    wikiCoverage = wikiCoverage,
    allowedEvidence = allowedEvidence,
    suppressedContextFactKeys = suppressedContextFactKeys,
)

fun buildMarkdownFileChangePlanningMessages(
    projectName: String,
    projectContext: String,
    markdowns: List<MarkdownSnapshot>,
    userRequest: String,
    conversationContext: String = "",
    wikiCitations: WikiMarkdownCitationSet = WikiMarkdownCitationSet.EMPTY,
    wikiCoverage: WikiEvidenceCoverage = WikiEvidenceCoverage.NONE,
    allowedEvidence: List<ContextFactEvidence> = emptyList(),
    suppressedContextFactKeys: Set<String> = emptySet(),
): List<OutgoingChatMessage> = buildMarkdownUpdatePlanningMessages(
    projectName = projectName,
    projectContext = projectContext,
    markdowns = markdowns,
    sourceText = userRequest,
    sourceLabel = "本轮用户文件变更请求",
    conversationContext = portableConversationContextForPlanning(conversationContext, wikiCitations),
    wikiCitations = wikiCitations,
    wikiCoverage = wikiCoverage,
    allowedEvidence = allowedEvidence,
    suppressedContextFactKeys = suppressedContextFactKeys,
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
    allowedEvidence: List<ContextFactEvidence>,
    suppressedContextFactKeys: Set<String>,
): List<OutgoingChatMessage> {
    val selectedMarkdowns = selectRelevantMarkdowns(
        markdowns = markdowns,
        referenceText = listOf(projectContext, conversationContext, sourceText).joinToString("\n"),
    )
    return listOf(
    OutgoingChatMessage(
        role = "system",
        text = buildMarkdownPlanningSystemPrompt(wikiCitations, wikiCoverage, allowedEvidence.isNotEmpty()),
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
            if (allowedEvidence.isNotEmpty()) {
                appendLine()
                appendLine("允许的项目 Evidence：")
                allowedEvidence.sortedBy(ContextFactEvidence::id).forEach { evidence ->
                    appendLine("- ${evidence.id}｜${evidence.authority.name}｜${evidence.sourceSha256.lowercase()}")
                }
            }
            if (suppressedContextFactKeys.isNotEmpty()) {
                appendLine()
                appendLine("本轮禁止重复的 Context Fact key：")
                suppressedContextFactKeys.sorted().forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("现有 Markdown：")
            if (selectedMarkdowns.isEmpty()) {
                appendLine("- 无")
            } else {
                selectedMarkdowns.forEach { markdown ->
                    appendLine("- ${markdown.path}｜${markdown.title}")
                    appendLine("```markdown")
                    appendLine(markdown.markdown)
                    appendLine("```")
                }
            }
            appendLine()
            appendLine("$sourceLabel：")
            appendLine(sourceText.trim())
        },
    ),
)
}

private fun selectRelevantMarkdowns(
    markdowns: List<MarkdownSnapshot>,
    referenceText: String,
): List<MarkdownSnapshot> {
    val normalizedReference = referenceText.normalizedForRelevance()
    val referenceTokens = relevanceTokens(normalizedReference)
    val context = markdowns
        .filter { it.path.isRootContextPath() }
        .minByOrNull { it.path.normalizedForRelevance() }
    val relevant = markdowns.asSequence()
        .filterNot { it === context }
        .mapNotNull { markdown ->
            val normalizedPath = markdown.path.normalizedForRelevance()
            val normalizedTitle = markdown.title.normalizedForRelevance()
            val exactPathMention = normalizedPath.isNotBlank() && normalizedReference.contains(normalizedPath)
            val exactTitleMention = normalizedTitle.length >= 2 && normalizedReference.contains(normalizedTitle)
            val candidateTokens = relevanceTokens(
                listOf(
                    markdown.path,
                    markdown.title,
                    markdown.markdown.take(MAX_MARKDOWN_CONTEXT_CHARS),
                ).joinToString("\n"),
            )
            val overlap = referenceTokens.intersect(candidateTokens).size
            val score = when {
                exactPathMention -> 1_000_000 + overlap
                exactTitleMention -> 100_000 + overlap
                overlap > 0 -> overlap
                else -> return@mapNotNull null
            }
            RankedMarkdown(markdown, score)
        }
        .sortedWith(
            compareByDescending<RankedMarkdown> { it.score }
                .thenBy { it.markdown.path },
        ).take(MAX_RELEVANT_MARKDOWN_FILES - if (context == null) 0 else 1)
        .map(RankedMarkdown::markdown)
        .toList()

    val reservedContext = context?.copy(
        markdown = context.markdown.take(minOf(MAX_MARKDOWN_CONTEXT_CHARS, MAX_TOTAL_MARKDOWN_CONTEXT_CHARS)),
    )
    var remainingChars = MAX_TOTAL_MARKDOWN_CONTEXT_CHARS - reservedContext?.markdown.orEmpty().length
    val boundedRelevant = relevant.mapNotNull { markdown ->
        if (remainingChars <= 0) return@mapNotNull null
        val content = markdown.markdown.take(minOf(MAX_MARKDOWN_CONTEXT_CHARS, remainingChars))
        remainingChars -= content.length
        markdown.copy(markdown = content)
    }
    return boundedRelevant + listOfNotNull(reservedContext)
}

private data class RankedMarkdown(
    val markdown: MarkdownSnapshot,
    val score: Int,
)

private fun String.isRootContextPath(): Boolean =
    trim().replace('\\', '/').removePrefix("./").equals("context.md", ignoreCase = true)

private fun String.normalizedForRelevance(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

private fun relevanceTokens(value: String): Set<String> {
    val normalized = value.normalizedForRelevance()
    val lexical = Regex("[\\p{L}\\p{N}_-]{2,}").findAll(normalized)
        .map(MatchResult::value)
        .filterNot { it in GENERIC_RELEVANCE_TOKENS }
        .toMutableSet()
    Regex("[\\p{IsHan}]+").findAll(normalized).forEach { match ->
        val text = match.value
        for (size in 2..3) {
            if (text.length >= size) {
                for (index in 0..text.length - size) lexical += text.substring(index, index + size)
            }
        }
    }
    return lexical
}

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
    contextFactsEnabled: Boolean,
): String {
    var prompt = BASE_MARKDOWN_PLANNING_SYSTEM_PROMPT
    if (contextFactsEnabled) {
        prompt += "\n\n" + CONTEXT_FACT_PLANNING_CONTRACT
    }
    if (wikiCitations.citations.isNotEmpty() || wikiCoverage.hasComparisonContext) {
        prompt += "\n\n" + """
            若来源内容包含 [^hwiki-*] 脚注，保留相关事实所需的脚注引用和定义。
            只能使用“可用 Wiki 脚注”中给出的来源，不得编造或改写书名、卷目、版本和位置。
            若比较覆盖信息标记某一知识库无证据，只能写“当前检索未找到依据”，不能写“该书没有记载”或“两书一致”。
            禁止输出 harness-wiki://、引用 UUID、chunk ID 和应用内部路径。
        """.trimIndent()
    }
    return prompt
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

private val CONTEXT_FACT_PLANNING_CONTRACT =
    """
        若 updates 包含根目录 context.md，必须同时在根对象输出 contextFacts；没有稳定事实时不得输出 context.md 更新。
        contextFacts 格式：
        "contextFacts": [
          {
            "section": "PROJECT_GOALS | KEY_DECISIONS | CURRENT_STATUS | FOLLOW_UP",
            "statement": "可审核的单条事实",
            "evidenceIds": ["只能取自允许的项目 Evidence"],
            "operation": "UPSERT"
          }
        ]
        每条 Context Fact 至少引用一个 Evidence，所有 evidenceIds 都必须存在于允许列表；不得使用隐藏推理或编造 Evidence。
        仅 ASSISTANT_PROPOSAL 不足以证明关键决策或当前状态。semantic key 与 Evidence hash 由应用确定性生成，不要输出。
    """.trimIndent()

private const val MAX_MARKDOWN_CONTEXT_CHARS = 6000
private const val MAX_RELEVANT_MARKDOWN_FILES = 6
private const val MAX_TOTAL_MARKDOWN_CONTEXT_CHARS = 24_000
private const val MAX_CONVERSATION_CONTEXT_CHARS = 12000
private const val MAX_WIKI_FOOTNOTE_COUNT = 40
private val GENERIC_RELEVANCE_TOKENS = setOf("md", "markdown", "context", "项目", "文件", "更新")
