# 项目工作台顶部交互 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让项目工作台先明确当前项目、下一步操作和会话、文件、Git 状态，同时保留现有标签页结构。

**Architecture:** `ProjectUiState.kt` 提供纯展示摘要和标签引导；`ProjectScreen.kt` 将既有项目、会话、交付物和 Git 状态映射到顶部，不新增后台刷新、Repository 或数据库结构。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、JUnit 4、Compose instrumentation test。

## Global Constraints

- 保留“会话 / 文件夹 / Git”的标签顺序、选择状态和聊天页跳转目标。
- 不新增项目内任务实体、任务状态或第三层导航。
- 不改变项目、会话、交付物、Git 的 Repository 和数据库结构。
- 已选项目时，“新建项目会话”是唯一主操作；低频操作保留在溢出菜单。
- Git 摘要不得触发额外后台刷新；未加载时显示“Git 状态未读取”。
- 所有触控入口至少 `48dp`，当前标签同时使用文字强调与指示器。
- 使用中文提交信息，只暂存当前任务文件，不自动推送。

---

## File Structure

- `app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt`：顶部摘要和标签引导纯模型。
- `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt`：顶部项目区、项目切换、主操作和标签引导接线。
- `app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt`：纯展示状态回归。
- `app/src/androidTest/java/com/harnessapk/ui/project/ProjectWorkbenchHeaderTest.kt`：顶部关键语义与操作测试。

### Task 1: 顶部摘要与标签引导展示状态

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt`

**Interfaces:**
- Produces: `ProjectWorkbenchOverview`、`projectWorkbenchOverview`、`projectWorkbenchTabGuidance`。

- [ ] **Step 1: 写入失败测试**

```kotlin
@Test
fun workbenchOverviewUsesLoadedGitStateWithoutRequestingRefresh() {
    val overview = projectWorkbenchOverview(2, 3, gitStatus("test", false, 1))
    assertEquals("2 个会话", overview.conversationLabel)
    assertEquals("3 个文件", overview.deliverableLabel)
    assertEquals("test · 1 项变更", overview.gitLabel)
}

@Test
fun workbenchTabGuidanceExplainsEachExistingTab() {
    assertEquals("在当前项目内开始或继续工作", projectWorkbenchTabGuidance(ProjectWorkbenchTab.CONVERSATIONS))
    assertEquals("查看会话沉淀和已写入文件", projectWorkbenchTabGuidance(ProjectWorkbenchTab.FOLDER))
    assertEquals("查看当前分支和工作区变更", projectWorkbenchTabGuidance(ProjectWorkbenchTab.GIT))
}
```

- [ ] **Step 2: 运行 RED 测试**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.project.ProjectSessionLaunchUiStateTest
```

Expected: 缺少展示模型与函数。

- [ ] **Step 3: 实现展示模型**

```kotlin
internal data class ProjectWorkbenchOverview(
    val conversationLabel: String,
    val deliverableLabel: String,
    val gitLabel: String,
)

internal fun projectWorkbenchOverview(
    conversationCount: Int,
    deliverableCount: Int,
    gitStatus: GitStatusSummary?,
): ProjectWorkbenchOverview = ProjectWorkbenchOverview(
    conversationLabel = "$conversationCount 个会话",
    deliverableLabel = "$deliverableCount 个文件",
    gitLabel = when {
        gitStatus == null -> "Git 状态未读取"
        gitStatus.isClean -> "${gitStatus.currentBranch} · 工作区干净"
        else -> "${gitStatus.currentBranch} · ${gitStatus.files.size} 项变更"
    },
)

internal fun projectWorkbenchTabGuidance(tab: ProjectWorkbenchTab): String = when (tab) {
    ProjectWorkbenchTab.CONVERSATIONS -> "在当前项目内开始或继续工作"
    ProjectWorkbenchTab.FOLDER -> "查看会话沉淀和已写入文件"
    ProjectWorkbenchTab.GIT -> "查看当前分支和工作区变更"
}
```

- [ ] **Step 4: 运行 GREEN 测试**

Run the Step 2 command. Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt
git commit -m "功能：补充项目工作台顶部摘要"
```

### Task 2: 接入顶部项目区和标签页引导

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt:700-790,960-1140`
- Create: `app/src/androidTest/java/com/harnessapk/ui/project/ProjectWorkbenchHeaderTest.kt`

**Interfaces:**
- Consumes: `ProjectWorkbenchOverview`、`projectWorkbenchTabGuidance`。
- Produces: `internal ProjectWorkbenchHeader` 供设备测试验证。

- [ ] **Step 1: 写入 Compose RED 测试**

```kotlin
composeRule.setContent {
    HarnessApkTheme {
        ProjectWorkbenchHeader(
            projectName = "家庭健康记录",
            overview = ProjectWorkbenchOverview("2 个会话", "3 个文件", "Git 状态未读取"),
            onSelectProject = {},
            onCreateSession = {},
            overflowContent = {},
        )
    }
}
composeRule.onNodeWithContentDescription("切换项目").assertIsDisplayed()
composeRule.onNodeWithText("新建项目会话").assertIsDisplayed().assertHasClickAction()
composeRule.onNodeWithText("2 个会话").assertIsDisplayed()
```

- [ ] **Step 2: 运行 RED 测试**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew assembleDebugAndroidTest
```

Expected: 缺少 `ProjectWorkbenchHeader`。

- [ ] **Step 3: 接线顶部区与标签引导**

在 `ProjectScreen` 计算：

```kotlin
val workbenchOverview = projectWorkbenchOverview(
    conversationCount = selectedProjectConversations.size,
    deliverableCount = deliverables.size,
    gitStatus = gitStatus,
)
```

实现 `ProjectWorkbenchHeader`：项目名称行是带 `KeyboardArrowDown` 的“切换项目”入口；已选项目时只显示全宽主按钮“新建项目会话”；会话、文件、Git 摘要为不可点击文本；溢出菜单继续承载克隆、导入、导出、分享、删除。无项目状态保留“新建项目”与可用溢出动作。

在标签页下方仅当当前内容为空时显示 `projectWorkbenchTabGuidance(selectedTab)`，保持现有 tab 选择、目标消费和 Git 刷新逻辑不变。

- [ ] **Step 4: 运行 GREEN 测试和构建**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.project.ProjectSessionLaunchUiStateTest
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew assembleDebug assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt app/src/androidTest/java/com/harnessapk/ui/project/ProjectWorkbenchHeaderTest.kt
git commit -m "优化：重构项目工作台顶部交互"
```

### Task 3: 设备验收

- [ ] **Step 1: 检查设备并运行测试**

```bash
/Users/tony/Library/Android/sdk/platform-tools/adb devices -l
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.project.ProjectWorkbenchHeaderTest
```

Expected: 有设备时验证当前项目、切换入口、主操作和摘要；无设备时记录限制，不声称已完成设备验收。

- [ ] **Step 2: 人工核对**

核对浅色、深色和放大字体下项目名称、切换入口、新建项目会话、三项摘要及标签文案不裁切；切换项目后摘要不展示旧项目状态。

## Self-Review

- 规格中的顶部身份、主操作、状态摘要和标签引导均由 Task 1 或 Task 2 覆盖。
- 计划没有新任务实体、Repository、数据库结构或后台刷新。
- 每项实现都有失败测试、通过测试、构建命令和中文提交命令。
