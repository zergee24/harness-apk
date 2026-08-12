package com.harnessapk.remote

import com.harnessapk.storage.RemoteCommandOutboxEntity
import com.harnessapk.storage.RemoteDao
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class RemoteCommandStatus {
    PENDING,
    SENT,
    ACCEPTED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

interface RemoteCommandStore {
    suspend fun insert(command: RemoteCommandOutboxEntity)
    suspend fun find(commandId: String): RemoteCommandOutboxEntity?
    suspend fun upsert(command: RemoteCommandOutboxEntity)
    suspend fun retryable(now: Long): List<RemoteCommandOutboxEntity>
}

class RoomRemoteCommandStore(
    private val dao: RemoteDao,
) : RemoteCommandStore {
    override suspend fun insert(command: RemoteCommandOutboxEntity) = dao.insertCommand(command)
    override suspend fun find(commandId: String): RemoteCommandOutboxEntity? = dao.command(commandId)
    override suspend fun upsert(command: RemoteCommandOutboxEntity) = dao.upsertCommand(command)
    override suspend fun retryable(now: Long): List<RemoteCommandOutboxEntity> = dao.retryableCommands(now)
}

data class RebuiltRemoteCommand(
    val commandId: String,
    val runId: String?,
    val type: String,
    val payload: JsonObject,
    val payloadJson: String,
    val payloadSha256: String,
    val status: RemoteCommandStatus,
    val attemptCount: Int,
)

class RemoteCommandOutbox(
    private val store: RemoteCommandStore,
) {
    suspend fun enqueue(
        commandId: String,
        runId: String?,
        type: String,
        payload: JsonElement,
        now: Long,
    ): RebuiltRemoteCommand {
        require(commandId.isNotBlank()) { "commandId is required" }
        require(type.isNotBlank()) { "command type is required" }
        val payloadJson = canonicalJson(payload)
        val payloadSha256 = sha256(payloadJson)
        val existing = store.find(commandId)
        if (existing != null) {
            require(existing.type == type && existing.runId == runId && existing.payloadSha256 == payloadSha256) {
                "commandId already belongs to another payload"
            }
            return rebuild(existing)
        }
        val entity = RemoteCommandOutboxEntity(
            commandId = commandId,
            runId = runId,
            type = type,
            payloadJson = payloadJson,
            payloadSha256 = payloadSha256,
            status = RemoteCommandStatus.PENDING.name,
            attemptCount = 0,
            nextAttemptAt = now,
            lastAttemptAt = null,
            acknowledgedAt = null,
            completedAt = null,
            resultJson = null,
            lastError = null,
            createdAt = now,
            updatedAt = now,
        )
        store.insert(entity)
        return rebuild(entity)
    }

    suspend fun rebuild(commandId: String): RebuiltRemoteCommand? =
        store.find(commandId)?.let(::rebuild)

    suspend fun retryable(now: Long): List<RebuiltRemoteCommand> =
        store.retryable(now).map(::rebuild)

    suspend fun markSent(commandId: String, now: Long, retryAt: Long): RebuiltRemoteCommand {
        val current = requireNotNull(store.find(commandId)) { "command not found" }
        val updated = current.copy(
            status = RemoteCommandStatus.SENT.name,
            attemptCount = current.attemptCount + 1,
            nextAttemptAt = retryAt,
            lastAttemptAt = now,
            lastError = null,
            updatedAt = now,
        )
        store.upsert(updated)
        return rebuild(updated)
    }

    suspend fun markDeliveryDeferred(commandId: String, now: Long, retryAt: Long, reason: String) {
        val current = requireNotNull(store.find(commandId)) { "command not found" }
        store.upsert(
            current.copy(
                nextAttemptAt = retryAt,
                lastAttemptAt = now,
                lastError = reason,
                updatedAt = now,
            ),
        )
    }

    suspend fun markSucceeded(commandId: String, now: Long, resultJson: String?) {
        val current = requireNotNull(store.find(commandId)) { "command not found" }
        store.upsert(
            current.copy(
                status = RemoteCommandStatus.SUCCEEDED.name,
                acknowledgedAt = current.acknowledgedAt ?: now,
                completedAt = now,
                resultJson = resultJson,
                nextAttemptAt = Long.MAX_VALUE,
                updatedAt = now,
            ),
        )
    }

    private fun rebuild(entity: RemoteCommandOutboxEntity): RebuiltRemoteCommand {
        require(sha256(entity.payloadJson) == entity.payloadSha256) {
            "stored command payload hash mismatch for ${entity.commandId}"
        }
        val payload = Json.parseToJsonElement(entity.payloadJson) as? JsonObject
            ?: throw IllegalArgumentException("stored command payload must be a JSON object")
        val status = RemoteCommandStatus.entries.firstOrNull { it.name == entity.status }
            ?: RemoteCommandStatus.UNKNOWN
        return RebuiltRemoteCommand(
            commandId = entity.commandId,
            runId = entity.runId,
            type = entity.type,
            payload = payload,
            payloadJson = entity.payloadJson,
            payloadSha256 = entity.payloadSha256,
            status = status,
            attemptCount = entity.attemptCount,
        )
    }
}

internal fun canonicalJson(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries
        .sortedBy { it.key }
        .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "${Json.encodeToString(kotlinx.serialization.serializer<String>(), key)}:${canonicalJson(value)}"
        }
    is JsonArray -> element.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
    else -> element.toString()
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
