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
    ): MarkdownUpdatePlan = withContext(dispatchers.io) {
        val content = assistantMarkdown.trim()
        require(content.isNotBlank()) { "没有可沉淀的助手输出" }
        planWithSource(
            projectName = projectName,
            projectContext = projectContext,
            markdowns = markdowns,
            sourceText = content,
            sourceLabel = "本轮助手输出",
            providerId = providerId,
            modelOverride = modelOverride,
        )
    }

    suspend fun planFromUserRequest(
        projectName: String,
        projectContext: String,
        markdowns: List<MarkdownSnapshot>,
        userRequest: String,
        providerId: String?,
        modelOverride: String?,
    ): MarkdownUpdatePlan = withContext(dispatchers.io) {
        val content = userRequest.trim()
        require(content.isNotBlank()) { "文件变更请求不能为空" }
        planWithSource(
            projectName = projectName,
            projectContext = projectContext,
            markdowns = markdowns,
            sourceText = content,
            sourceLabel = "本轮用户文件变更请求",
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
                ),
                temperature = temperatureForModel(requestModel),
                reasoningEffort = reasoningEffortForRequest(provider.profile, requestModel, defaultReasoningEffort()),
            ),
        ).collect {
            output.append(it.text)
        }
        return parseMarkdownUpdatePlanResponse(output.toString())
    }
}

fun buildMarkdownUpdatePlanningMessages(
    projectName: String,
    projectContext: String,
    markdowns: List<MarkdownSnapshot>,
    assistantMarkdown: String,
): List<OutgoingChatMessage> = buildMarkdownUpdatePlanningMessages(
    projectName = projectName,
    projectContext = projectContext,
    markdowns = markdowns,
    sourceText = assistantMarkdown,
    sourceLabel = "本轮助手输出",
)

fun buildMarkdownFileChangePlanningMessages(
    projectName: String,
    projectContext: String,
    markdowns: List<MarkdownSnapshot>,
    userRequest: String,
): List<OutgoingChatMessage> = buildMarkdownUpdatePlanningMessages(
    projectName = projectName,
    projectContext = projectContext,
    markdowns = markdowns,
    sourceText = userRequest,
    sourceLabel = "本轮用户文件变更请求",
)

private fun buildMarkdownUpdatePlanningMessages(
    projectName: String,
    projectContext: String,
    markdowns: List<MarkdownSnapshot>,
    sourceText: String,
    sourceLabel: String,
): List<OutgoingChatMessage> = listOf(
    OutgoingChatMessage(
        role = "system",
        text = """
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

private const val MAX_MARKDOWN_CONTEXT_CHARS = 6000
