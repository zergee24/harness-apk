package com.harnessapk.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiTurnIntentParserTest {
    private val aliases = listOf(
        WikiTurnAlias(
            wikiId = "history.zztj",
            title = "资治通鉴",
            displayAliases = setOf("通鉴"),
        ),
        WikiTurnAlias(
            wikiId = "history.24",
            title = "二十四史",
            displayAliases = setOf("二十四史"),
        ),
    )

    @Test
    fun `only this turn detects book title punctuation without mutating mounts`() {
        val intent = WikiTurnIntentParser().parse(
            "这一轮只看《资治通鉴》，王安石如何评价？",
            aliases,
        )

        assertEquals(WikiTurnIntentMode.ONLY_NAMED, intent.mode)
        assertEquals(setOf("history.zztj"), intent.namedWikiIds)
        assertEquals(false, intent.compareRequested)
    }

    @Test
    fun `comparison intent retains every named Wiki in stable order`() {
        val intent = WikiTurnIntentParser().parse(
            "请对比《资治通鉴》和二十四史对王安石的记述",
            aliases,
        )

        assertEquals(WikiTurnIntentMode.COMPARE_NAMED, intent.mode)
        assertEquals(listOf("history.24", "history.zztj"), intent.namedWikiIds.toList())
        assertTrue(intent.compareRequested)
    }

    @Test
    fun `ambiguous narrowing language remains auto without a recognized title`() {
        val intent = WikiTurnIntentParser().parse("这一轮只根据资料回答", aliases)

        assertEquals(WikiTurnIntentMode.AUTO, intent.mode)
        assertEquals(emptySet<String>(), intent.namedWikiIds)
    }

    @Test
    fun `named Wiki outside authorization is surfaced as unavailable`() {
        val intent = WikiTurnIntentParser().parse(
            query = "仅看《资治通鉴》",
            installedAliases = aliases,
            authorization = WikiQueryAuthorization(setOf(WikiRef("history.24", 1))),
        )

        assertEquals(setOf("history.zztj"), intent.unavailableNamedWikiIds)
    }
}
