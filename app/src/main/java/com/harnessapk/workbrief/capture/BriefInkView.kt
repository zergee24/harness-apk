package com.harnessapk.workbrief.capture

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.harnessapk.spike.canvas.SpikeInputPolicy
import com.harnessapk.spike.canvas.SpikeInputVerdict

/**
 * P1 记录页墨水视图：触摸事件驱动 [controller]，绘制来自 PageInk 的离屏位图。
 * e-ink 局部刷新：只对墨迹脏区做 invalidate。
 */
class BriefInkView(
    context: Context,
    private val controllerProvider: () -> BriefCaptureController?,
    private val dirtyInvalidate: (left: Int, top: Int, right: Int, bottom: Int) -> Unit,
) : View(context) {

    init {
        setBackgroundColor(0xFFF6F3EE.toInt())
    }

    var fingerMode = false

    private val policy = SpikeInputPolicy()
    private var stylusLastSeenAtMs: Long? = null
    private var activeTool = ""

    private fun toolName(event: MotionEvent): String = when {
        event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER -> PageInk.TOOL_ERASER
        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS -> PageInk.TOOL_STYLUS
        event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER -> PageInk.TOOL_FINGER
        else -> "unknown"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val controller = controllerProvider() ?: return true
        val tool = toolName(event)
        if (tool == PageInk.TOOL_STYLUS || tool == PageInk.TOOL_ERASER) {
            stylusLastSeenAtMs = System.currentTimeMillis()
        }
        val eraserButton = event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
        val verdict = policy.decide(
            tool, stylusLastSeenAtMs, System.currentTimeMillis(),
            fingerMode, eraserButton, controller.eraserMode,
        )

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                activeTool = if (verdict == SpikeInputVerdict.ERASE) PageInk.TOOL_ERASER else tool
                when (verdict) {
                    SpikeInputVerdict.DRAW, SpikeInputVerdict.ERASE -> {
                        controller.beginStroke(activeTool, event.x.toDouble(), event.y.toDouble(), event.eventTime, event.pressure)
                        feedMove(event)
                    }
                    SpikeInputVerdict.REJECT_PALM -> Unit
                }
            }
            MotionEvent.ACTION_MOVE -> {
                feedMove(event)
                if (verdict == SpikeInputVerdict.ERASE || activeTool == PageInk.TOOL_ERASER) {
                    dirtyInvalidate(0, 0, width, height)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                controller.finishStroke(activeTool)
                strokeCleanup()
            }
        }
        return true
    }

    private fun strokeCleanup() {
        activeTool = ""
    }

    private fun feedMove(event: MotionEvent) {
        val controller = controllerProvider() ?: return
        for (h in 0 until event.historySize) {
            controller.moveStroke(
                activeTool,
                event.getHistoricalX(h).toDouble(),
                event.getHistoricalY(h).toDouble(),
                event.getHistoricalEventTime(h),
                event.getHistoricalPressure(h),
            )
        }
        controller.moveStroke(
            activeTool,
            event.x.toDouble(),
            event.y.toDouble(),
            event.eventTime,
            event.pressure,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        controllerProvider()?.currentPage?.ink?.drawAllOnto(canvas)
    }
}
