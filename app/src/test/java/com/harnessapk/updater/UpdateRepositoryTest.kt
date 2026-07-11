package com.harnessapk.updater

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class UpdateRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun checkManifestMarksOptionalAndForceUpdates() {
        val repository = repository(currentVersionCode = 3)

        val optional = repository.checkManifest(manifest(versionCode = 4, minSupportedVersionCode = 1))
        val forced = repository.checkManifest(manifest(versionCode = 4, minSupportedVersionCode = 5))

        assertTrue(optional.updateAvailable)
        assertFalse(optional.forceUpdate)
        assertTrue(forced.forceUpdate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun checkManifestRejectsNonHttpsApkUrl() {
        repository().checkManifest(manifest(apkUrl = "http://example.com/app.apk"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun checkManifestRejectsNonHttpsChunkUrl() {
        repository().checkManifest(
            manifest(
                apkUrl = null,
                apkChunks = listOf("https://download.example.com/part-0", "http://example.com/part-1"),
            ),
        )
    }

    @Test
    fun downloadApkReassemblesChunksBeforeChecksumValidation() {
        val repository = repository(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val body = when (request.url.encodedPath) {
                        "/part-0" -> "a"
                        "/part-1" -> "p"
                        "/part-2" -> "k"
                        else -> error("Unexpected URL ${request.url}")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
                .build(),
        )

        val result = repository.downloadApk(
            manifest(
                apkUrl = null,
                apkChunks = listOf(
                    "https://download.example.com/part-0",
                    "https://download.example.com/part-1",
                    "https://download.example.com/part-2",
                ),
                sha256 = sha256("apk"),
            ),
        )

        assertEquals("apk", result.file.readText())
        assertEquals(sha256("apk"), result.sha256)
    }

    @Test
    fun downloadDeletesFileWhenChecksumDoesNotMatch() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("apk"))
        server.start()
        val repository = repository()

        runCatching {
            repository.downloadApk(manifest(apkUrl = server.url("/app.apk").toString().replace("http://", "https://")))
        }

        assertFalse(File(temp.root, "updates/harness-apk-2.apk").exists())
        assertFalse(File(temp.root, "updates/harness-apk-2.apk.part").exists())
        server.shutdown()
    }

    @Test
    fun sha256ComputesExpectedValue() {
        val file = temp.newFile("app.apk").apply { writeText("apk") }
        assertTrue(repository().sha256(file).matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun fetchManifestRetriesTransientServerFailure() {
        val attempts = AtomicInteger()
        val repository = repository(
            okHttpClient = clientResponding { request ->
                if (attempts.incrementAndGet() == 1) {
                    response(request, 500, "temporary")
                } else {
                    response(request, 200, manifestJson())
                }
            },
        )

        val result = repository.fetchManifest()

        assertTrue(result.updateAvailable)
        assertEquals(2, attempts.get())
    }

    @Test
    fun downloadRetriesTransientChunkFailureWithoutDuplicatingBytes() {
        val partOneAttempts = AtomicInteger()
        val repository = repository(
            okHttpClient = clientResponding { request ->
                when (request.url.encodedPath) {
                    "/part-0" -> response(request, 200, "a")
                    "/part-1" -> if (partOneAttempts.incrementAndGet() == 1) {
                        response(request, 500, "temporary")
                    } else {
                        response(request, 200, "pk")
                    }
                    else -> error("Unexpected URL ${request.url}")
                }
            },
        )

        val result = repository.downloadApk(
            manifest(
                apkUrl = null,
                apkChunks = listOf(
                    "https://download.example.com/part-0",
                    "https://download.example.com/part-1",
                ),
                sha256 = sha256("apk"),
            ),
        )

        assertEquals("apk", result.file.readText())
        assertEquals(2, partOneAttempts.get())
    }

    @Test
    fun nonRetryableNotFoundIsRequestedOnce() {
        val attempts = AtomicInteger()
        val repository = repository(
            okHttpClient = clientResponding { request ->
                attempts.incrementAndGet()
                response(request, 404, "missing")
            },
        )

        runCatching { repository.downloadApk(manifest()) }

        assertEquals(1, attempts.get())
    }

    @Test
    fun checksumFailureKeepsPreviousFileAndDeletesTemporaryFile() {
        val previous = File(temp.root, "updates/harness-apk-0.1.1.apk").apply {
            parentFile?.mkdirs()
            writeText("verified")
        }
        val repository = repository(okHttpClient = clientReturning("broken"))

        runCatching { repository.downloadApk(manifest(sha256 = sha256("expected"))) }

        assertEquals("verified", previous.readText())
        assertFalse(File(temp.root, "updates/harness-apk-2.apk.part").exists())
    }

    @Test
    fun validVersionCodeFileIsReusedWithoutNetworkCall() {
        val requests = AtomicInteger()
        val final = File(temp.root, "updates/harness-apk-2.apk").apply {
            parentFile?.mkdirs()
            writeText("apk")
        }
        val repository = repository(
            okHttpClient = clientResponding { request ->
                requests.incrementAndGet()
                response(request, 500, "unexpected")
            },
        )

        val result = repository.downloadApk(manifest(sha256 = sha256("apk")))

        assertEquals(final, result.file)
        assertEquals(0, requests.get())
    }

    private fun repository(
        currentVersionCode: Int = 1,
        okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
        retryDelay: (Long) -> Unit = {},
    ) = UpdateRepository(
        okHttpClient = okHttpClient,
        json = Json { ignoreUnknownKeys = true },
        manifestUrl = "https://download.example.com/update.json",
        currentVersionCode = currentVersionCode,
        cacheDir = temp.root,
        retryDelay = retryDelay,
    )

    private fun clientResponding(
        responder: (okhttp3.Request) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> responder(chain.request()) }
        .build()

    private fun clientReturning(body: String): OkHttpClient = clientResponding { request ->
        response(request, 200, body)
    }

    private fun response(request: okhttp3.Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
            .build()

    private fun manifestJson(): String = """
        {
          "versionCode": 2,
          "versionName": "0.1.1",
          "minSupportedVersionCode": 1,
          "apkUrl": "https://download.example.com/app.apk",
          "apkChunks": [],
          "sha256": "${sha256("apk")}",
          "releaseNotes": ["test"],
          "publishedAt": "2026-07-05T00:00:00Z"
        }
    """.trimIndent()

    private fun manifest(
        versionCode: Int = 2,
        minSupportedVersionCode: Int = 1,
        apkUrl: String? = "https://download.example.com/app.apk",
        apkChunks: List<String> = emptyList(),
        sha256: String = sha256("different"),
    ) = UpdateManifest(
        versionCode = versionCode,
        versionName = "0.1.1",
        minSupportedVersionCode = minSupportedVersionCode,
        apkUrl = apkUrl,
        apkChunks = apkChunks,
        sha256 = sha256,
        releaseNotes = listOf("test"),
        publishedAt = "2026-07-05T00:00:00Z",
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
