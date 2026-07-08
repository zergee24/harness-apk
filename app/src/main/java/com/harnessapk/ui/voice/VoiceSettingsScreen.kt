package com.harnessapk.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.harnessapk.common.AppContainer
import com.harnessapk.voice.VoiceSettings
import com.harnessapk.voice.requiresCloudConfiguration
import kotlinx.coroutines.launch

@Composable
fun VoiceSettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
) {
    val settings by container.settingsStore.voiceSettings.collectAsState(initial = VoiceSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var microphoneTestText by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        microphoneTestText = if (granted) "麦克风权限已开启，可以使用系统语音转写。" else "未获得麦克风权限，语音输入不可用。"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
            ListItem(
                headlineContent = { Text("启用语音输入") },
                supportingContent = {
                    Text(
                        if (settings.requiresCloudConfiguration()) {
                            "当前语音 Provider 需要云端配置。"
                        } else {
                            "无需配置 voice 模型，默认使用 Android 系统语音识别，转写后先填入输入框。"
                        },
                    )
                },
                trailingContent = {
                    Switch(
                        checked = settings.speechInputEnabled,
                        onCheckedChange = {
                            scope.launch { container.settingsStore.setSpeechInputEnabled(it) }
                        },
                    )
                },
            )
        }
        SettingsSection(title = "转写设置") {
            Text(
                text = "系统兜底：无 API Key、无账单、无 voice 模型选择；云端 STT 后续作为可选增强。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("默认 Provider：Android 系统语音识别", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("zh-CN", "en-US").forEach { language ->
                    FilterChip(
                        selected = settings.defaultTranscriptionLanguage == language,
                        onClick = {
                            scope.launch { container.settingsStore.setDefaultTranscriptionLanguage(language) }
                        },
                        label = { Text(language) },
                    )
                }
            }
            SettingSwitchRow(
                title = "自动标点",
                description = "系统 Provider 支持时会尽量补齐标点。",
                checked = settings.autoPunctuation,
                onCheckedChange = { scope.launch { container.settingsStore.setAutoPunctuation(it) } },
            )
            SettingSwitchRow(
                title = "转写后填入输入框",
                description = "保留用户确认和编辑步骤。",
                checked = settings.autoFillInput,
                onCheckedChange = { scope.launch { container.settingsStore.setAutoFillTranscription(it) } },
            )
            SettingSwitchRow(
                title = "转写后自动发送",
                description = "默认关闭，给小孩和老人留确认机会。",
                checked = settings.autoSendAfterTranscription,
                onCheckedChange = { scope.launch { container.settingsStore.setAutoSendAfterTranscription(it) } },
            )
            SettingSwitchRow(
                title = "保存原始音频",
                description = "第一版默认不保存录音。",
                checked = settings.saveOriginalAudio,
                onCheckedChange = { scope.launch { container.settingsStore.setSaveOriginalAudio(it) } },
            )
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
            TextButton(
                onClick = {
                    microphoneTestText = if (permissionGranted) {
                        "麦克风权限正常。请回到会话页点击麦克风按钮开始转写。"
                    } else {
                        "请先授权麦克风。"
                    }
                },
            ) {
                Text("麦克风测试")
            }
            microphoneTestText?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsSection(title = "朗读输出") {
            SettingSwitchRow(
                title = "启用回复朗读",
                description = "使用 Android 系统 TTS，只在用户点击回复朗读时播放。",
                checked = settings.ttsEnabled,
                onCheckedChange = { scope.launch { container.settingsStore.setTtsEnabled(it) } },
            )
            Text("默认 Provider：Android 系统 TTS，无需配置 voice 模型。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("语速：${"%.1f".format(settings.ttsSpeechRate)}x")
            Slider(
                value = settings.ttsSpeechRate,
                onValueChange = {
                    scope.launch { container.settingsStore.setTtsSpeechRate(it) }
                },
                valueRange = 0.6f..1.4f,
                steps = 7,
            )
            Text(
                text = "当前版本不接入云端语音服务；如果后续启用云端 STT/TTS，会在这里明确提示音频或文本会发送给对应 Provider。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
