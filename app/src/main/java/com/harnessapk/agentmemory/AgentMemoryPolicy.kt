package com.harnessapk.agentmemory

import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import java.text.Normalizer
import java.util.Locale

internal const val MAX_AGENT_MEMORY_POLICY_MESSAGES = 128
internal const val MAX_AGENT_MEMORY_POLICY_MESSAGE_CHARS = 12_000
internal const val MAX_AGENT_MEMORY_POLICY_PROJECT_FACTS = 128
internal const val MAX_AGENT_MEMORY_POLICY_PROJECT_FACT_CHARS = 4_000
private const val MAX_AGENT_MEMORY_POLICY_SUMMARY_CHARS = 8_000
private const val MAX_AGENT_MEMORY_POLICY_TOTAL_MESSAGE_CHARS = 128_000
private const val MAX_AGENT_MEMORY_POLICY_TOTAL_PROJECT_FACT_CHARS = 64_000
private const val MAX_MEMORY_FINGERPRINT_TERMS = 2_048

class AgentMemoryPolicy {
    fun filter(
        input: AgentMemoryExtractionInput,
        candidates: List<AgentMemoryCandidate>,
    ): List<AgentMemoryCandidate> = evaluate(input, candidates).accepted

    fun evaluate(
        input: AgentMemoryExtractionInput,
        candidates: List<AgentMemoryCandidate>,
    ): AgentMemoryPolicyResult {
        resourceFailure(input, candidates)?.let { return it }
        inputFailure(input, candidates.size)?.let { return it }

        val messagesById = input.recentMessages.associateBy { it.id.trim() }
        if (messagesById.size != input.recentMessages.size) {
            return invalidInput("消息快照 ID 不唯一", candidates.size)
        }
        val projectFingerprints = input.projectFacts
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::memoryFingerprint)
            .toList()
        val winners = linkedMapOf<Pair<AgentMemoryKind, String>, AgentMemoryCandidate>()

        candidates.forEach { candidate ->
            val normalizedKey = validatedCandidateKey(candidate) ?: return@forEach
            val source = messagesById[candidate.sourceMessageId.trim()] ?: return@forEach
            if (source.role != MessageRole.USER || source.status != MessageStatus.SUCCEEDED) {
                return@forEach
            }
            val quote = normalizedEvidenceText(candidate.sourceQuote)
            val sourceText = normalizedEvidenceText(source.content)
            if (quote.isEmpty() || !sourceText.contains(quote)) return@forEach
            if (!isSupportedBySource(candidate.content, quote)) return@forEach
            if (!isAllowedRelationshipFact(candidate, quote)) return@forEach
            if (overlapsProjectFact(candidate.content, quote, projectFingerprints)) return@forEach

            val key = candidate.kind to normalizedKey
            val current = winners[key]
            if (current == null || candidate.confidence > current.confidence) {
                winners[key] = candidate
            }
        }

        return AgentMemoryPolicyResult(
            status = AgentMemoryPolicyStatus.COMPLETED,
            accepted = winners.values.toList(),
            rejectedCount = candidates.size - winners.size,
            reason = "",
        )
    }

    private fun resourceFailure(
        input: AgentMemoryExtractionInput,
        candidates: List<AgentMemoryCandidate>,
    ): AgentMemoryPolicyResult? {
        val exceeded = candidates.size > MAX_AGENT_MEMORY_CANDIDATES_PER_MERGE ||
            input.recentMessages.size > MAX_AGENT_MEMORY_POLICY_MESSAGES ||
            input.projectFacts.size > MAX_AGENT_MEMORY_POLICY_PROJECT_FACTS ||
            input.conversationSummary.length > MAX_AGENT_MEMORY_POLICY_SUMMARY_CHARS ||
            input.recentMessages.any { it.content.length > MAX_AGENT_MEMORY_POLICY_MESSAGE_CHARS } ||
            input.projectFacts.any { it.length > MAX_AGENT_MEMORY_POLICY_PROJECT_FACT_CHARS } ||
            input.recentMessages.sumOf { it.content.length } >
            MAX_AGENT_MEMORY_POLICY_TOTAL_MESSAGE_CHARS ||
            input.projectFacts.sumOf(String::length) >
            MAX_AGENT_MEMORY_POLICY_TOTAL_PROJECT_FACT_CHARS
        return if (exceeded) {
            AgentMemoryPolicyResult(
                status = AgentMemoryPolicyStatus.RESOURCE_LIMIT_EXCEEDED,
                accepted = emptyList(),
                rejectedCount = candidates.size,
                reason = "关系记忆策略输入超过本地资源上限",
            )
        } else {
            null
        }
    }

    private fun inputFailure(
        input: AgentMemoryExtractionInput,
        candidateCount: Int,
    ): AgentMemoryPolicyResult? {
        if (!isValidId(input.agentId) || !isValidId(input.conversationId)) {
            return invalidInput("关系记忆策略作用域无效", candidateCount)
        }
        if (input.projectId != null && !isValidId(input.projectId)) {
            return invalidInput("关系记忆策略项目作用域无效", candidateCount)
        }
        if (
            input.recentMessages.any { message ->
                !isValidId(message.id) ||
                    message.conversationId.trim() != input.conversationId.trim()
            }
        ) {
            return invalidInput("消息快照作用域无效", candidateCount)
        }
        return null
    }

    private fun invalidInput(reason: String, rejectedCount: Int) = AgentMemoryPolicyResult(
        status = AgentMemoryPolicyStatus.INVALID_INPUT,
        accepted = emptyList(),
        rejectedCount = rejectedCount,
        reason = reason,
    )

    private fun validatedCandidateKey(candidate: AgentMemoryCandidate): String? {
        if (
            !isValidField(candidate.dedupeKey, MAX_AGENT_MEMORY_DEDUPE_KEY_CHARS) ||
            !isValidField(candidate.content, MAX_AGENT_MEMORY_CONTENT_CHARS) ||
            !isValidField(candidate.sourceMessageId, MAX_AGENT_MEMORY_ID_CHARS) ||
            !isValidField(candidate.sourceQuote, MAX_AGENT_MEMORY_SOURCE_QUOTE_CHARS) ||
            !candidate.confidence.isFinite() ||
            candidate.confidence !in 0.0..1.0
        ) {
            return null
        }
        return candidate.dedupeKey.trim().lowercase(Locale.ROOT)
    }
}

internal fun normalizedMemoryTerms(text: String): Set<String> = memoryFingerprint(text).terms

internal fun projectFactFingerprints(projectContext: List<String>): Set<String> = buildSet {
    projectContext.forEach { fact ->
        memoryFingerprint(fact).terms.forEach { term ->
            if (size < MAX_MEMORY_FINGERPRINT_TERMS) add(term)
        }
    }
}

private fun isValidId(value: String): Boolean = isValidField(value, MAX_AGENT_MEMORY_ID_CHARS)

private fun isValidField(value: String, maxChars: Int): Boolean {
    val trimmed = value.trim()
    return trimmed.isNotEmpty() && trimmed.length <= maxChars
}

private fun normalizedEvidenceText(text: String): String = text
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .trim()

private fun isAllowedRelationshipFact(
    candidate: AgentMemoryCandidate,
    sourceQuote: String,
): Boolean {
    val combined = "${candidate.content}\n$sourceQuote"
    if (
        PROJECT_ARTIFACT_OR_BUSINESS.containsMatchIn(combined) ||
        TRANSIENT_REQUEST.containsMatchIn(combined) ||
        HIDDEN_REASONING.containsMatchIn(combined) ||
        PERSONA_SOURCE_FACT.containsMatchIn(combined) ||
        PURE_GREETING.matches(normalizedComparableText(combined)) ||
        IMMEDIATE_EMOTION.containsMatchIn(combined)
    ) {
        return false
    }
    return when (candidate.kind) {
        AgentMemoryKind.ADDRESS_PREFERENCE -> ADDRESS_PREFERENCE.containsMatchIn(combined)
        AgentMemoryKind.USER_PREFERENCE ->
            STABLE_PREFERENCE.containsMatchIn(combined) &&
                INTERACTION_PREFERENCE.containsMatchIn(combined)
        AgentMemoryKind.SHARED_HISTORY ->
            SHARED_PARTICIPANTS.containsMatchIn(combined) &&
                SHARED_HISTORY_MARKER.containsMatchIn(combined)
        AgentMemoryKind.RELATIONSHIP_EVENT -> RELATIONSHIP_EVENT.containsMatchIn(combined)
    }
}

private fun isSupportedBySource(content: String, sourceQuote: String): Boolean {
    val contentFingerprint = memoryFingerprint(content)
    val quoteFingerprint = memoryFingerprint(sourceQuote)
    if (contentFingerprint.normalized.isEmpty() || quoteFingerprint.normalized.isEmpty()) return false
    if (
        contentFingerprint.normalized.contains(quoteFingerprint.normalized) ||
        quoteFingerprint.normalized.contains(contentFingerprint.normalized)
    ) {
        return true
    }
    return contentFingerprint.terms
        .intersect(quoteFingerprint.terms)
        .any { term -> term !in GENERIC_SUPPORT_TERMS && term.length >= 2 }
}

private fun overlapsProjectFact(
    content: String,
    sourceQuote: String,
    projectFingerprints: List<MemoryFingerprint>,
): Boolean {
    if (projectFingerprints.isEmpty()) return false
    val candidate = memoryFingerprint("$content\n$sourceQuote")
    return projectFingerprints.any { fact ->
        if (candidate.normalized.isEmpty() || fact.normalized.isEmpty()) return@any false
        if (
            candidate.normalized.contains(fact.normalized) ||
            fact.normalized.contains(candidate.normalized)
        ) {
            return@any true
        }
        val intersectionSize = candidate.terms.intersect(fact.terms).size
        if (intersectionSize == 0) return@any false
        val minimumSize = minOf(candidate.terms.size, fact.terms.size)
        val unionSize = candidate.terms.size + fact.terms.size - intersectionSize
        val containment = intersectionSize.toDouble() / minimumSize.coerceAtLeast(1)
        val jaccard = intersectionSize.toDouble() / unionSize.coerceAtLeast(1)
        containment >= 0.68 || jaccard >= 0.52
    }
}

private data class MemoryFingerprint(
    val normalized: String,
    val terms: Set<String>,
)

private fun memoryFingerprint(text: String): MemoryFingerprint {
    val normalized = normalizedComparableText(text)
    val terms = linkedSetOf<String>()
    ASCII_TOKEN.findAll(normalized).forEach { match ->
        if (terms.size >= MAX_MEMORY_FINGERPRINT_TERMS) return@forEach
        val token = match.value
        terms += token
        token.split(ASCII_TOKEN_SEPARATOR)
            .filter { it.length >= 2 }
            .forEach { part ->
                if (terms.size < MAX_MEMORY_FINGERPRINT_TERMS) terms += part
            }
    }
    HAN_RUN.findAll(normalized).forEach { match ->
        val run = match.value
        for (size in 2..3) {
            if (run.length < size) continue
            for (index in 0..run.length - size) {
                if (terms.size >= MAX_MEMORY_FINGERPRINT_TERMS) break
                terms += run.substring(index, index + size)
            }
        }
    }
    return MemoryFingerprint(normalized, terms)
}

private fun normalizedComparableText(text: String): String = Normalizer
    .normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(NON_COMPARABLE, " ")
    .replace(WHITESPACE, " ")
    .trim()

private val ASCII_TOKEN = Regex("[a-z0-9]+(?:[._/@:#-][a-z0-9]+)*")
private val ASCII_TOKEN_SEPARATOR = Regex("[._/@:#-]+")
private val HAN_RUN = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]+")
private val NON_COMPARABLE = Regex("[^a-z0-9._/@:#\\-\\u3400-\\u4dbf\\u4e00-\\u9fff]+")
private val WHITESPACE = Regex("\\s+")

private val PROJECT_ARTIFACT_OR_BUSINESS = Regex(
    pattern = """
        (?ix)
        (?:
            [a-z0-9_./\\-]+\.(?:md|markdown|kt|java|py|js|ts|tsx|json|ya?ml|xml|gradle|apk)\b |
            (?:^|\s)[/\\](?:[a-z0-9_.-]+[/\\])+[a-z0-9_.-]* |
            \bcommit\s+[0-9a-f]{7,40}\b |
            \b(?:git|branch|pull\s*request|release|deploy|deployment|endpoint|api|kpi|crm)\b |
            项目(?:目标|任务|事实|需求|计划|进度|目录|文件|上下文) |
            (?:代码|文件)(?:目录|路径|变更) |
            需求(?:项|文档|排期)? |
            任务(?:是|为|清单|进度)? |
            接口(?:地址|文档|参数)? |
            (?:发布|上线|部署)(?:日期|时间|计划)? |
            技术方案 |
            业务(?:事实|指标|目标|数据)? |
            客户(?:名称|是|为)? |
            订单(?:号|金额|状态)? |
            (?:本月|季度|年度)?指标 |
            转化率 |
            我们决定 |
            决定(?:本周|本月|采用|上线|发布)
        )
    """.trimIndent(),
)
private val TRANSIENT_REQUEST = Regex(
    "(?:今天|今晚|此刻|眼下|这次临时|本次临时|临时|仅本轮|只在本轮|当前话题|本次输出|这一次只)",
)
private val HIDDEN_REASONING = Regex(
    "(?:隐藏推理|内部推理|思维链|模型内部|先在心里|决策过程|推理过程)",
)
private val PERSONA_SOURCE_FACT = Regex(
    "(?:人物包|包内资料|原始资料|全集|著作|生平|世界观|历史材料|人物资料|立场是|主张是)",
)
private val IMMEDIATE_EMOTION = Regex(
    "(?:现在|此刻|刚刚|眼下).{0,10}(?:烦|难过|开心|生气|焦虑|疲惫|累)",
)
private val PURE_GREETING = Regex(
    "(?:你好|您好|嗨|hello|hi|谢谢|感谢|再见|继续|接着)[.!。！ ]*",
)
private val ADDRESS_PREFERENCE = Regex(
    "(?i)(?:称呼|叫我|喊我|称我|怎么叫|call\\s+me|address\\s+me|refer\\s+to\\s+me)",
)
private val STABLE_PREFERENCE = Regex(
    "(?i)(?:默认|以后|一直|通常|偏好|喜欢|希望|请用|不要|别|prefer|always|default)",
)
private val INTERACTION_PREFERENCE = Regex(
    "(?i)(?:回答|回复|中文|英文|语言|语气|表达|解释|建议|沟通|交流|提醒|催促|主动|简洁|详细|直接|隐私|response|reply|language|tone|style)",
)
private val SHARED_PARTICIPANTS = Regex(
    "(?i)(?:我们|我和你|你和我|一起|共同|between\\s+us|we\\s+)",
)
private val SHARED_HISTORY_MARKER = Regex(
    "(?i)(?:曾经|经历|度过|一起|共同|那次|那天|那个|过去|记得|以前|shared|went\\s+through)",
)
private val RELATIONSHIP_EVENT = Regex(
    "(?i)(?:信任|边界|关系|朋友|伙伴|失望|原谅|疏远|亲近|不再相信|更相信|trust|boundary|relationship|friend)",
)
private val GENERIC_SUPPORT_TERMS = setOf(
    "用户",
    "希望",
    "以后",
    "默认",
    "使用",
    "回答",
    "回复",
    "我们",
    "一起",
    "明确",
    "表示",
    "请用",
)
