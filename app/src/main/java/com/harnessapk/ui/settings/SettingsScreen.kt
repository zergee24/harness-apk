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
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    onOpenUpdates: () -> Unit,
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
                    "models" -> onOpenProviders
                    "search" -> onOpenSearch
                    "voice" -> onOpenVoice
                    "git" -> onOpenGit
                    "skills" -> onOpenSkills
                    "updates" -> onOpenUpdates
                    else -> ({})
                },
            )
        }
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
    "models" -> Icons.Outlined.Settings
    "search" -> Icons.Outlined.Search
    "voice" -> Icons.Outlined.Mic
    "git" -> Icons.Outlined.AccountTree
    "skills" -> Icons.Outlined.Extension
    "updates" -> Icons.Outlined.SystemUpdate
    else -> Icons.Outlined.Settings
}
