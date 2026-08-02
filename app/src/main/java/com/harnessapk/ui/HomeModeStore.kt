package com.harnessapk.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun migrateStoredMode(raw: String?): MainMode = when (raw) {
    "LIFE" -> MainMode.LIFE
    "WORK" -> MainMode.WORK
    "ME" -> MainMode.ME
    else -> MainMode.LIFE
}

internal fun migrateStoredThemeSource(raw: String?): MainMode = when (raw) {
    "WORK" -> MainMode.WORK
    else -> MainMode.LIFE
}

class HomeModeStore(context: Context) {
    private val preferences = context.getSharedPreferences("home_mode", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(loadMode())
    val mode: StateFlow<MainMode> = _mode.asStateFlow()
    private val _themeSourceMode = MutableStateFlow(loadThemeSourceMode())
    val themeSourceMode: StateFlow<MainMode> = _themeSourceMode.asStateFlow()

    fun save(mode: MainMode, themeSourceMode: MainMode) {
        val normalizedSource = nextThemeSource(themeSourceMode, mode)
        preferences.edit()
            .putString("main_mode", mode.name)
            .putString("theme_source_mode", normalizedSource.name)
            .apply()
        _mode.value = mode
        _themeSourceMode.value = normalizedSource
    }

    internal fun reset() {
        preferences.edit().clear().commit()
        _mode.value = loadMode()
        _themeSourceMode.value = loadThemeSourceMode()
    }

    private fun loadMode(): MainMode = migrateStoredMode(preferences.getString("main_mode", null))

    private fun loadThemeSourceMode(): MainMode =
        migrateStoredThemeSource(preferences.getString("theme_source_mode", null))
}
