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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.harnessapk.voice.SpeechRecognitionBackend
import com.harnessapk.voice.SystemSpeechRecognizer
import com.harnessapk.voice.VoiceInputEvent
import com.harnessapk.voice.VoiceInputState
import com.harnessapk.voice.reduceVoiceInputState
import com.harnessapk.voice.systemSpeechRecognitionIntent

data class SystemVoiceInputBinding(
    val state: VoiceInputState,
    val start: (currentDraft: String, language: String) -> Unit,
    val stop: () -> Unit,
    val cancel: () -> Unit,
    val consume: () -> Unit,
)

@Composable
fun rememberSystemVoiceInput(): SystemVoiceInputBinding {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(VoiceInputState()) }
    var pendingLanguage by remember { mutableStateOf("system") }
    var embeddedRecognizerActive by remember { mutableStateOf(false) }
    val latestState by rememberUpdatedState(state)
    val latestEmbeddedRecognizerActive by rememberUpdatedState(embeddedRecognizerActive)

    fun dispatch(event: VoiceInputEvent) {
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

    fun beginRecognition() {
        dispatch(VoiceInputEvent.PermissionGranted)
        when (recognizer.backend()) {
            SpeechRecognitionBackend.SPEECH_RECOGNIZER -> {
                embeddedRecognizerActive = true
                recognizer.start(pendingLanguage)
            }
            SpeechRecognitionBackend.RECOGNIZER_INTENT -> {
                embeddedRecognizerActive = false
                fallbackLauncher.launch(systemSpeechRecognitionIntent(pendingLanguage))
            }
            SpeechRecognitionBackend.UNAVAILABLE -> dispatch(
                VoiceInputEvent.Failed(
                    message = "此设备没有可用的系统语音识别",
                    preservePartial = false,
                ),
            )
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
                latestEmbeddedRecognizerActive &&
                latestState.active
            ) {
                recognizer.cancel()
                embeddedRecognizerActive = false
                dispatch(VoiceInputEvent.CancelRequested)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            recognizer.cancel()
            recognizer.destroy()
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
            if (state.phase == com.harnessapk.voice.VoiceInputPhase.LISTENING) {
                dispatch(VoiceInputEvent.StopRequested)
                recognizer.stop()
            }
        },
        cancel = {
            recognizer.cancel()
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
