package com.harnessapk.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

sealed interface DocumentExtractionResult {
    /** 含文本层的文档：抽取文本并入消息正文。 */
    data class TextDocument(val document: ExtractedDocument) : DocumentExtractionResult

    /** 扫描版 PDF（无有效文本层）：按页转图，走视觉模型管道。 */
    data class ScannedPdf(val totalPages: Int) : DocumentExtractionResult
}

data class ScannedPdfRenderResult(
    val totalPages: Int,
    val renderedPages: Int,
    val uris: List<Uri>,
)

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
    internal const val MIN_TEXT_LAYER_CHARS = 50
    private const val RENDER_TARGET_WIDTH_PX = 1240

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

    fun extract(context: Context, uri: Uri): DocumentExtractionResult {
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
        if (extension == "pdf" || mimeType == "application/pdf") {
            if (isTextLayerMissing(fullText)) return DocumentExtractionResult.ScannedPdf(totalPages = pdfPageCount(context, uri))
        }
        val originalCharCount = fullText.length
        val truncated = originalCharCount > MAX_CHARS_PER_FILE
        val text = if (truncated) {
            fullText.take(MAX_CHARS_PER_FILE) + "\n…（文件过长，已截断）"
        } else {
            fullText
        }
        if (text.isBlank()) {
            throw IllegalArgumentException("未能从文件中提取到文本")
        }
        return DocumentExtractionResult.TextDocument(
            ExtractedDocument(
                uri = uri.toString(),
                fileName = fileName,
                mimeType = mimeType,
                text = text,
                truncated = truncated,
                originalCharCount = originalCharCount,
            ),
        )
    }

    /**
     * 扫描版 PDF：PdfRenderer 逐页渲染为 JPEG（写入 [writePage] 给出的受管地址），
     * 交由视觉模型读图——等价于对每页做「拍照提问」。渲染页数对齐单消息图片上限。
     */
    fun renderScannedPdfPages(
        context: Context,
        uri: Uri,
        maxPages: Int,
        writePage: (index: Int, jpegBytes: ByteArray) -> Uri,
    ): ScannedPdfRenderResult {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("无法读取所选文件")
        descriptor.use { parcel ->
            PdfRenderer(parcel).use { renderer ->
                val totalPages = renderer.pageCount
                val uris = mutableListOf<Uri>()
                for (index in 0 until minOf(totalPages, maxPages)) {
                    renderer.openPage(index).use { page ->
                        val scale = minOf(
                            RENDER_TARGET_WIDTH_PX.toFloat() / page.width.coerceAtLeast(1),
                            RENDER_TARGET_WIDTH_PX.toFloat() / page.height.coerceAtLeast(1),
                            3f,
                        ).coerceAtLeast(0.5f)
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val jpeg = ByteArrayOutputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            out.toByteArray()
                        }
                        bitmap.recycle()
                        uris += writePage(index, jpeg)
                    }
                }
                return ScannedPdfRenderResult(totalPages = totalPages, renderedPages = uris.size, uris = uris)
            }
        }
    }

    /** 文本层缺失判定：扫描件常见「仅页码/水印级别文字」。 */
    internal fun isTextLayerMissing(text: String): Boolean = text.trim().length < MIN_TEXT_LAYER_CHARS

    private fun pdfPageCount(context: Context, uri: Uri): Int {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return 0).use { document -> return document.numberOfPages }
    }

    private fun extractPdf(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(bytes).use { document ->
            return PDFTextStripper().getText(document)
        }
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

    private val TEXT_EXTENSIONS = setOf("txt", "csv", "md", "markdown", "tsv", "log", "json")
}
