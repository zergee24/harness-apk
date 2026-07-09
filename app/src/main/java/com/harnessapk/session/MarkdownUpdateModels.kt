package com.harnessapk.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
)

data class MarkdownUpdatePlan(
    val proposals: List<MarkdownUpdateProposal>,
)

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
    )
}

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

private fun markdownLines(markdown: String): List<String> =
    if (markdown.isEmpty()) emptyList() else markdown.lines()

private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
