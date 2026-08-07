package com.harnessapk.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureStagingInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun stagedFilesArePrivateHashedAndRestoredFromRepository() {
        val source = context.cacheDir.resolve("capture-source-${UUID.randomUUID()}.jpg")
        val bytes = "private image".encodeToByteArray()
        source.writeBytes(bytes)
        val draftId = UUID.randomUUID().toString()
        val store = CaptureStagingStore(context) { uri -> FileInputStream(requireNotNull(uri.path)) }
        val items = store.stage(
            draftId,
            IncomingShareRequest(
                text = "说明",
                items = listOf(IncomingShareItem(Uri.fromFile(source).toString(), "photo.jpg", "image/jpeg", bytes.size.toLong())),
            ),
        )
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, items.single().sha256)
        assertEquals(CaptureItemKind.IMAGE, items.single().kind)
        assertTrue(File(requireNotNull(Uri.parse(items.single().localUri).path)).isFile)
        source.delete()

        val conversationId = "capture-${UUID.randomUUID()}"
        val repository = CaptureDraftRepository(context)
        val draft = CaptureDraft(conversationId, CaptureSource.ANDROID_SHARE, "说明", items, CaptureStatus.READY, 1L, Long.MAX_VALUE)
        repository.save(draft)
        assertEquals(draft, CaptureDraftRepository(context).current())
        repository.consume(conversationId)
        assertNull(CaptureDraftRepository(context).current())

        store.cleanup(draftId)
    }

    @Test
    fun sendMultipleImagesAreParsedAndCopiedBeforeSourcePermissionCanExpire() {
        val sources = (1..2).map { index ->
            context.cacheDir.resolve("capture-source-${UUID.randomUUID()}-$index.jpg").apply {
                writeBytes("image-$index".encodeToByteArray())
            }
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                ArrayList(sources.map(Uri::fromFile)),
            )
        }

        val request = requireNotNull(intent.toIncomingShareRequest(context.contentResolver))
        val draftId = UUID.randomUUID().toString()
        val store = CaptureStagingStore(context)
        val items = store.stage(draftId, request)
        sources.forEach(File::delete)

        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == CaptureItemKind.IMAGE })
        assertTrue(items.all { File(requireNotNull(Uri.parse(it.localUri).path)).isFile })
        assertEquals(setOf("image-1", "image-2"), items.map { item ->
            File(requireNotNull(Uri.parse(item.localUri).path)).readText()
        }.toSet())

        store.cleanup(draftId)
    }

    @Test
    fun ordinaryPdfImportsOnlyUnderProjectFiles() = kotlinx.coroutines.runBlocking {
        val source = context.cacheDir.resolve("capture-source-${UUID.randomUUID()}.pdf").apply {
            writeText("pdf from external share")
        }
        val container = (context.applicationContext as com.harnessapk.HarnessApkApplication).container
        container.captureDraftRepository.current()?.let { stale ->
            container.captureImportCoordinator.discard(stale.id)
        }
        val project = container.projectRepository.createProject("分享导入-${UUID.randomUUID()}")
        val draft = container.captureImportCoordinator.stage(
            IncomingShareRequest(
                text = "",
                items = listOf(
                    IncomingShareItem(
                        sourceUri = Uri.fromFile(source).toString(),
                        displayName = "report.pdf",
                        mimeType = "application/pdf",
                        declaredSizeBytes = source.length(),
                    ),
                ),
            ),
        )

        val directConversationFailure = runCatching {
            container.captureImportCoordinator.deliverToConversation(draft.id, "must-not-receive-file")
        }.exceptionOrNull()
        val imported = container.captureImportCoordinator.importFilesToProject(draft.id, project.id)

        assertTrue(directConversationFailure?.message.orEmpty().contains("普通文件"))
        assertEquals(listOf("files/report.pdf"), imported)
        assertEquals("pdf from external share", project.rootDirectory.resolve(imported.single()).readText())
        assertNull(container.captureDraftRepository.current())
        assertFalse(context.cacheDir.resolve("capture-staging/${draft.id}").exists())

        container.projectRepository.deleteProject(project.id)
        source.delete()
        Unit
    }

    @Test
    fun oversizedDeclaredBatchFailsBeforeCreatingDraftDirectory() {
        val draftId = UUID.randomUUID().toString()
        val store = CaptureStagingStore(context) { error("input must not open") }
        val failure = runCatching {
            store.stage(
                draftId,
                IncomingShareRequest(
                    text = "",
                    items = listOf(
                        IncomingShareItem("file:///oversized", "large.pdf", "application/pdf", MAX_CAPTURE_ITEM_BYTES + 1),
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("50 MiB"))
        assertTrue(context.cacheDir.resolve("capture-staging/$draftId").exists().not())
    }
}
