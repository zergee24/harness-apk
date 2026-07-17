package com.harnessapk.agent

internal const val H_BUNDLE_MIME_TYPE = "application/vnd.harness.hbundle"

internal fun externalAgentBundleUri(
    action: String?,
    viewUri: String?,
    sharedUri: String?,
): String? = when (action) {
    "android.intent.action.VIEW" -> viewUri
    "android.intent.action.SEND" -> sharedUri
    else -> null
}
