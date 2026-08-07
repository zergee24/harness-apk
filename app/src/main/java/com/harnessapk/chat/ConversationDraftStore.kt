package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ConversationDraft(
    val text: String = "",
    val attachments: List<PendingImageAttachment> = emptyList(),
)

class ConversationDraftStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "conversation_drafts",
        Context.MODE_PRIVATE,
    )

    fun load(conversationId: String): ConversationDraft {
        val raw = preferences.getString(conversationId, null) ?: return ConversationDraft()
        return runCatching {
            val persisted = json.parseToJsonElement(raw).jsonObject
            ConversationDraft(
                text = persisted["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                attachments = persisted["attachments"]?.jsonArray.orEmpty().map { element ->
                    val attachment = element.jsonObject
                    PendingImageAttachment(
                        Uri.parse(attachment.getValue("uri").jsonPrimitive.content),
                        attachment.getValue("mimeType").jsonPrimitive.content,
                    )
                },
            )
        }.getOrElse { ConversationDraft() }
    }

    fun save(conversationId: String, draft: ConversationDraft) {
        if (draft.text.isEmpty() && draft.attachments.isEmpty()) {
            preferences.edit().remove(conversationId).apply()
            return
        }
        val persisted = buildJsonObject {
            put("text", draft.text)
            put("attachments", buildJsonArray {
                draft.attachments.forEach { attachment ->
                    add(buildJsonObject {
                        put("uri", attachment.uri.toString())
                        put("mimeType", attachment.mimeType)
                    })
                }
            })
        }
        preferences.edit().putString(conversationId, persisted.toString()).apply()
    }

    fun clear(conversationId: String) {
        preferences.edit().remove(conversationId).apply()
    }
}
