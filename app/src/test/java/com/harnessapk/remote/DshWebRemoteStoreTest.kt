package com.harnessapk.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DshWebRemoteStoreTest {
    @Test
    fun parsesSetupStringIntoCleanUrlAndCredentials() {
        val setup = parseDshWebSetup("https://dsh-test:test-password@relay.zerg.work/dsh-1c3b/")
        requireNotNull(setup)
        assertEquals("https://relay.zerg.work/dsh-1c3b/", setup.url)
        assertEquals("dsh-test", setup.username)
        assertEquals("test-password", setup.password)
        // The URL handed to the WebView must not carry the credential.
        assertEquals(false, setup.url.contains("dsh-test"))
        assertEquals(false, setup.url.contains("test-password"))
    }

    @Test
    fun decodesPercentEncodedCredentialAndAddsTrailingSlash() {
        val setup = parseDshWebSetup("https://user:p%40ss@relay.zerg.work/dsh-1c3b")
        requireNotNull(setup)
        assertEquals("https://relay.zerg.work/dsh-1c3b/", setup.url)
        assertEquals("p@ss", setup.password)
    }

    @Test
    fun rejectsInsecureOrIncompleteSetupStrings() {
        assertNull(parseDshWebSetup("http://dsh-test:test-password@relay.zerg.work/dsh-1c3b/"))
        assertNull(parseDshWebSetup("https://relay.zerg.work/dsh-1c3b/"))
        assertNull(parseDshWebSetup("https://dsh-test@relay.zerg.work/dsh-1c3b/"))
        assertNull(parseDshWebSetup("https://dsh-test:@relay.zerg.work/dsh-1c3b/"))
        assertNull(parseDshWebSetup(""))
    }
}
