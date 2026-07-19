package com.harnessapk.chat

import com.harnessapk.common.AppDispatchers
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.websearch.JinaWebSearchClient
import com.harnessapk.websearch.WebSearchContext
import com.harnessapk.websearch.WebSearchRequest
import com.harnessapk.websearch.nativeWebSearchModeForRequest
import com.harnessapk.websearch.shouldUseExternalWebSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ChatExecutionCoordinator(
    private val executionRepository: ChatExecutionRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    private val providerRepository: ProviderRepository,
    private val webSearchClient: JinaWebSearchClient,
    private val attachmentStore: QueuedAttachmentStore,
    private val dispatchers: AppDispatchers,
    private val webSearchAllowed: suspend (conversationId: String) -> Boolean = { true },
    private val onWorkScheduled: () -> Unit = {},
    private val exactRequestCommitted: suspend (requestId: String) -> Boolean = { requestId ->
        executionRepository.entry(requestId) != null
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val runners = mutableMapOf<String, Job>()
    private val runnersMutex = Mutex()
    private val _activeConversationIds = MutableStateFlow<Set<String>>(emptySet())
    private val _activeExecutionCount = MutableStateFlow(0)

    val activeExecutionCount: StateFlow<Int> = _activeExecutionCount.asStateFlow()
    val activeConversationIds: StateFlow<Set<String>> = _activeConversationIds.asStateFlow()

    suspend fun enqueue(request: EnqueueChatRequest): ChatExecutionEntry = withContext(dispatchers.io) {
        val persistedAttachments = attachmentStore.persistAll(request.attachments)
        try {
            val entry = executionRepository.enqueue(request.copy(attachments = persistedAttachments))
            withContext(NonCancellable) {
                try {
                    ensureRunner(entry.conversationId)
                    onWorkScheduled()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                }
            }
            entry
        } catch (failure: Throwable) {
            cleanupUncommittedAttachments(request.requestId, persistedAttachments)
            throw failure
        }
    }

    private suspend fun cleanupUncommittedAttachments(
        requestId: String,
        attachments: List<PendingImageAttachment>,
    ) = withContext(NonCancellable) {
        val committed = runCatching { exactRequestCommitted(requestId) }.getOrNull()
        if (committed == false) {
            runCatching { attachmentStore.cleanup(attachments) }
        }
    }

    fun resumePending() {
        scope.launch {
            executionRepository.runningConversationIds().forEach { conversationId ->
                val hasActiveRunner = runnersMutex.withLock { runners[conversationId]?.isActive == true }
                if (shouldRecoverRunningExecution(hasActiveRunner)) {
                    executionRepository.recoverAfterProcessDeath(conversationId)
                }
            }
            executionRepository.openConversationIds().forEach { conversationId ->
                ensureRunner(conversationId)
            }
        }
    }

    fun steer(entryId: String) {
        scope.launch {
            val promoted = executionRepository.prepareSteer(entryId)
            restartConversation(promoted.conversationId)
        }
    }

    fun cancelActive(conversationId: String) {
        scope.launch {
            executionRepository.cancelRunning(conversationId)
            restartConversation(conversationId)
        }
    }

    suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private suspend fun restartConversation(conversationId: String) {
        val running = runnersMutex.withLock { runners[conversationId] }
        running?.cancel()
        running?.join()
        ensureRunner(conversationId)
    }

    private suspend fun ensureRunner(conversationId: String) {
        runnersMutex.withLock {
            if (runners[conversationId]?.isActive == true) return
            runners[conversationId] = scope.launch {
                runConversation(conversationId)
            }
        }
    }

    private suspend fun runConversation(conversationId: String) {
        try {
            while (currentCoroutineContext().isActive) {
                val next = executionRepository.nextQueued(conversationId) ?: return
                executionRepository.markRunning(next.id, assistantMessageId = null)
                markActive(conversationId, true)
                try {
                    val history = executionRepository.requestHistory(next.id)
                    val result = sendMessageUseCase.execute(
                        entry = next,
                        history = history,
                        webSearchContext = webSearchContextFor(next),
                        nativeWebSearchMode = nativeWebSearchModeFor(next),
                        onAssistantCreated = { assistantId ->
                            executionRepository.markRunning(next.id, assistantId)
                        },
                    )
                    executionRepository.markTerminal(
                        entryId = next.id,
                        status = result.status,
                        assistantMessageId = result.assistantMessageId,
                        errorMessage = result.errorMessage,
                    )
                } catch (cancelled: CancellationException) {
                    val entry = executionRepository.entry(next.id)
                    if (entry?.status == ChatExecutionStatus.RUNNING) {
                        executionRepository.markTerminal(next.id, ChatExecutionStatus.CANCELLED)
                    }
                    throw cancelled
                } finally {
                    markActive(conversationId, false)
                }
            }
        } finally {
            val finishedRunner = currentCoroutineContext()[Job]
            runnersMutex.withLock {
                if (shouldRemoveRunner(runners[conversationId], finishedRunner)) {
                    runners.remove(conversationId)
                }
            }
        }
    }

    private suspend fun webSearchContextFor(entry: ChatExecutionEntry): WebSearchContext? {
        if (!webSearchAllowed(entry.conversationId)) return null
        val settings = entry.requestContext.webSearchSettings
        val query = entryUserText(entry)
        val nativeMode = nativeWebSearchModeFor(entry)
        if (!shouldUseExternalWebSearch(query, entry.requestContext.webSearchEnabled, settings, nativeMode)) return null
        return runCatching {
            WebSearchContext(
                webSearchClient.searchKeywords(
                    WebSearchRequest(query = query, maxResults = settings.maxResults),
                ),
            )
        }.getOrNull()?.takeIf { it.results.results.isNotEmpty() }
    }

    private suspend fun nativeWebSearchModeFor(entry: ChatExecutionEntry): NativeWebSearchMode? {
        if (!webSearchAllowed(entry.conversationId)) return null
        val provider = if (entry.providerId == null) {
            providerRepository.defaultProviderForText().profile
        } else {
            providerRepository.providerWithKey(entry.providerId).profile
        }
        return nativeWebSearchModeForRequest(
            query = entryUserText(entry),
            enabledForSession = entry.requestContext.webSearchEnabled,
            settings = entry.requestContext.webSearchSettings,
            provider = provider,
        )
    }

    private suspend fun entryUserText(entry: ChatExecutionEntry): String =
        requireNotNull(executionRepository.requestHistory(entry.id).lastOrNull { it.id == entry.userMessageId }) {
            "待执行用户消息不存在"
        }.content

    private fun markActive(conversationId: String, active: Boolean) {
        _activeConversationIds.update { current ->
            if (active) current + conversationId else current - conversationId
        }
        _activeExecutionCount.value = _activeConversationIds.value.size
    }
}

internal fun <T> shouldRemoveRunner(registered: T?, finished: T?): Boolean =
    registered != null && registered === finished

internal fun shouldRecoverRunningExecution(hasActiveRunner: Boolean): Boolean = !hasActiveRunner
