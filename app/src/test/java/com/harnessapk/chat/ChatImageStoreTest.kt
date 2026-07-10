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
}
