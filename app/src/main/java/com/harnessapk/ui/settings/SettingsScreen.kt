package com.harnessapk.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CancelPresentation
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpenProviders: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenAgentPackages: () -> Unit,
    onOpenWikiLibrary: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenRemote: () -> Unit = {},
    onOpenConfigPackage: () -> Unit = {},
    simpleMode: Boolean = false,
    onSimpleModeChange: (Boolean) -> Unit = {},
    showUpdateBadge: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        settingsDestinations(showUpdateBadge = showUpdateBadge).forEach { destination ->
            SettingsRow(
                destination = destination,
                icon = iconFor(destination.id),
                onClick = when (destination.id) {
                    "remote" -> onOpenRemote
                    "models" -> onOpenProviders
                    "search" -> onOpenSearch
                    "voice" -> onOpenVoice
                    "git" -> onOpenGit
                    "skills" -> onOpenSkills
                    "agents" -> onOpenAgentPackages
                    "wikis" -> onOpenWikiLibrary
                    "updates" -> onOpenUpdates
                    "config" -> onOpenConfigPackage
                    else -> ({})
                },
            )
        }
        SimpleModeRow(checked = simpleMode, onCheckedChange = onSimpleModeChange)
    }
}

@Composable
private fun SimpleModeRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        ListItem(
            leadingContent = {
                Icon(Icons.Outlined.CancelPresentation, contentDescription = null)
            },
            headlineContent = { Text("生活简洁模式") },
            supportingContent = { Text("生活页只保留新建对话和最近会话，适合家人使用。") },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            },
        )
    }
}

@Composable
private fun SettingsRow(
    destination: SettingsDestination,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        onClick = onClick,
    ) {
        ListItem(
            leadingContent = {
                Icon(icon, contentDescription = null)
            },
            headlineContent = {
                Text(destination.title)
            },
            supportingContent = {
                Text(destination.description)
            },
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (destination.showBadge) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape),
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            },
        )
    }
}

private fun iconFor(id: String): ImageVector = when (id) {
    "remote" -> Icons.Outlined.Devices
    "models" -> Icons.Outlined.Settings
    "search" -> Icons.Outlined.Search
    "voice" -> Icons.Outlined.Mic
    "git" -> Icons.Outlined.AccountTree
    "skills" -> Icons.Outlined.Extension
    "agents" -> Icons.Outlined.Extension
    "wikis" -> Icons.AutoMirrored.Outlined.MenuBook
    "updates" -> Icons.Outlined.SystemUpdate
    "config" -> Icons.Outlined.IosShare
    else -> Icons.Outlined.Settings
}
