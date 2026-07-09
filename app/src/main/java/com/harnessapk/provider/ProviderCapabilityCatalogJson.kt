package com.harnessapk.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

fun parseProviderCapabilityCatalogJson(
    rawJson: String,
    json: Json = Json { ignoreUnknownKeys = true },
): ProviderCapabilityCatalog {
    val root = json.parseToJsonElement(rawJson).jsonObject
    val schemaVersion = root.intValue("schemaVersion") ?: 1
    require(schemaVersion == SUPPORTED_PROVIDER_CATALOG_SCHEMA_VERSION) {
        "不支持的模型能力清单 schemaVersion: $schemaVersion"
    }
    return ProviderCapabilityCatalog(
        catalogVersion = root.stringValue("catalogVersion").orEmpty(),
        providers = root.arrayValue("providers").mapNotNull { element ->
            element.jsonObject.toProviderCapabilityTemplate()
        },
    )
}

private fun JsonObject.toProviderCapabilityTemplate(): ProviderCapabilityTemplate? {
    val providerId = stringValue("providerId")?.takeIf { it.isNotBlank() } ?: return null
    val displayName = stringValue("displayName")?.takeIf { it.isNotBlank() } ?: providerId
    val models = arrayValue("models").mapNotNull { element ->
        element.jsonObject.toModelCapability()
    }
    return ProviderCapabilityTemplate(
        providerId = providerId,
        displayName = displayName,
        defaultModelId = stringValue("defaultModelId")?.takeIf { it.isNotBlank() }
            ?: models.firstOrNull()?.id.orEmpty(),
        models = models,
    )
}

private fun JsonObject.toModelCapability(): ModelCapability? {
    val id = stringValue("modelId")?.takeIf { it.isNotBlank() }
        ?: stringValue("id")?.takeIf { it.isNotBlank() }
        ?: return null
    val reasoningOptions = stringArrayValue("reasoningEffortOptions")
        .ifEmpty {
            if (booleanValue("supportsReasoningEffort") == true) {
                listOf("low", "medium", "high", "xhigh")
            } else {
                emptyList()
            }
        }
    return ModelCapability(
        id = id,
        contextWindowTokens = intValue("contextWindowTokens") ?: DEFAULT_CONTEXT_WINDOW_TOKENS,
        compressionThresholdPercent = intValue("defaultCompressionThresholdPercent")
            ?: intValue("compressionThresholdPercent")
            ?: DEFAULT_COMPRESSION_THRESHOLD_PERCENT,
        maxOutputTokens = intValue("maxOutputTokens"),
        inputModalities = stringArrayValue("inputModalities").ifEmpty { listOf("text") },
        outputModalities = stringArrayValue("outputModalities").ifEmpty { listOf("text") },
        reasoningEffortOptions = reasoningOptions,
        defaultReasoningEffort = stringValue("defaultReasoningEffort"),
        webSearchMode = jsonObjectValue("webSearch")
            ?.stringValue("mode")
            ?.toNativeWebSearchMode()
            ?: NativeWebSearchMode.DISABLED,
        readTimeoutMillis = jsonObjectValue("timeouts")?.longValue("readMs") ?: 180_000L,
    )
}

private fun String.toNativeWebSearchMode(): NativeWebSearchMode =
    when (trim().lowercase()) {
        "openai_web_search_options" -> NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS
        "enable_search_boolean" -> NativeWebSearchMode.ENABLE_SEARCH_BOOLEAN
        "glm_web_search_tool" -> NativeWebSearchMode.GLM_WEB_SEARCH_TOOL
        else -> NativeWebSearchMode.DISABLED
    }

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intValue(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.longValue(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.booleanValue(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.jsonObjectValue(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.arrayValue(key: String): List<JsonElement> =
    (this[key] as? JsonArray)?.jsonArray.orEmpty()

private fun JsonObject.stringArrayValue(key: String): List<String> =
    arrayValue(key).mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }

private const val SUPPORTED_PROVIDER_CATALOG_SCHEMA_VERSION = 1
