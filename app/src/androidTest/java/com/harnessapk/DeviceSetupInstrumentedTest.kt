package com.harnessapk

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderDraft
import com.harnessapk.provider.ProviderTemplates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 一次性设备配置入口（由 adb 传参驱动，未传参数的步骤自动跳过）：
 *
 * adb shell am instrument -w \
 *   -e pairingJson '<harness-bridge pair 输出的 JSON>' \
 *   -e providerName Kimi -e providerApiKey sk-xxx \
 *   com.harnessapk.debug.test/com.harnessapk.DeviceSetupInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class DeviceSetupInstrumentedTest {

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    @Test
    fun seedsRemoteProfileAndProviderFromInstrumentationArgs(): Unit = runBlocking {
        val container = (ApplicationProvider.getApplicationContext<android.content.Context>()
            as HarnessApkApplication).container

        arg("pairingJson")?.let { pairingJson ->
            val enrolled = container.remoteEnrollmentClient.enroll(pairingJson, null)
            container.remoteRepository.disconnect()
            container.remoteProfileStore.save(enrolled)
            container.remoteRepository.connect()
            assertNotNull(container.remoteProfileStore.profile.value)
        }

        arg("providerName")?.let { name ->
            val apiKey = requireNotNull(arg("providerApiKey")) { "传 providerName 时必须同时传 providerApiKey" }
            val template = ProviderTemplates.defaults.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val draft = ProviderDraft(
                name = name,
                baseUrl = arg("providerBaseUrl") ?: template?.baseUrl
                    ?: error("非模板 provider 必须传 providerBaseUrl"),
                apiKey = apiKey,
                defaultModel = arg("providerModel") ?: template?.defaultModel
                    ?: error("非模板 provider 必须传 providerModel"),
                defaultVisionModel = arg("providerVisionModel") ?: template?.defaultVisionModel,
                supportsVision = template?.supportsVision ?: false,
                nativeWebSearchMode = template?.nativeWebSearchMode ?: NativeWebSearchMode.DISABLED,
                availableModels = template?.modelConfigs?.map { it.id } ?: emptyList(),
                modelConfigs = template?.modelConfigs ?: emptyList(),
            )
            container.providerRepository.observeEnabled().first()
                .filter { it.name.equals(name, ignoreCase = true) }
                .forEach { container.providerRepository.deleteProvider(it.id) }
            val providerId = container.providerRepository.saveProvider(draft)
            container.settingsStore.setDefaultModelPreference(providerId, draft.defaultModel)
            assertEquals(name, container.providerRepository.firstEnabled()?.name)
        }
    }
}
