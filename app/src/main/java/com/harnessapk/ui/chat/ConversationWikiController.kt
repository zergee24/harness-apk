package com.harnessapk.ui.chat

import com.harnessapk.wiki.ConversationWikiMount
import com.harnessapk.wiki.ConversationWikiMountSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ConversationWikiControllerState(
    val writePending: Boolean = false,
    val refreshedMounts: List<ConversationWikiMount>? = null,
    val failure: Throwable? = null,
    val settledGeneration: Long = 0L,
)

class ConversationWikiController(
    private val scope: CoroutineScope,
    private val applyScope: suspend (List<ConversationWikiMountSelection>) -> Unit,
    private val restoreDefaultsAction: suspend () -> Unit,
    private val reloadMounts: suspend () -> List<ConversationWikiMount>,
) {
    private var latestGeneration = 0L
    private val requests = Channel<ConversationWikiRequest>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(ConversationWikiControllerState())

    val state: StateFlow<ConversationWikiControllerState> = _state.asStateFlow()

    init {
        scope.launch {
            for (request in requests) process(request)
        }
    }

    fun canApply(): Boolean = !state.value.writePending

    fun apply(selections: List<ConversationWikiMountSelection>) {
        enqueue(ConversationWikiMutation.Replace(selections))
    }

    fun restoreDefaults() {
        enqueue(ConversationWikiMutation.RestoreDefaults)
    }

    private fun enqueue(mutation: ConversationWikiMutation) {
        val generation = synchronized(this) { ++latestGeneration }
        _state.value = _state.value.copy(writePending = true, failure = null)
        check(requests.trySend(ConversationWikiRequest(generation, mutation)).isSuccess) {
            "知识库范围更新队列已关闭"
        }
    }

    private suspend fun process(request: ConversationWikiRequest) {
        try {
            when (val mutation = request.mutation) {
                is ConversationWikiMutation.Replace -> applyScope(mutation.selections)
                ConversationWikiMutation.RestoreDefaults -> restoreDefaultsAction()
            }
            val refreshed = reloadMounts()
            if (isLatest(request.generation)) {
                _state.value = ConversationWikiControllerState(
                    refreshedMounts = refreshed,
                    settledGeneration = request.generation,
                )
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            if (isLatest(request.generation)) {
                _state.value = ConversationWikiControllerState(
                    failure = cancelled,
                    settledGeneration = request.generation,
                )
            }
        } catch (failure: Throwable) {
            if (isLatest(request.generation)) {
                _state.value = ConversationWikiControllerState(
                    failure = failure,
                    settledGeneration = request.generation,
                )
            }
        }
    }

    private fun isLatest(generation: Long): Boolean = generation == synchronized(this) { latestGeneration }

    private data class ConversationWikiRequest(
        val generation: Long,
        val mutation: ConversationWikiMutation,
    )

    private sealed interface ConversationWikiMutation {
        data class Replace(val selections: List<ConversationWikiMountSelection>) : ConversationWikiMutation
        data object RestoreDefaults : ConversationWikiMutation
    }
}
