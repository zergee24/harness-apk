package com.harnessapk.ui.wiki

import com.harnessapk.storage.WikiEntity
import com.harnessapk.storage.WikiVersionEntity
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiVersionState

sealed interface WikiLibraryUiState {
    data object Loading : WikiLibraryUiState

    data object Empty : WikiLibraryUiState

    data class Content(
        val entries: List<WikiLibraryEntry>,
    ) : WikiLibraryUiState
}

data class WikiLibraryEntry(
    val wikiId: String,
    val title: String,
    val description: String,
    val activeVersion: WikiLibraryVersionUi?,
    val versions: List<WikiLibraryVersionUi>,
)

data class WikiLibraryVersionUi(
    val ref: WikiRef,
    val sizeBytes: Long,
    val publisherFingerprint: String,
    val publisherKeyId: String,
    val enabledForNewConversations: Boolean,
    val state: String,
    val invalidReason: String?,
) {
    val isReady: Boolean get() = state == WikiVersionState.READY.name
}

internal fun wikiLibraryUiState(
    wikis: List<WikiEntity>,
    versionsByWiki: Map<String, List<WikiVersionEntity>>,
): WikiLibraryUiState {
    if (wikis.isEmpty()) return WikiLibraryUiState.Empty
    return WikiLibraryUiState.Content(
        entries = wikis.map { wiki ->
            val versions = versionsByWiki[wiki.id]
                .orEmpty()
                .sortedByDescending(WikiVersionEntity::version)
                .map(WikiVersionEntity::toUi)
            WikiLibraryEntry(
                wikiId = wiki.id,
                title = wiki.title,
                description = wiki.description,
                activeVersion = versions.firstOrNull { it.ref.version == wiki.activeVersion },
                versions = versions,
            )
        },
    )
}

private fun WikiVersionEntity.toUi(): WikiLibraryVersionUi = WikiLibraryVersionUi(
    ref = WikiRef(wikiId, version),
    sizeBytes = sizeBytes,
    publisherFingerprint = publisherFingerprint,
    publisherKeyId = publisherKeyId,
    enabledForNewConversations = enabledForNewConversations,
    state = state,
    invalidReason = invalidReason,
)
