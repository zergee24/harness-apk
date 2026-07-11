package com.harnessapk.chat

import com.harnessapk.common.AppError
import com.harnessapk.network.ChatRequest
import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.provider.CapabilitySource
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderProfile
import com.harnessapk.provider.ResolvedModelCapability

class ModelAwareRequestBuilder {
    fun build(
        provider: ProviderProfile,
        apiKey: String,
        capability: ResolvedModelCapability,
        messages: List<OutgoingChatMessage>,
        temperature: Double,
        selectedReasoningEffort: ReasoningEffort,
        webSearchRequested: Boolean,
    ): ModelAwareChatRequest {
        val droppedOptions = mutableListOf<String>()
        if (messages.any { it.imageDataUrls.isNotEmpty() } && !capability.supportsInput("image")) {
            throw AppError.VisionUnsupported()
        }

        val reasoningEffort = selectedReasoningEffort.wireValue.takeIf {
            capability.reasoningEffortOptions.any { option -> option.equals(it, ignoreCase = true) }
        } ?: run {
            if (selectedReasoningEffort != ReasoningEffort.HIGH || capability.reasoningEffortOptions.isEmpty()) {
                droppedOptions += "reasoning_effort:${selectedReasoningEffort.wireValue}"
            }
            null
        }

        val nativeWebSearchMode = when {
            !webSearchRequested -> null
            capability.webSearchMode == NativeWebSearchMode.DISABLED -> {
                droppedOptions += "web_search"
                null
            }
            capability.webSearchMode == NativeWebSearchMode.EXTERNAL_BING -> null
            else -> capability.webSearchMode
        }

        return ModelAwareChatRequest(
            request = ChatRequest(
                baseUrl = provider.baseUrl,
                apiKey = apiKey,
                model = capability.modelId,
                messages = messages.withDefaultReasoningLanguage(),
                temperature = temperature,
                reasoningEffort = reasoningEffort,
                nativeWebSearchMode = nativeWebSearchMode,
                readTimeoutMillis = capability.readTimeoutMillis,
                customHeaders = provider.customHeaders,
                customBodyJson = provider.customBodyJson,
            ),
            diagnostics = ModelAwareRequestDiagnostics(
                capabilitySource = capability.source,
                catalogVersion = capability.catalogVersion,
                readTimeoutMillis = capability.readTimeoutMillis,
                droppedOptions = droppedOptions,
            ),
        )
    }
}

data class ModelAwareChatRequest(
    val request: ChatRequest,
    val diagnostics: ModelAwareRequestDiagnostics,
)

data class ModelAwareRequestDiagnostics(
    val capabilitySource: CapabilitySource,
    val catalogVersion: String?,
    val readTimeoutMillis: Long,
    val droppedOptions: List<String> = emptyList(),
) {
    fun toLogLines(): List<String> = buildList {
        add("Capability Source: $capabilitySource")
        add("Catalog Version: ${catalogVersion ?: "(none)"}")
        add("Read Timeout Ms: $readTimeoutMillis")
        if (droppedOptions.isNotEmpty()) {
            add("Dropped Options: ${droppedOptions.joinToString(", ")}")
        }
    }
}

private fun ResolvedModelCapability.supportsInput(modality: String): Boolean =
    inputModalities.any { it.equals(modality, ignoreCase = true) }

internal const val DEFAULT_REASONING_LANGUAGE_INSTRUCTION =
    "思考过程默认使用中文；可见回答的语言遵循用户输入，除非用户另有要求。"

internal fun List<OutgoingChatMessage>.withDefaultReasoningLanguage(): List<OutgoingChatMessage> {
    if (any { it.role.equals("system", ignoreCase = true) && it.text.contains(DEFAULT_REASONING_LANGUAGE_INSTRUCTION) }) {
        return this
    }
    val systemIndex = indexOfFirst { it.role.equals("system", ignoreCase = true) }
    if (systemIndex >= 0) {
        return mapIndexed { index, message ->
            if (index == systemIndex) {
                message.copy(text = message.text.trimEnd() + "\n\n" + DEFAULT_REASONING_LANGUAGE_INSTRUCTION)
            } else {
                message
            }
        }
    }
    return listOf(
        OutgoingChatMessage(
            role = "system",
            text = DEFAULT_REASONING_LANGUAGE_INSTRUCTION,
        ),
    ) + this
}
