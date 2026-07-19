package com.harnessapk.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.harnessapk.agent.AgentEvidence
import com.harnessapk.agent.AgentRuntimeContext
import com.harnessapk.agent.AgentLifecycleCoordinator
import com.harnessapk.agent.sanitizeAgentCitationMarkers
import com.harnessapk.common.AppDispatchers
import com.harnessapk.common.AppError
import com.harnessapk.common.toUserMessage
import com.harnessapk.network.OpenAiCompatibleClient
import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.provider.ModelCapabilityResolver
import com.harnessapk.provider.ModelConfig
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderCapabilityCatalog
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.provider.ProviderProfile
import com.harnessapk.session.SessionRequestContext
import com.harnessapk.session.buildSessionOutgoingMessages
import com.harnessapk.websearch.WebSearchContext
import com.harnessapk.websearch.toVisibleSourcesMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.UUID
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class SendMessageUseCase(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val client: OpenAiCompatibleClient,
    private val dispatchers: AppDispatchers,
    private val contextCompressor: ContextCompressor = ContextCompressor(),
    private val imageCompressionPolicy: ImageCompressionPolicy = ImageCompressionPolicy(),
    private val requestBuilder: ModelAwareRequestBuilder = ModelAwareRequestBuilder(),
    private val remoteCapabilityCatalog: suspend () -> ProviderCapabilityCatalog? = { null },
    private val agentContextProvider: suspend (conversationId: String, query: String) -> AgentRuntimeContext? = { _, _ -> null },
    private val outputTransformerPipelineFactory: () -> StreamEventTransformerPipeline = {
        StreamEventTransformerPipeline(listOf(ThinkTagStreamTransformer()))
    },
    private val lifecycleCoordinator: AgentLifecycleCoordinator = AgentLifecycleCoordinator(),
) {
    suspend fun send(
        conversationId: String,
        text: String,
        attachments: List<PendingImageAttachment>,
        providerId: String? = null,
        modelOverride: String? = null,
        reasoningEffort: ReasoningEffort = defaultReasoningEffort(),
        sessionContext: SessionRequestContext? = null,
        webSearchContext: WebSearchContext? = null,
        nativeWebSearchMode: NativeWebSearchMode? = null,
    ): ChatExecutionResult = withContext(dispatchers.io) {
        val userMessageId = chatRepository.insertUserMessage(conversationId, text, attachments)
        execute(
            entry = ChatExecutionEntry(
                id = "immediate-$userMessageId",
                conversationId = conversationId,
                userMessageId = userMessageId,
                assistantMessageId = null,
                targetAssistantMessageId = null,
                sequence = 0L,
                type = ChatExecutionType.NORMAL,
                status = ChatExecutionStatus.RUNNING,
                providerId = providerId,
                model = modelOverride,
                reasoningEffort = reasoningEffort,
                requestContext = ChatExecutionRequestContext(
                    sessionContext = sessionContext,
                    webSearchEnabled = webSearchContext != null || nativeWebSearchMode != null,
                ),
                errorMessage = null,
                createdAt = 0L,
                updatedAt = 0L,
            ),
            history = chatRepository.listMessages(conversationId),
            webSearchContext = webSearchContext,
            nativeWebSearchMode = nativeWebSearchMode,
        )
    }

    suspend fun execute(
        entry: ChatExecutionEntry,
        history: List<ChatMessage>,
        webSearchContext: WebSearchContext? = null,
        nativeWebSearchMode: NativeWebSearchMode? = null,
        onAssistantCreated: suspend (String) -> Unit = {},
    ): ChatExecutionResult = withContext(dispatchers.io) {
        val currentUserMessage = requireNotNull(chatRepository.message(entry.userMessageId)) {
            "待执行的用户消息不存在"
        }
        val text = currentUserMessage.content.ifBlank { "请看这张截图" }
        val attachments = chatRepository.listAttachments(entry.userMessageId).map { attachment ->
            PendingImageAttachment(Uri.parse(attachment.uri), attachment.mimeType)
        }
        val provider = entry.providerId?.let { providerRepository.providerWithKey(it) }
            ?: providerRepository.defaultProviderForText()
        val selectedModel = entry.model?.trim()?.takeIf { it.isNotBlank() }
            ?: if (attachments.isNotEmpty()) {
                provider.profile.defaultVisionModel ?: provider.profile.defaultModel
            } else {
                provider.profile.defaultModel
            }
        val requestModel = modelForRequest(selectedModel)
        val resolvedCapability = ModelCapabilityResolver(
            remoteCatalog = remoteCapabilityCatalog(),
        ).resolve(provider.profile, requestModel)
        if (attachments.isNotEmpty() && !resolvedCapability.supportsImageInput()) {
            val assistantId = chatRepository.insertAssistantPending(
                entry.conversationId,
                provider.profile.id,
                requestModel,
            )
            onAssistantCreated(assistantId)
            chatRepository.markAssistantFailed(assistantId, AppError.VisionUnsupported().toUserMessage())
            return@withContext ChatExecutionResult(
                status = ChatExecutionStatus.FAILED,
                assistantMessageId = assistantId,
                errorMessage = AppError.VisionUnsupported().toUserMessage(),
            )
        }

        val userMessageId = currentUserMessage.id
        var assistantId: String? = null
        var requestDiagnostics: ModelAwareRequestDiagnostics? = null
        var accumulator: StreamingMessageAccumulator? = null
        var streamClock: TimeMark? = null
        var agentContext: AgentRuntimeContext? = null
        var latestSnapshot: StreamingMessageSnapshot? = null
        var streamCompleted = false
        val traceId = UUID.randomUUID().toString()
        val startedAtMillis = System.currentTimeMillis()
        var flushCount = 0
        var receivedChars = 0

        try {
            agentContext = agentContextProvider(entry.conversationId, text)
            val effectiveSearchContexts = effectiveAgentSearchContexts(
                agentContext = agentContext,
                webSearchContext = webSearchContext,
                nativeWebSearchMode = nativeWebSearchMode,
            )
            val imageDataUrls = attachments.map { it.toDataUrl() }
            val existingMemory = chatRepository.memoryForConversation(entry.conversationId)
            val compressed = contextCompressor.prepare(
                conversationId = entry.conversationId,
                messages = executionHistoryWithCurrent(history, currentUserMessage),
                currentUserMessageId = userMessageId,
                currentImageDataUrls = imageDataUrls,
                existingMemory = existingMemory,
                nowMillis = System.currentTimeMillis(),
                policyOverride = compressionPolicyForModel(resolvedCapability.toModelConfig()),
            )
            compressed.memoryToSave?.let { memory ->
                chatRepository.upsertMemory(memory)
                if (shouldRecordCompressionEvent(existingMemory, memory)) {
                    chatRepository.insertSystemEvent(
                        entry.conversationId,
                        contextCompressionEventText(CompressionTrigger.AUTO, memory),
                    )
                }
            }
            val nextAssistantId = chatRepository.insertAssistantPending(
                entry.conversationId,
                provider.profile.id,
                requestModel,
            )
            onAssistantCreated(nextAssistantId)
            assistantId = nextAssistantId
            latestSnapshot = StreamingMessageSnapshot(
                status = MessageStatus.PENDING,
                parts = emptyList(),
            )
            val modelAwareRequest = requestBuilder.build(
                provider = provider.profile,
                apiKey = provider.apiKey,
                capability = resolvedCapability,
                messages = buildSessionOutgoingMessages(
                    context = entry.requestContext.sessionContext,
                    baseMessages = compressed.messages,
                    webSearchContext = effectiveSearchContexts.webSearchContext,
                    agentSystemContext = agentContext?.systemPrompt,
                ),
                temperature = temperatureForModel(requestModel),
                selectedReasoningEffort = entry.reasoningEffort,
                webSearchRequested = effectiveSearchContexts.nativeWebSearchMode != null,
            )
            requestDiagnostics = modelAwareRequest.diagnostics

            var retriesUsed = 0
            while (true) {
                streamCompleted = false
                val activeStreamClock = TimeSource.Monotonic.markNow()
                val activeAccumulator = StreamingMessageAccumulator()
                streamClock = activeStreamClock
                accumulator = activeAccumulator
                val outputTransformerPipeline = outputTransformerPipelineFactory()
                try {
                    client.streamChatEvents(modelAwareRequest.request).collect { event ->
                        outputTransformerPipeline.transform(event).forEach { transformedEvent ->
                            receivedChars += transformedEvent.visiblePayloadLength()
                            activeAccumulator.onEvent(
                                transformedEvent,
                                activeStreamClock.elapsedNow().inWholeMilliseconds,
                            )?.let { flush ->
                                flushCount += 1
                                latestSnapshot = flush.snapshot
                                chatRepository.replaceMessagePartsFromSnapshot(nextAssistantId, flush.snapshot)
                            }
                        }
                    }
                    streamCompleted = true
                    break
                } catch (error: Throwable) {
                    if (!currentCoroutineContext().isActive ||
                        !shouldRetryStreamAfterTransportFailure(error, retriesUsed)
                    ) {
                        throw error
                    }
                    retriesUsed += 1
                    latestSnapshot = StreamingMessageSnapshot(
                        status = MessageStatus.PENDING,
                        parts = emptyList(),
                    )
                    chatRepository.replaceMessagePartsFromSnapshot(nextAssistantId, requireNotNull(latestSnapshot))
                    delay(STREAM_TRANSPORT_RETRY_DELAY_MILLIS)
                }
            }
            effectiveSearchContexts.webSearchContext?.toVisibleSourcesMarkdown()?.takeIf { it.isNotBlank() }?.let {
                latestSnapshot = appendVisibleTextPart(requireNotNull(latestSnapshot), it)
                chatRepository.replaceMessagePartsFromSnapshot(nextAssistantId, requireNotNull(latestSnapshot))
            }
            if (agentContext != null) {
                lifecycleCoordinator.serialized {
                    latestSnapshot = sanitizeAgentCitationMarkers(requireNotNull(latestSnapshot))
                    latestSnapshot = appendAgentSourcesPart(requireNotNull(latestSnapshot), agentContext.evidence)
                    chatRepository.replaceMessagePartsFromSnapshot(nextAssistantId, requireNotNull(latestSnapshot))
                }
            }
            currentCoroutineContext().ensureActive()
            chatRepository.markAssistantSucceeded(nextAssistantId)
            ChatExecutionResult(
                status = ChatExecutionStatus.SUCCEEDED,
                assistantMessageId = nextAssistantId,
                errorMessage = null,
            )
        } catch (cancelled: CancellationException) {
            val cancelledSnapshot = if (streamCompleted) {
                latestSnapshot?.toCancelledSnapshot()
            } else {
                cancelStreamingSnapshot(
                    accumulator = accumulator,
                    nowMillis = streamClock?.elapsedNow()?.inWholeMilliseconds ?: 0L,
                )
            }
            try {
                withContext(NonCancellable) {
                    assistantId?.let { id ->
                        if (cancelledSnapshot == null) {
                            chatRepository.markAssistantCancelled(id)
                        } else {
                            chatRepository.replaceMessagePartsFromSnapshot(
                                id,
                                sanitizeAgentSnapshotIfNeeded(cancelledSnapshot, agentContext),
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                // Preserve the original cancellation even if best-effort cleanup fails.
            }
            throw cancelled
        } catch (error: Throwable) {
            val failedAssistantId = assistantId ?: chatRepository.insertAssistantPending(
                entry.conversationId,
                provider.profile.id,
                requestModel,
            )
            if (assistantId != null && agentContext != null) {
                val failedSnapshot = if (streamCompleted) {
                    latestSnapshot
                } else {
                    accumulator?.snapshot() ?: latestSnapshot
                }
                failedSnapshot?.let {
                    chatRepository.replaceMessagePartsFromSnapshot(
                        failedAssistantId,
                        sanitizeAgentCitationMarkers(it),
                    )
                }
            }
            chatRepository.markAssistantFailed(
                failedAssistantId,
                buildChatErrorLog(
                    provider = provider.profile,
                    requestModel = requestModel,
                    conversationId = entry.conversationId,
                    error = error,
                    nowMillis = System.currentTimeMillis(),
                    sensitiveTerms = listOf(provider.apiKey, text),
                    requestDiagnostics = requestDiagnostics,
                    runtimeDiagnostics = ChatRuntimeDiagnostics(
                        traceId = traceId,
                        startedAtMillis = startedAtMillis,
                        failedAtMillis = System.currentTimeMillis(),
                        flushCount = flushCount,
                        receivedChars = receivedChars,
                    ),
                ),
            )
            ChatExecutionResult(
                status = ChatExecutionStatus.FAILED,
                assistantMessageId = failedAssistantId,
                errorMessage = error.toUserMessage(),
            )
        }
    }

    private fun PendingImageAttachment.toDataUrl(): String {
        val bytes = context.contentResolver.openInputStream(uri).use { input ->
            input?.readBytes() ?: throw AppError.Network("无法读取图片")
        }
        val payload = compressImageBytes(bytes, mimeType)
        if (payload.bytes.size > MAX_IMAGE_BYTES) throw AppError.ImageTooLarge()
        val encoded = Base64.encodeToString(payload.bytes, Base64.NO_WRAP)
        return "data:${payload.mimeType};base64,$encoded"
    }

    private fun compressImageBytes(bytes: ByteArray, originalMimeType: String): ImagePayload {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ImagePayload(bytes, originalMimeType)
        if (!imageCompressionPolicy.shouldCompress(bounds.outWidth, bounds.outHeight, bytes.size)) {
            return ImagePayload(bytes, originalMimeType)
        }

        val size = imageCompressionPolicy.scaledSize(bounds.outWidth, bounds.outHeight)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, size.width, size.height)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: return ImagePayload(bytes, originalMimeType)
        val scaled = if (decoded.width == size.width && decoded.height == size.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, size.width, size.height, true)
        }
        return java.io.ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, imageCompressionPolicy.jpegQuality, output)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            ImagePayload(output.toByteArray(), "image/jpeg")
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / inSampleSize >= reqWidth && halfHeight / inSampleSize >= reqHeight) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    }
}

internal data class EffectiveAgentSearchContexts(
    val webSearchContext: WebSearchContext?,
    val nativeWebSearchMode: NativeWebSearchMode?,
)

internal fun effectiveAgentSearchContexts(
    agentContext: AgentRuntimeContext?,
    webSearchContext: WebSearchContext?,
    nativeWebSearchMode: NativeWebSearchMode?,
): EffectiveAgentSearchContexts = if (agentContext == null) {
    EffectiveAgentSearchContexts(webSearchContext, nativeWebSearchMode)
} else {
    EffectiveAgentSearchContexts(null, null)
}

internal fun webSearchAllowedForAgentConversation(agentId: String?): Boolean = agentId.isNullOrBlank()

internal fun sanitizeAgentSnapshotIfNeeded(
    snapshot: StreamingMessageSnapshot,
    agentContext: AgentRuntimeContext?,
): StreamingMessageSnapshot = if (agentContext == null) snapshot else sanitizeAgentCitationMarkers(snapshot)

internal fun appendVisibleTextPart(
    snapshot: StreamingMessageSnapshot,
    text: String,
): StreamingMessageSnapshot {
    if (text.isBlank()) return snapshot
    return snapshot.copy(
        parts = snapshot.parts + UiMessagePartDraft(
            index = snapshot.parts.size,
            type = UiMessagePartType.TEXT,
            content = text,
            metadata = emptyMap(),
            stable = snapshot.status != MessageStatus.PENDING && snapshot.status != MessageStatus.STREAMING,
        ),
    )
}

internal fun appendAgentSourcesPart(
    snapshot: StreamingMessageSnapshot,
    evidence: List<AgentEvidence>,
): StreamingMessageSnapshot {
    if (snapshot.legacyVisibleText().isBlank()) return snapshot
    val sources = evidence
        .map { evidence -> "${evidence.sourceTitle} · ${evidence.location}" }
        .distinct()
    if (sources.isEmpty()) return snapshot
    val chunkKeys = evidence.map(AgentEvidence::chunkKey).filter(String::isNotBlank).distinct().sorted()
    return snapshot.copy(
        parts = snapshot.parts + UiMessagePartDraft(
            index = snapshot.parts.size,
            type = UiMessagePartType.AGENT_SOURCES,
            content = sources.mapIndexed { index, source -> "资料 ${index + 1} · $source" }
                .joinToString(separator = "\n"),
            metadata = mapOf(
                "chunkKeys" to JsonArray(chunkKeys.map(::JsonPrimitive)).toString(),
            ),
            stable = true,
        ),
    )
}

internal fun cancelStreamingSnapshot(
    accumulator: StreamingMessageAccumulator?,
    nowMillis: Long,
): StreamingMessageSnapshot? =
    accumulator?.cancel(nowMillis)?.snapshot

private fun StreamingMessageSnapshot.toCancelledSnapshot(): StreamingMessageSnapshot = copy(
    status = MessageStatus.CANCELLED,
    parts = parts.map { part -> part.copy(stable = true) },
)

data class ChatRuntimeDiagnostics(
    val traceId: String,
    val startedAtMillis: Long,
    val failedAtMillis: Long,
    val flushCount: Int,
    val receivedChars: Int,
) {
    val elapsedMillis: Long = (failedAtMillis - startedAtMillis).coerceAtLeast(0L)
}

internal fun buildChatErrorLog(
    provider: ProviderProfile,
    requestModel: String,
    conversationId: String,
    error: Throwable,
    nowMillis: Long,
    sensitiveTerms: List<String> = emptyList(),
    requestDiagnostics: ModelAwareRequestDiagnostics? = null,
    runtimeDiagnostics: ChatRuntimeDiagnostics? = null,
): String {
    val userMessage = error.toUserMessage().asLlmFailureMessage()
    val details = (
        listOf(
            userMessage,
            "--- 诊断日志 ---",
            "Time: $nowMillis",
            "Provider: ${provider.name}",
            "Provider ID: ${provider.id}",
            "Base URL: ${provider.baseUrl}",
            "Model: $requestModel",
            "Conversation: $conversationId",
            "Exception: ${error::class.java.name}",
            "Message: ${error.message.orEmpty().ifBlank { "(empty)" }}",
        ) + runtimeDiagnostics.toLogLinesOrEmpty() + requestDiagnostics.toLogLinesOrEmpty()
    ).joinToString("\n")

    return details.hideSensitiveTerms(sensitiveTerms)
}

private fun ChatRuntimeDiagnostics?.toLogLinesOrEmpty(): List<String> =
    this?.let {
        listOf(
            "Trace ID: ${it.traceId}",
            "Started At: ${it.startedAtMillis}",
            "Failed At: ${it.failedAtMillis}",
            "Elapsed Ms: ${it.elapsedMillis}",
            "Flush Count: ${it.flushCount}",
            "Received Chars: ${it.receivedChars}",
        )
    }.orEmpty()

private fun ModelAwareRequestDiagnostics?.toLogLinesOrEmpty(): List<String> =
    this?.toLogLines().orEmpty()

private fun StreamEvent.visiblePayloadLength(): Int = when (this) {
    is StreamEvent.TextDelta -> text.length
    is StreamEvent.ImageDelta -> source.length
    is StreamEvent.ReasoningDelta -> text.length
    is StreamEvent.ToolCallDelta -> argumentsDelta.length
    is StreamEvent.ToolResult -> content.length
    is StreamEvent.SearchResult -> snippet.length
    is StreamEvent.Finished,
    is StreamEvent.RawProviderEvent,
    is StreamEvent.Usage -> 0
}

private fun com.harnessapk.provider.ResolvedModelCapability.supportsImageInput(): Boolean =
    inputModalities.any { it.equals("image", ignoreCase = true) }

private fun com.harnessapk.provider.ResolvedModelCapability.toModelConfig(): ModelConfig =
    ModelConfig(
        id = modelId,
        contextWindowTokens = contextWindowTokens,
        compressionThresholdPercent = compressionThresholdPercent,
    )

private fun String.hideSensitiveTerms(sensitiveTerms: List<String>): String {
    return sensitiveTerms
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .fold(this) { current, term -> current.replace(term, "[已隐藏]") }
}

private fun String.asLlmFailureMessage(): String {
    return if (startsWith("LLM ") || startsWith("当前供应商") || startsWith("图片")) {
        this
    } else {
        "LLM 请求失败：$this"
    }
}

private const val MAX_STREAM_TRANSPORT_RETRIES = 1
private const val STREAM_TRANSPORT_RETRY_DELAY_MILLIS = 1_000L

internal fun shouldRetryStreamAfterTransportFailure(error: Throwable, retriesUsed: Int): Boolean =
    retriesUsed < MAX_STREAM_TRANSPORT_RETRIES &&
        generateSequence(error) { it.cause }.any { cause -> cause is IOException }

private data class ImagePayload(
    val bytes: ByteArray,
    val mimeType: String,
)

data class ChatExecutionResult(
    val status: ChatExecutionStatus,
    val assistantMessageId: String?,
    val errorMessage: String?,
)
