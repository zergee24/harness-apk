package com.harnessapk.ui.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun expireCodexCookies(
    removals: List<CodexCookieRemoval>,
    writeCookie: (CodexCookieRemoval, (Boolean) -> Unit) -> Unit,
) {
    for (removal in removals) {
        suspendCancellableCoroutine<Unit> { continuation ->
            writeCookie(removal) { accepted ->
                if (continuation.isActive) {
                    if (accepted) continuation.resume(Unit)
                    else continuation.resumeWithException(IllegalStateException("Cookie 清理操作被拒绝"))
                }
            }
        }
    }
}

internal const val CodexWebUrl = "https://chatgpt.com/dots/home"
internal const val CodexLoginUrl = "https://chatgpt.com/auth/login"

internal fun codexSameDocumentUrl(candidate: String?, active: String?): Boolean =
    candidate != null && active != null && candidate.substringBefore('#') == active.substringBefore('#')

/** A new navigation dismisses an old error; finishing a failed page must not hide its error. */
internal data class CodexWebLoadState(
    val loading: Boolean = true,
    val error: String? = null,
) {
    fun started() = CodexWebLoadState()
    fun finished() = copy(loading = false)
    fun failed(message: String) = CodexWebLoadState(loading = false, error = message)
}

internal fun codexHttpErrorMessage(status: Int): String = when (status) {
    401 -> "登录状态可能已失效（401），可重新登录。"
    403 -> "访问被拒绝（403），可能是登录状态、网络或网页验证问题。可重新登录，或查看原网页完成验证；仍失败时请在浏览器中打开。"
    else -> "服务返回 $status，请稍后重试。"
}

// These shells share Android's default cookie jar. Never removeAllCookies(),
// removeSessionCookies() or deleteAllData(): those also sign out DSH/ZCode.
internal val CodexSessionHosts = listOf(
    "chatgpt.com",
    "chat.openai.com",
    "auth.openai.com",
    "auth0.openai.com",
    "openai.com",
)

internal data class CodexCookieRemoval(val url: String, val cookie: String)

/**
 * Expire only cookies visible on the known ChatGPT/OpenAI login origins.
 * Probe the common auth paths as getCookie(root) misses path-scoped cookies.
 * Keep both host-only and parent-domain forms, including chunked session names.
 * Cookie values are deliberately never copied into the removal plan.
 */
internal fun codexCookieRemovals(readCookies: (String) -> String?): List<CodexCookieRemoval> {
    val paths = listOf(
        "/", "/auth", "/auth/", "/auth/login", "/api/auth", "/api/auth/", "/api/auth/signin",
        "/dots", "/dots/", "/dots/home",
    )
    val removals = linkedSetOf<CodexCookieRemoval>()
    for (host in CodexSessionHosts) {
        val parent = if (host.endsWith(".openai.com")) "openai.com" else host
        val domains = listOf(null, host, ".$host", parent, ".$parent").distinct()
        for (path in paths) {
            val url = "https://$host$path"
            val names = readCookies(url).orEmpty().split(';').mapNotNull { part ->
                val name = part.substringBefore('=', "").trim()
                name.takeIf { it.matches(Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")) }
            }.distinct()
            for (name in names) {
                for (domain in domains) {
                    // __Host- cookies only permit Path=/ and no Domain attribute.
                    if (name.startsWith("__Host-") && (path != "/" || domain != null)) continue
                    val domainAttribute = domain?.let { "; Domain=$it" }.orEmpty()
                    removals += CodexCookieRemoval(
                        url,
                        "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path$domainAttribute; Secure; HttpOnly",
                    )
                }
            }
        }
    }
    return removals.toList()
}
