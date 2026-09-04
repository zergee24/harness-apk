package com.harnessapk.workbrief.capture

import android.content.Context
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.CanvasPageEntity
import com.harnessapk.workbrief.UserMarkerType
import com.harnessapk.workbrief.WorkBriefRepository
import com.harnessapk.workbrief.journal.StrokeJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 记录会话控制器：页级墨水状态（PageInk）+ journal 持久化 + 仓库状态推进。
 *
 * journal 记录载荷（JSON 文本，UTF-8 字节）：
 * - STROKE_COMMITTED {"pageId","tool","pts":[[x,y,t,pressure]...]}
 * - ERASE_POINT      {"pageId","x","y"}
 * - PAGE_ADDED       {"pageId","index"}
 * 记录顺序即事实顺序；重放按序应用即恢复会话墨迹（§17.2）。
 * 单写者：所有 append 调用都发生在同一控制器协程/主线程。
 */
class BriefCaptureController(
    context: Context,
    private val db: AppDatabase,
    private val repository: WorkBriefRepository,
    private val scope: CoroutineScope,
    val briefId: String,
    private val journalDir: File = File(
        context.getExternalFilesDir(null)?.apply { mkdirs() } ?: context.filesDir,
        "work-brief-journals",
    ),
) {
    data class PageUi(val pageId: String, val pageIndex: Int, val ink: PageInk)

    val pages = linkedMapOf<String, PageUi>()
    var currentPageId: String? = null
        private set
    var paused = false
        private set
    var eraserMode = false
    var journal: StrokeJournal? = null
        private set

    /** journal 全量记录（顺序即事实顺序），供回放页使用。 */
    var replayRecords: List<StrokeJournal.Record> = emptyList()
        private set

    /** 墨迹脏区回调：View 层据此做局部失效（e-ink 局部快刷）。 */
    var onInkChanged: (dirtyLeft: Int, dirtyTop: Int, dirtyRight: Int, dirtyBottom: Int) -> Unit =
        { _, _, _, _ -> }

    /** 可恢复错误上屏（不崩 App）。 */
    var onError: ((String) -> Unit)? = null

    private var sessionOriginWallClock = 0L

    val currentPage: PageUi? get() = currentPageId?.let { pages[it] }

    suspend fun prepare() {
        val session = db.workBriefDao().sessionForBrief(briefId) ?: error("简报 $briefId 无场次")
        sessionOriginWallClock = session.wallClockStartedAt
        db.briefCaptureDao().pagesBySession(session.id).forEach { page ->
            pages[page.id] = PageUi(page.id, page.pageIndex, PageInk(page.id, CANVAS_WIDTH, CANVAS_HEIGHT) { l, t, r, b ->
                onInkChanged(l, t, r, b)
            })
        }
        currentPageId = pages.values.minByOrNull { it.pageIndex }?.pageId
        android.util.Log.w("BriefCapture", "prepare: briefId=$briefId session=${session.id} status=${session.status}")
        val (journal, replayResult) = StrokeJournal.open(File(journalDir, "${session.id}.journal"))
        this.journal = journal
        replayRecords = replayResult.records
        replayResult.records.forEach { record -> applyRecord(record.type, String(record.payload, Charsets.UTF_8)) }
        if (replayResult.truncated) {
            // §17.2 可见恢复警告：P1 以日志承载，Task 9 真机走查人工确认。
            android.util.Log.w("BriefCapture", "journal 尾部损坏已截断：${session.id}")
        }
    }

    private fun inkFor(pageId: String): PageInk =
        pages.getOrPut(pageId) {
            PageUi(
                pageId,
                pages.size,
                PageInk(pageId, CANVAS_WIDTH, CANVAS_HEIGHT) { l, t, r, b ->
                    onInkChanged(l, t, r, b)
                },
            )
        }.ink

    private fun applyRecord(type: Byte, payload: String) {
        val obj = JSONObject(payload)
        when (type) {
            StrokeJournal.TYPE_STROKE_COMMITTED -> {
                val ink = inkFor(obj.getString("pageId"))
                val tool = obj.optString("tool", "stylus")
                val pts = obj.getJSONArray("pts")
                for (i in 0 until pts.length()) {
                    val arr = pts.getJSONArray(i)
                    ink.appendPoint(tool, arr.getDouble(0), arr.getDouble(1), arr.getLong(2), arr.getDouble(3).toFloat())
                }
            }
            StrokeJournal.TYPE_ERASE_POINT -> {
                inkFor(obj.getString("pageId")).eraseAt(obj.getDouble("x"), obj.getDouble("y"))
            }
            StrokeJournal.TYPE_PAGE_ADDED -> inkFor(obj.getString("pageId"))
        }
    }

    /** 开始一笔。返回是否接受（暂停中拒绝）。 */
    fun beginStroke(tool: String, x: Double, y: Double, t: Long, pressure: Float): Boolean {
        if (paused) return false
        val ink = currentPage?.ink ?: return false
        if (eraserMode || tool == PageInk.TOOL_ERASER) {
            eraseAtCurrent(x, y)
            return true
        }
        ink.appendPoint(tool, x, y, t, pressure)
        return true
    }

    /** 移动采样（含历史点逐个调用）。 */
    fun moveStroke(tool: String, x: Double, y: Double, t: Long, pressure: Float) {
        if (paused) return
        val ink = currentPage?.ink ?: return
        if (eraserMode || tool == PageInk.TOOL_ERASER) {
            eraseAtCurrent(x, y)
            return
        }
        ink.appendPoint(tool, x, y, t, pressure)
    }

    /** 结束一笔：整笔点集写入 journal（§17.1 每完成一笔立即写入）。 */
    fun finishStroke(tool: String) {
        if (paused) return
        val page = currentPage ?: return
        if (tool == PageInk.TOOL_ERASER || eraserMode) return
        val last = page.ink.strokes.lastOrNull() ?: return
        if (last.points.size < 2) return
        val pts = last.points.joinToString(",", "[", "]") { p ->
            "[${p.x},${p.y},${p.t},${p.pressure}]"
        }
        journal?.append(
            StrokeJournal.TYPE_STROKE_COMMITTED,
            """{"pageId":"${page.pageId}","tool":"$tool","pts":$pts}""".toByteArray(),
        )
    }

    private fun eraseAtCurrent(x: Double, y: Double) {
        val ink = currentPage?.ink ?: return
        ink.eraseAt(x, y)
        journal?.append(
            StrokeJournal.TYPE_ERASE_POINT,
            """{"pageId":"${currentPageId}","x":$x,"y":$y}""".toByteArray(),
        )
    }

    suspend fun addPage(): String {
        val session = db.workBriefDao().sessionForBrief(briefId) ?: error("无场次")
        val pageId = UUID.randomUUID().toString()
        val index = db.briefCaptureDao().pageCount(session.id)
        db.briefCaptureDao().insertPage(
            CanvasPageEntity(
                id = pageId,
                sessionId = session.id,
                pageIndex = index,
                logicalWidth = CANVAS_WIDTH,
                logicalHeight = CANVAS_HEIGHT,
                backgroundType = "blank",
                backgroundRef = null,
                backgroundSha256 = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
        journal?.append(
            StrokeJournal.TYPE_PAGE_ADDED,
            """{"pageId":"$pageId","index":$index}""".toByteArray(),
        )
        pages[pageId] = PageUi(
            pageId,
            index,
            PageInk(pageId, CANVAS_WIDTH, CANVAS_HEIGHT) { l, t, r, b ->
                onInkChanged(l, t, r, b)
            },
        )
        currentPageId = pageId
        return pageId
    }

    fun switchPage(pageId: String) {
        if (pages.containsKey(pageId)) currentPageId = pageId
    }

    fun addMarker(type: UserMarkerType, note: String, pageId: String? = null, onDone: () -> Unit = {}) {
        scope.launch {
            runCatching {
                android.util.Log.w("BriefCapture", "addMarker briefId=$briefId session=${journal != null} pageId=$pageId")
                repository.addMarker(briefId, type, pageId ?: currentPageId ?: "", offsetMs(), note)
            }.onSuccess { onDone() }
                .onFailure { onError?.invoke("标记失败：${it.message}") ; android.util.Log.e("BriefCapture", "addMarker", it) }
        }
    }

    fun pause(onDone: () -> Unit = {}) {
        scope.launch {
            runCatching {
                repository.pauseCapture(briefId)
                journal?.checkpoint()
            }.onSuccess { paused = true; onDone() }
                .onFailure { onError?.invoke("暂停失败：${it.message}") ; android.util.Log.e("BriefCapture", "pause", it) }
        }
    }

    fun resume(onDone: () -> Unit = {}) {
        scope.launch {
            runCatching {
                repository.resumeCapture(briefId)
                journal?.checkpoint()
            }.onSuccess { paused = false; onDone() }
                .onFailure { onError?.invoke("恢复失败：${it.message}") ; android.util.Log.e("BriefCapture", "resume", it) }
        }
    }

    fun stop(onDone: () -> Unit = {}) {
        scope.launch {
            runCatching {
                repository.stopCapture(briefId)
                journal?.checkpoint()
            }.onSuccess { onDone() }
                .onFailure { onError?.invoke("结束失败：${it.message}") ; android.util.Log.e("BriefCapture", "stop", it) }
        }
    }

    fun offsetMs(): Long = System.currentTimeMillis() - sessionOriginWallClock

    /** 回放前清空所有页墨迹（不影响 journal 与 Room 数据）。 */
    fun resetInkForReplay() {
        pages.values.forEach { page ->
            page.ink.resetInk()
        }
    }

    /** 回放驱动：在指定页按采样点追加分段墨迹（记录顺序即绘制顺序）。 */
    fun replayAppend(pageId: String, tool: String, x: Double, y: Double, t: Long, pressure: Float) {
        val ink = inkFor(pageId)
        val previous = ink.lastPoint()
        ink.appendPoint(tool, x, y, t, pressure)
        if (previous == null) {
            // 新一笔首点：轻提脏区即可
        }
    }

    fun replayErase(pageId: String, x: Double, y: Double) {
        inkFor(pageId).eraseAt(x, y)
    }

    fun replaySwitchPage(pageId: String) {
        if (pages.containsKey(pageId)) currentPageId = pageId
    }

    companion object {
        const val CANVAS_WIDTH = 1680
        const val CANVAS_HEIGHT = 1264
    }
}
