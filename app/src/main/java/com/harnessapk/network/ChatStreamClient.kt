package com.harnessapk.network

import com.harnessapk.chat.StreamEvent
import com.harnessapk.provider.ProviderApiProtocol
import kotlinx.coroutines.flow.Flow

/**
 * 聊天流式客户端统一入口。OpenAI 兼容与 Anthropic Messages 两种协议实现同一组
 * 流式接口，上层用例只依赖该接口，按供应商协议路由在容器层完成。
 */
interface ChatStreamClient {
    fun streamChat(request: ChatRequest): Flow<ChatDelta>

    fun streamChatEvents(request: ChatRequest): Flow<StreamEvent>
}

class ProtocolRoutingChatClient(
    private val openAiCompatibleClient: ChatStreamClient,
    private val anthropicMessagesClient: ChatStreamClient,
) : ChatStreamClient {
    override fun streamChat(request: ChatRequest): Flow<ChatDelta> =
        clientFor(request).streamChat(request)

    override fun streamChatEvents(request: ChatRequest): Flow<StreamEvent> =
        clientFor(request).streamChatEvents(request)

    private fun clientFor(request: ChatRequest): ChatStreamClient = when (request.apiProtocol) {
        ProviderApiProtocol.ANTHROPIC_MESSAGES -> anthropicMessagesClient
        ProviderApiProtocol.OPENAI_COMPATIBLE -> openAiCompatibleClient
    }
}
