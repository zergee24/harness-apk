package com.harnessapk.capture

import android.content.Intent
import com.harnessapk.agent.H_BUNDLE_MIME_TYPE
import com.harnessapk.wiki.H_WIKI_MIME_TYPE
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingShareParserTest {
    @Test
    fun packageFormatsAlwaysWinBeforeOrdinaryShare() {
        assertEquals(
            IncomingShareRouteKind.WIKI_PACKAGE,
            classifyIncomingShare(Intent.ACTION_SEND, H_WIKI_MIME_TYPE, listOf("guide.hwiki"), true, 1),
        )
        assertEquals(
            IncomingShareRouteKind.AGENT_BUNDLE,
            classifyIncomingShare(Intent.ACTION_SEND, H_BUNDLE_MIME_TYPE, listOf("agent.hbundle"), true, 1),
        )
        assertEquals(
            IncomingShareRouteKind.WIKI_PACKAGE,
            classifyIncomingShare(Intent.ACTION_SEND, "application/octet-stream", listOf("guide.hwiki"), false, 1),
        )
        assertEquals(
            IncomingShareRouteKind.AGENT_BUNDLE,
            classifyIncomingShare(Intent.ACTION_SEND, "application/zip", listOf("agent.hbundle"), false, 1),
        )
    }

    @Test
    fun ordinaryTextImagesAndFilesUseOneShareRoute() {
        assertEquals(
            IncomingShareRouteKind.ORDINARY_SHARE,
            classifyIncomingShare(Intent.ACTION_SEND, "text/plain", emptyList(), true, 0),
        )
        assertEquals(
            IncomingShareRouteKind.ORDINARY_SHARE,
            classifyIncomingShare(Intent.ACTION_SEND_MULTIPLE, "image/*", listOf("a.jpg", "b.jpg"), false, 2),
        )
        assertEquals(
            IncomingShareRouteKind.ORDINARY_SHARE,
            classifyIncomingShare(Intent.ACTION_SEND, "application/pdf", listOf("report.pdf"), false, 1),
        )
    }
}
