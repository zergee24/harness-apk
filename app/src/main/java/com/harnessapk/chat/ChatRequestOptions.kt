package com.harnessapk.chat

import com.harnessapk.provider.ModelCapabilityResolver
import com.harnessapk.provider.ProviderProfile

enum class ReasoningEffort(
    val wireValue: String,
    val label: String,
) {
    MINIMAL("minimal", "最小"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    XHIGH("xhigh", "超高"),
    MAX("max", "最大"),
}

fun defaultReasoningEffort(): ReasoningEffort = ReasoningEffort.XHIGH

fun reasoningEffortForRequest(
    provider: ProviderProfile,
    model: String,
    selectedEffort: ReasoningEffort,
): String? =
    if (supportsReasoningEffort(provider, model)) selectedEffort.wireValue else null

fun supportsReasoningEffort(provider: ProviderProfile, model: String): Boolean {
    return ModelCapabilityResolver().resolve(provider, model).supportsReasoningEffort
}
