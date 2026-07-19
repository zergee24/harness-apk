package com.harnessapk.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRetrievalPolicyTest {
    private val policy = AgentRetrievalPolicy()

    @Test
    fun exactBudgetsAreStableForEveryIntent() {
        assertEquals(
            AgentRetrievalBudget(0, 0, 0, 0, 0, 0, false),
            policy.budgetFor(AgentQueryIntent.RELATIONSHIP),
        )
        assertEquals(
            AgentRetrievalBudget(1, 0, 0, 4, 4_800, 2, false),
            policy.budgetFor(AgentQueryIntent.EXACT_FACT),
        )
        assertEquals(
            AgentRetrievalBudget(3, 1, 2, 6, 7_200, 2, false),
            policy.budgetFor(AgentQueryIntent.STANCE_METHOD),
        )
        assertEquals(
            AgentRetrievalBudget(4, 2, 1, 8, 9_600, 2, true),
            policy.budgetFor(AgentQueryIntent.TEMPORAL),
        )
        assertEquals(
            AgentRetrievalBudget(6, 2, 2, 12, 14_400, 2, true),
            policy.budgetFor(AgentQueryIntent.GLOBAL),
        )
    }

    @Test
    fun classifiesRelationshipFactStanceTemporalAndGlobalDeterministically() {
        assertEquals(AgentQueryIntent.RELATIONSHIP, policy.intentFor("你好，接着刚才聊"))
        assertEquals(AgentQueryIntent.EXACT_FACT, policy.intentFor("《实践论》发表于哪一年？"))
        assertEquals(AgentQueryIntent.STANCE_METHOD, policy.intentFor("你如何看待调查研究的方法？"))
        assertEquals(AgentQueryIntent.TEMPORAL, policy.intentFor("你早年和晚年的立场有什么变化？"))
        assertEquals(AgentQueryIntent.GLOBAL, policy.intentFor("请概括你的完整思想体系"))
    }

    @Test
    fun classifiesNaturalRelationshipMethodPeriodComparisonAndSummaryPhrasing() {
        assertEquals(AgentQueryIntent.RELATIONSHIP, policy.intentFor("你和周先生是什么关系？"))
        assertEquals(AgentQueryIntent.RELATIONSHIP, policy.intentFor("谢谢，继续说吧"))
        assertEquals(AgentQueryIntent.STANCE_METHOD, policy.intentFor("你会如何开展调查？"))
        assertEquals(AgentQueryIntent.TEMPORAL, policy.intentFor("1930年和1940年的判断有什么不同？"))
        assertEquals(AgentQueryIntent.GLOBAL, policy.intentFor("请总结一下你的主要观点"))
    }

    @Test
    fun greetingAndThanksOnlyClassifyAsRelationshipWhenTheWholeMessageIsSocial() {
        val matrix = listOf(
            "hi" to AgentQueryIntent.RELATIONSHIP,
            "Thanks, continue please." to AgentQueryIntent.RELATIONSHIP,
            "Which year was the essay published?" to AgentQueryIntent.EXACT_FACT,
            "What is his method?" to AgentQueryIntent.STANCE_METHOD,
            "This happened where?" to AgentQueryIntent.EXACT_FACT,
            "hi, which year was the essay published?" to AgentQueryIntent.EXACT_FACT,
            "thanks, please summarize the argument" to AgentQueryIntent.GLOBAL,
            "谢谢，《实践论》发表于哪一年？" to AgentQueryIntent.EXACT_FACT,
            "谢谢，你如何开展调查？" to AgentQueryIntent.STANCE_METHOD,
            "你好，请总结一下你的主要观点" to AgentQueryIntent.GLOBAL,
        )

        matrix.forEach { (query, expected) ->
            assertEquals(query, expected, policy.intentFor(query))
        }
    }
}
