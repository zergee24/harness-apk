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
            if (!isSupportedBySource(candidate, quote)) return@forEach
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
        return runCatching {
            normalizedAgentMemoryDedupeKey(candidate.dedupeKey)
        }.getOrNull()
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
        AgentMemoryKind.ADDRESS_PREFERENCE ->
            ADDRESS_PREFERENCE.containsMatchIn(sourceQuote) &&
                ADDRESS_PREFERENCE.containsMatchIn(candidate.content)
        AgentMemoryKind.USER_PREFERENCE ->
            STABLE_PREFERENCE.containsMatchIn(sourceQuote) &&
                preferenceValues(sourceQuote).isNotEmpty() &&
                preferenceValues(candidate.content).isNotEmpty()
        AgentMemoryKind.SHARED_HISTORY ->
            BILATERAL_QUOTE.containsMatchIn(sourceQuote) &&
                SHARED_HISTORY_MARKER.containsMatchIn(sourceQuote) &&
                CURRENT_RELATION_CONTENT.containsMatchIn(candidate.content)
        AgentMemoryKind.RELATIONSHIP_EVENT ->
            BILATERAL_QUOTE.containsMatchIn(sourceQuote) &&
                RELATIONSHIP_EVENT.containsMatchIn(sourceQuote) &&
                CURRENT_RELATION_CONTENT.containsMatchIn(candidate.content)
    }
}

private fun isSupportedBySource(
    candidate: AgentMemoryCandidate,
    sourceQuote: String,
): Boolean = when (candidate.kind) {
    AgentMemoryKind.ADDRESS_PREFERENCE -> {
        val contentValue = addressValue(candidate.content)
        val quoteValue = addressValue(sourceQuote)
        contentValue != null && contentValue == quoteValue
    }
    AgentMemoryKind.USER_PREFERENCE -> {
        val contentValues = preferenceValues(candidate.content)
        val quoteValues = preferenceValues(sourceQuote)
        contentValues.isNotEmpty() && quoteValues.containsAll(contentValues)
    }
    AgentMemoryKind.RELATIONSHIP_EVENT -> {
        val contentValues = relationshipValues(candidate.content)
        val quoteValues = relationshipValues(sourceQuote)
        contentValues.isNotEmpty() && quoteValues.containsAll(contentValues)
    }
    AgentMemoryKind.SHARED_HISTORY -> hasStrongTextualSupport(candidate.content, sourceQuote)
}

private fun hasStrongTextualSupport(content: String, sourceQuote: String): Boolean {
    val contentFingerprint = memoryFingerprint(content)
    val quoteFingerprint = memoryFingerprint(sourceQuote)
    if (contentFingerprint.normalized.isEmpty() || quoteFingerprint.normalized.isEmpty()) return false
    if (
        contentFingerprint.normalized.contains(quoteFingerprint.normalized) ||
        quoteFingerprint.normalized.contains(contentFingerprint.normalized)
    ) {
        return true
    }
    val contentTerms = contentFingerprint.terms - GENERIC_SUPPORT_TERMS
    val quoteTerms = quoteFingerprint.terms - GENERIC_SUPPORT_TERMS
    val intersectionSize = contentTerms.intersect(quoteTerms).size
    if (intersectionSize < 2) return false
    val contentCoverage = intersectionSize.toDouble() / contentTerms.size.coerceAtLeast(1)
    val quoteCoverage = intersectionSize.toDouble() / quoteTerms.size.coerceAtLeast(1)
    return contentCoverage >= 0.35 && quoteCoverage >= 0.25
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
            fact.normalized.contains(candidate.normalized) ||
            (
                candidate.compactHan.length >= 4 &&
                    fact.compactHan.length >= 4 &&
                    (
                        candidate.compactHan.contains(fact.compactHan) ||
                            fact.compactHan.contains(candidate.compactHan)
                        )
                )
        ) {
            return@any true
        }
        val intersection = candidate.terms.intersect(fact.terms)
        if (intersection.any(::isStrongIdentifier)) return@any true
        val intersectionSize = intersection.size
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
    val compactHan: String,
    val terms: Set<String>,
)

private fun memoryFingerprint(text: String): MemoryFingerprint {
    val normalized = normalizedComparableText(text)
    val terms = linkedSetOf<String>()
    val asciiComponents = ASCII_COMPONENT.findAll(normalized)
        .map(MatchResult::value)
        .toList()
    ASCII_TOKEN.findAll(normalized).forEach { match ->
        if (terms.size >= MAX_MEMORY_FINGERPRINT_TERMS) return@forEach
        val token = match.value
        terms += token
        token.split(ASCII_TOKEN_SEPARATOR)
            .filter { it.length >= 2 }
            .forEach { part ->
                if (terms.size < MAX_MEMORY_FINGERPRINT_TERMS) terms += part
            }
        val canonical = token.replace(ASCII_TOKEN_SEPARATOR, "")
        if (canonical.length >= 2 && terms.size < MAX_MEMORY_FINGERPRINT_TERMS) {
            terms += canonical
        }
    }
    asciiComponents.zipWithNext().forEach { (left, right) ->
        val canonical = left + right
        if (canonical.length >= 4 && terms.size < MAX_MEMORY_FINGERPRINT_TERMS) {
            terms += canonical
        }
    }
    HAN_RUN.findAll(normalized).forEach { match ->
        addHanNgrams(match.value, terms)
    }
    val compactHan = HAN_CHARACTER.findAll(normalized)
        .joinToString(separator = "") { it.value }
    addHanNgrams(compactHan, terms)
    return MemoryFingerprint(normalized, compactHan, terms)
}

private fun addHanNgrams(run: String, destination: MutableSet<String>) {
    for (size in 2..3) {
        if (run.length < size) continue
        for (index in 0..run.length - size) {
            if (destination.size >= MAX_MEMORY_FINGERPRINT_TERMS) return
            destination += run.substring(index, index + size)
        }
    }
}

private fun isStrongIdentifier(term: String): Boolean =
    term.length >= 5 && term.any(Char::isLetter) && term.any(Char::isDigit)

private fun normalizedComparableText(text: String): String = Normalizer
    .normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(NON_COMPARABLE, " ")
    .replace(WHITESPACE, " ")
    .trim()

private val ASCII_TOKEN = Regex("[a-z0-9]+(?:[._/@:#-][a-z0-9]+)*")
private val ASCII_COMPONENT = Regex("[a-z0-9]+")
private val ASCII_TOKEN_SEPARATOR = Regex("[._/@:#-]+")
private val HAN_RUN = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]+")
private val HAN_CHARACTER = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]")
private val NON_COMPARABLE = Regex("[^a-z0-9._/@:#\\-\\u3400-\\u4dbf\\u4e00-\\u9fff]+")
private val WHITESPACE = Regex("\\s+")

private fun addressValue(text: String): String? {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val raw = ADDRESS_VALUE.find(normalized)?.groupValues?.getOrNull(1) ?: return null
    val withoutSuffix = ADDRESS_VALUE_SUFFIXES.fold(raw) { value, suffix ->
        value.removeSuffix(suffix)
    }
    return normalizedComparableText(withoutSuffix).takeIf(String::isNotBlank)
}

private fun preferenceValues(text: String): Set<String> = buildSet {
    PREFERENCE_VALUE_PATTERNS.forEach { (value, pattern) ->
        if (pattern.containsMatchIn(text)) add(value)
    }
}

private fun relationshipValues(text: String): Set<String> = buildSet {
    RELATIONSHIP_VALUE_PATTERNS.forEach { (value, pattern) ->
        if (pattern.containsMatchIn(text)) add(value)
    }
}

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
            合同(?:额|金额|编号|状态)? |
            营收 |
            \bgmv\b |
            商户 |
            供应商 |
            产品(?:页面|模块|功能|版本)? |
            (?:登录|注册|支付|订单|详情|首页)页 |
            (?:页面|模块|功能|版本)(?:改版|迭代|开发|交付)? |
            (?:改版|迭代)(?:完成|计划|上线)? |
            (?:本月|季度|年度)?指标 |
            转化率 |
            (?:我们|一起|共同).{0,12}(?:完成|开发|实现|交付|上线|发布|改版|迭代|拿下|签下|达成)
                .{0,16}(?:页面|模块|功能|版本|产品|项目|需求|合同|订单|客户|商户|供应商|营收|gmv) |
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
private val ADDRESS_VALUE = Regex(
    "(?i)(?:称呼(?:用户|我)(?:为)?|叫我|喊我|称我为|call\\s+me|address\\s+me\\s+as)\\s*" +
        "([a-z0-9_\\-\\u3400-\\u4dbf\\u4e00-\\u9fff]{1,20})",
)
private val ADDRESS_VALUE_SUFFIXES = listOf("即可", "就行", "就好", "好了", "吧")
private val STABLE_PREFERENCE = Regex(
    "(?i)(?:默认|以后|一直|通常|偏好|喜欢|希望|需要|务必|请用|不要|请勿|别|prefer|always|default|never)",
)
private val PREFERENCE_VALUE_PATTERNS = linkedMapOf(
    "language:zh" to Regex(
        "(?i)(?:(?:用|使用|说|讲|回答|回复|交流|沟通).{0,4}中文|中文.{0,4}(?:回答|回复|交流|沟通|表达)|默认.{0,2}中文|chinese)",
    ),
    "language:en" to Regex(
        "(?i)(?:(?:用|使用|说|讲|回答|回复|交流|沟通).{0,4}英文|英文.{0,4}(?:回答|回复|交流|沟通|表达)|默认.{0,2}英文|english)",
    ),
    "style:concise" to Regex("(?i)(?:简洁|精简|简短|直接|concise|brief)"),
    "style:detailed" to Regex("(?i)(?:详细|展开说明|解释清楚|detailed)"),
    "tone:gentle" to Regex("(?i)(?:温和|委婉|柔和|gentle)"),
    "boundary:no-pressure" to Regex("(?i)(?:不要催|别催|不喜欢被催|不要逼|no\\s+pressure)"),
    "boundary:privacy" to Regex("(?i)(?:尊重.{0,4}隐私|不要.{0,4}隐私|privacy)"),
    "initiative:off" to Regex("(?i)(?:不要主动|别主动|未经允许不要|do\\s+not\\s+initiate)"),
    "voice:first-person" to Regex("(?i)(?:第一人称|first[ -]person)"),
)
private val BILATERAL_QUOTE = Regex(
    "(?i)(?:我们|咱们|我.{0,10}(?:你|您)|(?:你|您).{0,10}我|(?:和|跟|与)(?:你|您)|" +
        "(?:你|您)(?:陪|帮|让|对)我|我(?:对|把)(?:你|您)|\\bwe\\b|\\bi\\b.{0,20}\\byou\\b|" +
        "\\byou\\b.{0,20}\\bme\\b|between\\s+us)",
)
private val CURRENT_RELATION_CONTENT = Regex(
    "(?i)(?:我们|咱们|用户.{0,12}(?:我|当前人物)|(?:我|当前人物).{0,12}用户|" +
        "\\bwe\\b|\\buser\\b.{0,20}(?:me|current\\s+agent))",
)
private val SHARED_HISTORY_MARKER = Regex(
    "(?i)(?:曾经|经历|度过|一起|共同|那次|那天|那个|过去|记得|以前|shared|went\\s+through)",
)
private val RELATIONSHIP_EVENT = Regex(
    "(?i)(?:信任|边界|关系|朋友|伙伴|失望|原谅|疏远|亲近|不再相信|更相信|trust|boundary|relationship|friend)",
)
private val RELATIONSHIP_VALUE_PATTERNS = linkedMapOf(
    "trust" to Regex("(?i)(?:信任|相信|trust)"),
    "boundary" to Regex("(?i)(?:边界|界限|boundary)"),
    "friendship" to Regex("(?i)(?:朋友|伙伴|friend|partner)"),
    "distance" to Regex("(?i)(?:疏远|保持距离|distance)"),
    "closeness" to Regex("(?i)(?:亲近|更近|closer)"),
    "disappointment" to Regex("(?i)(?:失望|disappoint)"),
    "forgiveness" to Regex("(?i)(?:原谅|forgiv)"),
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
