package com.harnessapk.search

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSearchUiStateTest {
    @Test
    fun resultTargetsExactMessageOrProject() {
        assertEquals(
            LocalSearchTarget.ConversationMessage("conversation-1", "message-1"),
            LocalSearchResult(
                id = "message:message-1",
                type = LocalSearchDocumentType.MESSAGE,
                title = "家庭计划",
                snippet = "讨论预算",
                conversationId = "conversation-1",
                messageId = "message-1",
                projectId = "project-1",
                updatedAt = 1L,
            ).target(),
        )
        assertEquals(
            LocalSearchTarget.Project("project-1"),
            LocalSearchResult(
                id = "project:project-1",
                type = LocalSearchDocumentType.PROJECT_NAME,
                title = "家庭计划",
                snippet = "",
                conversationId = null,
                messageId = null,
                projectId = "project-1",
                updatedAt = 1L,
            ).target(),
        )
    }
}
