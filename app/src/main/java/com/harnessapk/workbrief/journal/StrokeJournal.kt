package com.harnessapk.workbrief.journal

import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * 工作简报笔迹追加日志（设计 §17.1/§17.2）。
 *
 * 记录格式（小端不适用——DataOutputStream 为大端）：
 * `[type:1B][sequence:8B][payloadLen:4B][crc:8B][payload]`，
 * CRC32 覆盖 type + sequence + payload。
 *
 * 语义：
 * - 每条记录写完即 flush；[checkpoint] 执行 fsync（每 2s 或 64KiB 自动触发）；
 * - 重放遇到尾部损坏（CRC 不符/长度越界）时截断到最后一条完整记录，并标记 [ReplayResult.truncated];
 * - sequence 必须单调递增，重放返回全部记录，调用方按 eventId 幂等消费。
 * 单写者（§17.3）：实例非线程安全，调用方保证同一时间只有一个记录协程。
 */
class StrokeJournal private constructor(
    private val file: File,
    private val clock: () -> Long,
) : Closeable {

    data class Record(val type: Byte, val sequence: Long, val payload: ByteArray)
    data class ReplayResult(val records: List<Record>, val truncated: Boolean, val lastSequence: Long)

    private var out: DataOutputStream? = null
    private var channel: FileOutputStream? = null
    private var lastSequence = 0L
    private var bytesSinceCheckpoint = 0L
    private var lastCheckpointAtMs = clock()
    var checkpointCount = 0
        private set

    val sizeBytes: Long
        get() = file.length()

    companion object {
        const val TYPE_STROKE_COMMITTED: Byte = 1
        const val TYPE_MARKER_ADDED: Byte = 2
        const val TYPE_PAGE_ADDED: Byte = 3
        const val TYPE_ERASE_POINT: Byte = 4
        const val TYPE_PAGE_REMOVED: Byte = 5

        private const val HEADER_BYTES = 1 + 8 + 4 + 8
        private const val CHECKPOINT_INTERVAL_MS = 2_000L
        private const val CHECKPOINT_INTERVAL_BYTES = 64L * 1024

        /** 打开既有日志或创建新日志；打开时先做尾部校验修复。 */
        fun open(file: File, clock: () -> Long = System::currentTimeMillis): Pair<StrokeJournal, ReplayResult> {
            val journal = StrokeJournal(file, clock)
            val result = journal.replay()
            journal.lastSequence = result.lastSequence
            return journal to result
        }
    }

    private fun outStream(): DataOutputStream {
        out?.let { return it }
        file.parentFile?.mkdirs()
        val fos = FileOutputStream(file, true)
        channel = fos
        out = DataOutputStream(fos)
        return out!!
    }

    /** 重放全部有效记录；尾部损坏时截断文件并返回 truncated=true。 */
    fun replay(): ReplayResult {
        if (!file.exists()) return ReplayResult(emptyList(), truncated = false, lastSequence = 0)
        val records = mutableListOf<Record>()
        var truncated = false
        var lastGoodOffset = 0L
        var lastSeq = 0L
        RandomAccessFile(file, "r").use { raf ->
            while (raf.filePointer + HEADER_BYTES <= file.length()) {
                val type = raf.readByte()
                val sequence = raf.readLong()
                val length = raf.readInt()
                if (length < 0 || raf.filePointer + length + 8 > file.length()) {
                    truncated = true
                    break
                }
                val payload = ByteArray(length)
                raf.readFully(payload)
                val crc = raf.readLong()
                val crc32 = CRC32().apply {
                    update(type.toInt())
                    update(sequence.toString().toByteArray())
                    update(payload)
                }
                if (crc32.value != crc) {
                    truncated = true
                    break
                }
                if (sequence <= lastSeq) {
                    truncated = true
                    break
                }
                records.add(Record(type, sequence, payload))
                lastSeq = sequence
                lastGoodOffset = raf.filePointer
            }
            // 尾部残留不足一条记录头（或半条记录）：按损坏尾部处理，不能当"没有更多记录"。
            if (!truncated && raf.filePointer < file.length()) truncated = true
        }
        if (truncated) truncateTo(lastGoodOffset)
        return ReplayResult(records, truncated, lastSeq)
    }

    fun append(type: Byte, payload: ByteArray): Long {
        val sequence = lastSequence + 1
        val crc32 = CRC32().apply {
            update(type.toInt())
            update(sequence.toString().toByteArray())
            update(payload)
        }
        val out = outStream()
        out.writeByte(type.toInt())
        out.writeLong(sequence)
        out.writeInt(payload.size)
        out.write(payload)
        out.writeLong(crc32.value)
        out.flush()
        lastSequence = sequence
        bytesSinceCheckpoint += HEADER_BYTES + payload.size
        maybeCheckpoint()
        return sequence
    }

    private fun maybeCheckpoint() {
        val now = clock()
        if (bytesSinceCheckpoint >= CHECKPOINT_INTERVAL_BYTES || now - lastCheckpointAtMs >= CHECKPOINT_INTERVAL_MS) {
            checkpoint()
        }
    }

    /** fsync 到磁盘（§17.1 提交顺序：追加+CRC → fsync → 索引事务 → UI checkpoint）。 */
    fun checkpoint() {
        channel?.channel?.force(false)
        bytesSinceCheckpoint = 0
        lastCheckpointAtMs = clock()
        checkpointCount++
    }

    private fun truncateTo(offset: Long) {
        // 截断可能发生在只读阶段（open→replay，输出通道尚未创建），独立以 rw 句柄执行。
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(offset)
            raf.channel.force(false)
        }
    }

    override fun close() {
        checkpoint()
        out?.close()
        out = null
        channel = null
    }
}
