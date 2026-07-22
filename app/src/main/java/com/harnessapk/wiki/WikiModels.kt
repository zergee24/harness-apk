package com.harnessapk.wiki

import com.harnessapk.packageformat.PublisherFingerprint
import java.nio.file.Path

data class WikiRef(
    val wikiId: String,
    val version: Int,
)

enum class GeneratedPages(
    val wireValue: String,
) {
    NONE("none"),
    PARTIAL("partial"),
    COMPLETE("complete"),
    ;

    companion object {
        fun fromWire(value: String): GeneratedPages =
            entries.singleOrNull { it.wireValue == value }
                ?: throw WikiPackageException("capabilities.generatedPages 不受支持：$value")
    }
}

data class WikiCapabilities(
    val sourceHierarchy: Boolean,
    val sourceSearch: Boolean,
    val hierarchicalSummaries: Boolean,
    val termIndex: Boolean,
    val temporalAnnotations: Boolean,
    val crossWikiLinks: Boolean,
    val generatedPages: GeneratedPages,
    val claimGraph: Boolean,
    val vectorIndex: Boolean,
    val sourceAttachments: Boolean,
)

data class WikiManifest(
    val ref: WikiRef,
    val title: String,
    val description: String,
    val languages: List<String>,
    val contentHash: String,
    val publisherKeyId: String,
    val publisherName: String,
    val conceptNamespace: String,
    val conceptRegistryHash: String,
    val builderProfile: String,
    val capabilities: WikiCapabilities,
)

data class WikiImportInspection(
    val manifest: WikiManifest,
    val publisherFingerprint: PublisherFingerprint,
    val archiveSizeBytes: Long,
    val contentSizeBytes: Long,
    val stagedDatabase: Path,
    val manifestJson: String,
)

open class WikiPackageException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

fun interface WikiDatabaseInspector {
    fun inspect(stagedDatabase: Path)
}
