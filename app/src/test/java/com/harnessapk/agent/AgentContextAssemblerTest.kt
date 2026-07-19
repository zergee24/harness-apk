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
                conceptsJson = "[]",
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
    fun repositoryRejectsAnyMalformedRequiredV2RuntimeAssetButAllowsBadOpener() = runTest {
        val valid = AgentVersionEntity(
            agentId = "agent-strict",
            version = 2,
            schemaVersion = 2,
            bundlePath = "/tmp/strict.hagent",
            bundleSha256 = "sha-strict",
            manifestJson = "{}",
            persona = "strict persona",
            worldviewJsonl = """{"id":"stance","topic":"topic","statement":"statement","conditions":[],"period":"","aliases":[],"confidence":1.0,"evidence":[]}""",
            installedAt = 1L,
            state = AgentStatus.READY.name,
            identityJson = """{"selfNames":["name"],"timeHorizon":"","roles":[],"relationships":[]}""",
            voiceJson = """{"defaultForm":"","sentenceRhythm":[],"rhetoricalMoves":[],"preferredTerms":[],"avoidPatterns":[],"evidence":[]}""",
            episodesJsonl = """{"id":"episode","period":"","location":"","participants":[],"summary":"summary","meaning":"","evidence":[]}""",
            conceptsJson = """[{"id":"concept","name":"name","aliases":[],"keywords":[],"evidence":[]}]""",
            examplesJsonl = """{"id":"example","intent":"","user":"question","assistant":"answer","styleTags":[],"generationType":"curated","evidence":[]}""",
            openersJson = "{bad opener",
            requiredCorpusCount = 1,
        )
        val corruptions = listOf(
            "identity" to valid.copy(identityJson = "{bad"),
            "voice" to valid.copy(voiceJson = """{"defaultForm":42}"""),
            "worldview" to valid.copy(worldviewJsonl = """{"id":"stance","statement":"missing topic"}"""),
            "episodes" to valid.copy(episodesJsonl = """{"id":"episode","summary":"ok"}\n{bad"""),
            "concepts" to valid.copy(conceptsJson = """[{"id":"concept"}]"""),
            "examples" to valid.copy(examplesJsonl = """{"id":"example","user":"question"}"""),
        )

        corruptions.forEach { (label, storedVersion) ->
            val repository = repositoryFor(storedVersion)
            val failure = runCatching {
                AgentContextAssembler(repository).assemble(
                    AgentContextRequest("agent-strict", 2, "具体事实是什么？"),
                )
            }.exceptionOrNull()

            assertTrue(label, failure is AgentBundleException)
            assertTrue(label, failure?.message.orEmpty().length in 1..200)
        }

        val context = AgentContextAssembler(repositoryFor(valid)).assemble(
            AgentContextRequest("agent-strict", 2, "具体事实是什么？"),
        )
        assertTrue(context != null)
        assertNull(repositoryFor(valid).opening("agent-strict", 2))
    }

    @Test
    fun repositoryResolvesHierarchyRoutesThroughParentChainBySourceAndRootId() = runTest {
        val dao = FakeAgentDao().apply {
            version = strictVersion("agent-routes", 2)
            versionCorpora = listOf(
                AgentVersionCorpusCrossRef("agent-routes", 2, "core", "hash-a", true),
                AgentVersionCorpusCrossRef("agent-routes", 2, "extra", "hash-b", false),
            )
            val nodes = listOf(
                hierarchyNode("hash-a:root-a", "source-a", "hash-a", "root-a", null, "同名标题"),
                hierarchyNode("hash-a:child-a", "source-a", "hash-a", "child-a", "hash-a:root-a", "同名标题"),
                hierarchyNode("hash-b:root-b", "source-b", "hash-b", "root-b", null, "同名标题"),
                hierarchyNode("hash-b:child-b", "source-b", "hash-b", "child-b", "hash-b:root-b", "同名标题"),
            )
            nodes.forEach { hierarchyNodes[it.nodeKey] = it }
            hierarchySearchRows += listOf(
                com.harnessapk.storage.AgentHierarchyFtsEntity("hash-a:child-a", "同名标题"),
                com.harnessapk.storage.AgentHierarchyFtsEntity("hash-b:child-b", "同名标题"),
            )
        }
        val repository = repositoryFor(dao)

        val routes = repository.searchHierarchy("agent-routes", 2, "同名标题", 8)

        assertEquals(
            setOf("source-a" to "root-a", "source-b" to "root-b"),
            routes.map { it.sourceId to it.topLevelId }.toSet(),
        )
        assertEquals(2, routes.map { "${it.sourceId}/${it.topLevelId}" }.distinct().size)
    }

    @Test
    fun repositoryReranksRoutedAndFtsCandidatesBeforeApplyingTheMergedLimit() = runTest {
        val dao = FakeAgentDao().apply {
            version = strictVersion("agent-merge", 2)
            versionCorpora = listOf(
                AgentVersionCorpusCrossRef("agent-merge", 2, "core", "hash", true),
            )
            routedSearchResult = listOf("routed-1", "routed-2")
            searchResult = listOf("fts-1", "fts-2")
            chunks["routed-1"] = storedChunk("routed-1", "source-r1", "unrelated")
            chunks["routed-2"] = storedChunk("routed-2", "source-r2", "unrelated")
            chunks["fts-1"] = storedChunk("fts-1", "source-f1", "target")
            chunks["fts-2"] = storedChunk("fts-2", "source-f2", "target")
        }
        val repository = repositoryFor(dao)

        val chunks = repository.searchChunks(
            agentId = "agent-merge",
            version = 2,
            query = "target",
            limit = 2,
            hierarchyRoutes = listOf(AgentHierarchyRoute("node", "source-r1", "root", 1)),
        )

        assertEquals(listOf("fts-1", "fts-2"), chunks.map(AgentRetrievalChunk::chunkKey))
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
    fun relationshipIntentInjectsOnlyBoundedQueryRelevantRelationships() = runTest {
        val relationships = listOf(
            V2Relationship("周先生", "师友", "1930-1940", listOf("evidence-a")),
            V2Relationship("周先生的同事", "共同研究者", "1935", listOf("evidence-b")),
            V2Relationship("李先生", "同事", "1950", listOf("evidence-c")),
            V2Relationship("王女士", "家人", "1960", listOf("evidence-d")),
            V2Relationship("陈先生", "朋友", "1970", listOf("evidence-e")),
            V2Relationship("赵先生", "朋友", "1980", listOf("evidence-f")),
        )
        val source = FakeContextSource(packageData(relationships = relationships))

        val context = AgentContextAssembler(source).assemble(request("你和周先生是什么关系？"))!!

        assertEquals(AgentQueryIntent.RELATIONSHIP, context.diagnostics.intent)
        assertEquals(0, source.chunkSearches)
        assertEquals(0, source.hierarchySearches)
        assertTrue(context.systemPrompt.contains("周先生"))
        assertTrue(context.systemPrompt.contains("师友"))
        assertTrue(context.systemPrompt.contains("1930-1940"))
        assertFalse(context.systemPrompt.contains("李先生"))
        assertFalse(context.systemPrompt.contains("王女士"))
        assertFalse(context.systemPrompt.contains("evidence-a"))
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
    fun periodReservationContinuesPastRejectedCandidatesBeforeOrdinaryFill() = runTest {
        val early = (0 until 8).map { index ->
            chunk(
                key = "early-$index",
                sourceId = "source-${index / 2}",
                period = "early",
                duplicateGroup = if (index == 0) "shared" else "early-$index",
                authorship = V2Authorship.DIRECT,
                score = 100 - index,
                text = "early evidence $index",
            )
        }
        val candidates = early + listOf(
            chunk("late-blocked", "source-late-a", "late", "shared", V2Authorship.DIRECT, 91, "blocked"),
            chunk("late-valid", "source-late-b", "late", "late-valid", V2Authorship.DIRECT, 1, "late evidence"),
        )

        val context = AgentContextAssembler(
            FakeContextSource(packageData(stances = emptyList()), candidates = candidates),
        ).assemble(request("你早年和晚年的观点如何变化？"))!!

        assertEquals(8, context.diagnostics.selectedChunkKeys.size)
        assertTrue(context.diagnostics.selectedChunkKeys.contains("late-valid"))
        assertEquals(2, context.diagnostics.periodCount)
    }

    @Test
    fun periodReservationSkipsAnUnavailablePeriodAndReservesTheNextAvailableOne() = runTest {
        val middle = (0 until 8).map { index ->
            chunk(
                key = "middle-$index",
                sourceId = "source-${index / 2}",
                period = "middle",
                duplicateGroup = "middle-$index",
                authorship = V2Authorship.DIRECT,
                score = 90 - index,
                text = "middle evidence $index",
            )
        }
        val candidates = listOf(
            chunk(
                "early-oversized",
                "source-early",
                "early",
                "early",
                V2Authorship.DIRECT,
                100,
                "x".repeat(10_000),
            ),
        ) + middle + listOf(
            chunk("late-valid", "source-late", "late", "late", V2Authorship.DIRECT, 1, "late evidence"),
        )

        val context = AgentContextAssembler(
            FakeContextSource(packageData(stances = emptyList()), candidates = candidates),
        ).assemble(request("你早年和晚年的观点如何变化？"))!!

        assertTrue(context.diagnostics.selectedChunkKeys.contains("late-valid"))
        assertEquals(setOf("middle", "late"), context.evidence.map { evidence ->
            candidates.single { it.chunkKey == evidence.chunkKey }.period
        }.toSet())
    }

    @Test
    fun routeReservationContinuesPastRejectedCandidatesAndKeepsSourceRootIdentity() = runTest {
        val routeA = AgentHierarchyRoute("node-a", "source-a", "root", 10)
        val routeB = AgentHierarchyRoute("node-b", "source-b", "root", 9)
        val filler = (0 until 11).map { index ->
            chunk(
                key = "filler-$index",
                sourceId = "source-${index / 2}",
                period = "same",
                duplicateGroup = if (index == 0) "shared" else "filler-$index",
                authorship = V2Authorship.DIRECT,
                score = 100 - index,
                text = "filler $index",
                routeIds = if (index == 0) setOf("source-a/root") else emptySet(),
            )
        }
        val candidates = filler + listOf(
            chunk(
                "route-b-blocked",
                "source-b",
                "same",
                "shared",
                V2Authorship.DIRECT,
                88,
                "blocked",
                setOf("source-b/root"),
            ),
            chunk(
                "route-b-valid",
                "source-b",
                "same",
                "route-b-valid",
                V2Authorship.DIRECT,
                1,
                "route b",
                setOf("source-b/root"),
            ),
        )

        val context = AgentContextAssembler(
            FakeContextSource(packageData(stances = emptyList()), candidates = candidates, routes = listOf(routeA, routeB)),
        ).assemble(request("请全面概括你的思想体系"))!!

        assertEquals(12, context.diagnostics.selectedChunkKeys.size)
        assertTrue(context.diagnostics.selectedChunkKeys.contains("route-b-valid"))
        assertEquals(setOf("source-a/root", "source-b/root"), context.diagnostics.selectedRouteIds.toSet())
    }

    @Test
    fun evidenceBudgetChargesTheCompleteRenderedBlock() = runTest {
        val fitting = chunk(
            "fitting",
            "source-a",
            "period-a",
            "fitting",
            V2Authorship.DIRECT,
            10,
            "x".repeat(4_720),
        )
        val oversized = chunk(
            "oversized",
            "source-b",
            "period-b",
            "oversized",
            V2Authorship.DIRECT,
            9,
            "y".repeat(4_780),
        )

        val context = AgentContextAssembler(
            FakeContextSource(packageData(stances = emptyList()), candidates = listOf(oversized, fitting)),
        ).assemble(request("具体事实是什么？"))!!
        val expectedBlock = "[source-a | period-a | loc-fitting | direct]\n${fitting.text}\n"

        assertEquals(listOf("fitting"), context.diagnostics.selectedChunkKeys)
        assertEquals(expectedBlock.length, context.diagnostics.selectedCharacterCount)
        assertTrue(context.diagnostics.selectedCharacterCount <= context.diagnostics.characterBudget)
        assertTrue(context.systemPrompt.contains(expectedBlock.trimEnd()))
    }

    @Test
    fun exactFactAllocatesRawEvidenceBeforeAStanceCanExhaustTheBudget() = runTest {
        val oversizedStance = V2Worldview(
            id = "stance-large",
            topic = "事实",
            statement = "立场".repeat(2_390),
            conditions = emptyList(),
            period = "",
            aliases = emptyList(),
            confidence = 1.0,
            evidence = emptyList(),
        )
        val raw = chunk("raw", "source-a", "period-a", "raw", V2Authorship.DIRECT, 10, "直接事实")

        val context = AgentContextAssembler(
            FakeContextSource(packageData(stances = listOf(oversizedStance)), candidates = listOf(raw)),
        ).assemble(request("具体事实是什么？"))!!

        assertEquals(listOf("raw"), context.diagnostics.selectedChunkKeys)
        assertTrue(context.evidence.single().text.contains("直接事实"))
        assertTrue(context.diagnostics.selectedCharacterCount <= 4_800)
    }

    @Test
    fun globalSearchUsesMultipleHierarchyRoutesBeforeChildChunks() = runTest {
        val source = FakeContextSource(
            packageData(),
            candidates = listOf(
                chunk("route-a", "source-a", "early", "g-a", V2Authorship.DIRECT, 9, "路线甲", setOf("source-a/top-a")),
                chunk("route-b", "source-b", "late", "g-b", V2Authorship.DIRECT, 8, "路线乙", setOf("source-b/top-b")),
            ),
            routes = listOf(
                AgentHierarchyRoute("top-a", "source-a", "top-a", 10),
                AgentHierarchyRoute("top-b", "source-b", "top-b", 9),
            ),
        )

        val context = AgentContextAssembler(source).assemble(request("请全面概括你的思想体系"))!!

        assertEquals(listOf("hierarchy", "chunks"), source.calls)
        assertTrue(context.diagnostics.hierarchyRoutingUsed)
        assertEquals(setOf("source-a/top-a", "source-b/top-b"), context.diagnostics.selectedRouteIds.toSet())
        assertEquals(2, context.diagnostics.periodCount)
    }

    @Test
    fun v1DiagnosticsSortAndBoundAssetIdsWhileRetainingTotalCount() = runTest {
        val stances = (0 until 80).map { index ->
            V2Worldview(
                id = "stance-${index.toString().padStart(3, '0')}",
                topic = "topic",
                statement = "statement",
                conditions = emptyList(),
                period = "",
                aliases = emptyList(),
                confidence = 1.0,
                evidence = emptyList(),
            )
        }.reversed()
        val source = FakeContextSource(packageData(stances = stances).copy(schemaVersion = 1))

        val context = AgentContextAssembler(source).assemble(request("你好"))!!

        assertEquals(64, context.diagnostics.selectedAssetIds.size)
        assertEquals(stances.map(V2Worldview::id).sorted().take(64), context.diagnostics.selectedAssetIds)
        assertEquals(80, context.diagnostics.selectedAssetTotalCount)
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
        relationships: List<V2Relationship> = emptyList(),
        stances: List<V2Worldview> = listOf(
            V2Worldview("stance-1", "调查", "先调查再判断", emptyList(), "all", emptyList(), 1.0, listOf("a")),
        ),
        requiredCorpusCount: Int = 1,
        installedRequiredCorpusCount: Int = 1,
        missingOptionalCoverage: List<String> = emptyList(),
    ) = AgentContextPackage(
        agentId = "agent-1",
        version = 7,
        schemaVersion = 2,
        persona = persona,
        identity = V2Identity(listOf("测试人物"), "1900-1970", listOf("研究者"), relationships),
        voice = voice,
        stances = stances,
        episodes = emptyList(),
        examples = emptyList(),
        opener = opener,
        requiredCorpusCount = requiredCorpusCount,
        installedRequiredCorpusCount = installedRequiredCorpusCount,
        missingOptionalCoverage = missingOptionalCoverage,
    )

    private fun repositoryFor(version: AgentVersionEntity): AgentRepository {
        val dao = FakeAgentDao().apply {
            this.version = version
            versionCorpora = listOf(
                AgentVersionCorpusCrossRef(version.agentId, version.version, "core", "hash", true),
            )
        }
        return repositoryFor(dao)
    }

    private fun repositoryFor(dao: FakeAgentDao): AgentRepository {
        val root = java.nio.file.Files.createTempDirectory("b8-fix-repository").toFile()
        return AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            timeProvider = TimeProvider { 1L },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun strictVersion(agentId: String, version: Int) = AgentVersionEntity(
        agentId = agentId,
        version = version,
        schemaVersion = 2,
        bundlePath = "/tmp/$agentId.hagent",
        bundleSha256 = "sha-$agentId",
        manifestJson = "{}",
        persona = "persona",
        worldviewJsonl = "",
        installedAt = 1L,
        state = AgentStatus.READY.name,
        identityJson = """{"selfNames":[],"timeHorizon":"","roles":[],"relationships":[]}""",
        voiceJson = """{"defaultForm":"","sentenceRhythm":[],"rhetoricalMoves":[],"preferredTerms":[],"avoidPatterns":[],"evidence":[]}""",
        conceptsJson = "[]",
        requiredCorpusCount = 1,
    )

    private fun hierarchyNode(
        nodeKey: String,
        sourceId: String,
        sourceHash: String,
        nodeId: String,
        parentNodeKey: String?,
        title: String,
    ) = com.harnessapk.storage.AgentHierarchyNodeEntity(
        nodeKey = nodeKey,
        sourceId = sourceId,
        sourceHash = sourceHash,
        nodeId = nodeId,
        kind = "section",
        title = title,
        parentNodeKey = parentNodeKey,
        path = title,
        summary = title,
    )

    private fun storedChunk(key: String, sourceId: String, text: String) = AgentChunkEntity(
        chunkKey = key,
        sourceId = sourceId,
        sourceHash = "hash",
        chunkId = key,
        sourceTitle = sourceId,
        period = "period",
        authorship = V2Authorship.DIRECT.wireName,
        location = "location",
        text = text,
        keywordsText = text,
        duplicateGroup = key,
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
