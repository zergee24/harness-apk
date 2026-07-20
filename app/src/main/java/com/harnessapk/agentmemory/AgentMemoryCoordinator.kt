package com.harnessapk.agentmemory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val AGENT_MEMORY_PERIODIC_ROUNDS = 10
private const val DEFAULT_AGENT_MEMORY_CHECKPOINT_ENTRIES = 256

class AgentMemoryCoordinator(
    private val scope: CoroutineScope,
    private val completedRoundCount: suspend (conversationId: String) -> Int,
    private val extract: suspend (conversationId: String) -> AgentMemoryExtractionResult,
    private val maxCheckpointEntries: Int = DEFAULT_AGENT_MEMORY_CHECKPOINT_ENTRIES,
) {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, ConversationDrainState>()
    private val checkpoints = LinkedHashMap<String, Int>(
        maxCheckpointEntries,
        0.75f,
        true,
    )

    init {
        require(maxCheckpointEntries > 0) { "关系记忆 checkpoint 上限必须大于零" }
    }

    fun onReplyCompleted(conversationId: String) {
        enqueue(conversationId, Trigger.REPLY_COMPLETED)
    }

    fun onConversationLeft(conversationId: String) {
        enqueue(conversationId, Trigger.CONVERSATION_LEFT)
    }

    internal suspend fun activeStateCount(): Int = mutex.withLock { states.size }

    internal suspend fun checkpointCount(): Int = mutex.withLock { checkpoints.size }

    private fun enqueue(conversationId: String, trigger: Trigger) {
        if (conversationId.isBlank()) return
        scope.launch {
            val stateToDrain = mutex.withLock {
                val state = states.getOrPut(conversationId, ::ConversationDrainState)
                state.record(trigger)
                if (state.running) {
                    null
                } else {
                    state.running = true
                    state
                }
            }
            if (stateToDrain != null) drain(conversationId, stateToDrain)
        }
    }

    private suspend fun drain(
        conversationId: String,
        ownedState: ConversationDrainState,
    ) {
        try {
            while (true) {
                val pending = mutex.withLock {
                    val registered = states[conversationId]
                    if (!shouldRemoveAgentMemoryState(registered, ownedState)) return
                    ownedState.takePending()
                }
                processPending(conversationId, pending)
                val continueDrain = mutex.withLock {
                    val registered = states[conversationId]
                    if (!shouldRemoveAgentMemoryState(registered, ownedState)) {
                        false
                    } else if (ownedState.hasPending()) {
                        true
                    } else {
                        states.remove(conversationId)
                        false
                    }
                }
                if (!continueDrain) return
            }
        } finally {
            withContext(NonCancellable) {
                val pendingToReplay = mutex.withLock {
                    if (shouldRemoveAgentMemoryState(states[conversationId], ownedState)) {
                        states.remove(conversationId)
                        ownedState.takePending().takeIf(PendingTriggers::hasPending)
                    } else {
                        null
                    }
                }
                pendingToReplay?.let { pending ->
                    if (pending.replyCompleted) {
                        enqueue(conversationId, Trigger.REPLY_COMPLETED)
                    }
                    if (pending.conversationLeft) {
                        enqueue(conversationId, Trigger.CONVERSATION_LEFT)
                    }
                }
            }
        }
    }

    private suspend fun processPending(
        conversationId: String,
        pending: PendingTriggers,
    ) {
        val completedRounds = try {
            completedRoundCount(conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        if (completedRounds <= 0) return

        val checkpoint = mutex.withLock { checkpoints[conversationId] ?: 0 }
        if (completedRounds <= checkpoint) return
        val shouldExtract = pending.conversationLeft ||
            (pending.replyCompleted && completedRounds % AGENT_MEMORY_PERIODIC_ROUNDS == 0)
        if (!shouldExtract) return

        val result = try {
            extract(conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        if (!result.advancesCheckpoint()) return

        mutex.withLock {
            checkpoints[conversationId] = maxOf(checkpoints[conversationId] ?: 0, completedRounds)
            while (checkpoints.size > maxCheckpointEntries) {
                val iterator = checkpoints.entries.iterator()
                iterator.next()
                iterator.remove()
            }
        }
    }
}

internal fun <T> shouldRemoveAgentMemoryState(registered: T?, finishing: T): Boolean =
    registered != null && registered === finishing

private enum class Trigger {
    REPLY_COMPLETED,
    CONVERSATION_LEFT,
}

private data class PendingTriggers(
    val replyCompleted: Boolean,
    val conversationLeft: Boolean,
) {
    fun hasPending(): Boolean = replyCompleted || conversationLeft
}

private class ConversationDrainState {
    var running: Boolean = false
    private var replyCompleted: Boolean = false
    private var conversationLeft: Boolean = false

    fun record(trigger: Trigger) {
        when (trigger) {
            Trigger.REPLY_COMPLETED -> replyCompleted = true
            Trigger.CONVERSATION_LEFT -> conversationLeft = true
        }
    }

    fun takePending(): PendingTriggers = PendingTriggers(
        replyCompleted = replyCompleted,
        conversationLeft = conversationLeft,
    ).also {
        replyCompleted = false
        conversationLeft = false
    }

    fun hasPending(): Boolean = replyCompleted || conversationLeft
}

private fun AgentMemoryExtractionResult.advancesCheckpoint(): Boolean = when (this) {
    is AgentMemoryExtractionResult.Succeeded -> true
    is AgentMemoryExtractionResult.Failed -> false
    is AgentMemoryExtractionResult.Skipped -> when (reason) {
        AgentMemoryExtractionSkipReason.CONVERSATION_MISSING,
        AgentMemoryExtractionSkipReason.ARCHIVED,
        AgentMemoryExtractionSkipReason.NO_AGENT,
        AgentMemoryExtractionSkipReason.NO_USER_MESSAGES,
        -> true
        AgentMemoryExtractionSkipReason.NO_COMPLETED_ASSISTANT,
        AgentMemoryExtractionSkipReason.IDENTITY_CHANGED,
        -> false
    }
}
