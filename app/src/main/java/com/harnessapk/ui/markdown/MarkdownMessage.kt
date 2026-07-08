package com.harnessapk.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownMessage(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blockCache = remember { IncrementalMarkdownBlockCache() }
    val chunks = remember(markdown) { blockCache.chunksFor(markdown) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chunks.forEach { chunk ->
            key(chunk.id) {
                MarkdownChunkView(chunk = chunk, textColor = textColor)
            }
        }
    }
}

@Composable
private fun MarkdownChunkView(chunk: ParsedMarkdownChunk, textColor: Color) {
    chunk.blocks.forEach { block ->
        MarkdownBlockView(block = block, textColor = textColor)
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, textColor: Color) {
    when (block) {
        is MarkdownBlock.Heading -> Text(
            text = block.text.toAnnotatedString(textColor),
            color = textColor,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = when (block.level) {
                    1 -> 22.sp
                    2 -> 19.sp
                    else -> 17.sp
                },
                fontWeight = FontWeight.SemiBold,
            ),
        )
        is MarkdownBlock.Paragraph -> Text(
            text = block.text.toAnnotatedString(textColor),
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 21.sp,
        )
        is MarkdownBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                MarkdownListRow(marker = "•", text = item, textColor = textColor)
            }
        }
        is MarkdownBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                MarkdownListRow(marker = "${block.startNumber + index}.", text = item, textColor = textColor)
            }
        }
        is MarkdownBlock.Quote -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier
                    .width(3.dp)
                    .padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                shape = RoundedCornerShape(999.dp),
                content = {},
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                block.blocks.forEach { MarkdownBlockView(it, textColor.copy(alpha = 0.82f)) }
            }
        }
        is MarkdownBlock.Code -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
        ) {
            Box(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = block.literal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                )
            }
        }
        is MarkdownBlock.Table -> MarkdownTable(table = block, textColor = textColor)
        MarkdownBlock.Divider -> HorizontalDivider(color = textColor.copy(alpha = 0.18f))
    }
}

@Composable
private fun MarkdownListRow(
    marker: String,
    text: List<MarkdownInline>,
    textColor: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = marker,
            color = textColor.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            modifier = Modifier.weight(1f),
            text = text.toAnnotatedString(textColor),
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 21.sp,
        )
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, textColor: Color) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            if (table.headers.isNotEmpty()) {
                MarkdownTableRow(cells = table.headers, textColor = textColor, isHeader = true)
                HorizontalDivider(color = textColor.copy(alpha = 0.14f))
            }
            table.rows.forEachIndexed { index, row ->
                MarkdownTableRow(cells = row, textColor = textColor, isHeader = false)
                if (index != table.rows.lastIndex) {
                    HorizontalDivider(color = textColor.copy(alpha = 0.10f))
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<List<MarkdownInline>>,
    textColor: Color,
    isHeader: Boolean,
) {
    Row {
        cells.forEach { cell ->
            Text(
                modifier = Modifier
                    .widthIn(min = 104.dp, max = 220.dp)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                text = cell.toAnnotatedString(textColor),
                color = textColor,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

@Composable
private fun List<MarkdownInline>.toAnnotatedString(textColor: Color): AnnotatedString = buildAnnotatedString {
    appendInlineList(this@toAnnotatedString, textColor)
}

private fun AnnotatedString.Builder.appendInlineList(
    inlines: List<MarkdownInline>,
    textColor: Color,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Text -> append(inline.literal)
            is MarkdownInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                appendInlineList(inline.children, textColor)
            }
            is MarkdownInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlineList(inline.children, textColor)
            }
            is MarkdownInline.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = textColor,
                    background = textColor.copy(alpha = 0.10f),
                ),
            ) {
                append(inline.literal)
            }
            is MarkdownInline.Link -> withStyle(
                SpanStyle(
                    color = textColor,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium,
                ),
            ) {
                appendInlineList(inline.children, textColor)
            }
            MarkdownInline.LineBreak -> append('\n')
        }
    }
}
