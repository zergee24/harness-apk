package com.harnessapk.ui.wiki

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.wiki.WikiAnnotation
import com.harnessapk.wiki.WikiChunk
import com.harnessapk.wiki.WikiContentUnavailableException
import com.harnessapk.wiki.WikiDocument
import com.harnessapk.wiki.WikiLink
import com.harnessapk.wiki.WikiManifest
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiSection
import kotlinx.coroutines.CancellationException

@Composable
fun WikiSourceReaderScreen(
    container: AppContainer,
    ref: WikiRef,
    chunkId: String,
    highlightText: String? = null,
    contentPadding: PaddingValues,
    onOpenSource: (String) -> Unit,
) {
    var state by remember(ref, chunkId) { mutableStateOf<WikiSourceReaderUiState>(WikiSourceReaderUiState.Loading) }
    var showRelatedInfo by remember(ref, chunkId) { mutableStateOf(false) }

    LaunchedEffect(ref, chunkId) {
        state = WikiSourceReaderUiState.Loading
        try {
            val manifest = container.wikiRepository.manifestFor(ref)
                ?: throw WikiContentUnavailableException("Wiki 版本不存在")
            val chunk = container.wikiContentStore.findChunk(ref, chunkId)
                ?: throw WikiContentUnavailableException("原文位置不存在")
            val section = container.wikiContentStore.findSection(ref, chunk.sectionId)
            val document = section?.let { container.wikiContentStore.findDocument(ref, it.documentId) }
            val neighbors = container.wikiContentStore.chunkNeighbors(ref, chunkId)
            val annotations = if (manifest.capabilities.temporalAnnotations) {
                container.wikiContentStore.annotationsFor(ref, "chunk", chunkId)
            } else {
                emptyList()
            }
            val links = if (manifest.capabilities.crossWikiLinks) {
                container.wikiContentStore.linksFrom(ref, "chunk", chunkId)
            } else {
                emptyList()
            }
            state = WikiSourceReaderUiState.Content(
                manifest = manifest,
                chunk = chunk,
                document = document,
                section = section,
                previousChunkId = neighbors?.previous?.id,
                nextChunkId = neighbors?.next?.id,
                annotations = annotations,
                links = links,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            state = WikiSourceReaderUiState.Error(wikiBrowserErrorMessage(failure))
        }
    }

    when (val current = state) {
        WikiSourceReaderUiState.Loading -> Box(
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
        is WikiSourceReaderUiState.Error -> WikiRecoveryState(
            current.message,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        )
        is WikiSourceReaderUiState.Content -> WikiSourceReaderContent(
            state = current,
            contentPadding = contentPadding,
            highlightText = highlightText,
            onOpenSource = onOpenSource,
            onShowRelatedInfo = { showRelatedInfo = true },
        )
    }

    if (showRelatedInfo && state is WikiSourceReaderUiState.Content) {
        val current = state as WikiSourceReaderUiState.Content
        WikiRelatedInfoSheet(
            annotations = current.annotations,
            links = current.links,
            onDismiss = { showRelatedInfo = false },
        )
    }
}

private sealed interface WikiSourceReaderUiState {
    data object Loading : WikiSourceReaderUiState

    data class Content(
        val manifest: WikiManifest,
        val chunk: WikiChunk,
        val document: WikiDocument?,
        val section: WikiSection?,
        val previousChunkId: String?,
        val nextChunkId: String?,
        val annotations: List<WikiAnnotation>,
        val links: List<WikiLink>,
    ) : WikiSourceReaderUiState

    data class Error(val message: String) : WikiSourceReaderUiState
}

@Composable
private fun WikiSourceReaderContent(
    state: WikiSourceReaderUiState.Content,
    contentPadding: PaddingValues,
    highlightText: String?,
    onOpenSource: (String) -> Unit,
    onShowRelatedInfo: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val sectionPath = state.section?.path?.takeIf(String::isNotBlank) ?: "未标注章节"
    val highlightColor = MaterialTheme.colorScheme.secondaryContainer
    val sourceText = remember(state.chunk.originalText, highlightText, highlightColor) {
        highlightedSourceText(
            originalText = state.chunk.originalText,
            highlightedText = highlightText,
            highlightColor = highlightColor,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.manifest.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            text = "v${state.manifest.ref.version}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        state.document?.let { document ->
            Text(document.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        }
        Text(
            text = sectionPath,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(state.chunk.locator.label, style = MaterialTheme.typography.labelLarge)
        HorizontalDivider()
        SelectionContainer {
            Text(
                text = sourceText,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                enabled = state.previousChunkId != null,
                onClick = { state.previousChunkId?.let(onOpenSource) },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一段原文")
            }
            IconButton(
                enabled = state.nextChunkId != null,
                onClick = { state.nextChunkId?.let(onOpenSource) },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一段原文")
            }
            Box(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            "${state.document?.title ?: state.manifest.title} · $sectionPath\n" +
                                "${state.chunk.locator.label}\n${state.chunk.originalText}",
                        ),
                    )
                },
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制原文引用")
            }
        }
        if (state.annotations.isNotEmpty() || state.links.isNotEmpty()) {
            TextButton(onClick = onShowRelatedInfo) { Text("查看关联信息") }
        }
    }
}

private fun highlightedSourceText(
    originalText: String,
    highlightedText: String?,
    highlightColor: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    val target = highlightedText?.takeIf(String::isNotBlank) ?: return AnnotatedString(originalText)
    val start = originalText.indexOf(target)
    if (start < 0) return AnnotatedString(originalText)
    val endExclusive = start + target.length
    return buildAnnotatedString {
        append(originalText, 0, start)
        withStyle(SpanStyle(background = highlightColor)) {
            append(originalText, start, endExclusive)
        }
        append(originalText, endExclusive, originalText.length)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WikiRelatedInfoSheet(
    annotations: List<WikiAnnotation>,
    links: List<WikiLink>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("关联信息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            annotations.forEach { annotation ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(annotation.kind, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = wikiTextPreview(annotation.valueJson, maxCodePoints = 200),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
            }
            links.forEach { link ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(link.kind, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${link.targetNamespace} · ${link.targetId}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
