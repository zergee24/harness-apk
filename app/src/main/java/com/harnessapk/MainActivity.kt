package com.harnessapk

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.harnessapk.chat.ChatExecutionService
import com.harnessapk.capture.toIncomingShareRequest
import com.harnessapk.agent.H_BUNDLE_MIME_TYPE
import com.harnessapk.agent.externalAgentBundleUri
import com.harnessapk.packageformat.CONFIG_PACKAGE_MIME_TYPE
import com.harnessapk.ui.HarnessApkApp
import com.harnessapk.ui.theme.HarnessApkTheme
import com.harnessapk.wiki.H_WIKI_MIME_TYPE
import com.harnessapk.wiki.isGenericWikiPackageMimeType
import com.harnessapk.wiki.wikiPackageUri
import com.harnessapk.wiki.wikiPersistableReadPermissionFlags
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var incomingAgentBundleUri by mutableStateOf<String?>(null)
    private var incomingWikiPackageUri by mutableStateOf<String?>(null)
    private var incomingConfigPackageUri by mutableStateOf<String?>(null)
    private var incomingRemoteRunId by mutableStateOf<String?>(null)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptIncomingIntent(intent)
        maybeRequestNotificationPermission()
        setContent {
            HarnessApkTheme {
                HarnessApkApp(
                    incomingAgentBundleUri = incomingAgentBundleUri?.let(Uri::parse),
                    onIncomingAgentBundleUriConsumed = { incomingAgentBundleUri = null },
                    incomingWikiPackageUri = incomingWikiPackageUri?.let(Uri::parse),
                    onIncomingWikiPackageUriConsumed = { incomingWikiPackageUri = null },
                    incomingConfigPackageUri = incomingConfigPackageUri?.let(Uri::parse),
                    onIncomingConfigPackageUriConsumed = { incomingConfigPackageUri = null },
                    incomingRemoteRunId = incomingRemoteRunId,
                    onIncomingRemoteRunConsumed = { incomingRemoteRunId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val container = (application as HarnessApkApplication).container
        lifecycleScope.launch {
            if (container.chatExecutionRepository.hasOpenWork()) {
                ChatExecutionService.start(this@MainActivity)
            }
        }
    }

    // Android 13+ 角标/提醒通知都依赖 POST_NOTIFICATIONS 运行时授权；
    // 拒绝后系统自动转"不再询问"，冷启动的重复请求会自行哑火。
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun acceptIncomingIntent(intent: Intent) {
        incomingRemoteRunId = intent.getStringExtra(EXTRA_REMOTE_RUN_ID)?.takeIf(String::isNotBlank)
        incomingAgentBundleUri = null
        incomingWikiPackageUri = null
        incomingConfigPackageUri = null
        val packageUri = intent.wikiPackageUri()
        val packageHandled = when {
            intent.type == CONFIG_PACKAGE_MIME_TYPE && packageUri != null -> {
                incomingConfigPackageUri = packageUri.toString()
                true
            }
            intent.type == H_WIKI_MIME_TYPE && packageUri != null -> {
                acceptWikiPackage(intent, packageUri)
                true
            }
            intent.type == H_BUNDLE_MIME_TYPE -> {
                incomingAgentBundleUri = intent.agentBundleUri()
                true
            }
            intent.action == Intent.ACTION_SEND &&
                isGenericWikiPackageMimeType(intent.type) &&
                packageUri != null -> {
                when {
                    contentResolver.displayName(packageUri).isHconfigFileName() -> {
                        incomingConfigPackageUri = packageUri.toString()
                        true
                    }
                    contentResolver.displayName(packageUri).isHwikiFileName() -> {
                        acceptWikiPackage(intent, packageUri)
                        true
                    }
                    contentResolver.displayName(packageUri).isHbundleFileName() -> {
                        incomingAgentBundleUri = intent.agentBundleUri()
                        true
                    }
                    else -> false
                }
            }
            else -> false
        }
        if (!packageHandled) {
            intent.toIncomingShareRequest(contentResolver)?.let { request ->
                setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                lifecycleScope.launch {
                    runCatching {
                        (application as HarnessApkApplication).container.captureImportCoordinator.stage(request)
                    }
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

    companion object {
        const val EXTRA_REMOTE_RUN_ID = "com.harnessapk.extra.REMOTE_RUN_ID"
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

private fun String?.isHbundleFileName(): Boolean = this?.endsWith(".hbundle", ignoreCase = true) == true

private fun String?.isHconfigFileName(): Boolean = this?.endsWith(".hconfig", ignoreCase = true) == true
