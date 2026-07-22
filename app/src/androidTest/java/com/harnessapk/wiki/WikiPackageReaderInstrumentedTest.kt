package com.harnessapk.wiki

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class WikiPackageReaderInstrumentedTest {
    @Test
    fun realFixturePassesReadOnlySqliteInspection() = withTestRoot { root ->
        val inspection = WikiPackageReader(root.resolve("staging").toPath()).inspect(copyFixture(root).toPath())

        assertEquals(WikiRef("fixture.history", 1), inspection.manifest.ref)
        assertEquals("史料测试库", inspection.manifest.title)
        assertTrue(inspection.contentSizeBytes > 0)
        assertTrue(inspection.stagedDatabase.toFile().isFile)
    }

    @Test
    fun readOnlyPartialIntegrityCheckSupportsAllFixtureOrdinaryTables() = withTestRoot { root ->
        val databaseFile = root.resolve("partial-integrity.sqlite")
        databaseFile.outputStream().use { output -> output.write(fixtureContent(root)) }
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        try {
            val tableNames = database.rawQuery(
                "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val sql = cursor.getString(1).orEmpty()
                        if (!sql.trimStart().startsWith("CREATE VIRTUAL TABLE", ignoreCase = true)) {
                            add(cursor.getString(0))
                        }
                    }
                }
            }
            (listOf("sqlite_schema") + tableNames).forEach { tableName ->
                val quoted = tableName.replace("\"", "\"\"")
                val results = database.rawQuery("PRAGMA integrity_check(\"$quoted\")", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
                assertEquals("partial integrity check failed for $tableName", listOf("ok"), results)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun tamperedContentFailsDuringSharedChecksumVerification() = withTestRoot { root ->
        val source = fixtureContent(root)
        val tampered = source.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        val archive = writeSignedPackage(
            target = root.resolve("tampered.hwiki"),
            content = tampered,
            checksummedContent = source,
        )

        assertInspectionFails(root, archive, "SHA-256")
    }

    @Test
    fun schemaTriggerAndForeignKeyViolationsFailBeforeAnyInstallStateExists() = withTestRoot { root ->
        val wrongVersion = mutateFixtureDatabase(root) { database ->
            database.execSQL("PRAGMA user_version=2")
        }
        val trigger = mutateFixtureDatabase(root) { database ->
            database.execSQL(
                "CREATE TRIGGER injected AFTER INSERT ON documents BEGIN SELECT 1; END",
            )
        }
        val alteredTable = mutateFixtureDatabase(root) { database ->
            database.execSQL("ALTER TABLE documents ADD COLUMN mutable TEXT")
        }
        val alteredFtsBackingTable = mutateFixtureDatabase(root) { database ->
            database.execSQL("ALTER TABLE chunks_original_fts_content ADD COLUMN mutable TEXT")
        }
        val foreignKeyViolation = mutateFixtureDatabase(root) { database ->
            database.execSQL("PRAGMA foreign_keys=OFF")
            database.execSQL(
                "INSERT INTO sections(section_id, document_id, parent_section_id, title, path, ordinal, metadata_json) " +
                    "VALUES('invalid-section', 'missing-document', NULL, 'Invalid', 'Invalid', 0, '{}')",
            )
        }

        assertInspectionFails(root, writeSignedPackage(root.resolve("wrong-version.hwiki"), wrongVersion), "user_version")
        assertInspectionFails(root, writeSignedPackage(root.resolve("trigger.hwiki"), trigger), "禁止对象")
        assertInspectionFails(root, writeSignedPackage(root.resolve("altered-table.hwiki"), alteredTable), "协议结构漂移")
        assertInspectionFails(
            root,
            writeSignedPackage(root.resolve("altered-fts-backing-table.hwiki"), alteredFtsBackingTable),
            "协议结构漂移",
        )
        assertInspectionFails(
            root,
            writeSignedPackage(root.resolve("foreign-key-violation.hwiki"), foreignKeyViolation),
            "foreign_key_check",
        )
    }

    @Test
    fun mismatchedManifestContentHashFailsBeforeSqliteInspection() = withTestRoot { root ->
        val archive = writeSignedPackage(
            target = root.resolve("wrong-content-hash.hwiki"),
            content = fixtureContent(root),
            manifestContentHash = "0".repeat(64),
        )

        assertInspectionFails(root, archive, "contentHash")
    }

    private fun assertInspectionFails(root: File, archive: File, expectedMessage: String) {
        val staging = root.resolve("failed-${UUID.randomUUID()}")
        try {
            WikiPackageReader(staging.toPath()).inspect(archive.toPath())
            fail("Expected WikiPackageException")
        } catch (error: WikiPackageException) {
            assertTrue(
                "Expected '$expectedMessage' in '${error.message}'",
                error.message.orEmpty().contains(expectedMessage),
            )
        }
        assertFalse(staging.walkTopDown().any { it.name == "content.sqlite" })
    }

    private fun mutateFixtureDatabase(root: File, mutation: (SQLiteDatabase) -> Unit): ByteArray {
        val databaseFile = root.resolve("mutable-${UUID.randomUUID()}.sqlite")
        databaseFile.outputStream().use { output -> output.write(fixtureContent(root)) }
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        try {
            mutation(database)
        } finally {
            database.close()
        }
        return databaseFile.readBytes()
    }

    private fun fixtureContent(root: File): ByteArray =
        ZipFile(copyFixture(root)).use { archive ->
            val entry = requireNotNull(archive.getEntry("content.sqlite"))
            archive.getInputStream(entry).use { it.readBytes() }
        }

    private fun copyFixture(root: File): File {
        val target = root.resolve("fixture-${UUID.randomUUID()}.hwiki")
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE_NAME).use { input ->
            target.outputStream().buffered().use(input::copyTo)
        }
        return target
    }

    private fun writeSignedPackage(
        target: File,
        content: ByteArray,
        checksummedContent: ByteArray = content,
        manifestContentHash: String = content.sha256(),
    ): File {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey().encoded
        val publisherFingerprint = publicKey.sha256()
        val manifest = manifestJson(manifestContentHash, publisherFingerprint).encodeToByteArray()
        val payloads = linkedMapOf(
            "manifest.json" to manifest,
            "content.sqlite" to content,
        )
        val checksums = payloads.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{\"files\":{", postfix = "}}", separator = ",") { (path, bytes) ->
                val digest = if (path == "content.sqlite") checksummedContent.sha256() else bytes.sha256()
                "\"$path\":\"$digest\""
            }
            .encodeToByteArray()
        val signer = Ed25519Signer().apply {
            init(true, privateKey)
            update(checksums, 0, checksums.size)
        }
        val signature = signer.generateSignature()
        val signatureJson = """
            {"algorithm":"Ed25519","publicKey":"${Base64.getEncoder().encodeToString(publicKey)}","signature":"${Base64.getEncoder().encodeToString(signature)}","signedFile":"checksums.json"}
        """.trimIndent().encodeToByteArray()

        ZipOutputStream(target.outputStream().buffered()).use { output ->
            (payloads + mapOf("checksums.json" to checksums, "signature.json" to signatureJson)).forEach { (path, bytes) ->
                output.putNextEntry(ZipEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return target
    }

    private fun manifestJson(contentHash: String, publisherFingerprint: String): String =
        """{"builder":{"name":"harness-wiki-builder","profile":"generic-v1","version":"1"},"capabilities":{"claimGraph":false,"crossWikiLinks":false,"generatedPages":"none","hierarchicalSummaries":true,"sourceAttachments":false,"sourceHierarchy":true,"sourceSearch":true,"temporalAnnotations":false,"termIndex":true,"vectorIndex":false},"conceptNamespace":"fixture-v1","conceptRegistryHash":"${"a".repeat(64)}","publisher":{"keyId":"ed25519:$publisherFingerprint","name":"Fixture Publisher"},"schemaVersion":1,"type":"hwiki","wiki":{"contentHash":"$contentHash","description":"Fixture database","id":"fixture.history","language":["zh-Hant","zh-Hans"],"title":"Fixture History","version":1}}"""

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private inline fun withTestRoot(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("wiki-package-reader-${UUID.randomUUID()}").apply { mkdirs() }
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
