package com.harnessapk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeModeUiStateTest {
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
    fun chatRouteCarriesFocusInputOnlyWhenRequested() {
        assertEquals("", chatRouteQuery(projectId = null, focusInput = false, encode = { it }))
        assertEquals("?focusInput=true", chatRouteQuery(projectId = null, focusInput = true, encode = { it }))
        assertEquals(
            "?projectId=p1&focusInput=true",
            chatRouteQuery(projectId = "p1", focusInput = true, encode = { it }),
        )
    }
}
