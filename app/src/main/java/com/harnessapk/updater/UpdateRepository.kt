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
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

fun interface UpdateArtifactDownloader {
    fun downloadApk(manifest: UpdateManifest): ApkDownloadResult
}

class UpdateRepository(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val manifestUrl: String,
    private val currentVersionCode: Int,
    private val cacheDir: File,
    private val maxAttempts: Int = 3,
    private val retryDelay: (Long) -> Unit = { Thread.sleep(it) },
) : UpdateArtifactDownloader {
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
        val body = retrying("更新检查") {
            okHttpClient.newCall(request).execute().use { response ->
                response.requireSuccessful("更新检查")
                response.body.string()
            }
        }
        return checkManifest(parseManifest(body))
    }

    override fun downloadApk(manifest: UpdateManifest): ApkDownloadResult {
        val downloadUrls = manifest.downloadUrls()
        downloadUrls.forEach { requireHttps(it) }
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        val output = File(updatesDir, "harness-apk-${manifest.versionCode}.apk")
        if (output.isFile) {
            val existingSha = runCatching { sha256(output) }.getOrNull()
            if (existingSha.equals(manifest.sha256, ignoreCase = true)) {
                return ApkDownloadResult(file = output, sha256 = checkNotNull(existingSha))
            }
        }
        val temporary = File(updatesDir, "${output.name}.part")
        temporary.delete()
        try {
            FileOutputStream(temporary).use { fileOut ->
                downloadUrls.forEachIndexed { index, url ->
                    val label = if (downloadUrls.size == 1) {
                        "安装包下载"
                    } else {
                        "安装包分片 ${index + 1}/${downloadUrls.size} 下载"
                    }
                    val request = Request.Builder().url(url).get().build()
                    val startOffset = fileOut.channel.position()
                    retrying(label) {
                        fileOut.channel.truncate(startOffset)
                        fileOut.channel.position(startOffset)
                        okHttpClient.newCall(request).execute().use { response ->
                            response.requireSuccessful(label)
                            response.body.byteStream().use { input ->
                                input.copyTo(fileOut)
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        val actual = sha256(temporary)
        if (!actual.equals(manifest.sha256, ignoreCase = true)) {
            temporary.delete()
            throw AppError.Update("安装包校验失败")
        }
        moveReplacing(temporary, output)
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

    private fun okhttp3.Response.requireSuccessful(label: String) {
        if (isSuccessful) return
        if (code == 408 || code == 429 || code >= 500) {
            throw RetryableUpdateException("HTTP $code")
        }
        throw AppError.Update("$label 失败：HTTP $code")
    }

    private fun <T> retrying(label: String, block: () -> T): T {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                if (!error.isRetryable() || attempt == maxAttempts - 1) {
                    if (error is RetryableUpdateException || error is IOException) {
                        throw AppError.Update("$label 失败：${error.message ?: "网络异常"}")
                    }
                    throw error
                }
                retryDelay(if (attempt == 0) 300L else 900L)
            }
        }
        error("unreachable")
    }

    private fun Throwable.isRetryable(): Boolean =
        this is RetryableUpdateException || this is IOException

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
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

private class RetryableUpdateException(message: String) : IOException(message)
