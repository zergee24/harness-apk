package com.harnessapk.project

import com.harnessapk.common.TimeProvider
import com.harnessapk.session.MarkdownFileApplyStatus
import com.harnessapk.session.MarkdownUpdateOperation
import com.harnessapk.session.MarkdownUpdateProposal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectWorkspaceGatewayAdapterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun applyMarkdownUpdatesKeepsSuccessfulFilesAndContinuesAfterInvalidPath() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 1L },
        )
        val project = repository.createProject("移动端 Harness")
        val gateway = ProjectWorkspaceGatewayAdapter(repository)

        val result = gateway.applyMarkdownUpdates(
            projectId = project.id,
            updates = listOf(
                proposal("docs/first.md", "# First"),
                proposal("../escape.md", "# Escape"),
                proposal("docs/third.md", "# Third"),
            ),
        )

        assertEquals(
            listOf(
                MarkdownFileApplyStatus.SUCCEEDED,
                MarkdownFileApplyStatus.FAILED,
                MarkdownFileApplyStatus.SUCCEEDED,
            ),
            result.results.map { it.status },
        )
        assertEquals(listOf("docs/first.md", "docs/third.md"), result.succeeded.map { it.proposal.path })
        assertEquals(listOf("../escape.md"), result.failed.map { it.proposal.path })
        assertTrue(project.rootDirectory.resolve("docs/first.md").isFile)
        assertTrue(project.rootDirectory.resolve("docs/third.md").isFile)
        assertTrue(!temporaryFolder.root.resolve("escape.md").exists())
    }

    @Test
    fun applyMarkdownUpdatesRethrowsCancellationBeforeValidationAndDoesNotWrite() = runBlocking {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 1L },
        )
        val project = repository.createProject("移动端 Harness")
        val gateway = ProjectWorkspaceGatewayAdapter(repository)
        var cancellationObserved = false
        try {
            runBlocking {
                currentCoroutineContext().cancel()
                gateway.applyMarkdownUpdates(
                    projectId = project.id,
                    updates = listOf(proposal("docs/cancelled.md", "# Cancelled")),
                )
            }
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
        assertTrue(!project.rootDirectory.resolve("docs/cancelled.md").exists())
    }

    @Test
    fun applyMarkdownUpdatesDoesNotStartLaterWriteAfterCancellation() = runBlocking {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 1L },
        )
        val project = repository.createProject("移动端 Harness")
        val gateway = ProjectWorkspaceGatewayAdapter(repository)
        val firstPath = project.rootDirectory.resolve("docs/first.md")
        val secondPath = project.rootDirectory.resolve("docs/second.md")

        val job = launch(Dispatchers.IO) {
            gateway.applyMarkdownUpdates(
                projectId = project.id,
                updates = listOf(
                    proposal("docs/first.md", "# First\n" + "content\n".repeat(2_000_000)),
                    proposal("docs/second.md", "# Second"),
                ),
            )
        }

        withTimeout(10_000) {
            while (!firstPath.exists()) {
                Thread.yield()
            }
            job.cancel()
        }
        job.join()

        assertTrue(firstPath.isFile)
        assertTrue(!secondPath.exists())
    }

    private fun proposal(path: String, markdown: String) = MarkdownUpdateProposal(
        operation = MarkdownUpdateOperation.CREATE,
        path = path,
        title = path.substringAfterLast('/').substringBeforeLast('.'),
        reason = "测试批量写入",
        markdown = markdown,
    )
}
