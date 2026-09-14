package com.harnessapk.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ZCode 远程配对链接的本地存档。URL 含签名 hash，等同工作区控制权：
 * 仅存 SharedPreferences，不进日志、不进备份面。
 */
class ZcodeWebRemoteStore(context: Context) {
    private val preferences = context.getSharedPreferences("zcode_web_remote", Context.MODE_PRIVATE)
    private val _url = MutableStateFlow(preferences.getString("pairing_url", null))
    val url: StateFlow<String?> = _url.asStateFlow()

    fun save(raw: String) {
        val link = raw.trim()
        preferences.edit().putString("pairing_url", link).apply()
        _url.value = link
    }

    fun clear() {
        preferences.edit().clear().apply()
        _url.value = null
    }
}
