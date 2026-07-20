package com.harnessapk.agent

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

internal class AgentCorpusValidationIndex(
    parent: File,
    private val maxRecordCount: Long = MAX_INDEX_RECORDS,
) : Closeable {
    private val root = Files.createTempDirectory(parent.toPath(), ".corpus-index-").toFile()
    private val diskBudgetBytes = minOf(MAX_INDEX_BYTES, root.usableSpace / 2)
    private var bytesWritten = 0L
    private var recordCount = 0L

    init {
        if (diskBudgetBytes < MIN_INDEX_BUDGET_BYTES) {
            close()
            throw AgentBundleException("可用磁盘空间不足，无法安全校验大语料")
        }
    }

    fun putUnique(key: String, value: ByteArray = byteArrayOf()): Boolean {
        validateRecord(key, value)
        val bucket = bucketFile(key)
        if (find(bucket, key) != null) return false
        if (recordCount >= maxRecordCount) {
            throw AgentBundleException("语料校验磁盘索引记录数超过安全预算")
        }
        val recordBytes = RECORD_HEADER_BYTES + key.encodeToByteArray().size + value.size
        if (bytesWritten + recordBytes > diskBudgetBytes) {
            throw AgentBundleException("语料校验磁盘索引超过安全预算")
        }
        if (bucket.length() + recordBytes > MAX_BUCKET_BYTES) {
            throw AgentBundleException("语料校验磁盘索引 bucket 超过安全预算")
        }
        DataOutputStream(BufferedOutputStream(FileOutputStream(bucket, true))).use { output ->
            val keyBytes = key.encodeToByteArray()
            output.writeShort(keyBytes.size)
            output.writeInt(value.size)
            output.write(keyBytes)
            output.write(value)
        }
        bytesWritten += recordBytes
        recordCount += 1
        return true
    }

    fun get(key: String): ByteArray? {
        validateRecord(key, byteArrayOf())
        return find(bucketFile(key), key)
    }

    fun contains(key: String): Boolean = get(key) != null

    fun diskBytes(): Long = bytesWritten

    fun records(): Long = recordCount

    override fun close() {
        root.deleteRecursively()
    }

    private fun bucketFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
        val bucket = ((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)
        return File(root, bucket.toString(16).padStart(4, '0'))
    }

    private fun find(bucket: File, expectedKey: String): ByteArray? {
        if (!bucket.isFile) return null
        DataInputStream(BufferedInputStream(FileInputStream(bucket))).use { input ->
            while (true) {
                val keySize = try {
                    input.readUnsignedShort()
                } catch (_: EOFException) {
                    return null
                }
                val valueSize = input.readInt()
                if (keySize <= 0 || keySize > MAX_KEY_BYTES || valueSize < 0 || valueSize > MAX_VALUE_BYTES) {
                    throw AgentBundleException("语料校验磁盘索引损坏")
                }
                val key = ByteArray(keySize).also(input::readFully).decodeToString()
                val value = ByteArray(valueSize).also(input::readFully)
                if (key == expectedKey) return value
            }
        }
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
        private const val MAX_BUCKET_BYTES = 64L * 1024 * 1024
        private const val MAX_INDEX_BYTES = 6L * 1024 * 1024 * 1024
        private const val MIN_INDEX_BUDGET_BYTES = 64L * 1024 * 1024
        private const val MAX_INDEX_RECORDS = 1_000_000L
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
