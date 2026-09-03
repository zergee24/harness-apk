package com.harnessapk.common

import android.content.Context
import android.os.StatFs
import android.speech.SpeechRecognizer
import androidx.room.Room
import androidx.room.withTransaction
import com.harnessapk.activity.ActivityRepository
import com.harnessapk.activity.RoomActivityFeedSource
import com.harnessapk.agent.AgentRepository
import com.harnessapk.agent.AgentContextAssembler
import com.harnessapk.agent.AgentLifecycleCoordinator
import com.harnessapk.agent.AgentRelationshipMemoryProvider
import com.harnessapk.agent.AgentTransactionRunner
import com.harnessapk.agentmemory.AgentMemoryRepository
import com.harnessapk.agentmemory.AgentMemoryTransactionRunner
import com.harnessapk.agentmemory.AgentMemoryAcceptedBatchMerger
import com.harnessapk.agentmemory.AgentMemoryCandidatePolicy
import com.harnessapk.agentmemory.AgentMemoryCoordinator
import com.harnessapk.agentmemory.AgentMemoryExtractionUseCase
import com.harnessapk.agentmemory.AgentMemoryPolicy
import com.harnessapk.agentmemory.LlmAgentMemoryCandidateGenerator
import com.harnessapk.agentmemory.MarkdownAgentMemoryProjectFactSource
import com.harnessapk.agentmemory.MAX_AGENT_MEMORY_PROJECT_CONTEXT_CHARS
import com.harnessapk.agentmemory.RepositoryAgentMemoryExtractionSource
import com.harnessapk.agentmemory.RepositoryAgentMemoryGenerationProviderResolver
import com.harnessapk.agentmemory.openAiAgentMemoryCompletionGateway
import com.harnessapk.agent.ConversationIdentityRepository
import com.harnessapk.BuildConfig
import com.harnessapk.chat.ChatImageStore
import com.harnessapk.chat.AgentSourcePartWriter
import com.harnessapk.chat.ChatRepository
import com.harnessapk.chat.ChatExecutionCoordinator
import com.harnessapk.chat.ChatExecutionRepository
import com.harnessapk.chat.ChatSendController
import com.harnessapk.chat.ChatSendRecoveryManager
import com.harnessapk.chat.ChatSendRecoveryStore
import com.harnessapk.chat.ChatExecutionService
import com.harnessapk.chat.ChatExecutionPowerGuard
import com.harnessapk.chat.ManualContextCompressionUseCase
import com.harnessapk.chat.NewConversationUseCase
import com.harnessapk.chat.ConversationWikiDefaultsCopier
import com.harnessapk.chat.ConversationWikiScopeReplacer
import com.harnessapk.chat.ConversationDraftStore
import com.harnessapk.chat.QueuedAttachmentStore
import com.harnessapk.chat.SendMessageUseCase
import com.harnessapk.chat.ProjectSourcePartWriter
import com.harnessapk.chat.ProjectTerminalTransactionRunner
import com.harnessapk.chat.WikiSourcePartWriter
import com.harnessapk.chat.decodeExecutionRequestContext
import com.harnessapk.chat.encodeExecutionRequestContext
import com.harnessapk.chat.assembleAgentContextForConversation
import com.harnessapk.chat.webSearchAllowedForAgentConversation
import com.harnessapk.capture.CaptureDraftRepository
import com.harnessapk.capture.CaptureImportCoordinator
import com.harnessapk.capture.CaptureStagingStore
import com.harnessapk.git.GitCredentialStore
import com.harnessapk.git.JGitEngine
import com.harnessapk.network.OpenAiCompatibleClient
import com.harnessapk.project.FileProjectRepository
import com.harnessapk.project.DeleteProjectUseCase
import com.harnessapk.project.ProjectWorkspaceGatewayAdapter
import com.harnessapk.projectsearch.ProjectContextAssembler
import com.harnessapk.projectsearch.ProjectEvidenceCapture
import com.harnessapk.projectsearch.ProjectEvidenceSnapshotRepository
import com.harnessapk.projectsearch.ProjectEvidenceStore
import com.harnessapk.projectsearch.ProjectEvidenceLiveVerifier
import com.harnessapk.projectsearch.ProjectRetrievalResult
import com.harnessapk.projectsearch.ProjectRetrievalStatus
import com.harnessapk.projectsearch.ProjectRuntimeContext
import com.harnessapk.projectsearch.ProjectSourceType
import com.harnessapk.projectsearch.RoomProjectRetrievalGateway
import com.harnessapk.projectsearch.RoomProjectMarkdownIndexer
import com.harnessapk.projectsearch.ProjectIndexWorker
import com.harnessapk.projectsearch.RoomProjectRunEvidenceIndexer
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.provider.ProviderCapabilityCatalogClient
import com.harnessapk.provider.parseProviderCapabilityCatalogJson
import com.harnessapk.security.ApiKeyCipher
import com.harnessapk.security.ResilientStringCipher
import com.harnessapk.configpackage.ConfigPackageApplier
import com.harnessapk.voice.VoiceCredentialStore
import com.harnessapk.search.LocalSearchRepository
import com.harnessapk.session.PromptOptimizerUseCase
import com.harnessapk.session.MarkdownNotebookRepository
import com.harnessapk.session.MarkdownDraftCoordinator
import com.harnessapk.session.MarkdownDraftApplyCoordinator
import com.harnessapk.session.MarkdownDraftApplyStore
import com.harnessapk.session.MarkdownDraftOriginType
import com.harnessapk.session.MarkdownDraftStore
import com.harnessapk.session.PersistedMarkdownDraft
import com.harnessapk.session.WikiMarkdownContextRepository
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.AppSettingsStore
import com.harnessapk.storage.ContextFactDedupeEntity
import com.harnessapk.wiki.InstalledWikiContentStore
import com.harnessapk.wiki.ConversationWikiRepository
import com.harnessapk.wiki.ConversationWikiTransactionRunner
import com.harnessapk.wiki.WikiPackageImportCoordinator
import com.harnessapk.wiki.WikiPackageReader
import com.harnessapk.wiki.WikiContextAssembler
import com.harnessapk.wiki.WikiQueryGateway
import com.harnessapk.wiki.WikiRetriever
import com.harnessapk.wiki.WikiRouter
import com.harnessapk.wiki.WikiRepository
import com.harnessapk.wiki.WikiTransactionRunner
import com.harnessapk.wiki.WikiTurnAlias
import com.harnessapk.wiki.WikiVersionReferenceChecker
import com.harnessapk.wiki.WikiVersionHealthReporter
import com.harnessapk.updater.ApkInstaller
import com.harnessapk.updater.UpdateRepository
import com.harnessapk.updater.UpdateDownloadCoordinator
import com.harnessapk.websearch.JinaWebSearchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.harnessapk.remote.AliyunPushManager
import com.harnessapk.remote.RemoteEnrollmentClient
import com.harnessapk.remote.RemoteBindingRepository
import com.harnessapk.remote.RemoteApprovalCommandCoordinator
import com.harnessapk.remote.RemoteCommandOutbox
import com.harnessapk.remote.RemoteRunLauncher
import com.harnessapk.remote.RemoteRunCommandCoordinator
import com.harnessapk.remote.RemoteTransport
import com.harnessapk.remote.RoomRemoteCommandStore
import com.harnessapk.remote.RoomRemoteRunCommandState
import com.harnessapk.remote.RoomApprovalResponseWriter
import com.harnessapk.remote.RemoteEventReducer
import com.harnessapk.remote.RemoteSyncCoordinator
import com.harnessapk.remote.RoomRemoteSyncState
import com.harnessapk.remote.RemoteProfileStore
import com.harnessapk.remote.RemoteRepository
import com.harnessapk.ui.HomeModeStore

class AppContainer(
    context: Context,
    applicationScopeOverride: CoroutineScope? = null,
    scheduleProjectIndexWarmup: Boolean = true,
) {
    private val appContext = context.applicationContext
    val dispatchers = AppDispatchers()
    val applicationScope = applicationScopeOverride
        ?: CoroutineScope(SupervisorJob() + dispatchers.io)
    val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "harness-apk.db",
    ).addMigrations(
        AppDatabase.MIGRATION_1_2,
        AppDatabase.MIGRATION_2_3,
        AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5,
        AppDatabase.MIGRATION_5_6,
        AppDatabase.MIGRATION_6_7,
        AppDatabase.MIGRATION_7_8,
        AppDatabase.MIGRATION_8_9,
        AppDatabase.MIGRATION_9_10,
        AppDatabase.MIGRATION_10_11,
        AppDatabase.MIGRATION_11_12,
        AppDatabase.MIGRATION_12_13,
        AppDatabase.MIGRATION_13_14,
        AppDatabase.MIGRATION_14_15,
        AppDatabase.MIGRATION_15_16,
        AppDatabase.MIGRATION_16_17,
        AppDatabase.MIGRATION_17_18,
        AppDatabase.MIGRATION_18_19,
        AppDatabase.MIGRATION_19_20,
        AppDatabase.MIGRATION_20_21,
        AppDatabase.MIGRATION_21_22,
        AppDatabase.MIGRATION_22_23,
        AppDatabase.MIGRATION_23_24,
    ).addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK).build()
    // 卓易通等容器可能没有可用的 AndroidKeyStore；三个密钥库统一走 Keystore 优先 + 软件回退
    val apiKeyCipher = ResilientStringCipher(appContext, "harness_apk_provider_keys")
    val settingsStore = AppSettingsStore(appContext)
    val voiceCredentialStore = VoiceCredentialStore(
        appContext,
        ResilientStringCipher(appContext, "harness_apk_voice_keys"),
    )
    val gitCredentialStore = GitCredentialStore(appContext, apiKeyCipher)
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    val chatHttpClient = AppHttpClients.chat()
    val updateHttpClient = AppHttpClients.updates()
    val webSearchHttpClient = AppHttpClients.webSearch()
    val providerCatalogHttpClient = AppHttpClients.providerCatalog()
    val remoteHttpClient = AppHttpClients.remote()
    val remoteProfileStore = RemoteProfileStore(appContext, ResilientStringCipher(appContext, "harness_apk_remote_keys"))
    val homeModeStore = HomeModeStore(appContext)
    val remoteEnrollmentClient = RemoteEnrollmentClient(remoteHttpClient)
    val aliyunPushManager = AliyunPushManager(appContext)
    val remoteRepository = RemoteRepository(remoteProfileStore, remoteHttpClient, applicationScope)
    val remoteBindingRepository = RemoteBindingRepository(database.remoteDao())
    val remoteCommandOutbox = RemoteCommandOutbox(RoomRemoteCommandStore(database.remoteDao()))
    val remoteApprovalCommandCoordinator = RemoteApprovalCommandCoordinator(
        remoteCommandOutbox,
        RoomApprovalResponseWriter(database.remoteDao()),
    )
    val remoteRunLauncher = RemoteRunLauncher(database, remoteCommandOutbox)
    val remoteRunCommandCoordinator = RemoteRunCommandCoordinator(
        remoteCommandOutbox,
        RoomRemoteRunCommandState(database.remoteDao()),
    )
    val remoteTransport = RemoteTransport(remoteCommandOutbox, remoteRepository)
    val remoteEventReducer = RemoteEventReducer(database)
    val remoteSyncCoordinator = RemoteSyncCoordinator(
        RoomRemoteSyncState(database, remoteEventReducer),
        remoteRepository,
    )
    val activityRepository = ActivityRepository(RoomActivityFeedSource(database))
    init {
        remoteRepository.attachSyncCoordinator(remoteSyncCoordinator)
        remoteRepository.attachConnectedHandler { _, _ -> remoteTransport.flush() }
    }
    val gitEngine = JGitEngine()
    val providerRepository = ProviderRepository(
        dao = database.providerProfileDao(),
        cipher = apiKeyCipher,
        timeProvider = SystemTimeProvider,
    )
    val configPackageApplier = ConfigPackageApplier(
        providerRepository = providerRepository,
        voiceCredentialStore = voiceCredentialStore,
        settingsStore = settingsStore,
        systemRecognitionAvailable = {
            runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)
        },
    )
    val providerCapabilityCatalogClient = ProviderCapabilityCatalogClient(providerCatalogHttpClient, json)
    val chatRepository = ChatRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        messagePartDao = database.messagePartDao(),
        attachmentDao = database.messageAttachmentDao(),
        memoryDao = database.conversationMemoryDao(),
        timeProvider = SystemTimeProvider,
    )
    private val agentLifecycleCoordinator = AgentLifecycleCoordinator()
    val agentRepository = AgentRepository(
        filesDir = appContext.filesDir,
        cacheDir = appContext.cacheDir,
        dao = database.agentDao(),
        conversationDao = database.conversationDao(),
        lifecycleCoordinator = agentLifecycleCoordinator,
        transactionRunner = AgentTransactionRunner { block ->
            database.withTransaction { block() }
        },
        timeProvider = SystemTimeProvider,
        ioDispatcher = dispatchers.io,
        privateInstallAvailableBytes = { StatFs(appContext.filesDir.absolutePath).availableBytes },
    )
    val agentMemoryRepository = AgentMemoryRepository(
        dao = database.agentMemoryDao(),
        transactionRunner = AgentMemoryTransactionRunner { block ->
            database.withTransaction { block() }
        },
        timeProvider = SystemTimeProvider,
    )
    val conversationWikiRepository = ConversationWikiRepository(
        dao = database.conversationWikiDao(),
        transactionRunner = ConversationWikiTransactionRunner { block ->
            database.withTransaction { block() }
        },
        timeProvider = SystemTimeProvider,
    )
    val wikiMarkdownContextRepository = WikiMarkdownContextRepository(database.conversationWikiDao())
    val wikiRepository = WikiRepository(
        filesDir = appContext.filesDir,
        dao = database.wikiDao(),
        transactionRunner = WikiTransactionRunner { block ->
            database.withTransaction { block() }
        },
        timeProvider = SystemTimeProvider,
        ioDispatcher = dispatchers.io,
        privateInstallAvailableBytes = { StatFs(appContext.filesDir.absolutePath).availableBytes },
        referenceChecker = WikiVersionReferenceChecker(conversationWikiRepository::referenceCounts),
    )
    val wikiContentStore = InstalledWikiContentStore(
        filesDir = appContext.filesDir,
        wikiDao = database.wikiDao(),
        healthReporter = WikiVersionHealthReporter(wikiRepository::markInvalid),
        ioDispatcher = dispatchers.io,
    )
    val wikiQueryGateway = WikiQueryGateway(wikiContentStore)
    val wikiRouter = WikiRouter(wikiQueryGateway)
    val wikiRetriever = WikiRetriever(wikiQueryGateway)
    private val wikiContextAssembler = WikiContextAssembler(
        router = wikiRouter,
        retriever = wikiRetriever,
        aliasesProvider = {
            wikiRepository.observeWikis().first().map { wiki ->
                WikiTurnAlias(
                    wikiId = wiki.id,
                    title = wiki.title,
                )
            }
        },
        titleProvider = { ref -> wikiRepository.manifestFor(ref)?.title },
    )
    private val wikiSourcePartWriter = WikiSourcePartWriter(
        conversationWikiRepository = conversationWikiRepository,
        chatRepository = chatRepository,
        contentStore = wikiContentStore,
    )
    val wikiPackageImportCoordinator = WikiPackageImportCoordinator(
        cacheDir = appContext.cacheDir,
        inspectPackage = { archive, stagingDirectory -> WikiPackageReader(stagingDirectory).inspect(archive) },
        install = wikiRepository::install,
        isKnownPublisher = wikiRepository::isPublisherKnown,
        hasReadyVersion = wikiRepository::hasReadyVersion,
        ioDispatcher = dispatchers.io,
    )
    val conversationIdentityRepository = ConversationIdentityRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        agentDao = database.agentDao(),
        timeProvider = SystemTimeProvider,
        lifecycleCoordinator = agentLifecycleCoordinator,
    )
    private val agentContextAssembler = AgentContextAssembler(
        source = agentRepository,
        relationshipMemoryProvider = AgentRelationshipMemoryProvider(agentMemoryRepository::list),
    )
    val newConversationUseCase = NewConversationUseCase(
        chatRepository = chatRepository,
        identityRepository = conversationIdentityRepository,
        lifecycleCoordinator = agentLifecycleCoordinator,
        wikiDefaultsCopier = ConversationWikiDefaultsCopier(
            conversationWikiRepository::copyDefaultsToConversationInTransaction,
        ),
        wikiScopeReplacer = ConversationWikiScopeReplacer(
            conversationWikiRepository::replaceEnabledScopeInTransaction,
        ),
        transactionRunner = WikiTransactionRunner { block ->
            database.withTransaction { block() }
        },
    )
    val openAiClient = OpenAiCompatibleClient(chatHttpClient, json)
    val chatImageStore = ChatImageStore(appContext, chatHttpClient, dispatchers)
    val webSearchClient = JinaWebSearchClient(webSearchHttpClient)
    val queuedAttachmentStore = QueuedAttachmentStore(appContext)
    val conversationDraftStore = ConversationDraftStore(appContext, json)
    val localSearchRepository = LocalSearchRepository(database.localSearchDao(), dispatchers)
    val projectMarkdownIndexer = RoomProjectMarkdownIndexer(database.localSearchDao())
    val projectRepository = FileProjectRepository(
        rootDirectory = appContext.filesDir,
        timeProvider = SystemTimeProvider,
        onProjectUpsert = { project ->
            localSearchRepository.upsertProject(project)
            ProjectIndexWorker.enqueue(appContext, project.id)
        },
        onProjectDelete = { projectId ->
            ProjectIndexWorker.cancel(appContext, projectId)
        },
        onProjectContentChanged = { project -> ProjectIndexWorker.enqueue(appContext, project.id) },
    )
    private val localSearchWarmup = applicationScope.launch {
        val projects = projectRepository.listProjects()
        localSearchRepository.rebuildTokens(projects)
        if (scheduleProjectIndexWarmup) {
            projects.forEach { project -> ProjectIndexWorker.enqueue(appContext, project.id) }
        }
    }
    val captureDraftRepository = CaptureDraftRepository(appContext)
    val captureStagingStore = CaptureStagingStore(appContext)
    val captureImportCoordinator = CaptureImportCoordinator(
        repository = captureDraftRepository,
        stagingStore = captureStagingStore,
        chatImageStore = chatImageStore,
        conversationDraftStore = conversationDraftStore,
        projectRepository = projectRepository,
        dispatchers = dispatchers,
    )
    private val agentMemoryPolicy = AgentMemoryPolicy()
    val agentMemoryExtractionUseCase = AgentMemoryExtractionUseCase(
        source = RepositoryAgentMemoryExtractionSource(chatRepository),
        projectFactSource = MarkdownAgentMemoryProjectFactSource { projectId ->
            projectRepository.readProjectContextBounded(
                projectId = projectId,
                maxChars = MAX_AGENT_MEMORY_PROJECT_CONTEXT_CHARS,
            )
        },
        generator = LlmAgentMemoryCandidateGenerator(
            providerResolver = RepositoryAgentMemoryGenerationProviderResolver(providerRepository),
            completionGateway = openAiAgentMemoryCompletionGateway(openAiClient),
        ),
        policy = AgentMemoryCandidatePolicy(agentMemoryPolicy::evaluate),
        merger = AgentMemoryAcceptedBatchMerger(agentMemoryRepository::merge),
    )
    val agentMemoryCoordinator = AgentMemoryCoordinator(
        scope = applicationScope,
        completedRoundCount = chatRepository::completedAssistantTextCount,
        extract = agentMemoryExtractionUseCase::extract,
    )
    val deleteProjectUseCase = DeleteProjectUseCase(
        projectRepository = projectRepository,
        database = database,
    )
    val projectWorkspaceGateway = ProjectWorkspaceGatewayAdapter(projectRepository)
    private val roomProjectRetrievalGateway = RoomProjectRetrievalGateway(
        dao = database.projectSearchDao(),
        localSearchDao = database.localSearchDao(),
        messageDao = database.messageDao(),
        runEvidenceIndexer = RoomProjectRunEvidenceIndexer(
            remoteDao = database.remoteDao(),
            localSearchDao = database.localSearchDao(),
        ),
    )
    private val projectEvidenceSnapshotRepository = ProjectEvidenceSnapshotRepository(
        store = ProjectEvidenceStore { capture -> persistProjectEvidenceCapture(capture) },
        timeProvider = SystemTimeProvider::nowMillis,
        liveVerifier = ProjectEvidenceLiveVerifier { requestedProjectId, document ->
            if (document.sourceType !in setOf(ProjectSourceType.CONTEXT, ProjectSourceType.MARKDOWN)) {
                true
            } else {
                document.relativePath?.let { path ->
                    projectRepository.projectFileRevisionIsCurrent(
                        projectId = requestedProjectId,
                        relativePath = path,
                        expectedSha256 = document.sourceSha256,
                    )
                } ?: false
            }
        },
    )
    val promptOptimizerUseCase = PromptOptimizerUseCase(
        providerRepository = providerRepository,
        client = openAiClient,
        dispatchers = dispatchers,
    )
    val manualContextCompressionUseCase = ManualContextCompressionUseCase(
        chatRepository = chatRepository,
        timeProvider = SystemTimeProvider,
    )
    val sendMessageUseCase = SendMessageUseCase(
        context = appContext,
        chatRepository = chatRepository,
        providerRepository = providerRepository,
        client = openAiClient,
        dispatchers = dispatchers,
        remoteCapabilityCatalog = {
            settingsStore.providerCapabilityCatalogSnapshot.first().rawJson
                ?.let { rawJson -> runCatching { parseProviderCapabilityCatalogJson(rawJson, json) }.getOrNull() }
        },
        agentContextProvider = { conversationId, request ->
            val conversation = chatRepository.conversation(conversationId)
            val agentId = conversation?.agentId
            val agentVersion = conversation?.agentVersion
            assembleAgentContextForConversation(
                agentId = agentId,
                agentVersion = agentVersion,
                request = request,
                assembler = agentContextAssembler::assemble,
            )
        },
        agentSourcePartWriter = AgentSourcePartWriter(
            dao = database.agentDao(),
            chatRepository = chatRepository,
            transactionRunner = AgentTransactionRunner { block -> database.withTransaction { block() } },
            lifecycleCoordinator = agentLifecycleCoordinator,
        ),
        wikiContextProvider = { _, query, scope -> wikiContextAssembler.assemble(query, scope) },
        wikiSourcePartWriter = wikiSourcePartWriter,
        projectContextProvider = { _, executionId, assistantMessageId, query, projectId ->
            val existingEvidence = database.projectSearchDao().evidenceForExecution(executionId)
            val existingRun = database.projectSearchDao().retrievalRunForExecution(executionId)
            if (existingRun != null) {
                if (existingEvidence.isEmpty()) {
                    null
                } else {
                    database.projectSearchDao().rebindEvidenceToMessage(executionId, assistantMessageId)
                    ProjectRuntimeContext(
                        retrievalRunId = existingRun.id,
                        evidence = existingEvidence,
                        systemContext = ProjectContextAssembler.assemble(existingEvidence),
                    )
                }
            } else {
                val result = runCatching { roomProjectRetrievalGateway.retrieve(projectId, query) }
                    .getOrElse { ProjectRetrievalResult(ProjectRetrievalStatus.FAILED, emptyList()) }
                projectEvidenceSnapshotRepository.capture(
                    executionId = executionId,
                    assistantMessageId = assistantMessageId,
                    projectId = projectId,
                    query = query,
                    result = result,
                )
            }
        },
        projectSourcePartWriter = ProjectSourcePartWriter(
            chatRepository = chatRepository,
            transactionRunner = ProjectTerminalTransactionRunner { block -> database.withTransaction { block() } },
            auditWriter = { executionId, status, unknownTokens ->
                database.projectSearchDao().updateCitationVerification(
                    executionId = executionId,
                    status = status,
                    unknownTokensJson = JsonArray(unknownTokens.map(::JsonPrimitive)).toString(),
                )
            },
        ),
        projectCitationAuditWriter = { executionId, status, unknownTokens ->
            database.projectSearchDao().updateCitationVerification(
                executionId = executionId,
                status = status,
                unknownTokensJson = JsonArray(unknownTokens.map(::JsonPrimitive)).toString(),
            )
        },
        lifecycleCoordinator = agentLifecycleCoordinator,
    )
    val chatExecutionRepository = ChatExecutionRepository(
        database = database,
        dao = database.chatExecutionEntryDao(),
        chatRepository = chatRepository,
        identityRepository = conversationIdentityRepository,
        timeProvider = SystemTimeProvider,
        lifecycleCoordinator = agentLifecycleCoordinator,
        wikiScopeSnapshotProvider = conversationWikiRepository::snapshotEnabled,
    )
    val chatSendRecoveryStore = ChatSendRecoveryStore()
    val chatExecutionCoordinator = ChatExecutionCoordinator(
        executionRepository = chatExecutionRepository,
        sendMessageUseCase = sendMessageUseCase,
        providerRepository = providerRepository,
        webSearchClient = webSearchClient,
        attachmentStore = queuedAttachmentStore,
        dispatchers = dispatchers,
        powerGuard = ChatExecutionPowerGuard(appContext),
        webSearchAllowed = { conversationId ->
            webSearchAllowedForAgentConversation(chatRepository.conversation(conversationId)?.agentId)
        },
        onWorkScheduled = { ChatExecutionService.start(appContext) },
        onReplyCompleted = agentMemoryCoordinator::onReplyCompleted,
    )
    val chatSendRecoveryManager = ChatSendRecoveryManager(
        scope = applicationScope,
        store = chatSendRecoveryStore,
        controller = ChatSendController(
            enqueue = chatExecutionCoordinator::enqueue,
            requestExists = { requestId -> chatExecutionRepository.entry(requestId) != null },
        ),
    )
    val markdownNotebookRepository = MarkdownNotebookRepository(
        chatRepository = chatRepository,
        linkDao = database.conversationMarkdownLinkDao(),
        draftDao = database.markdownChangeDraftDao(),
        timeProvider = SystemTimeProvider,
    )
    val markdownDraftCoordinator = MarkdownDraftCoordinator(
        store = object : MarkdownDraftStore {
            override suspend fun find(
                originType: MarkdownDraftOriginType,
                sourceId: String,
            ): PersistedMarkdownDraft? {
                val origin = database.projectSearchDao()
                    .draftOriginForSource(originType.name, sourceId) ?: return null
                val draft = database.markdownChangeDraftDao().findDraft(origin.draftId) ?: return null
                return PersistedMarkdownDraft(
                    draft = draft,
                    items = database.markdownChangeDraftDao().listItems(origin.draftId),
                    origin = origin,
                )
            }

            override suspend fun save(record: PersistedMarkdownDraft) {
                database.withTransaction {
                    database.markdownChangeDraftDao().upsertDraft(record.draft)
                    database.markdownChangeDraftDao().replaceItems(record.draft.id, record.items)
                    val projectDao = database.projectSearchDao()
                    val existingOrigin = projectDao.draftOriginForSource(
                        record.origin.sourceType,
                        record.origin.sourceId,
                    )
                    if (existingOrigin == null) {
                        projectDao.insertDraftOrigin(record.origin)
                    } else {
                        require(existingOrigin.draftId == record.draft.id)
                        require(existingOrigin.sourceSha256 == record.origin.sourceSha256)
                    }
                    record.contextFacts.forEach { fact ->
                        projectDao.upsertContextFact(
                            ContextFactDedupeEntity(
                                projectId = record.draft.projectId,
                                semanticKey = fact.semanticKey,
                                evidenceHash = fact.evidenceHash,
                                sourceId = record.origin.sourceId,
                                status = "PENDING",
                                updatedAt = record.draft.updatedAt,
                            ),
                        )
                    }
                }
            }
        },
        timeProvider = SystemTimeProvider::nowMillis,
    )
    val markdownDraftApplyCoordinator = MarkdownDraftApplyCoordinator(
        store = object : MarkdownDraftApplyStore {
            private val dao get() = database.markdownChangeDraftDao()

            override suspend fun findDraft(draftId: String) = dao.findDraft(draftId)
            override suspend fun listItems(draftId: String) = dao.listItems(draftId)
            override suspend fun claim(draftId: String, updatedAt: Long) = dao.claimForApply(draftId, updatedAt) == 1
            override suspend fun updateItem(itemId: String, status: String, errorMessage: String?) =
                dao.updateItemApplyResult(itemId, status, errorMessage)
            override suspend fun updateDraft(draft: com.harnessapk.storage.MarkdownChangeDraftEntity) =
                dao.updateDraft(draft)
        },
        gateway = projectWorkspaceGateway,
        timeProvider = SystemTimeProvider::nowMillis,
        markContextFacts = ::markContextFacts,
    )
    val updateRepository = UpdateRepository(
        okHttpClient = updateHttpClient,
        json = json,
        manifestUrl = BuildConfig.UPDATE_MANIFEST_URL,
        currentVersionCode = BuildConfig.VERSION_CODE,
        cacheDir = appContext.cacheDir,
    )
    val updateDownloadCoordinator = UpdateDownloadCoordinator(
        downloader = updateRepository,
        ioDispatcher = dispatchers.io,
    )
    val apkInstaller = ApkInstaller(appContext)

    private suspend fun persistProjectEvidenceCapture(capture: ProjectEvidenceCapture) {
        database.withTransaction {
            val projectDao = database.projectSearchDao()
            projectDao.upsertRetrievalRun(capture.run)
            if (capture.evidence.isNotEmpty()) projectDao.insertEvidenceSnapshots(capture.evidence)
            val executionDao = database.chatExecutionEntryDao()
            val execution = executionDao.findById(capture.run.executionId) ?: return@withTransaction
            val requestContext = decodeExecutionRequestContext(execution.requestContextJson)
            val snapshot = requestContext.contextSnapshot ?: return@withTransaction
            val v3 = snapshot.copy(
                schemaVersion = 3,
                retrievalRunId = capture.run.id,
                projectEvidenceIds = capture.evidence.map { it.id },
            )
            executionDao.update(
                execution.copy(
                    requestContextJson = encodeExecutionRequestContext(requestContext.copy(contextSnapshot = v3)),
                    updatedAt = SystemTimeProvider.nowMillis(),
                ),
            )
        }
    }

    /**
     * 发出发生文件写回的项目 id，供项目工作台（Git/文件视图）静默刷新。
     * 使用无 replay 的 SharedFlow：只在有活跃收集者时投递，避免切项目时回放旧值。
     */
    val projectContentInvalidation = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * 记录每个项目"本轮会话已评审并 apply 的写回文件相对路径"，作为 Git 提交白名单默认选中来源。
     * key = 项目 id，value = 相对项目根目录的路径列表。
     */
    val projectAppliedPaths = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    private val activeMarkdownPlanningDraftIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun beginMarkdownDraftPlanning(draftId: String): Boolean = activeMarkdownPlanningDraftIds.add(draftId)

    fun finishMarkdownDraftPlanning(draftId: String) {
        activeMarkdownPlanningDraftIds.remove(draftId)
    }

    fun isMarkdownDraftPlanning(draftId: String): Boolean = draftId in activeMarkdownPlanningDraftIds

    suspend fun markContextFacts(draftId: String, status: String) {
        require(status == "APPLIED" || status == "DISMISSED")
        val origin = database.projectSearchDao().draftOrigin(draftId) ?: return
        database.projectSearchDao().updateContextFactStatusForSource(
            sourceId = origin.sourceId,
            status = status,
            updatedAt = SystemTimeProvider.nowMillis(),
        )
    }
}
