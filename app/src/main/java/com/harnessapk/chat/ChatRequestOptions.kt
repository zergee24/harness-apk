package com.harnessapk.chat

import com.harnessapk.provider.ProviderProfile
import com.harnessapk.provider.modelConfigForProvider

enum class ReasoningEffort(
    val wireValue: String,
    val label: String,
) {
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    XHIGH("xhigh", "超高"),
}

fun defaultReasoningEffort(): ReasoningEffort = ReasoningEffort.HIGH

fun reasoningEffortForRequest(
    provider: ProviderProfile,
    model: String,
    selectedEffort: ReasoningEffort,
): String? =
    if (supportsReasoningEffort(provider, model)) selectedEffort.wireValue else null

fun supportsReasoningEffort(provider: ProviderProfile, model: String): Boolean {
    return modelConfigForProvider(provider, model).supportsReasoningEffort
}
