package com.harnessapk.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.harnessapk.common.AppContainer
import com.harnessapk.voice.VoiceSettings
import com.harnessapk.voice.VoiceProviderType
import com.harnessapk.voice.DEFAULT_ALIYUN_SPEECH_MODEL
import com.harnessapk.voice.siliconFlowSpeechModels
import com.harnessapk.voice.transcriptionLanguageOptions
import kotlinx.coroutines.launch

@Composable
fun VoiceSettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
) {
    val settings by container.settingsStore.voiceSettings.collectAsState(initial = VoiceSettings())
    val credentialState by container.voiceCredentialStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var siliconFlowApiKeyInput by remember { mutableStateOf("") }
    var aliyunApiKeyInput by remember { mutableStateOf("") }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsSection(title = "语音转写") {
            Text(
                text = "转写结果只填入输入框，不会自动发送。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = settings.defaultSpeechProvider == VoiceProviderType.ANDROID_SYSTEM,
                    onClick = {
                        scope.launch { container.settingsStore.setDefaultSpeechProvider(VoiceProviderType.ANDROID_SYSTEM) }
                    },
                    label = { Text("系统语音") },
                )
                FilterChip(
                    selected = settings.defaultSpeechProvider == VoiceProviderType.SILICON_FLOW,
                    onClick = {
                        scope.launch {
                            container.settingsStore.setDefaultSpeechProvider(VoiceProviderType.SILICON_FLOW)
                        }
                    },
                    label = { Text("硅基流动") },
                )
                FilterChip(
                    selected = settings.defaultSpeechProvider == VoiceProviderType.ALIYUN,
                    onClick = {
                        scope.launch { container.settingsStore.setDefaultSpeechProvider(VoiceProviderType.ALIYUN) }
                    },
                    label = { Text("阿里云实时") },
                )
            }
            if (settings.defaultSpeechProvider == VoiceProviderType.SILICON_FLOW) {
                Text(
                    text = if (credentialState.hasSiliconFlowApiKey) {
                        "API Key 已加密保存。录音转写完成后立即从本机删除。"
                    } else {
                        "配置 API Key 后即可使用，不依赖手机内置语音服务。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = siliconFlowApiKeyInput,
                    onValueChange = { value ->
                        siliconFlowApiKeyInput = value
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("硅基流动 API Key") },
                    placeholder = {
                        Text(if (credentialState.hasSiliconFlowApiKey) "已保存，留空不修改" else "sk-...")
                    },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        enabled = siliconFlowApiKeyInput.isNotBlank(),
                        onClick = {
                            container.voiceCredentialStore.saveSiliconFlowApiKey(siliconFlowApiKeyInput)
                            siliconFlowApiKeyInput = ""
                            scope.launch {
                                container.settingsStore.setDefaultSpeechProvider(VoiceProviderType.SILICON_FLOW)
                            }
                        },
                    ) {
                        Text(if (credentialState.hasSiliconFlowApiKey) "更新 Key" else "保存 Key")
                    }
                    if (credentialState.hasSiliconFlowApiKey) {
                        TextButton(onClick = container.voiceCredentialStore::clearSiliconFlowApiKey) {
                            Text("删除 Key")
                        }
                    }
                    TextButton(onClick = { uriHandler.openUri("https://cloud.siliconflow.cn/account/ak") }) {
                        Text("获取 Key")
                    }
                }
                Text("转写模型", fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    siliconFlowSpeechModels().forEach { model ->
                        FilterChip(
                            selected = settings.siliconFlowSpeechModel == model.id,
                            onClick = {
                                scope.launch { container.settingsStore.setSiliconFlowSpeechModel(model.id) }
                            },
                            label = {
                                Text(if (model.recommended) "${model.label}（默认）" else model.label)
                            },
                        )
                    }
                }
            } else if (settings.defaultSpeechProvider == VoiceProviderType.ALIYUN) {
                Text(
                    text = if (credentialState.hasAliyunApiKey) {
                        "API Key 已加密保存。语音会实时上传并逐句返回转写结果。"
                    } else {
                        "配置百炼 API Key 后即可边说边出字。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = aliyunApiKeyInput,
                    onValueChange = { aliyunApiKeyInput = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("阿里云百炼 API Key") },
                    placeholder = {
                        Text(if (credentialState.hasAliyunApiKey) "已保存，留空不修改" else "sk-...")
                    },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        enabled = aliyunApiKeyInput.isNotBlank(),
                        onClick = {
                            container.voiceCredentialStore.saveAliyunApiKey(aliyunApiKeyInput)
                            aliyunApiKeyInput = ""
                            scope.launch {
                                container.settingsStore.setDefaultSpeechProvider(VoiceProviderType.ALIYUN)
                            }
                        },
                    ) {
                        Text(if (credentialState.hasAliyunApiKey) "更新 Key" else "保存 Key")
                    }
                    if (credentialState.hasAliyunApiKey) {
                        TextButton(onClick = container.voiceCredentialStore::clearAliyunApiKey) {
                            Text("删除 Key")
                        }
                    }
                    TextButton(
                        onClick = {
                            uriHandler.openUri("https://bailian.console.aliyun.com/?apiKey=1#/api-key")
                        },
                    ) {
                        Text("获取 Key")
                    }
                }
                Text("实时模型", fontWeight = FontWeight.SemiBold)
                FilterChip(
                    selected = settings.aliyunSpeechModel == DEFAULT_ALIYUN_SPEECH_MODEL,
                    onClick = {
                        scope.launch {
                            container.settingsStore.setAliyunSpeechModel(DEFAULT_ALIYUN_SPEECH_MODEL)
                        }
                    },
                    label = { Text("Paraformer Realtime V2（默认）") },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    transcriptionLanguageOptions().forEach { language ->
                        FilterChip(
                            selected = settings.defaultTranscriptionLanguage == language.value,
                            onClick = {
                                scope.launch { container.settingsStore.setDefaultTranscriptionLanguage(language.value) }
                            },
                            label = { Text(language.label) },
                        )
                    }
                }
            } else {
                Text(
                    text = "音频由设备当前的系统语音服务处理，系统服务可能联网。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    transcriptionLanguageOptions().forEach { language ->
                        FilterChip(
                            selected = settings.defaultTranscriptionLanguage == language.value,
                            onClick = {
                                scope.launch { container.settingsStore.setDefaultTranscriptionLanguage(language.value) }
                            },
                            label = { Text(language.label) },
                        )
                    }
                }
            }
        }
        SettingsSection(title = "麦克风权限") {
            Text(
                text = if (permissionGranted) "已授权" else "未授权",
                color = if (permissionGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                onClick = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
            ) {
                Text(if (permissionGranted) "重新检查权限" else "授权麦克风")
            }
        }
        SettingsSection(title = "朗读输出") {
            SettingSwitchRow(
                title = "启用回复朗读",
                description = "使用 Android 系统 TTS，只在用户点击回复朗读时播放。",
                checked = settings.ttsEnabled,
                onCheckedChange = { scope.launch { container.settingsStore.setTtsEnabled(it) } },
            )
            Text("语速：${"%.1f".format(settings.ttsSpeechRate)}x")
            Slider(
                value = settings.ttsSpeechRate,
                onValueChange = {
                    scope.launch { container.settingsStore.setTtsSpeechRate(it) }
                },
                valueRange = 0.6f..1.4f,
                steps = 7,
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}
