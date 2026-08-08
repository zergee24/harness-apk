package com.harnessapk.remote

interface RemoteCommandSender {
    fun send(command: RebuiltRemoteCommand): Boolean
}

class RemoteTransport(
    private val outbox: RemoteCommandOutbox,
    private val sender: RemoteCommandSender,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun flush(): Int {
        val timestamp = now()
        var sent = 0
        outbox.retryable(timestamp).forEach { command ->
            val nextAttempt = timestamp + retryDelayMillis(command.attemptCount)
            if (sender.send(command)) {
                outbox.markSent(command.commandId, timestamp, nextAttempt)
                sent++
            } else {
                outbox.markDeliveryDeferred(
                    commandId = command.commandId,
                    now = timestamp,
                    retryAt = nextAttempt,
                    reason = "Mac 尚未连接",
                )
            }
        }
        return sent
    }

    private fun retryDelayMillis(attemptCount: Int): Long =
        (3_000L shl attemptCount.coerceAtMost(4)).coerceAtMost(48_000L)
}
