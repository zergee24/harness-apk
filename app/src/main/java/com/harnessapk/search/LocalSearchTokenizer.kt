package com.harnessapk.search

import com.harnessapk.wiki.WikiSourceSearch
import java.text.Normalizer
import java.util.Locale

object LocalSearchTokenizer {
    private val wordPattern = Regex("[A-Za-z0-9]+")

    fun tokens(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val latin = wordPattern.findAll(Normalizer.normalize(value, Normalizer.Form.NFKC))
            .map { it.value.lowercase(Locale.ROOT) }
        return (WikiSourceSearch.normalizedTokens(value) + latin).toSortedSet().toList()
    }

    fun indexedText(title: String, body: String): String = tokens("$title\n$body").joinToString(" ")

    fun matchExpression(query: String): String = WikiSourceSearch.ftsMatch(tokens(query))
}
