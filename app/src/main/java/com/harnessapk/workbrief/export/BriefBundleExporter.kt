package com.harnessapk.workbrief.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.harnessapk.storage.AppDatabase
import com.harnessapk.workbrief.capture.PageInk
import com.harnessapk.workbrief.journal.StrokeJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * .hbrief 无音频 bundle 导出（设计 §15.3 P1 子集）：
 * manifest / timeline / canvas(pages+strokes) / markers / anchors / preview(每页 PNG)。
 * 导出基于当前 Room 数据 + journal 重放；完成后 brief 进入 READY 并登记修订。
 */
class BriefBundleExporter(
    private val context: Context,
    private val db: AppDatabase,
    private val journalDir: File,
    private val density: Float = 1.75f,
) {
    data class Result(
        val file: File,
        val sha256: String,
        val manifestSha256: String,
        val revision: Int,
        val title: String,
    )

    suspend fun export(briefId: String, outputDirectory: File): Result = withContext(Dispatchers.IO) {
        val brief = db.workBriefDao().getById(briefId) ?: error("简报 $briefId 不存在")
        val session = db.workBriefDao().sessionForBrief(briefId) ?: error("简报 $briefId 无场次")
        val pages = db.briefCaptureDao().pagesBySession(session.id)
        val timeline = db.briefCaptureDao().timelineBySession(session.id)
        val markers = db.briefCaptureDao().markersBySession(session.id)
        val anchors = db.briefCaptureDao().anchorsBySession(session.id)

        // journal 重放 → 每页笔迹数据（不经过位图，直接结构化）
        val journal = StrokeJournal.open(File(journalDir, "${session.id}.journal")).first
        val records = journal.replay().records
        journal.close()

        data class Stroke(val pageId: String, val tool: String, val pts: JSONArray)
        val strokesByPage = linkedMapOf<String, MutableList<Stroke>>()
        val erases = mutableListOf<Triple<String, Double, Double>>()
        for (record in records) {
            val obj = JSONObject(String(record.payload, Charsets.UTF_8))
            val pageId = obj.optString("pageId")
            when (record.type) {
                StrokeJournal.TYPE_STROKE_COMMITTED -> {
                    val stroke = Stroke(pageId, obj.optString("tool", "stylus"), obj.optJSONArray("pts") ?: JSONArray())
                    strokesByPage.getOrPut(pageId) { mutableListOf() }.add(stroke)
                }
                StrokeJournal.TYPE_ERASE_POINT -> {
                    erases.add(Triple(pageId, obj.optDouble("x"), obj.optDouble("y")))
                }
            }
        }

        outputDirectory.mkdirs()
        val zipFile = File(outputDirectory, "${briefId}.hbrief.tmp")
        val entryHashes = linkedMapOf<String, String>()

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        var manifestBytes = ByteArray(0)

        fun addEntry(name: String, bytes: ByteArray) {
            entryHashes[name] = sha256(bytes)
        }

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                addEntry(name, bytes)
                if (name == "manifest.json") manifestBytes = bytes
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }

            // canvas/strokes/<pageId>.jsonl + preview/pages/<pageId>.png
            pages.forEach { page ->
                val ink = PageInk(page.id, page.logicalWidth, page.logicalHeight, density)
                val strokes = strokesByPage[page.id].orEmpty()
                strokes.forEach { stroke ->
                    val pts = stroke.pts
                    for (i in 0 until pts.length()) {
                        val arr = pts.getJSONArray(i)
                        ink.appendPoint(stroke.tool, arr.getDouble(0), arr.getDouble(1), arr.getLong(2), arr.getDouble(3).toFloat())
                    }
                }
                erases.filter { it.first == page.id }.forEach { (_, x, y) ->
                    ink.eraseAt(x, y)
                }
                val jsonl = StringBuilder()
                strokes.forEach { stroke ->
                    jsonl.append(
                        JSONObject()
                            .put("kind", "stroke")
                            .put("tool", stroke.tool)
                            .put("pts", stroke.pts)
                            .toString(),
                    ).append('\n')
                }
                entry("canvas/strokes/${page.id}.jsonl", jsonl.toString().toByteArray())

                ink.bitmap?.let { bmp ->
                    val png = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, png)
                    entry("preview/pages/${page.id}.png", png.toByteArray())
                }
            }

            // timeline / markers / anchors
            val tl = StringBuilder()
            timeline.forEach { e ->
                tl.append(
                    JSONObject()
                        .put("eventId", e.eventId)
                        .put("sequence", e.sequence)
                        .put("type", e.type)
                        .put("pageId", e.pageId ?: JSONObject.NULL)
                        .put("atOffsetMs", e.atOffsetMs)
                        .toString(),
                ).append('\n')
            }
            entry("timeline/events.jsonl", tl.toString().toByteArray())

            val mk = StringBuilder()
            markers.forEach { m ->
                mk.append(
                    JSONObject()
                        .put("id", m.id)
                        .put("type", m.type)
                        .put("pageId", m.pageId ?: JSONObject.NULL)
                        .put("atOffsetMs", m.atOffsetMs)
                        .put("note", m.note)
                        .put("resolvedAt", m.resolvedAt ?: JSONObject.NULL)
                        .put("createdAt", m.createdAt)
                        .toString(),
                ).append('\n')
            }
            entry("markers/markers.jsonl", mk.toString().toByteArray())

            val an = StringBuilder()
            anchors.forEach { a ->
                an.append(
                    JSONObject()
                        .put("id", a.id)
                        .put("type", a.type)
                        .put("relativePath", a.relativePath)
                        .put("startLine", a.startLine ?: JSONObject.NULL)
                        .put("endLine", a.endLine ?: JSONObject.NULL)
                        .put("contentHash", a.contentHash)
                        .put("manualLabel", a.manualLabel ?: JSONObject.NULL)
                        .put("createdAt", a.createdAt)
                        .toString(),
                ).append('\n')
            }
            entry("anchors/anchors.jsonl", an.toString().toByteArray())

            // canvas/pages.json
            val pagesArr = JSONArray()
            pages.forEach { p ->
                pagesArr.put(
                    JSONObject()
                        .put("pageId", p.id)
                        .put("pageIndex", p.pageIndex)
                        .put("logicalWidth", p.logicalWidth)
                        .put("logicalHeight", p.logicalHeight)
                        .put("backgroundType", p.backgroundType),
                )
            }
            entry("canvas/pages.json", (JSONObject().put("pages", pagesArr)).toString().toByteArray())

            // manifest.json（最后加入：包含其余条目哈希）
            val manifest = JSONObject()
                .put("schemaVersion", 1)
                .put("minReaderVersion", 1)
                .put("briefId", brief.id)
                .put("projectLocator", brief.projectId)
                .put("title", brief.title)
                .put("status", "READY")
                .put("revision", brief.revision + 1)
                .put("sourceBriefId", JSONObject.NULL)
                .put("continuationOfBriefId", JSONObject.NULL)
                .put("createdAt", brief.createdAt)
                .put("durationMs", session.durationMs)
                .put("activeDurationMs", session.activeDurationMs)
                .put("audioAvailability", "NONE")
                .put("entryHashes", JSONObject(entryHashes))
            entry("manifest.json", manifest.toString().toByteArray())
        }

        val bytes = zipFile.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifestSha = if (manifestBytes.isEmpty()) "" else sha256Of(manifestBytes)
        val final = File(outputDirectory, "${brief.id}.hbrief")
        zipFile.renameTo(final)

        Result(
            file = final,
            sha256 = sha,
            manifestSha256 = manifestSha,
            revision = brief.revision + 1,
            title = brief.title,
        )
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
