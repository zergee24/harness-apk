package com.harnessapk.spike.canvas

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.harnessapk.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spike-画布 可行性试点（2026-09-04，HiBreak 电纸书首发）。
 *
 * 独立 debug prototype，不进正式信息架构；release 构建直接 finish。
 * 能力矩阵、压感变宽墨迹、点级橡皮（擦到哪删哪）、JSONL 追加日志 + 强杀恢复。
 *
 * e-ink 跟笔优化：已提交笔画进离屏位图，触摸只重绘笔尖矩形（局部快速刷新）；
 * 统计条走 TextView，不参与画布重绘。
 */
class SpikeCanvasActivity : Activity() {

    private lateinit var canvasView: SpikeCanvasView
    private lateinit var statsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        canvasView = SpikeCanvasView(this)
        statsText = TextView(this).apply {
            setPadding(24, 20, 24, 8)
            setTextColor(0xFF555555.toInt())
            textSize = 14f
        }
        canvasView.onStats = { summary ->
            runOnUiThread { statsText.text = summary }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.END
        }
        controls.addView(Button(this).apply {
            text = "清除"
            setOnClickListener { canvasView.clearAll() }
        })
        controls.addView(Button(this).apply {
            text = "能力矩阵"
            setOnClickListener {
                Toast.makeText(this@SpikeCanvasActivity, canvasView.capabilityReport(), Toast.LENGTH_LONG).show()
            }
        })
        controls.addView(Button(this).apply {
            text = "橡皮：关"
            setOnClickListener {
                canvasView.eraserMode = !canvasView.eraserMode
                text = "橡皮：${if (canvasView.eraserMode) "开" else "关"}"
            }
        })
        controls.addView(Button(this).apply {
            text = "手指模式：关"
            setOnClickListener {
                canvasView.fingerMode = !canvasView.fingerMode
                text = "手指模式：${if (canvasView.fingerMode) "开" else "关"}"
            }
        })

        val root = FrameLayout(this)
        root.addView(canvasView)
        root.addView(statsText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(0, 0, 12, 24) },
        )
        setContentView(root)
    }
}

private class SpikeStroke(val tool: String) {
    val points = mutableListOf<SpikePoint>()

    fun toLine(): String {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(
                JSONArray()
                    .put(Math.round(p.x * 10) / 10.0)
                    .put(Math.round(p.y * 10) / 10.0)
                    .put(p.t)
                    .put(Math.round(p.pressure * 1000) / 1000.0),
            )
        }
        return JSONObject()
            .put("kind", "stroke")
            .put("tool", tool)
            .put("pts", arr)
            .toString()
    }

    companion object {
        fun fromLine(line: String): SpikeStroke? = runCatching {
            val obj = JSONObject(line)
            if (obj.optString("kind") != "stroke") return null
            val stroke = SpikeStroke(obj.optString("tool", "unknown"))
            val pts = obj.optJSONArray("pts") ?: return null
            for (i in 0 until pts.length()) {
                val p = pts.getJSONArray(i)
                stroke.points.add(SpikePoint(p.getDouble(0), p.getDouble(1), p.getLong(2), p.getDouble(3).toFloat()))
            }
            if (stroke.points.isEmpty()) null else stroke
        }.getOrNull()
    }
}

private class SpikePoint(val x: Double, val y: Double, val t: Long, val pressure: Float)

private class SpikeStrokeLog(context: Context) {
    private val file = File(context.getExternalFilesDir(null)?.apply { mkdirs() }, "spike_canvas/strokes.jsonl")
    private var writer: PrintWriter? = null

    /** 按日志顺序重放历史：笔画、擦除点、清屏。 */
    fun replay(onStroke: (SpikeStroke) -> Unit, onErase: (Float, Float) -> Unit, onClear: () -> Unit) {
        if (!file.exists()) return
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                when (obj.optString("kind")) {
                    "stroke" -> SpikeStroke.fromLine(line)?.let(onStroke)
                    "erase" -> onErase(
                        obj.optDouble("x", 0.0).toFloat(),
                        obj.optDouble("y", 0.0).toFloat(),
                    )
                    "cleared" -> onClear()
                }
            }
        }
    }

    fun appendStroke(stroke: SpikeStroke) {
        writer().println(stroke.toLine())
        writer()?.flush()
    }

    fun appendErase(x: Float, y: Float) {
        writer().println(JSONObject().put("kind", "erase").put("x", x.toDouble()).put("y", y.toDouble()).toString())
        writer()?.flush()
    }

    fun appendMarker(kind: String) {
        writer().println(JSONObject().put("kind", kind).toString())
        writer()?.flush()
    }

    fun resetFile() {
        writer?.close()
        writer = null
        file.delete()
    }

    private fun writer(): PrintWriter {
        writer?.let { return it }
        file.parentFile?.mkdirs()
        val w = PrintWriter(FileWriter(file, true), true)
        writer = w
        return w
    }

    fun close() {
        writer?.close()
        writer = null
    }
}

private class SpikeCanvasView(context: Context) : View(context) {

    var fingerMode = false
    var eraserMode = false
    var onStats: ((String) -> Unit)? = null

    private val policy = SpikeInputPolicy()
    private val stats = SpikeStats()
    private val log = SpikeStrokeLog(context)
    private val strokes = mutableListOf<SpikeStroke>()
    private var current: SpikeStroke? = null
    private var stylusLastSeenAtMs: Long? = null
    private var eraserButtonDown = false
    private var deviceSummary: String = ""

    // 已提交墨迹的离屏缓存：触摸只重绘笔尖矩形，e-ink 才能走局部快速刷新。
    private var committed: Bitmap? = null
    private var committedCanvas: Canvas? = null

    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    init {
        setBackgroundColor(0xFFF6F3EE.toInt())
        log.replay(onStroke = strokes::add, onErase = ::applyEraseAt, onClear = { strokes.clear() })
        deviceSummary = describeInputDevices()
        setOnTouchListener { _, event ->
            onTouchEvent(event)
            true
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        committed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        committedCanvas = Canvas(committed!!)
        strokes.forEach { drawStrokeInto(committedCanvas!!, it) }
        invalidate()
    }

    fun clearAll() {
        strokes.clear()
        committed?.eraseColor(Color.TRANSPARENT)
        log.resetFile()
        log.appendMarker("cleared")
        invalidate()
    }

    fun capabilityReport(): String {
        val line1 = "型号 ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val line2 = "压感 ${stats.pressureRangeText()} · tilt=${stats.tiltSeen} · 工具=${stats.toolsSeen}"
        val line3 = "延迟 p50=${stats.percentile(0.5)}ms p95=${stats.percentile(0.95)}ms"
        val line4 = "笔画 ${stats.strokes} · 点 ${stats.points} · 掌触拒绝 ${stats.palmRejected}"
        return listOf(line1, line2, line3, line4, deviceSummary).joinToString("\n")
    }

    private fun describeInputDevices(): String = buildString {
        InputDevice.getDeviceIds().forEach { id ->
            val device = InputDevice.getDevice(id) ?: return@forEach
            val sources = device.sources
            val isStylus = sources and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS
            val isTouch = sources and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN
            if (isStylus || isTouch) {
                val ranges = device.motionRanges.joinToString(";") { range ->
                    "${axisName(range.axis)}:[${range.min}~${range.max}]"
                }
                append("设备「${device.name}」touch=$isTouch stylus=$isStylus $ranges\n")
            }
        }
        if (isEmpty()) append("未发现触摸/手写输入设备\n")
    }

    private fun axisName(axis: Int): String = when (axis) {
        MotionEvent.AXIS_X -> "X"
        MotionEvent.AXIS_Y -> "Y"
        MotionEvent.AXIS_PRESSURE -> "PRESSURE"
        MotionEvent.AXIS_TILT -> "TILT"
        MotionEvent.AXIS_SIZE -> "SIZE"
        else -> "axis$axis"
    }

    private fun toolName(event: MotionEvent): String = when {
        event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER -> SpikeInputPolicy.TOOL_ERASER
        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS -> SpikeInputPolicy.TOOL_STYLUS
        event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER -> SpikeInputPolicy.TOOL_FINGER
        else -> SpikeInputPolicy.TOOL_UNKNOWN
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tool = toolName(event)
        if (tool == SpikeInputPolicy.TOOL_STYLUS || tool == SpikeInputPolicy.TOOL_ERASER) {
            stylusLastSeenAtMs = SystemClock.uptimeMillis()
        }
        eraserButtonDown = event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 ||
            event.buttonState and MotionEvent.BUTTON_SECONDARY != 0

        stats.addLatency(maxOf(0, SystemClock.uptimeMillis() - event.eventTime))
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT) != 0f

        var dirtyLeft = -1
        var dirtyTop = -1
        var dirtyRight = -1
        var dirtyBottom = -1
        fun markDirty(x: Float, y: Float) {
            val pad = MAX_STROKE_WIDTH_PX + 8f
            dirtyLeft = if (dirtyLeft < 0) (x - pad).toInt() else min(dirtyLeft, (x - pad).toInt())
            dirtyTop = if (dirtyTop < 0) (y - pad).toInt() else min(dirtyTop, (y - pad).toInt())
            dirtyRight = max(dirtyRight, (x + pad).toInt())
            dirtyBottom = max(dirtyBottom, (y + pad).toInt())
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val verdict = policy.decide(
                    tool, stylusLastSeenAtMs, SystemClock.uptimeMillis(),
                    fingerMode, eraserButtonDown, eraserMode,
                )
                when (verdict) {
                    SpikeInputVerdict.DRAW -> {
                        current = SpikeStroke(tool)
                        appendPoint(event, -1)?.let { p -> markDirty(p.x.toFloat(), p.y.toFloat()) }
                    }
                    SpikeInputVerdict.ERASE -> {
                        current = SpikeStroke(SpikeInputPolicy.TOOL_ERASER)
                        appendPoint(event, -1)?.let { p -> markDirty(p.x.toFloat(), p.y.toFloat()) }
                    }
                    SpikeInputVerdict.REJECT_PALM -> stats.addPalmRejected()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    appendPoint(event, h)?.let { p -> markDirty(p.x.toFloat(), p.y.toFloat()) }
                }
                appendPoint(event, -1)?.let { p -> markDirty(p.x.toFloat(), p.y.toFloat()) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { stroke ->
                    if (stroke.tool != SpikeInputPolicy.TOOL_ERASER && stroke.points.size >= 2) {
                        strokes.add(stroke)
                        stats.addStroke()
                        log.appendStroke(stroke)
                    }
                }
                current = null
            }
        }

        if (dirtyLeft >= 0) {
            invalidate(
                max(0, dirtyLeft), max(0, dirtyTop),
                min(width, dirtyRight), min(height, dirtyBottom),
            )
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastStatsRefreshAtMs > 500) {
            lastStatsRefreshAtMs = now
            onStats?.invoke(summaryText())
        }
        return true
    }

    private var lastStatsRefreshAtMs = 0L

    private fun summaryText(): String =
        "p50=${stats.percentile(0.5)}ms p95=${stats.percentile(0.95)}ms " +
            "笔${stats.strokes} 点${stats.points} 拒掌${stats.palmRejected} " +
            "压感${stats.pressureRangeText()} tilt=${if (stats.tiltSeen) "有" else "无"}"

    /** 采一个点：追进当前笔画；压感画进位图；橡皮点同时做点级擦除。返回该点。 */
    private fun appendPoint(event: MotionEvent, historyIndex: Int): SpikePoint? {
        val stroke = current ?: return null
        // historyIndex = -1 表示当前采样；>=0 时必须落在 historySize 内，否则 MotionEvent 会抛
        // IllegalArgumentException（DOWN 事件没有任何历史点）。
        if (historyIndex >= event.historySize) return null
        val x: Float
        val y: Float
        val t: Long
        val pressure: Float
        if (historyIndex >= 0) {
            x = event.getHistoricalX(historyIndex)
            y = event.getHistoricalY(historyIndex)
            t = event.getHistoricalEventTime(historyIndex)
            pressure = event.getHistoricalPressure(historyIndex)
        } else {
            x = event.x
            y = event.y
            t = event.eventTime
            pressure = event.pressure
        }
        val point = SpikePoint(x.toDouble(), y.toDouble(), t, pressure)
        val canvas = committedCanvas
        // 橡皮点不落墨——它的职责是触发 applyEraseAt 的位图修复。
        if (canvas != null && stroke.tool != SpikeInputPolicy.TOOL_ERASER) {
            val previous = stroke.points.lastOrNull()
            if (previous == null) {
                dotPaint.strokeWidth = strokeWidthFor(pressure)
                canvas.drawPoint(x, y, dotPaint)
            } else {
                strokePaint.strokeWidth = strokeWidthFor((previous.pressure + pressure) / 2f)
                canvas.drawLine(previous.x.toFloat(), previous.y.toFloat(), x, y, strokePaint)
            }
        }
        stroke.points.add(point)
        stats.addPoint(pressure, event.getAxisValue(MotionEvent.AXIS_TILT) != 0f, stroke.tool)
        if (stroke.tool == SpikeInputPolicy.TOOL_ERASER) {
            applyEraseAt(x, y)
            log.appendErase(x, y)
        }
        return point
    }

    /** 点级擦除：只删橡皮半径内的采样点，笔画被分成多段；受影响区域在位图上局部重绘。 */
    private fun applyEraseAt(x: Float, y: Float) {
        val bitmap = committed ?: return
        val canvas = committedCanvas ?: return
        var index = 0
        while (index < strokes.size) {
            val stroke = strokes[index]
            if (stroke.points.none { p ->
                    hypot((p.x - x).toFloat(), (p.y - y).toFloat()) <= ERASE_RADIUS_PX
                }
            ) {
                index++
                continue
            }
            val bbox = strokeBBox(stroke)
            // 清掉该笔画包围盒内的旧墨，再把穿过这个区域的所有笔画（含本笔画的幸存段）补画回来
            canvas.save()
            canvas.clipRect(bbox)
            canvas.drawRect(bbox, clearPaint)
            strokes.forEach { other ->
                if (other !== stroke && bboxesIntersect(strokeBBox(other), bbox)) {
                    drawStrokeInto(canvas, other)
                }
            }
            canvas.restore()

            val survivors = stroke.points.filter { p ->
                hypot((p.x - x).toFloat(), (p.y - y).toFloat()) > ERASE_RADIUS_PX
            }
            val fragments = splitRuns(survivors, stroke.tool)
            canvas.save()
            canvas.clipRect(bbox)
            fragments.forEach { drawStrokeInto(canvas, it) }
            canvas.restore()

            strokes.removeAt(index)
            fragments.forEachIndexed { i, fragment ->
                strokes.add(index + i, fragment)
            }
            index += fragments.size
            invalidate(
                (bbox.left - 2).toInt().coerceAtLeast(0),
                (bbox.top - 2).toInt().coerceAtLeast(0),
                (bbox.right + 2).toInt().coerceAtMost(width),
                (bbox.bottom + 2).toInt().coerceAtMost(height),
            )
        }
    }

    private fun strokeBBox(stroke: SpikeStroke): android.graphics.RectF {
        val pad = MAX_STROKE_WIDTH_PX + 4f
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        stroke.points.forEach { p ->
            left = min(left, p.x.toFloat()); right = max(right, p.x.toFloat())
            top = min(top, p.y.toFloat()); bottom = max(bottom, p.y.toFloat())
        }
        return android.graphics.RectF(left - pad, top - pad, right + pad, bottom + pad)
    }

    private fun bboxesIntersect(a: android.graphics.RectF, b: android.graphics.RectF): Boolean =
        a.left <= b.right && a.right >= b.left && a.top <= b.bottom && a.bottom >= b.top

    private fun splitRuns(points: List<SpikePoint>, tool: String): List<SpikeStroke> {
        if (points.isEmpty()) return emptyList()
        val fragments = mutableListOf<SpikeStroke>()
        var run = mutableListOf(points.first())
        points.drop(1).forEach { p ->
            val previous = run.last()
            // 被擦掉的缺口不连线：相邻幸存点距离超过阈值即另起一段。
            if (hypot((p.x - previous.x).toFloat(), (p.y - previous.y).toFloat()) > ERASE_RUN_GAP_PX) {
                fragments.add(SpikeStroke(tool).also { it.points.addAll(run) })
                run = mutableListOf()
            }
            run.add(p)
        }
        fragments.add(SpikeStroke(tool).also { it.points.addAll(run) })
        return fragments
    }

    /** 把整条笔画画进位图（恢复/重建时用；触摸路径是逐段增量画）。 */
    private fun drawStrokeInto(canvas: Canvas, stroke: SpikeStroke) {
        if (stroke.points.isEmpty()) return
        if (stroke.points.size < 2) {
            val p = stroke.points[0]
            dotPaint.strokeWidth = strokeWidthFor(p.pressure)
            canvas.drawPoint(p.x.toFloat(), p.y.toFloat(), dotPaint)
            return
        }
        for (i in 1 until stroke.points.size) {
            val a = stroke.points[i - 1]
            val b = stroke.points[i]
            strokePaint.strokeWidth = strokeWidthFor((a.pressure + b.pressure) / 2f)
            canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), strokePaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        committed?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun strokeWidthFor(pressure: Float): Float {
        val density = resources.displayMetrics.density
        return (1.2f + 4.8f * pressure.coerceIn(0f, 1f)) * density
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        log.close()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val ERASE_RADIUS_PX = 24f
        private const val ERASE_RUN_GAP_PX = 60f
        private const val MAX_STROKE_WIDTH_PX = 12f
    }
}
