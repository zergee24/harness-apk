package com.harnessapk.ui.wiki

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.wiki.WikiDocument
import com.harnessapk.wiki.WikiContentUnavailableException
import com.harnessapk.wiki.WikiManifest
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiSection
import com.harnessapk.wiki.WikiSummary
import com.harnessapk.wiki.WikiTerm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun WikiBrowserScreen(
    container: AppContainer,
    ref: WikiRef,
    contentPadding: PaddingValues,
    onOpenSource: (String) -> Unit,
    onTitleLoaded: (String) -> Unit,
    onUseInNewConversation: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var content by remember(ref) { mutableStateOf<WikiBrowserLoadedContent?>(null) }
    var error by remember(ref) { mutableStateOf<String?>(null) }
    var selectedDocumentId by rememberSaveable(ref.wikiId, ref.version) { mutableStateOf<String?>(null) }
    var sectionTrail by remember(ref) { mutableStateOf<List<WikiSection>>(emptyList()) }
    var nodeState by remember(ref) { mutableStateOf(WikiBrowserNodeState()) }
    var showTerms by remember(ref) { mutableStateOf(false) }
    var isCreatingConversation by remember(ref) { mutableStateOf(false) }
    var newConversationError by remember(ref) { mutableStateOf<String?>(null) }

    LaunchedEffect(ref) {
        error = null
        content = null
        selectedDocumentId = null
        sectionTrail = emptyList()
        nodeState = WikiBrowserNodeState(loading = true)
        try {
            val manifest = container.wikiRepository.manifestFor(ref)
                ?: throw WikiContentUnavailableException("Wiki 版本不存在")
            content = WikiBrowserLoadedContent(
                manifest = manifest,
                documents = container.wikiContentStore.listDocuments(ref),
                rootSections = container.wikiContentStore.listSections(ref, parentSectionId = null),
                terms = if (manifest.capabilities.termIndex) container.wikiContentStore.listTerms(ref) else emptyList(),
            )
            onTitleLoaded(manifest.title)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = wikiBrowserErrorMessage(failure)
        } finally {
            nodeState = nodeState.copy(loading = false)
        }
    }

    val selectedDocument = content?.documents?.firstOrNull { it.id == selectedDocumentId }
    val selectedSection = sectionTrail.lastOrNull()
    LaunchedEffect(content, selectedDocumentId, selectedSection?.id) {
        val currentContent = content ?: return@LaunchedEffect
        if (selectedDocument == null) {
            nodeState = WikiBrowserNodeState()
            return@LaunchedEffect
        }
        nodeState = WikiBrowserNodeState(loading = true)
        try {
            val ownerType: String
            val ownerId: String
            val childSections: List<WikiSection>
            val chunks = if (selectedSection == null) {
                ownerType = "document"
                ownerId = selectedDocument.id
                childSections = currentContent.rootSections.filter { it.documentId == selectedDocument.id }
                emptyList()
            } else {
                ownerType = "section"
                ownerId = selectedSection.id
                childSections = container.wikiContentStore.listSections(ref, selectedSection.id)
                container.wikiContentStore.listChunks(ref, selectedSection.id)
            }
            nodeState = WikiBrowserNodeState(
                childSections = childSections,
                chunks = chunks,
                summaries = if (currentContent.manifest.capabilities.hierarchicalSummaries) {
                    container.wikiContentStore.summariesFor(ref, ownerType, ownerId)
                } else {
                    emptyList()
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = wikiBrowserErrorMessage(failure)
            nodeState = WikiBrowserNodeState()
        }
    }

    BackHandler(enabled = selectedDocumentId != null) {
        if (sectionTrail.isNotEmpty()) {
            sectionTrail = sectionTrail.dropLast(1)
        } else {
            selectedDocumentId = null
        }
    }

    when {
        error != null -> WikiRecoveryState(
            message = error.orEmpty(),
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        )
        content == null || nodeState.loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(176.dp),
            )
        }
        else -> WikiBrowserContent(
            content = requireNotNull(content),
            selectedDocument = selectedDocument,
            sectionTrail = sectionTrail,
            nodeState = nodeState,
            contentPadding = contentPadding,
            onOpenDocument = { document ->
                selectedDocumentId = document.id
                sectionTrail = emptyList()
            },
            onOpenSection = { section -> sectionTrail = sectionTrail + section },
            onOpenSource = onOpenSource,
            onShowTerms = { showTerms = true },
            isCreatingConversation = isCreatingConversation,
            newConversationError = newConversationError,
            onUseInNewConversation = {
                if (isCreatingConversation) return@WikiBrowserContent
                scope.launch {
                    isCreatingConversation = true
                    newConversationError = null
                    try {
                        onUseInNewConversation()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        newConversationError = wikiBrowserErrorMessage(failure)
                    } finally {
                        isCreatingConversation = false
                    }
                }
            },
        )
    }

    if (showTerms) {
        content?.takeIf { it.manifest.capabilities.termIndex }?.let { current ->
            WikiTermsSheet(terms = current.terms, onDismiss = { showTerms = false })
        }
    }
}

private data class WikiBrowserLoadedContent(
    val manifest: WikiManifest,
    val documents: List<WikiDocument>,
    val rootSections: List<WikiSection>,
    val terms: List<WikiTerm>,
)

private data class WikiBrowserNodeState(
    val loading: Boolean = false,
    val childSections: List<WikiSection> = emptyList(),
    val chunks: List<com.harnessapk.wiki.WikiChunk> = emptyList(),
    val summaries: List<WikiSummary> = emptyList(),
)

@Composable
private fun WikiBrowserContent(
    content: WikiBrowserLoadedContent,
    selectedDocument: WikiDocument?,
    sectionTrail: List<WikiSection>,
    nodeState: WikiBrowserNodeState,
    contentPadding: PaddingValues,
    onOpenDocument: (WikiDocument) -> Unit,
    onOpenSection: (WikiSection) -> Unit,
    onOpenSource: (String) -> Unit,
    onShowTerms: () -> Unit,
    isCreatingConversation: Boolean,
    newConversationError: String?,
    onUseInNewConversation: () -> Unit,
) {
    val breadcrumbs = wikiBreadcrumbs(selectedDocument, sectionTrail)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "wiki-title") {
            Column(modifier = Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(content.manifest.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "v${content.manifest.ref.version}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    enabled = !isCreatingConversation,
                    onClick = onUseInNewConversation,
                ) {
                    Text(if (isCreatingConversation) "正在创建" else "在新会话中使用")
                }
                newConversationError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (breadcrumbs.isNotEmpty()) {
            item(key = "breadcrumbs") {
                Text(
                    text = breadcrumbs.joinToString(" / ") { it.title },
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (selectedDocument == null) {
            items(content.documents, key = WikiDocument::id) { document ->
                WikiDocumentRow(document, onClick = { onOpenDocument(document) })
                HorizontalDivider()
            }
        } else {
            if (nodeState.summaries.isNotEmpty()) {
                item(key = "summaries") { WikiSummaries(nodeState.summaries) }
            }
            if (content.manifest.capabilities.termIndex) {
                item(key = "terms") {
                    TextButton(onClick = onShowTerms) { Text("术语索引") }
                }
            }
            items(nodeState.childSections, key = WikiSection::id) { section ->
                WikiSectionRow(section, onClick = { onOpenSection(section) })
                HorizontalDivider()
            }
            items(nodeState.chunks, key = com.harnessapk.wiki.WikiChunk::id) { chunk ->
                WikiChunkRow(
                    chunk = chunk,
                    onClick = { onOpenSource(chunk.id) },
                )
                HorizontalDivider()
            }
            if (nodeState.childSections.isEmpty() && nodeState.chunks.isEmpty()) {
                item(key = "empty-section") {
                    Text(
                        text = "此目录暂未提供可浏览原文。",
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun WikiDocumentRow(document: WikiDocument, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(document.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        listOf(document.responsibility, document.edition).filter(String::isNotBlank).joinToString(" · ")
            .takeIf(String::isNotBlank)
            ?.let { detail ->
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
    }
}

@Composable
private fun WikiSectionRow(section: WikiSection, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        section.path.takeIf { it != section.title }?.let { path ->
            Text(path, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WikiChunkRow(chunk: com.harnessapk.wiki.WikiChunk, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(chunk.locator.label, style = MaterialTheme.typography.labelLarge)
        Text(
            text = wikiTextPreview(chunk.originalText),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WikiSummaries(summaries: List<WikiSummary>) {
    Column(
        modifier = Modifier.padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("摘要", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        summaries.forEach { summary ->
            Text(
                text = summary.text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WikiTermsSheet(terms: List<WikiTerm>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("术语索引", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (terms.isEmpty()) {
                Text("没有可展示的术语。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                terms.forEach { term ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(term.canonicalText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(
                            text = term.kind,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun WikiRecoveryState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 28.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal fun wikiTextPreview(value: String, maxCodePoints: Int = 180): String {
    if (value.codePointCount(0, value.length) <= maxCodePoints) return value
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints)) + "..."
}
