package com.harnessapk.packageformat

import java.nio.file.Path

data class SignedPackagePolicy(
    val allowedPayloads: Set<String>,
    val maxArchiveBytes: Long,
    val maxExpandedBytes: Long,
    val maxEntryCount: Int,
    val maxEntryBytes: Long = maxExpandedBytes,
    val maxCompressionRatio: Long = 200L,
    val minCompressionRatioCheckBytes: Long = 1024L * 1024L,
    val maxPathDepth: Int = 12,
    val maxManifestBytes: Int = 4 * 1024 * 1024,
    val maxChecksumsBytes: Int = 16 * 1024 * 1024,
    val maxSignatureBytes: Int = 64 * 1024,
    val payloadSizeLimits: List<SignedPackagePayloadSizeLimit> = emptyList(),
    val manifestPath: String = "manifest.json",
    val packageLabel: String = "签名包",
)

data class SignedPackagePayloadSizeLimit(
    val paths: Set<String>,
    val maxBytes: Long,
    val label: String,
)

data class PublisherFingerprint(
    val algorithm: String,
    val keyId: String? = null,
    val hex: String,
)

data class VerifiedPackage(
    val stagedArchive: Path,
    val archiveSizeBytes: Long,
    val expandedSizeBytes: Long,
    val manifestBytes: ByteArray,
    val payloads: Map<String, VerifiedPackageEntry>,
    val publisherFingerprint: PublisherFingerprint,
    val publisherPublicKey: ByteArray,
)

data class VerifiedPackageEntry(
    val path: String,
    val compressedSizeBytes: Long,
    val uncompressedSizeBytes: Long,
    val sha256: String,
)

open class SignedPackageException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

fun interface Ed25519SignatureVerifier {
    fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean
}
