package com.harnessapk.markdownpdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

class AndroidMarkdownPdfWriter {
    fun write(markdown: String, outputStream: OutputStream) {
        val source = buildMarkdownPdfDocument(markdown)
        val pdf = PdfDocument()
        try {
            var pageNumber = 1
            var page = pdf.startPage(pageInfo(pageNumber))
            var canvas = page.canvas
            var y = pageMargin

            fun newPage() {
                pdf.finishPage(page)
                pageNumber += 1
                page = pdf.startPage(pageInfo(pageNumber))
                canvas = page.canvas
                y = pageMargin
            }

            fun ensureSpace(height: Float) {
                if (y + height > pageHeight - pageMargin) newPage()
            }

            source.lines.forEach { line ->
                if (line.style == MarkdownPdfTextStyle.DIVIDER) {
                    ensureSpace(24f)
                    dividerPaint.color = Color.LTGRAY
                    canvas.drawLine(pageMargin, y + 10f, pageWidth - pageMargin, y + 10f, dividerPaint)
                    y += 24f
                    return@forEach
                }

                val paint = paintFor(line.style)
                val lineHeight = lineHeightFor(line.style)
                val before = topSpacingFor(line.style)
                val wrapped = wrapText(line.text, paint, pageWidth - pageMargin * 2)
                val totalHeight = before + wrapped.size * lineHeight + bottomSpacingFor(line.style)
                ensureSpace(totalHeight)
                y += before

                if (line.style == MarkdownPdfTextStyle.CODE) {
                    codeBackgroundPaint.color = 0xFFEFEFEF.toInt()
                    canvas.drawRect(
                        pageMargin - 6f,
                        y - lineHeight + 6f,
                        pageWidth - pageMargin + 6f,
                        y + wrapped.size * lineHeight + 2f,
                        codeBackgroundPaint,
                    )
                }

                wrapped.forEach { segment ->
                    canvas.drawText(segment, pageMargin, y, paint)
                    y += lineHeight
                }
                y += bottomSpacingFor(line.style)
            }

            pdf.finishPage(page)
            pdf.writeTo(outputStream)
        } finally {
            pdf.close()
        }
    }

    private fun paintFor(style: MarkdownPdfTextStyle): Paint = when (style) {
        MarkdownPdfTextStyle.HEADING_1 -> heading1Paint
        MarkdownPdfTextStyle.HEADING_2 -> heading2Paint
        MarkdownPdfTextStyle.HEADING_3 -> heading3Paint
        MarkdownPdfTextStyle.CODE -> codePaint
        MarkdownPdfTextStyle.TABLE -> tablePaint
        MarkdownPdfTextStyle.QUOTE -> quotePaint
        MarkdownPdfTextStyle.LIST,
        MarkdownPdfTextStyle.BODY,
        MarkdownPdfTextStyle.DIVIDER,
        -> bodyPaint
    }

    private fun lineHeightFor(style: MarkdownPdfTextStyle): Float = when (style) {
        MarkdownPdfTextStyle.HEADING_1 -> 30f
        MarkdownPdfTextStyle.HEADING_2 -> 26f
        MarkdownPdfTextStyle.HEADING_3 -> 23f
        MarkdownPdfTextStyle.CODE -> 18f
        MarkdownPdfTextStyle.TABLE -> 19f
        else -> 21f
    }

    private fun topSpacingFor(style: MarkdownPdfTextStyle): Float = when (style) {
        MarkdownPdfTextStyle.HEADING_1 -> 12f
        MarkdownPdfTextStyle.HEADING_2 -> 10f
        MarkdownPdfTextStyle.HEADING_3 -> 8f
        MarkdownPdfTextStyle.CODE -> 10f
        MarkdownPdfTextStyle.TABLE -> 6f
        else -> 5f
    }

    private fun bottomSpacingFor(style: MarkdownPdfTextStyle): Float = when (style) {
        MarkdownPdfTextStyle.HEADING_1,
        MarkdownPdfTextStyle.HEADING_2,
        MarkdownPdfTextStyle.HEADING_3,
        -> 8f
        MarkdownPdfTextStyle.CODE -> 10f
        else -> 4f
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf(" ")
        val lines = mutableListOf<String>()
        var rest = text.trimEnd()
        while (rest.isNotEmpty()) {
            val count = paint.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
            val rawSegment = rest.take(count)
            val splitAt = rawSegment.lastIndexOf(' ').takeIf { it > rawSegment.length / 2 } ?: rawSegment.length
            val segment = rest.take(splitAt).trimEnd().ifBlank { rest.take(count) }
            lines += segment
            rest = rest.drop(segment.length).trimStart()
        }
        return lines
    }

    private companion object {
        const val pageWidth = 595
        const val pageHeight = 842
        const val pageMargin = 48f

        fun pageInfo(pageNumber: Int): PdfDocument.PageInfo =
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()

        val bodyPaint = textPaint(size = 14f)
        val heading1Paint = textPaint(size = 22f, bold = true)
        val heading2Paint = textPaint(size = 18f, bold = true)
        val heading3Paint = textPaint(size = 16f, bold = true)
        val codePaint = textPaint(size = 12f, monospace = true)
        val tablePaint = textPaint(size = 12.5f)
        val quotePaint = textPaint(size = 14f).apply { color = Color.DKGRAY }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.2f }
        val codeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun textPaint(size: Float, bold: Boolean = false, monospace: Boolean = false): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = size
                typeface = when {
                    monospace -> Typeface.MONOSPACE
                    bold -> Typeface.DEFAULT_BOLD
                    else -> Typeface.DEFAULT
                }
            }
    }
}
