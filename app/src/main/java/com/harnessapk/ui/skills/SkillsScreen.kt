package com.harnessapk.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.skills.BundledSkill
import com.harnessapk.skills.BundledSkills
import com.harnessapk.skills.SkillActivationSettings
import kotlinx.coroutines.launch

@Composable
fun SkillsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    skills: List<BundledSkill> = BundledSkills.defaults,
) {
    val activationSettings by container.settingsStore.skillActivationSettings.collectAsState(
        initial = SkillActivationSettings.defaultFor(skills),
    )
    val scope = rememberCoroutineScope()
    SkillsScreenContent(
        contentPadding = contentPadding,
        skills = skills,
        activationSettings = activationSettings,
        onSkillEnabledChange = { skillId, enabled ->
            scope.launch {
                container.settingsStore.setSkillEnabled(skillId, enabled)
            }
        },
    )
}

@Composable
internal fun SkillsScreenContent(
    contentPadding: PaddingValues,
    skills: List<BundledSkill> = BundledSkills.defaults,
    activationSettings: SkillActivationSettings = SkillActivationSettings.defaultFor(skills),
    onSkillEnabledChange: (String, Boolean) -> Unit = { _, _ -> },
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "内置技能",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        skills.forEach { skill ->
            SkillRow(
                skill = skill,
                enabled = activationSettings.isEnabled(skill.id),
                onEnabledChange = { onSkillEnabledChange(skill.id, it) },
            )
        }
        PluginPlaceholder()
    }
}

@Composable
private fun SkillRow(
    skill: BundledSkill,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        ListItem(
            leadingContent = {
                Icon(Icons.Outlined.Extension, contentDescription = null)
            },
            headlineContent = {
                Text(skill.name)
            },
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(skill.summary)
                    Text(
                        text = "来源：${skill.sourceUrl}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Commit：${skill.sourceCommit.take(7)} · ${skill.assetPath}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text(if (enabled) "已启用" else "已关闭") })
                        AssistChip(onClick = {}, label = { Text("已内置") })
                        if (skill.isReadOnly) {
                            AssistChip(
                                onClick = {},
                                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                                label = { Text("只读") },
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun PluginPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "插件",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Text(
                modifier = Modifier.padding(16.dp),
                text = "第三方插件执行暂未启用。后续需要受信源、签名校验和权限说明后再开放。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
