package com.harnessapk.projectsearch

import java.nio.charset.StandardCharsets

data class ProjectIndexSource(
    val projectId: String,
    val sourceType: ProjectSourceType,
    val authority: ProjectSourceAuthority,
    val sourceKey: String,
    val relativePath: String?,
    val title: String,
    val body: String,
    val sourceSha256: String,
    val sourceUpdatedAt: Long,
)

data class ProjectIndexedSource(
    val source: ProjectIndexSource,
    val chunks: List<MarkdownSemanticChunk>,
    val bodyIndexed: Boolean,
)

data class ProjectIndexResult(
    val indexedSourceKeys: List<String> = emptyList(),
    val metadataOnlySourceKeys: List<String> = emptyList(),
    val failedSourceKeys: List<String> = emptyList(),
    val indexedBodyBytes: Long = 0,
    val rejected: Boolean = false,
)

fun interface ProjectIndexSink {
    fun replace(source: ProjectIndexedSource)
}

interface ProjectIndexDirtyStore {
    fun markDirty(projectId: String, sourceKey: String)
    fun clearDirty(projectId: String, sourceKey: String)
}

class ProjectIndexCoordinator(
    private val sink: ProjectIndexSink,
    private val dirtyStore: ProjectIndexDirtyStore,
    private val chunker: MarkdownSemanticChunker = MarkdownSemanticChunker(),
    private val maxFileBytes: Long = 2L * 1024 * 1024,
    private val maxProjectBodyBytes: Long = 50L * 1024 * 1024,
) {
    init {
        require(maxFileBytes > 0)
        require(maxProjectBodyBytes > 0)
    }

    fun indexProject(projectId: String, sources: List<ProjectIndexSource>): ProjectIndexResult {
        val normalizedProjectId = projectId.trim()
        if (
            normalizedProjectId.isEmpty() ||
            sources.map(ProjectIndexSource::sourceKey).distinct().size != sources.size ||
            sources.any {
                it.projectId != normalizedProjectId ||
                    (it.sourceType in FILE_SOURCE_TYPES && it.relativePath.isNullOrBlank())
            }
        ) {
            return ProjectIndexResult(rejected = true)
        }

        val indexed = mutableListOf<String>()
        val metadataOnly = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var projectBodyBytes = 0L

        sources.sortedBy(ProjectIndexSource::sourceKey).forEach { source ->
            dirtyStore.markDirty(normalizedProjectId, source.sourceKey)
            val fileSource = source.sourceType in FILE_SOURCE_TYPES
            val bodyBytes = source.body.toByteArray(StandardCharsets.UTF_8).size.toLong()
            val bodyIndexed = !fileSource || (
                bodyBytes <= maxFileBytes && projectBodyBytes + bodyBytes <= maxProjectBodyBytes
                )
            val chunks = if (bodyIndexed) {
                chunksFor(source)
            } else {
                listOf(metadataChunk(source))
            }

            try {
                sink.replace(ProjectIndexedSource(source, chunks, bodyIndexed))
                dirtyStore.clearDirty(normalizedProjectId, source.sourceKey)
                if (bodyIndexed) {
                    indexed += source.sourceKey
                    if (fileSource) projectBodyBytes += bodyBytes
                } else {
                    metadataOnly += source.sourceKey
                }
            } catch (_: Exception) {
                failed += source.sourceKey
            }
        }

        return ProjectIndexResult(
            indexedSourceKeys = indexed,
            metadataOnlySourceKeys = metadataOnly,
            failedSourceKeys = failed,
            indexedBodyBytes = projectBodyBytes,
        )
    }

    private fun chunksFor(source: ProjectIndexSource): List<MarkdownSemanticChunk> =
        if (source.sourceType in FILE_SOURCE_TYPES) {
            chunker.chunk(source.relativePath.orEmpty(), source.body)
        } else if (source.body.isBlank()) {
            emptyList()
        } else {
            listOf(MarkdownSemanticChunk(headingPath = source.title, ordinal = 0, text = source.body))
        }

    private fun metadataChunk(source: ProjectIndexSource): MarkdownSemanticChunk {
        val metadata = listOfNotNull(source.relativePath, source.title.takeIf(String::isNotBlank))
            .distinct()
            .joinToString("\n")
        return MarkdownSemanticChunk(headingPath = source.title, ordinal = 0, text = metadata)
    }

    private companion object {
        val FILE_SOURCE_TYPES = setOf(ProjectSourceType.CONTEXT, ProjectSourceType.MARKDOWN)
    }
}
