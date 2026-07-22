package com.harnessapk.ui.wiki

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.wiki.WikiRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun WikiSearchScreen(
    container: AppContainer,
    ref: WikiRef,
    contentPadding: PaddingValues,
    onOpenSource: (String) -> Unit,
) {
    var query by rememberSaveable(ref.wikiId, ref.version) { mutableStateOf("") }
    var state by remember(ref) { mutableStateOf<WikiSearchUiState>(WikiSearchUiState.Idle) }

    LaunchedEffect(ref, query) {
        val requestedQuery = query.trim()
        if (requestedQuery.isEmpty()) {
            state = WikiSearchUiState.Idle
            return@LaunchedEffect
        }
        delay(220)
        state = WikiSearchUiState.Loading
        try {
            val hits = container.wikiContentStore.searchSources(ref, requestedQuery)
            val documents = container.wikiContentStore.listDocuments(ref).associateBy { it.id }
            val sections = buildMap {
                hits.map { it.chunk.sectionId }.distinct().forEach { sectionId ->
                    container.wikiContentStore.findSection(ref, sectionId)?.let { section ->
                        put(section.id, section)
                    }
                }
            }
            state = WikiSearchUiState.Content(
                groupWikiSearchHits(
                    hits = hits,
                    documentsById = documents,
                    sectionsById = sections,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            state = WikiSearchUiState.Error(wikiBrowserErrorMessage(failure))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索原文") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Search, contentDescription = null) },
        )
        when (val current = state) {
            WikiSearchUiState.Idle -> Text(
                text = "输入关键词开始检索。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            WikiSearchUiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is WikiSearchUiState.Error -> WikiRecoveryState(current.message, Modifier.fillMaxSize())
            is WikiSearchUiState.Content -> {
                if (current.groups.isEmpty()) {
                    Text(
                        text = "没有找到相关原文。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        current.groups.forEach { group ->
                            item(key = "group-${group.documentId}-${group.sectionId}") {
                                Column(modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                                    Text(group.documentTitle, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = group.sectionPath,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            items(group.hits, key = { hit -> hit.chunkId }) { hit ->
                                WikiSearchHitRow(
                                    hit = hit,
                                    query = query,
                                    onOpen = { onOpenSource(hit.chunkId) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface WikiSearchUiState {
    data object Idle : WikiSearchUiState

    data object Loading : WikiSearchUiState

    data class Content(val groups: List<WikiSearchGroup>) : WikiSearchUiState

    data class Error(val message: String) : WikiSearchUiState
}

@Composable
private fun WikiSearchHitRow(
    hit: com.harnessapk.wiki.WikiSourceHit,
    query: String,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = wikiHighlightSearchText(wikiTextPreview(hit.originalText), query),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "${hit.locator.label} · ${hit.matches.joinToString("、") { it.label }}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun wikiHighlightSearchText(text: String, query: String): AnnotatedString {
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    return remember(text, query, highlight) {
        val needle = query.trim()
        if (needle.isEmpty()) return@remember AnnotatedString(text)
        buildAnnotatedString {
            var cursor = 0
            while (cursor < text.length) {
                val found = text.indexOf(needle, startIndex = cursor, ignoreCase = true)
                if (found < 0) {
                    append(text.substring(cursor))
                    break
                }
                append(text.substring(cursor, found))
                withStyle(SpanStyle(background = highlight)) {
                    append(text.substring(found, found + needle.length))
                }
                cursor = found + needle.length
            }
        }
    }
}
