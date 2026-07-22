package com.harnessapk.ui.wiki

import com.harnessapk.wiki.MessageWikiCitation
import com.harnessapk.wiki.WikiCitationVerificationState
import com.harnessapk.wiki.WikiRef
import org.junit.Assert.assertEquals
import org.junit.Test

class WikiCitationSourceStateTest {
    private val citation = MessageWikiCitation(
        id = "2f17f6dc-4fe9-4d3a-beb2-9ef46c4379ca",
        messageId = "message-1",
        displayOrdinal = 1,
        ref = WikiRef("history.zztj", 1),
        wikiTitle = "资治通鉴",
        documentId = "document-1",
        sectionId = "section-1",
        chunkId = "chunk-1",
        sourceTitle = "卷一",
        sectionPath = "卷一 / 起始",
        locatorLabel = "第一段",
        originalTextSnapshot = "臣光曰：以史为鉴。",
        originalTextSha256 = "93ed4f5ca1357547e3b753c3842bdd891d5568916a12ba011461ebe03ecc5a3f",
        answerRangesJson = "[[0,1]]",
        verificationState = WikiCitationVerificationState.VERIFIED,
        createdAt = 0L,
    )

    @Test
    fun installedExactVersionWithMatchingChunkHashOpensSourceReader() {
        assertEquals(
            WikiCitationSourceContentState.Installed(citation),
            wikiCitationSourceContentState(
                citation = citation,
                exactVersionReady = true,
                installedOriginalText = citation.originalTextSnapshot,
            ),
        )
    }

    @Test
    fun missingOrChangedPackageUsesImmutableSavedSnapshot() {
        assertEquals(
            WikiCitationSourceContentState.Snapshot(citation),
            wikiCitationSourceContentState(
                citation = citation,
                exactVersionReady = false,
                installedOriginalText = null,
            ),
        )
        assertEquals(
            WikiCitationSourceContentState.Snapshot(citation),
            wikiCitationSourceContentState(
                citation = citation,
                exactVersionReady = true,
                installedOriginalText = "被替换后的原文",
            ),
        )
    }
}
