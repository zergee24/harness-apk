# 双模式首页与双主题 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把首页从三平级 Tab（会话/项目/远程）重构为生活/工作双模式 Pager 首页，并引入暖浅/深科技双主题，模式持久化，能力层零改动。

**Architecture:** 只动 UI 导航层与主题层。`MainMode` 重定义为 LIFE/WORK；首页 `Routes.Conversations` 内用 `HorizontalPager` 双面板 + 顶部 `WarmSegmentedControl`；`MaterialTheme` 按模式切换（生活=现有 warmLight + HarnessShapes，工作=新 techDark + TechShapes）；模式经 `HomeModeStore`（SharedPreferences）持久化，旧值 SESSION→LIFE、PROJECT/REMOTE→WORK。

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2026.06.01, HorizontalPager 来自 foundation), Material3, Navigation-Compose 2.9.8, SharedPreferences。

**前置现状（执行前确认）：**
- 首页：`Routes.Conversations` 单一 route，内部 `when (mainMode)` 三选一（`HarnessApkApp.kt:369-406`）；顶部 `HomeTopBar` 含 `ModeSwitcher`（WarmSegmentedControl 三选一，`HarnessApkApp.kt:664-716`）；无底部导航。
- 主题：`HarnessApkTheme` 跟随系统深浅（warmLight/warmDark），`MainActivity` 与 androidTest 均以无参 `HarnessApkTheme {}` 调用。
- 持久化范例：`RemoteProfileStore`（getSharedPreferences + StateFlow，`remote/RemoteProfileStore.kt`）。
- 测试命令：JVM `.\gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"`；androidTest 需设备/模拟器 `.\gradlew.bat :app:connectedDebugAndroidTest`。

---

### Task 1: MainMode 重定义（LIFE/WORK）+ 调用点最小更新

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HomeUiState.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Test: `app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt`

- [ ] **Step 1: 重写失败测试**

覆盖 `app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt` 全部内容：

```kotlin
package com.harnessapk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeModeUiStateTest {
    @Test
    fun homeContainsLifeAndWorkModes() {
        assertEquals(
            listOf(MainMode.LIFE, MainMode.WORK),
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
    fun homePrimaryActionMatchesCurrentMode() {
        assertEquals(HomePrimaryAction.CREATE_CONVERSATION, homePrimaryAction(MainMode.LIFE))
        assertEquals(HomePrimaryAction.NONE, homePrimaryAction(MainMode.WORK))
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

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.HomeModeUiStateTest"`
Expected: FAIL——编译错误（`MainMode.SESSION` 等不存在）。

- [ ] **Step 3: 重定义 MainMode 与纯函数**

`app/src/main/java/com/harnessapk/ui/HomeUiState.kt` 全文替换为：

```kotlin
package com.harnessapk.ui

import com.harnessapk.updater.UpdateCheckResult

enum class MainMode(val label: String) {
    LIFE("生活"),
    WORK("工作"),
}

enum class HomePrimaryAction {
    CREATE_CONVERSATION,
    NONE,
}

internal fun homePrimaryAction(mode: MainMode): HomePrimaryAction = when (mode) {
    MainMode.LIFE -> HomePrimaryAction.CREATE_CONVERSATION
    MainMode.WORK -> HomePrimaryAction.NONE
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

- [ ] **Step 4: 最小更新 HarnessApkApp 调用点**

`app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt` 依次修改：

4a. 第 155 行（mainMode 初始值）：
```kotlin
var mainMode by rememberSaveable { mutableStateOf(MainMode.LIFE) }
```

4b. 删除第 177-179 行（REMOTE 兜底逻辑）：
```kotlin
LaunchedEffect(remoteProfile) {
    if (remoteProfile == null && mainMode == MainMode.REMOTE) mainMode = MainMode.SESSION
}
```

4c. 第 294 行（openWorkbench）：
```kotlin
mainMode = MainMode.WORK
```

4d. 首页 `when (mainMode)` 分支（约 369-406 行）改为两分支，删除 `MainMode.REMOTE -> RemoteScreen(...)`（远程入口由 Task 6 恢复）：
```kotlin
when (mainMode) {
    MainMode.LIFE -> ConversationListScreen(
        container = container,
        contentPadding = padding,
        onOpenChat = { navController.navigate(Routes.chat(it)) },
        onCreateConversation = onCreateConversation,
    )
    MainMode.WORK -> ProjectScreen(
        container = container,
        contentPadding = padding,
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
    )
}
```

4e. `ModeSwitcher`（约 700-716 行）去掉 `remoteEnabled` 过滤：
```kotlin
@Composable
private fun ModeSwitcher(
    mode: MainMode,
    onModeChange: (MainMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = MainMode.entries.toList()
    WarmSegmentedControl(
        options = modes.map { it.label },
        selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
        onSelected = { index -> onModeChange(modes[index]) },
        modifier = modifier,
    )
}
```

4f. `HomeTopBar`（约 664-698 行）移除 `remoteEnabled` 参数及其传递：
```kotlin
@Composable
private fun HomeTopBar(
    mode: MainMode,
    onModeChange: (MainMode) -> Unit,
    primaryAction: HomePrimaryAction,
    showUpdateBadge: Boolean,
    onCreateConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(64.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSwitcher(
            mode = mode,
            onModeChange = onModeChange,
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 216.dp),
        )
        HomeTopBarActions(
            primaryAction = primaryAction,
            showUpdateBadge = showUpdateBadge,
            onCreateConversation = onCreateConversation,
            onOpenSettings = onOpenSettings,
        )
    }
}
```

4g. `HomeTopBar` 调用处（约 300-308 行）删除 `remoteEnabled = remoteProfile != null`。

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.HomeModeUiStateTest"`
Expected: PASS（5 个测试）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/HomeUiState.kt app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt
git commit -m "重构：首页模式改为生活/工作双态"
```

---

### Task 2: HomeModeStore 持久化与旧值迁移

**Files:**
- Create: `app/src/main/java/com/harnessapk/ui/HomeModeStore.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Test: `app/src/test/java/com/harnessapk/ui/HomeModeStoreTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/harnessapk/ui/HomeModeStoreTest.kt`：

```kotlin
package com.harnessapk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeModeStoreTest {
    @Test
    fun nullOrUnknownStoredValueFallsBackToLife() {
        assertEquals(MainMode.LIFE, migrateStoredMode(null))
        assertEquals(MainMode.LIFE, migrateStoredMode("UNKNOWN_LEGACY"))
    }

    @Test
    fun lifeModeRoundTrips() {
        assertEquals(MainMode.LIFE, migrateStoredMode("LIFE"))
    }

    @Test
    fun workModeRoundTrips() {
        assertEquals(MainMode.WORK, migrateStoredMode("WORK"))
    }

    @Test
    fun legacySessionMapsToLife() {
        assertEquals(MainMode.LIFE, migrateStoredMode("SESSION"))
    }

    @Test
    fun legacyProjectMapsToWork() {
        assertEquals(MainMode.WORK, migrateStoredMode("PROJECT"))
    }

    @Test
    fun legacyRemoteMapsToWork() {
        assertEquals(MainMode.WORK, migrateStoredMode("REMOTE"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.HomeModeStoreTest"`
Expected: FAIL——`migrateStoredMode` 未定义。

- [ ] **Step 3: 实现 HomeModeStore**

创建 `app/src/main/java/com/harnessapk/ui/HomeModeStore.kt`：

```kotlin
package com.harnessapk.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun migrateStoredMode(raw: String?): MainMode = when (raw) {
    "LIFE" -> MainMode.LIFE
    "SESSION" -> MainMode.LIFE
    "WORK", "PROJECT", "REMOTE" -> MainMode.WORK
    else -> MainMode.LIFE
}

class HomeModeStore(context: Context) {
    private val preferences = context.getSharedPreferences("home_mode", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(load())
    val mode: StateFlow<MainMode> = _mode.asStateFlow()

    fun save(mode: MainMode) {
        preferences.edit().putString("main_mode", mode.name).apply()
        _mode.value = mode
    }

    private fun load(): MainMode = migrateStoredMode(preferences.getString("main_mode", null))
}
```

- [ ] **Step 4: AppContainer 接线**

`app/src/main/java/com/harnessapk/common/AppContainer.kt` 增加 import 与实例（放在 `remoteProfileStore` 声明旁，第 133 行附近）：

```kotlin
import com.harnessapk.ui.HomeModeStore
```

```kotlin
    val homeModeStore = HomeModeStore(appContext)
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.HomeModeStoreTest"`
Expected: PASS（6 个测试）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/HomeModeStore.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/test/java/com/harnessapk/ui/HomeModeStoreTest.kt
git commit -m "功能：首页模式选择持久化与旧值迁移"
```

---

### Task 3: 深科技主题与模式化主题入口

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/theme/Theme.kt`
- Test: `app/src/test/java/com/harnessapk/ui/theme/ThemeTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/harnessapk/ui/theme/ThemeTest.kt` 末尾追加：

```kotlin
    @Test
    fun workThemeUsesApprovedTechDarkTokens() {
        val scheme = techDarkColorScheme()

        assertEquals(Color(0xFF8CC9F0), scheme.primary)
        assertEquals(Color(0xFF101417), scheme.background)
        assertEquals(Color(0xFF161B1F), scheme.surface)
        assertEquals(Color(0xFFE2E5E8), scheme.onBackground)
        assertEquals(Color(0xFFB9C3CB), scheme.onSurfaceVariant)
    }

    @Test
    fun workThemeUsesSharpTechShapes() {
        assertEquals(4.dp, TechShapes.extraSmall.topStart)
        assertEquals(8.dp, TechShapes.medium.topStart)
        assertEquals(12.dp, TechShapes.extraLarge.topStart)
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.theme.ThemeTest"`
Expected: FAIL——`techDarkColorScheme` / `TechShapes` 未定义。

- [ ] **Step 3: 实现 techDarkColorScheme、TechShapes、ModeTheme**

`app/src/main/java/com/harnessapk/ui/theme/Theme.kt` 修改：

3a. 移除 `isSystemInDarkTheme` import（第 3 行），新增 `com.harnessapk.ui.MainMode` import。

3b. 在 `warmDarkColorScheme()` 之后追加：

```kotlin
internal fun techDarkColorScheme() = darkColorScheme(
    primary = Color(0xFF8CC9F0),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF004C6E),
    onPrimaryContainer = Color(0xFFC6E8FF),
    secondary = Color(0xFFB4C6D4),
    onSecondary = Color(0xFF1F303D),
    secondaryContainer = Color(0xFF364654),
    onSecondaryContainer = Color(0xFFD0E2F1),
    tertiary = Color(0xFFA7CDB8),
    onTertiary = Color(0xFF0D3526),
    tertiaryContainer = Color(0xFF254C3C),
    onTertiaryContainer = Color(0xFFC2E9D3),
    background = Color(0xFF101417),
    onBackground = Color(0xFFE2E5E8),
    surface = Color(0xFF161B1F),
    onSurface = Color(0xFFE2E5E8),
    surfaceVariant = Color(0xFF263139),
    onSurfaceVariant = Color(0xFFB9C3CB),
    outline = Color(0xFF6E7A84),
    outlineVariant = Color(0xFF3A454E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceDim = Color(0xFF101417),
    surfaceBright = Color(0xFF363C41),
    surfaceContainerLowest = Color(0xFF0B0E11),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2125),
    surfaceContainerHigh = Color(0xFF262B30),
    surfaceContainerHighest = Color(0xFF31363B),
)
```

3c. 在 `HarnessShapes` 之后追加：

```kotlin
internal val TechShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)
```

3d. 重写 `HarnessApkTheme`（不再跟随系统深浅；默认 LIFE 保持现有调用方兼容），并新增 `ModeTheme`：

```kotlin
@Composable
fun HarnessApkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = warmLightColorScheme(),
        typography = HarnessTypography,
        shapes = HarnessShapes,
        content = content,
    )
}

@Composable
fun ModeTheme(mode: MainMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = when (mode) {
            MainMode.LIFE -> warmLightColorScheme()
            MainMode.WORK -> techDarkColorScheme()
        },
        typography = HarnessTypography,
        shapes = when (mode) {
            MainMode.LIFE -> HarnessShapes
            MainMode.WORK -> TechShapes
        },
        content = content,
    )
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.theme.ThemeTest"`
Expected: PASS（原 4 个 + 新增 2 个）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/theme/Theme.kt app/src/test/java/com/harnessapk/ui/theme/ThemeTest.kt
git commit -m "主题：新增工作深科技主题并支持模式化"
```

---

### Task 4: 首页 Pager 双面板、主题切换与模式持久化接线

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt`
- Test: `app/src/androidTest/java/com/harnessapk/ui/DualModeHomePagerTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/androidTest/java/com/harnessapk/ui/DualModeHomePagerTest.kt`：

```kotlin
package com.harnessapk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.ui.theme.HarnessApkTheme
import com.harnessapk.ui.theme.ModeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DualModeHomePagerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeShowsBothModeTabsAndSettlesToLifePanel() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("生活").assertExists()
        composeRule.onNodeWithText("工作").assertExists()
        composeRule.onNodeWithText("还没有会话").assertExists()
    }

    @Test
    fun clickingWorkTabSwitchesToWorkPanel() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("工作").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("项目").assertExists()
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
    fun lifeModeAppliesWarmLightBackground() {
        var background by mutableStateOf(Color.Unspecified)
        composeRule.setContent {
            ModeTheme(MainMode.LIFE) {
                background = MaterialTheme.colorScheme.background
            }
        }
        composeRule.onRoot().assertExists()
        assertEquals(Color(0xFFFAF7F6), background)
    }
}
```

注意：`onNodeWithText("项目")` 断言工作面板（ProjectScreen 空态标题）。若 ProjectScreen 空态文案不同（如"还没有项目"），执行时按实际空态文案调整断言；`"还没有会话"` 同理（ConversationListScreen 空态，见 `EmptyConversationState`，如不一致改为 `EmptyConversationState` 的实际 title）。

- [ ] **Step 2: 更新 HarnessApkAppStateTest 源码断言**

`app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt` 中替换 `homeModeSwitcherUsesSharedSegmentedControlInsteadOfDropdown` 整个方法为：

```kotlin
    @Test
    fun homeModeSwitcherUsesPagerWithSharedSegmentedControl() {
        val source = File("src/main/java/com/harnessapk/ui/HarnessApkApp.kt").readText()

        assertTrue(source.contains("HorizontalPager("))
        assertTrue(source.contains("rememberPagerState("))
        assertTrue(source.contains("WarmSegmentedControl("))
        assertTrue(source.contains("MainMode.entries.map { it.label }"))
    }
```

- [ ] **Step 3: 运行 JVM 测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.HarnessApkAppStateTest"`
Expected: FAIL——源码断言不满足（尚无 HorizontalPager）。

- [ ] **Step 4: 实现 Pager 双面板首页**

`app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt` 修改：

4a. 新增 imports：
```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import com.harnessapk.ui.theme.ModeTheme
```

4b. 状态接线（第 155 行附近，替换 `var mainMode by rememberSaveable ...`）：
```kotlin
    val homeModeStore = container.homeModeStore
    var mainMode by rememberSaveable { mutableStateOf(homeModeStore.mode.value) }
    val pagerState = rememberPagerState(initialPage = MainMode.entries.indexOf(mainMode)) { MainMode.entries.size }
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != MainMode.entries.indexOf(mainMode)) {
            mainMode = MainMode.entries[pagerState.settledPage]
        }
    }
    LaunchedEffect(mainMode) {
        homeModeStore.save(mainMode)
        val targetPage = MainMode.entries.indexOf(mainMode)
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }
```

注意：`container` 在 HarnessApkApp 内第 170 行定义，`mainMode` 声明须移到 `container` 定义之后。若原第 155 行位于 `container` 之前，将其移动到 `val container = ...`（第 170 行）之后再接线。

4c. `ModeSwitcher` 改为按索引回调（替换 Task 1 版本）：
```kotlin
@Composable
private fun ModeSwitcher(
    mode: MainMode,
    onModeSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    WarmSegmentedControl(
        options = MainMode.entries.map { it.label },
        selectedIndex = MainMode.entries.indexOf(mode).coerceAtLeast(0),
        onSelected = onModeSelected,
        modifier = modifier,
    )
}
```

4d. `HomeTopBar` 签名改为 `onModeSelected: (Int) -> Unit`（参数与内部传递同步修改，其余不变）：
```kotlin
@Composable
private fun HomeTopBar(
    mode: MainMode,
    onModeSelected: (Int) -> Unit,
    primaryAction: HomePrimaryAction,
    showUpdateBadge: Boolean,
    onCreateConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ...
        ModeSwitcher(
            mode = mode,
            onModeSelected = onModeSelected,
            ...
        )
    ...
}
```

4e. `HomeTopBar` 调用处（约 300-308 行）：
```kotlin
                HomeTopBar(
                    mode = mainMode,
                    onModeSelected = { index ->
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    primaryAction = homePrimaryAction(mainMode),
                    showUpdateBadge = showUpdateBadge,
                    onCreateConversation = onCreateConversation,
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                )
```

4f. 用 `ModeTheme` 包裹整个 `Scaffold`（约 297 行 `Scaffold(` 改为 `ModeTheme(mainMode) { Scaffold(`，并在 Scaffold 大括号闭合处增加 `}`；注意 4g 同时让首页分支变为 Pager，整体为：
```kotlin
    ModeTheme(mainMode) {
        Scaffold(
            topBar = { ... },
        ) { padding ->
            NavHost(...) {
                composable(Routes.Conversations) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when (MainMode.entries[page]) {
                            MainMode.LIFE -> ConversationListScreen(...) // Task 1 版不变
                            MainMode.WORK -> ProjectScreen(...) // Task 1 版不变
                        }
                    }
                }
                // 其余 route 不变
            }
        }
    }
```
需要 `import androidx.compose.foundation.layout.fillMaxSize`。

- [ ] **Step 5: 运行 JVM 测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.harnessapk.ui.HarnessApkAppStateTest"`
Expected: PASS。

- [ ] **Step 6: 编译确认**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 真机/模拟器跑 androidTest**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.harnessapk.ui.DualModeHomePagerTest"`
Expected: PASS（4 个测试）。无设备时跳过并在提交信息中注明待补跑。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt app/src/androidTest/java/com/harnessapk/ui/DualModeHomePagerTest.kt
git commit -m "功能：首页双面板滑动切换与顶部 Tab"
```

---

### Task 5: 生活面板智能体与知识库入口

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Test: `app/src/androidTest/java/com/harnessapk/ui/LifePanelQuickEntryTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/androidTest/java/com/harnessapk/ui/LifePanelQuickEntryTest.kt`：

```kotlin
package com.harnessapk.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class LifePanelQuickEntryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lifePanelOffersAgentAndWikiQuickEntries() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("智能体").assertIsDisplayed()
        composeRule.onNodeWithText("知识库").assertIsDisplayed()
    }

    @Test
    fun agentQuickEntryOpensAgentPackages() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("智能体").performClick()
        composeRule.onNodeWithText("智能体包").assertIsDisplayed()
    }

    @Test
    fun wikiQuickEntryOpensWikiLibrary() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("知识库").performClick()
        composeRule.onNodeWithText("Wiki 知识库").assertIsDisplayed()
    }
}
```

注意：若页面存在同名文案（如顶部 Tab 也含"知识库"）导致 `onNodeWithText` 匹配多个节点，执行时改为 `onAllNodesWithText("知识库")[0]` 或调整文案。

- [ ] **Step 2: 实现入口行**

`app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt` 修改：

2a. 签名加两个默认参数（第 73-78 行）：
```kotlin
fun ConversationListScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenChat: (String) -> Unit,
    onCreateConversation: () -> Unit,
    onOpenAgentPackages: () -> Unit = {},
    onOpenWikiLibrary: () -> Unit = {},
)
```

2b. 新增 imports：
```kotlin
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AssistChip
```

2c. 在 `LazyColumn` 内、`if (conversations.isEmpty())` 之前插入入口 item（即列表最顶部）：
```kotlin
        item {
            QuickEntryRow(
                onOpenAgentPackages = onOpenAgentPackages,
                onOpenWikiLibrary = onOpenWikiLibrary,
            )
        }
```

2d. 文件末尾新增私有 Composable：
```kotlin
@Composable
private fun QuickEntryRow(
    onOpenAgentPackages: () -> Unit,
    onOpenWikiLibrary: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = onOpenAgentPackages,
            label = { Text("智能体") },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
        )
        AssistChip(
            onClick = onOpenWikiLibrary,
            label = { Text("知识库") },
            leadingIcon = { Icon(Icons.Outlined.MenuBook, contentDescription = null) },
        )
    }
}
```

- [ ] **Step 3: HarnessApkApp 传回调**

`HarnessApkApp.kt` 首页 `MainMode.LIFE -> ConversationListScreen(...)` 调用追加：
```kotlin
                        onOpenAgentPackages = { navController.navigate(Routes.AgentPackages) },
                        onOpenWikiLibrary = { navController.navigate(Routes.WikiLibrary) },
```

- [ ] **Step 4: 编译确认**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 真机/模拟器跑 androidTest**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.harnessapk.ui.LifePanelQuickEntryTest"`
Expected: PASS（3 个测试）。无设备时跳过并注明。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/androidTest/java/com/harnessapk/ui/LifePanelQuickEntryTest.kt
git commit -m "功能：生活面板增加智能体与知识库入口"
```

---

### Task 6: 工作面板远程控制入口

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt`
- Test: `app/src/androidTest/java/com/harnessapk/ui/WorkPanelRemoteEntryTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/androidTest/java/com/harnessapk/ui/WorkPanelRemoteEntryTest.kt`：

```kotlin
package com.harnessapk.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class WorkPanelRemoteEntryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun workPanelWithoutRemoteProfileHidesEntry() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("工作").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Codex 远程控制").assertDoesNotExist()
    }

    @Test
    fun remoteEntryOpensRemoteControlScreen() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("工作").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("远程控制").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("远程控制").assertIsDisplayed()
    }
}
```

注意：无 profile 时测试 1 断言入口不存在；测试 2 需先有已配对 profile 才能从工作面板看到入口——执行时若测试环境无法预置 profile，则将测试 2 改为点击入口（入口存在条件下）并注明需要真机预置 profile 后补跑；或按实际入口文案调整。

- [ ] **Step 2: ProjectScreen 支持外部 modifier**

`app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt` 修改：

2a. 签名（约 255 行）加参数：
```kotlin
internal fun ProjectScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onCurrentProjectChange: (WorkspaceProject?) -> Unit,
    workbenchTarget: ProjectWorkbenchTarget? = null,
    onWorkbenchTargetConsumed: (requestKey: Int) -> Unit = {},
    onCreateSession: (WorkspaceProject) -> Unit,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
)
```
（参数名与现有调用方一致，仅新增 `modifier` 末尾默认参数。）

2b. 顶层 `LazyColumn`（约 818 行）modifier 改为：
```kotlin
        modifier = modifier
            .fillMaxSize()
            .background(...)
```
（保留原有 `.fillMaxSize().background(...).padding(contentPadding)` 链，最前面接 `modifier`。）

- [ ] **Step 3: HarnessApkApp 接线**

`HarnessApkApp.kt` 修改：

3a. 新增 route：
```kotlin
    const val RemoteControl = "remote-control"
```

3b. 新增 imports：
```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.ElevatedCard
import androidx.compose.ui.Alignment
import com.harnessapk.ui.theme.HarnessSpacing
```
（按文件现有 import 情况去重。）

3c. 首页 `MainMode.WORK -> ProjectScreen(...)` 分支改为 Column 包裹（在 Task 1 版基础上）：
```kotlin
    MainMode.WORK -> Column(modifier = Modifier.fillMaxSize()) {
        if (remoteProfile != null) {
            RemoteEntryCard(
                hostName = remoteProfile.hostName,
                onClick = { navController.navigate(Routes.RemoteControl) },
                modifier = Modifier
                    .padding(horizontal = HarnessSpacing.pageHorizontal, vertical = 8.dp),
            )
        }
        ProjectScreen(
            container = container,
            contentPadding = padding,
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
```

3d. NavHost 新增 route（放在 `Routes.RemoteSettings` composable 旁）：
```kotlin
            composable(Routes.RemoteControl) {
                RemoteScreen(container = container, contentPadding = padding)
            }
```
（`RemoteScreen` 的 import 在 Task 1 已保留？Task 1 删除了 `MainMode.REMOTE -> RemoteScreen(...)` 分支但 import 保留即可——若编译器报 unused import，需在 3b 中补回 `import com.harnessapk.ui.remote.RemoteScreen`。）

3e. 文件末尾新增私有 Composable：
```kotlin
@Composable
private fun RemoteEntryCard(
    hostName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Dns, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text("Codex 远程控制", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = hostName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "进入远程控制",
            )
        }
    }
}
```
（需要 `import androidx.compose.foundation.layout.weight`？`weight` 是 Column/Row 作用域扩展，无需额外 import；`Modifier.weight` 来自 layout 作用域。）

- [ ] **Step 4: 编译确认**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 真机/模拟器跑 androidTest**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.harnessapk.ui.WorkPanelRemoteEntryTest"`
Expected: 测试 1 PASS；测试 2 需预置 profile（真机配对后补跑）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt app/src/androidTest/java/com/harnessapk/ui/WorkPanelRemoteEntryTest.kt
git commit -m "功能：工作面板增加远程控制入口"
```

---

### Task 7: 回归适配与全量验证

**Files:**
- Verify: `app/src/test` 全量 JVM 测试
- Verify: `app/src/androidTest` 导航测试（`HarnessApkAppNavigationTest`）
- Modify（如需）: 任何因导航结构变化失败的测试

- [ ] **Step 1: 全量 JVM 测试**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全部 PASS。若有失败，按失败信息修复（重点：`HarnessApkAppStateTest` 源码断言、`HomeModeUiStateTest`、`ThemeTest`、`HomeModeStoreTest`）。

- [ ] **Step 2: 编译 + Android 测试**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

有设备/模拟器时：
Run: `.\gradlew.bat :app:connectedDebugAndroidTest`
Expected: 全部 PASS（含 `HarnessApkAppNavigationTest` 既有用例与新增 `DualModeHomePagerTest`、`LifePanelQuickEntryTest`、`WorkPanelRemoteEntryTest`）。

- [ ] **Step 3: 检查无遗留引用**

Run: `Select-String -Path "app/src/main/java/**/*.kt" -Pattern "MainMode\.(SESSION|PROJECT|REMOTE)" | Select-Object -First 10`
Expected: 无输出（旧枚举值全部清除）。

- [ ] **Step 4: 提交（如有修复）**

```bash
git add -A
git commit -m "验证：双模式首页回归适配与全量测试"
```

---

## Self-Review

**Spec 覆盖检查：**
- 结构层（MainMode LIFE/WORK + Pager 双面板 + 顶部 Tab）→ Task 1、Task 4
- 能力层横切（智能体/Wiki/模型/会话保持可用）→ Task 5（入口）+ 无能力层改动
- 主题层（暖浅/深科技 + 落定切换）→ Task 3、Task 4（settledPage 驱动）
- 面板内容（生活=会话+快捷入口，工作=项目+远程入口）→ Task 5、Task 6
- 模式持久化（启动恢复 + REMOTE 迁移）→ Task 2
- 测试与回归 → Task 1/2/3/4/5/6 各自单测/Compose 测试 + Task 7 全量

**已知偏离（相对设计文档）：**
- 主题跟随系统深浅被移除（`HarnessApkTheme` 固定暖浅、工作模式经 `ModeTheme` 用深科技）——与设计"固定两套不跟随系统"一致，符合设计。
- 系统栏图标深浅仍由 values/values-night 静态控制（跟随系统），本次不改，属已知限制。

**类型一致性：**
- `MainMode.LIFE/WORK` 贯穿全部任务；`migrateStoredMode`（Task 2）→ `HomeModeStore.mode`（Task 2）→ `container.homeModeStore`（Task 4）；`ModeTheme(mode)`（Task 3）→ Task 4 使用；`onModeSelected: (Int) -> Unit`（Task 4）在 ModeSwitcher/HomeTopBar 一致。
- `ProjectScreen` 新增 `modifier` 默认参数，既有调用点（Task 1 的 WORK 分支）在 Task 6 前仍编译通过。
