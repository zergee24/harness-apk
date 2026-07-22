package com.harnessapk

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.harnessapk.agent.H_BUNDLE_MIME_TYPE
import com.harnessapk.agent.externalAgentBundleUri
import com.harnessapk.ui.HarnessApkApp
import com.harnessapk.ui.theme.HarnessApkTheme
import com.harnessapk.wiki.H_WIKI_MIME_TYPE
import com.harnessapk.wiki.isGenericWikiPackageMimeType
import com.harnessapk.wiki.wikiPackageUri
import com.harnessapk.wiki.wikiPersistableReadPermissionFlags

class MainActivity : ComponentActivity() {
    private var incomingAgentBundleUri by mutableStateOf<String?>(null)
    private var incomingWikiPackageUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptIncomingIntent(intent)
        setContent {
            HarnessApkTheme {
                HarnessApkApp(
                    incomingAgentBundleUri = incomingAgentBundleUri?.let(Uri::parse),
                    onIncomingAgentBundleUriConsumed = { incomingAgentBundleUri = null },
                    incomingWikiPackageUri = incomingWikiPackageUri?.let(Uri::parse),
                    onIncomingWikiPackageUriConsumed = { incomingWikiPackageUri = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIncomingIntent(intent)
    }

    private fun acceptIncomingIntent(intent: Intent) {
        incomingAgentBundleUri = null
        incomingWikiPackageUri = null
        val packageUri = intent.wikiPackageUri()
        when {
            intent.type == H_WIKI_MIME_TYPE && packageUri != null -> acceptWikiPackage(intent, packageUri)
            intent.type == H_BUNDLE_MIME_TYPE -> incomingAgentBundleUri = intent.agentBundleUri()
            intent.action == Intent.ACTION_SEND &&
                isGenericWikiPackageMimeType(intent.type) &&
                packageUri != null -> {
                if (contentResolver.displayName(packageUri).isHwikiFileName()) {
                    acceptWikiPackage(intent, packageUri)
                } else {
                    incomingAgentBundleUri = intent.agentBundleUri()
                }
            }
        }
    }

    private fun acceptWikiPackage(intent: Intent, uri: Uri) {
        val permissionFlags = wikiPersistableReadPermissionFlags(intent.flags)
        if (permissionFlags != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, permissionFlags) }
        }
        incomingWikiPackageUri = uri.toString()
    }
}

private fun Intent.agentBundleUri(): String? = externalAgentBundleUri(
    action = action,
    viewUri = dataString,
    sharedUri = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.toString(),
)

private fun ContentResolver.displayName(uri: Uri): String? = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull() ?: uri.lastPathSegment

private fun String?.isHwikiFileName(): Boolean = this?.endsWith(".hwiki", ignoreCase = true) == true
