package com.harnessapk.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class CaptureDraftRepository(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.applicationContext.getSharedPreferences("capture_drafts", Context.MODE_PRIVATE)
    private val _activeDraft = MutableStateFlow(loadPersisted())
    val activeDraft: StateFlow<CaptureDraft?> = _activeDraft.asStateFlow()

    @Synchronized
    fun save(draft: CaptureDraft) {
        preferences.edit().putString(ACTIVE_DRAFT_KEY, encode(draft)).commit()
        _activeDraft.value = draft
    }

    @Synchronized
    fun consume(draftId: String) {
        if (_activeDraft.value?.id != draftId) return
        preferences.edit().remove(ACTIVE_DRAFT_KEY).commit()
        _activeDraft.value = null
    }

    fun current(): CaptureDraft? = _activeDraft.value

    private fun loadPersisted(): CaptureDraft? {
        val draft = preferences.getString(ACTIVE_DRAFT_KEY, null)?.let(::decode) ?: return null
        return if (draft.expiresAt != null && draft.expiresAt <= nowMillis()) {
            preferences.edit().remove(ACTIVE_DRAFT_KEY).commit()
            null
        } else {
            draft
        }
    }

    private fun encode(draft: CaptureDraft): String = buildJsonObject {
        put("id", draft.id)
        put("source", draft.source.name)
        put("text", draft.text)
        put("status", draft.status.name)
        put("createdAt", draft.createdAt)
        draft.expiresAt?.let { put("expiresAt", it) }
        put("items", buildJsonArray {
            draft.stagedItems.forEach { item ->
                add(buildJsonObject {
                    put("id", item.id)
                    put("kind", item.kind.name)
                    put("displayName", item.displayName)
                    put("mimeType", item.mimeType)
                    put("localUri", item.localUri)
                    put("sizeBytes", item.sizeBytes)
                    put("sha256", item.sha256)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): CaptureDraft? = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        CaptureDraft(
            id = root.getValue("id").jsonPrimitive.content,
            source = CaptureSource.valueOf(root.getValue("source").jsonPrimitive.content),
            text = root["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            stagedItems = root["items"]?.jsonArray.orEmpty().map { element ->
                val item = element.jsonObject
                CaptureItem(
                    id = item.getValue("id").jsonPrimitive.content,
                    kind = CaptureItemKind.valueOf(item.getValue("kind").jsonPrimitive.content),
                    displayName = item.getValue("displayName").jsonPrimitive.content,
                    mimeType = item.getValue("mimeType").jsonPrimitive.content,
                    localUri = item.getValue("localUri").jsonPrimitive.content,
                    sizeBytes = item.getValue("sizeBytes").jsonPrimitive.longOrNull ?: 0L,
                    sha256 = item.getValue("sha256").jsonPrimitive.content,
                )
            },
            status = CaptureStatus.valueOf(root.getValue("status").jsonPrimitive.content),
            createdAt = root.getValue("createdAt").jsonPrimitive.longOrNull ?: 0L,
            expiresAt = root["expiresAt"]?.jsonPrimitive?.longOrNull,
        )
    }.getOrNull()

    private companion object {
        const val ACTIVE_DRAFT_KEY = "active"
    }
}
