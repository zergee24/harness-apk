package com.harnessapk.wiki

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WikiPackageInstallInstrumentedTest {
    @Test
    fun verifiedFixtureInstallsImmutablyAndRegistersRoomMetadata() = runBlocking {
        withTestRoot { root ->
            val database = newDatabase()
            try {
                val inspection = inspectFixture(root)
                val repository = repository(root, database)
                val confirmed = ConfirmedWikiImport(
                    inspection = inspection,
                    packageHash = root.resolve("fixture.history-v1.hwiki").readBytes().sha256(),
                    enabledForNewConversations = true,
                )

                assertEquals(WikiInstallOutcome.INSTALLED, repository.install(confirmed).outcome)
                assertEquals(WikiInstallOutcome.ALREADY_INSTALLED, repository.install(confirmed).outcome)
                val version = database.wikiDao().findVersion("fixture.history", 1)
                assertTrue(version?.enabledForNewConversations == true)
                assertTrue(File(version?.contentPath.orEmpty()).isFile)
                assertEquals(1, database.wikiDao().findWiki("fixture.history")?.activeVersion)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun interruptedMoveLeavesNoVisibleRoomVersionOrDirectory() = runBlocking {
        withTestRoot { root ->
            val database = newDatabase()
            try {
                val inspection = inspectFixture(root)
                val reference = inspection.manifest.ref
                val repository = repository(
                    root = root,
                    database = database,
                    fileOps = FailingMoveWikiFileOps(),
                )
                val confirmed = ConfirmedWikiImport(
                    inspection = inspection,
                    packageHash = root.resolve("fixture.history-v1.hwiki").readBytes().sha256(),
                    enabledForNewConversations = false,
                )

                assertTrue(runCatching { repository.install(confirmed) }.exceptionOrNull() is WikiInstallException)
                assertNull(database.wikiDao().findVersion(reference.wikiId, reference.version))
                assertFalse(repository.versionDirectory(reference).toFile().exists())
            } finally {
                database.close()
            }
        }
    }

    private fun inspectFixture(root: File): WikiImportInspection {
        val archive = root.resolve("fixture.history-v1.hwiki")
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE_NAME).use { input ->
            archive.outputStream().use(input::copyTo)
        }
        return WikiPackageReader(root.resolve("inspection-staging").toPath()).inspect(archive.toPath())
    }

    private fun repository(
        root: File,
        database: AppDatabase,
        fileOps: WikiFileOps = DefaultWikiFileOps(),
    ): WikiRepository = WikiRepository(
        filesDir = root.resolve("files"),
        dao = database.wikiDao(),
        transactionRunner = WikiTransactionRunner { block -> database.withTransaction { block() } },
        fileOps = fileOps,
        timeProvider = TimeProvider { 1L },
    )

    private fun newDatabase(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java,
    ).build()

    private inline fun withTestRoot(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("wiki-package-install-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val FIXTURE_NAME = "fixture.history-v1.hwiki"
    }
}

private class FailingMoveWikiFileOps(
    private val delegate: WikiFileOps = DefaultWikiFileOps(),
) : WikiFileOps by delegate {
    override suspend fun moveAtomically(source: java.nio.file.Path, destination: java.nio.file.Path) {
        throw WikiInstallException("injected atomic move failure")
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
