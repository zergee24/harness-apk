package com.harnessapk.session

import com.harnessapk.chat.modelForRequest
import com.harnessapk.chat.defaultReasoningEffort
import com.harnessapk.chat.reasoningEffortForRequest
import com.harnessapk.chat.temperatureForModel
import com.harnessapk.common.AppDispatchers
import com.harnessapk.network.ChatRequest
import com.harnessapk.network.OpenAiCompatibleClient
import com.harnessapk.provider.ProviderRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

class PromptOptimizerUseCase(
    private val providerRepository: ProviderRepository,
    private val client: OpenAiCompatibleClient,
    private val dispatchers: AppDispatchers,
) {
    suspend fun optimize(
        rawPrompt: String,
        projectContext: String,
        deliverableMarkdown: String,
        providerId: String?,
        modelOverride: String?,
    ): String = withContext(dispatchers.io) {
        val prompt = rawPrompt.trim()
        require(prompt.isNotBlank()) { "请先输入会话提示词" }
        val provider = providerId?.let { providerRepository.providerWithKey(it) }
            ?: providerRepository.defaultProviderForText()
        val selectedModel = modelOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: provider.profile.defaultModel
        val requestModel = modelForRequest(selectedModel)
        val output = StringBuilder()
        client.streamChat(
            ChatRequest(
                baseUrl = provider.profile.baseUrl,
                apiKey = provider.apiKey,
                model = requestModel,
                messages = buildPromptOptimizationMessages(prompt, projectContext, deliverableMarkdown),
                temperature = temperatureForModel(requestModel),
                reasoningEffort = reasoningEffortForRequest(provider.profile, requestModel, defaultReasoningEffort()),
            ),
        ).collect {
            output.append(it.text)
        }
        output.toString().trim()
    }
}
