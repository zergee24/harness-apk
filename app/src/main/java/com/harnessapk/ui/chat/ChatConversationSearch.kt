package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import com.harnessapk.wiki.MessageWikiCitation

internal enum class ConversationSearchFilter(val label: String) {
    ALL("全部"),
    CONTENT("正文"),
    PROCESS("过程"),
    SOURCES("来源"),
}

internal data class ConversationSearchDocument(
    val messageId: String,
    val messageIndex: Int,
    val content: String,
    val process: String,
    val sources: String,
)

internal fun buildConversationSearchDocuments(
    messages: List<ChatMessage>,
    partsByMessageId: Map<String, List<UiMessagePartDraft>>,
    citationsByMessageId: Map<String, List<MessageWikiCitation>>,
): List<ConversationSearchDocument> = messages.mapIndexed { index, message ->
    val parts = partsByMessageId[message.id].orEmpty()
    ConversationSearchDocument(
        messageId = message.id,
        messageIndex = index,
        content = buildList {
            add(message.content)
            parts.filter { it.type == UiMessagePartType.TEXT }.forEach { add(it.content) }
        }.joinToString("\n"),
        process = parts.filter { it.type in processSearchPartTypes }.joinToString("\n") { it.content },
        sources = buildList {
            parts.filter {
                it.type == UiMessagePartType.AGENT_SOURCES ||
                    it.type == UiMessagePartType.WIKI_SOURCES ||
                    it.type == UiMessagePartType.PROJECT_SOURCES
            }
                .forEach { add(it.content) }
            citationsByMessageId[message.id].orEmpty().forEach { citation ->
                add(citation.wikiTitle)
                add(citation.sourceTitle)
                add(citation.sectionPath)
                add(citation.originalTextSnapshot)
            }
        }.joinToString("\n"),
    )
}

internal fun searchConversationDocuments(
    documents: List<ConversationSearchDocument>,
    query: String,
    filter: ConversationSearchFilter,
): List<ConversationSearchDocument> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return emptyList()
    return documents.filter { document ->
        when (filter) {
            ConversationSearchFilter.ALL -> listOf(document.content, document.process, document.sources)
                .any { it.contains(normalized, ignoreCase = true) }
            ConversationSearchFilter.CONTENT -> document.content.contains(normalized, ignoreCase = true)
            ConversationSearchFilter.PROCESS -> document.process.contains(normalized, ignoreCase = true)
            ConversationSearchFilter.SOURCES -> document.sources.contains(normalized, ignoreCase = true)
        }
    }
}

private val processSearchPartTypes = setOf(
    UiMessagePartType.REASONING,
    UiMessagePartType.TOOL_CALL,
    UiMessagePartType.TOOL_RESULT,
    UiMessagePartType.SEARCH_RESULT,
    UiMessagePartType.FILE_CHANGE,
    UiMessagePartType.ERROR_DETAIL,
)
