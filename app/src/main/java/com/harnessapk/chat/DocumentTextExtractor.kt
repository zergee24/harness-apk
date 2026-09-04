package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

data class ExtractedDocument(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val text: String,
    val truncated: Boolean,
    val originalCharCount: Int,
)

/**
 * 会话文档附件的文本抽取：pdf（文本层）/ xlsx / docx / csv / txt / md。
 * 抽出的文本在发送时作为结构化文本块并入用户消息，不依赖任何 provider 的文件 API。
 * 扫描版 PDF（无文本层）抽不出内容，应提示改用拍照提问。
 */
object DocumentTextExtractor {
    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_CHARS_PER_FILE = 20_000
    const val MAX_DOCUMENTS_PER_MESSAGE = 3

    val SUPPORTED_MIME_TYPES = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain",
        "text/csv",
        "text/markdown",
        "text/tab-separated-values",
    )

    fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)
                else null
            } else {
                null
            }
        }.takeIf { !it.isNullOrBlank() } ?: uri.lastPathSegment ?: "document"

    fun extract(context: Context, uri: Uri): ExtractedDocument {
        val fileName = displayName(context, uri)
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取所选文件")
        if (bytes.size > MAX_FILE_BYTES) {
            throw IllegalArgumentException("文件超过 10 MB，请拆分后重试")
        }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val fullText: String = when {
            extension == "pdf" || mimeType == "application/pdf" -> extractPdf(context, bytes)
            extension == "xlsx" || mimeType.contains("spreadsheetml") -> XlsxLightReader.extract(bytes)
            extension == "docx" || mimeType.contains("wordprocessingml") -> DocxLightReader.extract(bytes)
            extension in TEXT_EXTENSIONS || mimeType.startsWith("text/") -> String(bytes, Charsets.UTF_8)
            else -> throw IllegalArgumentException("暂不支持该文件类型，可选 PDF / Word / Excel / CSV / TXT")
        }
        val originalCharCount = fullText.length
        val truncated = originalCharCount > MAX_CHARS_PER_FILE
        val text = if (truncated) {
            fullText.take(MAX_CHARS_PER_FILE) + "\n…（文件过长，已截断）"
        } else {
            fullText
        }
        if (text.isBlank()) {
            throw IllegalArgumentException("未能从文件中提取到文本；扫描版 PDF 请改用拍照提问")
        }
        return ExtractedDocument(
            uri = uri.toString(),
            fileName = fileName,
            mimeType = mimeType,
            text = text,
            truncated = truncated,
            originalCharCount = originalCharCount,
        )
    }

    /** 发送时把附件文本组织为结构化块并入用户消息。 */
    fun textBlock(document: ExtractedDocument): String =
        "【附件：${document.fileName}${if (document.truncated) "，已截断" else ""}】\n${document.text}\n【附件结束】"

    /** 附件为空时原文返回；有附件时文档块前置，用户输入保持在消息末尾。 */
    fun withDocumentBlocks(userText: String, documents: List<ExtractedDocument>): String {
        if (documents.isEmpty()) return userText
        val blocks = documents.joinToString("\n\n") { textBlock(it) }
        val trimmed = userText.trim()
        return if (trimmed.isEmpty()) blocks else "$blocks\n\n$trimmed"
    }

    private fun extractPdf(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(bytes).use { document ->
            return PDFTextStripper().getText(document)
        }
    }

    private val TEXT_EXTENSIONS = setOf("txt", "csv", "md", "markdown", "tsv", "log", "json")
}
