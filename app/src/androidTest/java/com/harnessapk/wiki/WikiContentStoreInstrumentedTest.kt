package com.harnessapk.wiki

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WikiContentStoreInstrumentedTest {
    @Test
    fun originalAndNormalizedChannelsResolveToOriginalTextAndLocator() = runBlocking {
        withInstalledFixture { fixture ->
            val original = fixture.store.searchSources(fixture.ref, "司馬光", limit = 20)
            val normalized = fixture.store.searchSources(fixture.ref, "司马光", limit = 20)

            assertTrue(original.isNotEmpty())
            assertTrue(normalized.isNotEmpty())
            val originalHit = original.first { it.originalText.contains("司馬光") }
            val normalizedHit = normalized.first { it.originalText.contains("司馬光") }
            assertTrue(originalHit.matches.any { it.channel == WikiSearchChannel.ORIGINAL })
            assertTrue(normalizedHit.matches.any { it.channel == WikiSearchChannel.NORMALIZED })
            assertNotNull(normalizedHit.locator)

            val documents = fixture.store.listDocuments(fixture.ref)
            assertTrue(documents.isNotEmpty())
            val rootSections = fixture.store.listSections(fixture.ref, null)
            assertTrue(rootSections.isNotEmpty())
            assertEquals(documents.first().id, fixture.store.findDocument(fixture.ref, documents.first().id)?.id)
            assertEquals(rootSections.first().id, fixture.store.findSection(fixture.ref, rootSections.first().id)?.id)
            assertEquals(normalizedHit.chunkId, fixture.store.findChunk(fixture.ref, normalizedHit.chunkId)?.id)
            assertTrue(fixture.store.stats(fixture.ref).sourceChunkCount > 0)
            assertNotNull(fixture.store.chunkNeighbors(fixture.ref, normalizedHit.chunkId))

            val summaryHit = fixture.store.searchSources(fixture.ref, "财政制度", limit = 20)
                .first { it.originalText.contains("庫藏記錄甲") }
            val termHit = fixture.store.searchSources(fixture.ref, "君实", limit = 20)
                .first { it.originalText.contains("君實") }
            assertTrue(summaryHit.matches.any { it.channel == WikiSearchChannel.SUMMARY })
            assertTrue(termHit.matches.any { it.channel == WikiSearchChannel.TERM })
        }
    }

    @Test
    fun unknownIdsAndOversizedLimitsFailClosed() = runBlocking {
        withInstalledFixture { fixture ->
            assertNull(fixture.store.findChunk(fixture.ref, "missing"))
            assertTrue(
                runCatching { fixture.store.searchSources(fixture.ref, "史", limit = 500) }.exceptionOrNull()
                    is IllegalArgumentException,
            )
        }
    }

    @Test
    fun corruptedVersionIsInvalidatedWithoutFallingBackToAnotherVersion() = runBlocking {
        withInstalledFixture { fixture ->
            val version = checkNotNull(fixture.database.wikiDao().findVersion(fixture.ref.wikiId, fixture.ref.version))
            val alternateRef = WikiRef(fixture.ref.wikiId, fixture.ref.version + 1)
            val alternateDirectory = checkNotNull(File(version.contentPath).parentFile?.parentFile)
                .resolve(alternateRef.version.toString())
            alternateDirectory.mkdirs()
            val alternateContent = alternateDirectory.resolve(WikiRepository.CONTENT_FILE_NAME)
            Files.copy(File(version.contentPath).toPath(), alternateContent.toPath())
            fixture.database.wikiDao().insertVersion(
                version.copy(
                    version = alternateRef.version,
                    contentPath = alternateContent.absolutePath,
                ),
            )
            File(version.contentPath).writeText("not a sqlite database")

            assertTrue(
                runCatching { fixture.store.listDocuments(fixture.ref) }.exceptionOrNull()
                    is WikiContentUnavailableException,
            )
            assertEquals(
                WikiVersionState.INVALID.name,
                fixture.database.wikiDao().findVersion(fixture.ref.wikiId, fixture.ref.version)?.state,
            )
            assertTrue(fixture.store.listDocuments(alternateRef).isNotEmpty())
        }
    }

    @Test
    fun schemaDriftInvalidatesOnlyTheRequestedVersion() = runBlocking {
        withInstalledFixture { fixture ->
            val version = checkNotNull(fixture.database.wikiDao().findVersion(fixture.ref.wikiId, fixture.ref.version))
            SQLiteDatabase.openDatabase(version.contentPath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.execSQL("CREATE TRIGGER unexpected AFTER INSERT ON documents BEGIN SELECT 1; END")
            }

            assertTrue(
                runCatching { fixture.store.listDocuments(fixture.ref) }.exceptionOrNull()
                    is WikiContentUnavailableException,
            )
            assertEquals(
                WikiVersionState.INVALID.name,
                fixture.database.wikiDao().findVersion(fixture.ref.wikiId, fixture.ref.version)?.state,
            )
        }
    }

    @Test
    fun recordedContentPathOutsideExactVersionDirectoryIsRejected() = runBlocking {
        withInstalledFixture { fixture ->
            val version = checkNotNull(fixture.database.wikiDao().findVersion(fixture.ref.wikiId, fixture.ref.version))
            val outsideContent = checkNotNull(File(version.contentPath).parentFile?.parentFile?.parentFile)
                .resolve("outside.sqlite")
            Files.copy(File(version.contentPath).toPath(), outsideContent.toPath())
            val outsideRef = WikiRef(fixture.ref.wikiId, fixture.ref.version + 1)
            fixture.database.wikiDao().insertVersion(
                version.copy(
                    version = outsideRef.version,
                    contentPath = outsideContent.absolutePath,
                ),
            )

            assertTrue(
                runCatching { fixture.store.listDocuments(outsideRef) }.exceptionOrNull()
                    is WikiContentUnavailableException,
            )
            assertEquals(
                WikiVersionState.INVALID.name,
                fixture.database.wikiDao().findVersion(outsideRef.wikiId, outsideRef.version)?.state,
            )
        }
    }

    private suspend fun withInstalledFixture(block: suspend (InstalledFixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("wiki-content-store-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val ref = WikiRef("fixture.history", 1)
            val repository = WikiRepository(
                filesDir = root.resolve("files"),
                dao = database.wikiDao(),
                transactionRunner = WikiTransactionRunner { operation -> database.withTransaction { operation() } },
                timeProvider = TimeProvider { 1L },
            )
            val archive = root.resolve("fixture.history-v1.hwiki")
            InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE_NAME).use { input ->
                archive.outputStream().use(input::copyTo)
            }
            val inspection = WikiPackageReader(root.resolve("inspection-staging").toPath()).inspect(archive.toPath())
            repository.install(
                ConfirmedWikiImport(
                    inspection = inspection,
                    packageHash = archive.readBytes().sha256(),
                    enabledForNewConversations = true,
                ),
            )
            val store = InstalledWikiContentStore(
                filesDir = root.resolve("files"),
                wikiDao = database.wikiDao(),
                healthReporter = WikiVersionHealthReporter(repository::markInvalid),
            )

            block(InstalledFixture(ref, store, database))
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private data class InstalledFixture(
        val ref: WikiRef,
        val store: WikiContentStore,
        val database: AppDatabase,
    )

    private companion object {
        const val FIXTURE_NAME = "fixture.history-v1.hwiki"
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
