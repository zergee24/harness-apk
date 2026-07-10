package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
}
