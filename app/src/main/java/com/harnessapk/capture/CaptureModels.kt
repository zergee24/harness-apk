package com.harnessapk.capture

enum class CaptureSource { TYPED, VOICE, ANDROID_SHARE }
enum class CaptureStatus { STAGING, READY, CONSUMED, FAILED, EXPIRED }
enum class CaptureItemKind { IMAGE, FILE }

data class CaptureItem(
    val id: String,
    val kind: CaptureItemKind,
    val displayName: String,
    val mimeType: String,
    val localUri: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class CaptureDraft(
    val id: String,
    val source: CaptureSource,
    val text: String,
    val stagedItems: List<CaptureItem>,
    val status: CaptureStatus,
    val createdAt: Long,
    val expiresAt: Long?,
)

data class IncomingShareItem(
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val declaredSizeBytes: Long?,
)

data class IncomingShareRequest(
    val text: String,
    val items: List<IncomingShareItem>,
)

data class CaptureTransferState(
    val active: Boolean = false,
    val completedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
)

internal const val MAX_CAPTURE_ITEM_BYTES = 50L * 1024L * 1024L
internal const val MAX_CAPTURE_TOTAL_BYTES = 100L * 1024L * 1024L
internal const val MAX_CAPTURE_ITEMS = 10
internal const val CAPTURE_EXPIRY_MILLIS = 24L * 60L * 60L * 1_000L
