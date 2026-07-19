package com.harnessapk.agent

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentVersionCorpusCrossRef
import com.harnessapk.storage.AgentVersionEntity
import com.harnessapk.storage.AgentChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextAssemblerTest {
    @Test
    fun v1AdapterKeepsPersonaWorldviewEvidenceAndFixedVersion() = runTest {
        val dao = FakeAgentDao().apply {
            version = AgentVersionEntity(
                agentId = "agent-v1",
                version = 4,
                schemaVersion = 1,
                bundlePath = "/tmp/v1.hbundle",
                bundleSha256 = "sha-v1",
                manifestJson = "{}",
                persona = "V1 人格",
                worldviewJsonl = """{"id":"private-id","statement":"调查先于结论","evidence":["chunk-v1"]}""",
                installedAt = 1L,
                state = AgentStatus.READY.name,
                requiredCorpusCount = 1,
            )
            versionCorpora = listOf(AgentVersionCorpusCrossRef("agent-v1", 4, "v1-core", "hash-v1", true))
            chunks["hash-v1:chunk-v1"] = AgentChunkEntity(
                chunkKey = "hash-v1:chunk-v1",
                sourceHash = "hash-v1",
                chunkId = "chunk-v1",
                sourceTitle = "V1 资料",
                location = "第一章",
                text = "先调查事实，再形成结论。",
                keywordsText = "调查 事实 结论",
            )
            searchResult = listOf("hash-v1:chunk-v1")
        }
        val root = java.nio.file.Files.createTempDirectory("b8-v1").toFile()
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            timeProvider = TimeProvider { 1L },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val context = AgentContextAssembler(repository).assemble(
            AgentContextRequest("agent-v1", 4, "为什么要先调查事实？"),
        )!!

        assertEquals(4, context.version)
        assertEquals(listOf("hash-v1:chunk-v1"), context.evidence.map(AgentEvidence::chunkKey))
        assertTrue(context.systemPrompt.contains("V1 人格"))
        assertTrue(context.systemPrompt.contains("调查先于结论"))
        assertFalse(context.systemPrompt.contains("private-id"))
    }

    @Test
    fun repositoryLoadsFixedV2VersionAssetsAndOpener() = runTest {
        val dao = FakeAgentDao().apply {
            version = AgentVersionEntity(
                agentId = "agent-history",
                version = 3,
                schemaVersion = 2,
                bundlePath = "/tmp/history.hagent",
                bundleSha256 = "sha-history",
                manifestJson = "{}",
                persona = "第三版人格",
                worldviewJsonl = """{"id":"stance-history","topic":"研究","statement":"第三版立场","conditions":[],"period":"早期","aliases":[],"confidence":1.0,"evidence":[]}""",
                installedAt = 1L,
                state = AgentStatus.READY.name,
                identityJson = """{"selfNames":["历史人物"],"timeHorizon":"第三版时期","roles":["研究者"],"relationships":[]}""",
                voiceJson = """{"defaultForm":"第三版语气","sentenceRhythm":[],"rhetoricalMoves":[],"preferredTerms":[],"avoidPatterns":["套话"],"evidence":[]}""",
                openersJson = """{"default":"第三版开场","alternatives":[]}""",
                requiredCorpusCount = 1,
            )
            versionCorpora = listOf(
                AgentVersionCorpusCrossRef("agent-history", 3, "core", "hash-core", true),
            )
        }
        val root = java.nio.file.Files.createTempDirectory("b8-repository").toFile()
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            timeProvider = TimeProvider { 1L },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertEquals("第三版开场", repository.opening("agent-history", 3))
        assertNull(repository.opening("agent-history", 2))
        val context = AgentContextAssembler(repository).assemble(
            AgentContextRequest("agent-history", 3, "你如何研究？"),
        )!!
        assertTrue(context.systemPrompt.contains("第三版人格"))
        assertTrue(context.systemPrompt.contains("第三版语气"))
        assertEquals(listOf("stance-history"), context.diagnostics.selectedAssetIds)
    }

    @Test
    fun relationshipIntentUsesIdentityAndMemoryWithoutRetrieval() = runTest {
        val source = FakeContextSource(packageData())
        val context = AgentContextAssembler(source).assemble(
            AgentContextRequest(
                agentId = "agent-1",
                version = 7,
                query = "你好，继续刚才的话题",
                conversationMemory = "用户正在比较两种研究方法。",
            ),
        )!!

        assertEquals(0, source.chunkSearches)
        assertEquals(0, source.hierarchySearches)
        assertEquals(AgentQueryIntent.RELATIONSHIP, context.diagnostics.intent)
        assertTrue(context.systemPrompt.contains("用户正在比较两种研究方法"))
        assertTrue(context.systemPrompt.contains("关系记忆"))
        assertTrue(context.evidence.isEmpty())
    }

    @Test
    fun rerankIsDeterministicAndHonorsPeriodSourceDuplicateAndCharacterCaps() = runTest {
        val candidates = listOf(
            chunk("b", "source-a", "early", "dup-1", V2Authorship.SECONDARY, 20, "次级资料"),
            chunk("a", "source-a", "early", "dup-1", V2Authorship.DIRECT, 20, "直接资料"),
            chunk("c", "source-a", "late", "dup-2", V2Authorship.DIRECT, 19, "晚期资料"),
            chunk("d", "source-a", "late", "dup-3", V2Authorship.DIRECT, 18, "来源超额"),
            chunk("e", "source-b", "middle", "dup-4", V2Authorship.EDITED_DIRECT, 17, "中期资料"),
            chunk("f", "source-c", "late", "dup-5", V2Authorship.DIRECT, 16, "x".repeat(10_000)),
        )
        val source = FakeContextSource(packageData(), candidates = candidates.reversed())
        val assembler = AgentContextAssembler(source)

        val first = assembler.assemble(request("你早年和晚年的观点如何变化？"))!!
        source.candidates = candidates.shuffled(kotlin.random.Random(9))
        val second = assembler.assemble(request("你早年和晚年的观点如何变化？"))!!

        assertEquals(first.diagnostics.selectedChunkKeys, second.diagnostics.selectedChunkKeys)
        assertEquals(listOf("a", "c", "e"), first.diagnostics.selectedChunkKeys)
        assertEquals(3, first.diagnostics.periodCount)
        assertEquals(2, first.diagnostics.sourceCount)
        assertEquals(3, first.diagnostics.duplicateGroupCount)
        assertTrue(first.evidence.sumOf { it.text.length } <= first.diagnostics.characterBudget)
        assertFalse(first.diagnostics.selectedChunkKeys.contains("b"))
        assertFalse(first.diagnostics.selectedChunkKeys.contains("d"))
        assertFalse(first.diagnostics.selectedChunkKeys.contains("f"))
    }

    @Test
    fun globalSearchUsesMultipleHierarchyRoutesBeforeChildChunks() = runTest {
        val source = FakeContextSource(
            packageData(),
            candidates = listOf(
                chunk("route-a", "source-a", "early", "g-a", V2Authorship.DIRECT, 9, "路线甲", setOf("top-a")),
                chunk("route-b", "source-b", "late", "g-b", V2Authorship.DIRECT, 8, "路线乙", setOf("top-b")),
            ),
            routes = listOf(
                AgentHierarchyRoute("top-a", "source-a", "top-a", 10),
                AgentHierarchyRoute("top-b", "source-b", "top-b", 9),
            ),
        )

        val context = AgentContextAssembler(source).assemble(request("请全面概括你的思想体系"))!!

        assertEquals(listOf("hierarchy", "chunks"), source.calls)
        assertTrue(context.diagnostics.hierarchyRoutingUsed)
        assertEquals(setOf("top-a", "top-b"), context.diagnostics.selectedRouteIds.toSet())
        assertEquals(2, context.diagnostics.periodCount)
    }

    @Test
    fun missingRequiredCorpusBlocksContextWhileOptionalOnlyAddsDiagnostics() = runTest {
        assertNull(
            AgentContextAssembler(
                FakeContextSource(packageData(requiredCorpusCount = 2, installedRequiredCorpusCount = 1)),
            ).assemble(request("这件事发生在哪一年？")),
        )

        val available = AgentContextAssembler(
            FakeContextSource(packageData(missingOptionalCoverage = listOf("letters", "interviews"))),
        ).assemble(request("这件事发生在哪一年？"))!!

        assertEquals(listOf("interviews", "letters"), available.diagnostics.missingOptionalCoverage)
    }

    @Test
    fun fixedVersionUsesOwnAssetsAndPromptBoundariesWithoutResponseDisclaimerTemplate() = runTest {
        val source = FakeContextSource(
            packageData(
                persona = "第七版身份",
                opener = "第七版开场白",
                voice = V2Voice(
                    defaultForm = "先判断再解释",
                    sentenceRhythm = listOf("短句"),
                    rhetoricalMoves = listOf("追问条件"),
                    preferredTerms = listOf("调查"),
                    avoidPatterns = listOf("空泛赞美"),
                    evidence = listOf("direct-1"),
                ),
            ),
        )

        val context = AgentContextAssembler(source).assemble(
            request("你如何开展调查？", projectContext = "仅本会话项目：移动端人格运行时"),
        )!!

        assertEquals(7, source.loadedVersions.single())
        assertTrue(context.systemPrompt.contains("第七版身份"))
        assertTrue(context.systemPrompt.contains("仅本会话项目"))
        assertTrue(context.systemPrompt.contains("基于资料模拟"))
        assertTrue(context.systemPrompt.contains("次级资料只能作为背景"))
        assertTrue(context.systemPrompt.contains("先判断再解释"))
        assertTrue(context.systemPrompt.contains("空泛赞美"))
        assertFalse(context.systemPrompt.contains("第七版开场白"))
        assertFalse(context.systemPrompt.contains("每次回答先声明"))
    }

    private fun request(query: String, projectContext: String = "") = AgentContextRequest(
        agentId = "agent-1",
        version = 7,
        query = query,
        projectContext = projectContext,
    )

    private fun packageData(
        persona: String = "人格内核",
        opener: String = "固定开场",
        voice: V2Voice = V2Voice("自然回答", emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        requiredCorpusCount: Int = 1,
        installedRequiredCorpusCount: Int = 1,
        missingOptionalCoverage: List<String> = emptyList(),
    ) = AgentContextPackage(
        agentId = "agent-1",
        version = 7,
        schemaVersion = 2,
        persona = persona,
        identity = V2Identity(listOf("测试人物"), "1900-1970", listOf("研究者"), emptyList()),
        voice = voice,
        stances = listOf(
            V2Worldview("stance-1", "调查", "先调查再判断", emptyList(), "all", emptyList(), 1.0, listOf("a")),
        ),
        episodes = emptyList(),
        examples = emptyList(),
        opener = opener,
        requiredCorpusCount = requiredCorpusCount,
        installedRequiredCorpusCount = installedRequiredCorpusCount,
        missingOptionalCoverage = missingOptionalCoverage,
    )

    private fun chunk(
        key: String,
        sourceId: String,
        period: String,
        duplicateGroup: String,
        authorship: V2Authorship,
        score: Int,
        text: String,
        routeIds: Set<String> = emptySet(),
    ) = AgentRetrievalChunk(
        chunkKey = key,
        chunkId = key,
        sourceId = sourceId,
        sourceTitle = sourceId,
        period = period,
        authorship = authorship,
        location = "loc-$key",
        text = text,
        duplicateGroup = duplicateGroup,
        physicalScore = score,
        routeIds = routeIds,
    )
}

private class FakeContextSource(
    private val data: AgentContextPackage?,
    var candidates: List<AgentRetrievalChunk> = emptyList(),
    private val routes: List<AgentHierarchyRoute> = emptyList(),
) : AgentContextDataSource {
    var chunkSearches = 0
    var hierarchySearches = 0
    val calls = mutableListOf<String>()
    val loadedVersions = mutableListOf<Int>()

    override suspend fun loadPackage(agentId: String, version: Int): AgentContextPackage? {
        loadedVersions += version
        return data
    }

    override suspend fun searchHierarchy(
        agentId: String,
        version: Int,
        query: String,
        limit: Int,
    ): List<AgentHierarchyRoute> {
        hierarchySearches += 1
        calls += "hierarchy"
        return routes
    }

    override suspend fun searchChunks(
        agentId: String,
        version: Int,
        query: String,
        limit: Int,
        hierarchyRoutes: List<AgentHierarchyRoute>,
    ): List<AgentRetrievalChunk> {
        chunkSearches += 1
        calls += "chunks"
        return candidates
    }
}
