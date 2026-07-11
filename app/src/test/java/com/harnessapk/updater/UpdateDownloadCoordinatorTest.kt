package com.harnessapk.updater

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDownloadCoordinatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun concurrentSameVersionDownloadsOnlyOnce() = runBlocking {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val file = temp.newFile("app.apk")
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        try {
            val coordinator = UpdateDownloadCoordinator(
                downloader = UpdateArtifactDownloader {
                    calls.incrementAndGet()
                    started.countDown()
                    check(gate.await(5, TimeUnit.SECONDS))
                    ApkDownloadResult(file, "sha")
                },
                ioDispatcher = dispatcher,
            )

            val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.download(manifest()) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.download(manifest()) }
            gate.countDown()

            assertEquals(first.await(), second.await())
            assertEquals(1, calls.get())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun failedDownloadCanRetryAndBecomeReady() = runTest {
        val calls = AtomicInteger()
        val file = temp.newFile("retry.apk")
        val coordinator = UpdateDownloadCoordinator(
            downloader = UpdateArtifactDownloader {
                if (calls.incrementAndGet() == 1) error("首次网络失败")
                ApkDownloadResult(file, "sha")
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val error = runCatching { coordinator.download(manifest()) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(UpdateDownloadState.Failed(2, "首次网络失败"), coordinator.state.value)
        assertEquals(file, coordinator.download(manifest()).file)
        assertTrue(coordinator.state.value is UpdateDownloadState.Ready)
        assertEquals(2, calls.get())
    }

    @Test
    fun readyFileIsReusedButDifferentVersionDownloadsAgain() = runTest {
        val calls = AtomicInteger()
        val file = temp.newFile("ready.apk")
        val coordinator = UpdateDownloadCoordinator(
            downloader = UpdateArtifactDownloader {
                calls.incrementAndGet()
                ApkDownloadResult(file, "sha")
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        coordinator.download(manifest(versionCode = 2))
        coordinator.download(manifest(versionCode = 2))
        coordinator.download(manifest(versionCode = 3))

        assertEquals(2, calls.get())
    }

    private fun manifest(versionCode: Int = 2): UpdateManifest = UpdateManifest(
        versionCode = versionCode,
        versionName = "0.1.1",
        minSupportedVersionCode = 1,
        apkUrl = "https://download.example.com/app.apk",
        sha256 = "sha",
        releaseNotes = emptyList(),
        publishedAt = "2026-07-05T00:00:00Z",
    )
}
