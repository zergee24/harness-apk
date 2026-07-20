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
private const val MAX_AGENT_MEMORY_POLICY_TOTAL_CANDIDATE_CHARS = 96_000L
private const val MAX_MEMORY_FINGERPRINT_TERMS = 2_048

class AgentMemoryPolicy {
    class AcceptedBatch private constructor(
        internal val agentId: String,
        internal val conversationId: String,
        internal val candidates: List<AgentMemoryCandidate>,
    ) {
        companion object {
            internal fun issue(
                authority: Any,
                agentId: String,
                conversationId: String,
                candidates: List<AgentMemoryCandidate>,
            ): AcceptedBatch {
                check(authority === AGENT_MEMORY_POLICY_AUTHORITY) {
                    "关系记忆写入批次只能由策略签发"
                }
                return AcceptedBatch(agentId, conversationId, candidates.toList())
            }
        }
    }

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

        val accepted = winners.values.toList()
        return AgentMemoryPolicyResult(
            status = AgentMemoryPolicyStatus.COMPLETED,
            accepted = accepted,
            rejectedCount = candidates.size - winners.size,
            reason = "",
            acceptedBatch = AcceptedBatch.issue(
                authority = AGENT_MEMORY_POLICY_AUTHORITY,
                agentId = input.agentId.trim(),
                conversationId = input.conversationId.trim(),
                candidates = accepted,
            ),
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
            MAX_AGENT_MEMORY_POLICY_TOTAL_PROJECT_FACT_CHARS ||
            candidates.sumOf { candidate ->
                candidate.dedupeKey.length.toLong() +
                    candidate.content.length +
                    candidate.sourceMessageId.length +
                    candidate.sourceQuote.length
            } > MAX_AGENT_MEMORY_POLICY_TOTAL_CANDIDATE_CHARS
        return if (exceeded) {
            AgentMemoryPolicyResult(
                status = AgentMemoryPolicyStatus.RESOURCE_LIMIT_EXCEEDED,
                accepted = emptyList(),
                rejectedCount = candidates.size,
                reason = "关系记忆策略输入超过本地资源上限",
                acceptedBatch = null,
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
        acceptedBatch = null,
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

private val AGENT_MEMORY_POLICY_AUTHORITY = Any()

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
    if (value.length > maxChars) return false
    return value.trim().isNotEmpty()
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
                preferenceValues(candidate.content).isNotEmpty() &&
                hasNoUnsupportedCriticalLiterals(candidate.content, sourceQuote)
        AgentMemoryKind.SHARED_HISTORY ->
            !AMBIGUOUS_BILATERAL.containsMatchIn(sourceQuote) &&
                BILATERAL_SHARED_QUOTE.containsMatchIn(sourceQuote) &&
                SHARED_HISTORY_MARKER.containsMatchIn(sourceQuote) &&
                CURRENT_RELATION_CONTENT.containsMatchIn(candidate.content)
        AgentMemoryKind.RELATIONSHIP_EVENT ->
            !AMBIGUOUS_BILATERAL.containsMatchIn(sourceQuote) &&
                BILATERAL_RELATIONSHIP_QUOTE.containsMatchIn(sourceQuote) &&
                RELATIONSHIP_EVENT.containsMatchIn(sourceQuote) &&
                CURRENT_RELATION_CONTENT.containsMatchIn(candidate.content) &&
                hasNoUnsupportedCriticalLiterals(candidate.content, sourceQuote)
    }
}

private fun isSupportedBySource(
    candidate: AgentMemoryCandidate,
    sourceQuote: String,
): Boolean = when (candidate.kind) {
        AgentMemoryKind.ADDRESS_PREFERENCE -> {
            val contentValue = addressValue(candidate.content)
            val quoteValue = addressValue(sourceQuote)
            contentValue != null &&
                contentValue == quoteValue &&
                hasStructuredTextSupport(candidate.content, sourceQuote, candidate.kind)
        }
        AgentMemoryKind.USER_PREFERENCE -> {
            val contentValues = preferenceValues(candidate.content)
            val quoteValues = preferenceValues(sourceQuote)
            contentValues.isNotEmpty() &&
                quoteValues.containsAll(contentValues) &&
                hasStructuredTextSupport(candidate.content, sourceQuote, candidate.kind)
        }
        AgentMemoryKind.RELATIONSHIP_EVENT -> {
            val contentValues = relationshipValues(candidate.content)
            val quoteValues = relationshipValues(sourceQuote)
            contentValues.isNotEmpty() &&
                quoteValues.containsAll(contentValues) &&
                hasStructuredTextSupport(candidate.content, sourceQuote, candidate.kind)
        }
    AgentMemoryKind.SHARED_HISTORY -> hasStrongTextualSupport(candidate.content, sourceQuote)
}

private fun hasStrongTextualSupport(content: String, sourceQuote: String): Boolean {
    val normalizedContent = canonicalSharedText(content)
    val normalizedQuote = canonicalSharedText(sourceQuote)
    return normalizedContent.isNotEmpty() &&
        normalizedQuote.isNotEmpty() &&
        normalizedQuote.contains(normalizedContent)
}

private fun canonicalSharedText(text: String): String = normalizedComparableText(text)
    .replace("当前人物", "你")
    .replace("本人物", "你")
    .replace("用户", "我")
    .replace("双方", "我们")

private fun overlapsProjectFact(
    content: String,
    sourceQuote: String,
    projectFingerprints: List<MemoryFingerprint>,
): Boolean {
    if (projectFingerprints.isEmpty()) return false
    val candidates = listOf(memoryFingerprint(content), memoryFingerprint(sourceQuote))
    return projectFingerprints.any { fact ->
        fact.overflowed || candidates.any { candidate ->
            candidate.overflowed || fingerprintsOverlap(candidate, fact)
        }
    }
}

private fun fingerprintsOverlap(
    candidate: MemoryFingerprint,
    fact: MemoryFingerprint,
): Boolean {
    if (candidate.normalized.isEmpty() || fact.normalized.isEmpty()) return false
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
        return true
    }
    val intersection = candidate.terms.intersect(fact.terms)
    if (intersection.any(::isStrongIdentifier)) return true
    val intersectionSize = intersection.size
    if (intersectionSize == 0) return false
    val minimumSize = minOf(candidate.terms.size, fact.terms.size)
    val unionSize = candidate.terms.size + fact.terms.size - intersectionSize
    val containment = intersectionSize.toDouble() / minimumSize.coerceAtLeast(1)
    val jaccard = intersectionSize.toDouble() / unionSize.coerceAtLeast(1)
    return containment >= 0.68 || jaccard >= 0.52
}

private data class MemoryFingerprint(
    val normalized: String,
    val compactHan: String,
    val terms: Set<String>,
    val overflowed: Boolean,
)

private fun memoryFingerprint(text: String): MemoryFingerprint {
    val normalized = normalizedComparableText(text)
    val terms = BoundedFingerprintTerms()
    val asciiComponents = ASCII_COMPONENT.findAll(normalized)
        .map(MatchResult::value)
        .toList()
    ASCII_TOKEN.findAll(normalized).forEach { match ->
        val token = match.value
        terms.add(token)
        token.split(ASCII_TOKEN_SEPARATOR)
            .filter { it.length >= 2 }
            .forEach(terms::add)
        val canonical = token.replace(ASCII_TOKEN_SEPARATOR, "")
        if (canonical.length >= 2) terms.add(canonical)
    }
    asciiComponents.zipWithNext().forEach { (left, right) ->
        val canonical = left + right
        if (canonical.length >= 4) terms.add(canonical)
    }
    HAN_RUN.findAll(normalized).forEach { match ->
        addHanNgrams(match.value, terms)
    }
    val compactHan = HAN_CHARACTER.findAll(normalized)
        .joinToString(separator = "") { it.value }
    addHanNgrams(compactHan, terms)
    return MemoryFingerprint(normalized, compactHan, terms.values, terms.overflowed)
}

private class BoundedFingerprintTerms {
    val values = linkedSetOf<String>()
    var overflowed = false
        private set

    fun add(value: String) {
        if (value in values) return
        if (values.size >= MAX_MEMORY_FINGERPRINT_TERMS) {
            overflowed = true
            return
        }
        values += value
    }
}

private fun addHanNgrams(run: String, destination: BoundedFingerprintTerms) {
    for (size in 2..3) {
        if (run.length < size) continue
        for (index in 0..run.length - size) {
            destination.add(run.substring(index, index + size))
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
private val SEMANTIC_CLAUSE_DELIMITERS = setOf('。', '！', '？', '!', '?', '；', ';', '，', ',', '\n')
private val SEMANTIC_CONTRAST = Regex("(?:但是|但|而是|不过|却|改为|转为)")
private val CRITICAL_LITERAL_PATTERNS = listOf(
    Regex("(?:小|老)[\\u3400-\\u4dbf\\u4e00-\\u9fff]{1,2}"),
    Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]{1,3}(?:先生|女士|老师|总)"),
    Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]{2,6}(?:省|市|县|区|镇|村|路|街|山|河|湖|机场|车站|之行)"),
    Regex("(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二三四五六七八九十百千万亿两]+)(?:倍|次|年|月|日|岁|个|%|％)"),
    Regex("(?:[a-z]+[._/@:#-]?[0-9]+|[0-9]+[._/@:#-]?[a-z]+)"),
)

private data class DirectedValue(
    val value: String,
    val direction: String,
)

private fun addressValue(text: String): DirectedValue? {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val match = ADDRESS_VALUE.find(normalized) ?: return null
    val raw = match.groupValues.getOrNull(1) ?: return null
    val withoutSuffix = ADDRESS_VALUE_SUFFIXES.fold(raw) { value, suffix ->
        value.removeSuffix(suffix)
    }
    val value = normalizedComparableText(withoutSuffix).takeIf(String::isNotBlank) ?: return null
    return DirectedValue(value, semanticDirection(normalized, match.range))
}

private fun preferenceValues(text: String): Set<String> = buildSet {
    PREFERENCE_VALUE_PATTERNS.forEach { (value, pattern) ->
        pattern.findAll(text).forEach { match ->
            add("$value:${semanticDirection(text, match.range)}")
        }
    }
}

private fun relationshipValues(text: String): Set<String> = buildSet {
    RELATIONSHIP_VALUE_PATTERNS.forEach { (value, pattern) ->
        pattern.findAll(text).forEach { match ->
            add("$value:${semanticDirection(text, match.range)}")
        }
    }
}

private fun semanticDirection(text: String, range: IntRange): String {
    val start = semanticSegmentStart(text, range.first)
    val end = range.last.coerceAtMost(text.lastIndex)
    val context = if (end >= start) text.substring(start, end + 1) else text
    return if (NEGATIVE_DIRECTION.containsMatchIn(context)) "negative" else "positive"
}

private fun semanticSegmentStart(text: String, before: Int): Int {
    val punctuation = text
        .take(before)
        .indexOfLast { it in SEMANTIC_CLAUSE_DELIMITERS }
        .let { if (it < 0) 0 else it + 1 }
    val contrast = SEMANTIC_CONTRAST.findAll(text.substring(0, before))
        .lastOrNull()
        ?.range
        ?.last
        ?.plus(1)
        ?: 0
    return maxOf(punctuation, contrast)
}

private fun hasNoUnsupportedCriticalLiterals(content: String, sourceQuote: String): Boolean {
    val contentLiterals = criticalLiterals(content)
    if (contentLiterals.isEmpty()) return true
    return criticalLiterals(sourceQuote).containsAll(contentLiterals)
}

private fun hasStructuredTextSupport(
    content: String,
    sourceQuote: String,
    kind: AgentMemoryKind,
): Boolean {
    val contentResidual = structuredResidual(content, kind)
    if (contentResidual.isEmpty()) return true
    return structuredResidual(sourceQuote, kind).contains(contentResidual)
}

private fun structuredResidual(text: String, kind: AgentMemoryKind): String {
    var residual = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val semanticPatterns = when (kind) {
        AgentMemoryKind.ADDRESS_PREFERENCE -> listOf(ADDRESS_VALUE, ADDRESS_PREFERENCE)
        AgentMemoryKind.USER_PREFERENCE -> PREFERENCE_VALUE_PATTERNS.values
        AgentMemoryKind.RELATIONSHIP_EVENT -> RELATIONSHIP_VALUE_PATTERNS.values
        AgentMemoryKind.SHARED_HISTORY -> emptyList()
    }
    semanticPatterns.forEach { pattern ->
        residual = pattern.replace(residual, " ")
    }
    residual = STRUCTURED_ENGLISH_BOILERPLATE.replace(residual, " ")
    residual = STRUCTURED_HAN_BOILERPLATE.replace(residual, " ")
    return normalizedComparableText(residual).replace(" ", "")
}

private fun criticalLiterals(text: String): Set<String> {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return buildSet {
        CRITICAL_LITERAL_PATTERNS.forEach { pattern ->
            pattern.findAll(normalized).forEach { match ->
                add(normalizedComparableText(match.value))
            }
        }
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
            项目(?:目标|任务|事实|需求|计划|进度|目录|文件|上下文)? |
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
            销售额 |
            利润 |
            预算 |
            成本 |
            报价 |
            付款 |
            费用 |
            账款 |
            发票 |
            毛利 |
            收入 |
            支出 |
            回款 |
            \bgmv\b |
            商户 |
            供应商 |
            (?:策划|执行|审批|交付|业务)?流程 |
            (?:技术|产品|营销|运营|婚礼|活动)?方案 |
            产品(?:页面|模块|功能|版本)? |
            (?:登录|注册|支付|订单|详情|首页)页 |
            (?:页面|模块|功能|版本)(?:改版|迭代|开发|交付)? |
            (?:改版|迭代)(?:完成|计划|上线)? |
            (?:本月|季度|年度)?指标 |
            转化率 |
            加班 |
            (?:周|月|季度|年度)?(?:汇报|报告|周报|月报|季报|年报|述职|报表) |
            会议纪要 |
            工单 |
            排期 |
            里程碑 |
            (?:我们|一起|共同).{0,16}(?:完成|开发|实现|交付|上线|发布|改版|迭代|拿下|签下|达成) |
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
private val AMBIGUOUS_BILATERAL = Regex(
    "(?i)(?:我们(?:的)?(?:部门|团队|公司|项目组|小组|班级|家庭|家人|同事)|" +
        "(?:你|您)(?:的)?(?:哥哥|弟弟|姐姐|妹妹|父亲|母亲|爸爸|妈妈|丈夫|妻子|爱人|孩子|" +
        "儿子|女儿|朋友|同事|领导|老师|学生|团队|部门|公司))",
)
private val BILATERAL_SHARED_QUOTE = Regex(
    "(?i)(?:我们|咱们|我(?:和|跟|与)(?:你|您)|(?:你|您)(?:和|跟|与)我|" +
        "(?:你|您)(?:陪|帮)我|between\\s+us|\\bwe\\b)",
)
private val BILATERAL_RELATIONSHIP_QUOTE = Regex(
    "(?i)(?:我.{0,6}(?:信任|相信|原谅|亲近|疏远)(?:你|您)|" +
        "我.{0,6}(?:对|于)(?:你|您).{0,4}(?:失望|亲近|疏远)|" +
        "我把(?:你|您)当(?:朋友|伙伴)|" +
        "我们(?:的|之间的)?(?:关系|边界|界限)|" +
        "\\bi\\b.{0,12}\\b(?:trust|believe|forgive)\\b.{0,8}\\byou\\b|" +
        "\\bour\\s+(?:relationship|boundary)\\b)",
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
private val NEGATIVE_DIRECTION = Regex(
    "(?i)(?:不再|不要|不|没|无|避免|拒绝|停止|失去|降低|减少|别|请勿|never|not|do\\s+not|don't)",
)
private val STRUCTURED_HAN_BOILERPLATE = Regex(
    "(?:当前人物|本人物|这次之后|从此以后|用户|我们|咱们|以后|今后|默认|一直|通常|" +
        "希望|偏好|喜欢|需要|务必|明确|表示|要求|回答|回复|交流|沟通|表达|使用|" +
        "开始|仍然|不再|不要|避免|请勿|并且|而且|同时|另外|以及|称呼|关系|事件|" +
        "请|别|更|用|说|讲|叫|喊|称|我|你|您|了|会|为|与|和|跟|对|让|使)",
)
private val STRUCTURED_ENGLISH_BOILERPLATE = Regex(
    "(?i)\\b(?:current\\s+agent|user|we|i|you|always|default|usually|prefer|prefers|" +
        "want|wants|please|reply|answer|speak|use|clearly|stated|states|more|now|" +
        "relationship|event|and|also)\\b",
)
