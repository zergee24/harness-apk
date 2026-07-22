package com.harnessapk.chat

import android.content.ContextWrapper
import com.harnessapk.agent.AgentEvidence
import com.harnessapk.agent.AgentRepository
import com.harnessapk.agent.AgentRuntimeContext
import com.harnessapk.agent.AgentTransactionRunner
import com.harnessapk.agent.AgentLifecycleCoordinator
import com.harnessapk.agent.FakeAgentDao
import com.harnessapk.common.AppDispatchers
import com.harnessapk.common.TimeProvider
import com.harnessapk.network.OpenAiCompatibleClient
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.security.EncryptedValue
import com.harnessapk.security.StringCipher
import com.harnessapk.storage.ConversationDao
import com.harnessapk.storage.ConversationEntity
import com.harnessapk.storage.ConversationMemoryDao
import com.harnessapk.storage.ConversationMemoryEntity
import com.harnessapk.storage.MessageAttachmentDao
import com.harnessapk.storage.MessageAttachmentEntity
import com.harnessapk.storage.MessageDao
import com.harnessapk.storage.MessageEntity
import com.harnessapk.storage.MessagePartDao
import com.harnessapk.storage.MessagePartEntity
import com.harnessapk.storage.ProviderProfileDao
import com.harnessapk.storage.ProviderProfileEntity
import com.harnessapk.storage.AgentVersionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SendMessageUseCaseAgentPersistenceTest {
    @Test
    fun executeBuildsProviderRequestFromOneStableWikiRuntimeContext() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"有据回答⟦W1⟧\"}}]}\n\ndata: [DONE]"))
        server.start()
        try {
            val store = inMemoryChatStore()
            val conversationId = store.repository.createConversation()
            val userMessageId = store.repository.insertUserMessage(conversationId, "项羽如何失败", emptyList())
            val ref = com.harnessapk.wiki.WikiRef("history.24", 1)
            var contextCalls = 0
            val runtimeContext = com.harnessapk.wiki.WikiRuntimeContext(
                scope = listOf(ref),
                intent = com.harnessapk.wiki.WikiTurnIntent(
                    mode = com.harnessapk.wiki.WikiTurnIntentMode.AUTO,
                    namedWikiIds = emptySet(),
                    compareRequested = false,
                ),
                retrieval = null,
                systemContext = "Wiki 原文证据：⟦W1⟧ 二十四史 · 史记 / 卷六",
            )
            val useCase = SendMessageUseCase(
                context = ContextWrapper(null),
                chatRepository = store.repository,
                providerRepository = providerRepository(server),
                client = OpenAiCompatibleClient(OkHttpClient(), Json { ignoreUnknownKeys = true }),
                dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
                wikiContextProvider = { _, query, scope ->
                    contextCalls += 1
                    assertEquals("项羽如何失败", query)
                    assertEquals(listOf(ref), scope)
                    runtimeContext
                },
            )
            val entry = ChatExecutionEntry(
                id = "entry-wiki-context",
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
                requestContext = ChatExecutionRequestContext(wikiScopeSnapshot = listOf(ref)),
                errorMessage = null,
                createdAt = 1L,
                updatedAt = 1L,
            )

            val result = useCase.execute(entry, store.repository.listMessages(conversationId))

            assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
            assertEquals(1, contextCalls)
            assertTrue(server.takeRequest().body.readUtf8().contains(requireNotNull(runtimeContext.systemContext)))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun executeCompressedAgentConversationBuildsOneRequestFromTheSavedMemory() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"收到\"}}]}\n\ndata: [DONE]"))
        server.start()
        try {
            val existingMemory = ConversationMemory(
                conversationId = "pending",
                summary = "旧摘要",
                coveredThroughMessageId = null,
                coveredThroughCreatedAt = 0L,
                compressedMessageCount = 1,
                updatedAt = 1L,
            )
            val store = inMemoryChatStore(existingMemory)
            val repository = store.repository
            val conversationId = repository.createConversation()
            store.memoryDao.memory = existingMemory.copy(conversationId = conversationId)
            repository.insertUserMessage(conversationId, "旧会话内容".repeat(16_000), emptyList())
            val currentUserMessageId = repository.insertUserMessage(
                conversationId,
                "本轮问题".repeat(16_000),
                emptyList(),
            )
            var assemblerCalls = 0
            var assembledRequest: com.harnessapk.agent.AgentContextRequest? = null
            val session = com.harnessapk.session.SessionRequestContext(
                finalPrompt = "本会话目标",
                projectName = "唯一项目",
                deliverableTitle = "唯一交付物",
                projectContext = "唯一项目上下文",
                deliverableMarkdown = "唯一会话 Markdown",
            )
            val useCase = SendMessageUseCase(
                context = ContextWrapper(null),
                chatRepository = repository,
                providerRepository = providerRepository(server),
                client = OpenAiCompatibleClient(OkHttpClient(), Json { ignoreUnknownKeys = true }),
                dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
                agentContextProvider = { _, request ->
                    assemblerCalls += 1
                    assembledRequest = request
                    AgentRuntimeContext(
                        agentId = "agent-1",
                        version = 1,
                        systemPrompt = """
                            人格系统提示词
                            以下是本机保存的早期对话记忆。
                            ${request.conversationMemory}
                            项目：${request.projectContext}
                            会话：${request.sessionContext}
                        """.trimIndent(),
                        evidence = emptyList(),
                    )
                },
            )
            val entry = ChatExecutionEntry(
                id = "entry-memory",
                conversationId = conversationId,
                userMessageId = currentUserMessageId,
                assistantMessageId = null,
                targetAssistantMessageId = null,
                sequence = 1L,
                type = ChatExecutionType.NORMAL,
                status = ChatExecutionStatus.RUNNING,
                providerId = null,
                model = null,
                reasoningEffort = defaultReasoningEffort(),
                requestContext = ChatExecutionRequestContext(sessionContext = session),
                errorMessage = null,
                createdAt = 1L,
                updatedAt = 1L,
            )

            val result = useCase.execute(entry, repository.listMessages(conversationId))

            assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
            assertEquals(1, assemblerCalls)
            val savedMemory = requireNotNull(repository.memoryForConversation(conversationId))
            assertEquals(savedMemory.summary, assembledRequest?.conversationMemory)
            assertTrue(savedMemory.summary.contains("旧摘要"))
            assertTrue(savedMemory.summary.contains("旧会话内容"))
            val requestBody = server.takeRequest().body.readUtf8()
            assertEquals(1, requestBody.occurrencesOf("以下是本机保存的早期对话记忆。"))
            assertEquals(1, requestBody.occurrencesOf("唯一项目上下文"))
            assertEquals(1, requestBody.occurrencesOf("唯一会话 Markdown"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun executeReplacesPersistedAgentTextWhenEmptyEvidenceLeavesOnlyCitationMarker() = runTest {
        executeAndReadParts(
            response = "[资料1]",
            agentContext = agentContext(evidence = emptyList()),
        ).also { (_, parts) ->
            assertEquals(listOf(UiMessagePartType.TEXT), parts.map { it.type })
            assertEquals("", parts.single().content)
        }
    }

    @Test
    fun executePersistsSanitizedAgentTextAndSeparateSources() = runTest {
        executeAndReadParts(
            response = "先调查。[资料 1]再决定。",
            agentContext = agentContext(
                evidence = listOf(
                    AgentEvidence(
                        "chunk-1",
                        "实践论",
                        "第一章",
                        "调查先于结论。",
                        8,
                        chunkKey = "source-hash:chunk-1",
                    ),
                ),
            ),
        ).also { (_, parts) ->
            assertEquals("先调查。再决定。", parts.first { it.type == UiMessagePartType.TEXT }.content)
            assertEquals(UiMessagePartType.AGENT_SOURCES, parts.last().type)
            assertTrue(parts.last().content.contains("资料 1 · 实践论 · 第一章"))
            assertEquals("[\"source-hash:chunk-1\"]", parts.last().metadata["chunkKeys"])
        }
    }

    @Test
    fun executeKeepsCitationMarkerForOrdinaryConversation() = runTest {
        executeAndReadParts(
            response = "先调查。[资料1]再决定。",
            agentContext = null,
        ).also { (_, parts) ->
            assertEquals("先调查。[资料1]再决定。", parts.single().content)
        }
    }

    @Test
    fun fixedAgentConversationFailsUnavailableBeforeNetworkWhenAssemblerReturnsNull() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("data: [DONE]"))
        server.start()
        try {
            val store = inMemoryChatStore()
            val repository = store.repository
            val conversationId = repository.createConversation(agentId = "agent-1", agentVersion = 7)
            val userMessageId = repository.insertUserMessage(conversationId, "继续", emptyList())
            val useCase = SendMessageUseCase(
                context = ContextWrapper(null),
                chatRepository = repository,
                providerRepository = providerRepository(server),
                client = OpenAiCompatibleClient(OkHttpClient(), Json { ignoreUnknownKeys = true }),
                dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
                agentContextProvider = { _, _ -> null },
            )
            val entry = ChatExecutionEntry(
                id = "entry-unavailable",
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
                requestContext = ChatExecutionRequestContext(
                    sessionContext = com.harnessapk.session.SessionRequestContext(
                        finalPrompt = "不得降级使用的普通会话提示",
                        projectName = null,
                        deliverableTitle = null,
                        projectContext = "不得降级使用的项目提示",
                        deliverableMarkdown = "",
                    ),
                    webSearchEnabled = true,
                ),
                errorMessage = null,
                createdAt = 1L,
                updatedAt = 1L,
            )

            val result = useCase.execute(
                entry,
                repository.listMessages(conversationId),
                nativeWebSearchMode = com.harnessapk.provider.NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
            )

            assertEquals(ChatExecutionStatus.FAILED, result.status)
            assertEquals("固定人格当前不可用，请检查人格包和所需资料后重试。", result.errorMessage)
            assertEquals(0, server.requestCount)
            val assistant = repository.listMessages(conversationId).last()
            assertEquals(MessageRole.ASSISTANT, assistant.role)
            assertEquals(MessageStatus.FAILED, assistant.status)
            assertTrue(assistant.errorMessage.orEmpty().contains("固定人格当前不可用，请检查人格包和所需资料后重试。"))
            assertTrue(assistant.errorMessage.orEmpty().contains("AppError\$AgentUnavailable"))
            assertTrue(repository.listMessageParts(assistant.id).isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun executeSendsStoredV1RuntimeContextWithoutWorldviewInternalId() = runTest {
        var requestBody = ""
        val root = Files.createTempDirectory("agent-send-context-test").toFile().apply { deleteOnExit() }
        val agentRepository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = FakeAgentDao().apply {
                version = AgentVersionEntity(
                    agentId = "agent-1",
                    version = 1,
                    schemaVersion = 1,
                    bundlePath = "/tmp/agent.hbundle",
                    bundleSha256 = "sha",
                    manifestJson = "{}",
                    persona = "我重视从事实出发。",
                    worldviewJsonl = """{"id":"view-investigation","statement":"调查应先于结论","evidence":["chunk-secret-42"],"confidence":1.0}""",
                    installedAt = 1L,
                    state = "READY",
                )
            },
            timeProvider = TimeProvider { 1L },
            ioDispatcher = Dispatchers.Unconfined,
        )

        executeAndReadParts(
            response = "收到。",
            agentContext = null,
            agentContextProvider = { _, request -> agentRepository.runtimeContext("agent-1", 1, request.query) },
            onRequestBody = { requestBody = it },
        )

        val messages = Json.parseToJsonElement(requestBody).jsonObject["messages"]!!.jsonArray
        val outgoingSystemContext = messages.first { message ->
            message.jsonObject["role"]!!.jsonPrimitive.contentOrNull == "system"
        }.jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(outgoingSystemContext.contains("调查应先于结论"))
        assertFalse(outgoingSystemContext.contains("chunk-secret-42"))
        assertFalse(outgoingSystemContext.contains("view-investigation"))
        assertFalse(outgoingSystemContext.contains("evidence"))
    }

    @Test
    fun executeSanitizesPersistedAgentTextBeforeMarkingFailure() = runTest {
        executeAndReadParts(
            response = "中途结果[资料1]",
            agentContext = agentContext(evidence = emptyList()),
            malformedAfterText = true,
        ).also { (status, parts) ->
            assertEquals(MessageStatus.FAILED, status)
            assertEquals("中途结果", parts.single().content)
        }
    }

    @Test
    fun executeSanitizesPersistedAgentTextBeforeMarkingCancellation() = runTest {
        executeAndReadParts(
            response = "中途结果[资料1]",
            agentContext = agentContext(evidence = emptyList()),
            cancelAfterText = true,
        ).also { (status, parts) ->
            assertEquals(MessageStatus.CANCELLED, status)
            assertEquals("中途结果", parts.single().content)
        }
    }

    @Test
    fun executeRealJobCancellationSanitizesAlreadyPersistedAgentText() = runTest {
        executeAndReadParts(
            response = "中途结果[资料1]",
            agentContext = agentContext(evidence = emptyList()),
            realJobCancellationAfterMarker = true,
        ).also { (status, parts) ->
            assertEquals(MessageStatus.CANCELLED, status)
            assertEquals("中途结果", parts.single { it.type == UiMessagePartType.TEXT }.content)
            assertTrue(parts.all { it.stable })
        }
    }

    @Test
    fun executeKeepsSourcesAndNonTextPartsWhenFinalSourceWriteFails() = runTest {
        executeAndReadParts(
            response = "最终回答[资料1]",
            agentContext = agentContext(
                evidence = listOf(AgentEvidence("chunk-1", "实践论", "第一章", "调查先于结论。", 8)),
            ),
            failureAfterSourceWrite = IllegalStateException("final source write failed"),
            includeToolResult = true,
        ).also { (status, parts) ->
            assertEquals(MessageStatus.FAILED, status)
            assertEquals("最终回答", parts.first { it.type == UiMessagePartType.TEXT }.content)
            assertEquals("[资料9]", parts.first { it.type == UiMessagePartType.TOOL_RESULT }.content)
            assertEquals(UiMessagePartType.AGENT_SOURCES, parts.last().type)
        }
    }

    @Test
    fun executeKeepsSourcesAndMarksCancelledWhenFinalSourceWriteCancels() = runTest {
        executeAndReadParts(
            response = "最终回答[资料1]",
            agentContext = agentContext(
                evidence = listOf(AgentEvidence("chunk-1", "实践论", "第一章", "调查先于结论。", 8)),
            ),
            failureAfterSourceWrite = CancellationException("final source write cancelled"),
        ).also { (status, parts) ->
            assertEquals(MessageStatus.CANCELLED, status)
            assertEquals("最终回答", parts.first { it.type == UiMessagePartType.TEXT }.content)
            assertEquals(UiMessagePartType.AGENT_SOURCES, parts.last().type)
            assertTrue(parts.all { it.stable })
        }
    }

    @Test
    fun executeRealJobCancellationAfterSourceWritePreservesFinalAgentParts() = runTest {
        executeAndReadParts(
            response = "最终回答[资料1]",
            agentContext = agentContext(
                evidence = listOf(AgentEvidence("chunk-1", "实践论", "第一章", "调查先于结论。", 8)),
            ),
            cancelCurrentContextAfterSourceWrite = true,
            includeToolResult = true,
        ).also { (status, parts) ->
            assertEquals(MessageStatus.CANCELLED, status)
            assertEquals("最终回答", parts.first { it.type == UiMessagePartType.TEXT }.content)
            assertEquals("[资料9]", parts.first { it.type == UiMessagePartType.TOOL_RESULT }.content)
            assertEquals(UiMessagePartType.AGENT_SOURCES, parts.last().type)
            assertTrue(parts.all { it.stable })
        }
    }

    @Test
    fun finalAgentSourcesWriteDropsChunkDeletedAfterRetrievalBeforePersistence() = runTest {
        val store = inMemoryChatStore()
        val conversationId = store.repository.createConversation(agentId = "agent-1", agentVersion = 1)
        val assistantId = store.repository.insertAssistantPending(conversationId, "provider-1", "model-1")
        val selectedAfterRetrieval = agentContext(
            evidence = listOf(
                AgentEvidence(
                    "chunk-1",
                    "实践论",
                    "第一章",
                    "调查先于结论。",
                    8,
                    chunkKey = "source-hash:chunk-1",
                ),
            ),
        )
        val dao = FakeAgentDao().apply {
            persistableChunkKeys += "source-hash:chunk-1"
        }
        val writer = AgentSourcePartWriter(
            dao = dao,
            chatRepository = store.repository,
            transactionRunner = AgentTransactionRunner { block -> block() },
            lifecycleCoordinator = AgentLifecycleCoordinator(),
        )
        dao.persistableChunkKeys.clear()

        val persisted = writer.persist(
            messageId = assistantId,
            snapshot = StreamingMessageSnapshot(
                status = MessageStatus.PENDING,
                parts = listOf(
                    UiMessagePartDraft(
                        index = 0,
                        type = UiMessagePartType.TEXT,
                        content = "先调查。[资料1]",
                        stable = true,
                    ),
                ),
            ),
            context = selectedAfterRetrieval,
        )

        assertEquals("先调查。", persisted.parts.single().content)
        assertTrue(persisted.parts.none { it.type == UiMessagePartType.AGENT_SOURCES })
        assertTrue(
            store.repository.listMessageParts(assistantId).none {
                it.metadata["chunkKeys"]?.contains("source-hash:chunk-1") == true
            },
        )
    }

    @Test
    fun finalAgentSourcesValidationAndReplacementShareTransactionAndNotifyOnlyAfterCommit() = runTest {
        val store = inMemoryChatStore()
        val conversationId = store.repository.createConversation(agentId = "agent-1", agentVersion = 1)
        val assistantId = store.repository.insertAssistantPending(conversationId, "provider-1", "model-1")
        val events = mutableListOf<String>()
        val dao = FakeAgentDao().apply {
            persistableChunkKeys += "source-hash:chunk-1"
            onInstalledVersionChunkKeysRead = { events += "validate" }
        }
        val writer = AgentSourcePartWriter(
            dao = dao,
            chatRepository = store.repository,
            transactionRunner = AgentTransactionRunner { block ->
                events += "begin"
                block()
                events += "commit"
            },
            lifecycleCoordinator = AgentLifecycleCoordinator(),
        )

        writer.persist(
            messageId = assistantId,
            snapshot = StreamingMessageSnapshot(
                status = MessageStatus.PENDING,
                parts = listOf(UiMessagePartDraft(0, UiMessagePartType.TEXT, "回答[资料1]", stable = true)),
            ),
            context = agentContext(
                evidence = listOf(
                    AgentEvidence("chunk-1", "实践论", "第一章", "调查先于结论。", 8, "source-hash:chunk-1"),
                ),
            ),
            onValidated = { events += "validated" },
        )

        assertEquals(listOf("begin", "validate", "commit", "validated"), events)
    }

    @Test
    fun finalAgentSourcesWriteRollsBackWithoutValidationCallbackWhenReplacementFails() = runTest {
        val store = inMemoryChatStore(failureAfterSourceWrite = IllegalStateException("replace failed"))
        val conversationId = store.repository.createConversation(agentId = "agent-1", agentVersion = 1)
        val assistantId = store.repository.insertAssistantPending(conversationId, "provider-1", "model-1")
        val events = mutableListOf<String>()
        val writer = AgentSourcePartWriter(
            dao = FakeAgentDao().apply {
                persistableChunkKeys += "source-hash:chunk-1"
                onInstalledVersionChunkKeysRead = { events += "validate" }
            },
            chatRepository = store.repository,
            transactionRunner = AgentTransactionRunner { block ->
                events += "begin"
                try {
                    block()
                    events += "commit"
                } catch (error: Throwable) {
                    events += "rollback"
                    throw error
                }
            },
            lifecycleCoordinator = AgentLifecycleCoordinator(),
        )

        assertTrue(
            runCatching {
                writer.persist(assistantId, sourceSnapshot(), sourceContext()) { events += "validated" }
            }.isFailure,
        )
        assertEquals(listOf("begin", "validate", "rollback"), events)
    }

    @Test
    fun finalAgentSourcesWriteKeepsDaoMutationOutsideLifecycleCoordinatorOutOfTransactionWindow() = runTest {
        val store = inMemoryChatStore()
        val conversationId = store.repository.createConversation(agentId = "agent-1", agentVersion = 1)
        val assistantId = store.repository.insertAssistantPending(conversationId, "provider-1", "model-1")
        val queryRead = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        val databaseLock = Mutex()
        val dao = FakeAgentDao().apply {
            persistableChunkKeys += "source-hash:chunk-1"
            onInstalledVersionChunkKeysRead = {
                queryRead.complete(Unit)
                allowWrite.await()
            }
        }
        val transactionRunner = AgentTransactionRunner { block -> databaseLock.withLock { block() } }
        val writer = AgentSourcePartWriter(
            dao = dao,
            chatRepository = store.repository,
            transactionRunner = transactionRunner,
            lifecycleCoordinator = AgentLifecycleCoordinator(),
        )

        val write = async { writer.persist(assistantId, sourceSnapshot(), sourceContext()) }
        queryRead.await()
        val mutation = launch {
            transactionRunner.run { dao.persistableChunkKeys.clear() }
        }
        assertFalse(mutation.isCompleted)
        allowWrite.complete(Unit)
        write.await()
        mutation.join()

        assertEquals(UiMessagePartType.AGENT_SOURCES, store.repository.listMessageParts(assistantId).last().type)
    }

    private fun sourceSnapshot() = StreamingMessageSnapshot(
        status = MessageStatus.PENDING,
        parts = listOf(UiMessagePartDraft(0, UiMessagePartType.TEXT, "回答[资料1]", stable = true)),
    )

    private fun sourceContext() = agentContext(
        evidence = listOf(
            AgentEvidence("chunk-1", "实践论", "第一章", "调查先于结论。", 8, "source-hash:chunk-1"),
        ),
    )

    private suspend fun executeAndReadParts(
        response: String,
        agentContext: AgentRuntimeContext?,
        malformedAfterText: Boolean = false,
        cancelAfterText: Boolean = false,
        failureAfterSourceWrite: Throwable? = null,
        includeToolResult: Boolean = false,
        realJobCancellationAfterMarker: Boolean = false,
        cancelCurrentContextAfterSourceWrite: Boolean = false,
        agentContextProvider: suspend (conversationId: String, request: com.harnessapk.agent.AgentContextRequest) -> AgentRuntimeContext? = { _, _ -> agentContext },
        onRequestBody: ((String) -> Unit)? = null,
    ): Pair<MessageStatus, List<UiMessagePartDraft>> {
        val server = MockWebServer()
        val responseBody = """
            data: {"choices":[{"delta":{"content":"$response"}}]}
            ${if (malformedAfterText) "data: {not-json}" else "data: [DONE]"}
        """.trimIndent()
        server.enqueue(
            if (realJobCancellationAfterMarker) {
                MockResponse().setChunkedBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"$response\"}}]}\n" + " ".repeat(128 * 1024),
                    1024,
                )
            } else {
                MockResponse().setBody(responseBody)
            },
        )
        server.start()
        try {
            val markerPersisted = CompletableDeferred<Unit>()
            val store = inMemoryChatStore(
                failureAfterSourceWrite = failureAfterSourceWrite,
                cancellationAware = realJobCancellationAfterMarker,
                markerPersisted = markerPersisted,
                cancelCurrentContextAfterSourceWrite = cancelCurrentContextAfterSourceWrite,
            )
            val repository = store.repository
            val conversationId = repository.createConversation()
            val userMessageId = repository.insertUserMessage(conversationId, "怎么做", emptyList())
            val useCase = SendMessageUseCase(
                context = ContextWrapper(null),
                chatRepository = repository,
                providerRepository = providerRepository(server),
                client = OpenAiCompatibleClient(OkHttpClient(), Json { ignoreUnknownKeys = true }),
                dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
                agentContextProvider = agentContextProvider,
                agentSourcePartWriter = AgentSourcePartWriter(
                    dao = FakeAgentDao().apply {
                        persistableChunkKeys += agentContext?.evidence.orEmpty().map(AgentEvidence::chunkKey)
                    },
                    chatRepository = repository,
                    transactionRunner = AgentTransactionRunner { block -> block() },
                    lifecycleCoordinator = AgentLifecycleCoordinator(),
                ),
                outputTransformerPipelineFactory = {
                    StreamEventTransformerPipeline(
                        listOf(object : StreamEventTransformer {
                            override fun transform(event: StreamEvent): List<StreamEvent> {
                                if (cancelAfterText && event is StreamEvent.Finished) {
                                    throw CancellationException("test cancellation")
                                }
                                return if (includeToolResult && event is StreamEvent.TextDelta) {
                                    listOf(event, StreamEvent.ToolResult("tool-1", "[资料9]"))
                                } else {
                                    listOf(event)
                                }
                            }
                        }),
                    )
                },
            )

            val entry = ChatExecutionEntry(
                    id = "entry-1",
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
                    createdAt = 0L,
                    updatedAt = 0L,
            )
            val result = if (realJobCancellationAfterMarker || cancelCurrentContextAfterSourceWrite) {
                coroutineScope {
                    val execution = async {
                        useCase.execute(entry, repository.listMessages(conversationId))
                    }
                    if (realJobCancellationAfterMarker) {
                        markerPersisted.await()
                        execution.cancel()
                    }
                    val failure = runCatching { execution.await() }.exceptionOrNull()
                    assertTrue(failure is CancellationException)
                    ChatExecutionResult(
                        status = ChatExecutionStatus.CANCELLED,
                        assistantMessageId = repository.listMessages(conversationId).last().id,
                        errorMessage = null,
                    )
                }
            } else {
                try {
                    useCase.execute(entry, repository.listMessages(conversationId))
                } catch (cancelled: CancellationException) {
                    ChatExecutionResult(
                        status = ChatExecutionStatus.CANCELLED,
                        assistantMessageId = repository.listMessages(conversationId).last().id,
                        errorMessage = null,
                    )
                }
            }

            if (!malformedAfterText && !cancelAfterText && failureAfterSourceWrite == null &&
                !realJobCancellationAfterMarker && !cancelCurrentContextAfterSourceWrite
            ) {
                assertEquals(ChatExecutionStatus.SUCCEEDED, result.status)
            }
            onRequestBody?.invoke(requireNotNull(server.takeRequest()).body.readUtf8())
            val assistantId = requireNotNull(result.assistantMessageId)
            val persisted = repository.listMessages(conversationId).last { it.id == assistantId }
            return persisted.status to repository.listMessageParts(assistantId)
        } finally {
            server.shutdown()
        }
    }

    private fun agentContext(evidence: List<AgentEvidence>) =
        AgentRuntimeContext("agent-1", 1, "人格提示词", evidence)
}

private data class ExecuteChatStore(
    val repository: ChatRepository,
    val memoryDao: ExecuteConversationMemoryDao,
)

private fun inMemoryChatStore(
    initialMemory: ConversationMemory? = null,
    failureAfterSourceWrite: Throwable? = null,
    cancellationAware: Boolean = false,
    markerPersisted: CompletableDeferred<Unit>? = null,
    cancelCurrentContextAfterSourceWrite: Boolean = false,
): ExecuteChatStore {
    val messagePartDao = ExecuteMessagePartDao(
        failureAfterSourceWrite = failureAfterSourceWrite,
        cancellationAware = cancellationAware,
        markerPersisted = markerPersisted,
        cancelCurrentContextAfterSourceWrite = cancelCurrentContextAfterSourceWrite,
    )
    val memoryDao = ExecuteConversationMemoryDao(initialMemory)
    return ExecuteChatStore(
        repository = ChatRepository(
            conversationDao = ExecuteConversationDao(),
            messageDao = ExecuteMessageDao(),
            messagePartDao = messagePartDao,
            attachmentDao = ExecuteMessageAttachmentDao(),
            memoryDao = memoryDao,
            timeProvider = TimeProvider { 1L },
        ),
        memoryDao = memoryDao,
    )
}

private fun providerRepository(server: MockWebServer): ProviderRepository = ProviderRepository(
    dao = ExecuteProviderProfileDao().apply {
        row = ProviderProfileEntity(
            id = "provider-1",
            name = "Test",
            baseUrl = server.url("/v1").toString(),
            apiKeyAlias = "provider:provider-1",
            encryptedApiKey = "secret".encodeToByteArray(),
            apiKeyIv = "iv".encodeToByteArray(),
            defaultModel = "test-model",
            availableModels = "test-model",
            defaultVisionModel = null,
            supportsVision = false,
            nativeWebSearchMode = "NONE",
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

private class ExecuteConversationDao : ConversationDao {
    private val rows = linkedMapOf<String, ConversationEntity>()
    override fun observeActive(): Flow<List<ConversationEntity>> = MutableStateFlow(rows.values.toList())
    override suspend fun findById(id: String) = rows[id]
    override suspend fun findLatestActive() = rows.values.filter { !it.isArchived }
        .maxWithOrNull(compareBy<ConversationEntity> { it.updatedAt }.thenBy { it.id })
    override suspend fun findLatestActiveInProject(projectId: String) = rows.values
        .filter { !it.isArchived && it.projectId == projectId }
        .maxWithOrNull(compareBy<ConversationEntity> { it.updatedAt }.thenBy { it.id })
    override suspend fun insert(entity: ConversationEntity) { rows[entity.id] = entity }
    override suspend fun update(entity: ConversationEntity) { rows[entity.id] = entity }
    override suspend fun updateIdentityIfNoUserMessages(id: String, agentId: String?, agentVersion: Int?, updatedAt: Long): Int = 0
    override suspend fun clearProject(projectId: String) {
        rows.replaceAll { _, conversation ->
            if (conversation.projectId == projectId) conversation.copy(projectId = null) else conversation
        }
    }
    override suspend fun archive(id: String, updatedAt: Long) = Unit
    override suspend fun countByAgentVersion(agentId: String, version: Int) =
        rows.values.count { it.agentId == agentId && it.agentVersion == version }
}

private class ExecuteMessageDao : MessageDao {
    private val rows = linkedMapOf<String, MessageEntity>()
    override fun observeForConversation(conversationId: String): Flow<List<MessageEntity>> = MutableStateFlow(rows.values.filter { it.conversationId == conversationId })
    override suspend fun listForConversation(conversationId: String) = rows.values.filter { it.conversationId == conversationId }
    override suspend fun listRecentSuccessfulText(conversationId: String, limit: Int) = rows.values
        .filter {
            it.conversationId == conversationId &&
                it.status == "SUCCEEDED" &&
                it.role in setOf("USER", "ASSISTANT") &&
                it.content.isNotBlank()
        }
        .sortedWith(compareByDescending<MessageEntity> { it.createdAt }.thenByDescending { it.id })
        .take(limit)
    override suspend fun findLastSuccessfulAssistant(conversationId: String) = rows.values
        .filter {
            it.conversationId == conversationId &&
                it.status == "SUCCEEDED" &&
                it.role == "ASSISTANT"
        }
        .maxWithOrNull(compareBy<MessageEntity> { it.createdAt }.thenBy { it.id })
    override suspend fun countSuccessfulAssistantText(conversationId: String) = rows.values.count {
        it.conversationId == conversationId &&
            it.status == "SUCCEEDED" &&
            it.role == "ASSISTANT" &&
            it.content.isNotBlank()
    }
    override suspend fun findById(id: String) = rows[id]
    override suspend fun countUserMessages(conversationId: String) = rows.values.count { it.conversationId == conversationId && it.role == "USER" }
    override suspend fun insert(entity: MessageEntity) { rows[entity.id] = entity }
    override suspend fun update(entity: MessageEntity) { rows[entity.id] = entity }
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun deleteForConversation(conversationId: String) { rows.entries.removeIf { it.value.conversationId == conversationId } }
}

private class ExecuteMessagePartDao(
    private var failureAfterSourceWrite: Throwable? = null,
    private val cancellationAware: Boolean = false,
    private val markerPersisted: CompletableDeferred<Unit>? = null,
    private var cancelCurrentContextAfterSourceWrite: Boolean = false,
) : MessagePartDao {
    private val rows = linkedMapOf<String, MessagePartEntity>()
    override fun observeForMessage(messageId: String): Flow<List<MessagePartEntity>> = MutableStateFlow(rows.values.filter { it.messageId == messageId }.sortedBy { it.partIndex })
    override suspend fun listForMessage(messageId: String) = rows.values.filter { it.messageId == messageId }.sortedBy { it.partIndex }
    override suspend fun insertAll(parts: List<MessagePartEntity>) { parts.forEach { rows[it.id] = it } }
    override suspend fun deleteForMessage(messageId: String) { rows.entries.removeIf { it.value.messageId == messageId } }
    override suspend fun markStableForMessage(messageId: String, updatedAt: Long) { rows.replaceAll { _, value -> if (value.messageId == messageId) value.copy(stable = true, updatedAt = updatedAt) else value } }
    override suspend fun replaceForMessage(messageId: String, parts: List<MessagePartEntity>) {
        if (cancellationAware) currentCoroutineContext().ensureActive()
        deleteForMessage(messageId)
        insertAll(parts)
        if (parts.any { it.type == UiMessagePartType.TEXT.name && it.content.contains("[资料") }) {
            markerPersisted?.complete(Unit)
        }
        if (parts.any { it.type == UiMessagePartType.AGENT_SOURCES.name }) {
            if (cancelCurrentContextAfterSourceWrite) {
                cancelCurrentContextAfterSourceWrite = false
                currentCoroutineContext().cancel(CancellationException("source write cancelled"))
                currentCoroutineContext().ensureActive()
            }
            failureAfterSourceWrite?.let { failure ->
                failureAfterSourceWrite = null
                throw failure
            }
        }
    }
}

private class ExecuteMessageAttachmentDao : MessageAttachmentDao {
    override fun observeForMessage(messageId: String): Flow<List<MessageAttachmentEntity>> = MutableStateFlow(emptyList())
    override suspend fun listForMessage(messageId: String): List<MessageAttachmentEntity> = emptyList()
    override suspend fun insert(entity: MessageAttachmentEntity) = Unit
}

private class ExecuteConversationMemoryDao(initialMemory: ConversationMemory? = null) : ConversationMemoryDao {
    var memory: ConversationMemory? = initialMemory

    override suspend fun findByConversationId(conversationId: String): ConversationMemoryEntity? =
        memory?.takeIf { it.conversationId == conversationId }?.toMemoryEntity()

    override fun observeForConversation(conversationId: String): Flow<ConversationMemoryEntity?> =
        MutableStateFlow(memory?.takeIf { it.conversationId == conversationId }?.toMemoryEntity())

    override suspend fun upsert(entity: ConversationMemoryEntity) {
        memory = ConversationMemory(
            conversationId = entity.conversationId,
            summary = entity.summary,
            coveredThroughMessageId = entity.coveredThroughMessageId,
            coveredThroughCreatedAt = entity.coveredThroughCreatedAt,
            compressedMessageCount = entity.compressedMessageCount,
            updatedAt = entity.updatedAt,
        )
    }
}

private fun String.occurrencesOf(needle: String): Int =
    split(needle).size - 1

private fun ConversationMemory.toMemoryEntity(): ConversationMemoryEntity = ConversationMemoryEntity(
    conversationId = conversationId,
    summary = summary,
    coveredThroughMessageId = coveredThroughMessageId,
    coveredThroughCreatedAt = coveredThroughCreatedAt,
    compressedMessageCount = compressedMessageCount,
    updatedAt = updatedAt,
)

private class ExecuteProviderProfileDao : ProviderProfileDao {
    var row: ProviderProfileEntity? = null
    override fun observeEnabled(): Flow<List<ProviderProfileEntity>> = MutableStateFlow(listOfNotNull(row))
    override suspend fun findById(id: String) = row?.takeIf { it.id == id }
    override suspend fun firstEnabled() = row?.takeIf { it.enabled }
    override suspend fun insert(entity: ProviderProfileEntity) { row = entity }
    override suspend fun update(entity: ProviderProfileEntity) { row = entity }
    override suspend fun delete(entity: ProviderProfileEntity) { if (row?.id == entity.id) row = null }
}
