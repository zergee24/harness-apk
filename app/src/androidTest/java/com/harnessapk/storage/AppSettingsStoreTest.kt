package com.harnessapk.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsStoreTest {
    @Test
    fun persistsDefaultModelPreference() = runBlocking {
        val store = AppSettingsStore(ApplicationProvider.getApplicationContext<Context>())

        store.clearDefaultModelPreference()
        store.setDefaultModelPreference(providerId = "openai", model = "gpt-5.5")

        assertEquals(
            DefaultModelPreference(providerId = "openai", model = "gpt-5.5"),
            store.defaultModelPreference.first(),
        )

        store.clearDefaultModelPreference()
    }
}
