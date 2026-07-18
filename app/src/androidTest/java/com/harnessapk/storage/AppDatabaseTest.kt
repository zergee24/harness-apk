package com.harnessapk.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.chat.UiMessagePartType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    @Test
    fun storesAgentVersionAndFindsCorpusChunkThroughFts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = db.agentDao()
        dao.upsertAgent(
            AgentEntity(
                id = "agent-1",
                name = "资料研究代理",
                summary = "基于资料模拟",
                activeVersion = 1,
                publisherPublicKey = byteArrayOf(1, 2, 3),
                publisherFingerprint = "fingerprint",
                installSource = "LOCAL_FILE",
                status = "READY",
                requiredCorpusCount = 1,
                installedCorpusCount = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        dao.insertVersion(
            AgentVersionEntity(
                agentId = "agent-1",
                version = 1,
                schemaVersion = 1,
                bundlePath = "/tmp/agent.hbundle",
                bundleSha256 = "bundle-sha",
                manifestJson = "{}",
                persona = "只根据资料回答",
                worldviewJsonl = "",
                installedAt = 1L,
                state = "READY",
            ),
        )
        dao.insertCorpus(
            AgentCorpusEntity(
                corpusId = "corpus-1",
                sourceHash = "source-hash",
                title = "测试资料",
                indexedAt = 1L,
                sizeBytes = 100L,
            ),
        )
        dao.insertVersionCorpus(
            AgentVersionCorpusCrossRef(
                agentId = "agent-1",
                version = 1,
                corpusId = "corpus-1",
                sourceHash = "source-hash",
                required = true,
            ),
        )
        dao.insertChunks(
            listOf(
                AgentChunkEntity(
                    chunkKey = "corpus-1:source-hash:chunk-1",
                    corpusId = "corpus-1",
                    sourceHash = "source-hash",
                    chunkId = "chunk-1",
                    sourceTitle = "测试资料",
                    location = "第一章",
                    text = "研究问题必须从事实出发",
                    keywordsText = "调查 事实",
                ),
            ),
        )
        dao.insertChunkSearchRows(
            listOf(
                AgentChunkFtsEntity(
                    chunkKey = "corpus-1:source-hash:chunk-1",
                    corpusKey = "corpus-1:source-hash",
                    searchableText = "调查 事实 调查研究",
                ),
            ),
        )

        val keys = dao.searchChunkKeys(
            corpusKeys = listOf("corpus-1:source-hash"),
            ftsQuery = "调查 OR 事实",
            limit = 8,
        )

        assertEquals(listOf("corpus-1:source-hash:chunk-1"), keys)
        db.close()
    }

    @Test
    fun storesConversation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val entity = ConversationEntity(
            id = "conversation-1",
            title = "测试会话",
            createdAt = 1L,
            updatedAt = 1L,
            defaultProviderId = null,
            defaultModel = null,
            isArchived = false,
            projectId = null,
            promptOriginal = "",
            promptOptimized = "",
            promptFinal = "",
        )

        db.conversationDao().insert(entity)

        assertEquals(listOf(entity), db.conversationDao().observeActive().first())
        db.close()
    }

    @Test
    fun rejectsIdentityUpdateWhenConversationAlreadyHasUserMessage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val conversation = ConversationEntity(
            id = "identity-locked",
            title = "身份锁定测试",
            createdAt = 1L,
            updatedAt = 1L,
            defaultProviderId = null,
            defaultModel = null,
            isArchived = false,
            projectId = null,
            promptOriginal = "",
            promptOptimized = "",
            promptFinal = "",
            agentId = "agent-1",
            agentVersion = 1,
        )
        db.conversationDao().insert(conversation)
        db.messageDao().insert(
            MessageEntity(
                id = "user-1",
                conversationId = conversation.id,
                role = MessageRole.USER.name,
                content = "你好",
                status = MessageStatus.SUCCEEDED.name,
                providerId = null,
                model = null,
                createdAt = 2L,
                updatedAt = 2L,
                errorCode = null,
                errorMessage = null,
            ),
        )

        val updated = db.conversationDao().updateIdentityIfNoUserMessages(
            id = conversation.id,
            agentId = "agent-2",
            agentVersion = 2,
            updatedAt = 3L,
        )

        assertEquals(0, updated)
        assertEquals("agent-1", db.conversationDao().findById(conversation.id)!!.agentId)
        assertEquals(1, db.conversationDao().findById(conversation.id)!!.agentVersion)
        db.close()
    }

    @Test
    fun storesConversationMemory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.conversationDao().insert(
            ConversationEntity(
                id = "conversation-memory",
                title = "记忆测试",
                createdAt = 1L,
                updatedAt = 1L,
                defaultProviderId = null,
                defaultModel = null,
                isArchived = false,
                projectId = null,
                promptOriginal = "",
                promptOptimized = "",
                promptFinal = "",
            ),
        )
        val memory = ConversationMemoryEntity(
            conversationId = "conversation-memory",
            summary = "- 用户：需要简洁回答",
            coveredThroughMessageId = "m1",
            coveredThroughCreatedAt = 10L,
            compressedMessageCount = 3,
            updatedAt = 20L,
        )

        db.conversationMemoryDao().upsert(memory)

        assertEquals(memory, db.conversationMemoryDao().findByConversationId("conversation-memory"))
        db.close()
    }

    @Test
    fun storesProviderAvailableModels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val provider = ProviderProfileEntity(
            id = "provider",
            name = "OpenAI",
            baseUrl = "https://happycode.vip/v1",
            apiKeyAlias = "provider:provider",
            encryptedApiKey = "key".encodeToByteArray(),
            apiKeyIv = "iv".encodeToByteArray(),
            defaultModel = "gpt-5.5",
            availableModels = "gpt-5.5\ngpt-5.5-pro",
            defaultVisionModel = "gpt-5.5",
            supportsVision = true,
            nativeWebSearchMode = NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS.name,
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
        )

        db.providerProfileDao().insert(provider)

        val stored = db.providerProfileDao().findById("provider")!!
        assertEquals("OpenAI", stored.name)
        assertEquals("gpt-5.5\ngpt-5.5-pro", stored.availableModels)
        db.close()
    }

    @Test
    fun storesMessageParts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.conversationDao().insert(
            ConversationEntity(
                id = "conversation-parts",
                title = "分片测试",
                createdAt = 1L,
                updatedAt = 1L,
                defaultProviderId = null,
                defaultModel = null,
                isArchived = false,
                projectId = null,
                promptOriginal = "",
                promptOptimized = "",
                promptFinal = "",
            ),
        )
        db.messageDao().insert(
            MessageEntity(
                id = "message-parts",
                conversationId = "conversation-parts",
                role = MessageRole.ASSISTANT.name,
                content = "可见答案",
                status = MessageStatus.STREAMING.name,
                providerId = "openai",
                model = "gpt-5.5",
                createdAt = 2L,
                updatedAt = 2L,
                errorCode = null,
                errorMessage = null,
            ),
        )
        val parts = listOf(
            MessagePartEntity(
                id = "part-1",
                messageId = "message-parts",
                partIndex = 0,
                type = UiMessagePartType.REASONING.name,
                content = "内部推理",
                metadataJson = "",
                stable = true,
                createdAt = 2L,
                updatedAt = 3L,
            ),
            MessagePartEntity(
                id = "part-2",
                messageId = "message-parts",
                partIndex = 1,
                type = UiMessagePartType.TEXT.name,
                content = "可见答案",
                metadataJson = "",
                stable = false,
                createdAt = 2L,
                updatedAt = 3L,
            ),
        )

        db.messagePartDao().replaceForMessage("message-parts", parts)

        assertEquals(parts, db.messagePartDao().listForMessage("message-parts"))
        db.close()
    }

    @Test
    fun storesChatExecutionEntry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.conversationDao().insert(
            ConversationEntity(
                id = "conversation-queue",
                title = "队列测试",
                createdAt = 1L,
                updatedAt = 1L,
                defaultProviderId = null,
                defaultModel = null,
                isArchived = false,
                projectId = null,
                promptOriginal = "",
                promptOptimized = "",
                promptFinal = "",
            ),
        )
        db.messageDao().insert(
            MessageEntity(
                id = "message-queue",
                conversationId = "conversation-queue",
                role = MessageRole.USER.name,
                content = "排队消息",
                status = MessageStatus.SUCCEEDED.name,
                providerId = null,
                model = null,
                createdAt = 2L,
                updatedAt = 2L,
                errorCode = null,
                errorMessage = null,
            ),
        )
        val entry = ChatExecutionEntryEntity(
            id = "entry-queue",
            conversationId = "conversation-queue",
            userMessageId = "message-queue",
            assistantMessageId = null,
            targetAssistantMessageId = null,
            sequence = 1L,
            type = "NORMAL",
            status = "QUEUED",
            providerId = "provider",
            model = "model",
            reasoningEffort = "HIGH",
            requestContextJson = "{}",
            errorMessage = null,
            createdAt = 2L,
            updatedAt = 2L,
        )

        db.chatExecutionEntryDao().insert(entry)

        assertEquals(listOf(entry), db.chatExecutionEntryDao().listForConversation("conversation-queue"))
        db.close()
    }
}
