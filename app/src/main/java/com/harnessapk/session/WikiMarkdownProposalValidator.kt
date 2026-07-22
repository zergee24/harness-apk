package com.harnessapk.session

class WikiMarkdownValidationException(message: String) : IllegalArgumentException(message)

object WikiMarkdownProposalValidator {
    fun validate(
        proposal: MarkdownUpdateProposal,
        citations: WikiMarkdownCitationSet,
    ): MarkdownUpdateProposal {
        if (citations.citations.isEmpty()) return proposal
        val markdown = proposal.markdown
        if (markdown.contains("harness-wiki://", ignoreCase = true)) {
            throw WikiMarkdownValidationException("Markdown 不能包含应用内部引用链接")
        }
        if (APP_PRIVATE_PATH.containsMatchIn(markdown)) {
            throw WikiMarkdownValidationException("Markdown 不能包含应用内部路径")
        }
        if (UUID_LEAK.containsMatchIn(markdown)) {
            throw WikiMarkdownValidationException("Markdown 不能包含引用 UUID")
        }
        if (CHUNK_ID_LEAK.containsMatchIn(markdown)) {
            throw WikiMarkdownValidationException("Markdown 不能包含原文片段标识")
        }
        validateCitationSnapshotsAreNotCopied(markdown, citations)

        val definitions = parseFootnoteDefinitions(markdown)
        if (definitions.groupBy(FootnoteDefinition::label).any { (_, values) -> values.size > 1 }) {
            throw WikiMarkdownValidationException("Markdown 不能包含重复脚注定义")
        }
        val definitionLineIndexes = definitions.map(FootnoteDefinition::lineIndex).toSet()
        val references = buildSet {
            markdown.lines().forEachIndexed { index, line ->
                if (index !in definitionLineIndexes) {
                    FOOTNOTE_REFERENCE.findAll(line).forEach { match -> add(match.groupValues[1]) }
                }
            }
        }
        val expectedByLabel = citations.citations.associate { citation ->
            citations.footnoteLabel(citation) to formatWikiFootnoteDefinition(citation)
        }
        val hwikiReferences = references.filter { it.startsWith(HWIKI_LABEL_PREFIX) }
        hwikiReferences.forEach { label ->
            if (label !in expectedByLabel) {
                throw WikiMarkdownValidationException("Markdown 使用了未授权的 Wiki 脚注")
            }
            val definition = definitions.singleOrNull { it.label == label }
                ?: throw WikiMarkdownValidationException("Markdown 缺少 Wiki 脚注定义")
            if (definition.content != expectedByLabel.getValue(label)) {
                throw WikiMarkdownValidationException("Markdown 改写了 Wiki 来源定义")
            }
        }
        definitions.filter { it.label.startsWith(HWIKI_LABEL_PREFIX) }.forEach { definition ->
            val expected = expectedByLabel[definition.label]
                ?: throw WikiMarkdownValidationException("Markdown 包含伪造的 Wiki 脚注定义")
            if (definition.content != expected) {
                throw WikiMarkdownValidationException("Markdown 改写了 Wiki 来源定义")
            }
        }

        val unusedLabels = expectedByLabel.keys - hwikiReferences.toSet()
        val normalizedMarkdown = removeUnusedWikiDefinitions(markdown, unusedLabels)
        return proposal.copy(markdown = normalizedMarkdown)
    }
}

private data class FootnoteDefinition(
    val label: String,
    val content: String,
    val lineIndex: Int,
)

private fun parseFootnoteDefinitions(markdown: String): List<FootnoteDefinition> = markdown
    .lines()
    .mapIndexedNotNull { index, line ->
        FOOTNOTE_DEFINITION.matchEntire(line)?.let { match ->
            FootnoteDefinition(
                label = match.groupValues[1],
                content = match.groupValues[2].trimEnd(),
                lineIndex = index,
            )
        }
    }

private fun removeUnusedWikiDefinitions(markdown: String, unusedLabels: Set<String>): String {
    if (unusedLabels.isEmpty()) return markdown
    return markdown
        .lines()
        .filterNot { line ->
            FOOTNOTE_DEFINITION.matchEntire(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { label -> label in unusedLabels }
                ?: false
        }
        .joinToString("\n")
        .trimEnd()
}

private fun validateCitationSnapshotsAreNotCopied(
    markdown: String,
    citations: WikiMarkdownCitationSet,
) {
    citations.citations.forEach { citation ->
        val snapshot = citation.originalTextSnapshot
        if (snapshot.codePointCount(0, snapshot.length) <= MAX_PORTABLE_QUOTATION_CODE_POINTS) return@forEach
        val bounded = snapshot.substring(0, snapshot.offsetByCodePoints(0, MAX_PORTABLE_QUOTATION_CODE_POINTS))
        if (markdown.contains(snapshot) || markdown.contains(bounded)) {
            throw WikiMarkdownValidationException("Markdown 不能复制完整 Wiki 原文")
        }
    }
}

private const val HWIKI_LABEL_PREFIX = "hwiki-"
private const val MAX_PORTABLE_QUOTATION_CODE_POINTS = 160
private val FOOTNOTE_REFERENCE = Regex("(?<!\\\\)\\[\\^([a-zA-Z0-9-]+)]")
private val FOOTNOTE_DEFINITION = Regex("^[ \\t]{0,3}\\[\\^([a-zA-Z0-9-]+)]:[ \\t]*(.*)$")
private val UUID_LEAK = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
private val CHUNK_ID_LEAK = Regex("(?i)\\bchunk(?:[_-][a-z0-9._-]+)+\\b")
private val APP_PRIVATE_PATH = Regex("(?i)(?:file://|content://|/(?:data|sdcard|storage|private|users)/)")
