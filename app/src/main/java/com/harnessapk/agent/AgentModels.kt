package com.harnessapk.agent

import java.io.File

enum class AgentStatus {
    READY,
    WAITING_FOR_CORPUS,
    DISABLED,
    DRAFT,
    FAILED,
}

data class AgentPackageManifest(
    val id: String,
    val name: String,
    val version: Int,
    val summary: String,
    val personaPath: String,
    val worldviewPath: String,
    val conceptsPath: String,
    val examplesPath: String,
    val evalPath: String,
    val requiredCorpora: List<String>,
)

data class AgentCorpusManifest(
    val id: String,
    val title: String,
    val sourceHash: String,
    val sourcesPath: String,
    val chunksPath: String,
    val required: Boolean,
)

data class AgentCorpusChunk(
    val id: String,
    val sourceTitle: String,
    val sourceHash: String,
    val location: String,
    val text: String,
    val keywords: List<String>,
    val ngrams: List<String>,
)

data class ParsedAgentBundle(
    val file: File,
    val packageSha256: String,
    val publisherPublicKey: ByteArray,
    val publisherFingerprint: String,
    val manifestJson: String,
    val agent: AgentPackageManifest,
    val corpora: List<AgentCorpusManifest>,
    val persona: String,
    val worldviewJsonl: String,
    val compressedSizeBytes: Long,
    val uncompressedSizeBytes: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is ParsedAgentBundle &&
            file == other.file &&
            packageSha256 == other.packageSha256 &&
            publisherPublicKey.contentEquals(other.publisherPublicKey) &&
            publisherFingerprint == other.publisherFingerprint &&
            manifestJson == other.manifestJson &&
            agent == other.agent &&
            corpora == other.corpora &&
            persona == other.persona &&
            worldviewJsonl == other.worldviewJsonl &&
            compressedSizeBytes == other.compressedSizeBytes &&
            uncompressedSizeBytes == other.uncompressedSizeBytes

    override fun hashCode(): Int = 31 * packageSha256.hashCode() + publisherPublicKey.contentHashCode()
}

data class AgentImportPreview(
    val agentId: String,
    val name: String,
    val version: Int,
    val summary: String,
    val publisherFingerprint: String,
    val corpora: List<String>,
    val compressedSizeBytes: Long,
    val includesOriginalSources: Boolean,
)

data class Agent(
    val id: String,
    val name: String,
    val summary: String,
    val activeVersion: Int,
    val publisherFingerprint: String,
    val status: AgentStatus,
    val requiredCorpusCount: Int,
    val installedCorpusCount: Int,
)

data class AgentImportSession(
    val id: String,
    val stagedFile: File,
    val parsedBundle: ParsedAgentBundle,
    val preview: AgentImportPreview,
)

data class AgentPackageImportSession(
    val id: String,
    val sourceName: String,
    val stagedFile: File,
    val parsedPackage: ParsedAgentPackage,
    val publisherFingerprint: String,
    val packageSha256: String,
    val packageBytes: Long,
    val preview: AgentImportPreview,
)

enum class AgentInstallOutcome {
    INSTALLED,
    ALREADY_INSTALLED,
}

data class AgentInstallResult(
    val outcome: AgentInstallOutcome,
    val agent: Agent,
)

data class AgentEvidence(
    val chunkId: String,
    val sourceTitle: String,
    val location: String,
    val text: String,
    val score: Int,
    val chunkKey: String = chunkId,
)

enum class AgentQueryIntent {
    RELATIONSHIP,
    EXACT_FACT,
    STANCE_METHOD,
    TEMPORAL,
    GLOBAL,
}

data class AgentRetrievalBudget(
    val stanceCount: Int,
    val episodeCount: Int,
    val exampleCount: Int,
    val chunkCount: Int,
    val characterCount: Int,
    val perSourceCount: Int,
    val requirePeriodDiversity: Boolean,
)

data class AgentContextRequest(
    val agentId: String,
    val version: Int,
    val query: String,
    val conversationMemory: String = "",
    val relationshipMemory: String = "",
    val projectContext: String = "",
    val sessionContext: String = "",
)

data class AgentRuntimeDiagnostics(
    val intent: AgentQueryIntent = AgentQueryIntent.EXACT_FACT,
    val selectedAssetIds: List<String> = emptyList(),
    val selectedAssetTotalCount: Int = selectedAssetIds.size,
    val selectedChunkKeys: List<String> = emptyList(),
    val selectedRouteIds: List<String> = emptyList(),
    val sourceCount: Int = 0,
    val periodCount: Int = 0,
    val duplicateGroupCount: Int = 0,
    val characterBudget: Int = 0,
    val selectedCharacterCount: Int = 0,
    val missingOptionalCoverage: List<String> = emptyList(),
    val hierarchyRoutingUsed: Boolean = false,
)

enum class AgentCorpusRemovalOutcome {
    REMOVED,
    REMOVED_CLEANUP_PENDING,
    REQUIRED,
    REFERENCED,
    NOT_INSTALLED,
}

data class AgentCorpusRemovalResult(
    val outcome: AgentCorpusRemovalOutcome,
)

data class AgentVersionCoverage(
    val agentId: String,
    val version: Int,
    val requiredCorpusCount: Int,
    val installedRequiredCorpusCount: Int,
    val installedCorpusCount: Int,
)

enum class AgentVersionRemovalOutcome {
    REMOVED,
    REMOVED_CLEANUP_PENDING,
    REFERENCED,
    ACTIVE,
    NOT_FOUND,
}

data class AgentVersionRemovalResult(
    val outcome: AgentVersionRemovalOutcome,
)

data class AgentRuntimeContext(
    val agentId: String,
    val version: Int,
    val systemPrompt: String,
    val evidence: List<AgentEvidence>,
    val diagnostics: AgentRuntimeDiagnostics = AgentRuntimeDiagnostics(),
)

class AgentBundleException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
