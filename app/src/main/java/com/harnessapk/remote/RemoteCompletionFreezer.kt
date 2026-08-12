package com.harnessapk.remote

import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.RemoteRunCompletionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

internal suspend fun freezeRemoteCompletion(
    database: AppDatabase,
    runId: String,
    raw: String,
    capturedAt: Long,
): String {
    val canonical = Json.parseToJsonElement(raw).toString()
    val root = Json.parseToJsonElement(canonical).jsonObject
    val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
    require(schemaVersion in 1..2) { "unsupported completion schema $schemaVersion" }
    val completionId = root["completionId"]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: "legacy-$runId"
    val sha = canonical.sha256()
    val dao = database.projectSearchDao()
    dao.insertRemoteCompletion(
        RemoteRunCompletionEntity(
            runId = runId,
            schemaVersion = schemaVersion,
            completionId = completionId,
            contentSha256 = sha,
            payloadJson = canonical,
            verificationState = if (schemaVersion == 2) "VERIFIED_V2" else "LEGACY_UNVERIFIED",
            capturedAt = capturedAt,
        ),
    )
    val frozen = requireNotNull(dao.remoteCompletion(runId))
    require(frozen.contentSha256 == frozen.payloadJson.sha256()) { "completion hash mismatch" }
    return frozen.payloadJson
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
