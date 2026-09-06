package com.harnessapk.chat

import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationDraftStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun textAndPrivateAttachmentSurviveStoreRecreationUntilCleared() {
        val conversationId = "draft-${UUID.randomUUID()}"
        val expected = ConversationDraft(
            text = "原草稿 语音结果",
            attachments = listOf(
                PendingImageAttachment(
                    uri = Uri.parse("content://com.harnessapk.fileprovider/chat-images/draft.jpg"),
                    mimeType = "image/jpeg",
                ),
            ),
        )

        ConversationDraftStore(context).save(conversationId, expected)

        val restored = ConversationDraftStore(context).load(conversationId)
        assertEquals(expected.text, restored.text)
        assertEquals(expected.attachments, restored.attachments)
        assertTrue(restored.updatedAt > 0L)
        ConversationDraftStore(context).clear(conversationId)
        assertEquals(ConversationDraft(), ConversationDraftStore(context).load(conversationId))
    }

    @Test
    fun updatedAtChangesOnlyWhenDraftContentChanges() {
        val conversationId = "timestamp-${UUID.randomUUID()}"
        var clock = 100L
        val store = ConversationDraftStore(context, nowMillis = { clock })
        try {
            val first = store.save(conversationId, ConversationDraft(text = "第一版")) as DraftSaveResult.Saved
            clock = 200L
            val same = store.save(conversationId, ConversationDraft(text = "第一版")) as DraftSaveResult.Saved
            clock = 300L
            val changed = store.save(conversationId, ConversationDraft(text = "第二版")) as DraftSaveResult.Saved

            assertEquals(100L, first.draft.updatedAt)
            assertEquals(100L, same.draft.updatedAt)
            assertEquals(300L, changed.draft.updatedAt)
        } finally {
            store.clear(conversationId)
        }
    }

    @Test
    fun typedDocumentAndImageSurviveStoreRecreationWithPrivateCopies() = runBlocking {
        val sourceImage = File(context.cacheDir, "draft-image-${UUID.randomUUID()}.jpg").apply {
            writeBytes("image-bytes".encodeToByteArray())
        }
        val sourceDocument = File(context.cacheDir, "draft-document-${UUID.randomUUID()}.txt").apply {
            writeText("文档正文")
        }
        val conversationId = "draft-${UUID.randomUUID()}"
        try {
            val store = ConversationDraftStore(context)
            val image = store.importImage(conversationId, Uri.fromFile(sourceImage), "image/jpeg")
            val document = store.importDocument(conversationId, Uri.fromFile(sourceDocument), "text/plain")

            assertEquals(DraftImportOutcome.ADDED, image.outcome)
            assertEquals(DraftImportOutcome.ADDED, document.outcome)
            assertEquals(DraftAttachmentState.READY, image.attachment?.state)
            assertEquals(DraftAttachmentState.READY, document.attachment?.state)

            val restored = ConversationDraftStore(context).load(conversationId)
            assertEquals("文档正文", restored.documents.single().body)
            assertEquals(sourceDocument.name, restored.documents.single().displayName)
            assertEquals(1, restored.imageMetadata.size)
            assertEquals("image-bytes", context.contentResolver.openInputStream(
                restored.imageMetadata.single().localUri.toUri(),
            )!!.bufferedReader().use { it.readText() })
            assertNotNull(restored.documents.single().toExtractedDocument())
        } finally {
            ConversationDraftStore(context).clear(conversationId)
            sourceImage.delete()
            sourceDocument.delete()
        }
    }

    @Test
    fun oldJsonStillLoadsAndMalformedJsonDoesNotOverwriteRawValue() {
        val preferences = context.getSharedPreferences("conversation_drafts", android.content.Context.MODE_PRIVATE)
        val oldConversationId = "old-${UUID.randomUUID()}"
        val brokenConversationId = "broken-${UUID.randomUUID()}"
        val oldUri = "content://legacy/image"
        val oldRaw = """
            {"text":"旧草稿","attachments":[{"uri":"$oldUri","mimeType":"image/png"}]}
        """.trimIndent()
        val brokenRaw = "{\"text\":\"保留原文\",\"attachments\":["
        try {
            assertTrue(preferences.edit().putString(oldConversationId, oldRaw).commit())
            assertTrue(preferences.edit().putString(brokenConversationId, brokenRaw).commit())

            val store = ConversationDraftStore(context)
            val old = store.load(oldConversationId)
            val broken = store.load(brokenConversationId)

            assertEquals("旧草稿", old.text)
            assertEquals(Uri.parse(oldUri), old.attachments.single().uri)
            assertEquals(1, old.schemaVersion)
            assertEquals("保留原文", broken.text)
            assertNotNull(broken.error)
            assertEquals(brokenRaw, preferences.getString(brokenConversationId, null))
        } finally {
            preferences.edit().remove(oldConversationId).remove(brokenConversationId).commit()
        }
    }

    @Test
    fun missingPrivateAttachmentRemainsVisibleAsMissing() {
        val conversationId = "missing-${UUID.randomUUID()}"
        val privateFiles = DraftAttachmentFiles(context)
        val privateCopy = privateFiles.createTemporary(conversationId, UUID.randomUUID().toString()).apply {
            file.writeBytes("private-copy".encodeToByteArray())
        }
        val missing = DraftImageAttachment(
            id = "missing-image",
            sourceUri = "content://source/image",
            localUri = privateCopy.uri.toString(),
            displayName = "missing.jpg",
            mimeType = "image/jpeg",
            sizeBytes = privateCopy.file.length(),
            sha256 = "c".repeat(64),
            state = DraftAttachmentState.READY,
        )
        try {
            val store = ConversationDraftStore(context)
            store.save(
                conversationId,
                ConversationDraft(
                    text = "原文字",
                    attachments = listOf(PendingImageAttachment(Uri.parse(missing.localUri), missing.mimeType)),
                    imageMetadata = listOf(missing),
                ),
            )
            assertTrue(privateCopy.file.delete())

            val restored = store.load(conversationId)

            assertEquals(DraftAttachmentState.MISSING, restored.imageMetadata.single().state)
            assertTrue(restored.error?.message?.contains("missing.jpg") == true)
            assertEquals("原文字", restored.text)
        } finally {
            ConversationDraftStore(context).clear(conversationId)
            privateCopy.file.delete()
        }
    }

    @Test
    fun validateSnapshotMarksMissingRecoveryAttachmentAndKeepsDraftText() {
        val conversationId = "recovery-missing-${UUID.randomUUID()}"
        val privateCopy = DraftAttachmentFiles(context)
            .createTemporary(conversationId, UUID.randomUUID().toString())
            .apply { file.writeBytes("recovery-private-copy".encodeToByteArray()) }
        val snapshot = ConversationDraft(
            text = "恢复原文",
            attachments = listOf(
                PendingImageAttachment(Uri.parse(privateCopy.uri.toString()), "image/jpeg"),
            ),
            imageMetadata = listOf(
                DraftImageAttachment(
                    id = "recovery-image",
                    sourceUri = null,
                    localUri = privateCopy.uri.toString(),
                    displayName = "恢复图片.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = privateCopy.file.length(),
                    sha256 = "d".repeat(64),
                ),
            ),
            updatedAt = 1234L,
        )
        try {
            assertTrue(privateCopy.file.delete())

            val validated = ConversationDraftStore(context).validateSnapshot(conversationId, snapshot)

            assertEquals(DraftAttachmentState.MISSING, validated.imageMetadata.single().state)
            assertTrue(validated.attachments.isEmpty())
            assertEquals("恢复原文", validated.text)
            assertEquals(1234L, validated.updatedAt)
        } finally {
            privateCopy.file.delete()
        }
    }

    @Test
    fun duplicateAndOversizedImportsKeepExistingDraftAndExposeOutcome() = runBlocking {
        val conversationId = "limits-${UUID.randomUUID()}"
        val source = File(context.cacheDir, "duplicate-${UUID.randomUUID()}.jpg").apply {
            writeBytes("same-image".encodeToByteArray())
        }
        val oversized = File(context.cacheDir, "oversized-${UUID.randomUUID()}.jpg").apply {
            outputStream().use { it.write(ByteArray(8 * 1024 * 1024 + 1)) }
        }
        try {
            val store = ConversationDraftStore(context)
            val first = store.importImage(conversationId, Uri.fromFile(source), "image/jpeg")
            val duplicate = store.importImage(conversationId, Uri.fromFile(source), "image/jpeg")
            val rejected = store.importImage(conversationId, Uri.fromFile(oversized), "image/jpeg")

            assertEquals(DraftImportOutcome.ADDED, first.outcome)
            assertEquals(DraftImportOutcome.DUPLICATE, duplicate.outcome)
            assertEquals(DraftImportOutcome.REJECTED, rejected.outcome)
            assertEquals(1, ConversationDraftStore(context).load(conversationId).imageMetadata.size)
        } finally {
            ConversationDraftStore(context).clear(conversationId)
            source.delete()
            oversized.delete()
        }
    }

    @Test
    fun legacyImageIsReplacedInPlaceWhenMigratingAtImageLimit() = runBlocking {
        val conversationId = "legacy-migration-${UUID.randomUUID()}"
        val sources = (0 until 4).map { index ->
            File(context.cacheDir, "legacy-$index-${UUID.randomUUID()}.jpg").apply {
                writeBytes("legacy-$index".encodeToByteArray())
            }
        }
        try {
            val legacy = sources.map { PendingImageAttachment(Uri.fromFile(it), "image/jpeg") }
            ConversationDraftStore(context).save(
                conversationId,
                ConversationDraft(text = "旧照片", attachments = legacy),
            )

            val result = ConversationDraftStore(context).importImage(
                conversationId,
                Uri.fromFile(sources.first()),
                "image/jpeg",
            )

            assertEquals(DraftImportOutcome.ADDED, result.outcome)
            val restored = ConversationDraftStore(context).load(conversationId)
            assertEquals(4, restored.attachments.size)
            assertEquals(1, restored.imageMetadata.size)
            assertTrue(restored.attachments.none { it.uri == Uri.fromFile(sources.first()) })
            assertEquals(DraftAttachmentState.READY, restored.imageMetadata.single().state)
        } finally {
            ConversationDraftStore(context).clear(conversationId)
            sources.forEach(File::delete)
        }
    }

    @Test
    fun failedDocumentImportPersistsRetryableFailedState() = runBlocking {
        val conversationId = "failed-${UUID.randomUUID()}"
        try {
            val result = ConversationDraftStore(context).importDocument(
                conversationId,
                Uri.parse("content://missing/${UUID.randomUUID()}.txt"),
                "text/plain",
            )

            assertEquals(DraftImportOutcome.FAILED, result.outcome)
            assertEquals(DraftAttachmentState.FAILED, result.attachment?.state)
            assertEquals(DraftAttachmentState.FAILED, ConversationDraftStore(context).load(conversationId).documents.single().state)
        } finally {
            ConversationDraftStore(context).clear(conversationId)
        }
    }

    @Test
    fun saveFailureReturnsFailedWithoutChangingPreviousSnapshot() {
        val backend = FailingDraftStorageBackend()
        val conversationId = "save-failure-${UUID.randomUUID()}"
        val store = ConversationDraftStore(context, storageBackend = backend)
        val draft = ConversationDraft(text = "无法保存")

        val result = store.save(conversationId, draft)

        assertTrue(result is DraftSaveResult.Failed)
        assertEquals(null, backend.values[conversationId])
    }

    private class FailingDraftStorageBackend : DraftStorageBackend {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun allStrings(): Map<String, String> = values.toMap()

        override fun commitPut(key: String, value: String): Boolean = false

        override fun commitRemove(key: String): Boolean = false
    }
}
