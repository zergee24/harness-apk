package com.harnessapk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.harnessapk.agent.externalAgentBundleUri
import com.harnessapk.ui.HarnessApkApp
import com.harnessapk.ui.theme.HarnessApkTheme

class MainActivity : ComponentActivity() {
    private var incomingAgentBundleUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingAgentBundleUri = intent.agentBundleUri()
        setContent {
            HarnessApkTheme {
                HarnessApkApp(
                    incomingAgentBundleUri = incomingAgentBundleUri?.let(Uri::parse),
                    onIncomingAgentBundleUriConsumed = { incomingAgentBundleUri = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingAgentBundleUri = intent.agentBundleUri()
    }
}

private fun Intent.agentBundleUri(): String? = externalAgentBundleUri(
    action = action,
    viewUri = dataString,
    sharedUri = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.toString(),
)
