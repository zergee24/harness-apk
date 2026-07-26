package com.harnessapk.chat

import android.content.Context
import android.os.PowerManager

class ChatExecutionPowerGuard(context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    suspend fun <T> whileRunning(block: suspend () -> T): T {
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "com.harnessapk:chat-generation",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
        return try {
            block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MILLIS = 16 * 60 * 1_000L
    }
}
