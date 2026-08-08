package com.harnessapk.voice

enum class VoiceProviderType {
    ANDROID_SYSTEM,
    CLOUD,
}

data class VoiceSettings(
    val speechInputEnabled: Boolean = false,
    val defaultSpeechProvider: VoiceProviderType = VoiceProviderType.ANDROID_SYSTEM,
    val cloudSpeechProviderId: String? = null,
    val cloudSpeechModel: String = "whisper-1",
    val defaultTranscriptionLanguage: String = "system",
    val autoPunctuation: Boolean = true,
    val autoFillInput: Boolean = true,
    val autoSendAfterTranscription: Boolean = false,
    val saveOriginalAudio: Boolean = false,
    val ttsEnabled: Boolean = false,
    val defaultTtsProvider: VoiceProviderType = VoiceProviderType.ANDROID_SYSTEM,
    val ttsSpeechRate: Float = 1.0f,
)

fun VoiceSettings.requiresCloudConfiguration(): Boolean =
    defaultSpeechProvider == VoiceProviderType.CLOUD || defaultTtsProvider == VoiceProviderType.CLOUD

fun VoiceSettings.cloudSpeechReady(): Boolean =
    defaultSpeechProvider == VoiceProviderType.CLOUD &&
        !cloudSpeechProviderId.isNullOrBlank() &&
        cloudSpeechModel.isNotBlank()

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
