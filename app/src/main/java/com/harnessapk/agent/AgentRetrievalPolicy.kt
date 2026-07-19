package com.harnessapk.agent

class AgentRetrievalPolicy {
    fun intentFor(query: String): AgentQueryIntent {
        val normalized = query.trim().lowercase()
        return when {
            GLOBAL_TERMS.any(normalized::contains) -> AgentQueryIntent.GLOBAL
            TEMPORAL_TERMS.any(normalized::contains) || YEAR_PATTERN.findAll(normalized).count() >= 2 -> {
                AgentQueryIntent.TEMPORAL
            }
            isRelationshipMessage(normalized) -> AgentQueryIntent.RELATIONSHIP
            STANCE_TERMS.any(normalized::contains) -> AgentQueryIntent.STANCE_METHOD
            else -> AgentQueryIntent.EXACT_FACT
        }
    }

    fun budgetFor(intent: AgentQueryIntent): AgentRetrievalBudget = when (intent) {
        AgentQueryIntent.RELATIONSHIP -> AgentRetrievalBudget(0, 0, 0, 0, 0, 0, false)
        AgentQueryIntent.EXACT_FACT -> AgentRetrievalBudget(1, 0, 0, 4, 4_800, 2, false)
        AgentQueryIntent.STANCE_METHOD -> AgentRetrievalBudget(3, 1, 2, 6, 7_200, 2, false)
        AgentQueryIntent.TEMPORAL -> AgentRetrievalBudget(4, 2, 1, 8, 9_600, 2, true)
        AgentQueryIntent.GLOBAL -> AgentRetrievalBudget(6, 2, 2, 12, 14_400, 2, true)
    }

    private fun isRelationshipMessage(query: String): Boolean {
        if (RELATIONSHIP_TERMS.any(query::contains)) return true
        return query.length <= SHORT_GREETING_LIMIT && GREETING_TERMS.any(query::contains)
    }

    private companion object {
        const val SHORT_GREETING_LIMIT = 32
        val GREETING_TERMS = listOf("你好", "您好", "早上好", "晚上好", "嗨", "hello", "hi", "谢谢", "再见")
        val RELATIONSHIP_TERMS = listOf(
            "继续", "接着", "刚才", "承接前文", "关系", "认识", "朋友", "家人", "同事", "谢谢",
        )
        val STANCE_TERMS = listOf("怎么看", "如何", "为什么", "方法", "立场", "主张", "态度", "怎么", "how", "why")
        val TEMPORAL_TERMS = listOf("早年", "晚年", "当时", "后来", "前期", "后期", "时期", "阶段", "演变", "变化", "前后")
        val GLOBAL_TERMS = listOf("总结", "概括", "完整思想", "思想体系", "全局", "总体", "一生", "complete worldview", "overview")
        val YEAR_PATTERN = Regex("(?:18|19|20)\\d{2}年")
    }
}
