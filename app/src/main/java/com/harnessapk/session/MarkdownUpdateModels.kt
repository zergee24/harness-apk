package com.harnessapk.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import java.util.Locale

enum class MarkdownUpdateOperation {
    CREATE,
    UPDATE,
}

data class MarkdownSnapshot(
    val id: String,
    val title: String,
    val path: String,
    val markdown: String,
)

data class MarkdownUpdateProposal(
    val operation: MarkdownUpdateOperation,
    val path: String,
    val title: String,
    val reason: String,
    val markdown: String,
    val baselineSha256: String? = null,
    val expectedAbsent: Boolean = false,
)

data class MarkdownUpdatePlan(
    val proposals: List<MarkdownUpdateProposal>,
    val contextFacts: List<ContextFactCandidate> = emptyList(),
)

class MarkdownUpdatePlanningException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

enum class MarkdownDiffLineType {
    CONTEXT,
    ADDED,
    REMOVED,
}

data class MarkdownDiffLine(
    val type: MarkdownDiffLineType,
    val text: String,
)

data class MarkdownDiffStats(
    val addedLineCount: Int,
    val removedLineCount: Int,
)

private val markdownPlanJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

fun parseMarkdownUpdatePlanResponse(response: String): MarkdownUpdatePlan {
    val jsonText = extractJsonObject(response)
    val root = markdownPlanJson.parseToJsonElement(jsonText).jsonObject
    val updates = root["updates"]?.jsonArray.orEmpty()
    return MarkdownUpdatePlan(
        proposals = updates.mapNotNull { element ->
            val update = element.jsonObject
            val path = update.stringValue("path").trim()
            val markdown = update.stringValue("markdown").trimEnd()
            if (path.isBlank() || markdown.isBlank()) {
                null
            } else {
                MarkdownUpdateProposal(
                    operation = update.stringValue("operation").toMarkdownOperation(),
                    path = path,
                    title = update.stringValue("title").trim().ifBlank { path.substringAfterLast('/') },
                    reason = update.stringValue("reason").trim(),
                    markdown = markdown,
                )
            }
        },
        contextFacts = root["contextFacts"]?.jsonArray.orEmpty().mapNotNull { element ->
            val fact = element.jsonObject
            val section = fact.stringValue("section").toContextSectionOrNull() ?: return@mapNotNull null
            val statement = fact.stringValue("statement").trim()
            val operation = fact.stringValue("operation").toFactOperationOrNull() ?: return@mapNotNull null
            ContextFactCandidate(
                section = section,
                statement = statement,
                evidenceIds = fact["evidenceIds"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) },
                evidenceAuthorities = emptySet(),
                operation = operation,
                semanticKey = "",
                evidenceHash = "",
            )
        },
    )
}

fun parseAndValidateMarkdownUpdatePlanResponse(
    response: String,
    wikiCitations: WikiMarkdownCitationSet = WikiMarkdownCitationSet.EMPTY,
    wikiCoverage: WikiEvidenceCoverage = WikiEvidenceCoverage.NONE,
    allowedEvidence: List<ContextFactEvidence> = emptyList(),
    suppressedContextFactKeys: Set<String> = emptySet(),
    existingContextMarkdown: String? = null,
): MarkdownUpdatePlan {
    val parsed = parseMarkdownUpdatePlanResponse(response)
    val acceptedFacts = if (parsed.proposals.any { it.path.isRootContextMarkdown() }) {
        ContextFactPolicy().accept(
            candidates = parsed.contextFacts,
            allowedEvidence = allowedEvidence,
            suppressedSemanticKeys = suppressedContextFactKeys,
        )
    } else {
        emptyList()
    }
    val factsToAppend = contextFactsNotAlreadyPresent(acceptedFacts, existingContextMarkdown)
    val deterministicContextProposal = factsToAppend.takeIf(List<*>::isNotEmpty)?.let { facts ->
        MarkdownUpdateProposal(
            operation = if (existingContextMarkdown == null) {
                MarkdownUpdateOperation.CREATE
            } else {
                MarkdownUpdateOperation.UPDATE
            },
            path = "context.md",
            title = "项目上下文",
            reason = "追加经 Evidence 验证的稳定项目事实",
            markdown = mergeContextMarkdown(existingContextMarkdown, facts),
            expectedAbsent = existingContextMarkdown == null,
        )
    }
    val contextValidated = parsed.copy(
        proposals = replaceContextProposal(parsed.proposals, deterministicContextProposal),
        contextFacts = factsToAppend,
    )
    if (wikiCitations.citations.isEmpty() && !wikiCoverage.hasMissingComparisonEvidence) return contextValidated
    return contextValidated.copy(
        proposals = contextValidated.proposals.map { proposal ->
            try {
                WikiMarkdownProposalValidator.validate(proposal, wikiCitations, wikiCoverage)
            } catch (error: WikiMarkdownValidationException) {
                throw MarkdownUpdatePlanningException(
                    "无法生成可审核的 Markdown 变更：${error.message}",
                    error,
                )
            }
        },
    )
}

private fun replaceContextProposal(
    proposals: List<MarkdownUpdateProposal>,
    contextProposal: MarkdownUpdateProposal?,
): List<MarkdownUpdateProposal> {
    var inserted = false
    return buildList {
        proposals.forEach { proposal ->
            if (proposal.path.isRootContextMarkdown()) {
                if (!inserted && contextProposal != null) {
                    add(contextProposal)
                    inserted = true
                }
            } else {
                add(proposal)
            }
        }
    }
}

private fun contextFactsNotAlreadyPresent(
    acceptedFacts: List<ContextFactCandidate>,
    existingContextMarkdown: String?,
): List<ContextFactCandidate> {
    val existingStatements = existingContextMarkdown?.let(::contextStatementsBySection).orEmpty()
    return acceptedFacts
        .sortedWith(compareBy<ContextFactCandidate> { it.section.ordinal }.thenBy { it.semanticKey })
        .distinctBy { it.section to it.statement.normalizedContextStatement() }
        .filterNot { fact ->
            fact.statement.normalizedContextStatement() in existingStatements[fact.section].orEmpty()
        }
}

private fun mergeContextMarkdown(
    existingContextMarkdown: String?,
    facts: List<ContextFactCandidate>,
): String {
    if (existingContextMarkdown == null) return buildNewContextMarkdown(facts)
    val lines = existingContextMarkdown.trimEnd().lines().toMutableList()
    facts.groupBy(ContextFactCandidate::section)
        .entries
        .sortedByDescending { (section, _) ->
            lines.indexOfFirst { line -> line.contextSectionOrNull() == section }
        }
        .forEach { (section, sectionFacts) ->
            val headingIndex = lines.indexOfFirst { line -> line.contextSectionOrNull() == section }
            val factLines = sectionFacts.flatMapIndexed { index, fact ->
                buildList {
                    if (index > 0) add("")
                    add("- ${fact.statement}")
                }
            }
            if (headingIndex < 0) {
                if (lines.isNotEmpty()) lines += ""
                lines += "## ${section.headingLabel()}"
                lines += ""
                lines += factLines
            } else {
                val nextHeadingIndex = (headingIndex + 1 until lines.size)
                    .firstOrNull { index -> lines[index].isLevelTwoHeading() }
                    ?: lines.size
                var insertionIndex = nextHeadingIndex
                while (insertionIndex > headingIndex + 1 && lines[insertionIndex - 1].isBlank()) {
                    insertionIndex -= 1
                }
                val hasExistingSectionContent = lines.subList(headingIndex + 1, insertionIndex).any(String::isNotBlank)
                val insertion = buildList {
                    if (hasExistingSectionContent || insertionIndex == headingIndex + 1) add("")
                    addAll(factLines)
                }
                lines.addAll(insertionIndex, insertion)
            }
        }
    return lines.joinToString("\n").trimEnd() + "\n"
}

private fun buildNewContextMarkdown(facts: List<ContextFactCandidate>): String {
    val factsBySection = facts
        .groupBy(ContextFactCandidate::section)
    return buildString {
        appendLine("# 项目上下文")
        ContextSection.entries.forEach { section ->
            appendLine()
            appendLine("## ${section.headingLabel()}")
            factsBySection[section].orEmpty().forEach { fact ->
                appendLine()
                appendLine("- ${fact.statement}")
            }
        }
    }.trimEnd() + "\n"
}

private fun ContextSection.headingLabel(): String = when (this) {
    ContextSection.PROJECT_GOALS -> "项目目标"
    ContextSection.KEY_DECISIONS -> "关键决策"
    ContextSection.CURRENT_STATUS -> "当前状态"
    ContextSection.FOLLOW_UP -> "待跟进"
}

private fun contextStatementsBySection(markdown: String): Map<ContextSection, Set<String>> {
    val statements = ContextSection.entries.associateWith { linkedSetOf<String>() }.toMutableMap()
    var currentSection: ContextSection? = null
    markdown.lineSequence().forEach { line ->
        if (line.isLevelTwoHeading()) {
            currentSection = line.contextSectionOrNull()
        } else {
            val normalized = line.removeMarkdownListPrefix().normalizedContextStatement()
            if (currentSection != null && normalized.isNotBlank()) {
                statements.getValue(requireNotNull(currentSection)) += normalized
            }
        }
    }
    return statements
}

private fun String.contextSectionOrNull(): ContextSection? {
    val heading = CONTEXT_HEADING.matchEntire(this)?.groupValues?.getOrNull(1)
        ?.trim()
        ?.trimEnd('#')
        ?.trim()
        ?: return null
    return ContextSection.entries.firstOrNull { it.headingLabel().equals(heading, ignoreCase = true) }
}

private fun String.isLevelTwoHeading(): Boolean = CONTEXT_HEADING.matches(this)

private fun String.removeMarkdownListPrefix(): String =
    replace(Regex("^\\s*(?:[-+*]|\\d+[.)])\\s+(?:\\[[ xX]\\]\\s+)?"), "")

private fun String.normalizedContextStatement(): String =
    Normalizer.normalize(trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

private val CONTEXT_HEADING = Regex("^\\s{0,3}##\\s+(.+?)\\s*$")

private fun String.isRootContextMarkdown(): Boolean =
    trim().replace('\\', '/').removePrefix("./").equals("context.md", ignoreCase = true)

fun buildMarkdownDiff(
    oldMarkdown: String,
    newMarkdown: String,
): List<MarkdownDiffLine> {
    val oldLines = markdownLines(oldMarkdown)
    val newLines = markdownLines(newMarkdown)
    val lengths = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
    for (oldIndex in oldLines.indices.reversed()) {
        for (newIndex in newLines.indices.reversed()) {
            lengths[oldIndex][newIndex] = if (oldLines[oldIndex] == newLines[newIndex]) {
                lengths[oldIndex + 1][newIndex + 1] + 1
            } else {
                maxOf(lengths[oldIndex + 1][newIndex], lengths[oldIndex][newIndex + 1])
            }
        }
    }

    val diff = mutableListOf<MarkdownDiffLine>()
    var oldIndex = 0
    var newIndex = 0
    while (oldIndex < oldLines.size && newIndex < newLines.size) {
        when {
            oldLines[oldIndex] == newLines[newIndex] -> {
                diff += MarkdownDiffLine(MarkdownDiffLineType.CONTEXT, oldLines[oldIndex])
                oldIndex += 1
                newIndex += 1
            }
            lengths[oldIndex + 1][newIndex] >= lengths[oldIndex][newIndex + 1] -> {
                diff += MarkdownDiffLine(MarkdownDiffLineType.REMOVED, oldLines[oldIndex])
                oldIndex += 1
            }
            else -> {
                diff += MarkdownDiffLine(MarkdownDiffLineType.ADDED, newLines[newIndex])
                newIndex += 1
            }
        }
    }
    while (oldIndex < oldLines.size) {
        diff += MarkdownDiffLine(MarkdownDiffLineType.REMOVED, oldLines[oldIndex])
        oldIndex += 1
    }
    while (newIndex < newLines.size) {
        diff += MarkdownDiffLine(MarkdownDiffLineType.ADDED, newLines[newIndex])
        newIndex += 1
    }
    return diff
}

fun markdownReviewSummary(
    proposals: List<MarkdownUpdateProposal>,
    retainedIndexes: Set<Int>,
): String {
    val kept = proposals.indices.count { it in retainedIndexes }
    val withdrawn = proposals.size - kept
    return "保留 $kept 项，撤回 $withdrawn 项"
}

fun markdownDiffStats(diff: List<MarkdownDiffLine>): MarkdownDiffStats =
    MarkdownDiffStats(
        addedLineCount = diff.count { it.type == MarkdownDiffLineType.ADDED },
        removedLineCount = diff.count { it.type == MarkdownDiffLineType.REMOVED },
    )

private fun extractJsonObject(response: String): String {
    val fenced = Regex("```\\s*(\\w+)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        .findAll(response)
        .mapNotNull { match ->
            val language = match.groupValues.getOrNull(1).orEmpty().trim()
            val content = match.groupValues.getOrNull(2).orEmpty().trim()
            content.takeIf {
                content.startsWith("{") || language.equals("json", ignoreCase = true)
            }
        }
        .firstOrNull()
    if (!fenced.isNullOrBlank()) return fenced
    val start = response.indexOf('{')
    val end = response.lastIndexOf('}')
    require(start >= 0 && end > start) { "LLM 未返回 Markdown 更新 JSON" }
    return response.substring(start, end + 1)
}

private fun String.toMarkdownOperation(): MarkdownUpdateOperation =
    when (trim().lowercase()) {
        "create", "new", "新增", "创建" -> MarkdownUpdateOperation.CREATE
        else -> MarkdownUpdateOperation.UPDATE
    }

private fun String.toContextSectionOrNull(): ContextSection? =
    runCatching { ContextSection.valueOf(trim().uppercase()) }.getOrNull()

private fun String.toFactOperationOrNull(): FactOperation? =
    runCatching { FactOperation.valueOf(trim().uppercase()) }.getOrNull()

private fun markdownLines(markdown: String): List<String> =
    if (markdown.isEmpty()) emptyList() else markdown.lines()

private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
