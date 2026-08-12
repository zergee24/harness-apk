package com.harnessapk.projectsearch

import com.harnessapk.remote.RemoteCompletionVerification
import com.harnessapk.remote.parseRemoteCompletionEvidence
import com.harnessapk.search.LocalSearchTokenizer
import com.harnessapk.storage.LocalSearchDao
import com.harnessapk.storage.LocalSearchDocumentEntity
import com.harnessapk.storage.RemoteDao
import java.security.MessageDigest

class RoomProjectRunEvidenceIndexer(
    private val remoteDao: RemoteDao,
    private val localSearchDao: LocalSearchDao,
) {
    suspend fun refreshProject(projectId: String) {
        val activeSourceKeys = mutableSetOf<String>()
        remoteDao.completedRunsForProject(projectId).forEach { run ->
            val raw = run.completionJson ?: return@forEach
            val completion = runCatching { parseRemoteCompletionEvidence(raw) }.getOrNull() ?: return@forEach
            if (completion.verification != RemoteCompletionVerification.VERIFIED_V2) return@forEach
            val prefix = "run:${run.id}:"
            val entries = buildList {
                add(Entry(completion.completionId ?: "completion", "完成摘要", completion.summary, raw.sha256()))
                completion.files.forEach { file ->
                    add(Entry(file.evidenceId ?: "file-${file.path.sha256().take(12)}", "变更文件", file.path, file.evidenceSha256 ?: file.path.sha256()))
                }
                completion.tests.forEach { test ->
                    val body = "${test.status} · ${test.command} · exit=${test.exitCode ?: "unknown"}"
                    add(Entry(test.evidenceId ?: "test-${body.sha256().take(12)}", "测试证据", body, test.evidenceSha256 ?: body.sha256()))
                }
                completion.gitState?.let { git -> add(Entry("git", "Git 状态", git, git.sha256())) }
                completion.unresolved.forEachIndexed { index, item ->
                    add(Entry("unresolved-${index + 1}-${item.sha256().take(12)}", "遗留项", item, item.sha256()))
                }
            }
            entries.forEachIndexed { ordinal, entry ->
                val sourceKey = prefix + entry.id
                activeSourceKeys += sourceKey
                val document = LocalSearchDocumentEntity(
                    id = "project:$projectId:${sourceKey.sha256().take(32)}",
                    type = ProjectSourceType.RUN_EVIDENCE.name,
                    title = run.objective,
                    body = entry.body,
                    conversationId = null,
                    messageId = null,
                    projectId = projectId,
                    updatedAt = run.completedAt ?: run.updatedAt,
                    sourceType = ProjectSourceType.RUN_EVIDENCE.name,
                    authority = ProjectSourceAuthority.VERIFIED_RUN.name,
                    sourceKey = sourceKey,
                    relativePath = null,
                    headingPath = entry.label,
                    ordinal = ordinal,
                    searchableText = LocalSearchTokenizer.indexedText("${run.objective} ${entry.label}", entry.body),
                    sourceSha256 = entry.sha256,
                    gitBlobId = null,
                    sourceUpdatedAt = run.completedAt ?: run.updatedAt,
                    indexedAt = System.currentTimeMillis(),
                    dirty = false,
                )
                localSearchDao.replaceProjectSourceDocuments(
                    projectId,
                    sourceKey,
                    listOf(document),
                    listOf(document.searchableText),
                )
            }
        }
        (localSearchDao.runEvidenceSourceKeys(projectId).toSet() - activeSourceKeys).forEach { stale ->
            localSearchDao.replaceProjectSourceDocuments(projectId, stale, emptyList(), emptyList())
        }
    }

    private data class Entry(val id: String, val label: String, val body: String, val sha256: String)
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
