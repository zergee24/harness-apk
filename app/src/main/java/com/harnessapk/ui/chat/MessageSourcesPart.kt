package com.harnessapk.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import com.harnessapk.wiki.MessageWikiCitation

internal data class MessageWikiSourceGroup(
    val wikiTitle: String,
    val citations: List<MessageWikiCitation>,
)

internal data class MessageSourcesUiState(
    val wikiGroups: List<MessageWikiSourceGroup>,
    val agentSources: List<String>,
    private val pendingWikiSummary: String? = null,
) {
    val collapsedSummary: String = buildList {
        if (wikiGroups.isNotEmpty()) {
            val wikiSummary = (
                listOf("引用 ${wikiGroups.sumOf { it.citations.size }}") +
                    wikiGroups.map { group -> "${group.wikiTitle} ${group.citations.size}" }
                ).joinToString(" · ")
            add(wikiSummary)
        } else {
            pendingWikiSummary?.takeIf(String::isNotBlank)?.let(::add)
        }
        if (agentSources.isNotEmpty()) add("人物资料 ${agentSources.size}")
    }.joinToString(" · ")
}

internal fun messageSourcesUiState(
    parts: List<UiMessagePartDraft>,
    citations: List<MessageWikiCitation>,
): MessageSourcesUiState? {
    val wikiParts = parts.filter { it.type == UiMessagePartType.WIKI_SOURCES }
    val agentSources = parts
        .filter { it.type == UiMessagePartType.AGENT_SOURCES }
        .flatMap { part -> part.content.lineSequence().map(String::trim).filter(String::isNotBlank).toList() }
        .distinct()
    val wikiGroups = citations
        .distinctBy(MessageWikiCitation::id)
        .sortedWith(compareBy<MessageWikiCitation> { it.wikiTitle }.thenBy { it.displayOrdinal })
        .groupBy(MessageWikiCitation::wikiTitle)
        .map { (wikiTitle, groupedCitations) ->
            MessageWikiSourceGroup(wikiTitle = wikiTitle, citations = groupedCitations)
        }
    if (wikiParts.isEmpty() && wikiGroups.isEmpty() && agentSources.isEmpty()) return null
    return MessageSourcesUiState(
        wikiGroups = wikiGroups,
        agentSources = agentSources,
        pendingWikiSummary = wikiParts.firstOrNull()?.content,
    )
}

@Composable
internal fun MessageSourcesPart(
    state: MessageSourcesUiState,
    onOpenWikiCitation: (String) -> Unit,
) {
    var expanded by remember(state) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = state.collapsedSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    modifier = Modifier.size(48.dp),
                    onClick = { expanded = !expanded },
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起参考资料" else "展开参考资料",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (expanded) {
                state.wikiGroups.forEachIndexed { groupIndex, group ->
                    if (groupIndex > 0) HorizontalDivider()
                    Text(
                        text = "${group.wikiTitle} · ${group.citations.size} 条",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    group.citations.forEach { citation ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenWikiCitation(citation.id) }
                                .padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = citation.sourceTitle,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${citation.sectionPath} · ${citation.locatorLabel}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "“${citation.originalTextSnapshot}”",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (state.wikiGroups.isEmpty() && state.collapsedSummary.isNotBlank()) {
                    Text(
                        text = "引用资料正在载入",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.agentSources.isNotEmpty()) {
                    if (state.wikiGroups.isNotEmpty()) HorizontalDivider()
                    Text(
                        text = "人物资料 · ${state.agentSources.size} 条",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.agentSources.forEach { source ->
                        Text(
                            text = source,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
