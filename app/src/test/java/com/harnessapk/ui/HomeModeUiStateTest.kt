package com.harnessapk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeModeUiStateTest {
    @Test
    fun homeContainsLifeWorkAndMeModes() {
        assertEquals(
            listOf(MainMode.LIFE, MainMode.WORK, MainMode.ME),
            MainMode.entries.toList(),
        )
    }

    @Test
    fun topLevelTitleLifeModeProjectAgnostic() {
        assertEquals(
            "生活",
            topLevelTitle(
                mode = MainMode.LIFE,
                currentProjectName = "移动端 Harness",
            ),
        )
    }

    @Test
    fun topLevelTitleUsesCurrentProjectInWorkMode() {
        assertEquals(
            "工作 · 移动端 Harness",
            topLevelTitle(
                mode = MainMode.WORK,
                currentProjectName = "移动端 Harness",
            ),
        )
    }

    @Test
    fun topLevelTitleFallsBackWithoutProject() {
        assertEquals("生活", topLevelTitle(MainMode.LIFE, currentProjectName = null))
        assertEquals("工作", topLevelTitle(MainMode.WORK, currentProjectName = " "))
    }

    @Test
    fun topLevelTitleMeModeIsStatic() {
        assertEquals("我的", topLevelTitle(MainMode.ME, currentProjectName = "移动端 Harness"))
        assertEquals("我的", topLevelTitle(MainMode.ME, currentProjectName = null))
    }

    @Test
    fun homePrimaryActionMatchesCurrentMode() {
        assertEquals(HomePrimaryAction.CREATE_CONVERSATION, homePrimaryAction(MainMode.LIFE))
        assertEquals(HomePrimaryAction.NONE, homePrimaryAction(MainMode.WORK))
        assertEquals(HomePrimaryAction.NONE, homePrimaryAction(MainMode.ME))
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
