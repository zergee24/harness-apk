package com.harnessapk.spike.canvas

import android.app.Activity
import android.content.Context
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
import android.widget.Toast
import com.harnessapk.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Spike-画布 可行性试点（2026-09-04，HiBreak 电纸书首发）。
 *
 * 独立 debug prototype，不进正式信息架构；release 构建直接 finish。
 * 四件事：①手写笔/手指能力矩阵；②墨水预览 + 事件→绘制延迟统计；
 * ③掌触裁决（见过手写笔后手指默认拒绝）；④JSONL 追加日志 + 强杀恢复。
 */
class SpikeCanvasActivity : Activity() {

    private lateinit var canvasView: SpikeCanvasView
    private var fingerModeButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        canvasView = SpikeCanvasView(this)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.END
        }
        fingerModeButton = Button(this).apply {
            text = "手指模式：关"
            setOnClickListener {
                canvasView.fingerMode = !canvasView.fingerMode
                text = "手指模式：${if (canvasView.fingerMode) "开" else "关"}"
            }
        }
        controls.addView(Button(this).apply {
            text = "清除"
            setOnClickListener { canvasView.clearAll() }
        })
        controls.addView(Button(this).apply {
            text = "能力矩阵"
            setOnClickListener {
                Toast.makeText(context, canvasView.capabilityReport(), Toast.LENGTH_LONG).show()
            }
        })
        controls.addView(fingerModeButton)

        val root = FrameLayout(this)
        root.addView(canvasView)
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

    fun loadExisting(): List<SpikeStroke> {
        if (!file.exists()) return emptyList()
        val strokes = mutableListOf<SpikeStroke>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                SpikeStroke.fromLine(line)?.let { strokes.add(it) }
            }
        }
        return strokes
    }

    fun appendStroke(stroke: SpikeStroke) {
        writer().println(stroke.toLine())
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

    private val policy = SpikeInputPolicy()
    private val stats = SpikeStats()
    private val log = SpikeStrokeLog(context)
    private val strokes = mutableListOf<SpikeStroke>()
    private var current: SpikeStroke? = null
    private var stylusLastSeenAtMs: Long? = null
    private var eraserButtonDown = false
    private var lastStatsRefreshAtMs = 0L
    private var deviceSummary: String = ""

    private val strokePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeWidth = 2.5f
        color = 0xFF1A1A1A.toInt()
    }
    private val eraserPaint = android.graphics.Paint(strokePaint).apply {
        color = 0xFFC2483C.toInt()
        strokeWidth = 8f
    }
    private val rejectedPaint = android.graphics.Paint(strokePaint).apply {
        color = 0xFF9A9AA0.toInt()
        strokeWidth = 1.5f
    }
    private val statsPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = 0xFF44444488.toInt()
        textSize = 30f
    }
    private val rejectedStrokes = mutableListOf<SpikeStroke>()

    init {
        setBackgroundColor(0xFFF6F3EE.toInt())
        strokes.addAll(log.loadExisting())
        deviceSummary = describeInputDevices()
        setOnTouchListener { _, event ->
            onTouchEvent(event)
            true
        }
    }

    fun clearAll() {
        strokes.clear()
        rejectedStrokes.clear()
        log.resetFile()
        log.appendMarker("cleared")
        invalidate()
    }

    fun capabilityReport(): String {
        val line1 = "型号 ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val line2 = "压感 ${stats.pressureRangeText()} · tilt=${stats.tiltSeen} · 工具=$stats.toolsSeen"
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

        stats.addLatency(max(0, SystemClock.uptimeMillis() - event.eventTime))
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT) != 0f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val verdict = policy.decide(tool, stylusLastSeenAtMs, SystemClock.uptimeMillis(), fingerMode, eraserButtonDown)
                when (verdict) {
                    SpikeInputVerdict.DRAW -> {
                        current = SpikeStroke(tool)
                        appendPoint(event, -1)
                    }
                    SpikeInputVerdict.ERASE -> current = SpikeStroke(SpikeInputPolicy.TOOL_ERASER)
                    SpikeInputVerdict.REJECT_PALM -> stats.addPalmRejected()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = current
                if (stroke != null && stroke.tool != SpikeInputPolicy.TOOL_ERASER) {
                    for (h in 0 until event.historySize) {
                        appendPoint(event, h)
                    }
                    appendPoint(event, -1)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { stroke ->
                    if (stroke.points.size >= 2) {
                        strokes.add(stroke)
                        stats.addStroke()
                        log.appendStroke(stroke)
                    }
                }
                current = null
            }
        }
        refreshStatsThrottled()
        invalidate()
        return true
    }

    private fun appendPoint(event: MotionEvent, historyIndex: Int) {
        val stroke = current ?: return
        // historyIndex = -1 表示当前采样；>=0 时必须落在 historySize 内，否则 MotionEvent 会抛
        // IllegalArgumentException（DOWN 事件没有任何历史点）。
        if (historyIndex >= event.historySize) return
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
        stroke.points.add(SpikePoint(x.toDouble(), y.toDouble(), t, pressure))
        stats.addPoint(pressure, event.getAxisValue(MotionEvent.AXIS_TILT) != 0f, stroke.tool)
    }

    private fun refreshStatsThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastStatsRefreshAtMs > 500) {
            lastStatsRefreshAtMs = now
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        (strokes + rejectedStrokes).forEach { stroke ->
            if (stroke.points.size < 2) return@forEach
            val path = android.graphics.Path()
            path.moveTo(stroke.points[0].x.toFloat(), stroke.points[0].y.toFloat())
            stroke.points.drop(1).forEach { p -> path.lineTo(p.x.toFloat(), p.y.toFloat()) }
            canvas.drawPath(path, strokePaint)
        }
        current?.let { stroke ->
            if (stroke.points.size >= 2) {
                val path = android.graphics.Path()
                path.moveTo(stroke.points[0].x.toFloat(), stroke.points[0].y.toFloat())
                stroke.points.drop(1).forEach { p -> path.lineTo(p.x.toFloat(), p.y.toFloat()) }
                canvas.drawPath(
                    path,
                    if (stroke.tool == SpikeInputPolicy.TOOL_ERASER) eraserPaint else strokePaint,
                )
            }
        }
        val summary = "p50=${stats.percentile(0.5)}ms p95=${stats.percentile(0.95)}ms " +
            "笔${stats.strokes} 点${stats.points} 拒掌${stats.palmRejected} " +
            "压感${stats.pressureRangeText()} tilt=${if (stats.tiltSeen) "有" else "无"}"
        canvas.drawText(summary, 24f, 56f, statsPaint)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        log.close()
        super.onDetachedFromWindow()
    }
}
