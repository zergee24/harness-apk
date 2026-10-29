package com.harnessapk.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.harnessapk.agent.AgentEvidence
import com.harnessapk.agent.AgentContextRequest
import com.harnessapk.agent.AgentRuntimeContext
import com.harnessapk.agent.AgentLifecycleCoordinator
import com.harnessapk.agent.sanitizeAgentCitationMarkers
import com.harnessapk.common.AppDispatchers
import com.harnessapk.common.AppError
import com.harnessapk.common.toUserMessage
import com.harnessapk.network.ChatStreamClient
import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.network.OutgoingToolCall
import com.harnessapk.provider.ModelCapabilityResolver
import com.harnessapk.provider.ModelConfig
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderApiProtocol
import com.harnessapk.provider.ProviderCapabilityCatalog
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.provider.ProviderProfile
import com.harnessapk.projectsearch.ProjectCitationVerifier
import com.harnessapk.projectsearch.ProjectRuntimeContext
import com.harnessapk.session.SessionRequestContext
import com.harnessapk.session.buildSessionOutgoingMessages
import com.harnessapk.websearch.WebSearchContext
import com.harnessapk.websearch.toVisibleSourcesMarkdown
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiRuntimeContext
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
import java.util.Locale
import java.util.UUID
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class SendMessageUseCase(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val client: ChatStreamClient,
    private val dispatchers: AppDispatchers,
    private val contextCompressor: ContextCompressor = ContextCompressor(),
    private val imageCompressionPolicy: ImageCompressionPolicy = ImageCompressionPolicy(),
    private val requestBuilder: ModelAwareRequestBuilder = ModelAwareRequestBuilder(),
    private val remoteCapabilityCatalog: suspend () -> ProviderCapabilityCatalog? = { null },
    private val agentContextProvider: suspend (conversationId: String, request: AgentContextRequest) -> AgentRuntimeContext? = { _, _ -> null },
    private val outputTransformerPipelineFactory: () -> StreamEventTransformerPipeline = {
        StreamEventTransformerPipeline(listOf(ThinkTagStreamTransformer()))
    },
    private val agentSourcePartWriter: AgentSourcePartWriter? = null,
    private val wikiContextProvider: suspend (conversationId: String, query: String, scope: List<WikiRef>) -> WikiRuntimeContext? =
        { _, _, _ -> null },
    private val wikiSourcePartWriter: WikiSourcePartWriter? = null,
    private val projectContextProvider:
        suspend (conversationId: String, executionId: String, assistantMessageId: String, query: String, projectId: String) -> ProjectRuntimeContext? =
        { _, _, _, _, _ -> null },
    private val projectSourcePartWriter: ProjectSourcePartWriter? = null,
    private val projectCitationAuditWriter:
        suspend (executionId: String, status: String, unknownTokens: List<String>) -> Unit = { _, _, _ -> },
    private val lifecycleCoordinator: AgentLifecycleCoordinator = AgentLifecycleCoordinator(),
    /** 远程生图工具；为 null 或 [ImageGenerateTool.isAvailable] 为假时不声明 function tools。 */
    private val imageGenerateTool: ImageGenerateTool? = null,
    /** 生成图片落盘能力；缺省时不声明工具（没有地方展示结果）。 */
    private val generatedImageFileWriter: GeneratedImageFileWriter? = null,
    /** 工具调用的诊断日志出口；默认写 logcat，测试可替换为 no-op。 */
    private val toolCallLogger: (String) -> Unit = { message ->
        android.util.Log.w(TAG, message)
    },
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
        onPhaseChanged: suspend (ChatExecutionPhase) -> Unit = {},
    ): ChatExecutionResult = withContext(dispatchers.io) {
        val conversation = requireNotNull(chatRepository.conversation(entry.conversationId)) {
            "待执行的会话不存在"
        }
        val fixedAgentConversation = !conversation.agentId.isNullOrBlank() && conversation.agentVersion != null
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
        var wikiContext: WikiRuntimeContext? = null
        var projectContext: ProjectRuntimeContext? = null
        var latestSnapshot: StreamingMessageSnapshot? = null
        var streamCompleted = false
        val traceId = UUID.randomUUID().toString()
        val startedAtMillis = System.currentTimeMillis()
        var flushCount = 0
        var receivedChars = 0

        try {
            val existingMemory = chatRepository.memoryForConversation(entry.conversationId)
            val imageDataUrls = attachments.map { it.toDataUrl() }
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
            val memoryForAgent = compressed.memoryToSave ?: existingMemory
            agentContext = agentContextProvider(
                entry.conversationId,
                agentContextRequestForSend(
                    query = text,
                    conversationMemory = memoryForAgent?.summary.orEmpty(),
                    sessionContext = entry.requestContext.sessionContext,
                ),
            )
            if (fixedAgentConversation && agentContext == null) throw AppError.AgentUnavailable()
            val wikiScope = entry.requestContext.wikiScopeSnapshot.orEmpty()
            if (wikiScope.isNotEmpty()) onPhaseChanged(ChatExecutionPhase.RETRIEVING_KNOWLEDGE)
            wikiContext = if (wikiScope.isEmpty()) {
                null
            } else {
                requireNotNull(wikiContextProvider(entry.conversationId, text, wikiScope)) {
                    "Wiki 运行时上下文不可用"
                }.also { context ->
                    require(context.scope == com.harnessapk.wiki.canonicalWikiScope(wikiScope)) {
                        "Wiki 运行时上下文范围不匹配"
                    }
                }
            }
            val effectiveSearchContexts = effectiveAgentSearchContexts(
                agentContext = agentContext,
                webSearchContext = webSearchContext,
                nativeWebSearchMode = nativeWebSearchMode,
            )
            val nextAssistantId = chatRepository.insertAssistantPending(
                entry.conversationId,
                provider.profile.id,
                requestModel,
            )
            onAssistantCreated(nextAssistantId)
            assistantId = nextAssistantId
            wikiContext?.let { context -> wikiSourcePartWriter?.persistInitial(nextAssistantId, context) }
            conversation.projectId?.takeIf(String::isNotBlank)?.let { projectId ->
                onPhaseChanged(ChatExecutionPhase.RETRIEVING_KNOWLEDGE)
                projectContext = runCatching {
                    projectContextProvider(
                        entry.conversationId,
                        entry.id,
                        nextAssistantId,
                        text,
                        projectId,
                    )
                }.getOrNull()
            }
            latestSnapshot = StreamingMessageSnapshot(
                status = MessageStatus.PENDING,
                parts = emptyList(),
            )
            // 工具声明只对 OpenAI 兼容协议生效；Anthropic Messages 本轮保持现状（见 AnthropicMessagesClient 的注释）。
            val imageToolAvailable = imageGenerateTool?.isAvailable() == true &&
                generatedImageFileWriter != null &&
                provider.profile.apiProtocol == ProviderApiProtocol.OPENAI_COMPATIBLE
            // 不可用时不声明 tools、也不在系统提示里承诺生图能力，避免模型空调用。
            var requestMessages = appendSystemInstruction(
                messages = buildSessionOutgoingMessages(
                    context = sessionContextOutsideAgentPrompt(entry.requestContext.sessionContext, agentContext),
                    baseMessages = requestBaseMessages(compressed, agentContext),
                    webSearchContext = effectiveSearchContexts.webSearchContext,
                    agentSystemContext = agentContext?.systemPrompt,
                    wikiSystemContext = wikiContext?.systemContext,
                    projectSystemContext = projectContext?.systemContext,
                ),
                instruction = if (imageToolAvailable) IMAGE_GENERATE_TOOL_SYSTEM_PROMPT else null,
            )
            var imageToolRounds = 0
            while (true) {
                val declaredTools = if (imageToolAvailable && imageToolRounds < MAX_IMAGE_TOOL_ROUNDS) {
                    listOf(imageGenerateToolDefinition())
                } else {
                    emptyList()
                }
                val modelAwareRequest = requestBuilder.build(
                    provider = provider.profile,
                    apiKey = provider.apiKey,
                    capability = resolvedCapability,
                    messages = requestMessages,
                    temperature = temperatureForModel(requestModel),
                    selectedReasoningEffort = entry.reasoningEffort,
                    webSearchRequested = effectiveSearchContexts.nativeWebSearchMode != null,
                    tools = declaredTools,
                    toolChoice = TOOL_CHOICE_AUTO.takeIf { declaredTools.isNotEmpty() },
                )
                requestDiagnostics = modelAwareRequest.diagnostics
                onPhaseChanged(ChatExecutionPhase.GENERATING)
                streamCompleted = false
                val activeStreamClock = TimeSource.Monotonic.markNow()
                val activeAccumulator = StreamingMessageAccumulator()
                streamClock = activeStreamClock
                accumulator = activeAccumulator
                val outputTransformerPipeline = outputTransformerPipelineFactory()
                val toolCallCollector = ToolCallCollector()
                client.streamChatEvents(modelAwareRequest.request).collect { event ->
                    outputTransformerPipeline.transform(event).forEach { transformedEvent ->
                        receivedChars += transformedEvent.visiblePayloadLength()
                        if (transformedEvent is StreamEvent.ToolCallDelta) {
                            // 工具调用增量不落 UI part：整轮结束后统一把 TOOL_CALL/TOOL_RESULT 落到图片消息。
                            toolCallCollector.accept(transformedEvent)
                        } else {
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
                }
                streamCompleted = true
                val toolCalls = toolCallCollector.completedCalls()
                if (toolCalls.isEmpty() || imageToolRounds >= MAX_IMAGE_TOOL_ROUNDS) break
                imageToolRounds += 1
                // 执行本轮工具调用，并把 assistant.tool_calls + tool 回执追加进下一轮请求。
                val toolOutcomes = executeToolCalls(
                    calls = toolCalls,
                    conversationId = entry.conversationId,
                    providerId = provider.profile.id,
                    requestModel = requestModel,
                )
                requestMessages = requestMessages +
                    OutgoingChatMessage(
                        role = "assistant",
                        text = "",
                        toolCalls = toolOutcomes.map(ToolExecutionOutcome::toOutgoingToolCall),
                    ) +
                    toolOutcomes.map(ToolExecutionOutcome::toToolMessage)
                onPhaseChanged(ChatExecutionPhase.GENERATING)
            }
            onPhaseChanged(ChatExecutionPhase.FINALIZING)
            effectiveSearchContexts.webSearchContext?.toVisibleSourcesMarkdown()?.takeIf { it.isNotBlank() }?.let {
                latestSnapshot = appendVisibleTextPart(requireNotNull(latestSnapshot), it)
                chatRepository.replaceMessagePartsFromSnapshot(nextAssistantId, requireNotNull(latestSnapshot))
            }
            if (agentContext != null) {
                lifecycleCoordinator.serialized {
                    val snapshot = requireNotNull(latestSnapshot)
                    latestSnapshot = agentSourcePartWriter?.persist(
                        nextAssistantId,
                        snapshot,
                        agentContext,
                        onPrepared = { latestSnapshot = it },
                    )
                        ?: sanitizeAgentCitationMarkers(snapshot).also {
                            chatRepository.replaceMessagePartsFromSnapshot(nextAssistantId, it)
                        }
                }
            }
            if (wikiContext != null) {
                lifecycleCoordinator.serialized {
                    val snapshot = requireNotNull(latestSnapshot)
                    latestSnapshot = wikiSourcePartWriter?.persist(
                        nextAssistantId,
                        snapshot,
                        wikiContext,
                        onPrepared = { latestSnapshot = it },
                    )
                        ?: snapshot.removeWikiCitationTokensForTerminal().also {
                            chatRepository.replaceMessagePartsFromSnapshot(nextAssistantId, it)
                        }
                }
            }
            if (projectContext != null) {
                lifecycleCoordinator.serialized {
                    latestSnapshot = persistProjectTerminal(
                        assistantMessageId = nextAssistantId,
                        executionId = entry.id,
                        snapshot = requireNotNull(latestSnapshot),
                        context = requireNotNull(projectContext),
                    )
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
                            val sanitized = sanitizeTerminalSnapshot(
                                snapshot = cancelledSnapshot,
                                agentContext = agentContext,
                                wikiContext = wikiContext,
                                projectContext = null,
                            )
                            if (agentContext != null && sanitized.hasAgentSources()) {
                                agentSourcePartWriter?.persist(id, sanitized, agentContext)
                                    ?: chatRepository.replaceMessagePartsFromSnapshot(id, sanitized)
                            } else {
                                chatRepository.replaceMessagePartsFromSnapshot(id, sanitized)
                            }
                            if (projectContext != null) {
                                persistProjectTerminal(id, entry.id, sanitized, requireNotNull(projectContext))
                            }
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
            if (assistantId != null) {
                val failedSnapshot = if (streamCompleted) {
                    latestSnapshot
                } else {
                    accumulator?.snapshot() ?: latestSnapshot
                }
                failedSnapshot?.let {
                    val sanitized = sanitizeTerminalSnapshot(
                        snapshot = it,
                        agentContext = agentContext,
                        wikiContext = wikiContext,
                        projectContext = null,
                    )
                    if (agentContext != null && sanitized.hasAgentSources()) {
                        agentSourcePartWriter?.persist(failedAssistantId, sanitized, agentContext)
                            ?: chatRepository.replaceMessagePartsFromSnapshot(failedAssistantId, sanitized)
                    } else {
                        chatRepository.replaceMessagePartsFromSnapshot(failedAssistantId, sanitized)
                    }
                    if (projectContext != null) {
                        persistProjectTerminal(
                            failedAssistantId,
                            entry.id,
                            sanitized,
                            requireNotNull(projectContext),
                        )
                    }
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
                retryableInterruption = isRetryableTransportFailure(error),
            )
        }
    }

    /**
     * 执行一次响应里的全部工具调用。
     * 只执行认识的工具；不认识的记日志并作为失败回执交给模型，不中断整次发送。
     */
    private suspend fun executeToolCalls(
        calls: List<CollectedToolCall>,
        conversationId: String,
        providerId: String?,
        requestModel: String,
    ): List<ToolExecutionOutcome> = calls.map { call ->
        if (call.name == IMAGE_GENERATE_TOOL_NAME) {
            executeImageGenerateTool(call, conversationId, providerId, requestModel)
        } else {
            val displayName = call.name.ifBlank { "(空)" }
            toolCallLogger("未知工具调用：name=$displayName id=${call.id}")
            ToolExecutionOutcome(
                call = call,
                receipt = "工具 $displayName 不存在，本应用只支持 $IMAGE_GENERATE_TOOL_NAME。请直接用文字回答用户。",
            )
        }
    }

    /** 执行 `image_generate`：解析参数 → 远程生图 → 落盘 → 插入会话图片消息。 */
    private suspend fun executeImageGenerateTool(
        call: CollectedToolCall,
        conversationId: String,
        providerId: String?,
        requestModel: String,
    ): ToolExecutionOutcome {
        val tool = imageGenerateTool
        val fileWriter = generatedImageFileWriter
        if (tool == null || fileWriter == null || !tool.isAvailable()) {
            return ToolExecutionOutcome(
                call = call,
                receipt = "生图工具当前不可用（Mac 未连接或未完成配对），无法生成图片。请用文字向用户说明，不要重复调用。",
            )
        }
        val params = parseImageToolArguments(call.argumentsJson).getOrElse { error ->
            return ToolExecutionOutcome(
                call = call,
                receipt = "image_generate 参数错误：${error.message ?: "参数无效"}。请修正参数后重试，或改用文字回答。",
            )
        }.params
        // 一次生图可能要 1~3 分钟，超时/离线都只作为工具回执，不让整次发送失败。
        val result = try {
            tool.generate(params)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            toolCallLogger("image_generate 执行失败：${error::class.java.name} ${error.message.orEmpty()}")
            return ToolExecutionOutcome(
                call = call,
                receipt = "image_generate 执行失败：${error.message?.takeIf { it.isNotBlank() } ?: "未知错误"}。请用文字向用户说明失败原因，不要重复调用。",
            )
        }
        val savedFile = try {
            fileWriter.write(result.data, result.mimeType)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            toolCallLogger("image_generate 结果落盘失败：${error.message.orEmpty()}")
            return ToolExecutionOutcome(
                call = call,
                receipt = "图片已生成但保存到会话失败（${error.message?.takeIf { it.isNotBlank() } ?: "未知错误"}）。请用文字向用户说明失败原因。",
            )
        }
        return try {
            persistGeneratedImageMessage(
                call = call,
                result = result,
                savedFile = savedFile,
                conversationId = conversationId,
                providerId = providerId,
                requestModel = requestModel,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            toolCallLogger("生成图片消息写入失败：${error.message.orEmpty()}")
            ToolExecutionOutcome(
                call = call,
                receipt = "图片已生成但写入会话失败（${error.message?.takeIf { it.isNotBlank() } ?: "未知错误"}）。请用文字向用户说明。",
            )
        }
    }

    /**
     * 把生成结果插入为一条 ASSISTANT 消息：content 是历史友好的纯文本说明，
     * 图片走 attachment（ChatScreen 复用图片渲染与「保存图片」），
     * TOOL_CALL / TOOL_RESULT 过程 part 复用到同一条消息的 part 表里。
     */
    private suspend fun persistGeneratedImageMessage(
        call: CollectedToolCall,
        result: com.harnessapk.remote.ImageGenerateResult,
        savedFile: GeneratedImageFile,
        conversationId: String,
        providerId: String?,
        requestModel: String,
    ): ToolExecutionOutcome {
        val duration = result.durationLabel()
        val messageText = "[已生成图片：${result.prompt.trim().take(MAX_IMAGE_PROMPT_PREVIEW_CHARS)}" +
            "（${result.width}×${result.height}，${result.quality}，${result.model}，耗时 $duration）]"
        val receipt = "图片已生成并展示给用户（${result.width}×${result.height}，质量 ${result.quality}，" +
            "模型 ${result.model}，耗时 $duration）。请用一句简短的话告知用户图片已生成，不要重复调用。"
        chatRepository.insertAssistantImageMessage(
            conversationId = conversationId,
            providerId = providerId,
            model = requestModel,
            content = messageText,
            image = savedFile,
            parts = listOf(
                UiMessagePartDraft(
                    index = 0,
                    type = UiMessagePartType.TEXT,
                    content = messageText,
                    metadata = emptyMap(),
                    stable = true,
                ),
                UiMessagePartDraft(
                    index = 1,
                    type = UiMessagePartType.TOOL_CALL,
                    content = call.argumentsJson,
                    metadata = mapOf("id" to call.id, "name" to IMAGE_GENERATE_TOOL_NAME),
                    stable = true,
                ),
                UiMessagePartDraft(
                    index = 2,
                    type = UiMessagePartType.TOOL_RESULT,
                    content = receipt,
                    metadata = mapOf("toolCallId" to call.id),
                    stable = true,
                ),
            ),
        )
        return ToolExecutionOutcome(call = call, receipt = receipt)
    }

    private suspend fun persistProjectTerminal(
        assistantMessageId: String,
        executionId: String,
        snapshot: StreamingMessageSnapshot,
        context: ProjectRuntimeContext,
    ): StreamingMessageSnapshot {
        val verification = verifyProjectSnapshot(snapshot, context)
        val status = if (verification.unknownTokens.isEmpty()) "PASSED" else "REJECTED_UNKNOWN_TOKENS"
        return projectSourcePartWriter?.persistTerminal(
            assistantMessageId = assistantMessageId,
            executionId = executionId,
            snapshot = verification.snapshot,
            evidence = context.evidence,
            validTokens = verification.validTokens.toSet(),
            status = status,
            unknownTokens = verification.unknownTokens,
        ) ?: verification.snapshot.also { sanitized ->
            // Safe fallback ordering: visible sanitized content is durable before the audit can say it passed.
            chatRepository.replaceMessagePartsFromSnapshot(assistantMessageId, sanitized)
            projectCitationAuditWriter(executionId, status, verification.unknownTokens)
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

    /** 单次工具调用的执行结果：[receipt] 作为 tool 角色消息回给模型。 */
    private data class ToolExecutionOutcome(
        val call: CollectedToolCall,
        val receipt: String,
    ) {
        fun toOutgoingToolCall(): OutgoingToolCall = OutgoingToolCall(
            id = call.id,
            name = call.name.ifBlank { UNKNOWN_TOOL_NAME },
            arguments = call.argumentsJson,
        )

        fun toToolMessage(): OutgoingChatMessage = OutgoingChatMessage(
            role = "tool",
            text = receipt,
            toolCallId = call.id,
        )
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_IMAGE_TOOL_ROUNDS = 2
        private const val TOOL_CHOICE_AUTO = "auto"
        private const val UNKNOWN_TOOL_NAME = "unknown_tool"
        private const val MAX_IMAGE_PROMPT_PREVIEW_CHARS = 80
        private const val TAG = "SendMessageUseCase"
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

internal suspend fun assembleAgentContextForConversation(
    agentId: String?,
    agentVersion: Int?,
    request: AgentContextRequest,
    assembler: suspend (AgentContextRequest) -> AgentRuntimeContext?,
): AgentRuntimeContext? {
    if (agentId.isNullOrBlank() || agentVersion == null) return null
    return assembler(request.copy(agentId = agentId, version = agentVersion))
}

internal fun agentContextRequestForSend(
    query: String,
    conversationMemory: String,
    sessionContext: SessionRequestContext?,
): AgentContextRequest = AgentContextRequest(
    agentId = "",
    version = 0,
    query = query,
    conversationMemory = conversationMemory,
    projectContext = sessionContext?.projectContext.orEmpty(),
    sessionContext = sessionContext?.toAgentSessionContext().orEmpty(),
)

internal fun sessionContextOutsideAgentPrompt(
    sessionContext: SessionRequestContext?,
    agentContext: AgentRuntimeContext?,
): SessionRequestContext? = if (agentContext == null) sessionContext else null

internal fun requestBaseMessages(
    compression: ContextCompressionResult,
    agentContext: AgentRuntimeContext?,
): List<OutgoingChatMessage> {
    if (agentContext == null) return compression.messages
    val first = compression.messages.firstOrNull()
    return if (
        first?.role == "system" &&
        first.text.startsWith("以下是本机保存的早期对话记忆。它由 App 自动压缩生成")
    ) {
        compression.messages.drop(1)
    } else {
        compression.messages
    }
}

private fun SessionRequestContext.toAgentSessionContext(): String = buildString {
    finalPrompt.trim().takeIf(String::isNotBlank)?.let { appendLine("会话目标：$it") }
    projectName?.trim()?.takeIf(String::isNotBlank)?.let { appendLine("当前项目：$it") }
    deliverableTitle?.trim()?.takeIf(String::isNotBlank)?.let { appendLine("当前 Markdown 沉淀：$it") }
    deliverableMarkdown.trim().takeIf(String::isNotBlank)?.let {
        appendLine("当前 Markdown 内容：")
        appendLine(it)
    }
}.trim()

internal fun sanitizeAgentSnapshotIfNeeded(
    snapshot: StreamingMessageSnapshot,
    agentContext: AgentRuntimeContext?,
): StreamingMessageSnapshot = if (agentContext == null) snapshot else sanitizeAgentCitationMarkers(snapshot)

internal fun sanitizeTerminalSnapshot(
    snapshot: StreamingMessageSnapshot,
    agentContext: AgentRuntimeContext?,
    wikiContext: WikiRuntimeContext?,
    projectContext: ProjectRuntimeContext? = null,
): StreamingMessageSnapshot {
    val agentSanitized = sanitizeAgentSnapshotIfNeeded(snapshot, agentContext)
    val wikiSanitized = if (wikiContext == null) agentSanitized else agentSanitized.removeWikiCitationTokensForTerminal()
    return if (projectContext == null) wikiSanitized else sanitizeProjectSnapshot(wikiSanitized, projectContext)
}

internal fun sanitizeProjectSnapshot(
    snapshot: StreamingMessageSnapshot,
    context: ProjectRuntimeContext,
): StreamingMessageSnapshot = verifyProjectSnapshot(snapshot, context).snapshot

internal data class ProjectSnapshotVerification(
    val snapshot: StreamingMessageSnapshot,
    val validTokens: List<String>,
    val unknownTokens: List<String>,
)

internal fun verifyProjectSnapshot(
    snapshot: StreamingMessageSnapshot,
    context: ProjectRuntimeContext,
): ProjectSnapshotVerification {
    val allowed = context.evidence.mapTo(linkedSetOf()) { it.token }
    val valid = linkedSetOf<String>()
    val unknown = linkedSetOf<String>()
    val sanitized = snapshot.copy(
        parts = snapshot.parts.map { part ->
            if (part.type == UiMessagePartType.TEXT) {
                val result = ProjectCitationVerifier.verify(part.content, allowed)
                valid += result.validTokens
                unknown += result.unknownTokens
                part.copy(content = result.text)
            } else {
                part
            }
        },
    )
    return ProjectSnapshotVerification(sanitized, valid.toList(), unknown.toList())
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

/**
 * 把工具能力说明合进已有 system 消息；没有 system 消息则新建一条。
 * [instruction] 为空时原样返回：工具不可用时不向模型承诺任何能力。
 */
internal fun appendSystemInstruction(
    messages: List<OutgoingChatMessage>,
    instruction: String?,
): List<OutgoingChatMessage> {
    val text = instruction?.trim().orEmpty()
    if (text.isEmpty()) return messages
    val systemIndex = messages.indexOfFirst { it.role.equals("system", ignoreCase = true) }
    if (systemIndex < 0) return listOf(OutgoingChatMessage(role = "system", text = text)) + messages
    return messages.mapIndexed { index, message ->
        if (index == systemIndex) message.copy(text = message.text.trimEnd() + "\n\n" + text) else message
    }
}

private fun com.harnessapk.remote.ImageGenerateResult.durationLabel(): String =
    String.format(Locale.US, "%.1fs", durationMs / 1000.0)

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

private fun StreamingMessageSnapshot.hasAgentSources(): Boolean =
    parts.any { it.type == UiMessagePartType.AGENT_SOURCES }

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

internal fun isRetryableTransportFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { cause -> cause is IOException }

private data class ImagePayload(
    val bytes: ByteArray,
    val mimeType: String,
)

data class ChatExecutionResult(
    val status: ChatExecutionStatus,
    val assistantMessageId: String?,
    val errorMessage: String?,
    val retryableInterruption: Boolean = false,
)
