package com.harnessapk.chat

import android.net.Uri

enum class MessageRole { SYSTEM, USER, ASSISTANT, ERROR }
enum class MessageStatus { PENDING, STREAMING, SUCCEEDED, FAILED, CANCELLED }

data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val projectId: String? = null,
    val promptOriginal: String,
    val promptOptimized: String,
    val promptFinal: String,
    val agentId: String? = null,
    val agentVersion: Int? = null,
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus,
    val providerId: String?,
    val model: String?,
    val errorMessage: String?,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class ChatAttachment(
    val id: String,
    val messageId: String,
    val type: String,
    val uri: String,
    val mimeType: String,
)

data class PendingImageAttachment(
    val uri: Uri,
    val mimeType: String,
)

data class ConversationMemory(
    val conversationId: String,
    val summary: String,
    val coveredThroughMessageId: String?,
    val coveredThroughCreatedAt: Long,
    val compressedMessageCount: Int,
    val updatedAt: Long,
)
