package com.harnessapk.wiki

import java.text.Normalizer
import java.util.Locale

class WikiTurnIntentParser {
    fun parse(
        query: String,
        installedAliases: Collection<WikiTurnAlias>,
        authorization: WikiQueryAuthorization? = null,
    ): WikiTurnIntent {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) {
            return WikiTurnIntent(WikiTurnIntentMode.AUTO, emptySet(), compareRequested = false)
        }
        val aliasIndex = buildAliasIndex(installedAliases)
        val namedIds = aliasIndex.entries
            .asSequence()
            .filter { (alias, wikiIds) -> alias.length >= MIN_ALIAS_LENGTH && wikiIds.size == 1 && normalizedQuery.contains(alias) }
            .map { (_, wikiIds) -> wikiIds.single() }
            .toSortedSet()
            .toCollection(linkedSetOf())
        val compareRequested = COMPARE_MARKERS.any { marker -> normalizedQuery.contains(marker) }
        val onlyRequested = ONLY_MARKERS.any { marker -> normalizedQuery.contains(marker) }
        val mode = when {
            compareRequested && namedIds.isNotEmpty() -> WikiTurnIntentMode.COMPARE_NAMED
            onlyRequested && namedIds.isNotEmpty() -> WikiTurnIntentMode.ONLY_NAMED
            else -> WikiTurnIntentMode.AUTO
        }
        val unavailable = authorization?.let { scope ->
            namedIds.filterNot { wikiId -> scope.refs().any { ref -> ref.wikiId == wikiId } }.toCollection(linkedSetOf())
        }.orEmpty()
        return WikiTurnIntent(
            mode = mode,
            namedWikiIds = namedIds,
            compareRequested = compareRequested,
            unavailableNamedWikiIds = unavailable,
        )
    }

    private fun buildAliasIndex(installedAliases: Collection<WikiTurnAlias>): Map<String, Set<String>> = buildMap {
        installedAliases.forEach { alias ->
            validateWikiRef(WikiRef(alias.wikiId, 1))
            (alias.displayAliases + alias.title + alias.wikiId)
                .map(::normalize)
                .filter { value -> value.length >= MIN_ALIAS_LENGTH }
                .forEach { normalized ->
                    val current = get(normalized).orEmpty()
                    put(normalized, current + alias.wikiId)
                }
        }
    }

    private fun normalize(value: String): String = buildString {
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .forEach { character ->
                if (character.isLetterOrDigit() || character in CJK_RANGE) append(character)
            }
    }

    private companion object {
        const val MIN_ALIAS_LENGTH = 2
        private val CJK_RANGE = '\u3400'..'\u9fff'
        private val ONLY_MARKERS = listOf("只看", "仅看", "只根据", "仅根据")
        private val COMPARE_MARKERS = listOf("比较", "对比", "互证", "两边")
    }
}
