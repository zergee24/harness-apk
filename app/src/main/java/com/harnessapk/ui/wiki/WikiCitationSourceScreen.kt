package com.harnessapk.ui.wiki

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.wiki.MessageWikiCitation
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiVersionState
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal sealed interface WikiCitationSourceContentState {
    data object Loading : WikiCitationSourceContentState

    data object Missing : WikiCitationSourceContentState

    data class Installed(val citation: MessageWikiCitation) : WikiCitationSourceContentState

    data class Snapshot(val citation: MessageWikiCitation) : WikiCitationSourceContentState
}

internal fun wikiCitationSourceContentState(
    citation: MessageWikiCitation,
    exactVersionReady: Boolean,
    installedOriginalText: String?,
): WikiCitationSourceContentState = if (
    exactVersionReady &&
    installedOriginalText != null &&
    sha256CitationSource(installedOriginalText) == citation.originalTextSha256
) {
    WikiCitationSourceContentState.Installed(citation)
} else {
    WikiCitationSourceContentState.Snapshot(citation)
}

@Composable
fun WikiCitationSourceScreen(
    container: AppContainer,
    citationId: String,
    contentPadding: PaddingValues,
    onOpenSource: (WikiRef, String) -> Unit,
) {
    var state by remember(citationId) {
        mutableStateOf<WikiCitationSourceContentState>(WikiCitationSourceContentState.Loading)
    }

    LaunchedEffect(citationId) {
        val citation = container.conversationWikiRepository.citation(citationId)
        if (citation == null) {
            state = WikiCitationSourceContentState.Missing
            return@LaunchedEffect
        }
        val installedOriginalText = installedCitationOriginalText(container, citation)
        state = wikiCitationSourceContentState(
            citation = citation,
            exactVersionReady = installedOriginalText != null,
            installedOriginalText = installedOriginalText,
        )
    }

    when (val current = state) {
        WikiCitationSourceContentState.Loading -> WikiCitationLoadingState(contentPadding)
        WikiCitationSourceContentState.Missing -> WikiRecoveryState(
            message = "引用不存在或已被删除。",
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        )
        is WikiCitationSourceContentState.Installed -> WikiSourceReaderScreen(
            container = container,
            ref = current.citation.ref,
            chunkId = current.citation.chunkId,
            highlightText = current.citation.originalTextSnapshot,
            contentPadding = contentPadding,
            onOpenSource = { chunkId -> onOpenSource(current.citation.ref, chunkId) },
        )
        is WikiCitationSourceContentState.Snapshot -> WikiCitationSnapshotContent(
            citation = current.citation,
            contentPadding = contentPadding,
        )
    }
}

private suspend fun installedCitationOriginalText(
    container: AppContainer,
    citation: MessageWikiCitation,
): String? = try {
    val version = container.wikiRepository
        .listVersions(citation.ref.wikiId)
        .firstOrNull { it.version == citation.ref.version }
    if (version?.state != WikiVersionState.READY.name) return null
    if (container.wikiRepository.manifestFor(citation.ref) == null) return null
    container.wikiContentStore.findChunk(citation.ref, citation.chunkId)?.originalText
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

@Composable
private fun WikiCitationLoadingState(contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = "正在读取引用原文",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WikiCitationSnapshotContent(
    citation: MessageWikiCitation,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "原知识库版本不可用",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(citation.wikiTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            text = "v${citation.ref.version}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(citation.sourceTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Text(
            text = citation.sectionPath,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(citation.locatorLabel, style = MaterialTheme.typography.labelLarge)
        HorizontalDivider()
        SelectionContainer {
            Text(citation.originalTextSnapshot, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun sha256CitationSource(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
