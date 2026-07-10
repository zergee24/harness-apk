package com.harnessapk.chat

import android.content.ContextWrapper
import com.harnessapk.common.AppDispatchers
import com.harnessapk.common.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatImageStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun managedFilesCopyImageIntoChatImagesDirectoryWithMimeExtension() {
        val file = ManagedChatImageFiles(temporaryFolder.root).copy(
            "picker-bytes".byteInputStream(),
            "image/jpeg",
        )

        assertEquals("chat-images", file.parentFile!!.name)
        assertEquals("jpg", file.extension)
        assertEquals("picker-bytes", file.readText())
    }

    @Test
    fun remoteImageCacheKeyIsStableAndDoesNotContainUrlPath() {
        val key = remoteChatImageCacheKey("https://example.com/model/reply.jpg?token=secret")

        assertEquals("8df6fc37584620b0d94fcbf1a7c90c9a4a5994ad04986e2b1c89cbaff9378c6d", key)
        assertTrue(!key.contains('/'))
        assertTrue(!key.contains('?'))
    }

    @Test
    fun remoteImageCacheReusesValidEntryWithoutCallingLoaderAgain() {
        val cache = RemoteChatImageCache(temporaryFolder.root)
        val url = "https://example.com/reply.jpg"
        val first = cache.getOrPut(url) { "remote-image".toByteArray() to "image/jpeg" }

        val second = cache.getOrPut(url) { throw AssertionError("有效缓存不应再次下载") }

        assertEquals("remote-image", first.file.readText())
        assertEquals(first.file, second.file)
        assertEquals("image/jpeg", second.mimeType)
    }

    @Test
    fun remoteImageCacheRejectsEmptyOrNonImageResponses() {
        val cache = RemoteChatImageCache(temporaryFolder.root)

        assertNetworkFailure {
            cache.store("https://example.com/empty", ByteArray(0), "image/jpeg")
        }
        assertNetworkFailure {
            cache.store("https://example.com/page", "<html/>".toByteArray(), "text/html")
        }
    }

    @Test
    fun materializeRemoteImageCachesSuccessfulHttpsJpegResponse() = runBlocking {
        var requestCount = 0
        val fixture = remoteMaterializeFixture { _, consume ->
            requestCount++
            consume(
                DownloadedChatImage(
                    statusCode = 200,
                    contentType = "image/jpeg",
                    readBody = { "remote-image".toByteArray() },
                ),
            )
        }
        val source = ChatImageSource.Remote("https://example.com/reply.jpg", null)

        val first = materializedFile(fixture.store, source)
        val second = materializedFile(fixture.store, source)

        assertEquals("remote-image", first.readText())
        assertEquals("remote-image", second.readText())
        assertEquals(2, fixture.persistedFiles.listFiles()!!.size)
        assertTrue(fixture.persistedFiles.listFiles()!!.all { it.readText() == "remote-image" })
        assertEquals(1, requestCount)
    }

    @Test
    fun materializeRemoteImageRejectsHtmlResponseWithoutCachingIt() = runBlocking {
        var requestCount = 0
        val fixture = remoteMaterializeFixture { _, consume ->
            requestCount++
            consume(
                DownloadedChatImage(
                    statusCode = 200,
                    contentType = if (requestCount == 1) "text/html; charset=utf-8" else "image/jpeg",
                    readBody = { if (requestCount == 1) "<html>not an image</html>".toByteArray() else "recovered-image".toByteArray() },
                ),
            )
        }
        val source = ChatImageSource.Remote("https://example.com/reply.jpg", null)

        assertSuspendNetworkFailure { fixture.store.materialize(source) }
        val recovered = materializedFile(fixture.store, source)

        assertEquals("recovered-image", fixture.persistedFiles.listFiles()!!.single().readText())
        assertEquals("recovered-image", recovered.readText())
        assertEquals(2, requestCount)
    }

    @Test
    fun materializeRemoteImageRejectsHttpUrlBeforeTransportOrCaching() = runBlocking {
        var requestCount = 0
        val fixture = remoteMaterializeFixture { _, _ ->
            requestCount++
            throw AssertionError("HTTP URL 不应调用 transport")
        }

        assertSuspendNetworkFailure {
            fixture.store.materialize(ChatImageSource.Remote("http://example.com/reply.jpg", null))
        }

        assertEquals(0, requestCount)
        assertTrue(!File(fixture.cacheDir, "chat-image-cache").exists())
    }

    @Test
    fun materializeRemoteImageRejectsInvalidUrlBeforeTransportOrCaching() = runBlocking {
        var requestCount = 0
        val fixture = remoteMaterializeFixture { _, _ ->
            requestCount++
            throw AssertionError("无效 URL 不应调用 transport")
        }

        assertSuspendNetworkFailure {
            fixture.store.materialize(ChatImageSource.Remote("https://", null))
        }

        assertEquals(0, requestCount)
        assertTrue(!File(fixture.cacheDir, "chat-image-cache").exists())
    }

    @Test
    fun materializeRemoteImageRejectsNon2xxResponseBeforeReadingBody() = runBlocking {
        var bodyReadCount = 0
        val fixture = remoteMaterializeFixture { _, consume ->
            consume(
                DownloadedChatImage(
                    statusCode = 404,
                    contentType = "image/jpeg",
                    readBody = {
                        bodyReadCount++
                        throw AssertionError("非 2xx 响应不应读取 body")
                    },
                ),
            )
        }

        assertSuspendNetworkFailure {
            fixture.store.materialize(ChatImageSource.Remote("https://example.com/reply.jpg", null))
        }

        assertEquals(0, bodyReadCount)
    }

    @Test
    fun materializeRemoteImageRejectsNonImageResponseBeforeReadingBody() = runBlocking {
        var bodyReadCount = 0
        val fixture = remoteMaterializeFixture(
            httpGet = { _, consume ->
                consume(
                    DownloadedChatImage(
                        statusCode = 200,
                        contentType = "text/html; charset=utf-8",
                        readBody = {
                            bodyReadCount++
                            throw AssertionError("非图片响应不应读取 body")
                        },
                    ),
                )
            },
        )

        assertSuspendNetworkFailure {
            fixture.store.materialize(ChatImageSource.Remote("https://example.com/reply.jpg", null))
        }

        assertEquals(0, bodyReadCount)
    }

    @Test
    fun materializeRemoteJpegReadsBodyOnceAndCachesIt() = runBlocking {
        var requestCount = 0
        var bodyReadCount = 0
        val fixture = remoteMaterializeFixture(
            httpGet = { _, consume ->
                requestCount++
                consume(
                    DownloadedChatImage(
                        statusCode = 200,
                        contentType = "image/jpeg",
                        readBody = {
                            bodyReadCount++
                            "remote-image".toByteArray()
                        },
                    ),
                )
            },
        )
        val source = ChatImageSource.Remote("https://example.com/reply.jpg", null)

        val first = materializedFile(fixture.store, source)
        val second = materializedFile(fixture.store, source)

        assertEquals("remote-image", first.readText())
        assertEquals("remote-image", second.readText())
        assertEquals(1, requestCount)
        assertEquals(1, bodyReadCount)
    }

    @Test
    fun resolveRejectsHttpImageUrl() {
        val result = resolveChatImageDisplaySource("http://example.com/image.jpg", "image/jpeg")

        assertTrue(result is ChatImageSource.Invalid)
    }

    @Test
    fun resolveParsesPercentEncodedImageDataUri() {
        val result = resolveChatImageDisplaySource("data:image/svg+xml,%3Csvg%2F%3E", null)

        assertTrue(result is ChatImageSource.Data)
        result as ChatImageSource.Data
        assertEquals("image/svg+xml", result.mimeType)
        assertArrayEquals("<svg/>".toByteArray(), result.bytes)
    }

    @Test
    fun resolveParsesBase64ImageDataUri() {
        val result = resolveChatImageDisplaySource("data:image/png;base64,aGVsbG8=", null)

        assertTrue(result is ChatImageSource.Data)
        result as ChatImageSource.Data
        assertEquals("image/png", result.mimeType)
        assertArrayEquals("hello".toByteArray(), result.bytes)
    }

    @Test
    fun resolveRejectsNonImageDataUri() {
        val result = resolveChatImageDisplaySource("data:text/plain,not-an-image", null)

        assertTrue(result is ChatImageSource.Invalid)
    }

    @Test
    fun resolveRejectsMalformedPercentEncodedImageDataUri() {
        val result = resolveChatImageDisplaySource("data:image/svg+xml,%invalid", null)

        assertTrue(result is ChatImageSource.Invalid)
    }

    private fun remoteMaterializeFixture(
        httpGet: (String, (DownloadedChatImage) -> Unit) -> Unit,
    ): RemoteMaterializeFixture {
        val filesDir = temporaryFolder.newFolder("files")
        val cacheDir = temporaryFolder.newFolder("cache")
        return RemoteMaterializeFixture(
            store = ChatImageStore(
                context = ContextWrapper(null),
                httpClient = null,
                dispatchers = AppDispatchers(io = Dispatchers.Unconfined),
                uriForFile = { file -> throw MaterializedImage(file) },
                filesDir = filesDir,
                cacheDir = cacheDir,
                httpGet = httpGet,
            ),
            persistedFiles = File(filesDir, "chat-images"),
            cacheDir = cacheDir,
        )
    }

    private fun assertNetworkFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected AppError.Network")
        } catch (_: AppError.Network) {
            // Expected.
        }
    }

    private suspend fun assertSuspendNetworkFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected AppError.Network")
        } catch (_: AppError.Network) {
            // Expected.
        }
    }

    private suspend fun materializedFile(
        store: ChatImageStore,
        source: ChatImageSource.Remote,
    ): File = try {
        store.materialize(source)
        error("Expected controlled URI sink to receive the managed file")
    } catch (result: MaterializedImage) {
        result.file
    }

    private data class RemoteMaterializeFixture(
        val store: ChatImageStore,
        val persistedFiles: File,
        val cacheDir: File,
    )

    private class MaterializedImage(val file: File) : RuntimeException()
}
