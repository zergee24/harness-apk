package com.harnessapk.git

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JGitEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cloneStatusBranchCommitAndPushUseRealWorkingTree() {
        val remote = createBareRemote()
        val worktree = temporaryFolder.newFolder("worktree")
        val engine = JGitEngine()

        engine.cloneRepository(
            GitCloneRequest(
                remoteUrl = remote.toURI().toString(),
                branch = "main",
                directory = worktree,
            ),
        )

        val initialStatus = engine.status(worktree)
        assertEquals("main", initialStatus.currentBranch)
        assertTrue(initialStatus.isClean)

        engine.createBranch(worktree, "feature/mobile-git", checkout = true)
        assertEquals("feature/mobile-git", engine.status(worktree).currentBranch)

        worktree.resolve("src/App.kt").writeProjectText("fun main() = Unit\n")
        val dirtyStatus = engine.status(worktree)
        assertEquals(1, dirtyStatus.untrackedCount)
        assertEquals(
            GitFileChange(path = "src/App.kt", type = GitChangeType.UNTRACKED),
            dirtyStatus.files.single(),
        )

        val commit = engine.stageAllAndCommit(
            directory = worktree,
            message = "新增移动端 Git 测试文件",
            author = GitCommitAuthor(name = "Harness", email = "harness@example.com"),
        )
        assertEquals(40, commit.id.length)
        assertTrue(engine.status(worktree).isClean)

        engine.push(worktree, credentials = null)

        val verify = temporaryFolder.newFolder("verify")
        engine.cloneRepository(
            GitCloneRequest(
                remoteUrl = remote.toURI().toString(),
                branch = "feature/mobile-git",
                directory = verify,
            ),
        )
        assertTrue(verify.resolve("src/App.kt").isFile)
    }

    @Test
    fun pullFastForwardOnlyUpdatesCleanWorkspace() {
        val remote = createBareRemote()
        val firstClone = temporaryFolder.newFolder("first-clone")
        val secondClone = temporaryFolder.newFolder("second-clone")
        val engine = JGitEngine()

        engine.cloneRepository(GitCloneRequest(remote.toURI().toString(), "main", firstClone))
        engine.cloneRepository(GitCloneRequest(remote.toURI().toString(), "main", secondClone))

        secondClone.resolve("docs/notes.md").writeProjectText("# Notes\n")
        engine.stageAllAndCommit(
            directory = secondClone,
            message = "新增远端说明",
            author = GitCommitAuthor(name = "Harness", email = "harness@example.com"),
        )
        engine.push(secondClone, credentials = null)

        val result = engine.pullFastForwardOnly(firstClone, credentials = null)

        assertTrue(result.fastForward)
        assertTrue(firstClone.resolve("docs/notes.md").isFile)
        assertTrue(engine.status(firstClone).isClean)
    }

    @Test
    fun checkoutBranchRejectsDirtyWorkspace() {
        val remote = createBareRemote()
        val worktree = temporaryFolder.newFolder("worktree")
        val engine = JGitEngine()
        engine.cloneRepository(GitCloneRequest(remote.toURI().toString(), "main", worktree))
        engine.createBranch(worktree, "feature/clean", checkout = false)

        worktree.resolve("local.txt").writeText("local")

        try {
            engine.checkoutBranch(worktree, "feature/clean")
            throw AssertionError("Expected GitOperationException")
        } catch (error: GitOperationException) {
            assertTrue(error.message.orEmpty().contains("未提交改动"))
        }
    }

    private fun createBareRemote(): File {
        val seed = temporaryFolder.newFolder("seed")
        val remote = temporaryFolder.newFolder("remote.git")
        Git.init().setBare(true).setDirectory(remote).call().close()
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            seed.resolve("README.md").writeText("# Remote\n")
            git.add().addFilepattern("README.md").call()
            git.commit()
                .setMessage("initial")
                .setAuthor(PersonIdent("Harness", "harness@example.com"))
                .setCommitter(PersonIdent("Harness", "harness@example.com"))
                .call()
            git.remoteAdd().setName("origin").setUri(URIish(remote.toURI().toString())).call()
            git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()
        }
        return remote
    }

    private fun File.writeProjectText(text: String) {
        parentFile?.mkdirs()
        writeText(text)
    }
}
