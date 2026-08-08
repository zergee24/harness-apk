package com.harnessapk.ui.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.harnessapk.common.AppContainer
import com.harnessapk.provider.ProviderWithKey
import com.harnessapk.voice.CloudTranscriptionRequest
import com.harnessapk.voice.M4aVoiceRecorder
import com.harnessapk.voice.OpenAiCompatibleTranscriptionClient
import com.harnessapk.voice.SpeechRecognitionBackend
import com.harnessapk.voice.SystemSpeechRecognizer
import com.harnessapk.voice.VoiceInputEvent
import com.harnessapk.voice.VoiceInputPhase
import com.harnessapk.voice.VoiceInputState
import com.harnessapk.voice.VoiceProviderType
import com.harnessapk.voice.VoiceSettings
import com.harnessapk.voice.reduceVoiceInputState
import com.harnessapk.voice.systemSpeechRecognitionIntent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SystemVoiceInputBinding(
    val state: VoiceInputState,
    val start: (currentDraft: String, language: String) -> Unit,
    val stop: () -> Unit,
    val cancel: () -> Unit,
    val consume: () -> Unit,
)

@Composable
fun rememberVoiceInput(container: AppContainer): SystemVoiceInputBinding {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by container.settingsStore.voiceSettings.collectAsState(initial = VoiceSettings())
    var state by remember { mutableStateOf(VoiceInputState()) }
    var pendingLanguage by remember { mutableStateOf("system") }
    var embeddedRecognizerActive by remember { mutableStateOf(false) }
    var activeProviderType by remember { mutableStateOf<VoiceProviderType?>(null) }
    var activeCloudProvider by remember { mutableStateOf<ProviderWithKey?>(null) }
    val latestState by rememberUpdatedState(state)
    val latestEmbeddedRecognizerActive by rememberUpdatedState(embeddedRecognizerActive)
    val latestActiveProviderType by rememberUpdatedState(activeProviderType)
    val recorder = remember(context.applicationContext) { M4aVoiceRecorder(context.applicationContext) }
    val transcriptionClient = remember(container) {
        OpenAiCompatibleTranscriptionClient(container.chatHttpClient, container.json)
    }

    fun dispatch(event: VoiceInputEvent) {
        if (
            event is VoiceInputEvent.FinalResult ||
            event is VoiceInputEvent.Failed ||
            event == VoiceInputEvent.CancelRequested
        ) {
            activeProviderType = null
            activeCloudProvider = null
        }
        state = reduceVoiceInputState(state, event)
    }

    val fallbackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (result.resultCode == Activity.RESULT_OK && transcript.isNotBlank()) {
            dispatch(VoiceInputEvent.FinalResult(transcript))
        } else {
            dispatch(VoiceInputEvent.CancelRequested)
        }
    }
    val recognizer = remember(context.applicationContext) {
        SystemSpeechRecognizer(
            context = context.applicationContext,
            onEvent = ::dispatch,
            onFallbackRequired = { language ->
                embeddedRecognizerActive = false
                fallbackLauncher.launch(systemSpeechRecognitionIntent(language))
            },
        )
    }

    fun beginCloudRecognition() {
        val providerId = settings.cloudSpeechProviderId
        if (providerId.isNullOrBlank() || settings.cloudSpeechModel.isBlank()) {
            dispatch(VoiceInputEvent.Failed("请先在语音能力中配置 API 转写服务", preservePartial = false))
            return
        }
        scope.launch {
            val provider = runCatching {
                withContext(container.dispatchers.io) {
                    container.providerRepository.providerWithKey(providerId)
                }
            }.getOrElse {
                dispatch(VoiceInputEvent.Failed("API 转写服务不可用，请检查模型配置", preservePartial = false))
                return@launch
            }
            if (state.phase != VoiceInputPhase.REQUESTING_PERMISSION) return@launch
            runCatching { recorder.start() }
                .onSuccess {
                    activeCloudProvider = provider
                    activeProviderType = VoiceProviderType.CLOUD
                    dispatch(VoiceInputEvent.PermissionGranted)
                }
                .onFailure { error ->
                    dispatch(
                        VoiceInputEvent.Failed(
                            error.message ?: "无法开始录音，请重试",
                            preservePartial = false,
                        ),
                    )
                }
        }
    }

    fun beginSystemRecognition() {
        dispatch(VoiceInputEvent.PermissionGranted)
        when (recognizer.backend()) {
            SpeechRecognitionBackend.SPEECH_RECOGNIZER -> {
                embeddedRecognizerActive = true
                activeProviderType = VoiceProviderType.ANDROID_SYSTEM
                recognizer.start(pendingLanguage)
            }
            SpeechRecognitionBackend.RECOGNIZER_INTENT -> {
                embeddedRecognizerActive = false
                activeProviderType = VoiceProviderType.ANDROID_SYSTEM
                fallbackLauncher.launch(systemSpeechRecognitionIntent(pendingLanguage))
            }
            SpeechRecognitionBackend.UNAVAILABLE -> dispatch(
                VoiceInputEvent.Failed(
                    message = "此设备没有可用的系统语音识别，可在语音能力中选择 API 转写",
                    preservePartial = false,
                ),
            )
        }
    }

    fun beginRecognition() {
        when (settings.defaultSpeechProvider) {
            VoiceProviderType.ANDROID_SYSTEM -> beginSystemRecognition()
            VoiceProviderType.CLOUD -> beginCloudRecognition()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            beginRecognition()
        } else {
            val permanentlyDenied = context.findActivity()
                ?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
            dispatch(
                VoiceInputEvent.Failed(
                    message = if (permanentlyDenied) {
                        "麦克风权限已关闭，请到系统设置开启"
                    } else {
                        "未获得麦克风权限"
                    },
                    preservePartial = false,
                ),
            )
        }
    }

    DisposableEffect(lifecycleOwner, recognizer) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_STOP &&
                (latestEmbeddedRecognizerActive || latestActiveProviderType == VoiceProviderType.CLOUD) &&
                latestState.active
            ) {
                recognizer.cancel()
                recorder.cancel()
                embeddedRecognizerActive = false
                dispatch(VoiceInputEvent.CancelRequested)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            recognizer.cancel()
            recognizer.destroy()
            recorder.cancel()
        }
    }

    return SystemVoiceInputBinding(
        state = state,
        start = { currentDraft, language ->
            pendingLanguage = language
            dispatch(VoiceInputEvent.StartRequested(currentDraft))
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                beginRecognition()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        stop = {
            if (state.phase == VoiceInputPhase.LISTENING) {
                dispatch(VoiceInputEvent.StopRequested)
                if (activeProviderType == VoiceProviderType.CLOUD) {
                    val provider = activeCloudProvider
                    val audioFile = runCatching { recorder.stop() }.getOrElse { error ->
                        dispatch(
                            VoiceInputEvent.Failed(
                                error.message ?: "录音失败，请重试",
                                preservePartial = false,
                            ),
                        )
                        return@SystemVoiceInputBinding
                    }
                    activeProviderType = null
                    scope.launch {
                        try {
                            val resolvedProvider = checkNotNull(provider)
                            val transcript = transcriptionClient.transcribe(
                                request = CloudTranscriptionRequest(
                                    baseUrl = resolvedProvider.profile.baseUrl,
                                    apiKey = resolvedProvider.apiKey,
                                    model = settings.cloudSpeechModel,
                                    language = pendingLanguage,
                                    customHeaders = resolvedProvider.profile.customHeaders,
                                ),
                                audioFile = audioFile,
                            )
                            dispatch(VoiceInputEvent.FinalResult(transcript))
                        } catch (error: Exception) {
                            dispatch(
                                VoiceInputEvent.Failed(
                                    error.message ?: "API 语音转写失败，请重试",
                                    preservePartial = false,
                                ),
                            )
                        } finally {
                            audioFile.delete()
                        }
                    }
                } else {
                    recognizer.stop()
                }
            }
        },
        cancel = {
            recognizer.cancel()
            recorder.cancel()
            embeddedRecognizerActive = false
            dispatch(VoiceInputEvent.CancelRequested)
        },
        consume = { dispatch(VoiceInputEvent.Consumed) },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
