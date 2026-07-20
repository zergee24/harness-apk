package com.harnessapk.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalAgentBundleIntentTest {
    @Test
    fun viewActionUsesItsDataUri() {
        assertEquals(
            "content://downloads/document/42",
            externalAgentBundleUri(
                action = "android.intent.action.VIEW",
                viewUri = "content://downloads/document/42",
                sharedUri = null,
            ),
        )
    }

    @Test
    fun sendActionUsesItsSharedUri() {
        assertEquals(
            "content://downloads/document/42",
            externalAgentBundleUri(
                action = "android.intent.action.SEND",
                viewUri = null,
                sharedUri = "content://downloads/document/42",
            ),
        )
    }

    @Test
    fun unrelatedActionDoesNotStartAnImport() {
        assertNull(
            externalAgentBundleUri(
                action = "android.intent.action.MAIN",
                viewUri = "content://downloads/document/42",
                sharedUri = "content://downloads/document/43",
            ),
        )
    }
}
