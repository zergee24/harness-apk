package com.harnessapk.session

import com.harnessapk.chat.ChatRepository
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.ConversationMarkdownLinkDao
import com.harnessapk.storage.ConversationMarkdownLinkEntity
import com.harnessapk.storage.MarkdownChangeDraftDao
import com.harnessapk.storage.MarkdownChangeDraftEntity
import com.harnessapk.storage.MarkdownChangeDraftItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ConversationMarkdownLink(
    val conversationId: String,
    val projectId: String,
    val relativePath: String,
    val linkedAt: Long,
    val updatedAt: Long,
)

class MarkdownNotebookRepository(
    private val chatRepository: ChatRepository,
    private val linkDao: ConversationMarkdownLinkDao,
    private val draftDao: MarkdownChangeDraftDao,
    private val timeProvider: TimeProvider,
) {
    fun observeLinks(conversationId: String): Flow<List<ConversationMarkdownLink>> =
        linkDao.observeForConversation(conversationId).map { rows -> rows.map(ConversationMarkdownLinkEntity::toDomain) }

    suspend fun linkMarkdown(conversationId: String, projectId: String, relativePath: String): Boolean {
        val conversation = requireNotNull(chatRepository.conversation(conversationId)) { "会话不存在" }
        require(conversation.projectId == projectId) { "会话不属于目标项目" }
        val normalizedPath = normalizeMarkdownLinkPath(relativePath)
        val now = timeProvider.nowMillis()
        return linkDao.insert(
            ConversationMarkdownLinkEntity(
                conversationId = conversationId,
                projectId = projectId,
                relativePath = normalizedPath,
                linkedAt = now,
                updatedAt = now,
            ),
        ) != -1L
    }

    suspend fun removeLink(conversationId: String, projectId: String, relativePath: String) {
        linkDao.delete(conversationId, projectId, normalizeMarkdownLinkPath(relativePath))
    }

    suspend fun saveDraft(
        draft: MarkdownChangeDraftEntity,
        items: List<MarkdownChangeDraftItemEntity>,
    ) {
        draftDao.upsertDraft(draft)
        draftDao.replaceItems(draft.id, items)
    }

    suspend fun draftItems(draftId: String): List<MarkdownChangeDraftItemEntity> = draftDao.listItems(draftId)
}

internal fun normalizeMarkdownLinkPath(value: String): String {
    val path = value.trim().replace('\\', '/').trim('/')
    require(path.isNotBlank() && path.endsWith(".md", ignoreCase = true)) { "只能关联 Markdown 文件" }
    require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Markdown 路径不安全" }
    return path
}

private fun ConversationMarkdownLinkEntity.toDomain() = ConversationMarkdownLink(
    conversationId = conversationId,
    projectId = projectId,
    relativePath = relativePath,
    linkedAt = linkedAt,
    updatedAt = updatedAt,
)
