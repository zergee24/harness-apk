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
import com.harnessapk.voice.transcriptionLanguageOptions
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
        SettingsSection(title = "转写语言") {
            Text(
                text = "音频由设备当前的系统语音服务处理，系统服务可能联网；转写结果只填入输入框，不会自动发送。",
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
