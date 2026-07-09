package com.harnessapk.common

import android.content.Context
import androidx.room.Room
import com.harnessapk.BuildConfig
import com.harnessapk.chat.ChatRepository
import com.harnessapk.chat.ManualContextCompressionUseCase
import com.harnessapk.chat.SendMessageUseCase
import com.harnessapk.git.GitCredentialStore
import com.harnessapk.git.JGitEngine
import com.harnessapk.network.OpenAiCompatibleClient
import com.harnessapk.project.FileProjectRepository
import com.harnessapk.project.ProjectWorkspaceGatewayAdapter
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.security.ApiKeyCipher
import com.harnessapk.session.PromptOptimizerUseCase
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.AppSettingsStore
import com.harnessapk.updater.ApkInstaller
import com.harnessapk.updater.UpdateRepository
import com.harnessapk.websearch.JinaWebSearchClient
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
    val gitEngine = JGitEngine()
    val providerRepository = ProviderRepository(
        dao = database.providerProfileDao(),
        cipher = apiKeyCipher,
        timeProvider = SystemTimeProvider,
    )
    val chatRepository = ChatRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        messagePartDao = database.messagePartDao(),
        attachmentDao = database.messageAttachmentDao(),
        memoryDao = database.conversationMemoryDao(),
        timeProvider = SystemTimeProvider,
    )
    val openAiClient = OpenAiCompatibleClient(chatHttpClient, json)
    val webSearchClient = JinaWebSearchClient(webSearchHttpClient)
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
    )
    val updateRepository = UpdateRepository(
        okHttpClient = updateHttpClient,
        json = json,
        manifestUrl = BuildConfig.UPDATE_MANIFEST_URL,
        currentVersionCode = BuildConfig.VERSION_CODE,
        cacheDir = appContext.cacheDir,
    )
    val apkInstaller = ApkInstaller(appContext)
}
