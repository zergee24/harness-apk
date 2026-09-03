package com.harnessapk.voice

enum class VoiceProviderType {
    ANDROID_SYSTEM,
    SILICON_FLOW,
    ALIYUN,
}

const val SILICON_FLOW_BASE_URL = "https://api.siliconflow.cn/v1"
const val DEFAULT_SILICON_FLOW_SPEECH_MODEL = "FunAudioLLM/SenseVoiceSmall"
const val ALIYUN_REALTIME_SPEECH_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
const val DEFAULT_ALIYUN_SPEECH_MODEL = "paraformer-realtime-v2"

data class VoiceSettings(
    val speechInputEnabled: Boolean = false,
    val defaultSpeechProvider: VoiceProviderType = VoiceProviderType.ANDROID_SYSTEM,
    val siliconFlowSpeechModel: String = DEFAULT_SILICON_FLOW_SPEECH_MODEL,
    val aliyunSpeechModel: String = DEFAULT_ALIYUN_SPEECH_MODEL,
    val defaultTranscriptionLanguage: String = "system",
    val autoPunctuation: Boolean = true,
    val autoFillInput: Boolean = true,
    val autoSendAfterTranscription: Boolean = false,
    val saveOriginalAudio: Boolean = false,
    val ttsEnabled: Boolean = false,
    val ttsAutoRead: Boolean = false,
    val defaultTtsProvider: VoiceProviderType = VoiceProviderType.ANDROID_SYSTEM,
    val ttsSpeechRate: Float = 1.0f,
)

fun VoiceSettings.requiresApiConfiguration(): Boolean =
    defaultSpeechProvider != VoiceProviderType.ANDROID_SYSTEM ||
        defaultTtsProvider == VoiceProviderType.SILICON_FLOW

fun VoiceSettings.siliconFlowSpeechReady(hasApiKey: Boolean): Boolean =
    defaultSpeechProvider == VoiceProviderType.SILICON_FLOW &&
        hasApiKey &&
        siliconFlowSpeechModel.isNotBlank()

fun VoiceSettings.aliyunSpeechReady(hasApiKey: Boolean): Boolean =
    defaultSpeechProvider == VoiceProviderType.ALIYUN &&
        hasApiKey &&
        aliyunSpeechModel.isNotBlank()

data class SiliconFlowSpeechModel(
    val id: String,
    val label: String,
    val recommended: Boolean,
)

fun siliconFlowSpeechModels(): List<SiliconFlowSpeechModel> = listOf(
    SiliconFlowSpeechModel(
        id = DEFAULT_SILICON_FLOW_SPEECH_MODEL,
        label = "SenseVoice Small",
        recommended = true,
    ),
    SiliconFlowSpeechModel(
        id = "TeleAI/TeleSpeechASR",
        label = "TeleSpeech ASR",
        recommended = false,
    ),
)

fun decodeVoiceProviderType(value: String?): VoiceProviderType = when (value) {
    VoiceProviderType.SILICON_FLOW.name, "CLOUD" -> VoiceProviderType.SILICON_FLOW
    VoiceProviderType.ALIYUN.name -> VoiceProviderType.ALIYUN
    else -> VoiceProviderType.ANDROID_SYSTEM
}

fun mergeTranscriptIntoInput(currentText: String, transcript: String): String {
    val cleanTranscript = transcript.trim()
    if (cleanTranscript.isBlank()) return currentText
    val cleanCurrent = currentText.trimEnd()
    return if (cleanCurrent.isBlank()) cleanTranscript else "$cleanCurrent\n$cleanTranscript"
}

data class TranscriptionLanguageOption(
    val value: String,
    val label: String,
)

fun transcriptionLanguageOptions(): List<TranscriptionLanguageOption> = listOf(
    TranscriptionLanguageOption("system", "跟随系统"),
    TranscriptionLanguageOption("zh-CN", "中文"),
    TranscriptionLanguageOption("en-US", "English"),
)
