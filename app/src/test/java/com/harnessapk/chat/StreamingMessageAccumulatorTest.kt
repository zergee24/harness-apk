package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMessageAccumulatorTest {
    @Test
    fun displaySnapshotHidesCompleteAndTrailingPartialWikiTokensWithoutChangingPersistedSnapshot() {
        val accumulator = StreamingMessageAccumulator()
        accumulator.onEvent(StreamEvent.TextDelta("正文⟦W1"), nowMillis = 0L)

        val partial = accumulator.snapshot()
        assertEquals("正文⟦W1", partial.parts.single().content)
        assertEquals("正文", partial.hideWikiCitationTokensForDisplay().parts.single().content)

        accumulator.onEvent(StreamEvent.TextDelta("⟧继续"), nowMillis = 10L)
        val complete = accumulator.snapshot()
        assertEquals("正文⟦W1⟧继续", complete.parts.single().content)
        assertEquals("正文继续", complete.hideWikiCitationTokensForDisplay().parts.single().content)
    }

    @Test
    fun firstTextDeltaFlushesImmediatelySoUserSeesProgress() {
        val accumulator = StreamingMessageAccumulator(
            flushIntervalMillis = 300L,
            maxBufferedChars = 320,
        )

        val flush = accumulator.onEvent(StreamEvent.TextDelta("你"), nowMillis = 0L)

        val snapshot = flush!!.snapshot
        assertEquals(StreamingFlushReason.FIRST_DELTA, flush.reason)
        assertEquals(MessageStatus.STREAMING, snapshot.status)
        assertEquals(1, snapshot.parts.size)
        assertEquals(UiMessagePartType.TEXT, snapshot.parts.single().type)
        assertEquals("你", snapshot.parts.single().content)
        assertFalse(snapshot.parts.single().stable)
    }

    @Test
    fun completeMarkdownImageProducesImagePartAndPreservesSurroundingText() {
        val accumulator = StreamingMessageAccumulator()

        accumulator.onEvent(
            StreamEvent.TextDelta("图片如下：![日落](https://cdn.example.com/sunset.png)谢谢。"),
            nowMillis = 0L,
        )

        val parts = accumulator.snapshot().parts
        assertEquals(
            listOf(UiMessagePartType.TEXT, UiMessagePartType.IMAGE, UiMessagePartType.TEXT),
            parts.map { it.type },
        )
        assertEquals("图片如下：", parts[0].content)
        assertEquals("https://cdn.example.com/sunset.png", parts[1].content)
        assertEquals("谢谢。", parts[2].content)
    }

    @Test
    fun partialMarkdownImageWaitsForClosingParenthesisBeforeCreatingImagePart() {
        val accumulator = StreamingMessageAccumulator()

        accumulator.onEvent(
            StreamEvent.TextDelta("图片如下：![日落](https://cdn.example.com/sun"),
            nowMillis = 0L,
        )

        assertEquals(
            listOf(UiMessagePartType.TEXT),
            accumulator.snapshot().parts.map { it.type },
        )
        assertEquals("图片如下：", accumulator.snapshot().parts.single().content)

        accumulator.onEvent(StreamEvent.TextDelta("set.png)谢谢。"), nowMillis = 10L)

        val parts = accumulator.snapshot().parts
        assertEquals(
            listOf(UiMessagePartType.TEXT, UiMessagePartType.IMAGE, UiMessagePartType.TEXT),
            parts.map { it.type },
        )
        assertEquals("https://cdn.example.com/sunset.png", parts[1].content)
        assertTrue(parts[1].stable)
    }

    @Test
    fun usageEventDoesNotBreakIncompleteMarkdownImage() {
        val accumulator = StreamingMessageAccumulator()

        accumulator.onEvent(
            StreamEvent.TextDelta("![日落](https://cdn.example.com/sunset"),
            nowMillis = 0L,
        )
        accumulator.onEvent(
            StreamEvent.Usage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
            nowMillis = 5L,
        )
        accumulator.onEvent(StreamEvent.TextDelta(".png)"), nowMillis = 10L)

        val part = accumulator.snapshot().parts.single()
        assertEquals(UiMessagePartType.IMAGE, part.type)
        assertEquals("https://cdn.example.com/sunset.png", part.content)
    }

    @Test
    fun normalMarkdownLinksAndBareUrlsRemainTextParts() {
        val accumulator = StreamingMessageAccumulator()
        val content = "普通[链接](https://example.com)和 https://cdn.example.com/photo.png 都是文本。"

        accumulator.onEvent(StreamEvent.TextDelta(content), nowMillis = 0L)

        assertEquals(listOf(UiMessagePartType.TEXT), accumulator.snapshot().parts.map { it.type })
        assertEquals(content, accumulator.snapshot().parts.single().content)
    }

    @Test
    fun explicitImageEventCreatesStableImagePartWithMetadata() {
        val accumulator = StreamingMessageAccumulator()

        accumulator.onEvent(
            StreamEvent.ImageDelta(
                source = "data:image/png;base64,aGVsbG8=",
                mimeType = "image/png",
                altText = "示例图",
            ),
            nowMillis = 0L,
        )

        val part = accumulator.snapshot().parts.single()
        assertEquals(UiMessagePartType.IMAGE, part.type)
        assertEquals("data:image/png;base64,aGVsbG8=", part.content)
        assertEquals("image/png", part.metadata["mimeType"])
        assertEquals("示例图", part.metadata["altText"])
        assertTrue(part.stable)
        assertEquals(MessageStatus.STREAMING, accumulator.snapshot().status)
    }

    @Test
    fun explicitImageEventPreservesLocalUriSource() {
        val accumulator = StreamingMessageAccumulator()

        accumulator.onEvent(
            StreamEvent.ImageDelta(
                source = "content://com.harnessapk.fileprovider/chat_images/generated.png",
                mimeType = "image/png",
            ),
            nowMillis = 0L,
        )

        val part = accumulator.snapshot().parts.single()
        assertEquals(UiMessagePartType.IMAGE, part.type)
        assertEquals("content://com.harnessapk.fileprovider/chat_images/generated.png", part.content)
        assertEquals("image/png", part.metadata["mimeType"])
    }

    @Test
    fun dataMarkdownImageInfersMimeType() {
        val accumulator = StreamingMessageAccumulator()

        accumulator.onEvent(
            StreamEvent.TextDelta("![图](data:image/webp;base64,aGVsbG8=)"),
            nowMillis = 0L,
        )

        val part = accumulator.snapshot().parts.single()
        assertEquals(UiMessagePartType.IMAGE, part.type)
        assertEquals("image/webp", part.metadata["mimeType"])
    }

    @Test
    fun textDeltasFlushByIntervalAfterFirstChunk() {
        val accumulator = StreamingMessageAccumulator(
            flushIntervalMillis = 300L,
            maxBufferedChars = 320,
        )

        accumulator.onEvent(StreamEvent.TextDelta("你"), nowMillis = 0L)
        assertNull(accumulator.onEvent(StreamEvent.TextDelta("好"), nowMillis = 100L))

        val flush = accumulator.onEvent(StreamEvent.TextDelta("啊"), nowMillis = 300L)

        assertEquals(StreamingFlushReason.INTERVAL, flush!!.reason)
        assertEquals("你好啊", flush.snapshot.parts.single().content)
    }

    @Test
    fun textDeltasFlushByBufferedSize() {
        val accumulator = StreamingMessageAccumulator(
            flushIntervalMillis = 1_000L,
            maxBufferedChars = 4,
        )

        accumulator.onEvent(StreamEvent.TextDelta("a"), nowMillis = 0L)

        val flush = accumulator.onEvent(StreamEvent.TextDelta("bcd"), nowMillis = 10L)

        assertEquals(StreamingFlushReason.SIZE, flush!!.reason)
        assertEquals("abcd", flush.snapshot.parts.single().content)
    }

    @Test
    fun longTextSplitsIntoStableBlocksAndSmallTail() {
        val accumulator = StreamingMessageAccumulator(
            flushIntervalMillis = 1_000L,
            maxBufferedChars = 200,
            maxTailChars = 12,
        )

        val flush = accumulator.onEvent(
            StreamEvent.TextDelta("第一段内容。\n\n第二段内容继续生成。"),
            nowMillis = 0L,
        )

        val parts = flush!!.snapshot.parts
        assertEquals(2, parts.size)
        assertEquals("第一段内容。\n\n", parts[0].content)
        assertEquals("第二段内容继续生成。", parts[1].content)
        assertTrue(parts[0].stable)
        assertFalse(parts[1].stable)
        assertTrue(parts[1].content.length <= 12)
    }

    @Test
    fun switchingFromReasoningToTextClosesReasoningPart() {
        val accumulator = StreamingMessageAccumulator()

        accumulator.onEvent(StreamEvent.ReasoningDelta("先分析"), nowMillis = 0L)
        val flush = accumulator.onEvent(StreamEvent.TextDelta("结论"), nowMillis = 20L)

        val parts = flush!!.snapshot.parts
        assertEquals(StreamingFlushReason.STRUCTURE, flush.reason)
        assertEquals(2, parts.size)
        assertEquals(UiMessagePartType.REASONING, parts[0].type)
        assertEquals("先分析", parts[0].content)
        assertTrue(parts[0].stable)
        assertEquals(UiMessagePartType.TEXT, parts[1].type)
        assertEquals("结论", parts[1].content)
        assertFalse(parts[1].stable)
    }

    @Test
    fun toolCallDeltasMergeByToolIdAndClosePreviousTextPart() {
        val accumulator = StreamingMessageAccumulator()

        accumulator.onEvent(StreamEvent.TextDelta("我来查"), nowMillis = 0L)
        accumulator.onEvent(
            StreamEvent.ToolCallDelta(id = "tool-1", name = "search", argumentsDelta = "{\"q\""),
            nowMillis = 10L,
        )
        val flush = accumulator.onEvent(
            StreamEvent.ToolCallDelta(id = "tool-1", name = "search", argumentsDelta = ":\"天气\"}"),
            nowMillis = 20L,
        )

        val parts = flush!!.snapshot.parts
        assertEquals(2, parts.size)
        assertEquals(UiMessagePartType.TEXT, parts[0].type)
        assertTrue(parts[0].stable)
        assertEquals(UiMessagePartType.TOOL_CALL, parts[1].type)
        assertEquals("{\"q\":\"天气\"}", parts[1].content)
        assertEquals("tool-1", parts[1].metadata["id"])
        assertEquals("search", parts[1].metadata["name"])
    }

    @Test
    fun cancelStabilizesPartsAndIgnoresLateDeltas() {
        val accumulator = StreamingMessageAccumulator()
        accumulator.onEvent(StreamEvent.TextDelta("已经生成"), nowMillis = 0L)

        val cancelled = accumulator.cancel(nowMillis = 50L)
        val late = accumulator.onEvent(StreamEvent.TextDelta("不应出现"), nowMillis = 60L)

        assertEquals(StreamingFlushReason.CANCELLED, cancelled.reason)
        assertEquals(MessageStatus.CANCELLED, cancelled.snapshot.status)
        assertEquals("已经生成", cancelled.snapshot.parts.single().content)
        assertTrue(cancelled.snapshot.parts.single().stable)
        assertNull(late)
        assertEquals("已经生成", accumulator.snapshot().parts.single().content)
    }

    @Test
    fun finishStabilizesPartsAndIgnoresLateDeltas() {
        val accumulator = StreamingMessageAccumulator()
        accumulator.onEvent(StreamEvent.TextDelta("完成"), nowMillis = 0L)

        val finished = accumulator.onEvent(StreamEvent.Finished(reason = "stop"), nowMillis = 100L)
        val late = accumulator.onEvent(StreamEvent.TextDelta("late"), nowMillis = 120L)

        assertEquals(StreamingFlushReason.FINISHED, finished!!.reason)
        assertEquals(MessageStatus.SUCCEEDED, finished.snapshot.status)
        assertEquals("stop", finished.snapshot.finishReason)
        assertTrue(finished.snapshot.parts.single().stable)
        assertNull(late)
        assertEquals("完成", accumulator.snapshot().parts.single().content)
    }

    @Test
    fun legacyVisibleTextUsesTextPartsOnlyUntilPartRendererIsReady() {
        val accumulator = StreamingMessageAccumulator()
        accumulator.onEvent(StreamEvent.ReasoningDelta("隐藏思考"), nowMillis = 0L)
        accumulator.onEvent(StreamEvent.TextDelta("可见答案"), nowMillis = 10L)
        accumulator.onEvent(
            StreamEvent.ToolCallDelta(id = "tool-1", name = "search", argumentsDelta = "{}"),
            nowMillis = 20L,
        )

        assertEquals("可见答案", accumulator.snapshot().legacyVisibleText())
    }

    @Test
    fun legacyVisibleTextConcatsSplitTextPartsWithoutExtraBlankLines() {
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.STREAMING,
            parts = listOf(
                UiMessagePartDraft(
                    index = 0,
                    type = UiMessagePartType.TEXT,
                    content = "第一段\n\n",
                    stable = true,
                ),
                UiMessagePartDraft(
                    index = 1,
                    type = UiMessagePartType.TEXT,
                    content = "第二段",
                    stable = false,
                ),
            ),
        )

        assertEquals("第一段\n\n第二段", snapshot.legacyVisibleText())
    }

    @Test
    fun longMarkdownStreamPreservesTextAndKeepsTailBounded() {
        val markdown = (1..220).joinToString("\n\n") { index ->
            """
            ## Git 章节 $index

            - 初始化仓库
              - 配置用户名
              - 配置邮箱
            - 提交代码

            ```bash
            git add .
            git commit -m "提交 $index"
            ```
            """.trimIndent()
        }
        val accumulator = StreamingMessageAccumulator(
            flushIntervalMillis = 300L,
            maxBufferedChars = 320,
            maxTailChars = 1_200,
        )
        var now = 0L

        markdown.chunked(37).forEach { chunk ->
            now += 80L
            accumulator.onEvent(StreamEvent.TextDelta(chunk), nowMillis = now)?.let { flush ->
                assertTrue(flush.snapshot.parts.last().content.length <= 1_200)
            }
        }
        val finished = accumulator.onEvent(StreamEvent.Finished("stop"), nowMillis = now + 80L)!!

        assertEquals(markdown, finished.snapshot.legacyVisibleText())
        assertTrue(finished.snapshot.parts.size > 1)
        assertTrue(finished.snapshot.parts.all { it.stable })
    }

    @Test
    fun legacyVisibleDeltaReturnsOnlyNewSuffixWhenSnapshotExtendsPreviousText() {
        val accumulator = StreamingMessageAccumulator()
        accumulator.onEvent(StreamEvent.TextDelta("你好"), nowMillis = 0L)
        accumulator.onEvent(StreamEvent.TextDelta("世界"), nowMillis = 400L)

        assertEquals("世界", accumulator.snapshot().legacyVisibleDelta(previousVisibleText = "你好"))
    }

    @Test
    fun legacyVisibleDeltaReturnsReplacementWhenSnapshotNoLongerExtendsPreviousText() {
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.STREAMING,
            parts = listOf(
                UiMessagePartDraft(
                    index = 0,
                    type = UiMessagePartType.TEXT,
                    content = "新的完整文本",
                    stable = false,
                ),
            ),
        )

        assertEquals("新的完整文本", snapshot.legacyVisibleDelta(previousVisibleText = "旧文本"))
    }
}
