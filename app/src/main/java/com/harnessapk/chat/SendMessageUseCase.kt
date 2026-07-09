package com.harnessapk.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
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
    ) = withContext(dispatchers.io) {
        val provider = providerId?.let { providerRepository.providerWithKey(it) }
            ?: providerRepository.defaultProviderForText()
        val selectedModel = modelOverride?.trim()?.takeIf { it.isNotBlank() }
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
                conversationId,
                provider.profile.id,
                requestModel,
            )
            chatRepository.markAssistantFailed(assistantId, AppError.VisionUnsupported().toUserMessage())
            return@withContext
        }

        val userMessageId = chatRepository.insertUserMessage(conversationId, text, attachments)
        var assistantId: String? = null
        var requestDiagnostics: ModelAwareRequestDiagnostics? = null

        try {
            val imageDataUrls = attachments.map { it.toDataUrl() }
            val existingMemory = chatRepository.memoryForConversation(conversationId)
            val compressed = contextCompressor.prepare(
                conversationId = conversationId,
                messages = chatRepository.listMessages(conversationId),
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
                        conversationId,
                        contextCompressionEventText(CompressionTrigger.AUTO, memory),
                    )
                }
            }
            val nextAssistantId = chatRepository.insertAssistantPending(
                conversationId,
                provider.profile.id,
                requestModel,
            )
            assistantId = nextAssistantId
            val streamClock = TimeSource.Monotonic.markNow()
            val accumulator = StreamingMessageAccumulator()
            var latestSnapshot = StreamingMessageSnapshot(
                status = MessageStatus.PENDING,
                parts = emptyList(),
            )
            val modelAwareRequest = requestBuilder.build(
                provider = provider.profile,
                apiKey = provider.apiKey,
                capability = resolvedCapability,
                messages = buildSessionOutgoingMessages(sessionContext, compressed.messages, webSearchContext),
                temperature = temperatureForModel(requestModel),
                selectedReasoningEffort = reasoningEffort,
                webSearchRequested = nativeWebSearchMode != null,
            )
            requestDiagnostics = modelAwareRequest.diagnostics

            client.streamChatEvents(modelAwareRequest.request).collect { event ->
                accumulator.onEvent(event, streamClock.elapsedNow().inWholeMilliseconds)?.let { flush ->
                    latestSnapshot = flush.snapshot
                    chatRepository.replaceMessagePartsFromSnapshot(nextAssistantId, latestSnapshot)
                }
            }
            webSearchContext?.toVisibleSourcesMarkdown()?.takeIf { it.isNotBlank() }?.let {
                latestSnapshot = appendVisibleTextPart(latestSnapshot, it)
                chatRepository.replaceMessagePartsFromSnapshot(nextAssistantId, latestSnapshot)
            }
            chatRepository.markAssistantSucceeded(nextAssistantId)
        } catch (cancelled: CancellationException) {
            assistantId?.let { chatRepository.markAssistantCancelled(it) }
            throw cancelled
        } catch (error: Throwable) {
            val failedAssistantId = assistantId ?: chatRepository.insertAssistantPending(
                conversationId,
                provider.profile.id,
                requestModel,
            )
            chatRepository.markAssistantFailed(
                failedAssistantId,
                buildChatErrorLog(
                    provider = provider.profile,
                    requestModel = requestModel,
                    conversationId = conversationId,
                    error = error,
                    nowMillis = System.currentTimeMillis(),
                    sensitiveTerms = listOf(provider.apiKey, text),
                    requestDiagnostics = requestDiagnostics,
                ),
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

internal fun buildChatErrorLog(
    provider: ProviderProfile,
    requestModel: String,
    conversationId: String,
    error: Throwable,
    nowMillis: Long,
    sensitiveTerms: List<String> = emptyList(),
    requestDiagnostics: ModelAwareRequestDiagnostics? = null,
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
        ) + requestDiagnostics.toLogLinesOrEmpty()
    ).joinToString("\n")

    return details.hideSensitiveTerms(sensitiveTerms)
}

private fun ModelAwareRequestDiagnostics?.toLogLinesOrEmpty(): List<String> =
    this?.toLogLines().orEmpty()

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

private data class ImagePayload(
    val bytes: ByteArray,
    val mimeType: String,
)
