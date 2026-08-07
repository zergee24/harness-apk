package com.harnessapk.ui.search

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.search.LocalSearchDocumentType
import com.harnessapk.search.LocalSearchResult
import com.harnessapk.search.LocalSearchTarget
import com.harnessapk.search.target
import kotlinx.coroutines.delay

@Composable
fun GlobalSearchScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenMessage: (conversationId: String, messageId: String) -> Unit,
    onOpenConversation: (conversationId: String) -> Unit,
    onOpenProject: (projectId: String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LocalSearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val current = query.trim()
        if (current.isBlank()) {
            results = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(220)
        results = container.localSearchRepository.search(current)
        loading = false
    }

    GlobalSearchContent(
        query = query,
        onQueryChange = { query = it },
        results = results,
        loading = loading,
        contentPadding = contentPadding,
        onResult = { result ->
            when (val target = result.target()) {
                is LocalSearchTarget.ConversationMessage -> onOpenMessage(target.conversationId, target.messageId)
                is LocalSearchTarget.Conversation -> onOpenConversation(target.conversationId)
                is LocalSearchTarget.Project -> onOpenProject(target.projectId)
                null -> Unit
            }
        },
    )
}

@Composable
internal fun GlobalSearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<LocalSearchResult>,
    loading: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    onResult: (LocalSearchResult) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("搜索") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results, key = LocalSearchResult::id) { result ->
                ListItem(
                    modifier = Modifier.clickable { onResult(result) },
                    overlineContent = { Text(result.type.label()) },
                    headlineContent = {
                        Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = result.snippet.takeIf(String::isNotBlank)?.let { snippet ->
                        {
                            Text(
                                text = snippet,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

private fun LocalSearchDocumentType.label(): String = when (this) {
    LocalSearchDocumentType.CONVERSATION -> "会话"
    LocalSearchDocumentType.MESSAGE -> "消息"
    LocalSearchDocumentType.MESSAGE_SOURCE -> "引用资料"
    LocalSearchDocumentType.PROJECT_NAME -> "项目"
}
