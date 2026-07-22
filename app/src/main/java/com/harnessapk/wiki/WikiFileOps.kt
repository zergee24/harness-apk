package com.harnessapk.wiki

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

interface WikiFileOps {
    fun createDirectories(directory: Path)

    suspend fun copy(source: Path, target: Path): Long

    fun fsync(file: Path)

    suspend fun moveAtomically(source: Path, destination: Path)

    fun deleteRecursively(path: Path): Boolean
}

class DefaultWikiFileOps : WikiFileOps {
    override fun createDirectories(directory: Path) {
        try {
            Files.createDirectories(directory)
        } catch (error: IOException) {
            throw WikiInstallException("无法创建 Wiki 存储目录", error)
        }
    }

    override suspend fun copy(source: Path, target: Path): Long {
        if (!Files.isRegularFile(source)) throw WikiInstallException("待安装 Wiki 原文不存在")
        createDirectories(requireNotNull(target.parent))
        return try {
            source.toFile().inputStream().buffered().use { input ->
                target.toFile().outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total = Math.addExact(total, read.toLong())
                        output.write(buffer, 0, read)
                    }
                    total
                }
            }
        } catch (error: WikiInstallException) {
            throw error
        } catch (error: Exception) {
            throw WikiInstallException("无法写入 Wiki 安装暂存文件", error)
        }
    }

    override fun fsync(file: Path) {
        try {
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
        } catch (error: IOException) {
            throw WikiInstallException("无法同步 Wiki 安装文件", error)
        }
    }

    override suspend fun moveAtomically(source: Path, destination: Path) {
        createDirectories(requireNotNull(destination.parent))
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!source.toFile().renameTo(destination.toFile())) {
                throw WikiInstallException("同文件系统原子移动 Wiki 版本失败")
            }
        } catch (error: IOException) {
            throw WikiInstallException("无法移动 Wiki 安装目录", error)
        }
    }

    override fun deleteRecursively(path: Path): Boolean =
        !Files.exists(path) || path.toFile().deleteRecursively()
}
