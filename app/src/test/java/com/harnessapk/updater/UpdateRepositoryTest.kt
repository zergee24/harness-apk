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

        assertFalse(File(temp.root, "updates/harness-apk-0.1.1.apk").exists())
        server.shutdown()
    }

    @Test
    fun sha256ComputesExpectedValue() {
        val file = temp.newFile("app.apk").apply { writeText("apk") }
        assertTrue(repository().sha256(file).matches(Regex("[0-9a-f]{64}")))
    }

    private fun repository(
        currentVersionCode: Int = 1,
        okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
    ) = UpdateRepository(
        okHttpClient = okHttpClient,
        json = Json { ignoreUnknownKeys = true },
        manifestUrl = "https://download.example.com/update.json",
        currentVersionCode = currentVersionCode,
        cacheDir = temp.root,
    )

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
