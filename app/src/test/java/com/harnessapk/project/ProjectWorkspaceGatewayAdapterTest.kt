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
import java.security.MessageDigest

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

    @Test
    fun applyRejectsUpdateWhenBaselineChangedAndPreservesCurrentFile() = runTest {
        val repository = FileProjectRepository(temporaryFolder.root, TimeProvider { 1L })
        val project = repository.createProject("M3 baseline")
        repository.writeMarkdownFile(project.id, "docs/decision.md", "# 原始\n")
        val baseline = sha256("# 原始\n")
        repository.writeMarkdownFile(project.id, "docs/decision.md", "# 外部修改\n")

        val result = ProjectWorkspaceGatewayAdapter(repository).applyMarkdownUpdates(
            project.id,
            listOf(
                proposal("docs/decision.md", "# 助手修改\n").copy(
                    operation = MarkdownUpdateOperation.UPDATE,
                    baselineSha256 = baseline,
                    expectedAbsent = false,
                ),
            ),
        )

        assertEquals(MarkdownFileApplyStatus.FAILED, result.results.single().status)
        assertTrue(result.results.single().errorMessage.orEmpty().contains("基线"))
        assertEquals("# 外部修改\n", repository.readDeliverable(project.id, "docs/decision.md"))
    }

    @Test
    fun applyRejectsCreateWhenFileAppearedAfterDraft() = runTest {
        val repository = FileProjectRepository(temporaryFolder.root, TimeProvider { 1L })
        val project = repository.createProject("M3 expected absent")
        repository.writeMarkdownFile(project.id, "reports/new.md", "# 他人新建\n")

        val result = ProjectWorkspaceGatewayAdapter(repository).applyMarkdownUpdates(
            project.id,
            listOf(
                proposal("reports/new.md", "# 草稿新建\n").copy(
                    expectedAbsent = true,
                ),
            ),
        )

        assertEquals(MarkdownFileApplyStatus.FAILED, result.results.single().status)
        assertTrue(result.results.single().errorMessage.orEmpty().contains("已存在"))
        assertEquals("# 他人新建\n", repository.readDeliverable(project.id, "reports/new.md"))
    }

    @Test
    fun applyRejectsDuplicateNormalizedPathsWithoutWritingEitherProposal() = runTest {
        val repository = FileProjectRepository(temporaryFolder.root, TimeProvider { 1L })
        val project = repository.createProject("M3 duplicate path")

        val result = ProjectWorkspaceGatewayAdapter(repository).applyMarkdownUpdates(
            project.id,
            listOf(
                proposal("reports/Same.md", "# first\n"),
                proposal("reports/same.md", "# second\n"),
            ),
        )

        assertEquals(
            listOf(MarkdownFileApplyStatus.FAILED, MarkdownFileApplyStatus.FAILED),
            result.results.map { it.status },
        )
        assertTrue(result.results.all { it.errorMessage.orEmpty().contains("重复") })
        assertTrue(!project.rootDirectory.resolve("reports/Same.md").exists())
        assertTrue(!project.rootDirectory.resolve("reports/same.md").exists())
    }

    private fun proposal(path: String, markdown: String) = MarkdownUpdateProposal(
        operation = MarkdownUpdateOperation.CREATE,
        path = path,
        title = path.substringAfterLast('/').substringBeforeLast('.'),
        reason = "测试批量写入",
        markdown = markdown,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
