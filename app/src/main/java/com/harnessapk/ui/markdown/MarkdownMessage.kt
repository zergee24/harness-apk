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
import androidx.compose.foundation.text.ClickableText
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
import java.net.URI
import java.util.Locale
import java.util.UUID

internal sealed interface MarkdownLinkTarget {
    data class WikiCitation(val citationId: String) : MarkdownLinkTarget

    data class ExternalUrl(val url: String) : MarkdownLinkTarget

    data object Ignored : MarkdownLinkTarget
}

internal fun markdownLinkTarget(destination: String): MarkdownLinkTarget {
    val uri = runCatching { URI(destination) }.getOrNull() ?: return MarkdownLinkTarget.Ignored
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "harness-wiki" -> citationIdFromUri(uri)
            ?.let(MarkdownLinkTarget::WikiCitation)
            ?: MarkdownLinkTarget.Ignored
        "http", "https" -> destination
            .takeIf { uri.host?.isNotBlank() == true }
            ?.let(MarkdownLinkTarget::ExternalUrl)
            ?: MarkdownLinkTarget.Ignored
        else -> MarkdownLinkTarget.Ignored
    }
}

private fun citationIdFromUri(uri: URI): String? {
    if (
        uri.host != "citation" ||
        uri.port != -1 ||
        uri.userInfo != null ||
        uri.query != null ||
        uri.fragment != null
    ) {
        return null
    }
    val rawId = uri.rawPath?.removePrefix("/")
        ?.takeIf { uri.rawPath == "/$it" }
        ?: return null
    val uuid = runCatching { UUID.fromString(rawId) }.getOrNull() ?: return null
    return uuid.toString().takeIf { it.equals(rawId, ignoreCase = true) }
}

private const val MARKDOWN_LINK_ANNOTATION = "markdown-link"
private val INTERNAL_WIKI_CITATION_MARKDOWN_LINK = Regex(
    """\[([^\[\]\n]*)]\(harness-wiki://citation/([0-9A-Fa-f-]{36})\)""",
)

internal fun markdownTextForCopy(markdown: String): String =
    INTERNAL_WIKI_CITATION_MARKDOWN_LINK.replace(markdown) { match ->
        val destination = "harness-wiki://citation/${match.groupValues[2]}"
        if (markdownLinkTarget(destination) is MarkdownLinkTarget.WikiCitation) {
            match.groupValues[1]
        } else {
            match.value
        }
    }

@Composable
fun MarkdownMessage(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onLinkClick: (String) -> Unit = {},
) {
    val blockCache = remember { IncrementalMarkdownBlockCache() }
    val chunks = remember(markdown) { blockCache.chunksFor(markdown) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chunks.forEach { chunk ->
            key(chunk.id) {
                MarkdownChunkView(
                    chunk = chunk,
                    textColor = textColor,
                    onLinkClick = onLinkClick,
                )
            }
        }
    }
}

@Composable
private fun MarkdownChunkView(
    chunk: ParsedMarkdownChunk,
    textColor: Color,
    onLinkClick: (String) -> Unit,
) {
    chunk.blocks.forEach { block ->
        MarkdownBlockView(
            block = block,
            textColor = textColor,
            onLinkClick = onLinkClick,
        )
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    textColor: Color,
    onLinkClick: (String) -> Unit,
) {
    when (block) {
        is MarkdownBlock.Heading -> MarkdownInlineText(
            inlines = block.text,
            textColor = textColor,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = when (block.level) {
                    1 -> markdownHeadingFontSizeSp(level = 1).sp
                    2 -> markdownHeadingFontSizeSp(level = 2).sp
                    else -> markdownHeadingFontSizeSp(level = 3).sp
                },
                lineHeight = markdownHeadingLineHeightSp(block.level).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            onLinkClick = onLinkClick,
        )
        is MarkdownBlock.Paragraph -> MarkdownInlineText(
            inlines = block.text,
            textColor = textColor,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = markdownBodyLineHeightSp().sp,
            onLinkClick = onLinkClick,
        )
        is MarkdownBlock.BulletList -> MarkdownList(
            items = block.items,
            textColor = textColor,
            markerForIndex = { "•" },
            onLinkClick = onLinkClick,
        )
        is MarkdownBlock.OrderedList -> MarkdownList(
            items = block.items,
            textColor = textColor,
            markerForIndex = { index -> "${block.startNumber + index}." },
            onLinkClick = onLinkClick,
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
                block.blocks.forEach {
                    MarkdownBlockView(
                        block = it,
                        textColor = textColor.copy(alpha = 0.82f),
                        onLinkClick = onLinkClick,
                    )
                }
            }
        }
        is MarkdownBlock.Code -> MarkdownCodeBlock(block)
        is MarkdownBlock.Math -> MarkdownMathBlock(block)
        is MarkdownBlock.Mermaid -> MarkdownMermaidBlock(block)
        is MarkdownBlock.Table -> MarkdownTable(
            table = block,
            textColor = textColor,
            onLinkClick = onLinkClick,
        )
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
                text = "数学表达式",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                text = mathFallbackText(block.literal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontSize = markdownCodeFontSizeSp().sp,
                    lineHeight = markdownCodeLineHeightSp().sp,
                ),
            )
        }
    }
}

internal fun mathFallbackText(latex: String): String {
    var text = latex.trim()
        .replace("\\times", "×")
        .replace("\\cdot", "·")
        .replace("\\pi", "π")
        .replace("\\leq", "≤")
        .replace("\\geq", "≥")

    val squareRoot = Regex("""\\sqrt\{([^{}]*)\}""")
    while (squareRoot.containsMatchIn(text)) {
        text = squareRoot.replace(text) { "√(${it.groupValues[1]})" }
    }
    val fraction = Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""")
    while (fraction.containsMatchIn(text)) {
        text = fraction.replace(text) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }
    }
    val superscript = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹')
    return text.replace(Regex("""\^([0-9])""")) { superscript.getValue(it.groupValues[1].single()).toString() }
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
    onLinkClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { index, item ->
            MarkdownListRow(
                marker = item.taskChecked?.let { if (it) "[x]" else "[ ]" } ?: markerForIndex(index),
                item = item,
                textColor = textColor,
                onLinkClick = onLinkClick,
            )
        }
    }
}

@Composable
private fun MarkdownListRow(
    marker: String,
    item: MarkdownListItem,
    textColor: Color,
    onLinkClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                modifier = Modifier.width(28.dp),
                text = marker,
                color = textColor.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
            )
            MarkdownInlineText(
                modifier = Modifier.weight(1f),
                inlines = item.text,
                textColor = textColor,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = markdownBodyLineHeightSp().sp,
                onLinkClick = onLinkClick,
            )
        }
        if (item.children.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 22.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.children.forEach { child ->
                    MarkdownBlockView(
                        block = child,
                        textColor = textColor,
                        onLinkClick = onLinkClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    textColor: Color,
    onLinkClick: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            if (table.headers.isNotEmpty()) {
                MarkdownTableRow(
                    cells = table.headers,
                    textColor = textColor,
                    isHeader = true,
                    onLinkClick = onLinkClick,
                )
                HorizontalDivider(color = textColor.copy(alpha = 0.14f))
            }
            table.rows.forEachIndexed { index, row ->
                MarkdownTableRow(
                    cells = row,
                    textColor = textColor,
                    isHeader = false,
                    onLinkClick = onLinkClick,
                )
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
    onLinkClick: (String) -> Unit,
) {
    Row {
        cells.forEach { cell ->
            MarkdownInlineText(
                modifier = Modifier
                    .widthIn(min = 104.dp, max = 220.dp)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                inlines = cell,
                textColor = textColor,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                    lineHeight = 18.sp,
                ),
                onLinkClick = onLinkClick,
            )
        }
    }
}

private fun List<MarkdownInline>.toMarkdownAnnotatedString(textColor: Color): AnnotatedString = buildAnnotatedString {
    appendInlineList(this@toMarkdownAnnotatedString, textColor)
}

@Composable
private fun MarkdownInlineText(
    inlines: List<MarkdownInline>,
    textColor: Color,
    style: TextStyle,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    lineHeight: androidx.compose.ui.unit.TextUnit = style.lineHeight,
) {
    val text = remember(inlines, textColor) { inlines.toMarkdownAnnotatedString(textColor) }
    val resolvedStyle = style.copy(color = textColor, lineHeight = lineHeight)
    val hasLinks = remember(text) { text.hasMarkdownLinkAnnotations() }
    if (!hasLinks) {
        Text(modifier = modifier, text = text, style = resolvedStyle)
    } else {
        ClickableText(
            modifier = modifier,
            text = text,
            style = resolvedStyle,
            onClick = { offset ->
                val position = offset.coerceIn(0, text.length - 1)
                text.getStringAnnotations(MARKDOWN_LINK_ANNOTATION, position, position + 1)
                    .lastOrNull()
                    ?.let { annotation -> onLinkClick(annotation.item) }
            },
        )
    }
}

private fun AnnotatedString.hasMarkdownLinkAnnotations(): Boolean =
    isNotEmpty() && getStringAnnotations(MARKDOWN_LINK_ANNOTATION, 0, length).isNotEmpty()

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
            is MarkdownInline.Link -> {
                val start = length
                withStyle(
                    SpanStyle(
                        color = textColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium,
                    ),
                ) {
                    appendInlineList(inline.children, textColor)
                }
                if (length > start) {
                    addStringAnnotation(MARKDOWN_LINK_ANNOTATION, inline.destination, start, length)
                }
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
