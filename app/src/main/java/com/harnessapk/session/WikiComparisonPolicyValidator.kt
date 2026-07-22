package com.harnessapk.session

object WikiComparisonPolicyValidator {
    fun validate(markdown: String, coverage: WikiEvidenceCoverage) {
        if (!coverage.hasMissingComparisonEvidence) return
        markdownProseStatements(markdown).forEach { statement ->
            if (statement.containsAny(UNSAFE_COMPARISON_CLAIMS) && !statement.containsAny(CAUTIOUS_RETRIEVAL_MARKERS)) {
                throw WikiMarkdownValidationException("比较证据不完整，不能使用绝对资料结论")
            }
        }
    }
}

private fun markdownProseStatements(markdown: String): List<String> {
    val prose = buildString {
        var activeFence: Char? = null
        markdown.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            val fence = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
            if (fence != null && trimmed.takeWhile { it == fence }.length >= 3) {
                activeFence = if (activeFence == null) fence else null
                return@forEach
            }
            if (activeFence != null || FOOTNOTE_DEFINITION_LINE.matches(line)) return@forEach
            appendLine(line)
        }
    }
    return prose.split(STATEMENT_BOUNDARY).map(String::trim).filter(String::isNotBlank)
}

private fun String.containsAny(values: List<String>): Boolean = values.any(::contains)

private val UNSAFE_COMPARISON_CLAIMS = listOf(
    "没有记载",
    "从未提及",
    "均未记载",
    "均无记载",
    "两书一致",
    "两书都认为",
)
private val CAUTIOUS_RETRIEVAL_MARKERS = listOf(
    "当前检索",
    "未找到可靠原文",
    "证据不足",
)
private val STATEMENT_BOUNDARY = Regex("[。！？!?]+|\\n+")
private val FOOTNOTE_DEFINITION_LINE = Regex("^[ \\t]{0,3}\\[\\^[a-zA-Z0-9-]+]:")
