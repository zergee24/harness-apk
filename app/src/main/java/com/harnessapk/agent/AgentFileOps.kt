package com.harnessapk.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface AgentFileOps {
    fun createDirectories(directory: File)

    suspend fun copyBounded(input: InputStream, target: File, maxBytes: Long): Long

    suspend fun write(target: File, block: suspend (OutputStream) -> Unit)

    suspend fun moveAtomically(source: File, destination: File)

    fun delete(file: File): Boolean
}

class DefaultAgentFileOps : AgentFileOps {
    override fun createDirectories(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw AgentBundleException("无法创建目录：${directory.absolutePath}")
        }
    }

    override suspend fun copyBounded(input: InputStream, target: File, maxBytes: Long): Long {
        var total = 0L
        write(target) { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw AgentBundleException("智能体包超过 2 GiB 上限")
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    override suspend fun write(target: File, block: suspend (OutputStream) -> Unit) {
        createDirectories(requireNotNull(target.parentFile))
        target.outputStream().buffered().use { output -> block(output) }
    }

    override suspend fun moveAtomically(source: File, destination: File) {
        createDirectories(requireNotNull(destination.parentFile))
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!source.renameTo(destination)) {
                throw AgentBundleException(
                    "同文件系统原子 rename 失败：${source.absolutePath} -> ${destination.absolutePath}",
                )
            }
        }
    }

    override fun delete(file: File): Boolean = !file.exists() || file.delete()
}
