package com.harnessapk.chat

import android.content.ContextWrapper
import com.harnessapk.common.AppDispatchers
import com.harnessapk.common.TimeProvider
import com.harnessapk.network.ChatDelta
import com.harnessapk.network.ChatRequest
import com.harnessapk.network.ChatStreamClient
import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.provider.ProviderApiProtocol
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.remote.ImageGenerateParams
import com.harnessapk.remote.ImageGenerateResult
import com.harnessapk.security.EncryptedValue
import com.harnessapk.security.StringCipher
import com.harnessapk.storage.ConversationDao
import com.harnessapk.storage.ConversationEntity
import com.harnessapk.storage.ConversationMemoryDao
import com.harnessapk.storage.ConversationMemoryEntity
import com.harnessapk.storage.LifeConversationRoomRow
import com.harnessapk.storage.MessageAttachmentDao
import com.harnessapk.storage.MessageAttachmentEntity
import com.harnessapk.storage.MessageDao
import com.harnessapk.storage.MessageEntity
import com.harnessapk.storage.MessagePartDao
import com.harnessapk.storage.MessagePartEntity
import com.harnessapk.storage.ProviderProfileDao
import com.harnessapk.storage.ProviderProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地聊天的 image_generate function calling 循环：
 * 声明 → 流式累积 → 执行 → 图片消息落库 → 第二轮请求携带 tool 回执 → 最终回答落库。
 */
class SendMessageUseCaseImageToolTest {
    @Test
    fun `执行生图工具后插入图片消息并在第二轮拿到最终回答`() = runTest {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "画一只戴宇航头盔的橘猫", emptyList())
        val tool = ImageToolGenerateTool(result = generatedResult())
        val writer = ImageToolGeneratedFileWriter()
        val client = ImageToolChatStreamClient(
            listOf(
                listOf(
                    StreamEvent.ToolCallDelta("call_a", IMAGE_GENERATE_TOOL_NAME, "{\"prompt\":\"一只戴宇航", index = 0),
                    StreamEvent.ToolCallDelta("", null, "头盔的橘猫\",\"size\":\"1024x1024\"}", index = 0),
                    StreamEvent.Finished("tool_calls"),
                ),
                listOf(
                    StreamEvent.TextDelta("图片已生成，请查收"),
                    StreamEvent.Finished("stop"),
                ),
            ),
        )
        val useCase = useCase(repository, client, tool, writer)

        val result = useCase.execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))

        assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
        assertEquals(2, client.requests.size)
        assertEquals(1, tool.requestedParams.size)
        assertEquals("一只戴宇航头盔的橘猫", tool.requestedParams.single().prompt)
        assertEquals(listOf(result.assistantMessageId), repository.listMessages(conversationId).filter {
            it.role == MessageRole.ASSISTANT && it.content == "图片已生成，请查收"
        }.map { it.id })

        // 第一轮声明工具；第二轮仍声明（未超过 2 轮上限）。
        assertEquals(listOf(IMAGE_GENERATE_TOOL_NAME), client.requests[0].tools.map { it.name })
        assertEquals("auto", client.requests[0].toolChoice)
        assertEquals(listOf(IMAGE_GENERATE_TOOL_NAME), client.requests[1].tools.map { it.name })
        // 系统提示只在工具可用时追加能力说明。
        assertTrue(client.requests[0].messages.first { it.role == "system" }.text.contains(IMAGE_GENERATE_TOOL_SYSTEM_PROMPT))

        // 第二轮请求：assistant.tool_calls 原样回传 + tool 回执。
        val secondMessages = client.requests[1].messages
        val assistantToolCall = secondMessages.first { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        assertEquals("call_a", assistantToolCall.toolCalls.single().id)
        assertEquals(IMAGE_GENERATE_TOOL_NAME, assistantToolCall.toolCalls.single().name)
        assertEquals(
            "{\"prompt\":\"一只戴宇航头盔的橘猫\",\"size\":\"1024x1024\"}",
            assistantToolCall.toolCalls.single().arguments,
        )
        val toolReceipt = secondMessages.first { it.role == "tool" }
        assertEquals("call_a", toolReceipt.toolCallId)
        assertTrue(toolReceipt.text.contains("图片已生成并展示给用户"))
        assertTrue(toolReceipt.text.contains("1024×1024"))

        // 图片落盘并作为独立 ASSISTANT 消息 + 图片附件插入会话。
        assertEquals(1, writer.writes.size)
        assertEquals(generatedResult().data.size to "image/png", writer.writes.single())
        val messages = repository.listMessages(conversationId)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.ASSISTANT), messages.map { it.role })
        val imageMessage = messages.last()
        assertTrue(imageMessage.content.startsWith("[已生成图片："))
        assertTrue(imageMessage.content.contains("1024×1024"))
        assertEquals(MessageStatus.SUCCEEDED, imageMessage.status)
        val attachments = repository.listAttachments(imageMessage.id)
        assertEquals(1, attachments.size)
        assertEquals("image", attachments.single().type)
        assertEquals("file:///tmp/generated-image.png", attachments.single().uri)
        assertEquals("image/png", attachments.single().mimeType)
        assertEquals(
            listOf(UiMessagePartType.TEXT, UiMessagePartType.TOOL_CALL, UiMessagePartType.TOOL_RESULT),
            repository.listMessageParts(imageMessage.id).map { it.type },
        )
        val toolCallPart = repository.listMessageParts(imageMessage.id)[1]
        assertEquals("call_a", toolCallPart.metadata["id"])
        assertEquals("image_generate", toolCallPart.metadata["name"])
    }

    @Test
    fun `工具不可用时不声明 tools 也不在系统提示承诺生图`() = runTest {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "你好", emptyList())
        val tool = ImageToolGenerateTool(result = generatedResult()).apply { available = false }
        val writer = ImageToolGeneratedFileWriter()
        val client = ImageToolChatStreamClient(listOf(listOf(StreamEvent.TextDelta("你好呀"), StreamEvent.Finished("stop"))))
        val useCase = useCase(repository, client, tool, writer)

        val result = useCase.execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))

        assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
        assertTrue(client.requests.single().tools.isEmpty())
        assertNull(client.requests.single().toolChoice)
        assertFalse(client.requests.single().messages.any { it.text.contains(IMAGE_GENERATE_TOOL_NAME) })
        assertTrue(writer.writes.isEmpty())
        assertEquals(2, repository.listMessages(conversationId).size)
    }

    @Test
    fun `Anthropic 协议不声明 function tools 保持现状`() = runTest {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "画张图", emptyList())
        val client = ImageToolChatStreamClient(listOf(listOf(StreamEvent.TextDelta("暂不支持"), StreamEvent.Finished("stop"))))
        val useCase = useCase(
            repository = repository,
            client = client,
            tool = ImageToolGenerateTool(result = generatedResult()),
            writer = ImageToolGeneratedFileWriter(),
            apiProtocol = ProviderApiProtocol.ANTHROPIC_MESSAGES,
        )

        useCase.execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))

        assertTrue(client.requests.single().tools.isEmpty())
        assertTrue(client.requests.single().toolChoice == null)
        assertFalse(client.requests.single().messages.any { it.text.contains(IMAGE_GENERATE_TOOL_NAME) })
    }

    @Test
    fun `生图失败只作为工具回执 会话仍然成功`() = runTest {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "画张图", emptyList())
        val tool = ImageToolGenerateTool(result = generatedResult()).apply {
            failure = IllegalStateException("生图等待超时（240 秒），图片可能仍在 Mac 上生成")
        }
        val writer = ImageToolGeneratedFileWriter()
        val client = ImageToolChatStreamClient(
            listOf(
                listOf(
                    StreamEvent.ToolCallDelta("call_fail", IMAGE_GENERATE_TOOL_NAME, "{\"prompt\":\"猫\"}", index = 0),
                    StreamEvent.Finished("tool_calls"),
                ),
                listOf(StreamEvent.TextDelta("抱歉，图片生成失败了，请稍后再试"), StreamEvent.Finished("stop")),
            ),
        )
        val useCase = useCase(repository, client, tool, writer)

        val result = useCase.execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))

        assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
        assertTrue(writer.writes.isEmpty())
        val toolReceipt = client.requests[1].messages.first { it.role == "tool" }
        assertTrue(toolReceipt.text.contains("image_generate 执行失败"))
        assertTrue(toolReceipt.text.contains("生图等待超时"))
        // 没有图片消息，只有一轮最终回答。
        assertTrue(repository.listMessages(conversationId).none { it.content.startsWith("[已生成图片：") })
        assertEquals("抱歉，图片生成失败了，请稍后再试", repository.listMessages(conversationId).last().content)
    }

    @Test
    fun `工具在声明后离线 回执让模型用文字解释且会话成功`() = runTest {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "画张图", emptyList())
        val client = ImageToolChatStreamClient(
            listOf(
                listOf(
                    StreamEvent.ToolCallDelta("call_offline", IMAGE_GENERATE_TOOL_NAME, "{\"prompt\":\"猫\"}", index = 0),
                    StreamEvent.Finished("tool_calls"),
                ),
                listOf(StreamEvent.TextDelta("Mac 离线了，先文字回复你"), StreamEvent.Finished("stop")),
            ),
        )
        val useCase = useCase(
            repository = repository,
            client = client,
            tool = ImageToolOfflineAtExecutionTool(),
            writer = ImageToolGeneratedFileWriter(),
        )

        val result = useCase.execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))

        assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
        val receipt = client.requests[1].messages.first { it.role == "tool" }
        assertTrue(receipt.text.contains("当前不可用"))
        assertEquals("Mac 离线了，先文字回复你", repository.listMessages(conversationId).last().content)
    }

    @Test
    fun `参数非法时作为工具错误回执且不调用生图`() = runTest {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "画张图", emptyList())
        val tool = ImageToolGenerateTool(result = generatedResult())
        val client = ImageToolChatStreamClient(
            listOf(
                listOf(
                    StreamEvent.ToolCallDelta("call_bad", IMAGE_GENERATE_TOOL_NAME, "{\"prompt\":\"  \"}", index = 0),
                    StreamEvent.Finished("tool_calls"),
                ),
                listOf(StreamEvent.TextDelta("请补充图片描述"), StreamEvent.Finished("stop")),
            ),
        )
        val useCase = useCase(repository, client, tool, ImageToolGeneratedFileWriter())

        val result = useCase.execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))

        assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
        assertTrue(tool.requestedParams.isEmpty())
        val receipt = client.requests[1].messages.first { it.role == "tool" }
        assertTrue(receipt.text.contains("image_generate 参数错误"))
        assertTrue(receipt.text.contains("prompt"))
    }

    @Test
    fun `未知工具名记日志并回执失败`() = runTest {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "帮我搜一下", emptyList())
        val logs = mutableListOf<String>()
        val client = ImageToolChatStreamClient(
            listOf(
                listOf(
                    StreamEvent.ToolCallDelta("call_x", "web_search", "{}", index = 0),
                    StreamEvent.Finished("tool_calls"),
                ),
                listOf(StreamEvent.TextDelta("我只能用文字回答"), StreamEvent.Finished("stop")),
            ),
        )
        val useCase = useCase(
            repository = repository,
            client = client,
            tool = ImageToolGenerateTool(result = generatedResult()),
            writer = ImageToolGeneratedFileWriter(),
            toolCallLogger = { logs += it },
        )

        val result = useCase.execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))

        assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
        assertTrue(logs.any { it.contains("未知工具调用") && it.contains("web_search") })
        val receipt = client.requests[1].messages.first { it.role == "tool" }
        assertTrue(receipt.text.contains("不存在"))
        assertTrue(receipt.text.contains(IMAGE_GENERATE_TOOL_NAME))
    }

    @Test
    fun `最多执行两轮工具调用 超过后不再声明工具`() = runTest {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "连续画图", emptyList())
        fun toolRound(id: String) = listOf(
            StreamEvent.ToolCallDelta(id, IMAGE_GENERATE_TOOL_NAME, "{\"prompt\":\"猫\"}", index = 0),
            StreamEvent.Finished("tool_calls"),
        )
        val tool = ImageToolGenerateTool(result = generatedResult())
        val client = ImageToolChatStreamClient(listOf(toolRound("call_1"), toolRound("call_2"), toolRound("call_3")))
        val useCase = useCase(repository, client, tool, ImageToolGeneratedFileWriter())

        val result = useCase.execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))

        assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
        assertEquals(3, client.requests.size)
        assertEquals(2, tool.requestedParams.size)
        // 第三轮不再声明工具：模型仍返回 tool_calls 时直接截断，不再执行也不发第四轮请求。
        assertTrue(client.requests[2].tools.isEmpty())
        assertEquals(
            2,
            repository.listMessages(conversationId).count { it.content.startsWith("[已生成图片：") },
        )
    }

    @Test
    fun `后续轮次历史把图片消息投影成纯文本`() = runTest {
        val repository = imageToolRepository()
        val conversationId = repository.createConversation()
        val userMessageId = repository.insertUserMessage(conversationId, "画一只橘猫", emptyList())
        val client = ImageToolChatStreamClient(
            listOf(
                listOf(
                    StreamEvent.ToolCallDelta("call_a", IMAGE_GENERATE_TOOL_NAME, "{\"prompt\":\"橘猫\"}", index = 0),
                    StreamEvent.Finished("tool_calls"),
                ),
                listOf(StreamEvent.TextDelta("画好了"), StreamEvent.Finished("stop")),
                listOf(StreamEvent.TextDelta("继续聊"), StreamEvent.Finished("stop")),
            ),
        )
        val useCase = useCase(repository, client, ImageToolGenerateTool(result = generatedResult()), ImageToolGeneratedFileWriter())
        useCase.execute(entry(conversationId, userMessageId), repository.listMessages(conversationId))

        val secondTurn = useCase.send(conversationId = conversationId, text = "再聊两句", attachments = emptyList())

        assertEquals(ChatExecutionStatus.SUCCEEDED, secondTurn.status)
        val historyMessages = client.requests.last().messages
        val imageHistory = historyMessages.filter { it.role == "assistant" && it.text.startsWith("[已生成图片：") }
        assertEquals(1, imageHistory.size)
        assertTrue(imageHistory.single().imageDataUrls.isEmpty())
        assertFalse(historyMessages.any { message -> message.imageDataUrls.any { it.startsWith("data:image/") } })
    }

    private fun useCase(
        repository: ChatRepository,
        client: ChatStreamClient,
        tool: ImageGenerateTool,
        writer: GeneratedImageFileWriter,
        apiProtocol: ProviderApiProtocol = ProviderApiProtocol.OPENAI_COMPATIBLE,
        toolCallLogger: (String) -> Unit = {},
    ): SendMessageUseCase = SendMessageUseCase(
        context = ContextWrapper(null),
        chatRepository = repository,
        providerRepository = imageToolProviderRepository(apiProtocol),
        client = client,
        dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
        imageGenerateTool = tool,
        generatedImageFileWriter = writer,
        toolCallLogger = toolCallLogger,
    )

    private fun entry(conversationId: String, userMessageId: String): ChatExecutionEntry = ChatExecutionEntry(
        id = "entry-image-tool",
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

    private fun generatedResult(): ImageGenerateResult = ImageGenerateResult(
        requestId = "image.generate:req-1",
        id = "img-1",
        prompt = "一只戴宇航头盔的橘猫",
        model = "gpt-image-2.5-flare",
        size = "1024x1024",
        quality = "medium",
        mimeType = "image/png",
        data = ByteArray(32) { it.toByte() },
        width = 1024,
        height = 1024,
        durationMs = 8_123L,
        costEstimateUsd = 0.053,
        savedPath = null,
        completedAtMillis = 2L,
    )
}

private class ImageToolChatStreamClient(
    private val rounds: List<List<StreamEvent>>,
) : ChatStreamClient {
    val requests = mutableListOf<ChatRequest>()

    override fun streamChat(request: ChatRequest): Flow<ChatDelta> = flow {
        streamChatEvents(request).collect { event ->
            if (event is StreamEvent.TextDelta) emit(ChatDelta(event.text))
        }
    }

    override fun streamChatEvents(request: ChatRequest): Flow<StreamEvent> = flow {
        requests += request
        rounds.getOrElse(requests.lastIndex) { emptyList() }.forEach { emit(it) }
    }
}

private class ImageToolGenerateTool(
    var result: ImageGenerateResult? = null,
) : ImageGenerateTool {
    var available: Boolean = true
    var failure: Throwable? = null
    val requestedParams = mutableListOf<ImageGenerateParams>()

    override fun isAvailable(): Boolean = available

    override suspend fun generate(params: ImageGenerateParams): ImageGenerateResult {
        requestedParams += params
        failure?.let { throw it }
        return requireNotNull(result) { "fake 生图结果未配置" }
    }
}

/** 声明阶段在线、真正执行时已离线的工具。 */
private class ImageToolOfflineAtExecutionTool : ImageGenerateTool {
    private var checks = 0

    override fun isAvailable(): Boolean = checks++ == 0

    override suspend fun generate(params: ImageGenerateParams): ImageGenerateResult =
        error("离线时不应调用生图")
}

private class ImageToolGeneratedFileWriter : GeneratedImageFileWriter {
    val writes = mutableListOf<Pair<Int, String>>()

    override suspend fun write(bytes: ByteArray, mimeType: String): GeneratedImageFile {
        writes += bytes.size to mimeType
        return GeneratedImageFile(uri = "file:///tmp/generated-image.png", mimeType = mimeType)
    }
}

internal fun imageToolProviderRepository(apiProtocol: ProviderApiProtocol): ProviderRepository = ProviderRepository(
    dao = ImageToolProviderProfileDao().apply {
        row = ProviderProfileEntity(
            id = "provider-1",
            name = "Test",
            baseUrl = "https://example.com/v1",
            apiKeyAlias = "provider:provider-1",
            encryptedApiKey = "secret".encodeToByteArray(),
            apiKeyIv = "iv".encodeToByteArray(),
            defaultModel = "test-model",
            availableModels = "test-model",
            defaultVisionModel = null,
            supportsVision = false,
            nativeWebSearchMode = "NONE",
            apiProtocol = apiProtocol.name,
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
    },
    cipher = object : StringCipher {
        override fun encrypt(plainText: String) = EncryptedValue(plainText.encodeToByteArray(), byteArrayOf())
        override fun decrypt(value: EncryptedValue) = value.cipherText.decodeToString()
    },
    timeProvider = TimeProvider { 1L },
)

internal fun imageToolRepository(): ChatRepository = ChatRepository(
    conversationDao = ImageToolConversationDao(),
    messageDao = ImageToolMessageDao(),
    messagePartDao = ImageToolMessagePartDao(),
    attachmentDao = ImageToolMessageAttachmentDao(),
    memoryDao = ImageToolConversationMemoryDao(),
    timeProvider = TimeProvider { 1L },
)

private class ImageToolConversationDao : ConversationDao {
    private val rows = linkedMapOf<String, ConversationEntity>()
    override fun observeActive(): Flow<List<ConversationEntity>> = MutableStateFlow(rows.values.toList())
    override fun observeLifeOverviewRows(includeArchived: Boolean): Flow<List<LifeConversationRoomRow>> =
        MutableStateFlow(emptyList())
    override suspend fun findById(id: String) = rows[id]
    override suspend fun findLatestActive() = rows.values.filter { !it.isArchived }
        .maxWithOrNull(compareBy<ConversationEntity> { it.updatedAt }.thenBy { it.id })
    override suspend fun findLatestActiveInProject(projectId: String) = rows.values
        .filter { !it.isArchived && it.projectId == projectId }
        .maxWithOrNull(compareBy<ConversationEntity> { it.updatedAt }.thenBy { it.id })
    override suspend fun insert(entity: ConversationEntity) {
        rows[entity.id] = entity
    }
    override suspend fun update(entity: ConversationEntity) {
        rows[entity.id] = entity
    }
    override suspend fun updateIdentityIfNoUserMessages(
        id: String,
        agentId: String?,
        agentVersion: Int?,
        updatedAt: Long,
    ): Int = 0
    override suspend fun clearProject(projectId: String) = Unit
    override suspend fun archive(id: String, updatedAt: Long) = Unit
    override suspend fun archiveLifeConversationIfIdle(id: String): Int = 0
    override suspend fun restoreLifeConversation(id: String): Int = 0
    override suspend fun countByAgentVersion(agentId: String, version: Int) = 0
    override suspend fun clearAgentReference(agentId: String, version: Int, updatedAt: Long): Int = 0
}

private class ImageToolMessageDao : MessageDao {
    private val rows = linkedMapOf<String, MessageEntity>()
    override fun observeForConversation(conversationId: String): Flow<List<MessageEntity>> =
        MutableStateFlow(rows.values.filter { it.conversationId == conversationId })
    override suspend fun listForConversation(conversationId: String) =
        rows.values.filter { it.conversationId == conversationId }
    override suspend fun listRecentSuccessfulText(conversationId: String, limit: Int) = rows.values
        .filter {
            it.conversationId == conversationId &&
                it.status == "SUCCEEDED" &&
                it.role in setOf("USER", "ASSISTANT") &&
                it.content.isNotBlank()
        }
        .take(limit)
    override suspend fun findLastSuccessfulAssistant(conversationId: String) = rows.values
        .lastOrNull { it.conversationId == conversationId && it.role == "ASSISTANT" && it.status == "SUCCEEDED" }
    override suspend fun countSuccessfulAssistantText(conversationId: String) = rows.values.count {
        it.conversationId == conversationId && it.role == "ASSISTANT" && it.content.isNotBlank()
    }
    override suspend fun findById(id: String) = rows[id]
    override suspend fun countUserMessages(conversationId: String) =
        rows.values.count { it.conversationId == conversationId && it.role == "USER" }
    override suspend fun insert(entity: MessageEntity) {
        rows[entity.id] = entity
    }
    override suspend fun update(entity: MessageEntity) {
        rows[entity.id] = entity
    }
    override suspend fun deleteById(id: String) {
        rows.remove(id)
    }
    override suspend fun deleteForConversation(conversationId: String) = Unit
}

private class ImageToolMessagePartDao : MessagePartDao {
    private val rows = linkedMapOf<String, MessagePartEntity>()
    override fun observeForMessage(messageId: String): Flow<List<MessagePartEntity>> =
        MutableStateFlow(rows.values.filter { it.messageId == messageId }.sortedBy { it.partIndex })
    override fun observeForConversation(conversationId: String): Flow<List<MessagePartEntity>> =
        MutableStateFlow(rows.values.toList())
    override suspend fun listForMessage(messageId: String) =
        rows.values.filter { it.messageId == messageId }.sortedBy { it.partIndex }
    override suspend fun insertAll(parts: List<MessagePartEntity>) {
        parts.forEach { rows[it.id] = it }
    }
    override suspend fun deleteForMessage(messageId: String) {
        rows.entries.removeIf { it.value.messageId == messageId }
    }
    override suspend fun markStableForMessage(messageId: String, updatedAt: Long) {
        rows.replaceAll { _, value ->
            if (value.messageId == messageId) value.copy(stable = true, updatedAt = updatedAt) else value
        }
    }
    override suspend fun replaceForMessage(messageId: String, parts: List<MessagePartEntity>) {
        deleteForMessage(messageId)
        insertAll(parts)
    }
}

private class ImageToolMessageAttachmentDao : MessageAttachmentDao {
    private val rows = linkedMapOf<String, MessageAttachmentEntity>()
    override fun observeForMessage(messageId: String): Flow<List<MessageAttachmentEntity>> =
        MutableStateFlow(rows.values.filter { it.messageId == messageId })
    override fun observeForConversation(conversationId: String): Flow<List<MessageAttachmentEntity>> =
        MutableStateFlow(rows.values.toList())
    override suspend fun listForMessage(messageId: String): List<MessageAttachmentEntity> =
        rows.values.filter { it.messageId == messageId }
    override suspend fun insert(entity: MessageAttachmentEntity) {
        rows[entity.id] = entity
    }
}

private class ImageToolConversationMemoryDao : ConversationMemoryDao {
    private var memory: ConversationMemoryEntity? = null
    override suspend fun findByConversationId(conversationId: String): ConversationMemoryEntity? =
        memory?.takeIf { it.conversationId == conversationId }
    override fun observeForConversation(conversationId: String): Flow<ConversationMemoryEntity?> =
        MutableStateFlow(memory?.takeIf { it.conversationId == conversationId })
    override suspend fun upsert(entity: ConversationMemoryEntity) {
        memory = entity
    }
}

private class ImageToolProviderProfileDao : ProviderProfileDao {
    var row: ProviderProfileEntity? = null
    override fun observeEnabled(): Flow<List<ProviderProfileEntity>> = MutableStateFlow(listOfNotNull(row))
    override suspend fun getAll(): List<ProviderProfileEntity> = listOfNotNull(row)
    override suspend fun findById(id: String) = row?.takeIf { it.id == id }
    override suspend fun firstEnabled() = row?.takeIf { it.enabled }
    override suspend fun insert(entity: ProviderProfileEntity) {
        row = entity
    }
    override suspend fun update(entity: ProviderProfileEntity) {
        row = entity
    }
    override suspend fun delete(entity: ProviderProfileEntity) {
        if (row?.id == entity.id) row = null
    }
}
