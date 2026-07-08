package com.harnessapk.updater

import com.harnessapk.common.AppError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

class UpdateRepository(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val manifestUrl: String,
    private val currentVersionCode: Int,
    private val cacheDir: File,
) {
    fun checkManifest(manifest: UpdateManifest): UpdateCheckResult {
        manifest.downloadUrls().forEach { requireHttps(it) }
        return UpdateCheckResult(
            manifest = manifest,
            updateAvailable = manifest.versionCode > currentVersionCode,
            forceUpdate = currentVersionCode < manifest.minSupportedVersionCode,
        )
    }

    fun fetchManifest(): UpdateCheckResult {
        requireHttps(manifestUrl)
        val request = Request.Builder().url(manifestUrl).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppError.Update("更新检查失败：HTTP ${response.code}")
            }
            val body = response.body.string()
            return checkManifest(parseManifest(body))
        }
    }

    fun downloadApk(manifest: UpdateManifest): ApkDownloadResult {
        val downloadUrls = manifest.downloadUrls()
        downloadUrls.forEach { requireHttps(it) }
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        val output = File(updatesDir, "harness-apk-${manifest.versionName}.apk")
        output.delete()
        try {
            output.outputStream().use { fileOut ->
                downloadUrls.forEachIndexed { index, url ->
                    val request = Request.Builder().url(url).get().build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val label = if (downloadUrls.size == 1) {
                                "安装包下载失败"
                            } else {
                                "安装包分片 ${index + 1}/${downloadUrls.size} 下载失败"
                            }
                            throw AppError.Update("$label：HTTP ${response.code}")
                        }
                        response.body.byteStream().use { input ->
                            input.copyTo(fileOut)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
        val actual = sha256(output)
        if (!actual.equals(manifest.sha256, ignoreCase = true)) {
            output.delete()
            throw AppError.Update("安装包校验失败")
        }
        return ApkDownloadResult(file = output, sha256 = actual)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireHttps(url: String) {
        require(url.startsWith("https://")) { "更新地址必须使用 HTTPS" }
    }

    private fun UpdateManifest.downloadUrls(): List<String> {
        val urls = apkChunks.ifEmpty { listOfNotNull(apkUrl) }
        require(urls.isNotEmpty()) { "更新清单缺少 APK 下载地址" }
        return urls
    }

    private fun parseManifest(body: String): UpdateManifest {
        val root = json.parseToJsonElement(body).jsonObject
        return UpdateManifest(
            versionCode = root.getValue("versionCode").jsonPrimitive.int,
            versionName = root.getValue("versionName").jsonPrimitive.content,
            minSupportedVersionCode = root.getValue("minSupportedVersionCode").jsonPrimitive.int,
            apkUrl = root["apkUrl"]?.jsonPrimitive?.contentOrNull,
            apkChunks = root["apkChunks"]?.jsonArray?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            } ?: emptyList(),
            sha256 = root.getValue("sha256").jsonPrimitive.content,
            releaseNotes = root["releaseNotes"]?.jsonArray?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            } ?: emptyList(),
            publishedAt = root.getValue("publishedAt").jsonPrimitive.content,
        )
    }
}
