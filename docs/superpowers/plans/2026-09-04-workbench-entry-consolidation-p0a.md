# 工作台入口收敛 P0-A：Git Tab 折叠 + 顶栏远程/副屏合并 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**背景（2026-09-04）：** 设计见 [2026-09-03-deliverable-layer-entry-consolidation-design.md](../specs/2026-09-03-deliverable-layer-entry-consolidation-design.md)（P0-A 包，含 2026-09-04 勘察修正：分支管理留在文件夹页 Git 区展开态，不进全局 `GitSettingsScreen`——它是无项目上下文的全局凭据页）。现状：工作台第三 Tab 常驻 `ProjectGitPanel`（7 个动作 + 5 个对话框，`ProjectScreen.kt:1230`）；工作模式顶栏双入口「远程」「副屏」（`HarnessApkApp.kt:460-470`）。

**Goal:** 工作台 Tab 从 3 个收敛为 2 个（会话/文件夹），Git 能力整体迁入文件夹页顶部状态条（默认收起、展开承载原面板全部动作）；顶栏远程相关按钮从 2 个收敛为 1 个；远程 Hub（`RemoteScreen`）线程列表新增「副屏」入口。

**范围（5 项）：**

1. `ProjectWorkbenchTab` 去掉 GIT 的可见性（枚举值保留，见"实现要点 1"）；`ProjectWorkbenchDestination.GIT` 语义改为"文件夹页 + Git 区展开"。
2. 文件夹页顶部新增 Git 状态条：非仓库 → 内联「初始化 Git」「克隆仓库」；干净 → 分支·工作区干净；有变更 → 分支·N 项变更，点按展开/收起 `ProjectGitPanel`（原面板复用，签名不动）。
3. Git 刷新语义从"选中 GIT Tab 刷新"改为"选中 FOLDER 刷新 + 选项目即刷新"；内容失效刷新（`projectContentInvalidation`）不动。
4. 顶栏移除「副屏」按钮与 `dashboardLaunch`（唯一使用点）。
5. `RemoteThreadListHeader` 增加「副屏」入口，启动 `DashboardActivity`。

**实现要点：**

1. **`ProjectWorkbenchTab.GIT` 枚举值必须保留**：`selectedTab` 走 `rememberSaveable`，进程恢复时 Bundle 里的 `"GIT"` 若无对应枚举值会抛异常。处理方式：值保留 + `visibleWorkbenchTabs()` 过滤只返回 `CONVERSATIONS, FOLDER`；`when (selectedTab)` 分支里 `GIT` 与 `FOLDER` 落到同一文件夹内容（防御陈旧恢复态）。
2. `projectWorkbenchTab(ProjectWorkbenchDestination.GIT)` 改返回 `FOLDER`；展开状态用 `var gitSectionExpanded by rememberSaveable`，target GIT 与克隆成功两条路径置 `true`。
3. 结果卡「查看 Git 变更」链路（`ChatScreen.kt:2682` → `HarnessApkApp.kt:709` `openWorkbench(projectId, GIT)`）**上游不动**，只改 `ProjectScreen` 的 target 消费落点。
4. `ProjectGitPanel` 及 5 个对话框（Commit/PendingCommit/PushPrompt/Branch/Clone）的承载与回调全部不动，只挪位置。
5. 旧测试语义改写而非删除：`workbenchTargetsMapToFolderAndGitTabs`、`gitRefreshIsOnlyTriggeredBySelectingGitTab`、`projectSelectionRefreshesGitOnlyWhileGitTabIsActive` 等（`ProjectSessionLaunchUiStateTest.kt:222/355/386`）。

**Non-goals：** 锚点服务与交付物详情（P0-B/P1）；`GitSettingsScreen` 任何改动；生活模式；`RemoteSettingsScreen` 的「副屏模式（常亮）」入口（保留）；Git 安全纪律（白名单提交、非快进禁推、Apply 不自动 commit）。

---

## Task 1: 状态模型与纯函数

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt`

- [ ] **Step 1: 写失败测试。** ①`projectWorkbenchTab(ProjectWorkbenchDestination.GIT) == ProjectWorkbenchTab.FOLDER`（改写 222 行 `workbenchTargetsMapToFolderAndGitTabs` 为 `gitTargetMapsToFolderTab`）；②新增 `visibleWorkbenchTabs()` 断言：恰为 `[CONVERSATIONS, FOLDER]`、不含 GIT；③FOLDER guidance 文案更新断言（见 Step 2 文案）；④刷新语义新断言：`shouldRefreshGitOnTabSelection(FOLDER)` 为真、`CONVERSATIONS` 为假；`shouldRefreshGitForProjectSelection` 签名改为 `(projectId: String?)`，任意 Tab 下项目选择均刷新。

- [ ] **Step 2: 实现。**

```kotlin
// ProjectUiState.kt
internal enum class ProjectWorkbenchTab(val label: String) {
    CONVERSATIONS("会话"),
    FOLDER("文件夹"),
    @Deprecated("已折叠进文件夹页 Git 区，仅为 rememberSaveable 陈旧恢复态保留")
    GIT("Git"),
}

internal fun visibleWorkbenchTabs(): List<ProjectWorkbenchTab> =
    listOf(ProjectWorkbenchTab.CONVERSATIONS, ProjectWorkbenchTab.FOLDER)

internal fun projectWorkbenchTab(destination: ProjectWorkbenchDestination): ProjectWorkbenchTab =
    when (destination) {
        ProjectWorkbenchDestination.CONVERSATIONS -> ProjectWorkbenchTab.CONVERSATIONS
        ProjectWorkbenchDestination.FILES, ProjectWorkbenchDestination.GIT -> ProjectWorkbenchTab.FOLDER
    }

internal fun projectWorkbenchTabGuidance(tab: ProjectWorkbenchTab): String = when (tab) {
    ProjectWorkbenchTab.CONVERSATIONS -> "在当前项目内开始或继续工作"
    ProjectWorkbenchTab.FOLDER -> "查看会话沉淀、已写入文件和 Git 变更"
    ProjectWorkbenchTab.GIT -> "查看会话沉淀、已写入文件和 Git 变更"
}

// ProjectScreen.kt（原 216-220 行两个函数迁改）
internal fun shouldRefreshGitOnTabSelection(tab: ProjectWorkbenchTab): Boolean =
    tab == ProjectWorkbenchTab.FOLDER

internal fun shouldRefreshGitForProjectSelection(projectId: String?): Boolean = projectId != null
```

- [ ] **Step 3:** `:app:testDebugUnitTest --tests "*ProjectSessionLaunchUiStateTest*"` 通过。

## Task 2: 文件夹页 Git 状态条与展开区

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt`（新增纯函数）
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt`
- Test: `app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt`
- Test: `app/src/androidTest/java/com/harnessapk/ui/project/ProjectGitBarTest.kt`（新建，仿 `ProjectWorkbenchHeaderTest`）

- [ ] **Step 1: 写失败测试。** 纯函数 `projectGitBarState`：`null → NotAvailable`；`isClean → Clean`；否则 `Changed(files.size)`。androidTest：非仓库态显示"当前项目还不是 Git 仓库"与两个按钮；`Clean` 态显示"工作区干净"、无变更数。

- [ ] **Step 2: 实现。**

```kotlin
// ProjectUiState.kt
internal sealed interface ProjectGitBarState {
    data object NotAvailable : ProjectGitBarState   // status == null（未读取或非仓库）
    data object Clean : ProjectGitBarState
    data class Changed(val count: Int) : ProjectGitBarState
}

internal fun projectGitBarState(status: GitStatusSummary?): ProjectGitBarState = when {
    status == null -> ProjectGitBarState.NotAvailable
    status.isClean -> ProjectGitBarState.Clean
    else -> ProjectGitBarState.Changed(status.files.size)
}
```

`ProjectScreen` 文件夹分支（现 `when (selectedTab)` 的 `FOLDER` 路径，`ProjectScreen.kt:1112`）顶部插入状态条：`NotAvailable` → 一行说明 + 「初始化 Git」「克隆仓库」（复用现有回调，同现面板 73-83 行）；`Clean` → 「分支x · 工作区干净」；`Changed(n)` → 「分支x · n 项变更」；后两态整条可点，切换 `gitSectionExpanded`。展开态在条下方原样渲染 `ProjectGitPanel`（全部现有参数与回调不动）。同步移除 `when (selectedTab)` 的 GIT 独立分支（`ProjectScreen.kt:1230-1253`，含面板挂载）与 `ProjectWorkbenchTabs` 的 `ProjectWorkbenchTab.entries` 遍历（改 `visibleWorkbenchTabs()`，`ProjectScreen.kt:1263`）。

- [ ] **Step 3:** JVM 纯函数测试通过；`ProjectGitBarTest` 在设备/模拟器上通过（无设备则记录 PENDING，随 Task 6 真机回归补齐）。

## Task 3: target/克隆/刷新语义重定向

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt`

- [ ] **Step 1: 写失败测试。** ①改写 `filesRefreshCannotPublishAfterNewerGitTargetForSameProject`（229 行）中的断言措辞与预期：GIT target 现映射 FOLDER Tab；②`gitRefreshIsOnlyTriggeredBySelectingGitTab`（355 行）改写为 `gitRefreshIsTriggeredBySelectingFolderTab`；③`projectSelectionRefreshesGitOnlyWhileGitTabIsActive`（386 行）改写为"项目选择即刷新（任意 Tab）"。

- [ ] **Step 2: 实现。** ①`ProjectScreen` 增加 `var gitSectionExpanded by rememberSaveable { mutableStateOf(false) }`；②target 消费（`ProjectScreen.kt:780-804`）：`selectedTab = targetTab`（GIT 已映射 FOLDER）后追加 `if (target.destination == ProjectWorkbenchDestination.GIT) gitSectionExpanded = true`；`refreshAlreadySelectedGit` 分支改写为"destination ∈ {FILES, GIT} 且已选中该项目与 FOLDER"时按需 `refreshGitState(project)`；③`cloneRepositoryAsProject` 成功回调（597 行）：`selectedTab = ProjectWorkbenchTab.FOLDER; gitSectionExpanded = true`（替换 `selectedTab = ProjectWorkbenchTab.GIT`）；④`LaunchedEffect(selectedProject, selectedTab)`（839-845 行）改写：

```kotlin
LaunchedEffect(selectedProject, selectedTab) {
    if (shouldRefreshGitForProjectSelection(selectedProject?.id)) {
        refreshGitState(selectedProject)
    } else if (shouldRefreshGitOnTabSelection(selectedTab)) {
        refreshGitState(selectedProject)
    }
}
```

（`selectedProject == null` 时 `refreshGitState(null)` 自身会清理并短路，语义不变。）

- [ ] **Step 3:** 该测试类全量通过；确认 `HarnessApkAppStateTest` 无需改动（上游 `openWorkbench(projectId, GIT)` 构造不变）。

## Task 4: 顶栏收敛

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`

- [ ] **Step 1:** 删除顶栏「副屏」`TextButton`（`HarnessApkApp.kt:467-470`）与 `dashboardLaunch` lambda（220-222 行，唯一使用点即顶栏）；「远程」按钮与通知铃不动。
- [ ] **Step 2:** `grep -rn "dashboardLaunch" app/src` 确认零残留；`:app:compileDebugKotlin` 通过。

## Task 5: 远程 Hub 副屏入口

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/remote/RemoteScreen.kt`

- [ ] **Step 1:** `RemoteThreadListHeader`（`RemoteScreen.kt:183`）增加 `onOpenDashboard: () -> Unit` 参数，头部动作区新增「副屏」`TextButton`（与 `onRefresh`/`onCreate` 同排，`48dp` 触达）；`RemoteThreadList` 调用处用 `LocalContext.current` 启动 `DashboardActivity`。`RemoteScreen` 的 `profile == null` 早退分支不加入口；线程详情态（`RemoteThreadDetail`）不加（返回列表即可达）。
- [ ] **Step 2:** `:app:compileDebugKotlin` 通过；`assembleDebug` 装机待真机验收。

## Task 6: 全量验证与真机回归

- [ ] **Step 1:** `:app:testDebugUnitTest` 全量通过；`:app:assembleDebug` 成功。
- [ ] **Step 2: 真机手工回归**（`adb -L tcp:5092 -s 20200611222647` 安装）：
  - 工作台只有「会话」「文件夹」两个 Tab；文件夹页顶部出现 Git 状态条。
  - 有未提交变更（先在会话里让 Agent 写一个文件）→ 状态条显示"N 项变更"，点开可见原面板全部动作：待提交/提交全部/推送/Fetch/快进拉取/新建分支/分支切换。
  - 提交一个文件、推送一次（若配置了远端）；构造非快进场景确认拒绝文案仍从状态条区域呈现。
  - 会话文件变更结果卡点「查看 Git 变更」→ 落在文件夹页且 Git 区已展开。
  - 顶栏 WORK 模式只剩「远程」+ 通知铃；远程 Hub 列表页点「副屏」→ DashboardActivity 打开；系统返回回列表。
  - 冷启恢复（杀进程后重进工作台）不因陈旧 `selectedTab=GIT` 崩溃。

**验收：**

- [ ] 工作台 Tab = 2；顶栏远程相关按钮 = 1。
- [ ] Git 全部既有能力在文件夹页状态条展开态可达，安全纪律文案不变。
- [ ] `:app:testDebugUnitTest` 全量通过 + `:app:assembleDebug` 成功。

**实施记录：** （完成后填写）
