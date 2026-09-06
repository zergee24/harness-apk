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
import com.harnessapk.voice.AliyunRealtimeRequest
import com.harnessapk.voice.AliyunRealtimeTranscriptionClient
import com.harnessapk.voice.AliyunRealtimeTranscriptionListener
import com.harnessapk.voice.AliyunRealtimeTranscriptionSession
import com.harnessapk.voice.CloudTranscriptionRequest
import com.harnessapk.voice.GenerationBoundVoiceInputEvent
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
import com.harnessapk.voice.aliyunRealtimeTranscriptionError
import com.harnessapk.voice.reduceVoiceInputState
import com.harnessapk.voice.siliconFlowTranscriptionError
import com.harnessapk.voice.systemSpeechRecognitionIntent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun shouldCancelVoiceOnStop(
    state: VoiceInputState,
    pendingPermissionGeneration: Long?,
    pendingFallbackGeneration: Long?,
    activeGeneration: Long?,
    embeddedRecognizerActive: Boolean,
    activeProviderType: VoiceProviderType?,
): Boolean = state.active &&
    pendingPermissionGeneration == null &&
    pendingFallbackGeneration == null &&
    (
        activeGeneration != null ||
            embeddedRecognizerActive ||
            activeProviderType != null
    )

data class SystemVoiceInputBinding(
    val state: VoiceInputState,
    val start: (currentDraft: String, language: String) -> Unit,
    val stop: () -> Unit,
    val cancel: () -> Unit,
    val consume: () -> Unit,
    val confirm: () -> Unit = {},
    val editReview: (text: String) -> Unit = {},
    val restart: () -> Unit = {},
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
    var nextGeneration by remember { mutableStateOf(0L) }
    var activeGeneration by remember { mutableStateOf<Long?>(null) }
    var pendingPermissionGeneration by remember { mutableStateOf<Long?>(null) }
    var pendingFallbackGeneration by remember { mutableStateOf<Long?>(null) }
    var embeddedRecognizerActive by remember { mutableStateOf(false) }
    var activeProviderType by remember { mutableStateOf<VoiceProviderType?>(null) }
    var activeSiliconFlowApiKey by remember { mutableStateOf<String?>(null) }
    var activeAliyunSession by remember { mutableStateOf<AliyunRealtimeTranscriptionSession?>(null) }
    var activeRecognizer by remember { mutableStateOf<SystemSpeechRecognizer?>(null) }
    val latestState by rememberUpdatedState(state)
    val recorder = remember(context.applicationContext) { M4aVoiceRecorder(context.applicationContext) }
    val pcmRecorder = remember { PcmVoiceRecorder() }
    val transcriptionClient = remember(container) {
        OpenAiCompatibleTranscriptionClient(container.chatHttpClient, container.json)
    }
    val aliyunTranscriptionClient = remember(container) {
        AliyunRealtimeTranscriptionClient(container.chatHttpClient, container.json)
    }

    fun isCurrent(generation: Long): Boolean = activeGeneration == generation

    fun withGeneration(event: VoiceInputEvent, generation: Long): VoiceInputEvent = when (event) {
        is VoiceInputEvent.StartRequested -> event.copy(generation = generation)
        is VoiceInputEvent.PartialResult -> event.copy(generation = generation)
        is VoiceInputEvent.FinalResult -> event.copy(generation = generation)
        is VoiceInputEvent.Failed -> event.copy(generation = generation)
        else -> event
    }

    fun dispatch(event: VoiceInputEvent) {
        state = reduceVoiceInputState(state, event)
    }

    fun dispatchSession(generation: Long, event: VoiceInputEvent) {
        if (!isCurrent(generation)) return
        if (event is VoiceInputEvent.FinalResult || event is VoiceInputEvent.Failed) {
            activeProviderType = null
            activeSiliconFlowApiKey = null
        }
        dispatch(
            GenerationBoundVoiceInputEvent(
                generation = generation,
                event = withGeneration(event, generation),
            ),
        )
    }

    fun releaseVoiceResources() {
        activeGeneration = null
        pendingPermissionGeneration = null
        pendingFallbackGeneration = null
        activeRecognizer?.cancel()
        activeRecognizer?.destroy()
        activeRecognizer = null
        recorder.cancel()
        pcmRecorder.cancel()
        activeAliyunSession?.cancel()
        activeAliyunSession = null
        embeddedRecognizerActive = false
        activeProviderType = null
        activeSiliconFlowApiKey = null
    }

    fun cancelCurrentSession() {
        val shouldCancel = state.active || state.phase == VoiceInputPhase.REVIEW
        releaseVoiceResources()
        if (shouldCancel) dispatch(VoiceInputEvent.CancelRequested)
    }

    val fallbackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val generation = pendingFallbackGeneration
        pendingFallbackGeneration = null
        if (generation != null && isCurrent(generation)) {
            val transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (result.resultCode == Activity.RESULT_OK && transcript.isNotBlank()) {
                dispatchSession(generation, VoiceInputEvent.FinalResult(transcript))
            } else {
                cancelCurrentSession()
            }
        }
    }

    fun beginSiliconFlowRecognition(generation: Long) {
        scope.launch {
            val apiKey = runCatching {
                withContext(container.dispatchers.io) {
                    container.voiceCredentialStore.siliconFlowApiKey()
                }
            }.getOrNull()
            if (!isCurrent(generation)) return@launch
            if (apiKey.isNullOrBlank()) {
                dispatchSession(
                    generation,
                    VoiceInputEvent.Failed("请先在语音能力中配置硅基流动 API Key", preservePartial = false),
                )
                return@launch
            }
            if (state.phase != VoiceInputPhase.REQUESTING_PERMISSION) return@launch
            runCatching { recorder.start() }
                .onSuccess {
                    if (!isCurrent(generation) || state.phase != VoiceInputPhase.REQUESTING_PERMISSION) {
                        recorder.cancel()
                        return@onSuccess
                    }
                    activeSiliconFlowApiKey = apiKey
                    activeProviderType = VoiceProviderType.SILICON_FLOW
                    dispatchSession(generation, VoiceInputEvent.PermissionGranted)
                }
                .onFailure { error ->
                    dispatchSession(
                        generation,
                        VoiceInputEvent.Failed(
                            error.message ?: "无法开始录音，请重试",
                            preservePartial = false,
                        ),
                    )
                }
        }
    }

    fun beginSystemRecognition(generation: Long) {
        if (!isCurrent(generation) || state.phase != VoiceInputPhase.REQUESTING_PERMISSION) return
        dispatchSession(generation, VoiceInputEvent.PermissionGranted)
        val sessionRecognizer = SystemSpeechRecognizer(
            context = context.applicationContext,
            onEvent = { event -> dispatchSession(generation, event) },
            onFallbackRequired = { language ->
                if (isCurrent(generation)) {
                    embeddedRecognizerActive = false
                    pendingFallbackGeneration = generation
                    fallbackLauncher.launch(systemSpeechRecognitionIntent(language))
                }
            },
        )
        activeRecognizer = sessionRecognizer
        activeProviderType = VoiceProviderType.ANDROID_SYSTEM
        when (sessionRecognizer.start(pendingLanguage)) {
            SpeechRecognitionBackend.SPEECH_RECOGNIZER -> {
                embeddedRecognizerActive = true
            }
            SpeechRecognitionBackend.RECOGNIZER_INTENT -> {
                embeddedRecognizerActive = false
                pendingFallbackGeneration = generation
                fallbackLauncher.launch(systemSpeechRecognitionIntent(pendingLanguage))
            }
            SpeechRecognitionBackend.UNAVAILABLE -> dispatchSession(
                generation,
                VoiceInputEvent.Failed(
                    message = "此设备没有可用的系统语音识别，可在语音能力中选择阿里云实时或硅基流动",
                    preservePartial = false,
                ),
            )
        }
    }

    fun beginAliyunRecognition(generation: Long) {
        scope.launch {
            val resolvedSettings = currentSettings()
            val apiKey = runCatching {
                withContext(container.dispatchers.io) {
                    container.voiceCredentialStore.aliyunApiKey()
                }
            }.getOrNull()
            if (!isCurrent(generation)) return@launch
            if (apiKey.isNullOrBlank()) {
                dispatchSession(
                    generation,
                    VoiceInputEvent.Failed("请先在语音能力中配置阿里云百炼 API Key", preservePartial = false),
                )
                return@launch
            }
            if (state.phase != VoiceInputPhase.REQUESTING_PERMISSION) return@launch

            activeProviderType = VoiceProviderType.ALIYUN
            lateinit var session: AliyunRealtimeTranscriptionSession
            session = try {
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
                                if (!isCurrent(generation) || state.phase != VoiceInputPhase.REQUESTING_PERMISSION) {
                                    return@launch
                                }
                                runCatching {
                                    pcmRecorder.start(
                                        onAudioChunk = { audio -> session.sendAudio(audio) },
                                        onFailure = { error ->
                                            scope.launch {
                                                if (!isCurrent(generation)) return@launch
                                                session.cancel()
                                                activeAliyunSession = null
                                                dispatchSession(
                                                    generation,
                                                    VoiceInputEvent.Failed(
                                                        error.message ?: "实时录音失败，请重试",
                                                        preservePartial = true,
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                }.onSuccess {
                                    if (isCurrent(generation)) {
                                        dispatchSession(generation, VoiceInputEvent.PermissionGranted)
                                    } else {
                                        pcmRecorder.cancel()
                                    }
                                }.onFailure { error ->
                                    if (!isCurrent(generation)) return@onFailure
                                    session.cancel()
                                    activeAliyunSession = null
                                    dispatchSession(
                                        generation,
                                        VoiceInputEvent.Failed(
                                            error.message ?: "无法开始实时录音，请重试",
                                            preservePartial = false,
                                        ),
                                    )
                                }
                            }
                        }

                        override fun onPartialResult(transcript: String) {
                            scope.launch {
                                dispatchSession(generation, VoiceInputEvent.PartialResult(transcript))
                            }
                        }

                        override fun onFinalResult(transcript: String) {
                            scope.launch {
                                if (!isCurrent(generation)) return@launch
                                pcmRecorder.stop()
                                activeAliyunSession = null
                                dispatchSession(generation, VoiceInputEvent.FinalResult(transcript))
                            }
                        }

                        override fun onFailure(error: Throwable) {
                            scope.launch {
                                if (!isCurrent(generation)) return@launch
                                pcmRecorder.cancel()
                                activeAliyunSession = null
                                dispatchSession(
                                    generation,
                                    VoiceInputEvent.Failed(
                                        aliyunRealtimeTranscriptionError(error),
                                        preservePartial = true,
                                    ),
                                )
                            }
                        }
                    },
                )
            } catch (error: Throwable) {
                if (isCurrent(generation)) {
                    activeProviderType = null
                    dispatchSession(
                        generation,
                        VoiceInputEvent.Failed(
                            aliyunRealtimeTranscriptionError(error),
                            preservePartial = false,
                        ),
                    )
                }
                return@launch
            }
            if (isCurrent(generation)) {
                activeAliyunSession = session
            } else {
                session.cancel()
            }
        }
    }

    fun beginRecognition(generation: Long) {
        scope.launch {
            val resolvedSettings = currentSettings()
            if (!isCurrent(generation)) return@launch
            when (resolvedSettings.defaultSpeechProvider) {
                VoiceProviderType.ANDROID_SYSTEM -> beginSystemRecognition(generation)
                VoiceProviderType.SILICON_FLOW -> beginSiliconFlowRecognition(generation)
                VoiceProviderType.ALIYUN -> beginAliyunRecognition(generation)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val generation = pendingPermissionGeneration
        pendingPermissionGeneration = null
        if (generation != null && isCurrent(generation)) {
            if (granted) {
                beginRecognition(generation)
            } else {
                val permanentlyDenied = context.findActivity()
                    ?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
                dispatchSession(
                    generation,
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
    }

    fun startSession(
        currentDraft: String,
        language: String,
        replaceActive: Boolean,
    ) {
        if (!replaceActive && (state.active || state.phase == VoiceInputPhase.REVIEW)) return
        releaseVoiceResources()
        pendingLanguage = language
        val generation = nextGeneration + 1L
        nextGeneration = generation
        activeGeneration = generation
        dispatch(VoiceInputEvent.StartRequested(currentDraft, generation = generation))
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            beginRecognition(generation)
        } else {
            pendingPermissionGeneration = generation
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && shouldCancelVoiceOnStop(
                    state = latestState,
                    pendingPermissionGeneration = pendingPermissionGeneration,
                    pendingFallbackGeneration = pendingFallbackGeneration,
                    activeGeneration = activeGeneration,
                    embeddedRecognizerActive = embeddedRecognizerActive,
                    activeProviderType = activeProviderType,
                )
            ) {
                cancelCurrentSession()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releaseVoiceResources()
        }
    }

    return SystemVoiceInputBinding(
        state = state,
        start = { currentDraft, language ->
            startSession(currentDraft, language, replaceActive = false)
        },
        stop = {
            val generation = activeGeneration
            if (generation != null && state.phase == VoiceInputPhase.LISTENING) {
                dispatchSession(generation, VoiceInputEvent.StopRequested)
                when (activeProviderType) {
                    VoiceProviderType.SILICON_FLOW -> {
                        val apiKey = activeSiliconFlowApiKey
                        val audioFile = runCatching { recorder.stop() }.getOrElse { error ->
                            dispatchSession(
                                generation,
                                VoiceInputEvent.Failed(
                                    error.message ?: "录音失败，请重试",
                                    preservePartial = false,
                                ),
                            )
                            null
                        }
                        if (audioFile != null) {
                            scope.launch {
                                try {
                                    if (!isCurrent(generation)) return@launch
                                    val resolvedApiKey = checkNotNull(apiKey)
                                    val resolvedSettings = currentSettings()
                                    if (!isCurrent(generation)) return@launch
                                    val transcript = transcriptionClient.transcribe(
                                        request = CloudTranscriptionRequest(
                                            baseUrl = SILICON_FLOW_BASE_URL,
                                            apiKey = resolvedApiKey,
                                            model = resolvedSettings.siliconFlowSpeechModel,
                                            language = "system",
                                        ),
                                        audioFile = audioFile,
                                    )
                                    dispatchSession(generation, VoiceInputEvent.FinalResult(transcript))
                                } catch (error: Exception) {
                                    dispatchSession(
                                        generation,
                                        VoiceInputEvent.Failed(
                                            siliconFlowTranscriptionError(error),
                                            preservePartial = false,
                                        ),
                                    )
                                } finally {
                                    audioFile.delete()
                                }
                            }
                        }
                    }

                    VoiceProviderType.ALIYUN -> {
                        pcmRecorder.stop()
                        activeAliyunSession?.finish()
                    }

                    VoiceProviderType.ANDROID_SYSTEM,
                    null,
                    -> activeRecognizer?.stop()
                }
            } else if (state.phase == VoiceInputPhase.REQUESTING_PERMISSION) {
                cancelCurrentSession()
            }
        },
        cancel = ::cancelCurrentSession,
        confirm = {
            if (state.phase == VoiceInputPhase.REVIEW) {
                releaseVoiceResources()
                dispatch(VoiceInputEvent.ConfirmRequested)
            }
        },
        editReview = { text ->
            if (state.phase == VoiceInputPhase.REVIEW) {
                dispatch(VoiceInputEvent.ReviewEdited(text))
            }
        },
        restart = {
            val draft = when (state.phase) {
                VoiceInputPhase.REQUESTING_PERMISSION,
                VoiceInputPhase.LISTENING,
                VoiceInputPhase.FINALIZING,
                VoiceInputPhase.REVIEW,
                -> state.baseDraft

                else -> state.displayText
            }
            startSession(draft, pendingLanguage, replaceActive = true)
        },
        consume = {
            if (
                state.committedText != null ||
                state.phase == VoiceInputPhase.CANCELLED ||
                state.phase == VoiceInputPhase.ERROR
            ) {
                dispatch(VoiceInputEvent.Consumed)
            }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
