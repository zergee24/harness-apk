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
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Folder
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
import com.harnessapk.ui.dashboard.DashboardActivity
import com.harnessapk.ui.remote.CodexWebActivity
import com.harnessapk.ui.remote.ZcodeWebRemoteActivity
import com.harnessapk.ui.theme.HarnessSpacing

// 工作模式入口卡片配置化：新增后端/工具 = 追加一条 WorkEntryConfig。
// kind 分派启动方式；启动扩展项（远程后端预选）随条目声明。
internal enum class WorkEntryKind {
    Dashboard,     // 原生页面：Agent 副屏常显
    CodexWeb,      // 浏览器套壳：dots 首页（桌面 UA）
    ZcodeWeb,      // 浏览器套壳：配对存储里的 zcode 远程链接
    RemoteBackend, // bridge 远程页：按预选后端打开
}

internal data class WorkEntryConfig(
    val testTag: String,
    val title: String,
    val icon: ImageVector,
    val kind: WorkEntryKind,
    // 启动扩展项：RemoteBackend 的预选后端 id。
    val backendId: String? = null,
)

// 远程代理入口按使用频率排序；本地项目工作台为次级分区。
internal val workHomeEntries = listOf(
    WorkEntryConfig("work-home-dashboard", "Agent 副屏", Icons.Outlined.Dashboard, WorkEntryKind.Dashboard),
    WorkEntryConfig("work-home-codex", "Codex", CodexBrandIcon, WorkEntryKind.CodexWeb),
    WorkEntryConfig("work-home-zcode", "ZCode", ZcodeBrandIcon, WorkEntryKind.ZcodeWeb),
    WorkEntryConfig(
        "work-home-dsh",
        "DSH",
        DshBrandIcon,
        WorkEntryKind.RemoteBackend,
        backendId = "dsh",
    ),
)

@Composable
internal fun WorkHomeScreen(
    contentPadding: PaddingValues,
    onOpenRemoteBackend: (String) -> Unit,
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
        workHomeEntries.forEach { entry ->
            val launch: () -> Unit = when (entry.kind) {
                WorkEntryKind.Dashboard -> {
                    {
                        context.startActivity(Intent(context, DashboardActivity::class.java))
                    }
                }
                WorkEntryKind.CodexWeb -> {
                    {
                        context.startActivity(Intent(context, CodexWebActivity::class.java))
                    }
                }
                WorkEntryKind.ZcodeWeb -> {
                    {
                        context.startActivity(Intent(context, ZcodeWebRemoteActivity::class.java))
                    }
                }
                WorkEntryKind.RemoteBackend -> {
                    { onOpenRemoteBackend(entry.backendId.orEmpty()) }
                }
            }
            WorkHomeEntry(
                testTag = entry.testTag,
                title = entry.title,
                supportingText = null,
                icon = entry.icon,
                onClick = launch,
            )
        }
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
    supportingText: String?,
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
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
