# 三 Tab 底部导航 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 0.2.3 的双面板 Pager 首页重构为底部导航三 Tab（生活/工作/我的），会话列表两套独立（生活=普通会话、工作=项目会话），能力层零改动。

**Architecture:** 只动 UI 导航层与主题映射。`MainMode` 三态 LIFE/WORK/ME；首页 `Routes.Conversations` 内容区按 `when(mainMode)` 渲染三 Tab，Scaffold bottomBar 放 Material3 NavigationBar；移除 HorizontalPager/顶部 Tab/顶栏设置按钮/Routes.Settings；生活 Tab 过滤 `projectId == null` 且移除按项目分组逻辑；`ModeTheme` 映射 ME→techDark+TechShapes。

**Tech Stack:** Kotlin, Jetpack Compose, Material3 NavigationBar, Navigation-Compose, SharedPreferences。

**前置现状（执行前确认）：**
- 当前分支 `test`，HEAD = 2e8b3e0（spec 已定稿：docs/superpowers/specs/2026-08-02-tab-navigation-design.md）。
- 0.2.3 已有：MainMode(LIFE/WORK) + HomeModeStore + ModeTheme（warmLight/techDark + 300ms animateColorScheme）+ 首页 HorizontalPager 双面板 + ConversationListScreen（含按项目分组）+ SettingsScreen 聚合页 + RemoteScreen 独立 route（Routes.RemoteControl）。
- 测试命令：JVM `.\gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"`；androidTest 需设备 `.\gradlew.bat :app:connectedDebugAndroidTest`。

---

### Task 1: MainMode 三态（LIFE/WORK/ME）+ 迁移

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HomeUiState.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/HomeModeStore.kt`
- Test: `app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt`
- Test: `app/src/test/java/com/harnessapk/ui/HomeModeStoreTest.kt`

- [ ] **Step 1: 写失败测试**

`HomeModeUiStateTest.kt` 全文替换为：

```kotlin
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
```

`HomeModeStoreTest.kt` 追加测试：

```kotlin
    @Test
    fun meModeRoundTrips() {
        assertEquals(MainMode.ME, migrateStoredMode("ME"))
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.HomeModeUiStateTest" --tests "com.harnessapk.ui.HomeModeStoreTest"`
Expected: FAIL——`MainMode.ME` 未定义 / `migrateStoredMode` 不含 ME。

- [ ] **Step 3: 实现**

`HomeUiState.kt` 全文替换：

```kotlin
package com.harnessapk.ui

import com.harnessapk.updater.UpdateCheckResult

enum class MainMode(val label: String) {
    LIFE("生活"),
    WORK("工作"),
    ME("我的"),
}

enum class HomePrimaryAction {
    CREATE_CONVERSATION,
    NONE,
}

internal fun homePrimaryAction(mode: MainMode): HomePrimaryAction = when (mode) {
    MainMode.LIFE -> HomePrimaryAction.CREATE_CONVERSATION
    MainMode.WORK -> HomePrimaryAction.NONE
    MainMode.ME -> HomePrimaryAction.NONE
}

internal fun shouldShowUpdateBadge(result: UpdateCheckResult?): Boolean =
    result?.updateAvailable == true || result?.forceUpdate == true

internal fun topLevelTitle(
    mode: MainMode,
    currentProjectName: String?,
): String {
    val projectName = currentProjectName?.trim().orEmpty()
    return when {
        mode != MainMode.WORK -> mode.label
        projectName.isBlank() -> mode.label
        else -> "${mode.label} · $projectName"
    }
}
```

`HomeModeStore.kt` 的 `migrateStoredMode` 替换：

```kotlin
internal fun migrateStoredMode(raw: String?): MainMode = when (raw) {
    "LIFE" -> MainMode.LIFE
    "WORK" -> MainMode.WORK
    "ME" -> MainMode.ME
    else -> MainMode.LIFE
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.HomeModeUiStateTest" --tests "com.harnessapk.ui.HomeModeStoreTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/HomeUiState.kt app/src/main/java/com/harnessapk/ui/HomeModeStore.kt app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt app/src/test/java/com/harnessapk/ui/HomeModeStoreTest.kt
git commit -m "重构：首页模式增加我的 Tab 并更新迁移"
```

---

### Task 2: ModeTheme 映射 ME → 深科技

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/theme/Theme.kt`
- Test: `app/src/test/java/com/harnessapk/ui/theme/ThemeTest.kt`

- [ ] **Step 1: 写失败测试**

`ThemeTest.kt` 追加：

```kotlin
    @Test
    fun meModeUsesTechDarkTheme() {
        assertEquals(
            techDarkColorScheme().background,
            when (MainMode.ME) {
                MainMode.LIFE -> warmLightColorScheme()
                MainMode.WORK -> techDarkColorScheme()
                MainMode.ME -> techDarkColorScheme()
            }.background,
        )
    }
```

（import `com.harnessapk.ui.MainMode`。）

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.theme.ThemeTest"`
Expected: FAIL——`when` 不穷尽（ME 未覆盖）。

- [ ] **Step 3: 实现**

`Theme.kt` 的 `ModeTheme` 与 `HarnessApkTheme` 委托处（`when (mode)` 两处：colorScheme、shapes）补 ME 分支：

```kotlin
@Composable
fun ModeTheme(mode: MainMode, content: @Composable () -> Unit) {
    val targetScheme = when (mode) {
        MainMode.LIFE -> warmLightColorScheme()
        MainMode.WORK -> techDarkColorScheme()
        MainMode.ME -> techDarkColorScheme()
    }
    MaterialTheme(
        colorScheme = animateColorScheme(targetScheme),
        typography = HarnessTypography,
        shapes = when (mode) {
            MainMode.LIFE -> HarnessShapes
            MainMode.WORK -> TechShapes
            MainMode.ME -> TechShapes
        },
        content = content,
    )
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.theme.ThemeTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/theme/Theme.kt app/src/test/java/com/harnessapk/ui/theme/ThemeTest.kt
git commit -m "主题：我的 Tab 映射到深科技主题"
```

---

### Task 3: 首页底部导航三 Tab（替换 Pager）

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt`
- Test: `app/src/androidTest/java/com/harnessapk/ui/DualModeHomePagerTest.kt`（重写为 Tab 导航测试）

- [ ] **Step 1: 重写失败测试**

`DualModeHomePagerTest.kt` 全文替换为 `TabNavigationTest.kt`（删除旧文件、新建新文件）：

删除 `app/src/androidTest/java/com/harnessapk/ui/DualModeHomePagerTest.kt`，新建 `app/src/androidTest/java/com/harnessapk/ui/TabNavigationTest.kt`：

```kotlin
package com.harnessapk.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.harnessapk.HarnessApkApplication
import com.harnessapk.ui.theme.HarnessApkTheme
import com.harnessapk.ui.theme.ModeTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TabNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPersistedMode() {
        (context as HarnessApkApplication).container.homeModeStore.reset()
    }

    @Test
    fun bottomNavShowsThreeTabsAndSettlesToLife() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("生活").assertExists()
        composeRule.onNodeWithText("工作").assertExists()
        composeRule.onNodeWithText("我的").assertExists()
        composeRule.onNodeWithText("还没有会话").assertExists()
    }

    @Test
    fun clickingWorkTabShowsProjectPanel() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("工作").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还没有项目").assertExists()
    }

    @Test
    fun clickingMeTabShowsSettingsAggregation() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("我的").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("模型配置").assertExists()
    }

    @Test
    fun workModeAppliesTechDarkBackground() {
        var background by mutableStateOf(Color.Unspecified)
        composeRule.setContent {
            ModeTheme(MainMode.WORK) {
                background = MaterialTheme.colorScheme.background
            }
        }
        composeRule.onRoot().assertExists()
        assertEquals(Color(0xFF101417), background)
    }

    @Test
    fun meModeAppliesTechDarkBackground() {
        var background by mutableStateOf(Color.Unspecified)
        composeRule.setContent {
            ModeTheme(MainMode.ME) {
                background = MaterialTheme.colorScheme.background
            }
        }
        composeRule.onRoot().assertExists()
        assertEquals(Color(0xFF101417), background)
    }
}
```

注意：`onNodeWithText("我的")` 可能匹配多个节点（底部导航 label + 顶栏标题"我的"）。若执行时冲突，改用 `onAllNodesWithText("我的")[0]` 或给 NavigationBarItem 加 testTag。`"模型配置"` 是 SettingsScreen 内 Provider 入口文案，执行时以 `settingsDestinations` 实际文案为准（Read `ui/settings/SettingsDestinations.kt` 确认）。

- [ ] **Step 2: 更新 HarnessApkAppStateTest 源码断言**

`HarnessApkAppStateTest.kt` 的 `homeModeSwitcherUsesPagerWithSharedSegmentedControl` 替换为：

```kotlin
    @Test
    fun homeNavigationUsesBottomBarWithoutPager() {
        val source = File("src/main/java/com/harnessapk/ui/HarnessApkApp.kt").readText().replace("\r\n", "\n")

        assertTrue(source.contains("NavigationBar {"))
        assertTrue(source.contains("NavigationBarItem("))
        assertFalse(source.contains("HorizontalPager("))
        assertFalse(source.contains("rememberPagerState("))
        assertFalse(source.contains("WarmSegmentedControl("))
    }
```

- [ ] **Step 3: 运行 JVM 测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.HarnessApkAppStateTest"`
Expected: FAIL——NavigationBar 断言不满足。

- [ ] **Step 4: 实现首页重构**

`app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt` 修改（先 Read 确认现状，以内容匹配为准）：

4a. imports：移除 `HorizontalPager`、`rememberPagerState`；追加 `androidx.compose.material3.NavigationBar`、`NavigationBarItem`、`androidx.compose.material.icons.outlined.Home`（或 Chat）、`Icons.Outlined.Work`（如存在；否则 `AccountTree`）、`Icons.Outlined.Person`。

4b. 状态接线：移除 `pagerState` 声明与两个 Pager 同步 LaunchedEffect；保留：

```kotlin
    val homeModeStore = container.homeModeStore
    var mainMode by rememberSaveable { mutableStateOf(homeModeStore.mode.value) }
    LaunchedEffect(mainMode) {
        if (homeModeStore.mode.value != mainMode) {
            homeModeStore.save(mainMode)
        }
    }
```

4c. 顶栏 `HomeTopBar` 移除 `ModeSwitcher` 与设置按钮（`HomeTopBarActions` 保留新建按钮；移除 `onOpenSettings` 参数与调用）。若 HomeTopBar 只剩新建按钮，可直接在 `Scaffold topBar` 内联简化实现（保留函数亦可，改动最小优先）。

4d. `Scaffold` 加 `bottomBar`：

```kotlin
        bottomBar = {
            NavigationBar {
                MainMode.entries.forEach { mode ->
                    NavigationBarItem(
                        selected = mainMode == mode,
                        onClick = { mainMode = mode },
                        icon = { Icon(homeModeIcon(mode), contentDescription = null) },
                        label = { Text(mode.label) },
                    )
                }
            }
        },
```

4e. `HomeUiState.kt` 加图标映射（Task 1 后补，或本任务加）：

```kotlin
internal fun homeModeIcon(mode: MainMode): ImageVector = when (mode) {
    MainMode.LIFE -> Icons.Outlined.Chat
    MainMode.WORK -> Icons.Outlined.AccountTree
    MainMode.ME -> Icons.Outlined.Person
}
```

（`HomeUiState.kt` 需要加 `androidx.compose.material.icons.Icons`、`androidx.compose.material.icons.outlined.Chat`/`AccountTree`/`Person`、`androidx.compose.ui.graphics.vector.ImageVector` imports。图标按实际可用性微调。）

4f. 首页内容区：`composable(Routes.Conversations)` 内移除 `HorizontalPager`，改为：

```kotlin
                when (mainMode) {
                    MainMode.LIFE -> ConversationListScreen(
                        container = container,
                        contentPadding = padding,
                        onOpenChat = { navController.navigate(Routes.chat(it)) },
                        onCreateConversation = onCreateConversation,
                    )
                    MainMode.WORK -> Column(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                        val profile = remoteProfile
                        if (profile != null) {
                            RemoteEntryCard(
                                hostName = profile.hostName,
                                onClick = { navController.navigate(Routes.RemoteControl) },
                                modifier = Modifier.padding(horizontal = HarnessSpacing.pageHorizontal, vertical = 8.dp),
                            )
                        }
                        ProjectScreen(
                            container = container,
                            contentPadding = PaddingValues(
                                start = padding.calculateStartPadding(LocalLayoutDirection.current),
                                end = padding.calculateEndPadding(LocalLayoutDirection.current),
                                bottom = padding.calculateBottomPadding(),
                            ),
                            onCurrentProjectChange = { project ->
                                currentProjectId = project?.id
                                currentProjectName = project?.name
                            },
                            workbenchTarget = workbenchTarget,
                            onWorkbenchTargetConsumed = { requestKey ->
                                if (workbenchTarget?.requestKey == requestKey) workbenchTarget = null
                            },
                            onCreateSession = { project ->
                                scope.launch {
                                    val request = projectConversationRequest(project.id, project.name)
                                    val conversationId = container.newConversationUseCase.create(request)
                                    navController.navigate(
                                        Routes.chat(
                                            conversationId = conversationId,
                                            projectId = project.id,
                                            focusInput = true,
                                        ),
                                    )
                                }
                            },
                            onOpenSession = { conversationId ->
                                navController.navigate(Routes.chat(conversationId = conversationId))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    MainMode.ME -> SettingsScreen(
                        contentPadding = padding,
                        onOpenProviders = { navController.navigate(Routes.Providers) },
                        onOpenSearch = { navController.navigate(Routes.Search) },
                        onOpenVoice = { navController.navigate(Routes.Voice) },
                        onOpenGit = { navController.navigate(Routes.Git) },
                        onOpenSkills = { navController.navigate(Routes.Skills) },
                        onOpenAgentPackages = { navController.navigate(Routes.AgentPackages) },
                        onOpenWikiLibrary = { navController.navigate(Routes.WikiLibrary) },
                        onOpenUpdates = { navController.navigate(Routes.Updates) },
                        onOpenRemote = { navController.navigate(Routes.RemoteSettings) },
                        showUpdateBadge = showUpdateBadge,
                    )
                }
```

（注意：ME 分支的 `SettingsScreen` 需要 import `com.harnessapk.ui.settings.SettingsScreen`；`contentPadding` 直接传 `padding`，因为 SettingsScreen 内部已 `.padding(contentPadding)`。）

4g. 移除 `Routes.Settings` 常量与 NavHost 中 `composable(Routes.Settings)`；确认无其他导航到 `Routes.Settings` 的调用（顶栏设置按钮已移除）。

4h. 保留 `remoteProfile` 订阅（4f 用到）。

- [ ] **Step 5: 运行 JVM 测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全部 PASS（如有 SettingsScreen 相关测试受影响，按失败信息最小适配）。

- [ ] **Step 6: 编译确认**

Run: `.\gradlew.bat :app:assembleDebug` + `.\gradlew.bat :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: androidTest（有设备才跑）**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.harnessapk.ui.TabNavigationTest"`
Expected: PASS。无设备跳过注明。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/main/java/com/harnessapk/ui/HomeUiState.kt app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt app/src/androidTest/java/com/harnessapk/ui/DualModeHomePagerTest.kt app/src/androidTest/java/com/harnessapk/ui/TabNavigationTest.kt
git commit -m "功能：首页改为底部导航三 Tab"
```

---

### Task 4: 生活 Tab 会话过滤与分组逻辑移除

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/conversation/ConversationListUiState.kt`（如分组构建逻辑在此）
- Test: `app/src/test/java/com/harnessapk/ui/conversation/ConversationListUiStateTest.kt`（如有分组相关测试，同步删除/适配）
- Test: `app/src/androidTest/java/com/harnessapk/ui/LifePanelQuickEntryTest.kt`（适配，如无影响则不动）

- [ ] **Step 1: 确认现状**

Read `app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt` 与 `ConversationListUiState.kt`，确认：
- 分组相关代码位置（`groupedByProject`、`buildProjectGroupedConversationState`、`ConversationListHeader`、`ProjectConversationGroupHeader`、`ProjectSessionsToggleRow`、`conversationItems` 的 grouped 分支等）
- 现有 JVM 测试覆盖（`ConversationListUiStateTest.kt` 是否测分组）

- [ ] **Step 2: 写失败测试（UI 层过滤逻辑）**

在 `ConversationListUiStateTest.kt`（或新建 `LifeConversationListTest.kt`）追加纯函数测试（若过滤逻辑抽为纯函数）：

```kotlin
    @Test
    fun lifeConversationFilterKeepsOnlyNonProjectConversations() {
        val conversations = listOf(
            conversation(id = "c1", projectId = null),
            conversation(id = "c2", projectId = "p1"),
            conversation(id = "c3", projectId = null),
        )
        assertEquals(listOf("c1", "c3"), lifeConversations(conversations).map { it.id })
    }
```

若过滤为 UI 层内联（`conversations.filter { it.projectId == null }`）则跳过此步，直接 Step 3 并在 androidTest 覆盖。

- [ ] **Step 3: 实现**

`ConversationListScreen.kt`：
- 3a. 分组状态全部移除：`groupedByProject`、`allProjectSessionsCollapsed`、`collapsedProjectIds`、`groupedState`、`groupedConversationDisplaySections` 相关分支、`ConversationListHeader`、`ProjectConversationGroupHeader`、`ProjectSessionsToggleRow`、`conversationIdentityLabel` 中无分组相关保留（确认后清理 dead code）。
- 3b. 会话过滤：`val lifeConversations = conversations.filter { it.projectId == null }`，列表渲染用 `lifeConversations`（含空态判断 `if (lifeConversations.isEmpty())`）。
- 3c. 移除不再使用的 imports（`buildProjectGroupedConversationState` 等）。

`ConversationListUiState.kt`：移除分组构建逻辑与类型（`buildProjectGroupedConversationState`、`ConversationGroupedDisplaySection`、`ConversationProjectGroup` 等），确认无其他引用（grep 全仓）。

- [ ] **Step 4: 更新/删除受影响测试**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全部 PASS。分组相关测试删除或重写为过滤测试。

- [ ] **Step 5: 编译 + androidTest 编译**

Run: `.\gradlew.bat :app:assembleDebug` + `.\gradlew.bat :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: androidTest（有设备才跑）**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest`
Expected: 生活 Tab 相关测试 PASS。无设备跳过注明。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "功能：生活会话列表仅保留普通会话并移除项目分组"
```

---

### Task 5: 回归适配与全量验证

**Files:**
- Verify: `app/src/test` 全量
- Verify: `app/src/androidTest` 编译 + 既有用例适配（`HarnessApkAppNavigationTest`、`LifePanelQuickEntryTest`、`WorkPanelRemoteEntryTest`——工作 Tab 底部导航语义下文案/交互是否仍成立）

- [ ] **Step 1: 全量 JVM 测试**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全部 PASS。

- [ ] **Step 2: 编译**

Run: `.\gradlew.bat :app:assembleDebug` + `.\gradlew.bat :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 遗留检查**

Run: `Select-String -Path "app/src/main/java/**/*.kt" -Pattern "HorizontalPager|rememberPagerState|WarmSegmentedControl|Routes\.Settings|groupedByProject"`
Expected: 无输出（或仅注释/无关命中）。

- [ ] **Step 4: androidTest 兼容核对**

Read `app/src/androidTest/java/com/harnessapk/ui/HarnessApkAppNavigationTest.kt`、`LifePanelQuickEntryTest.kt`、`WorkPanelRemoteEntryTest.kt`，确认与新导航结构一致；冲突处最小适配（如点击目标从顶部 Tab 改为底部导航项）。

- [ ] **Step 5: 提交（如有修复）**

```bash
git add -A
git commit -m "验证：三 Tab 底部导航回归适配与全量测试"
```

---

## Self-Review

**Spec 覆盖：**
- 底部三 Tab + 移除 Pager/顶部 Tab → Task 3
- 生活=默认生活项目（过滤 projectId==null + 隐藏项目层级）→ Task 4
- 两套会话列表独立（生活普通会话 / 工作项目会话）→ Task 4 + 工作台现状保留
- 我的=设置聚合（内嵌 SettingsScreen + 顶栏设置按钮移除 + Routes.Settings 移除）→ Task 3
- 双主题：LIFE 暖浅 / WORK+ME 深科技 → Task 2
- 持久化三态 + 迁移 → Task 1
- 远程入口卡片保留 → Task 3（4f 原样保留）

**类型一致性：**
- `MainMode.LIFE/WORK/ME` 贯穿全部任务；`migrateStoredMode`（Task 1）→ `homeModeStore`（Task 3）；`ModeTheme` 三态穷尽（Task 2）；`homeModeIcon`（Task 3）与 MainMode 对齐。

**已知风险：**
- androidTest 无设备环境未执行（Tab 文案冲突、SettingsScreen 文案需真机/本地确认），执行时以实际文案适配。
- `Routes.Settings` 移除需 grep 确认无残留导航。
- 0.2.3 已发布测试通道，本改动在 test 分支继续开发，版本号届时再升。
