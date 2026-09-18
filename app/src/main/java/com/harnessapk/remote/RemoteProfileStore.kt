package com.harnessapk.remote

import android.content.Context
import android.os.Build
import android.util.Base64
import com.harnessapk.security.EncryptedValue
import com.harnessapk.security.StringCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface RemoteProfileProvider {
    val profile: StateFlow<RemoteProfile?>
}

class RemoteProfileStore(
    context: Context,
    private val cipher: StringCipher,
) : RemoteProfileProvider {
    private val preferences = context.getSharedPreferences("remote_profiles", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(load())
    override val profile: StateFlow<RemoteProfile?> = _profile.asStateFlow()

    fun save(profile: RemoteProfile) {
        val encryptedToken = cipher.encrypt(profile.deviceToken)
        val encryptedSecret = cipher.encrypt(profile.pairingSecret)
        // 同步落盘：进程被杀（instrumentation、后台回收）时不能丢配对凭据
        preferences.edit()
            .putString("relay_url", profile.relayUrl)
            .putString("host_id", profile.hostId)
            .putString("host_name", profile.hostName)
            .putString("device_id", profile.deviceId)
            .putString("pairing_ticket", profile.pairingTicket)
            .putString("device_token", encryptedToken.encode())
            .putString("pairing_secret", encryptedSecret.encode())
            .commit()
        _profile.value = profile
    }

    fun clear() {
        preferences.edit().clear().commit()
        _profile.value = null
    }

    private fun load(): RemoteProfile? = runCatching {
        val relayUrl = preferences.getString("relay_url", null) ?: return null
        RemoteProfile(
            relayUrl = relayUrl,
            hostId = requireNotNull(preferences.getString("host_id", null)),
            hostName = preferences.getString("host_name", null).orEmpty().ifBlank { "Mac" },
            deviceId = requireNotNull(preferences.getString("device_id", null)),
            deviceToken = cipher.decrypt(requireNotNull(preferences.getString("device_token", null)).decodeEncrypted()),
            pairingTicket = preferences.getString("pairing_ticket", null).orEmpty(),
            pairingSecret = cipher.decrypt(requireNotNull(preferences.getString("pairing_secret", null)).decodeEncrypted()),
        )
    }.getOrNull()
}

class RemoteEnrollmentClient(private val httpClient: OkHttpClient) {
    suspend fun enroll(pairingRaw: String, pushTarget: String?): RemoteProfile {
        val pairing = parsePairingPayload(pairingRaw)
        val body = buildJsonObject {
            put("Ticket", JsonPrimitive(pairing.pairingTicket))
            put("DeviceName", JsonPrimitive("${Build.MANUFACTURER} ${Build.MODEL}".trim()))
            pushTarget?.takeIf(String::isNotBlank)?.let { put("PushTarget", JsonPrimitive(it)) }
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(pairing.relayUrl.trimEnd('/') + "/v1/enroll")
            .post(body)
            .build()
        val raw = httpClient.await(request)
        val response = Json.parseToJsonElement(raw).jsonObject
        return RemoteProfile(
            relayUrl = pairing.relayUrl,
            hostId = response.string("hostId") ?: pairing.hostId,
            hostName = pairing.hostName,
            deviceId = requireNotNull(response.string("deviceId")) { "节点未返回设备 ID" },
            deviceToken = requireNotNull(response.string("deviceToken")) { "节点未返回设备令牌" },
            pairingTicket = pairing.pairingTicket,
            pairingSecret = pairing.pairingSecret,
        )
    }

    suspend fun updatePushTarget(profile: RemoteProfile, pushTarget: String) {
        val body = buildJsonObject {
            put("PushTarget", JsonPrimitive(pushTarget))
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(profile.relayUrl.trimEnd('/') + "/v1/devices/${profile.deviceId}")
            .header("Authorization", "Bearer ${profile.deviceToken}")
            .patch(body)
            .build()
        httpClient.await(request)
    }
}

private suspend fun OkHttpClient.await(request: Request): String = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            response.use {
                val body = it.body.string()
                if (!it.isSuccessful) {
                    continuation.resumeWithException(IOException("节点返回 ${it.code}: ${body.take(200)}"))
                } else {
                    continuation.resume(body)
                }
            }
        }
    })
}

private fun EncryptedValue.encode(): String = listOf(cipherText, initializationVector)
    .joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP or Base64.NO_PADDING) }

private fun String.decodeEncrypted(): EncryptedValue {
    val parts = split('.')
    require(parts.size == 2)
    return EncryptedValue(
        cipherText = Base64.decode(parts[0], Base64.NO_WRAP or Base64.NO_PADDING),
        initializationVector = Base64.decode(parts[1], Base64.NO_WRAP or Base64.NO_PADDING),
    )
}
