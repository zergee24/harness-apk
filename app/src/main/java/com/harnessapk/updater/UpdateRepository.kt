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
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

fun interface UpdateArtifactDownloader {
    fun downloadApk(
        manifest: UpdateManifest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): ApkDownloadResult
}

class UpdateRepository(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val manifestUrl: String,
    private val currentVersionCode: Int,
    private val cacheDir: File,
    private val maxAttempts: Int = 3,
    private val retryDelay: (Long) -> Unit = { Thread.sleep(it) },
    private val downloadConcurrency: Int = DEFAULT_DOWNLOAD_CONCURRENCY,
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

    fun downloadApk(manifest: UpdateManifest): ApkDownloadResult =
        downloadApk(manifest) { _, _ -> }

    override fun downloadApk(
        manifest: UpdateManifest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): ApkDownloadResult {
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
            if (downloadUrls.size == 1) {
                downloadWholeFile(downloadUrls.single(), temporary, onProgress)
            } else {
                downloadChunksConcurrently(downloadUrls, updatesDir, temporary, onProgress)
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

    /** 整包单请求：直接写入目标文件，进度按响应 Content-Length 汇报。 */
    private fun downloadWholeFile(
        url: String,
        output: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val progress = DownloadProgress(chunkCount = 1, onProgress)
        val request = Request.Builder().url(url).get().build()
        retrying("安装包下载") {
            progress.resetChunk(0)
            FileOutputStream(output).use { fileOut ->
                okHttpClient.newCall(request).execute().use { response ->
                    response.requireSuccessful("安装包下载")
                    progress.onChunkTotal(0, response.body.contentLength())
                    response.body.byteStream().use { input ->
                        input.copyTo(fileOut) { bytes -> progress.onChunkRead(0, bytes) }
                    }
                }
            }
        }
        progress.finish()
    }

    /**
     * 分片并发下载：每个分片独立临时文件 + 独立重试，失败只补下该片；
     * 全部就绪后按顺序拼接为整包。分片间并行（默认 6 路），总进度跨片聚合。
     */
    private fun downloadChunksConcurrently(
        urls: List<String>,
        updatesDir: File,
        concatTarget: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val chunkFiles = urls.mapIndexed { index, _ ->
            File(updatesDir, "${concatTarget.name}.chunk-$index")
        }
        // 清掉上次中断的残留，避免旧尺寸污染拼接结果
        chunkFiles.forEach(File::delete)
        val progress = DownloadProgress(chunkCount = urls.size, onProgress)
        val pool = Executors.newFixedThreadPool(minOf(downloadConcurrency, urls.size))
        try {
            val futures = urls.mapIndexed { index, url ->
                pool.submit(
                    Callable {
                        val request = Request.Builder().url(url).get().build()
                        retrying("安装包分片 ${index + 1}/${urls.size} 下载") {
                            progress.resetChunk(index)
                            FileOutputStream(chunkFiles[index]).use { fileOut ->
                                okHttpClient.newCall(request).execute().use { response ->
                                    response.requireSuccessful("安装包分片 ${index + 1}/${urls.size} 下载")
                                    progress.onChunkTotal(index, response.body.contentLength())
                                    response.body.byteStream().use { input ->
                                        input.copyTo(fileOut) { bytes -> progress.onChunkRead(index, bytes) }
                                    }
                                }
                            }
                        }
                    },
                )
            }
            var failure: Throwable? = null
            futures.forEachIndexed { index, future ->
                try {
                    future.get()
                } catch (cancelled: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (failure == null) failure = IOException("下载被中断", cancelled)
                } catch (error: ExecutionException) {
                    if (failure == null) failure = error.cause ?: error
                }
                if (failure != null && index < futures.lastIndex) {
                    pool.shutdownNow()
                    pool.awaitTermination(10, TimeUnit.SECONDS)
                }
            }
            failure?.let {
                chunkFiles.forEach(File::delete)
                throw it
            }
        } finally {
            pool.shutdown()
        }
        progress.finish()
        FileOutputStream(concatTarget).use { merged ->
            chunkFiles.forEach { chunk ->
                chunk.inputStream().use { it.copyTo(merged) }
            }
        }
        chunkFiles.forEach(File::delete)
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

    /** 跨分片聚合的字节进度；totalBytes 在全部分片 Content-Length 就绪后才有值。 */
    private class DownloadProgress(
        chunkCount: Int,
        val onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        private val chunkBytes = AtomicLongArray(chunkCount)
        private val chunkTotals = AtomicLongArray(chunkCount).also { array ->
            for (index in 0 until array.length()) array.set(index, -1L)
        }
        private val finished = AtomicBoolean(false)
        private val lastEmitted = AtomicLong(-1L)

        fun resetChunk(index: Int) {
            chunkBytes.set(index, 0L)
            emit()
        }

        fun onChunkRead(index: Int, bytes: Int) {
            if (bytes <= 0) return
            chunkBytes.addAndGet(index, bytes.toLong())
            emit()
        }

        fun onChunkTotal(index: Int, total: Long) {
            chunkTotals.set(index, if (total > 0) total else -1L)
            emit()
        }

        fun finish() {
            finished.set(true)
            emit()
        }

        private fun emit() {
            val downloaded = sumOf(chunkBytes)
            if (!finished.get() && downloaded - lastEmitted.get() < PROGRESS_EMIT_DELTA_BYTES) return
            lastEmitted.set(downloaded)
            onProgress(downloaded, totalIfKnown())
        }

        private fun totalIfKnown(): Long? {
            var total = 0L
            for (index in 0 until chunkTotals.length()) {
                val chunkTotal = chunkTotals.get(index)
                if (chunkTotal < 0) return null
                total += chunkTotal
            }
            return total.takeIf { it > 0 }
        }

        private fun sumOf(array: AtomicLongArray): Long {
            var sum = 0L
            for (index in 0 until array.length()) sum += array.get(index)
            return sum
        }
    }

    private fun InputStream.copyTo(out: java.io.OutputStream, onRead: (Int) -> Unit): Long {
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
            onRead(read)
            copied += read
        }
        return copied
    }

    private companion object {
        private const val DEFAULT_DOWNLOAD_CONCURRENCY = 6
        private const val PROGRESS_EMIT_DELTA_BYTES = 128L * 1024
    }
}

private class RetryableUpdateException(message: String) : IOException(message)

private fun UpdateManifest.downloadUrls(): List<String> {
    val urls = apkChunks.ifEmpty { listOfNotNull(apkUrl) }
    require(urls.isNotEmpty()) { "更新清单缺少 APK 下载地址" }
    return urls
}

private fun parseManifest(body: String): UpdateManifest {
    val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
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
