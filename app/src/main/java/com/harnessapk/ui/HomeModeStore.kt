package com.harnessapk.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun migrateStoredMode(raw: String?): MainMode = when (raw) {
    "LIFE" -> MainMode.LIFE
    "SESSION" -> MainMode.LIFE
    "WORK", "PROJECT", "REMOTE" -> MainMode.WORK
    else -> MainMode.LIFE
}

class HomeModeStore(context: Context) {
    private val preferences = context.getSharedPreferences("home_mode", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(load())
    val mode: StateFlow<MainMode> = _mode.asStateFlow()

    fun save(mode: MainMode) {
        preferences.edit().putString("main_mode", mode.name).apply()
        _mode.value = mode
    }

    internal fun reset() {
        preferences.edit().clear().commit()
        _mode.value = load()
    }

    private fun load(): MainMode = migrateStoredMode(preferences.getString("main_mode", null))
}
