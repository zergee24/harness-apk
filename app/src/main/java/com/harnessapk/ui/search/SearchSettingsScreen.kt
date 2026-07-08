package com.harnessapk.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import kotlinx.coroutines.launch

@Composable
fun SearchSettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
) {
    val settings by container.settingsStore.webSearchSettings.collectAsState(
        initial = com.harnessapk.websearch.WebSearchSettings(),
    )
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
            ListItem(
                headlineContent = { Text("启用联网搜索") },
                supportingContent = { Text("会话里仍需要手动打开“联网”开关才会搜索。") },
                trailingContent = {
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = {
                            scope.launch { container.settingsStore.setWebSearchEnabled(it) }
                        },
                    )
                },
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("搜索路由", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("模型内搜索优先")
                Text(
                    text = "当前模型配置了模型内搜索时，会直接随 LLM 请求启用搜索；未配置时回退到免 Key Jina。Bing Grounding 作为后续 Azure 通道预留。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("每次搜索最大结果数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 8).forEach { count ->
                        FilterChip(
                            selected = settings.maxResults == count,
                            onClick = {
                                scope.launch { container.settingsStore.setWebSearchMaxResults(count) }
                            },
                            label = { Text("${count} 条") },
                        )
                    }
                }
            }
        }
    }
}
