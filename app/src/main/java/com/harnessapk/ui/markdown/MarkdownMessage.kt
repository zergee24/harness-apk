package com.harnessapk.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
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
                    1 -> markdownHeadingFontSizeSp(level = 1).sp
                    2 -> markdownHeadingFontSizeSp(level = 2).sp
                    else -> markdownHeadingFontSizeSp(level = 3).sp
                },
                lineHeight = markdownHeadingLineHeightSp(block.level).sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        is MarkdownBlock.Paragraph -> Text(
            text = block.text.toAnnotatedString(textColor),
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = markdownBodyLineHeightSp().sp,
        )
        is MarkdownBlock.BulletList -> MarkdownList(
            items = block.items,
            textColor = textColor,
            markerForIndex = { "•" },
        )
        is MarkdownBlock.OrderedList -> MarkdownList(
            items = block.items,
            textColor = textColor,
            markerForIndex = { index -> "${block.startNumber + index}." },
        )
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
        is MarkdownBlock.Code -> MarkdownCodeBlock(block)
        is MarkdownBlock.Math -> MarkdownMathBlock(block)
        is MarkdownBlock.Mermaid -> MarkdownMermaidBlock(block)
        is MarkdownBlock.Table -> MarkdownTable(table = block, textColor = textColor)
        MarkdownBlock.Divider -> HorizontalDivider(color = textColor.copy(alpha = 0.18f))
    }
}

@Composable
private fun MarkdownCodeBlock(block: MarkdownBlock.Code) {
    val clipboard = LocalClipboardManager.current
    val language = block.info.orEmpty().trim().substringBefore(' ').ifBlank { "text" }
    val codeTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val highlighted = remember(block.literal, language, codeTextColor) {
        tokenizeCode(block.literal, language).toAnnotatedString(codeTextColor)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    modifier = Modifier.size(32.dp),
                    onClick = { clipboard.setText(AnnotatedString(block.literal)) },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "复制代码",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    text = highlighted,
                    color = codeTextColor,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = markdownCodeFontSizeSp().sp,
                        lineHeight = markdownCodeLineHeightSp().sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MarkdownMathBlock(block: MarkdownBlock.Math) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "公式",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "KaTeX 暂不可用，已显示源码",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                text = block.literal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = markdownCodeFontSizeSp().sp,
                    lineHeight = markdownCodeLineHeightSp().sp,
                ),
            )
        }
    }
}

@Composable
private fun MarkdownMermaidBlock(block: MarkdownBlock.Mermaid) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "mermaid",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "图表预览暂不可用，已显示源码",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                text = block.literal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = markdownCodeFontSizeSp().sp,
                    lineHeight = markdownCodeLineHeightSp().sp,
                ),
            )
        }
    }
}

private fun List<CodeToken>.toAnnotatedString(textColor: Color): AnnotatedString = buildAnnotatedString {
    forEach { token ->
        val style = when (token.kind) {
            CodeTokenKind.KEYWORD -> SpanStyle(color = textColor.copy(alpha = 0.96f), fontWeight = FontWeight.SemiBold)
            CodeTokenKind.STRING -> SpanStyle(color = textColor.copy(alpha = 0.86f))
            CodeTokenKind.NUMBER -> SpanStyle(color = textColor.copy(alpha = 0.92f), fontWeight = FontWeight.Medium)
            CodeTokenKind.COMMENT -> SpanStyle(color = textColor.copy(alpha = 0.62f), fontStyle = FontStyle.Italic)
            CodeTokenKind.PUNCTUATION -> SpanStyle(color = textColor.copy(alpha = 0.78f))
            CodeTokenKind.PLAIN -> SpanStyle(color = textColor)
        }
        withStyle(style) { append(token.literal) }
    }
}

@Composable
private fun MarkdownList(
    items: List<MarkdownListItem>,
    textColor: Color,
    markerForIndex: (Int) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { index, item ->
            MarkdownListRow(
                marker = item.taskChecked?.let { if (it) "[x]" else "[ ]" } ?: markerForIndex(index),
                item = item,
                textColor = textColor,
            )
        }
    }
}

@Composable
private fun MarkdownListRow(
    marker: String,
    item: MarkdownListItem,
    textColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                modifier = Modifier.width(28.dp),
                text = marker,
                color = textColor.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = item.text.toAnnotatedString(textColor),
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = markdownBodyLineHeightSp().sp,
            )
        }
        if (item.children.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 22.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.children.forEach { child ->
                    MarkdownBlockView(block = child, textColor = textColor)
                }
            }
        }
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
            is MarkdownInline.Math -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = textColor,
                    background = textColor.copy(alpha = 0.08f),
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

internal fun markdownHeadingFontSizeSp(level: Int): Int = when (level) {
    1 -> 22
    2 -> 19
    else -> 17
}

internal fun markdownHeadingLineHeightSp(level: Int): Int = when (level) {
    1 -> 30
    2 -> 27
    else -> 25
}

internal fun markdownBodyLineHeightSp(): Int = 22

internal fun markdownCodeFontSizeSp(): Int = 13

internal fun markdownCodeLineHeightSp(): Int = 22
