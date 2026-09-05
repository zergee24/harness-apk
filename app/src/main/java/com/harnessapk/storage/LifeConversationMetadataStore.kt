package com.harnessapk.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Where a conversation was created from the V2 life entry point. */
enum class LifeConversationOrigin {
    UNKNOWN,
    LIFE_TEXT,
    LIFE_PHOTO,
    LIFE_VOICE,
    STANDARD,
}

/**
 * Metadata that is intentionally independent from the Room conversation row.
 * Missing records mean unknown/legacy data and must remain visible.
 */
data class LifeConversationMetadata(
    val origin: LifeConversationOrigin = LifeConversationOrigin.UNKNOWN,
    val userRetained: Boolean = false,
    val customTitle: String? = null,
    val undoDeadlineMillis: Long? = null,
)

/** Small persistence seam so overview logic can be tested without Android. */
interface LifeConversationMetadataStorePort {
    val entries: StateFlow<Map<String, LifeConversationMetadata>>

    fun get(conversationId: String): LifeConversationMetadata? = entries.value[conversationId]

    fun put(conversationId: String, metadata: LifeConversationMetadata)

    fun recordOrigin(conversationId: String, origin: LifeConversationOrigin) {
        val current = get(conversationId) ?: LifeConversationMetadata()
        put(conversationId, current.copy(origin = origin))
    }

    fun markUserRetained(conversationId: String, retained: Boolean = true) {
        val current = get(conversationId) ?: LifeConversationMetadata()
        put(conversationId, current.copy(userRetained = retained))
    }

    fun setCustomTitle(conversationId: String, title: String?) {
        val normalized = title?.trim()?.ifBlank { null }
        val current = get(conversationId) ?: LifeConversationMetadata()
        put(
            conversationId,
            current.copy(
                customTitle = normalized,
                userRetained = current.userRetained || normalized != null,
            ),
        )
    }

    fun setUndoDeadline(conversationId: String, deadlineMillis: Long?) {
        val current = get(conversationId) ?: LifeConversationMetadata()
        put(conversationId, current.copy(undoDeadlineMillis = deadlineMillis))
    }

    fun clearUndoDeadline(conversationId: String) = setUndoDeadline(conversationId, null)
}

/**
 * Persistent sidecar for life-only presentation facts. It avoids a Room
 * migration while still surviving process death and preserving legacy rows.
 */
class LifeConversationMetadataStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
    preferencesOverride: SharedPreferences? = null,
) : LifeConversationMetadataStorePort {
    private val preferences = preferencesOverride ?: context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()
    private val _entries = MutableStateFlow(loadAll())
    override val entries: StateFlow<Map<String, LifeConversationMetadata>> = _entries.asStateFlow()

    override fun put(conversationId: String, metadata: LifeConversationMetadata) {
        val id = conversationId.trim()
        require(id.isNotBlank()) { "conversationId 不能为空" }
        synchronized(lock) {
            val next = _entries.value.toMutableMap().apply { this[id] = metadata }
            val committed = preferences.edit()
                .putString(KEY_PREFIX + id, encode(metadata))
                .putStringSet(KEY_IDS, next.keys.toSet())
                .commit()
            if (!committed) {
                throw IllegalStateException(
                    "无法保存生活会话元数据：SharedPreferences.commit() 返回 false",
                )
            }
            _entries.value = next.toMap()
        }
    }

    private fun loadAll(): Map<String, LifeConversationMetadata> {
        val ids = preferences.getStringSet(KEY_IDS, null).orEmpty()
        val storedIds = if (ids.isNotEmpty()) ids else preferences.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
            .toSet()
        return storedIds.mapNotNull { id ->
            val raw = preferences.getString(KEY_PREFIX + id, null) ?: return@mapNotNull null
            id to decode(raw)
        }.toMap()
    }

    private fun encode(metadata: LifeConversationMetadata): String = buildJsonObject {
        put("origin", JsonPrimitive(metadata.origin.name))
        put("userRetained", JsonPrimitive(metadata.userRetained))
        metadata.customTitle?.let { put("customTitle", JsonPrimitive(it)) }
        metadata.undoDeadlineMillis?.let { put("undoDeadlineMillis", JsonPrimitive(it)) }
    }.toString()

    private fun decode(raw: String): LifeConversationMetadata = runCatching {
        val objectValue = json.parseToJsonElement(raw).jsonObject
        LifeConversationMetadata(
            origin = objectValue["origin"]?.jsonPrimitive?.contentOrNull
                ?.let { value -> runCatching { LifeConversationOrigin.valueOf(value) }.getOrNull() }
                ?: LifeConversationOrigin.UNKNOWN,
            userRetained = objectValue["userRetained"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
            customTitle = objectValue["customTitle"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
            undoDeadlineMillis = objectValue["undoDeadlineMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        )
    }.getOrDefault(LifeConversationMetadata())

    private companion object {
        const val PREFERENCES_NAME = "life_conversation_metadata"
        const val KEY_IDS = "conversation_ids"
        const val KEY_PREFIX = "conversation::"
    }
}

/** Deterministic test seam and a useful fixture source for an initial wiring pass. */
class InMemoryLifeConversationMetadataStore(
    initial: Map<String, LifeConversationMetadata> = emptyMap(),
) : LifeConversationMetadataStorePort {
    private val _entries = MutableStateFlow(initial.toMap())
    override val entries: StateFlow<Map<String, LifeConversationMetadata>> = _entries.asStateFlow()

    override fun put(conversationId: String, metadata: LifeConversationMetadata) {
        val id = conversationId.trim()
        require(id.isNotBlank()) { "conversationId 不能为空" }
        _entries.value = _entries.value + (id to metadata)
    }
}
