package com.harnessapk.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

internal class AgentCorpusValidationIndex(
    @Suppress("UNUSED_PARAMETER") parent: File,
    private val maxRecordCount: Long = MAX_INDEX_RECORDS,
) : Closeable {
    private var bytesWritten = 0L
    private var recordCount = 0L
    private val valuesByKey = HashMap<String, ByteArray>()

    fun putUnique(key: String, value: ByteArray = byteArrayOf()): Boolean {
        validateRecord(key, value)
        if (valuesByKey.containsKey(key)) return false
        if (recordCount >= maxRecordCount) {
            throw AgentBundleException("语料校验磁盘索引记录数超过安全预算")
        }
        val keyBytes = key.encodeToByteArray()
        val recordBytes = RECORD_HEADER_BYTES + keyBytes.size + value.size
        if (bytesWritten + recordBytes > memoryBudgetBytes) {
            throw AgentBundleException("语料校验磁盘索引超过安全预算")
        }
        valuesByKey[key] = value
        bytesWritten += recordBytes
        recordCount += 1
        return true
    }

    fun get(key: String): ByteArray? {
        validateRecord(key, byteArrayOf())
        return valuesByKey[key]
    }

    fun contains(key: String): Boolean = get(key) != null

    fun diskBytes(): Long = bytesWritten

    fun records(): Long = recordCount

    override fun close() {
        valuesByKey.clear()
    }

    private fun validateRecord(key: String, value: ByteArray) {
        val keySize = key.encodeToByteArray().size
        if (keySize <= 0 || keySize > MAX_KEY_BYTES || value.size > MAX_VALUE_BYTES) {
            throw AgentBundleException("语料校验索引记录超过安全上限")
        }
    }

    companion object {
        private const val RECORD_HEADER_BYTES = 6
        private const val MAX_KEY_BYTES = 1_024
        private const val MAX_VALUE_BYTES = 8 * 1024 * 1024
        private const val MAX_INDEX_RECORDS = 1_000_000L
        private const val memoryBudgetBytes = 256L * 1024 * 1024
    }
}

internal fun encodeIndexFields(vararg values: String): ByteArray =
    ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(values.size)
            values.forEach { value ->
                val encoded = value.encodeToByteArray()
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }
        bytes.toByteArray()
    }

internal fun decodeIndexFields(payload: ByteArray): List<String> =
    DataInputStream(ByteArrayInputStream(payload)).use { input ->
        val count = input.readInt()
        if (count !in 0..256) throw AgentBundleException("语料校验磁盘索引损坏")
        List(count) {
            val size = input.readInt()
            if (size !in 0..8 * 1024 * 1024) throw AgentBundleException("语料校验磁盘索引损坏")
            ByteArray(size).also(input::readFully).decodeToString()
        }.also {
            if (input.read() != -1) throw AgentBundleException("语料校验磁盘索引损坏")
        }
    }
