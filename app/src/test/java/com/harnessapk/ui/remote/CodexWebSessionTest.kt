package com.harnessapk.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class CodexWebSessionTest {
    @Test
    fun `document matching ignores fragments but rejects old pages and unknown urls`() {
        assertTrue(codexSameDocumentUrl(CodexLoginUrl, "$CodexLoginUrl#login"))
        assertFalse(codexSameDocumentUrl(CodexWebUrl, CodexLoginUrl))
        assertFalse(codexSameDocumentUrl(null, null))
        assertFalse(codexSameDocumentUrl(CodexLoginUrl, "$CodexLoginUrl?error=blocked"))
    }

    @Test
    fun `new navigation clears sticky 403 and can finish successfully`() {
        val failed = CodexWebLoadState().failed(codexHttpErrorMessage(403)).finished()
        assertFalse(failed.loading)
        assertNotNull(failed.error)
        val login = failed.started()
        assertTrue(login.loading)
        assertNull(login.error)
        assertEquals(CodexWebLoadState(loading = false), login.finished())
    }

    @Test
    fun `failed page finish does not clear error or trigger automatic relogin`() {
        val failed = CodexWebLoadState().failed(codexHttpErrorMessage(403))
        assertEquals(failed, failed.finished())
        assertTrue(failed.error!!.contains("网页验证"))
        assertTrue(codexHttpErrorMessage(401).contains("401"))
        assertEquals("服务返回 503，请稍后重试。", codexHttpErrorMessage(503))
    }

    @Test
    fun `cookie reset probes and writes only explicit login hosts`() {
        val probed = mutableListOf<String>()
        val plan = codexCookieRemovals { url ->
            probed += url
            "__Secure-next-auth.session-token.0=secret-value; ordinary=other-secret"
        }
        assertEquals(CodexSessionHosts.toSet(), probed.map { URI(it).host }.toSet())
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { URI(it.url).host in CodexSessionHosts })
        assertTrue(plan.none { "secret-value" in it.cookie || "other-secret" in it.cookie })
        assertTrue(plan.all { "Max-Age=0" in it.cookie && "; Secure; HttpOnly" in it.cookie })
        assertTrue(plan.any { "; Domain=.openai.com;" in it.cookie })
        assertTrue(plan.any { it.cookie.startsWith("__Secure-next-auth.session-token.0=;") })
    }

    @Test
    fun `host prefixed cookies are expired only without domain and at root`() {
        val plan = codexCookieRemovals { "__Host-session=secret" }
        assertEquals(CodexSessionHosts.size, plan.size)
        assertTrue(plan.all { "; Path=/; Secure;" in it.cookie && "; Domain=" !in it.cookie })
    }

    @Test
    fun `path scoped auth cookies are discovered without global deletion`() {
        val plan = codexCookieRemovals { url ->
            if (url == "https://auth.openai.com/auth/login") "auth=secret" else null
        }
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.url == "https://auth.openai.com/auth/login" })
        assertTrue(plan.all { "; Path=/auth/login" in it.cookie })
        assertEquals(emptyList<CodexCookieRemoval>(), codexCookieRemovals { null })
    }

    @Test
    fun `trailing slash cookie paths are included`() {
        for (path in listOf("/auth/", "/api/auth/", "/dots/")) {
            val plan = codexCookieRemovals { url ->
                if (URI(url).path.startsWith(path)) "session=secret" else null
            }
            assertTrue(plan.any { "; Path=$path;" in it.cookie })
        }
    }

    @Test
    fun `rejected cookie write aborts reset rather than reporting success`() = kotlinx.coroutines.test.runTest {
        val removal = CodexCookieRemoval("https://chatgpt.com/", "test=; Max-Age=0")
        var writes = 0
        val result = runCatching {
            expireCodexCookies(listOf(removal, removal)) { _, completed ->
                writes++
                completed(false)
            }
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(1, writes)
    }

    @Test
    fun `successful cookie writes all complete before reset returns`() = kotlinx.coroutines.test.runTest {
        val removal = CodexCookieRemoval("https://chatgpt.com/", "test=; Max-Age=0")
        var completedWrites = 0
        expireCodexCookies(listOf(removal, removal)) { _, completed ->
            completedWrites++
            completed(true)
        }
        assertEquals(2, completedWrites)
    }

    @Test
    fun `malformed cookie names cannot inject attributes`() {
        val plan = codexCookieRemovals { "broken; bad name=x; injected\r\nName=y; good=value" }
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.cookie.startsWith("good=;") })
    }
}
