package com.harnessapk.chat

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

private fun name(localName: String?, qName: String?): String =
    localName?.takeIf(String::isNotEmpty) ?: qName?.substringAfterLast(':').orEmpty()

/**
 * 轻量 xlsx 文本抽取：xlsx 本质是 zip + XML，只读 sharedStrings 与 worksheet 的
 * 单元格值（含公式缓存值与 inline string），不做样式/合并单元格/图表。
 * 使用 SAX（JVM 与 Android 皆有），不依赖 POI。
 */
object XlsxLightReader {
    fun extract(bytes: ByteArray): String {
        var sharedStrings: List<String> = emptyList()
        val sheets = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    when {
                        name == "xl/sharedStrings.xml" -> sharedStrings = SharedStringsHandler.parse(zip.readBytes())
                        name.startsWith("xl/worksheets/") && name.endsWith(".xml") ->
                            sheets += name to zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (sheets.isEmpty()) return ""
        return sheets.sortedBy { it.first }.joinToString("\n\n") { (name, bytes) ->
            val label = name.substringAfterLast('/').removeSuffix(".xml")
            "## $label\n" + SheetHandler.parse(bytes, sharedStrings)
        }.trim()
    }

    private object SharedStringsHandler {
        fun parse(bytes: ByteArray): List<String> {
            val strings = mutableListOf<String>()
            val parser = SAXParserFactory.newInstance().newSAXParser()
            parser.parse(ByteArrayInputStream(bytes), object : DefaultHandler() {
                private var inItem = false
                private val buffer = StringBuilder()
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    if (name(localName, qName) == "si") {
                        inItem = true
                        buffer.clear()
                    }
                }

                override fun characters(ch: CharArray?, start: Int, length: Int) {
                    if (inItem) buffer.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    if (name(localName, qName) == "si" && inItem) {
                        strings += buffer.toString()
                        inItem = false
                    }
                }
            })
            return strings
        }
    }

    private object SheetHandler {
        fun parse(bytes: ByteArray, sharedStrings: List<String>): String {
            val rows = mutableListOf<MutableList<String>>()
            var row: MutableList<String>? = null
            var cell = Cell()
            var inValueText = false
            val buffer = StringBuilder()
            val parser = SAXParserFactory.newInstance().newSAXParser()
            parser.parse(ByteArrayInputStream(bytes), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    when (name(localName, qName)) {
                        "row" -> row = mutableListOf()
                        "c" -> {
                            val ref = attributes?.getValue("r").orEmpty()
                            cell = Cell(column = columnIndexFromRef(ref), type = attributes?.getValue("t").orEmpty())
                            buffer.clear()
                            inValueText = false
                        }
                        "is" -> cell = cell.copy(inline = true)
                        "v" -> inValueText = true
                        "t" -> if (cell.inline) inValueText = true
                        "f" -> {
                            inValueText = false
                            buffer.clear()
                        }
                    }
                }

                override fun characters(ch: CharArray?, start: Int, length: Int) {
                    if (inValueText) buffer.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    when (name(localName, qName)) {
                        "v", "t" -> {
                            if (cell.inline || !cell.valueSet) {
                                cell = cell.copy(value = buffer.toString(), valueSet = true)
                            }
                            inValueText = false
                        }
                        "f" -> buffer.clear()
                        "c" -> {
                            row?.let { mutableList ->
                                val index = cell.column.coerceAtLeast(mutableList.size)
                                while (mutableList.size < index) mutableList += ""
                                mutableList += cell.displayValue(sharedStrings)
                            }
                            cell = Cell()
                            buffer.clear()
                            inValueText = false
                        }
                        "row" -> {
                            row?.let { if (it.any { value -> value.isNotBlank() }) rows += it }
                            row = null
                        }
                    }
                }
            })
            return rows.joinToString("\n") { cells -> cells.joinToString(" | ") }
        }

        private fun columnIndexFromRef(ref: String): Int {
            var index = 0
            for (ch in ref) {
                if (ch in 'A'..'Z') index = index * 26 + (ch - 'A' + 1) else break
            }
            return index - 1
        }

        private data class Cell(
            val column: Int = -1,
            val type: String = "",
            val inline: Boolean = false,
            val value: String = "",
            val valueSet: Boolean = false,
        ) {
            fun displayValue(sharedStrings: List<String>): String = when (type) {
                "s" -> sharedStrings.getOrNull(value.toIntOrNull() ?: -1).orEmpty()
                "inlineStr" -> value
                "b" -> if (value == "1") "TRUE" else "FALSE"
                else -> value
            }
        }
    }
}

/** 轻量 docx 文本抽取：只取 word/document.xml 的段落文本。 */
object DocxLightReader {
    fun extract(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    return DocumentHandler.parse(zip.readBytes())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ""
    }

    private object DocumentHandler {
        fun parse(bytes: ByteArray): String {
            val paragraphs = mutableListOf<StringBuilder>()
            var inText = false
            val parser = SAXParserFactory.newInstance().newSAXParser()
            parser.parse(ByteArrayInputStream(bytes), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    when (name(localName, qName)) {
                        "p" -> paragraphs += StringBuilder()
                        "t" -> inText = true
                    }
                }

                override fun characters(ch: CharArray?, start: Int, length: Int) {
                    if (inText) paragraphs.lastOrNull()?.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    if (name(localName, qName) == "t") inText = false
                }
            })
            return paragraphs
                .map { it.toString().trim() }
                .filter(String::isNotBlank)
                .joinToString("\n")
        }
    }
}
