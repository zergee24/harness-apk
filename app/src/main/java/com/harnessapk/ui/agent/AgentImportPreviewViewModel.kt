package com.harnessapk.ui.agent

import com.harnessapk.agent.AgentImportSessionUnavailableException
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the single preview that may remain actionable in the import dialog. */
internal class AgentImportPreviewViewModel<T : Any>(
    private val discardImport: suspend (T) -> Unit,
    private val stagedFile: (T) -> File,
) {
    private val mutableSession = MutableStateFlow<T?>(null)
    val session: StateFlow<T?> = mutableSession.asStateFlow()

    suspend fun replace(newSession: T): Boolean {
        val previous = mutableSession.value
        if (previous != null) {
            try {
                discardImport(previous)
            } catch (_: AgentImportSessionUnavailableException) {
                // The repository no longer owns the old preview; replacement can proceed.
            } catch (error: Throwable) {
                discardNewSession(newSession, error)
                throw error
            }
        }
        if (mutableSession.compareAndSet(previous, newSession)) return true
        discardNewSession(newSession)
        return false
    }

    suspend fun discardIfCurrent(expected: T): Boolean {
        if (mutableSession.value !== expected) return false
        try {
            discardImport(expected)
        } catch (_: AgentImportSessionUnavailableException) {
            // The preview is already terminal in the repository.
        }
        return mutableSession.compareAndSet(expected, null)
    }

    fun clearIfCurrent(expected: T): Boolean =
        mutableSession.compareAndSet(expected, null)

    private suspend fun discardNewSession(newSession: T, original: Throwable? = null) {
        try {
            discardImport(newSession)
        } catch (cleanupError: Throwable) {
            stagedFile(newSession).delete()
            original?.addSuppressed(cleanupError) ?: throw cleanupError
        }
    }
}
