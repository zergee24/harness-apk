package com.harnessapk.ui.chat

import com.harnessapk.wiki.ConversationWikiMount
import com.harnessapk.wiki.ConversationWikiMountSelection
import com.harnessapk.wiki.WikiRef

data class ConversationWikiCatalogVersion(
    val ref: WikiRef,
    val ready: Boolean,
    val active: Boolean,
)

data class ConversationWikiCatalogEntry(
    val wikiId: String,
    val title: String,
    val versions: List<ConversationWikiCatalogVersion>,
)

data class ConversationWikiScopeOption(
    val wikiId: String,
    val title: String,
    val mountedRef: WikiRef?,
    val enabled: Boolean,
    val unavailable: Boolean,
    val suggestedReadyRef: WikiRef?,
    val readyVersions: List<ConversationWikiCatalogVersion>,
)

data class ConversationWikiUiState(
    val toolbarLabel: String,
    val options: List<ConversationWikiScopeOption>,
)

data class ConversationWikiScopeDraftEntry(
    val wikiId: String,
    val ref: WikiRef?,
    val enabled: Boolean,
    val wasMounted: Boolean,
)

data class ConversationWikiScopeDraft(
    val entries: List<ConversationWikiScopeDraftEntry>,
) {
    fun setEnabled(
        option: ConversationWikiScopeOption,
        enabled: Boolean,
    ): ConversationWikiScopeDraft = update(option.wikiId) { entry ->
        if (!enabled) {
            entry.copy(
                ref = if (entry.wasMounted) entry.ref else null,
                enabled = false,
            )
        } else {
            val resolved = entry.ref
                ?.takeIf { ref -> option.readyVersions.any { it.ref == ref } }
                ?: option.suggestedReadyRef
            entry.copy(ref = resolved, enabled = resolved != null)
        }
    }

    fun selectVersion(wikiId: String, ref: WikiRef): ConversationWikiScopeDraft = update(wikiId) { entry ->
        entry.copy(ref = ref)
    }

    fun mountSelections(): List<ConversationWikiMountSelection> = entries
        .mapNotNull { entry ->
            entry.ref?.let { ref -> ConversationWikiMountSelection(ref = ref, enabled = entry.enabled) }
        }
        .sortedBy { it.ref.wikiId }

    private fun update(
        wikiId: String,
        transform: (ConversationWikiScopeDraftEntry) -> ConversationWikiScopeDraftEntry,
    ): ConversationWikiScopeDraft = copy(
        entries = entries.map { entry -> if (entry.wikiId == wikiId) transform(entry) else entry },
    )
}

internal fun conversationWikiUiState(
    mounts: List<ConversationWikiMount>,
    catalog: List<ConversationWikiCatalogEntry>,
): ConversationWikiUiState {
    val mountsByWikiId = mounts.associateBy { mount -> mount.ref.wikiId }
    val catalogByWikiId = catalog.associateBy(ConversationWikiCatalogEntry::wikiId)
    val wikiIds = (mountsByWikiId.keys + catalogByWikiId.keys).toSortedSet()
    val options = wikiIds.map { wikiId ->
        val mounted = mountsByWikiId[wikiId]
        val entry = catalogByWikiId[wikiId]
        val readyVersions = entry?.versions.orEmpty()
            .filter(ConversationWikiCatalogVersion::ready)
            .sortedBy { it.ref.version }
        val mountedReady = mounted?.let { mount ->
            entry?.versions?.firstOrNull { it.ref == mount.ref }?.ready == true
        } ?: false
        val suggested = readyVersions.firstOrNull(ConversationWikiCatalogVersion::active)
            ?: readyVersions.maxByOrNull { it.ref.version }
        ConversationWikiScopeOption(
            wikiId = wikiId,
            title = entry?.title?.ifBlank { wikiId } ?: wikiId,
            mountedRef = mounted?.ref,
            enabled = mounted?.enabled == true,
            unavailable = mounted != null && !mountedReady,
            suggestedReadyRef = suggested?.ref,
            readyVersions = readyVersions,
        )
    }.sortedWith(compareBy<ConversationWikiScopeOption> { it.title }.thenBy { it.wikiId })
    val readyEnabledCount = options.count { option -> option.enabled && !option.unavailable }
    val toolbarLabel = when {
        options.any(ConversationWikiScopeOption::unavailable) -> "自动 · $readyEnabledCount · 异常"
        readyEnabledCount > 0 -> "自动 · $readyEnabledCount"
        else -> "知识库"
    }
    return ConversationWikiUiState(toolbarLabel = toolbarLabel, options = options)
}

internal fun initialConversationWikiScopeDraft(
    state: ConversationWikiUiState,
): ConversationWikiScopeDraft = ConversationWikiScopeDraft(
    entries = state.options.map { option ->
        ConversationWikiScopeDraftEntry(
            wikiId = option.wikiId,
            ref = option.mountedRef,
            enabled = option.enabled,
            wasMounted = option.mountedRef != null,
        )
    },
)
