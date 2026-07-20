package com.harnessapk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeModeUiStateTest {
    @Test
    fun homeOnlyContainsConversationAndProject() {
        assertEquals(
            listOf(MainMode.SESSION, MainMode.PROJECT),
            MainMode.entries.toList(),
        )
    }

    @Test
    fun topLevelTitleKeepsSessionModeProjectAgnostic() {
        assertEquals(
            "会话",
            topLevelTitle(
                mode = MainMode.SESSION,
                currentProjectName = "移动端 Harness",
            ),
        )
    }

    @Test
    fun topLevelTitleUsesCurrentProjectInProjectMode() {
        assertEquals(
            "项目 · 移动端 Harness",
            topLevelTitle(
                mode = MainMode.PROJECT,
                currentProjectName = "移动端 Harness",
            ),
        )
    }

    @Test
    fun topLevelTitleFallsBackWithoutProject() {
        assertEquals("会话", topLevelTitle(MainMode.SESSION, currentProjectName = null))
        assertEquals("项目", topLevelTitle(MainMode.PROJECT, currentProjectName = " "))
    }

    @Test
    fun homePrimaryActionMatchesCurrentMode() {
        assertEquals(HomePrimaryAction.CREATE_CONVERSATION, homePrimaryAction(MainMode.SESSION))
        assertEquals(HomePrimaryAction.NONE, homePrimaryAction(MainMode.PROJECT))
    }

    @Test
    fun chatRouteKeepsOldQueriesAndOptionallyCarriesSourceMessage() {
        assertEquals(
            "",
            chatRouteQuery(projectId = null, focusInput = false, sourceMessageId = null, encode = { it }),
        )
        assertEquals(
            "?focusInput=true",
            chatRouteQuery(projectId = null, focusInput = true, sourceMessageId = null, encode = { it }),
        )
        assertEquals(
            "?projectId=p1&focusInput=true",
            chatRouteQuery(projectId = "p1", focusInput = true, sourceMessageId = null, encode = { it }),
        )
        assertEquals(
            "?sourceMessageId=message%201",
            chatRouteQuery(
                projectId = null,
                focusInput = false,
                sourceMessageId = "message 1",
                encode = { it.replace(" ", "%20") },
            ),
        )
    }
}
