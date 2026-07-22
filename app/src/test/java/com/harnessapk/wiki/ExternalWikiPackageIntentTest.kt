package com.harnessapk.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalWikiPackageIntentTest {
    @Test
    fun `view action uses its data uri`() {
        assertEquals(
            "content://downloads/document/42",
            externalWikiPackageUri(
                action = "android.intent.action.VIEW",
                viewUri = "content://downloads/document/42",
                sharedUri = null,
            ),
        )
    }

    @Test
    fun `send action uses its shared uri`() {
        assertEquals(
            "content://downloads/document/42",
            externalWikiPackageUri(
                action = "android.intent.action.SEND",
                viewUri = null,
                sharedUri = "content://downloads/document/42",
            ),
        )
    }

    @Test
    fun `unrelated action does not start an import`() {
        assertNull(
            externalWikiPackageUri(
                action = "android.intent.action.MAIN",
                viewUri = "content://downloads/document/42",
                sharedUri = "content://downloads/document/43",
            ),
        )
    }

    @Test
    fun `persisted permission keeps read and persistable flags only`() {
        assertEquals(
            WIKI_URI_READ_PERMISSION or WIKI_URI_PERSISTABLE_PERMISSION,
            wikiPersistableReadPermissionFlags(
                WIKI_URI_READ_PERMISSION or WIKI_URI_PERSISTABLE_PERMISSION or WIKI_URI_WRITE_PERMISSION,
            ),
        )
        assertEquals(0, wikiPersistableReadPermissionFlags(WIKI_URI_READ_PERMISSION))
    }

    @Test
    fun `generic share remains a candidate until package inspection`() {
        assertTrue(isGenericWikiPackageMimeType("application/zip"))
        assertTrue(isGenericWikiPackageMimeType("application/octet-stream"))
    }
}
