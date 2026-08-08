package com.harnessapk.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.common.TimeProvider
import com.harnessapk.project.DeleteProjectUseCase
import com.harnessapk.project.FileProjectRepository
import com.harnessapk.remote.RemoteBindingRepository
import com.harnessapk.remote.WorkspaceCandidate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteProjectLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val rootDirectory = context.cacheDir.resolve("m2-project-lifecycle-${UUID.randomUUID()}")
    private val projects = FileProjectRepository(rootDirectory, TimeProvider { 100L })
    private val bindings = RemoteBindingRepository(database.remoteDao(), now = { 200L }, newId = { "binding-1" })

    @After
    fun tearDown() {
        database.close()
        rootDirectory.deleteRecursively()
    }

    @Test
    fun unbindKeepsHistoricalRunReadableFromBindingSnapshot() = runBlocking {
        val project = projects.createProject("Harness")
        bindings.bind(project.id, "host-1", candidate())
        database.remoteDao().insertRun(run(project.id))

        bindings.unbind(project.id)

        assertNull(database.remoteDao().bindingForProject(project.id))
        val historical = database.remoteDao().run("run-1")
        assertNotNull(historical)
        assertEquals("Harness", historical?.projectNameSnapshot)
        assertTrue(historical?.bindingSnapshotJson?.contains("github.com/acme/harness") == true)
    }

    @Test
    fun deletingProjectRemovesActiveBindingButKeepsHistoricalRun() = runBlocking {
        val project = projects.createProject("Harness")
        bindings.bind(project.id, "host-1", candidate())
        database.remoteDao().insertRun(run(project.id))

        DeleteProjectUseCase(projects, database).delete(project.id)

        assertNull(database.remoteDao().bindingForProject(project.id))
        assertNotNull(database.remoteDao().run("run-1"))
    }

    private fun candidate() = WorkspaceCandidate(
        workspaceId = "workspace-1",
        displayName = "harness",
        cwd = "/Users/test/harness",
        repositoryLabel = "github.com/acme/harness",
        branch = "test",
        repositoryFingerprint = "fingerprint-1",
        lastUsedAt = 100L,
    )

    private fun run(projectId: String) = RemoteRunEntity(
        id = "run-1",
        projectId = projectId,
        projectNameSnapshot = "Harness",
        bindingId = "binding-1",
        bindingSnapshotJson = "{\"repositoryLabel\":\"github.com/acme/harness\"}",
        hostId = "host-1",
        threadId = "thread-1",
        turnId = "turn-1",
        objective = "继续实现 M2",
        status = "COMPLETED",
        latestLine = "完成",
        lastLogicalSequence = 1L,
        startedAt = 100L,
        updatedAt = 200L,
        completedAt = 200L,
        completionJson = null,
        errorMessage = null,
    )
}
