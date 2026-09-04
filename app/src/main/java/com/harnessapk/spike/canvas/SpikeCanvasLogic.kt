package com.harnessapk.spike.canvas

/**
 * Spike-画布 输入裁决（纯逻辑，JVM 可测）。
 *
 * 规则：手写笔永远绘制；橡皮工具进入擦除；手指在"手指模式"开启、或从未见过手写笔时
 * 才允许绘制，否则视为掌触拒绝（近 15s 内见过手写笔即算"最近见过"）。
 */
enum class SpikeInputVerdict { DRAW, ERASE, REJECT_PALM }

class SpikeInputPolicy(
    private val stylusRecentMs: Long = 15_000L,
) {

    fun decide(
        tool: String,
        stylusLastSeenAtMs: Long?,
        nowMs: Long,
        fingerMode: Boolean,
        eraserActive: Boolean,
    ): SpikeInputVerdict {
        val stylusRecent = stylusLastSeenAtMs != null && nowMs - stylusLastSeenAtMs <= stylusRecentMs
        return when {
            tool == TOOL_ERASER || eraserActive -> SpikeInputVerdict.ERASE
            tool == TOOL_STYLUS -> SpikeInputVerdict.DRAW
            tool == TOOL_FINGER && fingerMode -> SpikeInputVerdict.DRAW
            tool == TOOL_FINGER && !stylusRecent -> SpikeInputVerdict.DRAW
            else -> SpikeInputVerdict.REJECT_PALM
        }
    }

    companion object {
        const val TOOL_STYLUS = "stylus"
        const val TOOL_FINGER = "finger"
        const val TOOL_ERASER = "eraser"
        const val TOOL_UNKNOWN = "unknown"
    }
}

/**
 * 延迟与计数统计（纯逻辑，JVM 可测）。latency 记录的是"事件时间戳 → 绘制派发"的
 * 管道延迟，不含 e-ink 面板物理刷新时间——面板刷新要在报告里单独说明。
 */
class SpikeStats {
    private val latencies = ArrayDeque<Long>()
    var strokes = 0
        private set
    var points = 0
        private set
    var palmRejected = 0
        private set
    var pressureMin = Float.MAX_VALUE
        private set
    var pressureMax = 0f
        private set
    var tiltSeen = false
        private set
    val toolsSeen = linkedSetOf<String>()

    fun addLatency(ms: Long) {
        latencies.addLast(ms)
        if (latencies.size > 400) latencies.removeFirst()
    }

    fun addStroke() {
        strokes++
    }

    fun addPoint(pressure: Float, tilt: Boolean, tool: String) {
        points++
        if (pressure in 0f..1f) {
            if (pressure < pressureMin) pressureMin = pressure
            if (pressure > pressureMax) pressureMax = pressure
        }
        if (tilt) tiltSeen = true
        toolsSeen.add(tool)
    }

    fun addPalmRejected() {
        palmRejected++
    }

    fun percentile(fraction: Double): Long {
        if (latencies.isEmpty()) return -1
        val sorted = latencies.sorted()
        val index = (fraction * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    fun pressureRangeText(): String =
        if (pressureMax == 0f) "未采集" else "%.2f ~ %.2f".format(pressureMin, pressureMax)
}
