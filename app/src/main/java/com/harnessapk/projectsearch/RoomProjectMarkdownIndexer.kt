package com.harnessapk.projectsearch

import com.harnessapk.search.LocalSearchTokenizer
import com.harnessapk.storage.LocalSearchDao
import com.harnessapk.storage.LocalSearchDocumentEntity
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class ProjectMarkdownIndexReport(
    val projectId: String,
    val indexedFiles: Int,
    val indexedChunks: Int,
    val metadataOnlyFiles: Int,
    val failedSources: List<String>,
    val indexedBodyBytes: Long,
)

class RoomProjectMarkdownIndexer(
    private val dao: LocalSearchDao,
    private val chunker: MarkdownSemanticChunker = MarkdownSemanticChunker(),
    private val maxFileBytes: Long = 2L * 1024 * 1024,
    private val maxProjectBodyBytes: Long = 50L * 1024 * 1024,
) {
    suspend fun refreshProject(projectId: String, projectRoot: File): ProjectMarkdownIndexReport {
        require(projectId.isNotBlank())
        val canonicalRoot = projectRoot.canonicalFile
        require(canonicalRoot.isDirectory) { "项目目录不存在" }
        val files = canonicalRoot.walkTopDown()
            .onEnter { directory ->
                directory == canonicalRoot ||
                    (!directory.name.startsWith('.') && !Files.isSymbolicLink(directory.toPath()))
            }
            .filter { file ->
                file.isFile &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    file.extension.lowercase() in setOf("md", "markdown") &&
                    file.canonicalPath.startsWith(canonicalRoot.path + File.separator)
            }
            .sortedBy { it.relativeTo(canonicalRoot).invariantSeparatorsPath }
            .toList()

        val activeSourceKeys = mutableSetOf<String>()
        val failed = mutableListOf<String>()
        var indexedFiles = 0
        var indexedChunks = 0
        var metadataOnlyFiles = 0
        var bodyBytes = 0L

        files.forEach { file ->
            val relativePath = file.relativeTo(canonicalRoot).invariantSeparatorsPath
            val sourceKey = "file:$relativePath"
            activeSourceKeys += sourceKey
            dao.markProjectSourceDirty(projectId, sourceKey)
            runCatching {
                val fileBytes = file.length()
                val bodyAllowed = fileBytes <= maxFileBytes && bodyBytes + fileBytes <= maxProjectBodyBytes
                val bytes = if (bodyAllowed) file.readBytes() else ByteArray(0)
                val markdown = if (bodyAllowed) bytes.toString(Charsets.UTF_8) else ""
                val sourceSha = if (bodyAllowed) sha256(bytes) else sha256("$relativePath:${file.length()}:${file.lastModified()}".encodeToByteArray())
                val chunks = if (bodyAllowed) {
                    chunker.chunk(relativePath, markdown)
                } else {
                    listOf(MarkdownSemanticChunk(relativePath, 0, relativePath))
                }
                val sourceType = if (relativePath.substringAfterLast('/').equals("context.md", true)) {
                    ProjectSourceType.CONTEXT
                } else {
                    ProjectSourceType.MARKDOWN
                }
                val documents = chunks.map { chunk ->
                    LocalSearchDocumentEntity(
                        id = "project:$projectId:${sha256("$sourceKey:${chunk.headingPath}:${chunk.ordinal}:$sourceSha".encodeToByteArray()).take(32)}",
                        type = sourceType.name,
                        title = relativePath.substringAfterLast('/'),
                        body = chunk.text,
                        conversationId = null,
                        messageId = null,
                        projectId = projectId,
                        updatedAt = file.lastModified(),
                        sourceType = sourceType.name,
                        authority = ProjectSourceAuthority.REVIEWED_ARTIFACT.name,
                        sourceKey = sourceKey,
                        relativePath = relativePath,
                        headingPath = chunk.headingPath,
                        ordinal = chunk.ordinal,
                        searchableText = LocalSearchTokenizer.indexedText(
                            "$relativePath ${chunk.headingPath}",
                            chunk.text,
                        ),
                        sourceSha256 = sourceSha,
                        gitBlobId = null,
                        sourceUpdatedAt = file.lastModified(),
                        indexedAt = System.currentTimeMillis(),
                        dirty = false,
                    )
                }
                dao.replaceProjectSourceDocuments(
                    projectId,
                    sourceKey,
                    documents,
                    documents.map(LocalSearchDocumentEntity::searchableText),
                )
                if (bodyAllowed) {
                    indexedFiles += 1
                    indexedChunks += chunks.size
                    bodyBytes += fileBytes
                } else {
                    metadataOnlyFiles += 1
                }
            }.onFailure {
                failed += sourceKey
            }
        }
        if (failed.isEmpty()) {
            (dao.markdownSourceKeys(projectId).toSet() - activeSourceKeys).forEach { staleKey ->
                dao.replaceProjectSourceDocuments(projectId, staleKey, emptyList(), emptyList())
            }
        }
        return ProjectMarkdownIndexReport(
            projectId = projectId,
            indexedFiles = indexedFiles,
            indexedChunks = indexedChunks,
            metadataOnlyFiles = metadataOnlyFiles,
            failedSources = failed,
            indexedBodyBytes = bodyBytes,
        )
    }

    suspend fun deleteProject(projectId: String) {
        dao.markdownSourceKeys(projectId).forEach { sourceKey ->
            dao.documentIdsForProjectSource(projectId, sourceKey).forEach { id -> dao.deleteFts(id) }
        }
        dao.deleteProjectMarkdownDocuments(projectId)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
