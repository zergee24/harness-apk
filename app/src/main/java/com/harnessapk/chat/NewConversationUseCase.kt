package com.harnessapk.chat

import com.harnessapk.agent.ConversationIdentityRepository
import com.harnessapk.agent.ConversationIdentitySelection
import com.harnessapk.agent.InitialConversationIdentity
import com.harnessapk.agent.AgentLifecycleCoordinator
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiTransactionRunner

fun interface ConversationWikiDefaultsCopier {
    suspend fun copyDefaultsToConversationInTransaction(conversationId: String)
}

fun interface ConversationWikiScopeReplacer {
    suspend fun replaceEnabledScopeInTransaction(conversationId: String, scope: List<WikiRef>)
}

class NewConversationUseCase(
    private val chatRepository: ChatRepository,
    private val identityRepository: ConversationIdentityRepository,
    private val lifecycleCoordinator: AgentLifecycleCoordinator = AgentLifecycleCoordinator(),
    private val wikiDefaultsCopier: ConversationWikiDefaultsCopier? = null,
    private val wikiScopeReplacer: ConversationWikiScopeReplacer? = null,
    private val transactionRunner: WikiTransactionRunner? = null,
) {
    init {
        require((wikiDefaultsCopier == null) == (transactionRunner == null)) {
            "Wiki 默认范围复制与事务必须同时配置"
        }
    }

    suspend fun create(
        title: String = "新会话",
        projectId: String? = null,
        identity: InitialConversationIdentity = InitialConversationIdentity.Suggested,
        wikiScope: List<WikiRef>? = null,
    ): String = lifecycleCoordinator.serialized {
        val selected = when (identity) {
            InitialConversationIdentity.Suggested -> identityRepository.suggest(projectId)
            InitialConversationIdentity.Assistant -> ConversationIdentitySelection(null, null, null, false)
            is InitialConversationIdentity.Agent -> identityRepository.selectionForNewConversation(identity.agentId)
        }
        val createConversation: suspend () -> String = {
            chatRepository.createConversation(
                title = title,
                projectId = projectId,
                agentId = selected.agentId,
                agentVersion = selected.agentVersion,
            )
        }
        val copier = wikiDefaultsCopier
        val scopeReplacer = wikiScopeReplacer
        val runner = transactionRunner
        if (wikiScope != null && (scopeReplacer == null || runner == null)) {
            throw IllegalStateException("未配置 Wiki 范围替换能力")
        }
        if (copier == null || runner == null) return@serialized createConversation()

        var conversationId: String? = null
        runner.run {
            val createdId = createConversation()
            if (wikiScope == null) {
                copier.copyDefaultsToConversationInTransaction(createdId)
            } else {
                requireNotNull(scopeReplacer).replaceEnabledScopeInTransaction(createdId, wikiScope)
            }
            conversationId = createdId
        }
        requireNotNull(conversationId)
    }
}
