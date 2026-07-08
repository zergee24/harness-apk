package com.harnessapk.project

import java.io.File
import java.util.Locale

data class Project(
    val id: String,
    val name: String,
    val rootDirectory: File,
    val updatedAt: Long,
)

data class ProjectDeliverable(
    val id: String,
    val title: String,
    val relativePath: String,
    val template: DeliverableTemplate,
    val updatedAt: Long,
    val artifactType: ProjectArtifactType = ProjectArtifactType.MARKDOWN,
)

data class ProjectSessionSummary(
    val id: String,
    val title: String,
    val markdown: String,
)

enum class DeliverableTemplate(
    val label: String,
    val directoryName: String,
) {
    REQUIREMENT("需求文档", "requirements"),
    SOLUTION("方案文档", "solutions"),
    RESEARCH("调研记录", "research"),
    TODO("任务清单", "todos"),
    REPORT("验收报告", "reports"),
    RETROSPECTIVE("复盘总结", "retrospectives"),
    CONTEXT("项目上下文", ""),
    SESSION("会话摘要", "sessions"),
}

enum class ProjectArtifactType(
    val label: String,
    val defaultMimeType: String,
    val isTextPreviewable: Boolean,
    val rendersAsMarkdown: Boolean = false,
) {
    MARKDOWN(
        label = "Markdown",
        defaultMimeType = "text/markdown",
        isTextPreviewable = true,
        rendersAsMarkdown = true,
    ),
    DOCUMENT(
        label = "Word",
        defaultMimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        isTextPreviewable = false,
    ),
    SPREADSHEET(
        label = "Excel",
        defaultMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        isTextPreviewable = false,
    ),
    PRESENTATION(
        label = "PPT",
        defaultMimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        isTextPreviewable = false,
    ),
    PDF(
        label = "PDF",
        defaultMimeType = "application/pdf",
        isTextPreviewable = false,
    ),
    CODE(
        label = "代码",
        defaultMimeType = "text/plain",
        isTextPreviewable = true,
    ),
    IMAGE(
        label = "图片",
        defaultMimeType = "image/*",
        isTextPreviewable = false,
    ),
    TEXT(
        label = "文本",
        defaultMimeType = "text/plain",
        isTextPreviewable = true,
    ),
    OTHER(
        label = "其他",
        defaultMimeType = "*/*",
        isTextPreviewable = false,
    ),
}

fun projectArtifactTypeForPath(path: String): ProjectArtifactType {
    val normalized = path.substringAfterLast('/').lowercase(Locale.ROOT)
    val extension = normalized.substringAfterLast('.', missingDelimiterValue = "")
    return when {
        extension in markdownExtensions -> ProjectArtifactType.MARKDOWN
        extension in documentExtensions -> ProjectArtifactType.DOCUMENT
        extension in spreadsheetExtensions -> ProjectArtifactType.SPREADSHEET
        extension in presentationExtensions -> ProjectArtifactType.PRESENTATION
        extension == "pdf" -> ProjectArtifactType.PDF
        extension in imageExtensions -> ProjectArtifactType.IMAGE
        extension in codeExtensions || normalized in codeFileNames -> ProjectArtifactType.CODE
        extension in textExtensions -> ProjectArtifactType.TEXT
        else -> ProjectArtifactType.OTHER
    }
}

class ProjectWorkspaceException(message: String) : IllegalArgumentException(message)

private val markdownExtensions = setOf("md", "markdown", "mdown")
private val documentExtensions = setOf("doc", "docx", "odt", "rtf")
private val spreadsheetExtensions = setOf("xls", "xlsx", "csv", "tsv", "ods")
private val presentationExtensions = setOf("ppt", "pptx", "odp")
private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")
private val textExtensions = setOf("txt", "log")
private val codeExtensions = setOf(
    "kt",
    "kts",
    "java",
    "js",
    "jsx",
    "ts",
    "tsx",
    "py",
    "swift",
    "go",
    "rs",
    "c",
    "cc",
    "cpp",
    "h",
    "hpp",
    "cs",
    "rb",
    "php",
    "sh",
    "bash",
    "zsh",
    "sql",
    "json",
    "xml",
    "yml",
    "yaml",
    "toml",
    "gradle",
    "html",
    "css",
    "scss",
)
private val codeFileNames = setOf(
    "dockerfile",
    "makefile",
    "gradle.properties",
    ".gitignore",
    ".editorconfig",
)
