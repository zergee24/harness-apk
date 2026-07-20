package com.harnessapk.agentmemory

import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryPolicyTest {
    private val policy = AgentMemoryPolicy()

    @Test
    fun keepsOnlyStableRelationshipFactsBackedBySuccessfulUserQuotes() {
        val input = input(
            user(
                "user-1",
                """
                以后请叫我老唐。
                以后默认用中文回答。
                我们一起度过我最难受的那个晚上。
                这次之后，我更信任你了。
                """.trimIndent(),
            ),
        )
        val candidates = listOf(
            candidate(
                AgentMemoryKind.ADDRESS_PREFERENCE,
                "address",
                "以后称呼用户为老唐",
                "user-1",
                "以后请叫我老唐。",
            ),
            candidate(
                AgentMemoryKind.USER_PREFERENCE,
                "language",
                "默认使用中文回答",
                "user-1",
                "以后默认用中文回答。",
            ),
            candidate(
                AgentMemoryKind.SHARED_HISTORY,
                "hard-night",
                "我们一起度过用户最难受的那个晚上",
                "user-1",
                "我们一起度过我最难受的那个晚上。",
            ),
            candidate(
                AgentMemoryKind.RELATIONSHIP_EVENT,
                "trust",
                "用户明确表示更信任我",
                "user-1",
                "这次之后，我更信任你了。",
            ),
        )

        val result = policy.evaluate(input, candidates)

        assertEquals(AgentMemoryPolicyStatus.COMPLETED, result.status)
        assertEquals(candidates, result.accepted)
        assertEquals(candidates, policy.filter(input, candidates))
        assertEquals(0, result.rejectedCount)
    }

    @Test
    fun rejectsProjectArtifactsTasksDecisionsAndBusinessFacts() {
        val facts = listOf(
            "项目目标是发布 Harness APK",
            "需要修改 notes/plan.md",
            "代码目录是 /src/main",
            "commit a1b2c3d",
            "任务是完成安装页",
            "需求是增加导入",
            "接口地址是 /v1/chat/completions",
            "计划 8 月 1 日上线",
            "技术方案采用 Room",
            "客户是喜橙",
            "订单号是 12345",
            "本月指标是转化率 20%",
            "我们决定本周发布",
        )
        val messages = facts.mapIndexed { index, fact -> user("user-$index", fact) }
        val candidates = facts.mapIndexed { index, fact ->
            candidate(
                kind = AgentMemoryKind.USER_PREFERENCE,
                dedupeKey = "project-$index",
                content = fact,
                sourceMessageId = "user-$index",
                sourceQuote = fact,
            )
        }

        val result = policy.evaluate(
            input = input(*messages.toTypedArray(), projectId = "project-1", projectFacts = facts),
            candidates = candidates,
        )

        assertEquals(AgentMemoryPolicyStatus.COMPLETED, result.status)
        assertTrue(result.accepted.isEmpty())
        assertEquals(candidates.size, result.rejectedCount)
    }

    @Test
    fun projectFingerprintRejectsPrefixesReorderingPunctuationAndCaseChanges() {
        val projectFact = "北岸灯塔采用代号 Oriole_7421"
        val variants = listOf(
            "用户曾说北岸灯塔采用代号 Oriole_7421",
            "北岸灯塔，采用代号 ORIOLE-7421",
            "Oriole 7421 是北岸灯塔采用的代号",
        )
        val candidates = variants.mapIndexed { index, text ->
            candidate(
                kind = AgentMemoryKind.SHARED_HISTORY,
                dedupeKey = "variant-$index",
                content = text,
                sourceMessageId = "user-$index",
                sourceQuote = text,
            )
        }

        val result = policy.evaluate(
            input(
                *variants.mapIndexed { index, text -> user("user-$index", text) }.toTypedArray(),
                projectId = "project-1",
                projectFacts = listOf(projectFact),
            ),
            candidates,
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(variants.size, result.rejectedCount)
    }

    @Test
    fun genericProjectBoundaryStillAppliesOutsideProjects() {
        val text = "当前任务是修改 README.md 并发布 2.0"

        val result = policy.evaluate(
            input(user("user-1", text), projectId = null, projectFacts = emptyList()),
            listOf(
                candidate(
                    AgentMemoryKind.SHARED_HISTORY,
                    "daily-project-fact",
                    text,
                    "user-1",
                    text,
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun rejectsTransientReasoningPersonaGreetingAndImmediateEmotion() {
        val rejected = listOf(
            "今天只要三条建议",
            "仅本轮使用表格",
            "当前话题是版本发布",
            "隐藏推理里先判断风险",
            "请保存完整思维链",
            "模型内部先判断用户意图",
            "毛泽东的世界观是实事求是",
            "人物包资料说明他在一九二七年写作",
            "你好",
            "我现在有点烦",
        )
        val result = policy.evaluate(
            input(*rejected.mapIndexed { index, text -> user("user-$index", text) }.toTypedArray()),
            rejected.mapIndexed { index, text ->
                candidate(
                    AgentMemoryKind.USER_PREFERENCE,
                    "rejected-$index",
                    text,
                    "user-$index",
                    text,
                )
            },
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(rejected.size, result.rejectedCount)
    }

    @Test
    fun rejectsAssistantFailedPendingUnknownAndForgedSources() {
        val input = input(
            user("valid", "以后默认用中文回答"),
            assistant("assistant", "用户以后默认用中文回答"),
            user("failed", "以后叫我失败", status = MessageStatus.FAILED),
            user("pending", "以后叫我等待", status = MessageStatus.PENDING),
        )
        val candidates = listOf(
            candidate(
                AgentMemoryKind.USER_PREFERENCE,
                "assistant",
                "默认使用中文回答",
                "assistant",
                "用户以后默认用中文回答",
            ),
            candidate(
                AgentMemoryKind.ADDRESS_PREFERENCE,
                "failed",
                "以后称呼用户为失败",
                "failed",
                "以后叫我失败",
            ),
            candidate(
                AgentMemoryKind.ADDRESS_PREFERENCE,
                "pending",
                "以后称呼用户为等待",
                "pending",
                "以后叫我等待",
            ),
            candidate(
                AgentMemoryKind.USER_PREFERENCE,
                "unknown",
                "默认使用中文回答",
                "missing",
                "以后默认用中文回答",
            ),
            candidate(
                AgentMemoryKind.USER_PREFERENCE,
                "forged",
                "默认使用中文回答",
                "valid",
                "默认用英文回答",
            ),
        )

        val result = policy.evaluate(input, candidates)

        assertTrue(result.accepted.isEmpty())
        assertEquals(candidates.size, result.rejectedCount)
    }

    @Test
    fun normalizesLineEndingsForExactQuoteAndRequiresContentSupport() {
        val input = input(
            user("supported", "以后都用中文\r\n回答我"),
            user("unrelated", "我喜欢吃苹果"),
        )
        val supported = candidate(
            AgentMemoryKind.USER_PREFERENCE,
            "language",
            "默认使用中文回答",
            "supported",
            "以后都用中文\n回答我",
        )
        val unrelated = candidate(
            AgentMemoryKind.ADDRESS_PREFERENCE,
            "address",
            "以后称呼我为老唐",
            "unrelated",
            "我喜欢吃苹果",
        )

        val result = policy.evaluate(input, listOf(supported, unrelated))

        assertEquals(listOf(supported), result.accepted)
        assertEquals(1, result.rejectedCount)
    }

    @Test
    fun rejectsPartialOverlapThatFabricatesLanguageOrAddressValues() {
        val input = input(
            user("music", "我喜欢中文歌"),
            user("address", "以后叫我小李"),
        )
        val candidates = listOf(
            candidate(
                AgentMemoryKind.USER_PREFERENCE,
                "language",
                "用户希望以后默认使用中文回答",
                "music",
                "我喜欢中文歌",
            ),
            candidate(
                AgentMemoryKind.ADDRESS_PREFERENCE,
                "address",
                "以后称呼用户为老唐",
                "address",
                "以后叫我小李",
            ),
        )

        val result = policy.evaluate(input, candidates)

        assertTrue(result.accepted.isEmpty())
        assertEquals(candidates.size, result.rejectedCount)
    }

    @Test
    fun rejectsPreferenceAndRelationshipDirectionReversal() {
        val input = input(
            user("language", "以后不要用中文回答"),
            user("trust", "我不再相信你"),
        )
        val result = policy.evaluate(
            input,
            listOf(
                candidate(
                    AgentMemoryKind.USER_PREFERENCE,
                    "language",
                    "默认使用中文回答",
                    "language",
                    "以后不要用中文回答",
                ),
                candidate(
                    AgentMemoryKind.RELATIONSHIP_EVENT,
                    "trust",
                    "用户更信任我",
                    "trust",
                    "我不再相信你",
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(2, result.rejectedCount)
    }

    @Test
    fun rejectsNegativeAddressAndLongDistanceNegationReversal() {
        val longNegative =
            "以后不要在任何未经我明确确认和反复说明的情况下使用中文回答"
        val result = policy.evaluate(
            input(
                user("address", "以后不要叫我小李"),
                user("language", longNegative),
            ),
            listOf(
                candidate(
                    AgentMemoryKind.ADDRESS_PREFERENCE,
                    "address",
                    "以后称呼用户为小李",
                    "address",
                    "以后不要叫我小李",
                ),
                candidate(
                    AgentMemoryKind.USER_PREFERENCE,
                    "language",
                    "默认使用中文回答",
                    "language",
                    longNegative,
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(2, result.rejectedCount)
    }

    @Test
    fun keepsNegativePreferenceAndTrustDirectionWhenContentAgrees() {
        val input = input(
            user("language", "以后不要用中文回答"),
            user("trust", "我不再相信你"),
        )
        val negativeLanguage = candidate(
            AgentMemoryKind.USER_PREFERENCE,
            "language",
            "以后避免使用中文回答",
            "language",
            "以后不要用中文回答",
        )
        val lostTrust = candidate(
            AgentMemoryKind.RELATIONSHIP_EVENT,
            "trust",
            "用户不再信任我",
            "trust",
            "我不再相信你",
        )

        val result = policy.evaluate(input, listOf(negativeLanguage, lostTrust))

        assertEquals(listOf(negativeLanguage, lostTrust), result.accepted)
    }

    @Test
    fun rejectsSharedHistoryWhenCandidateReplacesAPlace() {
        val input = input(user("trip", "我们一起去了北京旅行"))
        val result = policy.evaluate(
            input,
            listOf(
                candidate(
                    AgentMemoryKind.SHARED_HISTORY,
                    "trip",
                    "我们一起去了上海旅行",
                    "trip",
                    "我们一起去了北京旅行",
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun rejectsStructuredCandidatesThatAddPeoplePlacesOrNumbers() {
        val result = policy.evaluate(
            input(
                user("language", "以后默认用中文回答"),
                user("trust", "我更信任你"),
            ),
            listOf(
                candidate(
                    AgentMemoryKind.USER_PREFERENCE,
                    "language",
                    "以后让小李默认用中文回答",
                    "language",
                    "以后默认用中文回答",
                ),
                candidate(
                    AgentMemoryKind.RELATIONSHIP_EVENT,
                    "trust",
                    "上海之行后用户信任我三倍",
                    "trust",
                    "我更信任你",
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(2, result.rejectedCount)
    }

    @Test
    fun rejectsPreferenceCandidateThatAppendsAnUnsupportedRule() {
        val quote = "以后默认用中文回答"
        val result = policy.evaluate(
            input(user("language", quote)),
            listOf(
                candidate(
                    AgentMemoryKind.USER_PREFERENCE,
                    "language",
                    "默认使用中文回答，并且用户要求每次回复先自我介绍",
                    "language",
                    quote,
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun rejectsThirdPartyRelationshipAndHistoryAsCurrentAgentMemories() {
        val input = input(
            user("friend", "小李是我最信任的朋友"),
            user("history", "我和小李一起度过最难受的那个晚上"),
        )
        val candidates = listOf(
            candidate(
                AgentMemoryKind.RELATIONSHIP_EVENT,
                "trust",
                "用户更信任当前人物",
                "friend",
                "小李是我最信任的朋友",
            ),
            candidate(
                AgentMemoryKind.SHARED_HISTORY,
                "hard-night",
                "我们一起度过用户最难受的那个晚上",
                "history",
                "我和小李一起度过最难受的那个晚上",
            ),
        )

        val result = policy.evaluate(input, candidates)

        assertTrue(result.accepted.isEmpty())
        assertEquals(candidates.size, result.rejectedCount)
    }

    @Test
    fun rejectsPossessiveThirdPartiesAndGroupWe() {
        val input = input(
            user("relative", "我对你哥哥很信任"),
            user("department", "我们部门一起度过了那次危机"),
        )
        val result = policy.evaluate(
            input,
            listOf(
                candidate(
                    AgentMemoryKind.RELATIONSHIP_EVENT,
                    "trust",
                    "用户更信任我",
                    "relative",
                    "我对你哥哥很信任",
                ),
                candidate(
                    AgentMemoryKind.SHARED_HISTORY,
                    "crisis",
                    "我们一起度过了那次危机",
                    "department",
                    "我们部门一起度过了那次危机",
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(2, result.rejectedCount)
    }

    @Test
    fun rejectsRelationshipPredicateWhoseObjectIsAThirdParty() {
        val quote = "我和你都信任小李"
        val result = policy.evaluate(
            input(user("third-party", quote)),
            listOf(
                candidate(
                    AgentMemoryKind.RELATIONSHIP_EVENT,
                    "trust",
                    "用户信任我",
                    "third-party",
                    quote,
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun rejectsRelationshipCauseThatPointsTrustAtAThirdParty() {
        val quote = "你让我信任小李"
        val result = policy.evaluate(
            input(user("third-party-cause", quote)),
            listOf(
                candidate(
                    AgentMemoryKind.RELATIONSHIP_EVENT,
                    "trust",
                    "用户信任当前人物",
                    "third-party-cause",
                    quote,
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun keepsRelationshipCauseWhenPredicateStillPointsAtCurrentAgent() {
        val quote = "你让我信任你"
        val candidate = candidate(
            AgentMemoryKind.RELATIONSHIP_EVENT,
            "trust",
            "用户信任当前人物",
            "direct-cause",
            quote,
        )

        val result = policy.evaluate(
            input(user("direct-cause", quote)),
            listOf(candidate),
        )

        assertEquals(listOf(candidate), result.accepted)
    }

    @Test
    fun rejectsWorkProductsContractsAndCommercialOutcomesOutsideProjects() {
        val facts = listOf(
            "我们一起完成了登录页改版",
            "我们一起拿下了三百万合同",
            "我们共同完成供应商模块迭代并提升 GMV",
            "我们一起实现了销售额翻倍",
            "我们一起交付了婚礼策划流程",
            "我们一起加班写了季度汇报",
        )
        val result = policy.evaluate(
            input(*facts.mapIndexed { index, text -> user("work-$index", text) }.toTypedArray()),
            facts.mapIndexed { index, text ->
                candidate(
                    AgentMemoryKind.SHARED_HISTORY,
                    "work-$index",
                    text,
                    "work-$index",
                    text,
                )
            },
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(facts.size, result.rejectedCount)
    }

    @Test
    fun rejectsProjectBudgetFactsWithoutProjectContext() {
        val quote = "我们一起经历这个项目预算五十万元的变更"
        val result = policy.evaluate(
            input(user("budget", quote), projectId = null, projectFacts = emptyList()),
            listOf(
                candidate(
                    AgentMemoryKind.SHARED_HISTORY,
                    "project-budget",
                    quote,
                    "budget",
                    quote,
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun longProjectFactTailStillBlocksMatchingCandidate() {
        val noisyPrefix = buildString {
            repeat(2_200) { index ->
                append((0x4E00 + index).toChar())
            }
        }
        val projectFact = noisyPrefix + "我们一起度过蓝色灯塔夜晚"
        val reorderedTail = "蓝色灯塔夜晚由我们一起度过"
        val result = policy.evaluate(
            input(
                user("tail", reorderedTail),
                projectId = "project-1",
                projectFacts = listOf(projectFact),
            ),
            listOf(
                candidate(
                    AgentMemoryKind.SHARED_HISTORY,
                    "tail",
                    reorderedTail,
                    "tail",
                    reorderedTail,
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun projectFingerprintResistsSplitChineseAndIdentifierSeparatorChanges() {
        val chinese = "我们一起经历北、岸、灯、塔、计、划、发、布、新、版、本"
        val ascii = "we went through Oriole 7421 together"
        val result = policy.evaluate(
            input(
                user("chinese", chinese),
                user("ascii", ascii),
                projectId = "project-1",
                projectFacts = listOf(
                    "北岸灯塔计划发布新版本",
                    "Oriole_7421 rollout target",
                ),
            ),
            listOf(
                candidate(
                    AgentMemoryKind.SHARED_HISTORY,
                    "chinese-project",
                    chinese,
                    "chinese",
                    chinese,
                ),
                candidate(
                    AgentMemoryKind.SHARED_HISTORY,
                    "ascii-project",
                    ascii,
                    "ascii",
                    ascii,
                ),
            ),
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(2, result.rejectedCount)
    }

    @Test
    fun dropsMalformedCandidatesIndividuallyWithoutMutatingInput() {
        val input = input(user("valid", "以后默认用中文回答"))
        val valid = candidate(
            AgentMemoryKind.USER_PREFERENCE,
            "language",
            "默认使用中文回答",
            "valid",
            "以后默认用中文回答",
        )
        val candidates = listOf(
            valid,
            valid.copy(dedupeKey = " "),
            valid.copy(content = " "),
            valid.copy(sourceMessageId = " "),
            valid.copy(sourceQuote = " "),
            valid.copy(dedupeKey = "x".repeat(MAX_AGENT_MEMORY_DEDUPE_KEY_CHARS + 1)),
            valid.copy(content = "x".repeat(MAX_AGENT_MEMORY_CONTENT_CHARS + 1)),
            valid.copy(sourceMessageId = "x".repeat(MAX_AGENT_MEMORY_ID_CHARS + 1)),
            valid.copy(sourceQuote = "x".repeat(MAX_AGENT_MEMORY_SOURCE_QUOTE_CHARS + 1)),
            valid.copy(confidence = Double.NaN),
            valid.copy(confidence = Double.POSITIVE_INFINITY),
            valid.copy(content = " ".repeat(MAX_AGENT_MEMORY_CONTENT_CHARS + 1) + valid.content),
        )
        val original = candidates.toList()

        val result = policy.evaluate(input, candidates)

        assertEquals(listOf(valid), result.accepted)
        assertEquals(candidates.size - 1, result.rejectedCount)
        assertEquals(original, candidates)
    }

    @Test
    fun aggregateCandidateCharacterBudgetFailsClosed() {
        val validInput = input(user("valid", "以后默认用中文回答"))
        val candidate = candidate(
            AgentMemoryKind.USER_PREFERENCE,
            "language",
            "默认使用中文回答",
            "valid",
            "以后默认用中文回答",
        )
        val oversized = List(MAX_AGENT_MEMORY_CANDIDATES_PER_MERGE) { index ->
            candidate.copy(
                dedupeKey = "key-$index",
                content = candidate.content + " ".repeat(MAX_AGENT_MEMORY_CONTENT_CHARS - candidate.content.length),
            )
        }

        val result = policy.evaluate(validInput, oversized)

        assertEquals(AgentMemoryPolicyStatus.RESOURCE_LIMIT_EXCEEDED, result.status)
        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun globalResourceLimitsFailClosedWithPureStatus() {
        val validInput = input(user("valid", "以后默认用中文回答"))
        val candidate = candidate(
            AgentMemoryKind.USER_PREFERENCE,
            "language",
            "默认使用中文回答",
            "valid",
            "以后默认用中文回答",
        )
        val tooManyCandidates = policy.evaluate(
            validInput,
            List(MAX_AGENT_MEMORY_CANDIDATES_PER_MERGE + 1) { index ->
                candidate.copy(dedupeKey = "language-$index")
            },
        )
        val tooManyFacts = policy.evaluate(
            validInput.copy(
                projectFacts = List(MAX_AGENT_MEMORY_POLICY_PROJECT_FACTS + 1) { "事实 $it" },
            ),
            listOf(candidate),
        )
        val tooLongMessage = policy.evaluate(
            input(
                user(
                    "long",
                    "x".repeat(MAX_AGENT_MEMORY_POLICY_MESSAGE_CHARS + 1),
                ),
            ),
            listOf(candidate),
        )

        listOf(tooManyCandidates, tooManyFacts, tooLongMessage).forEach { result ->
            assertEquals(AgentMemoryPolicyStatus.RESOURCE_LIMIT_EXCEEDED, result.status)
            assertTrue(result.accepted.isEmpty())
        }
    }

    @Test
    fun invalidInputFailsClosedWithoutEchoingCandidateData() {
        val secret = "do-not-echo-secret"
        val result = policy.evaluate(
            input(user("duplicate", "以后默认用中文回答"))
                .copy(
                    recentMessages = listOf(
                        user("duplicate", "以后默认用中文回答"),
                        assistant("duplicate", "冲突 ID"),
                    ),
                ),
            listOf(
                candidate(
                    AgentMemoryKind.USER_PREFERENCE,
                    "language",
                    secret,
                    "duplicate",
                    "以后默认用中文回答",
                ),
            ),
        )

        assertEquals(AgentMemoryPolicyStatus.INVALID_INPUT, result.status)
        assertTrue(result.accepted.isEmpty())
        assertFalse(result.reason.contains(secret))
    }

    @Test
    fun invalidScopeAndMessageSnapshotsFailClosed() {
        val candidate = candidate(
            AgentMemoryKind.USER_PREFERENCE,
            "language",
            "默认使用中文回答",
            "valid",
            "以后默认用中文回答",
        )
        val valid = input(user("valid", "以后默认用中文回答"))
        val invalidInputs = listOf(
            valid.copy(agentId = " "),
            valid.copy(conversationId = " "),
            valid.copy(projectId = " "),
            valid.copy(agentId = "x".repeat(MAX_AGENT_MEMORY_ID_CHARS + 1)),
            valid.copy(
                recentMessages = listOf(
                    user("valid", "以后默认用中文回答").copy(conversationId = "other"),
                ),
            ),
        )

        invalidInputs.forEach { invalid ->
            val result = policy.evaluate(invalid, listOf(candidate))

            assertEquals(AgentMemoryPolicyStatus.INVALID_INPUT, result.status)
            assertTrue(result.accepted.isEmpty())
            assertEquals(1, result.rejectedCount)
        }
    }

    @Test
    fun duplicateKeyUsesHighestConfidenceAndKeepsFirstOnTieInStableOrder() {
        val input = input(
            user(
                "user-1",
                """
                以后默认用中文回答。
                请一直用中文回复。
                默认中文即可。
                以后请叫我老唐。
                """.trimIndent(),
            ),
        )
        val low = candidate(
            AgentMemoryKind.USER_PREFERENCE,
            " Language ",
            "默认使用中文回答",
            "user-1",
            "以后默认用中文回答。",
            confidence = 0.5,
        )
        val highFirst = low.copy(
            dedupeKey = "language",
            content = "一直使用中文回复",
            sourceQuote = "请一直用中文回复。",
            confidence = 0.9,
        )
        val highSecond = low.copy(
            dedupeKey = "LANGUAGE",
            content = "默认中文",
            sourceQuote = "默认中文即可。",
            confidence = 0.9,
        )
        val address = candidate(
            AgentMemoryKind.ADDRESS_PREFERENCE,
            "address",
            "以后称呼用户为老唐",
            "user-1",
            "以后请叫我老唐。",
        )

        val result = policy.evaluate(input, listOf(low, highFirst, highSecond, address))

        assertEquals(listOf(highFirst, address), result.accepted)
        assertEquals(2, result.rejectedCount)
    }

    @Test
    fun dedupeKeyUsesNfkcAndCollapsedWhitespace() {
        val input = input(user("user-1", "以后默认用中文回答"))
        val first = candidate(
            AgentMemoryKind.USER_PREFERENCE,
            "language preference",
            "默认使用中文回答",
            "user-1",
            "以后默认用中文回答",
        )
        val equivalent = first.copy(dedupeKey = "  ｌａｎｇｕａｇｅ　   ｐｒｅｆｅｒｅｎｃｅ  ")

        val result = policy.evaluate(input, listOf(first, equivalent))

        assertEquals(listOf(first), result.accepted)
        assertEquals(1, result.rejectedCount)
    }

    @Test
    fun messageSnapshotContainsOnlyPolicyFields() {
        assertEquals(
            setOf("id", "conversationId", "role", "status", "content", "order"),
            AgentMemoryMessageSnapshot::class.java.declaredFields
                .filterNot { it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun chineseFingerprintsUseBoundedNgramsInsteadOfWholeRuns() {
        val terms = normalizedMemoryTerms("北岸灯塔计划 Oriole_7421")

        assertTrue("北岸" in terms)
        assertTrue("北岸灯" in terms)
        assertTrue("oriole_7421" in terms)
        assertFalse("北岸灯塔计划" in terms)
        assertTrue(projectFactFingerprints(listOf("北岸灯塔计划")).isNotEmpty())
    }

    private fun input(
        vararg messages: AgentMemoryMessageSnapshot,
        projectId: String? = null,
        projectFacts: List<String> = emptyList(),
    ) = AgentMemoryExtractionInput(
        agentId = "agent-1",
        conversationId = "conversation-1",
        projectId = projectId,
        conversationSummary = "",
        recentMessages = messages.toList(),
        projectFacts = projectFacts,
    )

    private fun candidate(
        kind: AgentMemoryKind,
        dedupeKey: String,
        content: String,
        sourceMessageId: String,
        sourceQuote: String,
        confidence: Double = 0.8,
    ) = AgentMemoryCandidate(
        kind = kind,
        dedupeKey = dedupeKey,
        content = content,
        sourceMessageId = sourceMessageId,
        sourceQuote = sourceQuote,
        confidence = confidence,
    )

    private fun user(
        id: String,
        content: String,
        status: MessageStatus = MessageStatus.SUCCEEDED,
    ) = message(id, MessageRole.USER, content, status)

    private fun assistant(
        id: String,
        content: String,
        status: MessageStatus = MessageStatus.SUCCEEDED,
    ) = message(id, MessageRole.ASSISTANT, content, status)

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        status: MessageStatus,
    ) = AgentMemoryMessageSnapshot(
        id = id,
        conversationId = "conversation-1",
        role = role,
        content = content,
        status = status,
        order = id.hashCode().toLong(),
    )
}
