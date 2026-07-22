package com.harnessapk.wiki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object WikiManifestParser {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun parse(payload: ByteArray): WikiManifest =
        try {
            val root = json.parseToJsonElement(payload.decodeStrictUtf8("manifest.json")).requireObject("manifest")
            root.requireExactFields(MANIFEST_FIELDS, "manifest")
            if (root.requireString("type", "manifest", allowBlank = false) != "hwiki") {
                throw WikiPackageException("manifest.type 必须是 hwiki")
            }
            if (root.requireInt("schemaVersion", "manifest") != SCHEMA_VERSION) {
                throw WikiPackageException("manifest.schemaVersion 必须是 $SCHEMA_VERSION")
            }

            val wiki = root.requireObject("wiki", "manifest")
            wiki.requireExactFields(WIKI_FIELDS, "wiki")
            val version = wiki.requireInt("version", "wiki")
            if (version <= 0) throw WikiPackageException("wiki.version 必须是正整数")
            val languages = wiki.requireStringArray("language", "wiki")
            if (languages.isEmpty()) throw WikiPackageException("wiki.language 必须是非空数组")
            languages.forEachIndexed { index, language ->
                if (!LANGUAGE_PATTERN.matches(language)) {
                    throw WikiPackageException("wiki.language[$index] 不是规范语言标记")
                }
            }
            if (languages.toSet().size != languages.size) {
                throw WikiPackageException("wiki.language 不能包含重复值")
            }

            val publisher = root.requireObject("publisher", "manifest")
            publisher.requireExactFields(PUBLISHER_FIELDS, "publisher")
            val capabilities = root.requireObject("capabilities", "manifest").parseCapabilities()
            val builder = root.requireObject("builder", "manifest")
            builder.requireExactFields(BUILDER_FIELDS, "builder")

            WikiManifest(
                ref = WikiRef(
                    wikiId = wiki.requireIdentifier("id", "wiki"),
                    version = version,
                ),
                title = wiki.requireString("title", "wiki", allowBlank = false),
                description = wiki.requireString("description", "wiki", allowBlank = true),
                languages = languages,
                contentHash = wiki.requireHash("contentHash", "wiki"),
                publisherKeyId = publisher.requireString("keyId", "publisher", allowBlank = false),
                publisherName = publisher.requireString("name", "publisher", allowBlank = false),
                conceptNamespace = root.requireIdentifier("conceptNamespace", "manifest"),
                conceptRegistryHash = root.requireHash("conceptRegistryHash", "manifest"),
                builderProfile = builder.requireIdentifier("profile", "builder"),
                capabilities = capabilities,
            ).also {
                builder.requireString("name", "builder", allowBlank = false)
                builder.requireString("version", "builder", allowBlank = false)
            }
        } catch (error: WikiPackageException) {
            throw error
        } catch (error: Exception) {
            throw WikiPackageException("manifest.json 格式无效", error)
        }

    private fun JsonObject.parseCapabilities(): WikiCapabilities {
        requireExactFields(CAPABILITY_FIELDS, "capabilities")
        val vectorIndex = requireBoolean("vectorIndex", "capabilities")
        if (vectorIndex) {
            throw WikiPackageException("schema v1 不支持 capabilities.vectorIndex=true")
        }
        val sourceAttachments = requireBoolean("sourceAttachments", "capabilities")
        if (sourceAttachments) {
            throw WikiPackageException("schema v1 不支持 capabilities.sourceAttachments=true")
        }
        return WikiCapabilities(
            sourceHierarchy = requireBoolean("sourceHierarchy", "capabilities"),
            sourceSearch = requireBoolean("sourceSearch", "capabilities"),
            hierarchicalSummaries = requireBoolean("hierarchicalSummaries", "capabilities"),
            termIndex = requireBoolean("termIndex", "capabilities"),
            temporalAnnotations = requireBoolean("temporalAnnotations", "capabilities"),
            crossWikiLinks = requireBoolean("crossWikiLinks", "capabilities"),
            generatedPages = GeneratedPages.fromWire(
                requireString("generatedPages", "capabilities", allowBlank = false),
            ),
            claimGraph = requireBoolean("claimGraph", "capabilities"),
            vectorIndex = vectorIndex,
            sourceAttachments = sourceAttachments,
        )
    }

    private fun JsonElement.requireObject(label: String): JsonObject =
        this as? JsonObject ?: throw WikiPackageException("$label 必须是对象")

    private fun JsonObject.requireObject(key: String, parent: String): JsonObject =
        this[key]?.requireObject("$parent.$key")
            ?: throw WikiPackageException("$parent 缺少 $key")

    private fun JsonObject.requireExactFields(expected: Set<String>, label: String) {
        val unknown = keys - expected
        if (unknown.isNotEmpty()) {
            throw WikiPackageException("$label 包含未知字段：${unknown.sorted().joinToString()}")
        }
        val missing = expected - keys
        if (missing.isNotEmpty()) {
            throw WikiPackageException("$label 缺少字段：${missing.sorted().joinToString()}")
        }
    }

    private fun JsonObject.requireString(key: String, parent: String, allowBlank: Boolean): String {
        val value = this[key] as? JsonPrimitive
        if (value == null || !value.isString || (!allowBlank && value.content.isBlank())) {
            throw WikiPackageException("$parent.$key 必须是${if (allowBlank) "字符串" else "非空字符串"}")
        }
        return value.content
    }

    private fun JsonObject.requireBoolean(key: String, parent: String): Boolean {
        val value = this[key] as? JsonPrimitive
        return value?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
            ?: throw WikiPackageException("$parent.$key 必须是布尔值")
    }

    private fun JsonObject.requireInt(key: String, parent: String): Int {
        val value = this[key] as? JsonPrimitive
        return value?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw WikiPackageException("$parent.$key 必须是整数")
    }

    private fun JsonObject.requireStringArray(key: String, parent: String): List<String> {
        val values = this[key] as? JsonArray
            ?: throw WikiPackageException("$parent.$key 必须是数组")
        return values.mapIndexed { index, value ->
            val primitive = value as? JsonPrimitive
            primitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?: throw WikiPackageException("$parent.$key[$index] 必须是字符串")
        }
    }

    private fun JsonObject.requireIdentifier(key: String, parent: String): String {
        val value = requireString(key, parent, allowBlank = false)
        if (!IDENTIFIER_PATTERN.matches(value)) {
            throw WikiPackageException("$parent.$key 不是规范标识符")
        }
        return value
    }

    private fun JsonObject.requireHash(key: String, parent: String): String {
        val value = requireString(key, parent, allowBlank = false)
        if (!HASH_PATTERN.matches(value)) {
            throw WikiPackageException("$parent.$key 必须是 64 位小写 SHA-256")
        }
        return value
    }

    private fun ByteArray.decodeStrictUtf8(label: String): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
                .toString()
        } catch (error: CharacterCodingException) {
            throw WikiPackageException("$label 不是有效 UTF-8", error)
        }

    private const val SCHEMA_VERSION = 1
    private val IDENTIFIER_PATTERN = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
    private val HASH_PATTERN = Regex("[0-9a-f]{64}")
    private val LANGUAGE_PATTERN = Regex("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
    private val MANIFEST_FIELDS = setOf(
        "type",
        "schemaVersion",
        "wiki",
        "publisher",
        "capabilities",
        "conceptNamespace",
        "conceptRegistryHash",
        "builder",
    )
    private val WIKI_FIELDS = setOf("id", "version", "title", "language", "description", "contentHash")
    private val PUBLISHER_FIELDS = setOf("keyId", "name")
    private val BUILDER_FIELDS = setOf("name", "version", "profile")
    private val CAPABILITY_FIELDS = setOf(
        "sourceHierarchy",
        "sourceSearch",
        "hierarchicalSummaries",
        "termIndex",
        "temporalAnnotations",
        "crossWikiLinks",
        "generatedPages",
        "claimGraph",
        "vectorIndex",
        "sourceAttachments",
    )
}
