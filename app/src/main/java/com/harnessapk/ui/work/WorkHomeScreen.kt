package com.harnessapk.ui.work

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harnessapk.ui.remote.ZcodeWebRemoteActivity
import com.harnessapk.ui.theme.HarnessSpacing

// 工作模式首页：远程代理为主入口（Codex / ZCode / DSH），
// 本地项目工作台（含自主 Git）降级为次级入口。
@Composable
internal fun WorkHomeScreen(
    contentPadding: PaddingValues,
    remotePaired: Boolean,
    onOpenCodex: () -> Unit,
    onOpenDsh: () -> Unit,
    onOpenWorkbench: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = HarnessSpacing.pageHorizontal, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkHomeEntry(
            testTag = "work-home-codex",
            title = "Codex",
            supportingText = if (remotePaired) "Mac 节点已配对 · 远程会话与任务" else "扫描 Mac Bridge 二维码完成配对",
            icon = Icons.Outlined.Dns,
            onClick = onOpenCodex,
        )
        WorkHomeEntry(
            testTag = "work-home-zcode",
            title = "ZCode",
            supportingText = "远程控制网页 · 独立于节点配对",
            icon = Icons.Outlined.Terminal,
            onClick = { context.startActivity(Intent(context, ZcodeWebRemoteActivity::class.java)) },
        )
        WorkHomeEntry(
            testTag = "work-home-dsh",
            title = "DSH",
            supportingText = if (remotePaired) {
                "DeepSeek Harness · 续接 Mac 上的会话"
            } else {
                "需先配对 Mac Bridge 节点"
            },
            icon = Icons.Outlined.Monitor,
            onClick = onOpenDsh,
        )
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Text(
            text = "本地",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WorkHomeEntry(
            testTag = "work-home-workbench",
            title = "项目工作台",
            supportingText = "本地项目 · 文件 · Git",
            icon = Icons.Outlined.Folder,
            onClick = onOpenWorkbench,
        )
    }
}

@Composable
private fun WorkHomeEntry(
    testTag: String,
    title: String,
    supportingText: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val enabled = onClick != null
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
            .alpha(if (enabled) 1f else 0.6f),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .clickable(enabled = enabled) { onClick?.invoke() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabled) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
