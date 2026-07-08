package com.harnessapk.git

import java.io.File
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

class JGitEngine {
    fun cloneRepository(request: GitCloneRequest) {
        runGitOperation("克隆仓库失败") {
            Git.cloneRepository()
                .setURI(request.remoteUrl.trim())
                .setDirectory(request.directory)
                .setBranch(request.branch.trim().ifBlank { null })
                .setCredentialsProvider(request.credentials?.toCredentialsProvider())
                .call()
                .close()
        }
    }

    fun initRepository(directory: File, branch: String = "main") {
        runGitOperation("初始化 Git 仓库失败") {
            Git.init()
                .setDirectory(directory)
                .setInitialBranch(branch.trim().ifBlank { "main" })
                .call()
                .close()
        }
    }

    fun bindRemote(directory: File, remoteUrl: String) {
        withGit(directory, "绑定远端仓库失败") { git ->
            val config = git.repository.config
            config.setString("remote", "origin", "url", remoteUrl.trim())
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            config.save()
        }
    }

    fun status(directory: File): GitStatusSummary = withGit(directory, "读取 Git 状态失败") { git ->
        val status = git.status().call()
        val files = buildList {
            status.added.sorted().forEach { add(GitFileChange(it, GitChangeType.ADDED)) }
            status.changed.sorted().forEach { add(GitFileChange(it, GitChangeType.CHANGED)) }
            status.removed.sorted().forEach { add(GitFileChange(it, GitChangeType.REMOVED)) }
            status.modified.sorted().forEach { add(GitFileChange(it, GitChangeType.MODIFIED)) }
            status.missing.sorted().forEach { add(GitFileChange(it, GitChangeType.MISSING)) }
            status.untracked.sorted().forEach { add(GitFileChange(it, GitChangeType.UNTRACKED)) }
            status.conflicting.sorted().forEach { add(GitFileChange(it, GitChangeType.CONFLICTING)) }
        }
        val tracking = runCatching {
            org.eclipse.jgit.lib.BranchTrackingStatus.of(git.repository, git.repository.branch)
        }.getOrNull()
        GitStatusSummary(
            currentBranch = git.repository.branch,
            isClean = status.isClean,
            stagedCount = status.added.size + status.changed.size + status.removed.size,
            unstagedCount = status.modified.size + status.missing.size + status.conflicting.size,
            untrackedCount = status.untracked.size,
            aheadCount = tracking?.aheadCount ?: 0,
            behindCount = tracking?.behindCount ?: 0,
            files = files,
        )
    }

    fun branches(directory: File): List<GitBranchSummary> = withGit(directory, "读取分支失败") { git ->
        val currentBranch = git.repository.branch
        val localBranches = git.branchList().call().map { ref ->
            val name = Repository.shortenRefName(ref.name)
            GitBranchSummary(
                name = name,
                isCurrent = name == currentBranch,
                isRemote = false,
            )
        }
        val remoteBranches = git.branchList()
            .setListMode(ListBranchCommand.ListMode.REMOTE)
            .call()
            .map { ref ->
                GitBranchSummary(
                    name = Repository.shortenRefName(ref.name),
                    isCurrent = false,
                    isRemote = true,
                )
            }
        (localBranches + remoteBranches).sortedWith(compareBy<GitBranchSummary> { it.isRemote }.thenBy { it.name })
    }

    fun createBranch(directory: File, branch: String, checkout: Boolean) {
        val branchName = normalizeBranchName(branch)
        withGit(directory, "创建分支失败") { git ->
            if (checkout) requireCleanWorkingTree(git)
            if (checkout) {
                git.checkout()
                    .setCreateBranch(true)
                    .setName(branchName)
                    .call()
            } else {
                git.branchCreate()
                    .setName(branchName)
                    .call()
            }
        }
    }

    fun checkoutBranch(directory: File, branch: String) {
        val branchName = normalizeBranchName(branch)
        withGit(directory, "切换分支失败") { git ->
            requireCleanWorkingTree(git)
            val hasLocal = git.branchList().call().any { Repository.shortenRefName(it.name) == branchName }
            val checkout = git.checkout().setName(branchName)
            if (!hasLocal && branchName.startsWith("origin/")) {
                checkout
                    .setCreateBranch(true)
                    .setName(branchName.removePrefix("origin/"))
                    .setStartPoint(branchName)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
            }
            checkout.call()
        }
    }

    fun stageAllAndCommit(
        directory: File,
        message: String,
        author: GitCommitAuthor,
    ): GitCommitResult {
        val trimmedMessage = message.trim()
        require(trimmedMessage.isNotBlank()) { "提交信息不能为空" }
        val person = PersonIdent(author.name.trim(), author.email.trim())
        return withGit(directory, "提交失败") { git ->
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            val commit = git.commit()
                .setMessage(trimmedMessage)
                .setAuthor(person)
                .setCommitter(person)
                .call()
            GitCommitResult(
                id = commit.name,
                shortId = commit.name.take(7),
                message = commit.fullMessage,
            )
        }
    }

    fun push(directory: File, credentials: GitCredentials?) {
        withGit(directory, "推送失败") { git ->
            val branch = git.repository.branch
            git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                .setCredentialsProvider(credentials?.toCredentialsProvider())
                .call()
                .toList()
            configureUpstream(git.repository, branch)
        }
    }

    fun fetch(directory: File, credentials: GitCredentials?) {
        withGit(directory, "拉取远端信息失败") { git ->
            git.fetch()
                .setRemote("origin")
                .setCredentialsProvider(credentials?.toCredentialsProvider())
                .call()
        }
    }

    fun pullFastForwardOnly(directory: File, credentials: GitCredentials?): GitPullResult =
        withGit(directory, "拉取失败") { git ->
            requireCleanWorkingTree(git)
            val result = git.pull()
                .setRemote("origin")
                .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                .setCredentialsProvider(credentials?.toCredentialsProvider())
                .call()
            if (!result.isSuccessful) {
                throw GitOperationException("远端更新无法快进合并，请在桌面端处理冲突后再同步")
            }
            GitPullResult(
                fastForward = result.fetchResult != null,
                message = result.mergeResult?.mergeStatus?.toString() ?: "OK",
            )
        }

    fun isRepository(directory: File): Boolean = directory.resolve(".git").isDirectory

    private fun requireCleanWorkingTree(git: Git) {
        val repositoryState = git.repository.repositoryState
        if (repositoryState != RepositoryState.SAFE) {
            throw GitOperationException("仓库当前状态不可操作：$repositoryState")
        }
        if (!git.status().call().isClean) {
            throw GitOperationException("工作区存在未提交改动，请先提交或清理后再切换/拉取")
        }
    }

    private fun configureUpstream(repository: Repository, branch: String) {
        val config = repository.config
        config.setString("branch", branch, "remote", "origin")
        config.setString("branch", branch, "merge", "refs/heads/$branch")
        config.save()
    }

    private fun normalizeBranchName(branch: String): String {
        val branchName = branch.trim()
        require(branchName.isNotBlank()) { "分支名称不能为空" }
        return branchName
    }

    private fun GitCredentials.toCredentialsProvider(): UsernamePasswordCredentialsProvider =
        UsernamePasswordCredentialsProvider(username.trim(), token)

    private inline fun <T> withGit(directory: File, message: String, block: (Git) -> T): T =
        runGitOperation(message) {
            Git.open(directory).use(block)
        }

    private inline fun <T> runGitOperation(message: String, block: () -> T): T {
        return try {
            block()
        } catch (error: GitOperationException) {
            throw error
        } catch (error: Throwable) {
            throw GitOperationException("$message：${error.message.orEmpty()}", error)
        }
    }
}
