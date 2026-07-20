package com.harnessapk

import android.app.Instrumentation
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HarnessApkApplicationInstrumentedTest {
    @Test
    fun onCreateLaunchesAgentFileRecoveryWithoutAnotherImport() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val application = Instrumentation.newApplication(
            HarnessApkApplication::class.java,
            base,
        ) as HarnessApkApplication
        val snapshot = application.cacheDir
            .resolve("agent-install-snapshots")
            .apply { mkdirs() }
            .resolve("${UUID.randomUUID()}.package")
            .apply { writeText("orphan") }

        application.onCreate()

        withTimeout(5_000L) {
            while (snapshot.exists()) delay(25L)
        }
        assertFalse(snapshot.exists())
        application.container.database.close()
    }
}
