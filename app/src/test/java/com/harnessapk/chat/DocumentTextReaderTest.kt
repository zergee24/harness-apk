package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentTextReaderTest {
    private fun zipOf(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun xlsxBytes(sharedStrings: String, sheet: String): ByteArray = zipOf(
        mapOf(
            "xl/sharedStrings.xml" to sharedStrings,
            "xl/worksheets/sheet1.xml" to sheet,
        ),
    )

    @Test
    fun xlsxReaderResolvesSharedStringsAndPadsEmptyCells() {
        val shared = """
            <sst count="3" uniqueCount="3">
              <si><t>项目</t></si>
              <si><t>金额</t></si>
              <si><r><t>多</t></r><r><t>段文本</t></r></si>
            </sst>
        """.trimIndent()
        val sheet = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>123.45</v></c></row>
                <row r="3"><c r="A3" t="inlineStr"><is><t>内联</t></is></c><c r="B3" t="b"><v>1</v></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val text = XlsxLightReader.extract(xlsxBytes(shared, sheet))

        assertTrue(text.contains("## sheet1"))
        val lines = text.lines().filter(String::isNotBlank).drop(1)
        assertEquals("项目 |  | 金额", lines[0])
        assertEquals("多段文本 | 123.45", lines[1])
        assertEquals("内联 | TRUE", lines[2])
    }

    @Test
    fun xlsxReaderKeepsFormulaCachedValues() {
        val sheet = """
            <worksheet><sheetData>
              <row r="1"><c r="A1"><f>SUM(B1:B2)</f><v>42</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()

        val text = XlsxLightReader.extract(xlsxBytes("<sst/>", sheet))

        assertTrue(text.contains("42"))
    }

    @Test
    fun withDocumentBlocksPrependsBlocksAndKeepsUserTextLast() {
        val doc = ExtractedDocument(
            uri = "content://test/bill",
            fileName = "账单.xlsx",
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            text = "项目 | 金额",
            truncated = false,
            originalCharCount = 7,
        )

        assertEquals(
            "【附件：账单.xlsx】\n项目 | 金额\n【附件结束】",
            DocumentTextExtractor.withDocumentBlocks("", listOf(doc)),
        )
        assertEquals(
            "【附件：账单.xlsx】\n项目 | 金额\n【附件结束】\n\n帮我看看这份账单",
            DocumentTextExtractor.withDocumentBlocks("帮我看看这份账单", listOf(doc)),
        )
        assertEquals("纯文本", DocumentTextExtractor.withDocumentBlocks("纯文本", emptyList()))
    }

    @Test
    fun docxReaderExtractsParagraphText() {
        val document = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>第一段</w:t><w:t>合并同段</w:t></w:r></w:p>
                <w:p><w:r><w:t>  第二段带空格 </w:t></w:r></w:p>
                <w:p><w:r><w:t></w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val text = DocxLightReader.extract(zipOf(mapOf("word/document.xml" to document)))

        assertEquals("第一段合并同段\n第二段带空格", text)
    }
}
