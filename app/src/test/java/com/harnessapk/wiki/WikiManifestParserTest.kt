package com.harnessapk.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WikiManifestParserTest {
    @Test
    fun `schema v1 manifest maps all capability fields`() {
        val manifest = WikiManifestParser.parse(validManifestBytes())

        assertEquals(WikiRef("fixture.history", 1), manifest.ref)
        assertEquals("Fixture History", manifest.title)
        assertEquals(listOf("zh-Hant", "zh-Hans"), manifest.languages)
        assertTrue(manifest.capabilities.sourceHierarchy)
        assertTrue(manifest.capabilities.sourceSearch)
        assertEquals(GeneratedPages.NONE, manifest.capabilities.generatedPages)
        assertEquals("generic-v1", manifest.builderProfile)
    }

    @Test
    fun `unsupported or overstated manifests fail closed`() {
        listOf(
            manifestWith("\"schemaVersion\":1", "\"schemaVersion\":2"),
            manifestWith("\"type\":\"hwiki\"", "\"type\":\"hagent\""),
            manifestWith("\"vectorIndex\":false", "\"vectorIndex\":true"),
            manifestWith("\"sourceAttachments\":false", "\"sourceAttachments\":true"),
            manifestWith("\"title\":\"Fixture History\"", "\"title\":\"  \""),
            manifestWith("[\"zh-Hant\",\"zh-Hans\"]", "[\"zh-Hant\",\"zh-Hant\"]"),
        ).forEach(::assertManifestFailure)
    }

    @Test
    fun `manifest structural boundary values fail closed`() {
        listOf(
            manifestWith("\"id\":\"fixture.history\"", "\"id\":\"Fixture history\""),
            manifestWith("\"version\":1", "\"version\":0"),
            manifestWith(
                "\"contentHash\":\"${"c".repeat(64)}\"",
                "\"contentHash\":\"${"C".repeat(64)}\"",
            ),
            manifestWith("\"generatedPages\":\"none\"", "\"generatedPages\":\"future\""),
            validManifestJson().removeSuffix("}").plus(",\"unexpected\":true}").encodeToByteArray(),
        ).forEach(::assertManifestFailure)
    }

    private fun assertManifestFailure(bytes: ByteArray) {
        try {
            WikiManifestParser.parse(bytes)
            fail("Expected WikiPackageException")
        } catch (_: WikiPackageException) {
            // Expected.
        }
    }

    private fun manifestWith(expected: String, replacement: String): ByteArray =
        validManifestJson().replace(expected, replacement).encodeToByteArray()

    private fun validManifestBytes(): ByteArray = validManifestJson().encodeToByteArray()

    private fun validManifestJson(): String =
        """{"builder":{"name":"harness-wiki-builder","profile":"generic-v1","version":"1"},"capabilities":{"claimGraph":false,"crossWikiLinks":false,"generatedPages":"none","hierarchicalSummaries":true,"sourceAttachments":false,"sourceHierarchy":true,"sourceSearch":true,"temporalAnnotations":false,"termIndex":true,"vectorIndex":false},"conceptNamespace":"fixture-v1","conceptRegistryHash":"${"a".repeat(64)}","publisher":{"keyId":"ed25519:${"b".repeat(64)}","name":"Fixture Publisher"},"schemaVersion":1,"type":"hwiki","wiki":{"contentHash":"${"c".repeat(64)}","description":"Fixture database","id":"fixture.history","language":["zh-Hant","zh-Hans"],"title":"Fixture History","version":1}}"""
}
