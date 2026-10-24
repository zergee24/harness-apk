package com.harnessapk.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder

/**
 * One DSH browser-UI endpoint and the relay's basic-auth credential.
 *
 * `url` is the token-free public root (the Mac proxy injects the dsh launch
 * token), so the app never stores that token. `username`/`password` are the
 * Caddy basic-auth pair that gates the relay path; the WebView answers the
 * challenge from [DshWebRemoteActivity] instead of prompting the user.
 */
data class DshWebSetup(
    val url: String,
    val username: String,
    val password: String,
)

/**
 * Parse the one-time setup string shared by the Mac runner:
 *
 *   https://<basic-user>:<basic-password>@relay.example/dsh-<random>/
 *
 * The returned URL drops the userinfo, so the credential never travels in a
 * navigation URL, history entry, or log line.
 */
fun parseDshWebSetup(raw: String): DshWebSetup? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.host.isNullOrBlank()) return null
    val userInfo = uri.userInfo ?: return null
    val parts = userInfo.split(":", limit = 2)
    if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
    val username = runCatching { URLDecoder.decode(parts[0], "UTF-8") }.getOrNull() ?: return null
    val password = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() ?: return null
    if (username.isEmpty() || password.isEmpty()) return null
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    val normalizedPath = if (path.endsWith("/")) path else "$path/"
    val port = if (uri.port > 0) uri.port else -1
    val cleanUrl = runCatching {
        URI("https", null, uri.host, port, normalizedPath, uri.rawQuery, null).toString()
    }.getOrNull() ?: return null
    return DshWebSetup(url = cleanUrl, username = username, password = password)
}

/**
 * DSH browser-UI credentials, stored in app-private preferences.
 *
 * The basic-auth pair grants access to the user's DSH workspace through the
 * relay; treat this file like the ZCode remote pairing URL. It never enters the
 * APK, logs, or backups.
 */
class DshWebRemoteStore(context: Context) {
    private val preferences = context.getSharedPreferences("dsh_web_remote", Context.MODE_PRIVATE)
    private val _setup = MutableStateFlow(load())
    val setup: StateFlow<DshWebSetup?> = _setup.asStateFlow()

    private fun load(): DshWebSetup? {
        val url = preferences.getString("url", null) ?: return null
        val username = preferences.getString("username", null) ?: return null
        val password = preferences.getString("password", null) ?: return null
        if (url.isBlank() || username.isBlank() || password.isBlank()) return null
        return DshWebSetup(url = url, username = username, password = password)
    }

    /** Parse and store a setup string; returns false when it is not usable. */
    fun save(raw: String): Boolean {
        val parsed = parseDshWebSetup(raw) ?: return false
        return save(parsed)
    }

    fun save(setup: DshWebSetup): Boolean {
        if (setup.url.isBlank() || setup.username.isBlank() || setup.password.isBlank()) return false
        preferences.edit()
            .putString("url", setup.url)
            .putString("username", setup.username)
            .putString("password", setup.password)
            .apply()
        _setup.value = setup
        return true
    }

    fun clear() {
        preferences.edit().clear().apply()
        _setup.value = null
    }
}
