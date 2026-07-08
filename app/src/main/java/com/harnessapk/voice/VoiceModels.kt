package com.harnessapk.voice

enum class VoiceProviderType {
    ANDROID_SYSTEM,
    CLOUD,
}

data class VoiceSettings(
    val speechInputEnabled: Boolean = false,
    val defaultSpeechProvider: VoiceProviderType = VoiceProviderType.ANDROID_SYSTEM,
    val defaultTranscriptionLanguage: String = "zh-CN",
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

fun mergeTranscriptIntoInput(currentText: String, transcript: String): String {
    val cleanTranscript = transcript.trim()
    if (cleanTranscript.isBlank()) return currentText
    val cleanCurrent = currentText.trimEnd()
    return if (cleanCurrent.isBlank()) cleanTranscript else "$cleanCurrent\n$cleanTranscript"
}
