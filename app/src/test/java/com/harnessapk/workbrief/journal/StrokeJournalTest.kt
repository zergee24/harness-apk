package com.harnessapk.workbrief.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class StrokeJournalTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun appendReplayRoundTripPreservesPayloadAndOrder() {
        val file = temp.newFile("a.journal")
        StrokeJournal.open(file).first.use { journal ->
            val seq1 = journal.append(StrokeJournal.TYPE_STROKE_COMMITTED, "笔迹一".toByteArray())
            val seq2 = journal.append(StrokeJournal.TYPE_MARKER_ADDED, "M1".toByteArray())
            assertEquals(1L, seq1)
            assertEquals(2L, seq2)
        }
        StrokeJournal.open(file).first.use { journal ->
            val result = journal.replay()
            assertFalse(result.truncated)
            assertEquals(2, result.records.size)
            assertEquals("笔迹一", String(result.records[0].payload))
            assertEquals(StrokeJournal.TYPE_MARKER_ADDED, result.records[1].type)
            assertEquals(2L, result.lastSequence)
        }
    }

    @Test
    fun corruptedTailIsTruncatedAndReported() {
        val file = temp.newFile("b.journal")
        StrokeJournal.open(file).first.use { journal ->
            journal.append(StrokeJournal.TYPE_STROKE_COMMITTED, "good-1".toByteArray())
            journal.append(StrokeJournal.TYPE_STROKE_COMMITTED, "good-2".toByteArray())
        }
        Files.write(
            file.toPath(),
            "broken-tail".toByteArray(),
            StandardOpenOption.APPEND,
        )

        val (journal, openResult) = StrokeJournal.open(file)
        assertTrue("打开时应截断损坏尾部", openResult.truncated)
        assertEquals(2, openResult.records.size)
        assertEquals(2L, openResult.lastSequence)
        journal.use { it.append(StrokeJournal.TYPE_ERASE_POINT, "e".toByteArray()) }
        // 截断后可继续追加，序列接续
        val (journal2, result2) = StrokeJournal.open(file)
        assertFalse("截断修复后的文件重放不应再报损坏", result2.truncated)
        assertEquals(3, result2.records.size)
        assertEquals(3L, result2.lastSequence)
        journal2.close()
    }

    @Test
    fun emptyFileReplaysToNothing() {
        val journal = StrokeJournal.open(temp.newFile("c.journal")).first
        val result = journal.replay()
        assertEquals(0, result.records.size)
        assertFalse(result.truncated)
        journal.close()
    }

    @Test
    fun checkpointAdvancesCounter() {
        var now = 5_000L
        val journal = StrokeJournal.open(temp.newFile("d.journal"), { now }).first
        journal.append(StrokeJournal.TYPE_STROKE_COMMITTED, "x".toByteArray())
        journal.checkpoint()
        assertEquals(1, journal.checkpointCount)
    }

    @Test
    fun autoCheckpointAfterByteThreshold() {
        var now = 5_000L
        val journal = StrokeJournal.open(temp.newFile("g.journal"), { now }).first
        journal.use {
            repeat(64) { journal.append(StrokeJournal.TYPE_ERASE_POINT, ByteArray(1024)) }
        }
        assertTrue(journal.checkpointCount >= 1)
        journal.close()
    }

    @Test
    fun sequenceMustNotGoBackwardsAcrossSessions() {
        val file = temp.newFile("e.journal")
        StrokeJournal.open(file).first.use { it.append(StrokeJournal.TYPE_STROKE_COMMITTED, "a".toByteArray()) }
        val second = StrokeJournal.open(file).first
        assertEquals(1L, second.replay().lastSequence)
        second.use { it.append(StrokeJournal.TYPE_STROKE_COMMITTED, "b".toByteArray()) }
        StrokeJournal.open(file).first.use { journal ->
            assertEquals(2L, journal.replay().lastSequence)
        }
    }
}
