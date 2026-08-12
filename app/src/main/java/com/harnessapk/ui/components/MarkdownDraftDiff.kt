package com.harnessapk.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.harnessapk.session.MarkdownDiffLine
import com.harnessapk.session.MarkdownDiffLineType

/** Shared renderer used by Assistant, Explicit and Remote persisted drafts. */
@Composable
fun MarkdownDraftDiff(
    lines: List<MarkdownDiffLine>,
    modifier: Modifier = Modifier,
    maxLines: Int = 120,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.take(maxLines).forEach { line ->
            val prefix = when (line.type) {
                MarkdownDiffLineType.ADDED -> "+"
                MarkdownDiffLineType.REMOVED -> "-"
                MarkdownDiffLineType.CONTEXT -> " "
            }
            val color = when (line.type) {
                MarkdownDiffLineType.ADDED -> MaterialTheme.colorScheme.primary
                MarkdownDiffLineType.REMOVED -> MaterialTheme.colorScheme.error
                MarkdownDiffLineType.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = "$prefix ${line.text}",
                color = color,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.semantics {
                    contentDescription = when (line.type) {
                        MarkdownDiffLineType.ADDED -> "新增 ${line.text}"
                        MarkdownDiffLineType.REMOVED -> "删除 ${line.text}"
                        MarkdownDiffLineType.CONTEXT -> line.text
                    }
                },
            )
        }
        if (lines.size > maxLines) {
            Text(
                text = "... 已截断 ${lines.size - maxLines} 行",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
