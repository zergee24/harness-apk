package com.harnessapk.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentMessagePresentationTest {
    @Test
    fun stripsInlineAgentCitationsWhenSourcesArePresentedSeparately() {
        assertEquals(
            "正文结论。下一段。",
            stripAgentCitationMarkers("正文结论。[资料 4]下一段。[资料 12]"),
        )
    }
}
