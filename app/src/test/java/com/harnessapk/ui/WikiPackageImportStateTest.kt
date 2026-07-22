package com.harnessapk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiPackageImportStateTest {
    @Test
    fun `external package opens library with one pending uri`() {
        val transition = reduceWikiPackageImport(
            WikiPackageImportState(),
            WikiPackageImportEvent.ExternalPackageReceived("content://packages/history.hwiki"),
        )

        assertTrue(transition.navigateToLibrary)
        assertEquals("content://packages/history.hwiki", transition.state.pendingUri)
    }

    @Test
    fun `duplicate external package does not navigate twice`() {
        val first = reduceWikiPackageImport(
            WikiPackageImportState(),
            WikiPackageImportEvent.ExternalPackageReceived("content://packages/history.hwiki"),
        )
        val repeated = reduceWikiPackageImport(
            first.state,
            WikiPackageImportEvent.ExternalPackageReceived("content://packages/history.hwiki"),
        )

        assertFalse(repeated.navigateToLibrary)
        assertEquals(first.state, repeated.state)
    }

    @Test
    fun `picker and restored pending uri use the same library handoff`() {
        val selected = reduceWikiPackageImport(
            WikiPackageImportState(),
            WikiPackageImportEvent.PickerPackageSelected("content://packages/history.hwiki"),
        )
        val restored = reduceWikiPackageImport(
            selected.state,
            WikiPackageImportEvent.RestorePendingImport,
        )

        assertTrue(selected.navigateToLibrary)
        assertTrue(restored.navigateToLibrary)
        assertEquals("content://packages/history.hwiki", restored.state.pendingUri)
    }

    @Test
    fun `cancel and invalid package clear only the pending uri`() {
        val state = WikiPackageImportState(pendingUri = "content://packages/history.hwiki")

        val cancelled = reduceWikiPackageImport(state, WikiPackageImportEvent.ImportCancelled)
        val invalid = reduceWikiPackageImport(
            state,
            WikiPackageImportEvent.ImportRejected("知识库包校验失败"),
        )

        assertNull(cancelled.state.pendingUri)
        assertNull(invalid.state.pendingUri)
        assertEquals("知识库包校验失败", invalid.errorMessage)
    }

    @Test
    fun `unknown publisher requires explicit confirmation while known publisher installs directly`() {
        assertEquals(
            WikiImportTrustDecision.REQUIRE_CONFIRMATION,
            wikiImportTrustDecision(isKnownPublisher = false),
        )
        assertEquals(
            WikiImportTrustDecision.INSTALL_DIRECTLY,
            wikiImportTrustDecision(isKnownPublisher = true),
        )
    }
}
