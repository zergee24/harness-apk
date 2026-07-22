package com.harnessapk.ui.wiki

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.wiki.GeneratedPages
import com.harnessapk.wiki.H_WIKI_MIME_TYPE
import com.harnessapk.wiki.WIKI_URI_READ_PERMISSION
import com.harnessapk.wiki.WikiCapabilities
import com.harnessapk.wiki.WikiContentStats
import com.harnessapk.wiki.WikiInstallException
import com.harnessapk.wiki.WikiPackageException
import com.harnessapk.wiki.WikiPackageImportSession
import com.harnessapk.wiki.WikiPackageLoadProgress
import com.harnessapk.wiki.WikiRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiLibraryScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    pendingImportUri: String?,
    importError: String?,
    importRequestKey: Int,
    onImportRequestConsumed: () -> Unit,
    onPickerPackageSelected: (String) -> Unit,
    onImportCancelled: () -> Unit,
    onImportRejected: (String) -> Unit,
    onImportCompleted: () -> Unit,
    onOpenBrowser: (WikiRef) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importLoadProgress = remember { MutableStateFlow<WikiPackageLoadProgress?>(null) }
    val loadProgress by importLoadProgress.collectAsState()
    var importSession by remember { mutableStateOf<WikiPackageImportSession?>(null) }
    var preparedUri by remember { mutableStateOf<String?>(null) }
    var isPreparing by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var enabledForNewConversations by remember { mutableStateOf(false) }
    var selectedVersionEntry by remember { mutableStateOf<WikiLibraryEntry?>(null) }
    var versionToRemove by remember { mutableStateOf<WikiLibraryVersionUi?>(null) }
    var libraryActionError by remember { mutableStateOf<String?>(null) }
    val latestSession by rememberUpdatedState(importSession)
    val installedWikis by container.wikiRepository.observeWikis().collectAsState(initial = emptyList())
    val libraryState by produceState<WikiLibraryUiState>(
        initialValue = WikiLibraryUiState.Loading,
        installedWikis,
    ) {
        value = runCatching {
            wikiLibraryUiState(
                wikis = installedWikis,
                versionsByWiki = installedWikis.associate { wiki ->
                    wiki.id to container.wikiRepository.listVersions(wiki.id)
                },
            )
        }.getOrElse { WikiLibraryUiState.Empty }
    }
    val activeRefs = (libraryState as? WikiLibraryUiState.Content)
        ?.entries
        ?.mapNotNull { it.activeVersion?.ref }
        .orEmpty()
    val contentStats by produceState<Map<WikiRef, WikiContentStats?>>(
        initialValue = emptyMap(),
        activeRefs,
    ) {
        value = activeRefs.associateWith { ref ->
            runCatching { container.wikiContentStore.stats(ref) }.getOrNull()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            retainWikiReadPermission(context, uri)
            onPickerPackageSelected(uri.toString())
        }
    }
    val openPicker = {
        picker.launch(
            arrayOf(
                H_WIKI_MIME_TYPE,
                "application/zip",
                "application/octet-stream",
            ),
        )
    }

    DisposableEffect(container) {
        onDispose {
            latestSession?.let { session ->
                container.applicationScope.launch {
                    runCatching { container.wikiPackageImportCoordinator.discard(session) }
                }
            }
        }
    }

    LaunchedEffect(importRequestKey) {
        if (importRequestKey > 0) {
            onImportRequestConsumed()
            if (!isPreparing && !isInstalling) openPicker()
        }
    }

    LaunchedEffect(pendingImportUri) {
        val uriString = pendingImportUri
        if (uriString == null) {
            preparedUri = null
            importSession?.let { session ->
                importSession = null
                runCatching { container.wikiPackageImportCoordinator.discard(session) }
            }
            return@LaunchedEffect
        }
        if (uriString == preparedUri) return@LaunchedEffect

        importSession?.let { previous ->
            importSession = null
            runCatching { container.wikiPackageImportCoordinator.discard(previous) }
        }
        preparedUri = uriString
        isPreparing = true
        importLoadProgress.value = WikiPackageLoadProgress.Copying(copiedBytes = 0L, totalBytes = null)
        try {
            val uri = Uri.parse(uriString)
            val sourceName = withContext(Dispatchers.IO) { wikiPackageDisplayName(context, uri) }
            val sourceBytes = withContext(Dispatchers.IO) { wikiPackageSourceBytes(context, uri) }
            val session = container.wikiPackageImportCoordinator.prepareImport(
                sourceName = sourceName,
                openInputStream = {
                    requireNotNull(context.contentResolver.openInputStream(uri)) {
                        "无法读取所选知识库包"
                    }
                },
                sourceBytes = sourceBytes,
                onProgress = { progress -> importLoadProgress.value = progress },
            )
            importSession = session
            enabledForNewConversations = session.defaultEnabledForNewConversations
            if (session.isKnownPublisher) {
                isInstalling = true
                try {
                    container.wikiPackageImportCoordinator.install(
                        session = session,
                        enabledForNewConversations = session.defaultEnabledForNewConversations,
                    )
                    importSession = null
                    preparedUri = null
                    onImportCompleted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    importSession = null
                    preparedUri = null
                    onImportRejected(wikiImportErrorMessage(error))
                } finally {
                    isInstalling = false
                }
            }
        } catch (cancelled: CancellationException) {
            preparedUri = null
            throw cancelled
        } catch (error: Throwable) {
            importSession = null
            preparedUri = null
            onImportRejected(wikiImportErrorMessage(error))
        } finally {
            isPreparing = false
            importLoadProgress.value = null
        }
    }

    fun cancelImport() {
        val session = importSession
        importSession = null
        preparedUri = null
        scope.launch {
            session?.let { runCatching { container.wikiPackageImportCoordinator.discard(it) } }
            onImportCancelled()
        }
    }

    fun installImport(session: WikiPackageImportSession) {
        scope.launch {
            isInstalling = true
            try {
                container.wikiPackageImportCoordinator.install(
                    session = session,
                    enabledForNewConversations = enabledForNewConversations,
                )
                importSession = null
                preparedUri = null
                onImportCompleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                importSession = null
                preparedUri = null
                onImportRejected(wikiImportErrorMessage(error))
            } finally {
                isInstalling = false
            }
        }
    }

    fun activateVersion(version: WikiLibraryVersionUi) {
        scope.launch {
            libraryActionError = runCatching {
                container.wikiRepository.activate(version.ref)
                null
            }.getOrElse(::wikiLibraryActionErrorMessage)
        }
    }

    fun setDefaultScope(version: WikiLibraryVersionUi, enabled: Boolean) {
        scope.launch {
            libraryActionError = runCatching {
                container.wikiRepository.setEnabledForNewConversations(version.ref, enabled)
                null
            }.getOrElse(::wikiLibraryActionErrorMessage)
        }
    }

    fun removeVersion(version: WikiLibraryVersionUi) {
        scope.launch {
            libraryActionError = runCatching {
                container.wikiRepository.removeVersion(version.ref)
                selectedVersionEntry = null
                null
            }.getOrElse(::wikiLibraryActionErrorMessage)
            versionToRemove = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (isPreparing || isInstalling) {
            WikiPackageLoadIndicator(
                progress = loadProgress,
                installing = isInstalling,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            WikiLibraryContent(
                state = libraryState,
                contentStats = contentStats,
                errorText = libraryActionError ?: importError,
                onRequestImport = openPicker,
                onOpenBrowser = onOpenBrowser,
                onOpenVersions = { selectedVersionEntry = it },
                onSetDefaultScope = ::setDefaultScope,
            )
        }
    }

    importSession
        ?.takeIf { !it.isKnownPublisher && !isPreparing && !isInstalling }
        ?.let { session ->
            WikiTrustSheet(
                session = session,
                enabledForNewConversations = enabledForNewConversations,
                onEnabledForNewConversationsChange = { enabledForNewConversations = it },
                onDismiss = ::cancelImport,
                onInstall = { installImport(session) },
            )
        }

    selectedVersionEntry?.let { entry ->
        WikiVersionSheet(
            entry = entry,
            onDismiss = { selectedVersionEntry = null },
            onActivate = ::activateVersion,
            onRemove = { versionToRemove = it },
        )
    }
    versionToRemove?.let { version ->
        AlertDialog(
            onDismissRequest = { versionToRemove = null },
            title = { Text("删除 v${version.ref.version}？") },
            text = { Text("已被会话引用的版本不会被删除。") },
            confirmButton = {
                TextButton(onClick = { removeVersion(version) }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { versionToRemove = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun WikiLibraryContent(
    state: WikiLibraryUiState,
    contentStats: Map<WikiRef, WikiContentStats?>,
    errorText: String?,
    onRequestImport: () -> Unit,
    onOpenBrowser: (WikiRef) -> Unit,
    onOpenVersions: (WikiLibraryEntry) -> Unit,
    onSetDefaultScope: (WikiLibraryVersionUi, Boolean) -> Unit,
) {
    when (state) {
        WikiLibraryUiState.Loading -> Box(Modifier.fillMaxSize()) {
            LinearProgressIndicator(modifier = Modifier
                .align(Alignment.Center)
                .width(176.dp))
        }
        WikiLibraryUiState.Empty -> WikiLibraryEmptyState(
            errorText = errorText,
            onRequestImport = onRequestImport,
            modifier = Modifier.fillMaxSize(),
        )
        is WikiLibraryUiState.Content -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            errorText?.let { message ->
                item(key = "library-error") {
                    Text(
                        text = message,
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.entries, key = WikiLibraryEntry::wikiId) { entry ->
                WikiLibraryRow(
                    entry = entry,
                    stats = entry.activeVersion?.ref?.let(contentStats::get),
                    onOpenBrowser = onOpenBrowser,
                    onOpenVersions = { onOpenVersions(entry) },
                    onSetDefaultScope = onSetDefaultScope,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WikiLibraryRow(
    entry: WikiLibraryEntry,
    stats: WikiContentStats?,
    onOpenBrowser: (WikiRef) -> Unit,
    onOpenVersions: () -> Unit,
    onSetDefaultScope: (WikiLibraryVersionUi, Boolean) -> Unit,
) {
    val activeVersion = entry.activeVersion
    var menuExpanded by remember(entry.wikiId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = activeVersion?.isReady == true) {
                activeVersion?.let { version -> onOpenBrowser(version.ref) }
            }
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                entry.description.takeIf(String::isNotBlank)?.let { description ->
                    Text(
                        text = description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "${entry.title} 更多操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("浏览原文") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
                        enabled = activeVersion?.isReady == true,
                        onClick = {
                            menuExpanded = false
                            activeVersion?.let { version -> onOpenBrowser(version.ref) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("管理版本") },
                        onClick = {
                            menuExpanded = false
                            onOpenVersions()
                        },
                    )
                }
            }
        }
        val versionLabel = activeVersion?.let { version ->
            "当前 v${version.ref.version} · ${formatWikiPackageSize(version.sizeBytes)}"
        } ?: "未选择可用版本"
        Text(
            text = versionLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        stats?.let {
            Text(
                text = "${it.sourceChunkCount} 段原文 · ${it.documentCount} 份资料",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        activeVersion?.let { version ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "用于新会话",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = version.enabledForNewConversations,
                    enabled = version.isReady,
                    onCheckedChange = { enabled -> onSetDefaultScope(version, enabled) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WikiVersionSheet(
    entry: WikiLibraryEntry,
    onDismiss: () -> Unit,
    onActivate: (WikiLibraryVersionUi) -> Unit,
    onRemove: (WikiLibraryVersionUi) -> Unit,
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
            Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            entry.versions.forEach { version ->
                WikiVersionRow(
                    version = version,
                    isActive = entry.activeVersion?.ref == version.ref,
                    onActivate = { onActivate(version) },
                    onRemove = { onRemove(version) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WikiVersionRow(
    version: WikiLibraryVersionUi,
    isActive: Boolean,
    onActivate: () -> Unit,
    onRemove: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append("v${version.ref.version}")
                    if (isActive) append(" · 当前版本")
                    if (!version.isReady) append(" · 不可用")
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(version.publisherFingerprint)) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制发布者指纹")
            }
        }
        Text(
            text = "${formatWikiPackageSize(version.sizeBytes)} · ${version.publisherKeyId}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        version.invalidReason?.takeIf(String::isNotBlank)?.let { reason ->
            Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isActive && version.isReady) {
                TextButton(onClick = onActivate) { Text("设为当前版本") }
            }
            TextButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("删除")
            }
        }
    }
}

@Composable
private fun WikiLibraryEmptyState(
    errorText: String?,
    onRequestImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        errorText?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = "还没有导入 Wiki 知识库",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "导入 .hwiki 后，可离线浏览原文和资料目录。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRequestImport) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("导入 .hwiki")
        }
    }
}

@Composable
private fun WikiPackageLoadIndicator(
    progress: WikiPackageLoadProgress?,
    installing: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = progress?.let(::wikiPackageLoadFraction)
    Column(
        modifier = modifier
            .width(272.dp)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when {
                installing -> "正在安装知识库"
                progress != null -> wikiPackageLoadTitle(progress)
                else -> "正在准备知识库"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when {
                installing -> "正在写入应用私有存储"
                progress != null -> wikiPackageLoadDetail(progress)
                else -> "请稍候"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WikiTrustSheet(
    session: WikiPackageImportSession,
    enabledForNewConversations: Boolean,
    onEnabledForNewConversationsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val manifest = session.inspection.manifest
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "确认安装知识库",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "这是首次使用该发布者的资料包，请核对来源后再安装。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            WikiTrustField("名称", manifest.title)
            WikiTrustField("Wiki ID", manifest.ref.wikiId)
            WikiTrustField("版本", "v${manifest.ref.version}")
            WikiTrustField("发布者", manifest.publisherName)
            WikiTrustField("密钥 ID", manifest.publisherKeyId)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("发布者指纹", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = session.inspection.publisherFingerprint.hex,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(session.inspection.publisherFingerprint.hex))
                        },
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制发布者指纹")
                    }
                }
            }
            WikiTrustField("压缩包大小", formatWikiPackageSize(session.inspection.archiveSizeBytes))
            WikiTrustField("展开后大小", formatWikiPackageSize(session.inspection.contentSizeBytes))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("可用能力", style = MaterialTheme.typography.labelLarge)
                wikiCapabilityLabels(manifest.capabilities).forEach { label ->
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("用于新会话", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "新建会话默认可选择此知识库。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = enabledForNewConversations,
                    onCheckedChange = onEnabledForNewConversationsChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = onInstall) { Text("安装") }
            }
        }
    }
}

@Composable
private fun WikiTrustField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal fun wikiPackageLoadFraction(progress: WikiPackageLoadProgress): Float? = when (progress) {
    is WikiPackageLoadProgress.Copying -> progress.totalBytes
        ?.takeIf { it > 0L }
        ?.let { total -> (progress.copiedBytes.toFloat() / total).coerceIn(0f, 1f) }
    WikiPackageLoadProgress.Validating -> null
}

internal fun wikiPackageLoadTitle(progress: WikiPackageLoadProgress): String = when (progress) {
    is WikiPackageLoadProgress.Copying -> "正在读取知识库包"
    WikiPackageLoadProgress.Validating -> "正在校验知识库包"
}

internal fun wikiPackageLoadDetail(progress: WikiPackageLoadProgress): String = when (progress) {
    is WikiPackageLoadProgress.Copying -> progress.totalBytes
        ?.takeIf { it > 0L }
        ?.let { total -> "已读取 ${formatWikiPackageSize(progress.copiedBytes)} / ${formatWikiPackageSize(total)}" }
        ?: "已读取 ${formatWikiPackageSize(progress.copiedBytes)}"
    WikiPackageLoadProgress.Validating -> "正在核验签名、清单和资料数据库"
}

internal fun formatWikiPackageSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun retainWikiReadPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, WIKI_URI_READ_PERMISSION)
    }
}

private fun wikiPackageDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull() ?: uri.lastPathSegment ?: "知识库包"

private fun wikiPackageSourceBytes(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
        descriptor.length.takeIf { it > 0L }
    }
}.getOrNull()

private fun wikiImportErrorMessage(error: Throwable): String = when (error) {
    is WikiPackageException,
    is WikiInstallException,
    -> error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(180)?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "知识库包无法导入"
    else -> "知识库包无法导入，请确认文件完整后重试"
}

private fun wikiLibraryActionErrorMessage(error: Throwable): String = when (error) {
    is WikiInstallException -> error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(180)?.trim()
        ?.takeIf(String::isNotBlank)
        ?: "无法完成知识库操作"
    else -> "无法完成知识库操作，请稍后重试。"
}

private fun wikiCapabilityLabels(capabilities: WikiCapabilities): List<String> = buildList {
    if (capabilities.sourceHierarchy) add("原文目录浏览")
    if (capabilities.sourceSearch) add("原文检索")
    if (capabilities.hierarchicalSummaries) add("层级摘要")
    if (capabilities.termIndex) add("术语与别名")
    if (capabilities.temporalAnnotations) add("时间标注")
    if (capabilities.crossWikiLinks) add("跨资料链接")
    when (capabilities.generatedPages) {
        GeneratedPages.NONE -> Unit
        GeneratedPages.PARTIAL -> add("部分生成页面")
        GeneratedPages.COMPLETE -> add("完整生成页面")
    }
}
