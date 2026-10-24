package com.harnessapk.chat

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

class ChatExecutionPowerGuard(context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)

    suspend fun <T> whileRunning(block: suspend () -> T): T {
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "com.harnessapk:chat-generation",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
        // 部分机型退到主屏幕会让 WiFi 进省电甚至断流，Socket 读停在半路直到超时；
        // 生成期间保持 WiFi 高性能，回到前台也不留下断掉的读。
        val wifiLock = wifiManager?.let { manager ->
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG)
            } else {
                @Suppress("DEPRECATION")
                manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
            }).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        return try {
            block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        }
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MILLIS = 16 * 60 * 1_000L
        const val WIFI_LOCK_TAG = "com.harnessapk:chat-generation-wifi"
    }
}
