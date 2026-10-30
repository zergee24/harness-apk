package com.harnessapk.chat

import java.io.IOException

/**
 * 一次回答的分段耗时。每个阶段结束调 [mark]，结束时 [summary] 输出一行：
 * `ChatTiming trace=… model=… context=12ms agent=340ms request=5ms firstEvent=2810ms firstText=4120ms total=9300ms`
 *
 * 用 `adb logcat -s ChatTiming` 抓取，定位「首字慢」到底慢在本地准备、检索还是模型首包。
 */
internal class ChatTimingTrace(
    private val traceId: String,
    private val clock: () -> Long = ::monotonicMillis,
) {
    private val startedAt = clock()
    private var lastMarkAt = startedAt
    private val segments = mutableListOf<Pair<String, Long>>()
    private val labels = linkedMapOf<String, String>()

    fun label(key: String, value: String?) {
        value?.takeIf(String::isNotBlank)?.let { labels[key] = it }
    }

    /** 记录自上一个 mark 以来的耗时。 */
    fun mark(segment: String) {
        val now = clock()
        segments += segment to (now - lastMarkAt)
        lastMarkAt = now
    }

    /** 记录自开始以来的绝对时刻（用于首包、首字这类里程碑）。 */
    fun milestone(name: String) {
        segments += name to (clock() - startedAt)
    }

    fun elapsedMillis(): Long = clock() - startedAt

    fun summary(outcome: String): String = buildString {
        append("trace=").append(traceId.take(8))
        labels.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        segments.forEach { (name, millis) -> append(' ').append(name).append('=').append(millis).append("ms") }
        append(" total=").append(elapsedMillis()).append("ms")
        append(" outcome=").append(outcome)
    }
}

internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

/**
 * 流已经开始输出后，超过阈值没有任何新事件：判定为卡流。
 * 继承 [IOException]，因此会走现有的网络中断自动重试（[isRetryableTransportFailure]）。
 */
class ChatStreamStalledException(idleMillis: Long) :
    IOException("回答中途卡住：${idleMillis / 1_000L} 秒没有收到新内容，已自动断开")

/** 首个事件之后允许的最长静默时间。 */
internal const val DEFAULT_STREAM_IDLE_TIMEOUT_MILLIS = 60_000L
