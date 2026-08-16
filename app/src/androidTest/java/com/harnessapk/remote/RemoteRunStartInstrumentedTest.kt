package com.harnessapk.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.project.Project
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.ProjectRemoteBindingEntity
import com.harnessapk.storage.RemoteCommandOutboxEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteRunStartInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val outbox = RemoteCommandOutbox(RoomRemoteCommandStore(database.remoteDao()))
    private val launcher = RemoteRunLauncher(database, outbox, now = { 100L })

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun localLaunchAtomicallyCreatesQueuedRunAndRebuildableCommand() = runBlocking {
        val startedAt = System.nanoTime()
        val first = launcher.launch(project(), binding(), "  实现 M2  ", runId = "run-1", commandId = "command-1")
        val second = launcher.launch(project(), binding(), "实现 M2", runId = "run-1", commandId = "command-1")
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals("QUEUED", database.remoteDao().run("run-1")?.status)
        assertEquals("等待 Mac 接收", database.remoteDao().run("run-1")?.latestLine)
        assertEquals(first.run, second.run)
        assertEquals(first.command.payloadSha256, second.command.payloadSha256)
        assertEquals("run.start", first.command.payload.string("type"))
        assertEquals("fingerprint-1", first.command.payload.string("repositoryFingerprint"))
        assertEquals(1L, first.command.payload["contextSnapshot"]?.let { snapshot ->
            (snapshot as kotlinx.serialization.json.JsonObject).long("schemaVersion")
        })
        assertTrue("local launch took ${elapsedMillis}ms", elapsedMillis < 1_000L)
    }

    @Test
    fun offlineFlushKeepsQueuedRunAndPersistentCommand() = runBlocking {
        launcher.launch(project(), binding(), "实现 M2", runId = "run-offline", commandId = "command-offline")
        val transport = RemoteTransport(
            outbox = outbox,
            sender = object : RemoteCommandSender {
                override fun send(command: RebuiltRemoteCommand): Boolean = false
            },
            now = { 100L },
        )

        assertEquals(0, transport.flush())
        assertEquals("QUEUED", database.remoteDao().run("run-offline")?.status)
        assertNotNull(database.remoteDao().command("command-offline"))
        assertEquals("PENDING", database.remoteDao().command("command-offline")?.status)
    }

    @Test
    fun commandInsertFailureRollsBackRunInSameRoomTransaction() = runBlocking {
        val failingOutbox = RemoteCommandOutbox(object : RemoteCommandStore {
            override suspend fun insert(command: RemoteCommandOutboxEntity) = error("disk full")
            override suspend fun find(commandId: String): RemoteCommandOutboxEntity? = null
            override suspend fun upsert(command: RemoteCommandOutboxEntity) = Unit
            override suspend fun retryable(now: Long): List<RemoteCommandOutboxEntity> = emptyList()
        })
        val failingLauncher = RemoteRunLauncher(database, failingOutbox, now = { 100L })

        runCatching {
            failingLauncher.launch(project(), binding(), "实现 M2", runId = "run-failed", commandId = "command-failed")
        }

        assertNull(database.remoteDao().run("run-failed"))
    }

    private fun project() = Project(
        id = "project-1",
        name = "Harness APK",
        rootDirectory = File("/unused"),
        updatedAt = 1L,
    )

    private fun binding() = ProjectRemoteBindingEntity(
        id = "binding-1",
        projectId = "project-1", backendId = "codex",
        hostId = "host-1",
        workspaceId = "workspace-1",
        cwd = "/Users/test/harness-apk",
        displayName = "harness-apk",
        repositoryFingerprint = "fingerprint-1",
        repositoryLabel = "github.com/acme/harness-apk",
        state = "ACTIVE",
        verifiedAt = 1L,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
