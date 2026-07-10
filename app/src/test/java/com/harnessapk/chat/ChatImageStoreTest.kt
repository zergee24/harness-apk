package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    private fun assertNetworkFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected AppError.Network")
        } catch (_: com.harnessapk.common.AppError.Network) {
            // Expected.
        }
    }
}
