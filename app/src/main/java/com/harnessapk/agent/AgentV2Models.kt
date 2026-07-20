package com.harnessapk.agent

import java.io.File

sealed class ParsedAgentPackage {
    abstract val file: File
    abstract val packageSha256: String
    abstract val publisherPublicKey: ByteArray
    abstract val publisherFingerprint: String
    abstract val manifestJson: String
    abstract val compressedSizeBytes: Long
    abstract val uncompressedSizeBytes: Long
}

data class V1Bundle(
    val bundle: ParsedAgentBundle,
) : ParsedAgentPackage() {
    override val file: File get() = bundle.file
    override val packageSha256: String get() = bundle.packageSha256
    override val publisherPublicKey: ByteArray get() = bundle.publisherPublicKey
    override val publisherFingerprint: String get() = bundle.publisherFingerprint
    override val manifestJson: String get() = bundle.manifestJson
    override val compressedSizeBytes: Long get() = bundle.compressedSizeBytes
    override val uncompressedSizeBytes: Long get() = bundle.uncompressedSizeBytes
}

data class V2Bundle(
    override val file: File,
    override val packageSha256: String,
    override val publisherPublicKey: ByteArray,
    override val publisherFingerprint: String,
    override val manifestJson: String,
    override val compressedSizeBytes: Long,
    override val uncompressedSizeBytes: Long,
    val manifest: V2BundleManifest,
    val profile: V2InstallProfile,
    val agent: V2Agent,
    val corpora: List<V2Corpus>,
    val sources: List<V2Source>,
    val selectedCorpusIds: List<String>,
) : ParsedAgentPackage()

data class V2Agent(
    override val file: File,
    override val packageSha256: String,
    override val publisherPublicKey: ByteArray,
    override val publisherFingerprint: String,
    override val manifestJson: String,
    override val compressedSizeBytes: Long,
    override val uncompressedSizeBytes: Long,
    val manifest: V2AgentManifest,
    val persona: String,
    val identity: V2Identity,
    val voice: V2Voice,
    val worldview: List<V2Worldview>,
    val episodes: List<V2Episode>,
    val concepts: List<V2Concept>,
    val examples: List<V2Example>,
    val openers: V2Openers,
    val evaluations: List<V2Evaluation>,
    val installPlanJson: String,
    val installPlan: V2InstallPlan,
    val isRunnable: Boolean,
) : ParsedAgentPackage()

data class V2Corpus(
    override val file: File,
    override val packageSha256: String,
    override val publisherPublicKey: ByteArray,
    override val publisherFingerprint: String,
    override val manifestJson: String,
    override val compressedSizeBytes: Long,
    override val uncompressedSizeBytes: Long,
    val manifest: V2CorpusManifest,
    val sources: List<V2SourceRecord>,
    val nodeCount: Int,
    val chunkCount: Int,
    val duplicateCount: Int,
    val validationDiagnostics: V2CorpusValidationDiagnostics,
) : ParsedAgentPackage()

data class V2CorpusValidationDiagnostics(
    val backend: String,
    val indexedRecordCount: Long,
    val peakInMemoryRecordCount: Int,
    val diskBytes: Long,
)

data class V2Source(
    override val file: File,
    override val packageSha256: String,
    override val publisherPublicKey: ByteArray,
    override val publisherFingerprint: String,
    override val manifestJson: String,
    override val compressedSizeBytes: Long,
    override val uncompressedSizeBytes: Long,
    val manifest: V2SourceManifest,
) : ParsedAgentPackage()

enum class V2PackageType(val wireName: String) {
    BUNDLE("hbundle"),
    AGENT("hagent"),
    CORPUS("hcorpus"),
    SOURCE("hsource"),
}

enum class V2InstallClass(val wireName: String) {
    REQUIRED("required"),
    RECOMMENDED("recommended"),
    OPTIONAL("optional"),
    SOURCE("source"),
}

enum class V2SourceGenre(val wireName: String) {
    ESSAY("essay"),
    SPEECH("speech"),
    CONVERSATION("conversation"),
    LETTER("letter"),
    INTERVIEW("interview"),
    MEMOIR("memoir"),
    SECONDARY("secondary"),
    UNKNOWN("unknown"),
}

enum class V2Authorship(val wireName: String) {
    DIRECT("direct"),
    EDITED_DIRECT("edited_direct"),
    SECONDARY("secondary"),
    UNKNOWN("unknown"),
}

data class V2BundleManifest(
    val agent: V2BundleAgentDeclaration,
    val profileId: String,
    val selectedPackageIds: List<String>,
)

data class V2BundleAgentDeclaration(
    val id: String,
    val version: Int,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class V2AgentManifest(
    val id: String,
    val name: String,
    val version: Int,
    val requiredCorpora: List<String>,
    val runnableWithoutCorpora: Boolean,
)

data class V2CorpusManifest(
    val id: String,
    val agentId: String,
    val version: Int,
    val installClass: V2InstallClass,
    val chunkCount: Int,
    val sourceIds: List<String>,
    val sourceHashes: List<String>,
    val periods: List<String>,
    val genres: List<V2SourceGenre>,
    val authorship: List<V2Authorship>,
    val topLevelIds: List<String>,
    val coverage: List<String>,
)

data class V2SourceManifest(
    val id: String,
    val agentId: String,
    val version: Int,
    val sourceId: String,
    val sourceHash: String,
    val fileName: String,
    val storedName: String,
    val rawSizeBytes: Long,
)

data class V2Identity(
    val selfNames: List<String>,
    val timeHorizon: String,
    val roles: List<String>,
    val relationships: List<V2Relationship>,
)

data class V2Relationship(
    val subject: String,
    val relation: String,
    val period: String,
    val evidence: List<String>,
)

data class V2Voice(
    val defaultForm: String,
    val sentenceRhythm: List<String>,
    val rhetoricalMoves: List<String>,
    val preferredTerms: List<String>,
    val avoidPatterns: List<String>,
    val evidence: List<String>,
)

data class V2Worldview(
    val id: String,
    val topic: String,
    val statement: String,
    val conditions: List<String>,
    val period: String,
    val aliases: List<String>,
    val confidence: Double,
    val evidence: List<String>,
)

data class V2Episode(
    val id: String,
    val period: String,
    val location: String,
    val participants: List<String>,
    val summary: String,
    val meaning: String,
    val evidence: List<String>,
)

data class V2Concept(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val keywords: List<String>,
    val evidence: List<String>,
)

data class V2Example(
    val id: String,
    val intent: String,
    val user: String,
    val assistant: String,
    val styleTags: List<String>,
    val generationType: String,
    val evidence: List<String>,
)

data class V2Openers(
    val default: String,
    val alternatives: List<String>,
)

data class V2Evaluation(
    val id: String,
    val category: String,
    val question: String,
    val period: String,
    val expectedEvidence: List<String>,
    val corpusId: String,
)

data class V2InstallPlan(
    val packages: List<V2InstallPackage>,
    val profiles: List<V2InstallProfile>,
    val recommendedProfileId: String,
    val requiredCorpusIds: List<String>,
)

data class V2InstallPackage(
    val id: String,
    val type: V2PackageType,
    val fileName: String,
    val installClass: V2InstallClass,
    val dependencies: List<String>,
    val sizeBytes: Long,
    val sha256: String,
)

data class V2InstallProfile(
    val id: String,
    val packageIds: List<String>,
    val recommended: Boolean,
)

data class V2SourceRecord(
    val sourceId: String,
    val title: String,
    val fileName: String,
    val storedName: String,
    val sourceHash: String,
    val format: String,
    val genre: V2SourceGenre,
    val authorship: V2Authorship,
    val period: String,
    val rawSizeBytes: Long,
    val extractedChars: Long,
)

data class V2HierarchyNode(
    val id: String,
    val kind: String,
    val title: String,
    val sourceId: String,
    val parentId: String?,
    val path: List<String>,
    val summary: String,
)

data class V2Chunk(
    val id: String,
    val sourceId: String,
    val sourceHash: String,
    val sourceTitle: String,
    val genre: V2SourceGenre,
    val authorship: V2Authorship,
    val period: String,
    val location: String,
    val parentIds: List<String>,
    val context: String,
    val text: String,
    val keywords: List<String>,
    val ngrams: List<String>,
    val conflictKey: String,
    val duplicateGroup: String,
    val sourceAliases: List<String>,
    val simHash: String,
)

data class V2Duplicate(
    val duplicateChunkId: String,
    val physicalChunkId: String,
    val duplicateSourceId: String,
    val primarySourceId: String,
    val matchType: String,
    val period: String,
    val conflictKey: String,
)
