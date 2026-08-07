package com.harnessapk.capture

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.harnessapk.agent.H_BUNDLE_MIME_TYPE
import com.harnessapk.wiki.H_WIKI_MIME_TYPE

enum class IncomingShareRouteKind {
    AGENT_BUNDLE,
    WIKI_PACKAGE,
    ORDINARY_SHARE,
    NONE,
}

fun classifyIncomingShare(
    action: String?,
    mimeType: String?,
    displayNames: List<String>,
    hasText: Boolean,
    streamCount: Int,
): IncomingShareRouteKind {
    val names = displayNames.map(String::lowercase)
    if (mimeType == H_WIKI_MIME_TYPE || names.any { it.endsWith(".hwiki") }) {
        return IncomingShareRouteKind.WIKI_PACKAGE
    }
    if (mimeType == H_BUNDLE_MIME_TYPE || names.any { it.endsWith(".hbundle") }) {
        return IncomingShareRouteKind.AGENT_BUNDLE
    }
    val shareAction = action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    return if (shareAction && (hasText || streamCount > 0)) {
        IncomingShareRouteKind.ORDINARY_SHARE
    } else {
        IncomingShareRouteKind.NONE
    }
}

fun Intent.toIncomingShareRequest(contentResolver: ContentResolver): IncomingShareRequest? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val uris = when (action) {
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
            this,
            Intent.EXTRA_STREAM,
            Uri::class.java,
        ).orEmpty()
        else -> listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
    }
    val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
    val metadata = uris.map { uri -> contentResolver.shareMetadata(uri, type) }
    if (
        classifyIncomingShare(
            action = action,
            mimeType = type,
            displayNames = metadata.map(IncomingShareItem::displayName),
            hasText = text.isNotBlank(),
            streamCount = metadata.size,
        ) != IncomingShareRouteKind.ORDINARY_SHARE
    ) {
        return null
    }
    return IncomingShareRequest(text = text, items = metadata)
}

private fun ContentResolver.shareMetadata(uri: Uri, fallbackMimeType: String?): IncomingShareItem {
    var displayName: String? = null
    var sizeBytes: Long? = null
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
            }
        }
    }
    val mimeType = getType(uri)?.substringBefore(';')
        ?: fallbackMimeType?.substringBefore(';')
        ?: "application/octet-stream"
    return IncomingShareItem(
        sourceUri = uri.toString(),
        displayName = displayName?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "shared-file",
        mimeType = mimeType,
        declaredSizeBytes = sizeBytes,
    )
}
