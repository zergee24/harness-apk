package com.harnessapk.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ConversationIdentityPicker(
    state: ConversationIdentityUiState,
    onSelectAgentId: (String?) -> Unit,
    onShowDetails: () -> Unit,
) {
    if (!state.mutable) {
        if (state.selectedAgentId != null) {
            Text(
                text = "${state.selectedName} · 基于资料模拟",
                modifier = Modifier.clickable(onClick = onShowDetails),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            modifier = Modifier.heightIn(min = 48.dp),
            selected = false,
            onClick = { expanded = true },
            label = {
                Text(
                    text = state.selectedName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            state.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        expanded = false
                        onSelectAgentId(option.agentId)
                    },
                )
            }
        }
    }
}
