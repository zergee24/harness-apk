package com.harnessapk.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
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
        )

        db.conversationDao().insert(entity)

        assertEquals(listOf(entity), db.conversationDao().observeActive().first())
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
}
