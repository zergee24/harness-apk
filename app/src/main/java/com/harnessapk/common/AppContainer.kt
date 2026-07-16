package com.harnessapk.common

import android.content.Context
import androidx.room.Room
import com.harnessapk.BuildConfig
import com.harnessapk.chat.ChatImageStore
import com.harnessapk.chat.ChatRepository
import com.harnessapk.chat.ChatExecutionCoordinator
import com.harnessapk.chat.ChatExecutionRepository
import com.harnessapk.chat.ChatExecutionService
import com.harnessapk.chat.ManualContextCompressionUseCase
import com.harnessapk.chat.QueuedAttachmentStore
import com.harnessapk.chat.SendMessageUseCase
import com.harnessapk.git.GitCredentialStore
import com.harnessapk.git.JGitEngine
import com.harnessapk.network.OpenAiCompatibleClient
import com.harnessapk.project.FileProjectRepository
import com.harnessapk.project.ProjectWorkspaceGatewayAdapter
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.provider.ProviderCapabilityCatalogClient
import com.harnessapk.provider.parseProviderCapabilityCatalogJson
import com.harnessapk.security.ApiKeyCipher
import com.harnessapk.session.PromptOptimizerUseCase
import com.harnessapk.session.MarkdownNotebookRepository
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.AppSettingsStore
import com.harnessapk.updater.ApkInstaller
import com.harnessapk.updater.UpdateRepository
import com.harnessapk.updater.UpdateDownloadCoordinator
import com.harnessapk.websearch.JinaWebSearchClient
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val dispatchers = AppDispatchers()
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
    ).build()
    val apiKeyCipher = ApiKeyCipher()
    val settingsStore = AppSettingsStore(appContext)
    val gitCredentialStore = GitCredentialStore(appContext, apiKeyCipher)
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    val chatHttpClient = AppHttpClients.chat()
    val updateHttpClient = AppHttpClients.updates()
    val webSearchHttpClient = AppHttpClients.webSearch()
    val providerCatalogHttpClient = AppHttpClients.providerCatalog()
    val gitEngine = JGitEngine()
    val providerRepository = ProviderRepository(
        dao = database.providerProfileDao(),
        cipher = apiKeyCipher,
        timeProvider = SystemTimeProvider,
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
    val openAiClient = OpenAiCompatibleClient(chatHttpClient, json)
    val chatImageStore = ChatImageStore(appContext, chatHttpClient, dispatchers)
    val webSearchClient = JinaWebSearchClient(webSearchHttpClient)
    val queuedAttachmentStore = QueuedAttachmentStore(appContext)
    val projectRepository = FileProjectRepository(
        rootDirectory = appContext.filesDir,
        timeProvider = SystemTimeProvider,
    )
    val projectWorkspaceGateway = ProjectWorkspaceGatewayAdapter(projectRepository)
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
    )
    val chatExecutionRepository = ChatExecutionRepository(
        database = database,
        dao = database.chatExecutionEntryDao(),
        chatRepository = chatRepository,
        timeProvider = SystemTimeProvider,
    )
    val chatExecutionCoordinator = ChatExecutionCoordinator(
        executionRepository = chatExecutionRepository,
        sendMessageUseCase = sendMessageUseCase,
        providerRepository = providerRepository,
        webSearchClient = webSearchClient,
        attachmentStore = queuedAttachmentStore,
        dispatchers = dispatchers,
        onWorkScheduled = { ChatExecutionService.start(appContext) },
    )
    val markdownNotebookRepository = MarkdownNotebookRepository(
        chatRepository = chatRepository,
        linkDao = database.conversationMarkdownLinkDao(),
        draftDao = database.markdownChangeDraftDao(),
        timeProvider = SystemTimeProvider,
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
}
