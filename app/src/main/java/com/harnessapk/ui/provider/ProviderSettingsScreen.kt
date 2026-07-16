package com.harnessapk.ui.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.BuildConfig
import com.harnessapk.common.AppContainer
import com.harnessapk.provider.CapabilitySource
import com.harnessapk.provider.DEFAULT_OPEN_AI_REASONING_EFFORT
import com.harnessapk.provider.ModelConfig
import com.harnessapk.provider.ModelCapabilityResolver
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.OPEN_AI_REASONING_EFFORT_OPTIONS
import com.harnessapk.provider.ProviderCapabilityCatalog
import com.harnessapk.provider.ProviderDraft
import com.harnessapk.provider.ProviderProfile
import com.harnessapk.provider.ProviderTemplate
import com.harnessapk.provider.ProviderTemplates
import com.harnessapk.provider.ResolvedModelCapability
import com.harnessapk.provider.defaultModelConfig
import com.harnessapk.provider.parseProviderCapabilityCatalogJson
import com.harnessapk.storage.DefaultModelPreference
import com.harnessapk.storage.ProviderCapabilityCatalogSnapshot
import com.harnessapk.ui.model.resolveModelSelection
import com.harnessapk.ui.model.selectableModelsForProvider
import kotlinx.coroutines.launch

@Composable
fun ProviderSettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
) {
    val providers by container.providerRepository.observeEnabled().collectAsState(initial = emptyList())
    val defaultModelPreference by container.settingsStore.defaultModelPreference.collectAsState(
        initial = DefaultModelPreference(),
    )
    val catalogSnapshot by container.settingsStore.providerCapabilityCatalogSnapshot.collectAsState(
        initial = ProviderCapabilityCatalogSnapshot(),
    )
    val scope = rememberCoroutineScope()
    val defaultTemplate = ProviderTemplates.default
    val remoteCatalog = remember(catalogSnapshot.rawJson) {
        catalogSnapshot.rawJson?.let { rawJson ->
            runCatching { parseProviderCapabilityCatalogJson(rawJson, container.json) }.getOrNull()
        }
    }
    val capabilityResolver = remember(remoteCatalog, providers) {
        ModelCapabilityResolver(remoteCatalog = remoteCatalog)
    }
    var defaultProviderId by remember { mutableStateOf<String?>(null) }
    var defaultModelName by remember { mutableStateOf("") }
    var defaultStatus by remember { mutableStateOf<String?>(null) }
    var catalogStatus by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(defaultTemplate.name) }
    var baseUrl by remember { mutableStateOf(defaultTemplate.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(defaultTemplate.defaultModel) }
    var modelConfigs by remember { mutableStateOf(defaultTemplate.modelConfigs) }
    var visionModel by remember { mutableStateOf(defaultTemplate.defaultVisionModel.orEmpty()) }
    var supportsVision by remember { mutableStateOf(defaultTemplate.supportsVision) }
    var nativeWebSearchMode by remember { mutableStateOf(defaultTemplate.nativeWebSearchMode) }
    var status by remember { mutableStateOf<String?>(null) }
    var editingProviderId by remember { mutableStateOf<String?>(null) }
    var providerToDelete by remember { mutableStateOf<ProviderProfile?>(null) }

    fun resetForm() {
        name = defaultTemplate.name
        baseUrl = defaultTemplate.baseUrl
        apiKey = ""
        model = defaultTemplate.defaultModel
        modelConfigs = modelConfigsForTemplate(defaultTemplate, remoteCatalog)
        visionModel = defaultTemplate.defaultVisionModel.orEmpty()
        supportsVision = defaultTemplate.supportsVision
        nativeWebSearchMode = defaultTemplate.nativeWebSearchMode
        editingProviderId = null
    }

    fun applyTemplate(template: ProviderTemplate) {
        name = template.name
        baseUrl = template.baseUrl
        model = template.defaultModel
        modelConfigs = modelConfigsForTemplate(template, remoteCatalog)
        visionModel = template.defaultVisionModel.orEmpty()
        supportsVision = template.supportsVision
        nativeWebSearchMode = template.nativeWebSearchMode
        editingProviderId = null
        apiKey = ""
        status = null
    }

    fun editProvider(provider: ProviderProfile) {
        editingProviderId = provider.id
        name = provider.name
        baseUrl = provider.baseUrl
        apiKey = ""
        model = provider.defaultModel
        modelConfigs = provider.modelConfigs
        visionModel = provider.defaultVisionModel.orEmpty()
        supportsVision = provider.supportsVision
        nativeWebSearchMode = provider.nativeWebSearchMode
        status = "正在编辑 ${provider.name}，API Key 留空会沿用原 Key。"
    }

    LaunchedEffect(providers, defaultModelPreference) {
        val selection = resolveModelSelection(
            providers = providers,
            currentProviderId = defaultProviderId,
            currentModel = defaultModelName,
            preferredProviderId = defaultModelPreference.providerId,
            preferredModel = defaultModelPreference.model,
        )
        defaultProviderId = selection.providerId
        defaultModelName = selection.model
    }

    providerToDelete?.let { provider ->
        AlertDialog(
            onDismissRequest = { providerToDelete = null },
            title = { Text("删除供应商") },
            text = { Text("确认删除 ${provider.name}？删除后需要重新填写 API Key 才能使用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                container.providerRepository.deleteProvider(provider.id)
                                if (defaultModelPreference.providerId == provider.id) {
                                    container.settingsStore.clearDefaultModelPreference()
                                }
                            }.onSuccess {
                                if (editingProviderId == provider.id) resetForm()
                                status = "已删除 ${provider.name}"
                            }.onFailure {
                                status = it.message
                            }
                            providerToDelete = null
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { providerToDelete = null }) { Text("取消") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            DefaultModelCard(
                providers = providers,
                selectedProviderId = defaultProviderId,
                selectedModel = defaultModelName,
                modelOptions = defaultModelOptionsForProvider(providers, defaultProviderId, capabilityResolver),
                status = defaultStatus,
                catalogVersion = catalogSnapshot.catalogVersion ?: "bundled",
                catalogStatus = catalogStatus ?: catalogSnapshot.errorMessage,
                onSelectProvider = { provider ->
                    defaultProviderId = provider.id
                    defaultModelName = capabilityResolver.selectableModels(provider).firstOrNull()?.modelId.orEmpty()
                    defaultStatus = null
                },
                onSelectModel = {
                    defaultModelName = it
                    defaultStatus = null
                },
                onSave = {
                    val providerId = defaultProviderId
                    val modelName = defaultModelName
                    if (providerId == null || modelName.isBlank()) {
                        defaultStatus = "请先保存供应商并选择模型"
                    } else {
                        scope.launch {
                            runCatching {
                                container.settingsStore.setDefaultModelPreference(providerId, modelName)
                            }.onSuccess {
                                defaultStatus = "已设置默认模型"
                            }.onFailure {
                                defaultStatus = it.message
                            }
                        }
                    }
                },
                onRefreshCatalog = {
                    scope.launch {
                        catalogStatus = "正在更新模型清单..."
                        runCatching {
                            val fetched = container.providerCapabilityCatalogClient
                                .fetchDocument(BuildConfig.PROVIDER_CATALOG_URL)
                            container.settingsStore.setProviderCapabilityCatalog(
                                rawJson = fetched.rawJson,
                                catalogVersion = fetched.catalog.catalogVersion,
                                sha256 = fetched.sha256,
                                fetchedAt = System.currentTimeMillis(),
                            )
                            fetched.catalog.catalogVersion
                        }.onSuccess { version ->
                            catalogStatus = "模型清单已更新：$version"
                        }.onFailure {
                            val message = it.message ?: "模型清单更新失败"
                            container.settingsStore.setProviderCapabilityCatalogError(message)
                            catalogStatus = message
                        }
                    }
                },
            )
        }

        item {
            ProviderFormCard(
                editing = editingProviderId != null,
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                modelConfigs = modelConfigs,
                visionModel = visionModel,
                supportsVision = supportsVision,
                nativeWebSearchMode = nativeWebSearchMode,
                status = status,
                onNameChange = { name = it },
                onBaseUrlChange = { baseUrl = it },
                onApiKeyChange = { apiKey = it },
                onModelChange = { model = it },
                onModelConfigsChange = { modelConfigs = it },
                onVisionModelChange = { visionModel = it },
                onSupportsVisionChange = { supportsVision = it },
                onNativeWebSearchModeChange = { nativeWebSearchMode = it },
                onApplyTemplate = ::applyTemplate,
                onSave = {
                    scope.launch {
                        val draft = ProviderDraft(
                            name = name,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            defaultModel = model,
                            defaultVisionModel = visionModel.ifBlank { null },
                            supportsVision = supportsVision,
                            nativeWebSearchMode = nativeWebSearchMode,
                            availableModels = modelConfigs.map { it.id },
                            modelConfigs = modelConfigs,
                        )
                        runCatching {
                            val providerId = editingProviderId
                            if (providerId == null) {
                                container.providerRepository.saveProvider(draft)
                            } else {
                                container.providerRepository.updateProvider(providerId, draft)
                            }
                        }.onSuccess {
                            status = if (editingProviderId == null) {
                                "已保存，Key 已加密保存在本机"
                            } else {
                                "已保存修改"
                            }
                            resetForm()
                        }.onFailure {
                            status = it.message
                        }
                    }
                },
                onCancelEdit = {
                    resetForm()
                    status = null
                },
            )
        }

        item {
            Text(
                text = "已保存供应商",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (providers.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = "还没有保存的模型供应商。先选择模板，填入 Key 后保存。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(providers, key = { it.id }) { provider ->
                ProviderRow(
                    provider = provider,
                    capabilities = capabilityResolver.selectableModels(provider),
                    onEdit = { editProvider(provider) },
                    onDelete = { providerToDelete = provider },
                )
            }
        }
    }
}

@Composable
private fun DefaultModelCard(
    providers: List<ProviderProfile>,
    selectedProviderId: String?,
    selectedModel: String,
    modelOptions: List<String>,
    status: String?,
    catalogVersion: String,
    catalogStatus: String?,
    onSelectProvider: (ProviderProfile) -> Unit,
    onSelectModel: (String) -> Unit,
    onSave: () -> Unit,
    onRefreshCatalog: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "默认模型",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "新对话会优先使用这里选择的供应商和模型。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "模型清单：$catalogVersion",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        catalogStatus?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(onClick = onRefreshCatalog) {
                        Icon(Icons.Outlined.Sync, contentDescription = null)
                        Text("更新", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (providers.isEmpty()) {
                Text(
                    text = "先保存一个供应商后再设置默认模型。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("供应商", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { provider ->
                        FilterChip(
                            selected = provider.id == selectedProviderId,
                            onClick = { onSelectProvider(provider) },
                            label = { Text(provider.name) },
                        )
                    }
                }
                Text("模型", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    modelOptions.forEach { model ->
                        FilterChip(
                            selected = model == selectedModel,
                            onClick = { onSelectModel(model) },
                            label = {
                                Text(
                                    text = model,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSave,
                ) {
                    Text("保存默认模型")
                }
            }
            status?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ProviderFormCard(
    editing: Boolean,
    name: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    modelConfigs: List<ModelConfig>,
    visionModel: String,
    supportsVision: Boolean,
    nativeWebSearchMode: NativeWebSearchMode,
    status: String?,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onModelConfigsChange: (List<ModelConfig>) -> Unit,
    onVisionModelChange: (String) -> Unit,
    onSupportsVisionChange: (Boolean) -> Unit,
    onNativeWebSearchModeChange: (NativeWebSearchMode) -> Unit,
    onApplyTemplate: (ProviderTemplate) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (editing) "编辑供应商" else "新增供应商",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Key 只加密保存在本机，聊天时直接请求你配置的 API 地址。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProviderTemplates.defaults.forEach { template ->
                    FilterChip(
                        selected = name == template.name && !editing,
                        onClick = { onApplyTemplate(template) },
                        label = { Text(template.name) },
                    )
                }
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = onNameChange,
                label = { Text("名称") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("Base URL") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(if (editing) "API Key（留空沿用原 Key）" else "API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = model,
                onValueChange = onModelChange,
                label = { Text("默认文本模型") },
                singleLine = true,
            )
            ModelConfigListEditor(
                providerName = name,
                modelConfigs = modelConfigs,
                onModelConfigsChange = onModelConfigsChange,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = visionModel,
                onValueChange = onVisionModelChange,
                label = { Text("图片模型") },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("支持图片", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("开启后可发送截图", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = supportsVision, onCheckedChange = onSupportsVisionChange)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("模型内搜索", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        text = "打开后会话联网优先随模型请求启用搜索",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isNativeWebSearchEnabled(nativeWebSearchMode),
                    onCheckedChange = { enabled ->
                        onNativeWebSearchModeChange(
                            nativeWebSearchModeForSwitch(providerName = name, enabled = enabled),
                        )
                    },
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSave,
            ) {
                Icon(Icons.Outlined.Key, contentDescription = null)
                Text(if (editing) "保存修改" else "保存供应商", modifier = Modifier.padding(start = 8.dp))
            }
            if (editing) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancelEdit,
                ) { Text("取消编辑") }
            }
            status?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ModelConfigListEditor(
    providerName: String,
    modelConfigs: List<ModelConfig>,
    onModelConfigsChange: (List<ModelConfig>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("可选模型", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "每个模型独立维护上下文和自动压缩比例",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { onModelConfigsChange(appendModelConfig(modelConfigs, providerName)) },
            ) { Text("添加") }
        }
        modelConfigs.forEachIndexed { index, config ->
            ModelConfigEditorRow(
                config = config,
                providerName = providerName,
                canDelete = modelConfigs.size > 1,
                onChange = { next ->
                    onModelConfigsChange(updateModelConfigAt(modelConfigs, index) { next })
                },
                onDelete = {
                    onModelConfigsChange(removeModelConfigAt(modelConfigs, index))
                },
            )
        }
    }
}

@Composable
private fun ModelConfigEditorRow(
    config: ModelConfig,
    providerName: String,
    canDelete: Boolean,
    onChange: (ModelConfig) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = config.id,
                    onValueChange = {
                        onChange(config.copy(id = it))
                    },
                    label = { Text("模型") },
                    singleLine = true,
                )
                IconButton(
                    enabled = canDelete,
                    onClick = onDelete,
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除模型")
                }
            }
            ModelConfigDataBar(
                label = "上下文",
                valueText = config.contextWindowTokens.toCompactTokenText(),
                progress = modelConfigDataBarProgress(config.contextWindowTokens, MAX_CONTEXT_WINDOW_TOKENS),
            )
            Slider(
                value = config.contextWindowTokens.coerceIn(MIN_CONTEXT_WINDOW_TOKENS, MAX_CONTEXT_WINDOW_TOKENS)
                    .toFloat(),
                onValueChange = {
                    onChange(config.copy(contextWindowTokens = it.toInt().roundToThousands()))
                },
                valueRange = MIN_CONTEXT_WINDOW_TOKENS.toFloat()..MAX_CONTEXT_WINDOW_TOKENS.toFloat(),
            )
            ModelConfigDataBar(
                label = "自动压缩",
                valueText = "${config.compressionThresholdPercent.coerceIn(1, 95)}%",
                progress = modelConfigDataBarProgress(config.compressionThresholdPercent, 95),
            )
            Slider(
                value = config.compressionThresholdPercent.coerceIn(1, 95).toFloat(),
                onValueChange = {
                    onChange(config.copy(compressionThresholdPercent = it.toInt().coerceIn(1, 95)))
                },
                valueRange = 1f..95f,
            )
            Text("模型能力", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            ModelCapabilitySwitchRow(
                label = "图片输入",
                checked = config.inputModalities.orEmpty().contains("image"),
                onCheckedChange = { enabled ->
                    onChange(updateInputModality(config, modality = "image", enabled = enabled))
                },
            )
            ModelCapabilitySwitchRow(
                label = "音频输入",
                checked = config.inputModalities.orEmpty().contains("audio"),
                onCheckedChange = { enabled ->
                    onChange(updateInputModality(config, modality = "audio", enabled = enabled))
                },
            )
            ModelCapabilitySwitchRow(
                label = "推理强度",
                checked = !config.reasoningEffortOptions.isNullOrEmpty(),
                onCheckedChange = { enabled ->
                    onChange(updateReasoningCapability(config, enabled))
                },
            )
            ModelCapabilitySwitchRow(
                label = "模型内搜索",
                checked = config.webSearchMode != null && config.webSearchMode != NativeWebSearchMode.DISABLED,
                onCheckedChange = { enabled ->
                    onChange(updateModelWebSearchMode(config, providerName, enabled))
                },
            )
            ModelCapabilitySwitchRow(
                label = "工具调用",
                checked = config.supportsToolCalling == true,
                onCheckedChange = { enabled ->
                    onChange(config.copy(supportsToolCalling = enabled))
                },
            )
            ModelConfigDataBar(
                label = "读超时",
                valueText = "${((config.readTimeoutMillis ?: DEFAULT_READ_TIMEOUT_MILLIS) / 1000L).toInt()}秒",
                progress = modelConfigDataBarProgress(
                    value = ((config.readTimeoutMillis ?: DEFAULT_READ_TIMEOUT_MILLIS) / 1000L).toInt(),
                    maxValue = MAX_READ_TIMEOUT_SECONDS,
                ),
            )
            Slider(
                value = ((config.readTimeoutMillis ?: DEFAULT_READ_TIMEOUT_MILLIS) / 1000L)
                    .toFloat()
                    .coerceIn(MIN_READ_TIMEOUT_SECONDS.toFloat(), MAX_READ_TIMEOUT_SECONDS.toFloat()),
                onValueChange = {
                    onChange(updateReadTimeoutSeconds(config, it.toInt()))
                },
                valueRange = MIN_READ_TIMEOUT_SECONDS.toFloat()..MAX_READ_TIMEOUT_SECONDS.toFloat(),
            )
        }
    }
}

@Composable
private fun ModelCapabilitySwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ModelConfigDataBar(
    label: String,
    valueText: String,
    progress: Float,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderProfile,
    capabilities: List<ResolvedModelCapability>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("文本模型：${provider.defaultModel}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "可选模型：${capabilities.map { it.modelId }.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "上下文：${capabilities.joinToString("、") { "${it.modelId} ${it.contextWindowTokens.toCompactTokenText()}" }}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                capabilities.firstOrNull { it.modelId == provider.defaultModel }?.let { capability ->
                    Text(
                        text = "能力来源：${capabilitySourceSummary(capability)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "模型内搜索：${if (isNativeWebSearchEnabled(provider.nativeWebSearchMode)) "开启" else "关闭"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                provider.defaultVisionModel?.let {
                    Text("图片模型：$it", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = if (provider.hasApiKey) "Key 已加密保存" else "Key 未保存",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (provider.hasApiKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

private const val MIN_CONTEXT_WINDOW_TOKENS = 32_000
private const val MAX_CONTEXT_WINDOW_TOKENS = 1_000_000
private const val MIN_READ_TIMEOUT_SECONDS = 30
private const val MAX_READ_TIMEOUT_SECONDS = 300
private const val DEFAULT_READ_TIMEOUT_MILLIS = 180_000L

internal fun modelConfigDataBarProgress(value: Int, maxValue: Int): Float {
    if (maxValue <= 0) return 0f
    return (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
}

internal fun updateModelConfigAt(
    configs: List<ModelConfig>,
    index: Int,
    update: (ModelConfig) -> ModelConfig,
): List<ModelConfig> =
    configs.mapIndexed { currentIndex, config ->
        if (currentIndex == index) update(config).normalizedModelConfig() else config
    }

internal fun removeModelConfigAt(configs: List<ModelConfig>, index: Int): List<ModelConfig> =
    configs.filterIndexed { currentIndex, _ -> currentIndex != index }
        .ifEmpty { listOf(ModelConfig("new-model")) }

internal fun appendModelConfig(configs: List<ModelConfig>, providerName: String): List<ModelConfig> {
    val base = "new-model"
    val existingIds = configs.map { it.id }.toSet()
    val nextId = generateSequence(base) { previous ->
        val suffix = previous.substringAfterLast('-', missingDelimiterValue = "0").toIntOrNull()?.plus(1) ?: 2
        "$base-$suffix"
    }.first { it !in existingIds }
    return configs + defaultModelConfig(providerName, nextId)
}

internal fun updateInputModality(
    config: ModelConfig,
    modality: String,
    enabled: Boolean,
): ModelConfig {
    val normalized = modality.trim().lowercase()
    val current = (config.inputModalities ?: listOf("text"))
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toMutableList()
    if ("text" !in current) current.add(0, "text")
    if (enabled && normalized !in current) current.add(normalized)
    if (!enabled) current.remove(normalized)
    return config.copy(inputModalities = current.distinct())
}

internal fun updateReasoningCapability(config: ModelConfig, enabled: Boolean): ModelConfig =
    if (enabled) {
        config.copy(
            reasoningEffortOptions = OPEN_AI_REASONING_EFFORT_OPTIONS,
            defaultReasoningEffort = DEFAULT_OPEN_AI_REASONING_EFFORT,
        )
    } else {
        config.copy(reasoningEffortOptions = null, defaultReasoningEffort = null)
    }

internal fun updateModelWebSearchMode(
    config: ModelConfig,
    providerName: String,
    enabled: Boolean,
): ModelConfig = config.copy(
    webSearchMode = if (enabled) {
        nativeWebSearchModeForSwitch(providerName, enabled = true)
    } else {
        NativeWebSearchMode.DISABLED
    },
)

internal fun updateReadTimeoutSeconds(config: ModelConfig, seconds: Int): ModelConfig =
    config.copy(
        readTimeoutMillis = seconds.coerceIn(MIN_READ_TIMEOUT_SECONDS, MAX_READ_TIMEOUT_SECONDS) * 1000L,
    )

internal fun isNativeWebSearchEnabled(mode: NativeWebSearchMode): Boolean =
    mode != NativeWebSearchMode.DISABLED

internal fun nativeWebSearchModeForSwitch(providerName: String, enabled: Boolean): NativeWebSearchMode {
    if (!enabled) return NativeWebSearchMode.DISABLED
    val normalizedName = providerName.lowercase()
    return when {
        "kimi" in normalizedName -> NativeWebSearchMode.ENABLE_SEARCH_BOOLEAN
        "glm" in normalizedName || "智谱" in normalizedName -> NativeWebSearchMode.GLM_WEB_SEARCH_TOOL
        else -> NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS
    }
}

internal fun modelConfigsForTemplate(
    template: ProviderTemplate,
    catalog: ProviderCapabilityCatalog?,
): List<ModelConfig> {
    val capabilityTemplate = catalog?.providers?.firstOrNull {
        it.providerId.equals(template.name, ignoreCase = true) ||
            it.displayName.equals(template.name, ignoreCase = true)
    } ?: return template.modelConfigs
    return capabilityTemplate.models.map {
        ModelConfig(
            id = it.id,
            contextWindowTokens = it.contextWindowTokens,
            compressionThresholdPercent = it.compressionThresholdPercent,
            maxOutputTokens = it.maxOutputTokens,
            inputModalities = it.inputModalities,
            outputModalities = it.outputModalities,
            reasoningEffortOptions = it.reasoningEffortOptions,
            defaultReasoningEffort = it.defaultReasoningEffort,
            webSearchMode = it.webSearchMode,
            supportsToolCalling = it.supportsToolCalling,
            readTimeoutMillis = it.readTimeoutMillis,
        )
    }.ifEmpty { template.modelConfigs }
}

internal fun capabilitySourceSummary(capability: ResolvedModelCapability): String =
    when (capability.source) {
        CapabilitySource.REMOTE -> listOf("remote", capability.catalogVersion)
            .filterNotNull()
            .joinToString(" · ")
        CapabilitySource.LOCAL_OVERRIDE -> "本地覆盖"
        CapabilitySource.PROVIDER_PROFILE -> "本地配置"
        CapabilitySource.BUNDLED -> "内置清单"
        CapabilitySource.FALLBACK -> "保守默认"
    }

private fun ModelConfig.normalizedModelConfig(): ModelConfig = copy(
    id = id.trim(),
    contextWindowTokens = contextWindowTokens.coerceIn(MIN_CONTEXT_WINDOW_TOKENS, MAX_CONTEXT_WINDOW_TOKENS),
    compressionThresholdPercent = compressionThresholdPercent.coerceIn(1, 95),
    maxOutputTokens = maxOutputTokens?.coerceAtLeast(1),
    inputModalities = inputModalities?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }?.distinct(),
    outputModalities = outputModalities?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }?.distinct(),
    reasoningEffortOptions = reasoningEffortOptions?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }?.distinct(),
    defaultReasoningEffort = defaultReasoningEffort?.trim()?.takeIf { it.isNotBlank() },
    readTimeoutMillis = readTimeoutMillis?.coerceIn(MIN_READ_TIMEOUT_SECONDS * 1000L, MAX_READ_TIMEOUT_SECONDS * 1000L),
)

private fun Int.toCompactTokenText(): String =
    if (this >= 1_000_000) {
        "${this / 1_000_000}M"
    } else {
        "${this / 1_000}k"
    }

internal fun defaultModelOptionsForProvider(
    providers: List<ProviderProfile>,
    selectedProviderId: String?,
    resolver: ModelCapabilityResolver? = null,
): List<String> =
    providers.firstOrNull { it.id == selectedProviderId }
        ?.let { provider ->
            resolver?.selectableModels(provider)?.map { it.modelId }
                ?: selectableModelsForProvider(provider)
        }
        .orEmpty()

private fun Int.roundToThousands(): Int = (this / 1_000) * 1_000
