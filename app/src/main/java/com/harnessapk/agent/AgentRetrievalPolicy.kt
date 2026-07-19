package com.harnessapk.agent

class AgentRetrievalPolicy {
    fun intentFor(query: String): AgentQueryIntent {
        val normalized = query.trim().lowercase()
        return when {
            containsAnyTerm(normalized, GLOBAL_TERMS) -> AgentQueryIntent.GLOBAL
            containsAnyTerm(normalized, TEMPORAL_TERMS) || YEAR_PATTERN.findAll(normalized).count() >= 2 -> {
                AgentQueryIntent.TEMPORAL
            }
            containsAnyTerm(normalized, RELATIONSHIP_TOPIC_TERMS) -> AgentQueryIntent.RELATIONSHIP
            containsAnyTerm(normalized, STANCE_TERMS) -> AgentQueryIntent.STANCE_METHOD
            isPureSocialMessage(normalized) -> AgentQueryIntent.RELATIONSHIP
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

    private fun isPureSocialMessage(query: String): Boolean {
        if (query.isBlank() || query.length > MAX_SOCIAL_MESSAGE_LENGTH) return false
        val normalized = query.replace(SOCIAL_PUNCTUATION, " ").trim()
        if (normalized.isBlank()) return false
        val latinWords = ENGLISH_WORD.findAll(normalized).map(MatchResult::value).toList()
        val withoutLatin = normalized.replace(ENGLISH_WORD, "").replace(Regex("\\s+"), "")
        return latinWords.all { it in ENGLISH_SOCIAL_WORDS } &&
            CHINESE_SOCIAL_MESSAGE.matches(withoutLatin)
    }

    private companion object {
        const val MAX_SOCIAL_MESSAGE_LENGTH = 64
        val RELATIONSHIP_TOPIC_TERMS = listOf("关系", "认识", "朋友", "家人", "同事", "relationship")
        val STANCE_TERMS = listOf(
            "怎么看", "如何", "为什么", "方法", "立场", "主张", "态度", "怎么", "how", "why", "method",
        )
        val TEMPORAL_TERMS = listOf("早年", "晚年", "当时", "后来", "前期", "后期", "时期", "阶段", "演变", "变化", "前后")
        val GLOBAL_TERMS = listOf(
            "总结", "概括", "完整思想", "思想体系", "全局", "总体", "一生",
            "complete worldview", "overview", "summarize", "summary",
        )
        val YEAR_PATTERN = Regex("(?:18|19|20)\\d{2}年")
        val ENGLISH_WORD = Regex("[a-z]+")
        val SOCIAL_PUNCTUATION = Regex("[\\s，,。.!！？?、；;：:]+")
        val ENGLISH_SOCIAL_WORDS = setOf(
            "hello", "hi", "thanks", "thank", "you", "continue", "please", "good", "morning",
            "evening", "bye", "goodbye", "go", "on", "there",
        )
        val CHINESE_SOCIAL_MESSAGE = Regex(
            "^(?:你好|您好|早上好|晚上好|嗨|谢谢|多谢|感谢|再见|继续|接着|刚才|承接前文|说|聊|话题|前文|下去|吧|呀|啊|啦|哦|你|您|的)*$",
        )
    }
}

private fun containsAnyTerm(text: String, terms: List<String>): Boolean = terms.any { term ->
    if (term.any { it in 'a'..'z' }) {
        Regex("(?<![a-z0-9_])${Regex.escape(term)}(?![a-z0-9_])").containsMatchIn(text)
    } else {
        text.contains(term)
    }
}
