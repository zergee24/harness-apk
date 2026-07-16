package com.harnessapk.agent

import java.io.File

enum class AgentStatus {
    READY,
    WAITING_FOR_CORPUS,
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

data class AgentEvidence(
    val chunkId: String,
    val sourceTitle: String,
    val location: String,
    val text: String,
    val score: Int,
)

data class AgentRuntimeContext(
    val agentId: String,
    val version: Int,
    val systemPrompt: String,
    val evidence: List<AgentEvidence>,
)

class AgentBundleException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
