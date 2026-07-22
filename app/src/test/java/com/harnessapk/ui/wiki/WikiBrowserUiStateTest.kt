package com.harnessapk.ui.wiki

import com.harnessapk.storage.WikiEntity
import com.harnessapk.storage.WikiVersionEntity
import com.harnessapk.wiki.WikiChunk
import com.harnessapk.wiki.WikiContentUnavailableException
import com.harnessapk.wiki.WikiDocument
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiSection
import com.harnessapk.wiki.WikiSourceHit
import com.harnessapk.wiki.WikiSourceLocator
import com.harnessapk.wiki.WikiSourceMatch
import com.harnessapk.wiki.WikiVersionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiBrowserUiStateTest {
    @Test
    fun `empty library has one explicit empty state`() {
        assertEquals(WikiLibraryUiState.Empty, wikiLibraryUiState(emptyList(), emptyMap()))
    }

    @Test
    fun `installed versions preserve active and default scope`() {
        val library = wikiLibraryUiState(
            wikis = listOf(wiki(activeVersion = 2)),
            versionsByWiki = mapOf(
                "fixture.history" to listOf(
                    version(1, enabledForNewConversations = false),
                    version(2, enabledForNewConversations = true),
                ),
            ),
        ) as WikiLibraryUiState.Content

        assertEquals(1, library.entries.size)
        assertEquals(WikiRef("fixture.history", 2), library.entries.single().activeVersion?.ref)
        assertTrue(library.entries.single().activeVersion?.enabledForNewConversations == true)
        assertEquals(2, library.entries.single().versions.size)
    }

    @Test
    fun `breadcrumbs retain document then selected section path`() {
        val breadcrumbs = wikiBreadcrumbs(
            document = document(),
            sectionTrail = listOf(
                section(id = "section-root", title = "卷第一"),
                section(id = "section-leaf", title = "威烈王二十三年", parentId = "section-root"),
            ),
        )

        assertEquals(listOf("资治通鉴", "卷第一", "威烈王二十三年"), breadcrumbs.map(WikiBreadcrumb::title))
    }

    @Test
    fun `source search groups original hits by document and section`() {
        val source = sourceHit(sectionId = "section-leaf", text = "司马光修《资治通鉴》。")
        val grouped = groupWikiSearchHits(
            hits = listOf(source),
            documentsById = mapOf("document-zztj" to document()),
            sectionsById = mapOf("section-leaf" to section(id = "section-leaf", title = "卷第一")),
        )

        assertEquals(1, grouped.size)
        assertEquals("资治通鉴", grouped.single().documentTitle)
        assertEquals("卷第一", grouped.single().sectionPath)
        assertEquals(source.chunkId, grouped.single().hits.single().chunkId)
    }

    @Test
    fun `source route restores exact valid identifiers and rejects malformed ones`() {
        val ref = WikiRef("fixture.history", 2)
        val route = WikiRoutes.source(ref, "chunk/one")

        assertEquals(ref, WikiRoutes.decodeRef("fixture.history", "2"))
        assertEquals("chunk/one", WikiRoutes.decodeChunkId("chunk%2Fone"))
        assertTrue(route.contains("fixture.history"))
        assertNull(WikiRoutes.decodeRef("Bad Wiki", "2"))
        assertNull(WikiRoutes.decodeRef("fixture.history", "0"))
        assertNull(WikiRoutes.decodeChunkId(""))
    }

    @Test
    fun `missing version is exposed as a readable recovery error`() {
        assertEquals(
            "该 Wiki 版本已不可用，请返回知识库重新选择。",
            wikiBrowserErrorMessage(WikiContentUnavailableException("Wiki 版本不存在")),
        )
        assertFalse(wikiBrowserErrorMessage(IllegalStateException("private path")).contains("private path"))
    }

    private fun wiki(activeVersion: Int?): WikiEntity = WikiEntity(
        id = "fixture.history",
        title = "资治通鉴",
        description = "按卷编排的史料",
        activeVersion = activeVersion,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun version(version: Int, enabledForNewConversations: Boolean): WikiVersionEntity = WikiVersionEntity(
        wikiId = "fixture.history",
        version = version,
        contentPath = "/files/wikis/fixture.history/$version/content.sqlite",
        schemaVersion = 1,
        contentHash = "a".repeat(64),
        packageHash = "b".repeat(64),
        publisherKeyId = "ed25519:fixture",
        publisherFingerprint = "c".repeat(64),
        manifestJson = "{}",
        sizeBytes = 1024L,
        enabledForNewConversations = enabledForNewConversations,
        state = WikiVersionState.READY.name,
        installedAt = 1L,
    )

    private fun document() = WikiDocument(
        id = "document-zztj",
        title = "资治通鉴",
        responsibility = "司马光",
        edition = "",
        language = "zh-Hant",
        rights = "",
        sourceHash = "d".repeat(64),
        ordinal = 1,
        metadataJson = "{}",
    )

    private fun section(id: String, title: String, parentId: String? = null) = WikiSection(
        id = id,
        documentId = "document-zztj",
        parentSectionId = parentId,
        title = title,
        path = title,
        ordinal = 1,
        metadataJson = "{}",
    )

    private fun sourceHit(sectionId: String, text: String) = WikiSourceHit(
        chunk = WikiChunk(
            id = "chunk-one",
            sectionId = sectionId,
            ordinal = 1,
            originalText = text,
            locator = WikiSourceLocator("locator-one", "chunk-one", "卷一", "{}"),
            contentHash = "e".repeat(64),
        ),
        matches = listOf(WikiSourceMatch(com.harnessapk.wiki.WikiSearchChannel.ORIGINAL, "原文")),
    )
}
