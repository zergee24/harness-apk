package com.harnessapk.network

import com.harnessapk.provider.NativeWebSearchMode

data class ChatRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<OutgoingChatMessage>,
    val temperature: Double = 0.2,
    val reasoningEffort: String? = null,
    val nativeWebSearchMode: NativeWebSearchMode? = null,
    val readTimeoutMillis: Long? = null,
    val customHeaders: Map<String, String> = emptyMap(),
    val customBodyJson: String = "",
)

data class OutgoingChatMessage(
    val role: String,
    val text: String,
    val imageDataUrls: List<String> = emptyList(),
)

data class ChatDelta(
    val text: String,
)

class ChatHttpException(message: String) : Exception(message)
