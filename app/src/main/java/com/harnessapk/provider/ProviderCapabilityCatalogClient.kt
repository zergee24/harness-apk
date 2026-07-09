package com.harnessapk.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

class ProviderCapabilityCatalogClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetch(
        url: String,
        expectedSha256: String? = null,
    ): ProviderCapabilityCatalog = fetchDocument(url, expectedSha256).catalog

    suspend fun fetchDocument(
        url: String,
        expectedSha256: String? = null,
    ): FetchedProviderCapabilityCatalog = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                "模型能力清单下载失败：HTTP ${response.code}"
            }
            val body = response.body.string()
            val actualSha256 = body.sha256Hex()
            expectedSha256?.takeIf { it.isNotBlank() }?.let { expected ->
                require(actualSha256.equals(expected, ignoreCase = true)) {
                    "模型能力清单校验失败"
                }
            }
            FetchedProviderCapabilityCatalog(
                rawJson = body,
                sha256 = actualSha256,
                catalog = parseProviderCapabilityCatalogJson(body, json),
            )
        }
    }
}

data class FetchedProviderCapabilityCatalog(
    val rawJson: String,
    val sha256: String,
    val catalog: ProviderCapabilityCatalog,
)

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { "%02x".format(it) }
}
