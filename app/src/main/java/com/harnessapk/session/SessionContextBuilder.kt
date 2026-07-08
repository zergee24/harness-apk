package com.harnessapk.session

import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.websearch.WebSearchContext

data class SessionRequestContext(
    val finalPrompt: String,
    val projectName: String?,
    val deliverableTitle: String?,
    val projectContext: String,
    val deliverableMarkdown: String,
)

fun buildPromptOptimizationMessages(
    rawPrompt: String,
    projectContext: String,
    deliverableMarkdown: String,
): List<OutgoingChatMessage> = listOf(
    OutgoingChatMessage(
        role = "system",
        text = "你是会话提示词优化器，只输出可直接用于当前会话的优化后提示词。",
    ),
    OutgoingChatMessage(
        role = "user",
        text = buildString {
            appendLine("请把下面的原始提示词优化成稳定的会话提示词。")
            appendLine()
            appendLine("必须包含：")
            appendLine("- 角色")
            appendLine("- 目标")
            appendLine("- 约束")
            appendLine("- 输出格式")
            appendLine()
            appendLine("原始提示词：")
            appendLine(rawPrompt.trim())
            if (projectContext.isNotBlank()) {
                appendLine()
                appendLine("项目上下文：")
                appendLine(projectContext.trim())
            }
            if (deliverableMarkdown.isNotBlank()) {
                appendLine()
                appendLine("当前 Markdown 沉淀：")
                appendLine(deliverableMarkdown.trim())
            }
        },
    ),
)

fun buildSessionOutgoingMessages(
    context: SessionRequestContext?,
    baseMessages: List<OutgoingChatMessage>,
    webSearchContext: WebSearchContext? = null,
): List<OutgoingChatMessage> {
    val systemMessages = buildList {
        if (context != null && !context.isBlank()) add(context.toSystemMessage())
        webSearchContext?.takeIf { it.results.results.isNotEmpty() }?.let { add(it.toSystemMessage()) }
    }
    if (systemMessages.isEmpty()) return baseMessages
    return listOf(OutgoingChatMessage(role = "system", text = systemMessages.joinToString("\n\n"))) + baseMessages
}

fun canWriteBackMarkdown(
    projectId: String?,
    deliverableId: String?,
    markdown: String,
): Boolean =
    !projectId.isNullOrBlank() && markdown.isNotBlank()

private fun SessionRequestContext.isBlank(): Boolean =
    finalPrompt.isBlank() &&
        projectContext.isBlank() &&
        deliverableMarkdown.isBlank() &&
        projectName.isNullOrBlank() &&
        deliverableTitle.isNullOrBlank()

private fun SessionRequestContext.toSystemMessage(): String = buildString {
    appendLine("会话提示词：")
    appendLine(finalPrompt.ifBlank { "按用户当前请求提供帮助。" }.trim())
    projectName?.takeIf { it.isNotBlank() }?.let {
        appendLine()
        appendLine("当前项目：$it")
    }
    deliverableTitle?.takeIf { it.isNotBlank() }?.let {
        appendLine("当前 Markdown 沉淀：$it")
    }
    if (projectContext.isNotBlank()) {
        appendLine()
        appendLine("项目上下文：")
        appendLine(projectContext.trim())
    }
    if (deliverableMarkdown.isNotBlank()) {
        appendLine()
        appendLine("当前 Markdown 内容：")
        appendLine(deliverableMarkdown.trim())
    }
    appendLine()
    appendLine("如果需要修改 Markdown 文件，请先输出建议内容，等待用户确认写回。")
}

private fun WebSearchContext.toSystemMessage(): String = buildString {
    appendLine("联网搜索来源：")
    results.results.forEachIndexed { index, source ->
        val number = index + 1
        appendLine("[$number] ${source.title}")
        appendLine("URL: ${source.url}")
        if (source.snippet.isNotBlank()) appendLine("摘要: ${source.snippet}")
        appendLine()
    }
    appendLine("回答中引用联网信息时必须使用 [1]、[2] 这类来源编号。")
}
