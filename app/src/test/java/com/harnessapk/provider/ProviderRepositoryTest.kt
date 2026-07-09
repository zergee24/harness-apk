package com.harnessapk.provider

import com.harnessapk.common.TimeProvider
import com.harnessapk.security.EncryptedValue
import com.harnessapk.security.StringCipher
import com.harnessapk.storage.ProviderProfileDao
import com.harnessapk.storage.ProviderProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProviderRepositoryTest {
    @Test
    fun saveProviderPersistsEncryptedKeyOnly() = runTest {
        val dao = FakeProviderProfileDao()
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        )

        val id = repository.saveProvider(
            ProviderDraft(
                name = "Test",
                baseUrl = "https://example.com/v1",
                apiKey = "secret-key",
                defaultModel = "model",
                defaultVisionModel = null,
                supportsVision = false,
            ),
        )

        val stored = dao.findById(id)!!
        assertNotEquals("secret-key", stored.encryptedApiKey!!.decodeToString())
        assertFalse(stored.baseUrl.endsWith("/"))
        assertEquals("secret-key", repository.getApiKey(id))
    }

    @Test
    fun saveProviderPersistsAvailableModelsWithDefaultFirst() = runTest {
        val dao = FakeProviderProfileDao()
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        )

        val id = repository.saveProvider(
            ProviderDraft(
                name = "OpenAI",
                baseUrl = "https://happycode.vip/v1",
                apiKey = "secret-key",
                defaultModel = "gpt-5.5",
                defaultVisionModel = "gpt-5.5-vision",
                supportsVision = true,
                availableModels = listOf(" gpt-5.5-pro ", "gpt-5.5", "gpt-5.5-pro", "gpt-5.5-vision"),
            ),
        )

        val profile = repository.findById(id)!!
        val stored = dao.findById(id)!!
        assertEquals(listOf("gpt-5.5", "gpt-5.5-pro", "gpt-5.5-vision"), profile.availableModels)
        assertEquals(200_000, profile.modelConfigs.first { it.id == "gpt-5.5" }.contextWindowTokens)
        assertEquals(
            "gpt-5.5|200000|70\ngpt-5.5-pro|200000|70\ngpt-5.5-vision|200000|70",
            stored.availableModels,
        )
    }

    @Test
    fun saveProviderPersistsPerModelCapabilityOverrides() = runTest {
        val dao = FakeProviderProfileDao()
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        )

        val id = repository.saveProvider(
            ProviderDraft(
                name = "OpenAI",
                baseUrl = "https://happycode.vip/v1",
                apiKey = "secret-key",
                defaultModel = "gpt-5.5",
                defaultVisionModel = null,
                supportsVision = true,
                modelConfigs = listOf(
                    ModelConfig(
                        id = "gpt-5.5",
                        contextWindowTokens = 200_000,
                        compressionThresholdPercent = 68,
                        maxOutputTokens = 32_000,
                        inputModalities = listOf("text", "image", "audio"),
                        outputModalities = listOf("text", "audio"),
                        reasoningEffortOptions = listOf("low", "medium", "high", "xhigh"),
                        defaultReasoningEffort = "high",
                        webSearchMode = NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
                        supportsToolCalling = true,
                        readTimeoutMillis = 240_000L,
                    ),
                ),
            ),
        )

        val config = repository.findById(id)!!.modelConfigs.single()
        assertEquals(32_000, config.maxOutputTokens)
        assertEquals(listOf("text", "image", "audio"), config.inputModalities)
        assertEquals(listOf("text", "audio"), config.outputModalities)
        assertEquals(listOf("low", "medium", "high", "xhigh"), config.reasoningEffortOptions)
        assertEquals("high", config.defaultReasoningEffort)
        assertEquals(NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS, config.webSearchMode)
        assertEquals(true, config.supportsToolCalling)
        assertEquals(240_000L, config.readTimeoutMillis)
    }

    @Test
    fun readProviderBackfillsModelConfigsFromLegacyModelNames() = runTest {
        val dao = FakeProviderProfileDao()
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        )
        dao.insert(
            ProviderProfileEntity(
                id = "legacy",
                name = "OpenAI",
                baseUrl = "https://happycode.vip/v1",
                apiKeyAlias = "provider:legacy",
                encryptedApiKey = "yek".encodeToByteArray(),
                apiKeyIv = "iv".encodeToByteArray(),
                defaultModel = "gpt-5.5",
                availableModels = "gpt-5.5\ncustom-local",
                defaultVisionModel = null,
                supportsVision = true,
                nativeWebSearchMode = NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS.name,
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val profile = repository.findById("legacy")!!

        assertEquals(listOf("gpt-5.5", "custom-local"), profile.availableModels)
        assertEquals(200_000, profile.modelConfigs.first { it.id == "gpt-5.5" }.contextWindowTokens)
        assertEquals(200_000, profile.modelConfigs.first { it.id == "custom-local" }.contextWindowTokens)
    }

    @Test
    fun saveProviderPersistsNativeWebSearchMode() = runTest {
        val dao = FakeProviderProfileDao()
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        )

        val id = repository.saveProvider(
            ProviderDraft(
                name = "OpenAI",
                baseUrl = "https://happycode.vip/v1",
                apiKey = "secret-key",
                defaultModel = "gpt-5.5",
                defaultVisionModel = null,
                supportsVision = false,
                nativeWebSearchMode = NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
            ),
        )

        val profile = repository.findById(id)!!
        val stored = dao.findById(id)!!
        assertEquals(NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS, profile.nativeWebSearchMode)
        assertEquals("OPENAI_WEB_SEARCH_OPTIONS", stored.nativeWebSearchMode)
    }

    @Test
    fun saveProviderPersistsCustomRequestOverrides() = runTest {
        val dao = FakeProviderProfileDao()
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        )

        val id = repository.saveProvider(
            ProviderDraft(
                name = "OpenAI",
                baseUrl = "https://happycode.vip/v1",
                apiKey = "secret-key",
                defaultModel = "gpt-5.5",
                defaultVisionModel = null,
                supportsVision = false,
                customHeaders = mapOf(
                    " X-Provider-Feature " to " beta ",
                    "Blank" to " ",
                ),
                customBodyJson = """ { "metadata": { "source": "local-override" } } """,
            ),
        )

        val profile = repository.findById(id)!!
        val stored = dao.findById(id)!!
        assertEquals(mapOf("X-Provider-Feature" to "beta"), profile.customHeaders)
        assertEquals("""{ "metadata": { "source": "local-override" } }""", profile.customBodyJson)
        assertEquals("""{"X-Provider-Feature":"beta"}""", stored.customHeadersJson)
        assertEquals("""{ "metadata": { "source": "local-override" } }""", stored.customBodyJson)
    }

    @Test
    fun updateProviderPersistsAvailableModelsAndIncludesDefaultWhenMissing() = runTest {
        val dao = FakeProviderProfileDao()
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        )
        val id = repository.saveProvider(
            ProviderDraft(
                name = "Kimi",
                baseUrl = "https://api.moonshot.cn/v1",
                apiKey = "kimi-key",
                defaultModel = "kimi-k2.7-code",
                defaultVisionModel = "kimi-k2.7-code",
                supportsVision = true,
                availableModels = listOf("kimi-k2.7-code"),
            ),
        )

        repository.updateProvider(
            id,
            ProviderDraft(
                name = "Kimi",
                baseUrl = "https://api.moonshot.cn/v1",
                apiKey = "",
                defaultModel = "kimi-k2.7",
                defaultVisionModel = null,
                supportsVision = false,
                availableModels = listOf("kimi-k2.7-code", " kimi-k2.7-code "),
            ),
        )

        val updated = repository.findById(id)!!
        assertEquals(listOf("kimi-k2.7", "kimi-k2.7-code"), updated.availableModels)
    }

    @Test
    fun providerWithKeyReturnsRequestedProviderInsteadOfNewestDefault() = runTest {
        val dao = FakeProviderProfileDao()
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        )
        val firstId = repository.saveProvider(
            ProviderDraft(
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                apiKey = "openai-key",
                defaultModel = "gpt-4.1-mini",
                defaultVisionModel = "gpt-4.1-mini",
                supportsVision = true,
            ),
        )
        repository.saveProvider(
            ProviderDraft(
                name = "Kimi",
                baseUrl = "https://api.moonshot.cn/v1",
                apiKey = "kimi-key",
                defaultModel = "kimi-k2",
                defaultVisionModel = null,
                supportsVision = false,
            ),
        )

        val selected = repository.providerWithKey(firstId)

        assertEquals("OpenAI", selected.profile.name)
        assertEquals("openai-key", selected.apiKey)
    }

    @Test
    fun updateProviderKeepsExistingApiKeyWhenApiKeyIsBlank() = runTest {
        val dao = FakeProviderProfileDao()
        var now = 10L
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { now },
        )
        val id = repository.saveProvider(
            ProviderDraft(
                name = "OpenAI",
                baseUrl = "https://happycode.vip/v1",
                apiKey = "original-key",
                defaultModel = "gpt-5.5",
                defaultVisionModel = "gpt-5.5",
                supportsVision = true,
            ),
        )

        now = 20L
        repository.updateProvider(
            id,
            ProviderDraft(
                name = "OpenAI Proxy",
                baseUrl = "https://happycode.vip/v1/",
                apiKey = "",
                defaultModel = "gpt-5.5-pro",
                defaultVisionModel = "",
                supportsVision = false,
            ),
        )

        val updated = repository.findById(id)!!
        val stored = dao.findById(id)!!
        assertEquals("OpenAI Proxy", updated.name)
        assertEquals("https://happycode.vip/v1", updated.baseUrl)
        assertEquals("gpt-5.5-pro", updated.defaultModel)
        assertEquals(null, updated.defaultVisionModel)
        assertFalse(updated.supportsVision)
        assertEquals(20L, stored.updatedAt)
        assertEquals("original-key", repository.getApiKey(id))
    }

    @Test
    fun updateProviderReplacesApiKeyWhenProvided() = runTest {
        val dao = FakeProviderProfileDao()
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        )
        val id = repository.saveProvider(
            ProviderDraft(
                name = "Kimi",
                baseUrl = "https://api.moonshot.cn/v1",
                apiKey = "old-key",
                defaultModel = "kimi-k2.7-code",
                defaultVisionModel = "kimi-k2.7-code",
                supportsVision = true,
            ),
        )

        repository.updateProvider(
            id,
            ProviderDraft(
                name = "Kimi",
                baseUrl = "https://api.moonshot.cn/v1",
                apiKey = "new-key",
                defaultModel = "kimi-k2.7-code",
                defaultVisionModel = "kimi-k2.7-code",
                supportsVision = true,
            ),
        )

        assertEquals("new-key", repository.getApiKey(id))
    }

    @Test
    fun deleteProviderRemovesStoredProvider() = runTest {
        val dao = FakeProviderProfileDao()
        val repository = ProviderRepository(
            dao = dao,
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        )
        val id = repository.saveProvider(
            ProviderDraft(
                name = "Kimi",
                baseUrl = "https://api.moonshot.cn/v1",
                apiKey = "key",
                defaultModel = "kimi-k2.7-code",
                defaultVisionModel = "kimi-k2.7-code",
                supportsVision = true,
            ),
        )

        repository.deleteProvider(id)

        assertEquals(null, repository.findById(id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun saveProviderRejectsHttpBaseUrl() = runTest {
        ProviderRepository(
            dao = FakeProviderProfileDao(),
            cipher = ReversingCipher,
            timeProvider = TimeProvider { 10L },
        ).saveProvider(
            ProviderDraft(
                name = "Bad",
                baseUrl = "http://example.com/v1",
                apiKey = "secret-key",
                defaultModel = "model",
                defaultVisionModel = null,
                supportsVision = false,
            ),
        )
    }
}

private object ReversingCipher : StringCipher {
    override fun encrypt(plainText: String): EncryptedValue = EncryptedValue(
        cipherText = plainText.reversed().encodeToByteArray(),
        initializationVector = "iv".encodeToByteArray(),
    )

    override fun decrypt(value: EncryptedValue): String = value.cipherText.decodeToString().reversed()
}

private class FakeProviderProfileDao : ProviderProfileDao {
    private val rows = linkedMapOf<String, ProviderProfileEntity>()
    private val flow = MutableStateFlow<List<ProviderProfileEntity>>(emptyList())

    override fun observeEnabled(): Flow<List<ProviderProfileEntity>> = flow

    override suspend fun findById(id: String): ProviderProfileEntity? = rows[id]

    override suspend fun firstEnabled(): ProviderProfileEntity? = rows.values.firstOrNull { it.enabled }

    override suspend fun insert(entity: ProviderProfileEntity) {
        rows[entity.id] = entity
        flow.value = rows.values.filter { it.enabled }
    }

    override suspend fun update(entity: ProviderProfileEntity) {
        rows[entity.id] = entity
        flow.value = rows.values.filter { it.enabled }
    }

    override suspend fun delete(entity: ProviderProfileEntity) {
        rows.remove(entity.id)
        flow.value = rows.values.filter { it.enabled }
    }
}
