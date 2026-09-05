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
import androidx.compose.runtime.LaunchedEffect
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
import com.harnessapk.voice.CloudTranscriptionRequest
import com.harnessapk.voice.AliyunRealtimeRequest
import com.harnessapk.voice.AliyunRealtimeTranscriptionClient
import com.harnessapk.voice.AliyunRealtimeTranscriptionListener
import com.harnessapk.voice.AliyunRealtimeTranscriptionSession
import com.harnessapk.voice.M4aVoiceRecorder
import com.harnessapk.voice.OpenAiCompatibleTranscriptionClient
import com.harnessapk.voice.PcmVoiceRecorder
import com.harnessapk.voice.SILICON_FLOW_BASE_URL
import com.harnessapk.voice.SpeechRecognitionBackend
import com.harnessapk.voice.SystemSpeechRecognizer
import com.harnessapk.voice.VoiceInputEvent
import com.harnessapk.voice.VoiceInputPhase
import com.harnessapk.voice.VoiceInputState
import com.harnessapk.voice.VoiceProviderType
import com.harnessapk.voice.VoiceSettings
import com.harnessapk.voice.reduceVoiceInputState
import com.harnessapk.voice.aliyunRealtimeTranscriptionError
import com.harnessapk.voice.siliconFlowTranscriptionError
import com.harnessapk.voice.systemSpeechRecognitionIntent
import kotlinx.coroutines.flow.first
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
    // null = 设置尚未从 DataStore 加载；启动语音前必须等待真实值，
    // 否则会用默认的 ANDROID_SYSTEM 路由（「语音提问」冷启动必现系统识别）
    var settings by remember { mutableStateOf<VoiceSettings?>(null) }
    LaunchedEffect(container) {
        container.settingsStore.voiceSettings.collect { settings = it }
    }
    suspend fun currentSettings(): VoiceSettings =
        settings ?: container.settingsStore.voiceSettings.first().also { settings = it }
    var state by remember { mutableStateOf(VoiceInputState()) }
    var pendingLanguage by remember { mutableStateOf("system") }
    var embeddedRecognizerActive by remember { mutableStateOf(false) }
    var activeProviderType by remember { mutableStateOf<VoiceProviderType?>(null) }
    var activeSiliconFlowApiKey by remember { mutableStateOf<String?>(null) }
    var activeAliyunSession by remember { mutableStateOf<AliyunRealtimeTranscriptionSession?>(null) }
    val latestState by rememberUpdatedState(state)
    val latestEmbeddedRecognizerActive by rememberUpdatedState(embeddedRecognizerActive)
    val latestActiveProviderType by rememberUpdatedState(activeProviderType)
    val recorder = remember(context.applicationContext) { M4aVoiceRecorder(context.applicationContext) }
    val pcmRecorder = remember { PcmVoiceRecorder() }
    val transcriptionClient = remember(container) {
        OpenAiCompatibleTranscriptionClient(container.chatHttpClient, container.json)
    }
    val aliyunTranscriptionClient = remember(container) {
        AliyunRealtimeTranscriptionClient(container.chatHttpClient, container.json)
    }

    fun dispatch(event: VoiceInputEvent) {
        if (
            event is VoiceInputEvent.FinalResult ||
            event is VoiceInputEvent.Failed ||
            event == VoiceInputEvent.CancelRequested
        ) {
            activeProviderType = null
            activeSiliconFlowApiKey = null
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

    fun beginSiliconFlowRecognition() {
        scope.launch {
            val apiKey = runCatching {
                withContext(container.dispatchers.io) {
                    container.voiceCredentialStore.siliconFlowApiKey()
                }
            }.getOrNull()
            if (apiKey.isNullOrBlank()) {
                dispatch(VoiceInputEvent.Failed("请先在语音能力中配置硅基流动 API Key", preservePartial = false))
                return@launch
            }
            if (state.phase != VoiceInputPhase.REQUESTING_PERMISSION) return@launch
            runCatching { recorder.start() }
                .onSuccess {
                    activeSiliconFlowApiKey = apiKey
                    activeProviderType = VoiceProviderType.SILICON_FLOW
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
                    message = "此设备没有可用的系统语音识别，可在语音能力中选择阿里云实时或硅基流动",
                    preservePartial = false,
                ),
            )
        }
    }

    fun beginAliyunRecognition() {
        scope.launch {
            val resolvedSettings = currentSettings()
            val apiKey = runCatching {
                withContext(container.dispatchers.io) {
                    container.voiceCredentialStore.aliyunApiKey()
                }
            }.getOrNull()
            if (apiKey.isNullOrBlank()) {
                dispatch(VoiceInputEvent.Failed("请先在语音能力中配置阿里云百炼 API Key", preservePartial = false))
                return@launch
            }
            if (state.phase != VoiceInputPhase.REQUESTING_PERMISSION) return@launch

            activeProviderType = VoiceProviderType.ALIYUN
            lateinit var session: AliyunRealtimeTranscriptionSession
            session = runCatching {
                aliyunTranscriptionClient.start(
                    request = AliyunRealtimeRequest(
                        apiKey = apiKey,
                        model = resolvedSettings.aliyunSpeechModel,
                        language = pendingLanguage,
                        autoPunctuation = resolvedSettings.autoPunctuation,
                    ),
                    listener = object : AliyunRealtimeTranscriptionListener {
                        override fun onReady() {
                            scope.launch {
                                if (state.phase != VoiceInputPhase.REQUESTING_PERMISSION) return@launch
                                runCatching {
                                    pcmRecorder.start(
                                        onAudioChunk = { audio -> session.sendAudio(audio) },
                                        onFailure = { error ->
                                            scope.launch {
                                                session.cancel()
                                                activeAliyunSession = null
                                                dispatch(
                                                    VoiceInputEvent.Failed(
                                                        error.message ?: "实时录音失败，请重试",
                                                        preservePartial = true,
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                }.onSuccess {
                                    dispatch(VoiceInputEvent.PermissionGranted)
                                }.onFailure { error ->
                                    session.cancel()
                                    activeAliyunSession = null
                                    dispatch(
                                        VoiceInputEvent.Failed(
                                            error.message ?: "无法开始实时录音，请重试",
                                            preservePartial = false,
                                        ),
                                    )
                                }
                            }
                        }

                        override fun onPartialResult(transcript: String) {
                            scope.launch { dispatch(VoiceInputEvent.PartialResult(transcript)) }
                        }

                        override fun onFinalResult(transcript: String) {
                            scope.launch {
                                pcmRecorder.stop()
                                activeAliyunSession = null
                                dispatch(VoiceInputEvent.FinalResult(transcript))
                            }
                        }

                        override fun onFailure(error: Throwable) {
                            scope.launch {
                                pcmRecorder.cancel()
                                activeAliyunSession = null
                                dispatch(
                                    VoiceInputEvent.Failed(
                                        aliyunRealtimeTranscriptionError(error),
                                        preservePartial = true,
                                    ),
                                )
                            }
                        }
                    },
                )
            }.getOrElse { error ->
                activeProviderType = null
                dispatch(
                    VoiceInputEvent.Failed(
                        aliyunRealtimeTranscriptionError(error),
                        preservePartial = false,
                    ),
                )
                return@launch
            }
            activeAliyunSession = session
        }
    }

    fun beginRecognition() {
        scope.launch {
            // 先等待真实设置加载完成再路由，避免冷启动竞态走系统识别
            val resolvedSettings = currentSettings()
            when (resolvedSettings.defaultSpeechProvider) {
                VoiceProviderType.ANDROID_SYSTEM -> beginSystemRecognition()
                VoiceProviderType.SILICON_FLOW -> beginSiliconFlowRecognition()
                VoiceProviderType.ALIYUN -> beginAliyunRecognition()
            }
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
                (
                    latestEmbeddedRecognizerActive ||
                        latestActiveProviderType == VoiceProviderType.SILICON_FLOW ||
                        latestActiveProviderType == VoiceProviderType.ALIYUN
                    ) &&
                latestState.active
            ) {
                recognizer.cancel()
                recorder.cancel()
                pcmRecorder.cancel()
                activeAliyunSession?.cancel()
                activeAliyunSession = null
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
            pcmRecorder.cancel()
            activeAliyunSession?.cancel()
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
                if (activeProviderType == VoiceProviderType.SILICON_FLOW) {
                    val apiKey = activeSiliconFlowApiKey
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
                            val resolvedApiKey = checkNotNull(apiKey)
                            val transcript = transcriptionClient.transcribe(
                                request = CloudTranscriptionRequest(
                                    baseUrl = SILICON_FLOW_BASE_URL,
                                    apiKey = resolvedApiKey,
                                    model = currentSettings().siliconFlowSpeechModel,
                                    language = "system",
                                ),
                                audioFile = audioFile,
                            )
                            dispatch(VoiceInputEvent.FinalResult(transcript))
                        } catch (error: Exception) {
                            dispatch(
                                VoiceInputEvent.Failed(
                                    siliconFlowTranscriptionError(error),
                                    preservePartial = false,
                                ),
                            )
                        } finally {
                            audioFile.delete()
                        }
                    }
                } else if (activeProviderType == VoiceProviderType.ALIYUN) {
                    pcmRecorder.stop()
                    activeAliyunSession?.finish()
                    activeProviderType = null
                } else {
                    recognizer.stop()
                }
            } else if (state.phase == VoiceInputPhase.REQUESTING_PERMISSION) {
                activeAliyunSession?.cancel()
                activeAliyunSession = null
                pcmRecorder.cancel()
                dispatch(VoiceInputEvent.CancelRequested)
            }
        },
        cancel = {
            recognizer.cancel()
            recorder.cancel()
            pcmRecorder.cancel()
            activeAliyunSession?.cancel()
            activeAliyunSession = null
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
