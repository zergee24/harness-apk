package com.harnessapk.git

import java.io.File

data class GitCloneRequest(
    val remoteUrl: String,
    val branch: String,
    val directory: File,
    val credentials: GitCredentials? = null,
)

data class GitCredentials(
    val username: String,
    val token: String,
)

data class GitCommitAuthor(
    val name: String,
    val email: String,
)

data class GitCommitResult(
    val id: String,
    val shortId: String,
    val message: String,
)

data class GitStatusSummary(
    val currentBranch: String,
    val isClean: Boolean,
    val stagedCount: Int,
    val unstagedCount: Int,
    val untrackedCount: Int,
    val aheadCount: Int,
    val behindCount: Int,
    val files: List<GitFileChange>,
)

data class GitFileChange(
    val path: String,
    val type: GitChangeType,
)

enum class GitChangeType(val label: String) {
    ADDED("已暂存新增"),
    CHANGED("已暂存修改"),
    REMOVED("已暂存删除"),
    MODIFIED("已修改"),
    MISSING("已删除"),
    UNTRACKED("未跟踪"),
    CONFLICTING("冲突"),
}

data class GitBranchSummary(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean,
)

data class GitPullResult(
    val fastForward: Boolean,
    val message: String,
)

class GitOperationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
