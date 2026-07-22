package com.harnessapk.wiki

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

object WikiSourceSearch {
    const val RRF_K = 60
    const val MAX_RESULT_LIMIT = 100
    const val MAX_CHANNEL_CANDIDATES = 200
    const val MAX_QUERY_CODE_POINTS = 320
    const val MAX_SNIPPET_CODE_POINTS = 320

    private val traditionalVariants = mapOf(
        "萬" to "万",
        "與" to "与",
        "東" to "东",
        "書" to "书",
        "亂" to "乱",
        "於" to "于",
        "會" to "会",
        "傳" to "传",
        "體" to "体",
        "來" to "来",
        "後" to "后",
        "從" to "从",
        "徵" to "征",
        "時" to "时",
        "晉" to "晋",
        "國" to "国",
        "學" to "学",
        "將" to "将",
        "歲" to "岁",
        "漢" to "汉",
        "無" to "无",
        "為" to "为",
        "禮" to "礼",
        "紀" to "纪",
        "聞" to "闻",
        "臺" to "台",
        "號" to "号",
        "說" to "说",
        "軍" to "军",
        "長" to "长",
        "門" to "门",
        "馬" to "马",
        "發" to "发",
        "職" to "职",
    )
    private val punctuationCodePoints = (
        "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~" +
            "，。！？；：、（）【】《》〈〉「」『』〔〕…—·﹏"
        ).codePoints().toArray().toSet()
    private val wordPattern = Regex("[A-Za-z0-9]+")

    val normalizationMapHash: String by lazy {
        val canonicalMap = traditionalVariants.entries
            .sortedBy { entry -> entry.key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                "\"$key\":\"$value\""
            }
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalMap.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun validateLimit(limit: Int) {
        require(limit in 1..MAX_RESULT_LIMIT) { "Wiki 检索数量必须在 1 到 $MAX_RESULT_LIMIT 之间" }
    }

    fun validateQuery(query: String) {
        require(query.isNotBlank()) { "Wiki 检索内容不能为空" }
        require(query.codePointCount(0, query.length) <= MAX_QUERY_CODE_POINTS) {
            "Wiki 检索内容不能超过 $MAX_QUERY_CODE_POINTS 个字符"
        }
    }

    fun originalTokens(query: String): List<String> = tokens(query, normalized = false)

    fun normalizedTokens(query: String): List<String> = tokens(query, normalized = true)

    fun ftsMatch(tokens: Collection<String>): String =
        tokens.asSequence()
            .filter { token -> token.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString(" OR ") { token -> "\"${token.replace("\"", "\"\"")}\"" }

    fun fuse(channelRankings: List<List<WikiSearchCandidate>>, limit: Int): List<WikiRankedSource> {
        validateLimit(limit)
        val scores = linkedMapOf<String, Double>()
        val matches = linkedMapOf<String, LinkedHashSet<WikiSourceMatch>>()
        channelRankings.forEach { ranking ->
            val seenInChannel = mutableSetOf<String>()
            ranking.forEachIndexed { index, candidate ->
                if (candidate.chunkId.isBlank() || !seenInChannel.add(candidate.chunkId)) return@forEachIndexed
                scores[candidate.chunkId] = (scores[candidate.chunkId] ?: 0.0) + 1.0 / (RRF_K + index + 1)
                matches.getOrPut(candidate.chunkId) { LinkedHashSet() }.add(candidate.match)
            }
        }
        return scores.keys
            .sortedWith(compareByDescending<String> { scores.getValue(it) }.thenBy { it })
            .take(limit)
            .map { chunkId ->
                WikiRankedSource(
                    chunkId = chunkId,
                    matches = matches.getValue(chunkId).sortedWith(
                        compareBy(WikiSourceMatch::channel, WikiSourceMatch::label),
                    ),
                )
            }
    }

    fun truncateSnippet(value: String): String {
        if (value.codePointCount(0, value.length) <= MAX_SNIPPET_CODE_POINTS) return value
        return value.substring(0, value.offsetByCodePoints(0, MAX_SNIPPET_CODE_POINTS)) + "..."
    }

    private fun tokens(query: String, normalized: Boolean): List<String> {
        val surface = if (normalized) normalizeForSearch(query) else compactForSearch(query, translateVariants = false)
        val ngrams = ngrams(surface)
        val words = wordPattern.findAll(surface).map { match -> match.value.lowercase(Locale.ROOT) }
        return (ngrams + words).toSortedSet().toList()
    }

    private fun normalizeForSearch(value: String): String = compactForSearch(value, translateVariants = true)

    private fun compactForSearch(value: String, translateVariants: Boolean): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        return buildString {
            var offset = 0
            while (offset < normalized.length) {
                val codePoint = normalized.codePointAt(offset)
                offset += Character.charCount(codePoint)
                if (Character.isWhitespace(codePoint) || codePoint == IDEOGRAPHIC_SPACE || codePoint in punctuationCodePoints) {
                    continue
                }
                val character = String(Character.toChars(codePoint))
                val replacement = if (translateVariants) traditionalVariants[character] ?: character else character
                append(replacement)
            }
        }
    }

    private fun ngrams(value: String): List<String> {
        val codePoints = value.codePoints().toArray()
        val tokens = sortedSetOf<String>()
        for (size in 2..3) {
            for (start in 0..(codePoints.size - size)) {
                val token = buildString {
                    for (index in start until start + size) appendCodePoint(codePoints[index])
                }
                if (token.any { character -> character in CJK_START_CHAR..CJK_END_CHAR }) tokens += token
            }
        }
        return tokens.toList()
    }

    private const val IDEOGRAPHIC_SPACE = 0x3000
    private const val CJK_START_CHAR = '\u3400'
    private const val CJK_END_CHAR = '\u9fff'
}
