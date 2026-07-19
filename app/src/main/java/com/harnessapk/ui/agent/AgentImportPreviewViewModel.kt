package com.harnessapk.ui.agent

import com.harnessapk.agent.AgentImportSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the single preview that may remain actionable in the import dialog. */
internal class AgentImportPreviewViewModel(
    private val discardImport: suspend (AgentImportSession) -> Unit,
) {
    private val mutableSession = MutableStateFlow<AgentImportSession?>(null)
    val session: StateFlow<AgentImportSession?> = mutableSession.asStateFlow()

    suspend fun replace(newSession: AgentImportSession): Boolean {
        val previous = mutableSession.value
        if (previous != null) {
            try {
                discardImport(previous)
            } catch (error: Throwable) {
                discardNewSession(newSession, error)
                throw error
            }
        }
        if (mutableSession.compareAndSet(previous, newSession)) return true
        discardNewSession(newSession)
        return false
    }

    suspend fun discardIfCurrent(expected: AgentImportSession): Boolean {
        if (mutableSession.value !== expected) return false
        discardImport(expected)
        return mutableSession.compareAndSet(expected, null)
    }

    fun clearIfCurrent(expected: AgentImportSession): Boolean =
        mutableSession.compareAndSet(expected, null)

    private suspend fun discardNewSession(newSession: AgentImportSession, original: Throwable? = null) {
        try {
            discardImport(newSession)
        } catch (cleanupError: Throwable) {
            newSession.stagedFile.delete()
            original?.addSuppressed(cleanupError) ?: throw cleanupError
        }
    }
}
