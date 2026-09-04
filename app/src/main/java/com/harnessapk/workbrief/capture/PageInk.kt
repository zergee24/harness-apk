package com.harnessapk.workbrief.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.hypot

/** 单点采样（逻辑坐标 + 压感 + 单调时间戳）。 */
data class InkPoint(val x: Double, val y: Double, val t: Long, val pressure: Float)

/** 一段墨迹（一笔 = 点序列；点级擦除会把一笔拆成多段）。 */
data class InkStroke(val tool: String, val points: MutableList<InkPoint> = mutableListOf())

/**
 * 每页墨水状态：笔迹列表 + 离屏位图（已提交墨迹）。
 * 触摸路径：begin → append(含历史点) → finish；擦除为点级（擦到哪删哪，缺口不连线）。
 */
class PageInk(
    val pageId: String,
    val width: Int,
    val height: Int,
    private val density: Float = 1.75f,
    private val onInkChanged: (dirtyLeft: Int, dirtyTop: Int, dirtyRight: Int, dirtyBottom: Int) -> Unit = { _, _, _, _ -> },
) {
    val strokes = mutableListOf<InkStroke>()
    var bitmap: Bitmap? = null
        private set
    private var bitmapCanvas: Canvas? = null

    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF1A1A1A.toInt()
    }
    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0xFF1A1A1A.toInt()
    }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    companion object {
        const val ERASE_RADIUS_PX = 24f
        const val ERASE_RUN_GAP_PX = 60f
        private const val MAX_STROKE_WIDTH_PX = 12f
        const val TOOL_STYLUS = "stylus"
        const val TOOL_FINGER = "finger"
        const val TOOL_ERASER = "eraser"

        fun strokeBBox(stroke: InkStroke): android.graphics.RectF {
            val pad = MAX_STROKE_WIDTH_PX + 4f
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            stroke.points.forEach { p ->
                left = minOf(left, p.x.toFloat()); right = maxOf(right, p.x.toFloat())
                top = minOf(top, p.y.toFloat()); bottom = maxOf(bottom, p.y.toFloat())
            }
            return android.graphics.RectF(left - pad, top - pad, right + pad, bottom + pad)
        }
    }

    fun ensureBitmap() {
        if (bitmap == null && width > 0 && height > 0) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmapCanvas = Canvas(bitmap!!)
        }
    }

    private fun strokeWidthFor(pressure: Float): Float =
        (1.2f + 4.8f * pressure.coerceIn(0f, 1f)) * density

    /** 追加采样点并把该段墨迹画进位图；返回脏区（逻辑坐标）。 */
    fun appendPoint(tool: String, x: Double, y: Double, t: Long, pressure: Float): InkPoint {
        ensureBitmap()
        val stroke = strokes.lastOrNull() ?: run {
            val s = InkStroke(tool, mutableListOf())
            strokes.add(s)
            s
        }
        val previous = stroke.points.lastOrNull()
        stroke.points.add(InkPoint(x, y, t, pressure))
        bitmapCanvas?.let { canvas ->
            if (previous == null) {
                dotPaint.strokeWidth = strokeWidthFor(pressure)
                canvas.drawPoint(x.toFloat(), y.toFloat(), dotPaint)
            } else {
                strokePaint.strokeWidth = strokeWidthFor((previous.pressure + pressure) / 2f)
                canvas.drawLine(previous.x.toFloat(), previous.y.toFloat(), x.toFloat(), y.toFloat(), strokePaint)
            }
        }
        val pad = MAX_STROKE_WIDTH_PX + 8f
        onInkChanged(
            (x - pad).toInt().coerceAtLeast(0),
            (y - pad).toInt().coerceAtLeast(0),
            (x + pad).toInt().coerceAtMost(width),
            (y + pad).toInt().coerceAtMost(height),
        )
        return stroke.points.last()
    }

    /** 点级擦除：删半径内采样点并拆段；对位图做包围盒局部重绘。返回是否有变化。 */
    fun eraseAt(x: Double, y: Double): Boolean {
        ensureBitmap()
        val bmp = bitmap ?: return false
        var changed = false
        val dirty = android.graphics.RectF()
        var index = 0
        while (index < strokes.size) {
            val stroke = strokes[index]
            val hit = stroke.points.any { p ->
                hypot((p.x - x).toFloat(), (p.y - y).toFloat()) <= ERASE_RADIUS_PX
            }
            if (!hit) {
                index++
                continue
            }
            val bbox = Companion.strokeBBox(stroke)
            dirty.union(bbox)
            val survivors = stroke.points.filter { p ->
                hypot((p.x - x).toFloat(), (p.y - y).toFloat()) > ERASE_RADIUS_PX
            }
            val fragments = splitRuns(survivors, stroke.tool)
            strokes.removeAt(index)
            fragments.forEachIndexed { i, fragment ->
                strokes.add(index + i, fragment)
            }
            index += fragments.size
            changed = true
        }
        if (changed) {
            bitmapCanvas?.let { canvas ->
                canvas.save()
                canvas.clipRect(dirty)
                canvas.drawRect(dirty, clearPaint)
                strokes.forEach { stroke ->
                    if (Companion.strokeBBox(stroke).intersect(dirty)) drawStrokeInto(canvas, stroke)
                }
                canvas.restore()
            }
            onInkChanged(
                dirty.left.toInt().coerceAtLeast(0),
                dirty.top.toInt().coerceAtLeast(0),
                dirty.right.toInt().coerceAtMost(width),
                dirty.bottom.toInt().coerceAtMost(height),
            )
        }
        return changed
    }

    private fun splitRuns(points: List<InkPoint>, tool: String): List<InkStroke> {
        if (points.isEmpty()) return emptyList()
        val fragments = mutableListOf<InkStroke>()
        var run = mutableListOf(points.first())
        points.drop(1).forEach { p ->
            val previous = run.last()
            if (hypot((p.x - previous.x).toFloat(), (p.y - previous.y).toFloat()) > ERASE_RUN_GAP_PX) {
                fragments.add(InkStroke(tool).also { it.points.addAll(run) })
                run = mutableListOf()
            }
            run.add(p)
        }
        fragments.add(InkStroke(tool).also { it.points.addAll(run) })
        return fragments
    }

    /** 把整条笔画画进位图（重放/恢复路径用；触摸路径是逐段增量）。 */
    fun drawStrokeInto(canvas: Canvas, stroke: InkStroke) {
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

    fun drawAllOnto(canvas: Canvas) {
        ensureBitmap()
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }
}
