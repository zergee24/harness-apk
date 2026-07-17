package com.harnessapk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeModeUiStateTest {
    @Test
    fun homeModesKeepConversationAgentProjectOrder() {
        assertEquals(
            listOf(MainMode.SESSION, MainMode.AGENT, MainMode.PROJECT),
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
        assertEquals("智能体", topLevelTitle(MainMode.AGENT, currentProjectName = "不应出现"))
    }

    @Test
    fun homePrimaryActionMatchesCurrentMode() {
        assertEquals(HomePrimaryAction.CREATE_CONVERSATION, homePrimaryAction(MainMode.SESSION))
        assertEquals(HomePrimaryAction.NONE, homePrimaryAction(MainMode.PROJECT))
        assertEquals(HomePrimaryAction.IMPORT_AGENT, homePrimaryAction(MainMode.AGENT))
    }

    @Test
    fun chatRouteCarriesFocusInputOnlyWhenRequested() {
        assertEquals("", chatRouteQuery(projectId = null, focusInput = false, encode = { it }))
        assertEquals("?focusInput=true", chatRouteQuery(projectId = null, focusInput = true, encode = { it }))
        assertEquals(
            "?projectId=p1&focusInput=true",
            chatRouteQuery(projectId = "p1", focusInput = true, encode = { it }),
        )
    }
}
