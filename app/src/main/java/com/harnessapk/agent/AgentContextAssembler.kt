package com.harnessapk.agent

import com.harnessapk.agentmemory.AgentMemory
import com.harnessapk.agentmemory.AgentMemoryKind
import kotlinx.coroutines.CancellationException

data class AgentContextPackage(
    val agentId: String,
    val version: Int,
    val schemaVersion: Int,
    val persona: String,
    val identity: V2Identity?,
    val voice: V2Voice?,
    val stances: List<V2Worldview>,
    val episodes: List<V2Episode>,
    val examples: List<V2Example>,
    val opener: String?,
    val requiredCorpusCount: Int,
    val installedRequiredCorpusCount: Int,
    val missingOptionalCoverage: List<String>,
)

data class AgentHierarchyRoute(
    val nodeKey: String,
    val sourceId: String,
    val topLevelId: String,
    val physicalScore: Int,
)

data class AgentRetrievalChunk(
    val chunkKey: String,
    val chunkId: String,
    val sourceId: String,
    val sourceTitle: String,
    val period: String,
    val authorship: V2Authorship,
    val location: String,
    val text: String,
    val duplicateGroup: String,
    val physicalScore: Int,
    val routeIds: Set<String> = emptySet(),
)

interface AgentContextDataSource {
    suspend fun loadPackage(agentId: String, version: Int): AgentContextPackage?

    suspend fun searchHierarchy(
        agentId: String,
        version: Int,
        query: String,
        limit: Int,
    ): List<AgentHierarchyRoute>

    suspend fun searchChunks(
        agentId: String,
        version: Int,
        query: String,
        limit: Int,
        hierarchyRoutes: List<AgentHierarchyRoute>,
    ): List<AgentRetrievalChunk>
}

fun interface AgentRelationshipMemoryProvider {
    suspend fun list(agentId: String): List<AgentMemory>
}

class AgentContextAssembler(
    private val source: AgentContextDataSource,
    private val policy: AgentRetrievalPolicy = AgentRetrievalPolicy(),
    private val relationshipMemoryProvider: AgentRelationshipMemoryProvider =
        AgentRelationshipMemoryProvider { emptyList() },
) {
    suspend fun assemble(request: AgentContextRequest): AgentRuntimeContext? {
        val packageData = source.loadPackage(request.agentId, request.version) ?: return null
        if (packageData.installedRequiredCorpusCount < packageData.requiredCorpusCount) return null
        val relationshipMemoryLines = try {
            selectAgentRelationshipMemoryLines(
                relationshipMemoryProvider.list(request.agentId)
                    .filter { it.agentId == request.agentId },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }

        if (packageData.schemaVersion < 2) {
            return assembleV1(request, packageData, relationshipMemoryLines)
        }

        val intent = policy.intentFor(request.query)
        val budget = policy.budgetFor(intent)
        val selectionBudget = CharacterSelectionBudget(budget.characterCount)
        val hierarchyRoutes = if (intent == AgentQueryIntent.GLOBAL && budget.chunkCount > 0) {
            source.searchHierarchy(
                request.agentId,
                request.version,
                request.query,
                budget.chunkCount * CANDIDATE_MULTIPLIER,
            ).sortedWith(routeComparator()).distinctBy(AgentHierarchyRoute::nodeKey)
        } else {
            emptyList()
        }
        val candidates = if (budget.chunkCount == 0) {
            emptyList()
        } else {
            source.searchChunks(
                request.agentId,
                request.version,
                request.query,
                budget.chunkCount * CANDIDATE_MULTIPLIER,
                hierarchyRoutes,
            )
        }
        var stances = emptyList<V2Worldview>()
        var episodes = emptyList<V2Episode>()
        var examples = emptyList<V2Example>()
        var selectedChunks = emptyList<AgentRetrievalChunk>()

        fun selectStructuredAssets() {
            stances = selectAssets(
                packageData.stances,
                budget.stanceCount,
                request.query,
                selectionBudget,
                V2Worldview::id,
                { stance -> listOf(stance.topic, stance.statement, stance.period) + stance.aliases + stance.conditions },
                ::renderStance,
            )
            episodes = selectAssets(
                packageData.episodes,
                budget.episodeCount,
                request.query,
                selectionBudget,
                V2Episode::id,
                { episode ->
                    listOf(episode.period, episode.location, episode.summary, episode.meaning) + episode.participants
                },
                ::renderEpisode,
            )
            examples = selectAssets(
                packageData.examples,
                budget.exampleCount,
                request.query,
                selectionBudget,
                V2Example::id,
                { example -> listOf(example.intent, example.user, example.assistant) + example.styleTags },
                ::renderExample,
            )
        }

        fun selectRawChunks() {
            selectedChunks = rerankChunks(
                candidates = candidates,
                intent = intent,
                budget = budget,
                selectionBudget = selectionBudget,
                hierarchyRoutes = hierarchyRoutes,
            )
        }

        if (intent == AgentQueryIntent.EXACT_FACT) {
            selectRawChunks()
            selectStructuredAssets()
        } else {
            selectStructuredAssets()
            selectRawChunks()
        }
        val evidence = selectedChunks.map { chunk ->
            AgentEvidence(
                chunkId = chunk.chunkId,
                sourceTitle = chunk.sourceTitle,
                location = chunk.location,
                text = chunk.text,
                score = chunk.physicalScore,
                chunkKey = chunk.chunkKey,
            )
        }
        val routeKeys = hierarchyRoutes.map(AgentHierarchyRoute::stableRouteId).toSet()
        val selectedRouteIds = selectedChunks.flatMap(AgentRetrievalChunk::routeIds)
            .filter(routeKeys::contains)
            .distinct()
            .sorted()
            .take(MAX_DIAGNOSTIC_ITEMS)
        val allSelectedAssetIds = buildList {
            addAll(stances.map(V2Worldview::id))
            addAll(episodes.map(V2Episode::id))
            addAll(examples.map(V2Example::id))
        }.sorted()
        val diagnostics = AgentRuntimeDiagnostics(
            intent = intent,
            selectedAssetIds = allSelectedAssetIds.take(MAX_DIAGNOSTIC_ITEMS),
            selectedAssetTotalCount = allSelectedAssetIds.size,
            selectedChunkKeys = selectedChunks.map(AgentRetrievalChunk::chunkKey).toList(),
            selectedRouteIds = selectedRouteIds,
            sourceCount = selectedChunks.map(AgentRetrievalChunk::sourceId).distinct().size,
            periodCount = selectedChunks.map(AgentRetrievalChunk::period).filter(String::isNotBlank).distinct().size,
            duplicateGroupCount = selectedChunks.map(AgentRetrievalChunk::stableDuplicateKey).distinct().size,
            characterBudget = budget.characterCount,
            selectedCharacterCount = selectionBudget.used,
            missingOptionalCoverage = packageData.missingOptionalCoverage.distinct().sorted()
                .take(MAX_DIAGNOSTIC_ITEMS),
            hierarchyRoutingUsed = hierarchyRoutes.isNotEmpty(),
        )
        val relationships = if (intent == AgentQueryIntent.RELATIONSHIP) {
            selectRelationships(packageData.identity, request.query)
        } else {
            emptyList()
        }
        return AgentRuntimeContext(
            agentId = request.agentId,
            version = request.version,
            systemPrompt = buildV2Prompt(
                request,
                packageData,
                relationships,
                stances,
                episodes,
                examples,
                selectedChunks,
                relationshipMemoryLines,
            ),
            evidence = evidence.toList(),
            diagnostics = diagnostics,
        )
    }

    private suspend fun assembleV1(
        request: AgentContextRequest,
        packageData: AgentContextPackage,
        relationshipMemoryLines: List<String>,
    ): AgentRuntimeContext {
        val intent = policy.intentFor(request.query)
        val selectedChunks = if (intent == AgentQueryIntent.RELATIONSHIP) {
            emptyList()
        } else {
            source.searchChunks(
                request.agentId,
                request.version,
                request.query,
                V1_EVIDENCE_LIMIT * CANDIDATE_MULTIPLIER,
                emptyList(),
            ).distinctBy(AgentRetrievalChunk::chunkKey)
                .sortedWith(chunkComparator(preferDirect = false))
                .take(V1_EVIDENCE_LIMIT)
        }
        val evidence = selectedChunks.map { chunk ->
            AgentEvidence(
                chunkId = chunk.chunkId,
                sourceTitle = chunk.sourceTitle,
                location = chunk.location,
                text = chunk.text,
                score = chunk.physicalScore,
                chunkKey = chunk.chunkKey,
            )
        }
        return AgentRuntimeContext(
            agentId = request.agentId,
            version = request.version,
            systemPrompt = buildString {
                appendLine("你以包内人物身份使用第一人称与用户交谈；这是基于资料模拟，不得冒充真实人物。")
                appendLine("历史事实、人物经历和核心立场必须由人物资料支持；未知时明确证据不足，不得补写。")
                appendLine()
                appendLine("身份内核：")
                appendLine(packageData.persona.trim())
                appendAgentRelationshipMemorySection(relationshipMemoryLines)
                if (packageData.stances.isNotEmpty()) {
                    appendLine()
                    appendLine("人物立场：")
                    packageData.stances.forEach { appendLine(it.statement) }
                }
                if (evidence.isNotEmpty()) {
                    appendLine()
                    appendLine("可用于本轮事实与立场判断的原始证据：")
                    evidence.forEach { appendLine(it.text.trim()) }
                }
            },
            evidence = evidence,
            diagnostics = AgentRuntimeDiagnostics(
                intent = intent,
                selectedAssetIds = packageData.stances.map(V2Worldview::id).sorted().take(MAX_DIAGNOSTIC_ITEMS),
                selectedAssetTotalCount = packageData.stances.size,
                selectedChunkKeys = selectedChunks.map(AgentRetrievalChunk::chunkKey),
                sourceCount = selectedChunks.map(AgentRetrievalChunk::sourceId).distinct().size,
                periodCount = selectedChunks.map(AgentRetrievalChunk::period).filter(String::isNotBlank).distinct().size,
                duplicateGroupCount = selectedChunks.map(AgentRetrievalChunk::stableDuplicateKey).distinct().size,
                missingOptionalCoverage = packageData.missingOptionalCoverage.distinct().sorted()
                    .take(MAX_DIAGNOSTIC_ITEMS),
            ),
        )
    }

    private fun rerankChunks(
        candidates: List<AgentRetrievalChunk>,
        intent: AgentQueryIntent,
        budget: AgentRetrievalBudget,
        selectionBudget: CharacterSelectionBudget,
        hierarchyRoutes: List<AgentHierarchyRoute>,
    ): List<AgentRetrievalChunk> {
        if (budget.chunkCount == 0) return emptyList()
        val preferDirect = intent in setOf(AgentQueryIntent.STANCE_METHOD, AgentQueryIntent.TEMPORAL, AgentQueryIntent.GLOBAL)
        val sorted = candidates.distinctBy(AgentRetrievalChunk::chunkKey).sortedWith(chunkComparator(preferDirect))
        val selected = mutableListOf<AgentRetrievalChunk>()
        val sourceCounts = mutableMapOf<String, Int>()
        val duplicateGroups = mutableSetOf<String>()

        fun tryAdd(chunk: AgentRetrievalChunk): Boolean {
            if (selected.size >= budget.chunkCount) return false
            if (sourceCounts.getOrDefault(chunk.sourceId, 0) >= budget.perSourceCount) return false
            if (chunk.stableDuplicateKey() in duplicateGroups) return false
            if (!selectionBudget.tryUse(renderEvidenceBlock(chunk).length)) return false
            selected += chunk
            sourceCounts[chunk.sourceId] = sourceCounts.getOrDefault(chunk.sourceId, 0) + 1
            duplicateGroups += chunk.stableDuplicateKey()
            return true
        }

        planDiversityReservations(
            candidates = sorted,
            budget = budget,
            remainingCharacters = selectionBudget.remaining,
            routeIds = hierarchyRoutes.map(AgentHierarchyRoute::stableRouteId).toSet(),
        ).forEach { reserved -> check(tryAdd(reserved)) }
        sorted.filterNot(selected::contains).forEach(::tryAdd)
        return selected
    }

    private fun buildV2Prompt(
        request: AgentContextRequest,
        packageData: AgentContextPackage,
        relationships: List<V2Relationship>,
        stances: List<V2Worldview>,
        episodes: List<V2Episode>,
        examples: List<V2Example>,
        chunks: List<AgentRetrievalChunk>,
        relationshipMemoryLines: List<String>,
    ): String = buildString {
        appendLine("你以包内人物身份使用第一人称与用户交谈；这是基于资料模拟，不得冒充真实人物。")
        appendLine()
        appendLine("第一人称身份与时间边界：")
        packageData.identity?.let { identity ->
            appendLine("自称：${identity.selfNames.joinToString("、").ifBlank { "遵循身份内核" }}")
            appendLine("时间范围：${identity.timeHorizon.ifBlank { "仅限包内资料覆盖时期" }}")
            if (identity.roles.isNotEmpty()) appendLine("角色：${identity.roles.joinToString("、")}")
            if (relationships.isNotEmpty()) {
                appendLine("本轮相关关系：")
                relationships.forEach { relationship ->
                    appendLine(renderRelationship(relationship))
                }
            }
        }
        appendLine(packageData.persona.trim())
        packageData.voice?.let { voice ->
            appendLine()
            appendLine("可执行表达方式：")
            if (voice.defaultForm.isNotBlank()) appendLine("默认形式：${voice.defaultForm}")
            if (voice.sentenceRhythm.isNotEmpty()) appendLine("句式节奏：${voice.sentenceRhythm.joinToString("；")}")
            if (voice.rhetoricalMoves.isNotEmpty()) appendLine("表达动作：${voice.rhetoricalMoves.joinToString("；")}")
            if (voice.preferredTerms.isNotEmpty()) appendLine("偏好用词：${voice.preferredTerms.joinToString("；")}")
            if (voice.avoidPatterns.isNotEmpty()) appendLine("避免模式：${voice.avoidPatterns.joinToString("；")}")
        }
        appendLine()
        appendLine("证据与未知事实规则：")
        appendLine("历史事实、人物经历和核心立场只使用本提示中的人物资产与原始证据；缺少证据时明确不知道，不用通用知识补写。")
        appendLine("次级资料只能作为背景，不能写成第一人称记忆，也不能用于模仿人物声音。层级摘要只用于检索路由，不作为事实证据。")
        appendLine()
        appendLine("会话记忆：")
        appendLine(request.conversationMemory.trim().ifBlank { "当前没有压缩会话记忆；承接可见对话即可。" })
        appendAgentRelationshipMemorySection(relationshipMemoryLines)
        if (request.projectContext.isNotBlank() || request.sessionContext.isNotBlank()) {
            appendLine()
            appendLine("当前会话的项目与会话上下文：")
            request.projectContext.trim().takeIf(String::isNotBlank)?.let(::appendLine)
            request.sessionContext.trim().takeIf(String::isNotBlank)?.let(::appendLine)
        }
        if (stances.isNotEmpty()) {
            appendLine()
            appendLine("本轮相关立场：")
            stances.forEach { appendLine(renderStance(it)) }
        }
        if (episodes.isNotEmpty()) {
            appendLine()
            appendLine("本轮相关经历：")
            episodes.forEach { appendLine(renderEpisode(it)) }
        }
        if (examples.isNotEmpty()) {
            appendLine()
            appendLine("本轮表达示例：")
            examples.forEach { appendLine(renderExample(it)) }
        }
        if (chunks.isNotEmpty()) {
            appendLine()
            appendLine("本轮可用原始证据：")
            chunks.forEach { chunk -> append(renderEvidenceBlock(chunk)) }
        }
        appendLine()
        appendLine("回答应简洁自然，先直接回应；仅在核心主张需要时沿用应用既有引用约定。不要输出内部诊断、chunk 术语或机械编号来源段落。")
        appendLine("不要把模拟边界做成每次回答的固定免责声明；只有用户询问真实性或关键主张确实缺少证据时才说明。")
    }.trim()

    private companion object {
        const val CANDIDATE_MULTIPLIER = 4
        const val V1_EVIDENCE_LIMIT = 8
        const val MAX_DIAGNOSTIC_ITEMS = 64
    }
}

internal fun selectAgentRelationshipMemoryLines(memories: List<AgentMemory>): List<String> {
    val sorted = memories.mapNotNull { memory ->
        normalizeAgentRelationshipMemoryContent(memory.content)?.let { content ->
            SelectedAgentRelationshipMemory(memory, content)
        }
    }.sortedWith(
        compareBy<SelectedAgentRelationshipMemory> { it.memory.kind.relationshipPriority() }
            .thenByDescending { it.memory.userEdited }
            .thenByDescending { it.memory.updatedAt }
            .thenBy { it.memory.id },
    )
    val seenContents = mutableSetOf<String>()
    val lines = mutableListOf<String>()
    var usedContentCharacters = 0
    for (selected in sorted) {
        if (lines.size >= MAX_AGENT_RELATIONSHIP_MEMORIES) break
        if (!seenContents.add(selected.content)) continue
        if (usedContentCharacters + selected.content.length > MAX_AGENT_RELATIONSHIP_TOTAL_CHARS) break
        lines += "- ${selected.memory.kind.relationshipLabel()}：${selected.content}"
        usedContentCharacters += selected.content.length
    }
    return lines
}

private data class SelectedAgentRelationshipMemory(
    val memory: AgentMemory,
    val content: String,
)

private fun normalizeAgentRelationshipMemoryContent(content: String): String? {
    val withoutTags = AGENT_RELATIONSHIP_XML_TAG.replace(content, " ")
        .replace('<', ' ')
        .replace('>', ' ')
        .replace('`', ' ')
    val singleLine = buildString(withoutTags.length) {
        withoutTags.forEach { character ->
            when {
                character.isWhitespace() -> append(' ')
                character.isISOControl() -> Unit
                else -> append(character)
            }
        }
    }.replace(AGENT_RELATIONSHIP_WHITESPACE, " ").trim()
    if (singleLine.isEmpty()) return null
    return singleLine.takeWithoutSplittingSurrogate(MAX_AGENT_RELATIONSHIP_ENTRY_CHARS)
        .trim()
        .takeIf(String::isNotEmpty)
}

private fun String.takeWithoutSplittingSurrogate(maxChars: Int): String {
    if (length <= maxChars) return this
    val truncated = take(maxChars)
    return if (truncated.lastOrNull()?.isHighSurrogate() == true) truncated.dropLast(1) else truncated
}

private fun AgentMemoryKind.relationshipPriority(): Int = when (this) {
    AgentMemoryKind.ADDRESS_PREFERENCE -> 0
    AgentMemoryKind.USER_PREFERENCE -> 1
    AgentMemoryKind.RELATIONSHIP_EVENT -> 2
    AgentMemoryKind.SHARED_HISTORY -> 3
}

private fun AgentMemoryKind.relationshipLabel(): String = when (this) {
    AgentMemoryKind.ADDRESS_PREFERENCE -> "称呼偏好"
    AgentMemoryKind.USER_PREFERENCE -> "稳定偏好"
    AgentMemoryKind.RELATIONSHIP_EVENT -> "关系变化"
    AgentMemoryKind.SHARED_HISTORY -> "共同经历"
}

private fun StringBuilder.appendAgentRelationshipMemorySection(lines: List<String>) {
    if (lines.isEmpty()) return
    appendLine()
    appendLine("我们之间：")
    appendLine("以下是用户可编辑的关系事实，只用于称呼、稳定偏好和关系连续性。")
    appendLine("当前用户的明确表达优先；不要把这些内容当作人物历史、项目事实或未列出的推断。")
    lines.forEach(::appendLine)
}

private const val MAX_AGENT_RELATIONSHIP_MEMORIES = 12
private const val MAX_AGENT_RELATIONSHIP_ENTRY_CHARS = 240
private const val MAX_AGENT_RELATIONSHIP_TOTAL_CHARS = 1_600
private val AGENT_RELATIONSHIP_XML_TAG = Regex("</?[A-Za-z][^>]{0,128}>")
private val AGENT_RELATIONSHIP_WHITESPACE = Regex("\\s+")

private class CharacterSelectionBudget(private val limit: Int) {
    var used: Int = 0
        private set
    val remaining: Int
        get() = limit - used

    fun tryUse(characters: Int): Boolean {
        if (characters < 0 || used + characters > limit) return false
        used += characters
        return true
    }
}

private fun <T> selectAssets(
    assets: List<T>,
    limit: Int,
    query: String,
    characterBudget: CharacterSelectionBudget,
    id: (T) -> String,
    searchableText: (T) -> List<String>,
    render: (T) -> String,
): List<T> {
    if (limit == 0) return emptyList()
    val terms = retrievalTerms(query)
    val scored = assets.map { asset ->
        asset to searchableText(asset).sumOf { value -> terms.count(value.lowercase()::contains) }
    }.filter { (_, score) -> score > 0 }
    val sorted = scored.sortedWith(
        compareByDescending<Pair<T, Int>>(Pair<T, Int>::second).thenBy { (asset, _) -> id(asset) },
    ).map(Pair<T, Int>::first)
    return buildList {
        sorted.forEach { asset ->
            if (size < limit && characterBudget.tryUse(render(asset).length + 1)) add(asset)
        }
    }
}

private fun planDiversityReservations(
    candidates: List<AgentRetrievalChunk>,
    budget: AgentRetrievalBudget,
    remainingCharacters: Int,
    routeIds: Set<String>,
): List<AgentRetrievalChunk> {
    if ((!budget.requirePeriodDiversity && routeIds.isEmpty()) || budget.chunkCount <= 0) return emptyList()
    val maxReservationCount = minOf(2, budget.chunkCount)
    val plans = mutableListOf<List<AgentRetrievalChunk>>()

    fun isFeasible(plan: List<AgentRetrievalChunk>): Boolean {
        if (plan.size > maxReservationCount) return false
        if (plan.groupingBy(AgentRetrievalChunk::sourceId).eachCount().values.any { it > budget.perSourceCount }) {
            return false
        }
        if (plan.map(AgentRetrievalChunk::stableDuplicateKey).distinct().size != plan.size) return false
        return plan.sumOf { renderEvidenceBlock(it).length } <= remainingCharacters
    }

    candidates.forEachIndexed { firstIndex, first ->
        listOf(first).takeIf(::isFeasible)?.let(plans::add)
        if (maxReservationCount >= 2) {
            for (secondIndex in firstIndex + 1 until candidates.size) {
                listOf(first, candidates[secondIndex]).takeIf(::isFeasible)?.let(plans::add)
            }
        }
    }
    if (plans.isEmpty()) return emptyList()

    fun periodCount(plan: List<AgentRetrievalChunk>): Int = if (budget.requirePeriodDiversity) {
        plan.map(AgentRetrievalChunk::period).filter(String::isNotBlank).distinct().size
    } else {
        0
    }
    fun routeCount(plan: List<AgentRetrievalChunk>): Int =
        plan.flatMap(AgentRetrievalChunk::routeIds).filter(routeIds::contains).distinct().size

    val targetPeriods = plans.maxOf(::periodCount).coerceAtMost(2)
    val targetRoutes = plans.maxOf(::routeCount).coerceAtMost(2)
    var best = plans.first()
    fun compare(left: List<AgentRetrievalChunk>, right: List<AgentRetrievalChunk>): Int {
        val leftPeriods = periodCount(left)
        val rightPeriods = periodCount(right)
        val leftRoutes = routeCount(left)
        val rightRoutes = routeCount(right)
        val leftMeetsTargets = if (leftPeriods >= targetPeriods && leftRoutes >= targetRoutes) 1 else 0
        val rightMeetsTargets = if (rightPeriods >= targetPeriods && rightRoutes >= targetRoutes) 1 else 0
        if (leftMeetsTargets != rightMeetsTargets) return leftMeetsTargets.compareTo(rightMeetsTargets)
        val leftCappedCoverage = minOf(leftPeriods, 2) + minOf(leftRoutes, 2)
        val rightCappedCoverage = minOf(rightPeriods, 2) + minOf(rightRoutes, 2)
        if (leftCappedCoverage != rightCappedCoverage) return leftCappedCoverage.compareTo(rightCappedCoverage)
        val leftCoverage = leftPeriods + leftRoutes
        val rightCoverage = rightPeriods + rightRoutes
        if (leftCoverage != rightCoverage) return leftCoverage.compareTo(rightCoverage)
        return right.size.compareTo(left.size)
    }
    plans.drop(1).forEach { candidate ->
        if (compare(candidate, best) > 0) best = candidate
    }
    return best
}

private fun chunkComparator(preferDirect: Boolean): Comparator<AgentRetrievalChunk> =
    compareByDescending<AgentRetrievalChunk> { if (preferDirect) it.authorship.directRank() else 0 }
        .thenByDescending(AgentRetrievalChunk::physicalScore)
        .thenBy(AgentRetrievalChunk::sourceId)
        .thenBy(AgentRetrievalChunk::period)
        .thenBy(AgentRetrievalChunk::chunkKey)

private fun routeComparator(): Comparator<AgentHierarchyRoute> =
    compareByDescending<AgentHierarchyRoute> { it.physicalScore }
        .thenBy(AgentHierarchyRoute::sourceId)
        .thenBy(AgentHierarchyRoute::topLevelId)
        .thenBy(AgentHierarchyRoute::nodeKey)

private val AgentHierarchyRoute.stableRouteId: String
    get() = "$sourceId/$topLevelId"

private fun V2Authorship.directRank(): Int = when (this) {
    V2Authorship.DIRECT -> 2
    V2Authorship.EDITED_DIRECT -> 1
    V2Authorship.SECONDARY, V2Authorship.UNKNOWN -> 0
}

private fun AgentRetrievalChunk.stableDuplicateKey(): String = duplicateGroup.ifBlank { chunkKey }

private fun selectRelationships(identity: V2Identity?, query: String): List<V2Relationship> {
    val terms = retrievalTerms(query)
    if (identity == null || terms.isEmpty()) return emptyList()
    return identity.relationships.map { relationship ->
        val searchable = listOf(relationship.subject, relationship.relation, relationship.period)
        val lexicalScore = searchable.sumOf { value -> terms.count(value.lowercase()::contains) }
        val exactScore = searchable.count { value -> value.isNotBlank() && query.contains(value, ignoreCase = true) } * 100
        relationship to (lexicalScore + exactScore)
    }.filter { (_, score) -> score >= MIN_RELATIONSHIP_RELEVANCE }
        .sortedWith(
            compareByDescending<Pair<V2Relationship, Int>> { it.second }
                .thenBy { it.first.subject }
                .thenBy { it.first.relation }
                .thenBy { it.first.period },
        )
        .map(Pair<V2Relationship, Int>::first)
        .take(MAX_SELECTED_RELATIONSHIPS)
}

private fun retrievalTerms(query: String): List<String> = buildList {
    addAll(Regex("[A-Za-z0-9_]{2,}").findAll(query.lowercase()).map(MatchResult::value))
    Regex("[\\u3400-\\u9fff]+").findAll(query).forEach { match ->
        val value = match.value
        if (value.length == 1) add(value) else (0 until value.length - 1).forEach { add(value.substring(it, it + 2)) }
    }
}.distinct()

private fun renderStance(stance: V2Worldview): String = buildString {
    append(stance.statement)
    if (stance.period.isNotBlank()) append("（时期：${stance.period}）")
    if (stance.conditions.isNotEmpty()) append(" 条件：${stance.conditions.joinToString("；")}")
}

private fun renderEpisode(episode: V2Episode): String =
    "${episode.summary}（${episode.period}；${episode.location}）意义：${episode.meaning}"

private fun renderExample(example: V2Example): String =
    "用户：${example.user}\n人物：${example.assistant}"

private fun renderRelationship(relationship: V2Relationship): String = buildString {
    append("${relationship.subject}：${relationship.relation}")
    if (relationship.period.isNotBlank()) append("（时期：${relationship.period}）")
}

private fun renderEvidenceBlock(chunk: AgentRetrievalChunk): String = buildString {
    appendLine(
        "[${chunk.sourceTitle} | ${chunk.period.ifBlank { "时期未知" }} | ${chunk.location} | ${chunk.authorship.wireName}]",
    )
    appendLine(chunk.text.trim())
}

private const val MAX_SELECTED_RELATIONSHIPS = 4
private const val MIN_RELATIONSHIP_RELEVANCE = 2
