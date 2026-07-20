package com.harnessapk.chat

import com.harnessapk.agent.AgentLifecycleCoordinator
import com.harnessapk.agent.AgentRuntimeContext
import com.harnessapk.agent.AgentTransactionRunner
import com.harnessapk.agent.sanitizeAgentCitationMarkers
import com.harnessapk.storage.AgentDao

/** Persists final agent citations only while the fixed agent version remains installed. */
class AgentSourcePartWriter(
    private val dao: AgentDao,
    private val chatRepository: ChatRepository,
    private val transactionRunner: AgentTransactionRunner,
    private val lifecycleCoordinator: AgentLifecycleCoordinator,
) {
    suspend fun persist(
        messageId: String,
        snapshot: StreamingMessageSnapshot,
        context: AgentRuntimeContext,
        onPrepared: (StreamingMessageSnapshot) -> Unit = {},
        onValidated: (StreamingMessageSnapshot) -> Unit = {},
    ): StreamingMessageSnapshot = lifecycleCoordinator.serialized {
        lateinit var persisted: StreamingMessageSnapshot
        transactionRunner.run {
            val selectedKeys = context.evidence.map { it.chunkKey }.filter(String::isNotBlank).distinct()
            val installedKeys = if (selectedKeys.isEmpty()) {
                emptySet()
            } else {
                dao.listInstalledVersionChunkKeys(context.agentId, context.version, selectedKeys).toSet()
            }
            val verifiedEvidence = context.evidence.filter { it.chunkKey in installedKeys }
            val sourceFree = sanitizeAgentCitationMarkers(snapshot).let { sanitized ->
                sanitized.copy(
                    parts = sanitized.parts
                        .filterNot { it.type == UiMessagePartType.AGENT_SOURCES }
                        .mapIndexed { index, part -> part.copy(index = index) },
                )
            }
            persisted = appendAgentSourcesPart(sourceFree, verifiedEvidence)
            onPrepared(persisted)
            chatRepository.replaceMessagePartsFromSnapshot(messageId, persisted)
        }
        onValidated(persisted)
        persisted
    }
}
