package com.harnessapk.chat

import android.content.ContextWrapper
import com.harnessapk.common.AppDispatchers
import com.harnessapk.network.ChatDelta
import com.harnessapk.network.ChatRequest
import com.harnessapk.network.ChatStreamClient
import com.harnessapk.provider.ProviderApiProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卡流看门狗：开始输出后长时间无新事件要尽快断开，并标记为可自动重试的网络中断。 */
class SendMessageUseCaseStreamStallTest {
    @Test
    fun `输出开始后静默超时判定卡流并可自动重试`() = runBlocking {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "你好", emptyList())
        val client = StallingClient { emit(StreamEvent.TextDelta("第一段")); awaitCancellation() }
        val timingLines = mutableListOf<String>()

        val result = withTimeout(5_000) {
            useCase(repository, client, idleTimeoutMillis = 200, timingLines = timingLines)
                .execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))
        }

        assertEquals(ChatExecutionStatus.FAILED, result.status)
        assertTrue(result.retryableInterruption)
        assertTrue(result.errorMessage.orEmpty().contains("卡住"))
        assertTrue(timingLines.single().contains("firstOutput="))
        assertTrue(timingLines.single().contains("outcome=failed"))
    }

    @Test
    fun `首字之前的长时间思考不触发看门狗`() = runBlocking {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "你好", emptyList())
        val client = StallingClient {
            delay(600)
            emit(StreamEvent.TextDelta("想好了"))
            emit(StreamEvent.Finished("stop"))
        }
        val timingLines = mutableListOf<String>()

        val result = useCase(repository, client, idleTimeoutMillis = 200, timingLines = timingLines)
            .execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))

        assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
        val line = timingLines.single()
        assertTrue(line, line.contains("outcome=succeeded"))
        assertTrue(line, Regex("firstOutput=(\\d+)ms").find(line)!!.groupValues[1].toLong() >= 500)
    }

    private fun useCase(
        repository: ChatRepository,
        client: ChatStreamClient,
        idleTimeoutMillis: Long,
        timingLines: MutableList<String>,
    ) = SendMessageUseCase(
        context = ContextWrapper(null),
        chatRepository = repository,
        providerRepository = imageToolProviderRepository(ProviderApiProtocol.OPENAI_COMPATIBLE),
        client = client,
        dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
        streamIdleTimeoutMillis = idleTimeoutMillis,
        timingLogger = { timingLines += it },
    )

    private fun entry(conversationId: String, userMessageId: String) = ChatExecutionEntry(
        id = "entry-stall",
        conversationId = conversationId,
        userMessageId = userMessageId,
        assistantMessageId = null,
        targetAssistantMessageId = null,
        sequence = 1L,
        type = ChatExecutionType.NORMAL,
        status = ChatExecutionStatus.RUNNING,
        providerId = null,
        model = null,
        reasoningEffort = defaultReasoningEffort(),
        requestContext = ChatExecutionRequestContext(),
        errorMessage = null,
        createdAt = 1L,
        updatedAt = 1L,
    )
}

private class StallingClient(
    private val body: suspend kotlinx.coroutines.flow.FlowCollector<StreamEvent>.() -> Unit,
) : ChatStreamClient {
    override fun streamChat(request: ChatRequest): Flow<ChatDelta> = emptyFlow()
    override fun streamChatEvents(request: ChatRequest): Flow<StreamEvent> = flow { body() }
}
