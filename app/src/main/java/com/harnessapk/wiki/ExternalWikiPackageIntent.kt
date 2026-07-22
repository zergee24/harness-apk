package com.harnessapk.wiki

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal const val H_WIKI_MIME_TYPE = "application/vnd.harness.hwiki+zip"
internal const val WIKI_URI_READ_PERMISSION = Intent.FLAG_GRANT_READ_URI_PERMISSION
internal const val WIKI_URI_WRITE_PERMISSION = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
internal const val WIKI_URI_PERSISTABLE_PERMISSION = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

internal fun externalWikiPackageUri(
    action: String?,
    viewUri: String?,
    sharedUri: String?,
): String? = when (action) {
    Intent.ACTION_VIEW -> viewUri
    Intent.ACTION_SEND -> sharedUri
    else -> null
}

internal fun Intent.wikiPackageUri(): Uri? = externalWikiPackageUri(
    action = action,
    viewUri = dataString,
    sharedUri = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.toString(),
)?.let(Uri::parse)

internal fun wikiPersistableReadPermissionFlags(intentFlags: Int): Int {
    val required = WIKI_URI_READ_PERMISSION or WIKI_URI_PERSISTABLE_PERMISSION
    return if (intentFlags and required == required) required else 0
}

internal fun isGenericWikiPackageMimeType(mimeType: String?): Boolean = mimeType == "application/zip" ||
    mimeType == "application/octet-stream"

internal fun Intent.isWikiPackageIntent(): Boolean = type == H_WIKI_MIME_TYPE && wikiPackageUri() != null

internal fun Intent.isGenericWikiPackageCandidate(): Boolean =
    action == Intent.ACTION_SEND && isGenericWikiPackageMimeType(type) && wikiPackageUri() != null
