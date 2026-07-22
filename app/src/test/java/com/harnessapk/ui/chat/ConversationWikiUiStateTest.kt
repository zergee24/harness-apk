package com.harnessapk.ui.chat

import com.harnessapk.wiki.ConversationWikiMount
import com.harnessapk.wiki.ConversationWikiMountSelection
import com.harnessapk.wiki.WikiRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWikiUiStateTest {
    @Test
    fun `zero one and two enabled Wikis use compact toolbar labels`() {
        val first = catalog("history.24", "二十四史", version = 1, active = true)
        val second = catalog("history.zztj", "资治通鉴", version = 2, active = true)

        assertEquals("知识库", conversationWikiUiState(emptyList(), listOf(first, second)).toolbarLabel)
        assertEquals(
            "自动 · 1",
            conversationWikiUiState(listOf(mount(first.versions.single().ref)), listOf(first, second)).toolbarLabel,
        )
        assertEquals(
            "自动 · 2",
            conversationWikiUiState(
                listOf(mount(first.versions.single().ref), mount(second.versions.single().ref)),
                listOf(first, second),
            ).toolbarLabel,
        )
    }

    @Test
    fun `unavailable mounted version keeps an exception label and remains manageable`() {
        val stale = ConversationWikiCatalogEntry(
            wikiId = "history.24",
            title = "二十四史",
            versions = listOf(
                ConversationWikiCatalogVersion(WikiRef("history.24", 1), ready = false, active = false),
                ConversationWikiCatalogVersion(WikiRef("history.24", 2), ready = true, active = true),
            ),
        )

        val state = conversationWikiUiState(listOf(mount(WikiRef("history.24", 1))), listOf(stale))

        assertEquals("自动 · 0 · 异常", state.toolbarLabel)
        assertTrue(state.options.single().unavailable)
        assertEquals(WikiRef("history.24", 2), state.options.single().suggestedReadyRef)
    }

    @Test
    fun `draft enables an unmounted Wiki and selects an exact ready version`() {
        val entry = ConversationWikiCatalogEntry(
            wikiId = "history.zztj",
            title = "资治通鉴",
            versions = listOf(
                ConversationWikiCatalogVersion(WikiRef("history.zztj", 1), ready = true, active = false),
                ConversationWikiCatalogVersion(WikiRef("history.zztj", 2), ready = true, active = true),
            ),
        )
        val state = conversationWikiUiState(emptyList(), listOf(entry))
        val enabled = initialConversationWikiScopeDraft(state).setEnabled(state.options.single(), enabled = true)
        val switched = enabled.selectVersion("history.zztj", WikiRef("history.zztj", 1))

        assertEquals(
            listOf(ConversationWikiMountSelection(WikiRef("history.zztj", 1), enabled = true)),
            switched.mountSelections(),
        )
    }

    @Test
    fun `disabling an unmounted Wiki does not create a reference`() {
        val entry = catalog("history.24", "二十四史", version = 1, active = true)
        val state = conversationWikiUiState(emptyList(), listOf(entry))
        val draft = initialConversationWikiScopeDraft(state).setEnabled(state.options.single(), enabled = false)

        assertTrue(draft.mountSelections().isEmpty())
        assertNull(draft.entries.single().ref)
        assertFalse(draft.entries.single().enabled)
    }
}

private fun catalog(
    wikiId: String,
    title: String,
    version: Int,
    active: Boolean,
): ConversationWikiCatalogEntry = ConversationWikiCatalogEntry(
    wikiId = wikiId,
    title = title,
    versions = listOf(ConversationWikiCatalogVersion(WikiRef(wikiId, version), ready = true, active = active)),
)

private fun mount(ref: WikiRef, enabled: Boolean = true): ConversationWikiMount = ConversationWikiMount(
    conversationId = "conversation-1",
    ref = ref,
    enabled = enabled,
    mountedAt = 1L,
    updatedAt = 1L,
)
