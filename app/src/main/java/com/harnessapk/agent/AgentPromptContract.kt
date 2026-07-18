package com.harnessapk.agent

import com.harnessapk.chat.StreamingMessageSnapshot
import com.harnessapk.chat.UiMessagePartType
import com.harnessapk.storage.AgentVersionEntity

internal fun buildAgentSystemPrompt(
    version: AgentVersionEntity,
    evidence: List<AgentEvidence>,
): String = buildString {
    appendLine("你以包内人物身份使用第一人称与用户交谈；这是基于资料模拟，不得冒充真实人物。")
    appendLine("先直接回答用户，再说明结论成立的条件。默认使用自然段，内容确有并列关系时才使用列表。")
    appendLine("历史事实、人物经历和核心立场必须由人物资料支持；不得用通用知识补写。")
    appendLine("问候、承接前文和关系互动不要求原文证据，可自然回应。")
    appendLine("今日事实只来自本轮、当前会话和已经提供的项目上下文。条件不足时先给方法判断，再只追问一个最关键的现实条件。")
    appendLine("不要提资料库、chunk、资料编号、内部文件位置或惯例性的资料充分性声明。用户直接询问真实性时才明确说明是基于资料模拟。")
    appendLine("不要机械套用“以下三点”“我的回答不是……而是……”等通用 AI 结构。")
    appendLine()
    appendLine("身份内核：")
    appendLine(version.persona.trim())
    version.worldviewJsonl.trim().takeIf(String::isNotBlank)?.let {
        appendLine()
        appendLine("人物立场：")
        appendLine(it)
    }
    if (evidence.isNotEmpty()) {
        appendLine()
        appendLine("可用于本轮事实与立场判断的原始证据：")
        evidence.forEach { item ->
            appendLine(item.text.trim())
            appendLine()
        }
    }
}

internal fun sanitizeAgentCitationMarkers(
    snapshot: StreamingMessageSnapshot,
): StreamingMessageSnapshot = snapshot.copy(
    parts = snapshot.parts.map { part ->
        if (part.type == UiMessagePartType.TEXT) {
            part.copy(content = part.content.replace(Regex("""\[资料\s*\d+\]"""), ""))
        } else {
            part
        }
    },
)
