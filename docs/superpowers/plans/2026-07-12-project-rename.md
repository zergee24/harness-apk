# 项目名称修改 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在项目工作台中安全修改当前项目的显示名称。

**Architecture:** 项目目录名继续作为稳定项目 ID。仓储层将名称写入 `.harness/name` 本地元数据并更新时间戳；项目页在既有“更多”菜单中调用该能力，成功后刷新项目列表和顶部当前项目名。

**Tech Stack:** Kotlin、Jetpack Compose、kotlinx-coroutines、JUnit、Android Gradle Plugin。

## Global Constraints

- 仅修改 `.harness/name`，不得重命名项目目录、改写 README 或修改 Git 历史。
- 保存时去除首尾空格，空名称必须抛出“项目名称不能为空”。
- 不纳入与项目重命名无关的工作树变更；提交信息使用中文。

---

### Task 1: 持久化项目显示名称

**Files:**
- Modify: `app/src/main/java/com/harnessapk/project/FileProjectRepository.kt:19-35`
- Modify: `app/src/test/java/com/harnessapk/project/ProjectRepositoryTest.kt`

**Interfaces:**
- Consumes: `FileProjectRepository.projectDirectory(projectId): File` 和 `writeLocalProjectName(project, projectName): Unit`。
- Produces: `suspend fun renameProject(projectId: String, name: String): Project`，返回写入后的项目。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun renameProjectKeepsStableDirectoryAndPersistsDisplayName() = runTest {
    val created = repository.createProject("旧名称")

    val renamed = repository.renameProject(created.id, "  新名称  ")

    assertEquals(created.id, renamed.id)
    assertEquals(created.rootDirectory, renamed.rootDirectory)
    assertEquals("新名称", renamed.name)
    assertEquals("新名称", repository.listProjects().single().name)
}

@Test
fun renameProjectRejectsBlankName() = runTest {
    val project = repository.createProject("原项目")

    val error = assertFailsWith<IllegalArgumentException> {
        repository.renameProject(project.id, "   ")
    }

    assertEquals("项目名称不能为空", error.message)
}
```

- [ ] **Step 2: 验证测试失败**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.project.ProjectRepositoryTest`

Expected: 编译失败，提示 `renameProject` 未定义。

- [ ] **Step 3: 最小实现**

```kotlin
suspend fun renameProject(projectId: String, name: String): Project {
    val trimmedName = name.trim()
    require(trimmedName.isNotBlank()) { "项目名称不能为空" }
    val project = projectDirectory(projectId)
    writeLocalProjectName(project, trimmedName)
    project.setLastModified(timeProvider.nowMillis())
    return projectFromDirectory(project)
}
```

- [ ] **Step 4: 验证测试通过**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.project.ProjectRepositoryTest`

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/project/FileProjectRepository.kt app/src/test/java/com/harnessapk/project/ProjectRepositoryTest.kt
git commit -m '功能：支持修改项目名称'
```

### Task 2: 工作台重命名入口和即时刷新

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt:254-1210`
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt:61-95`
- Modify: `app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt`

**Interfaces:**
- Consumes: `FileProjectRepository.renameProject(projectId: String, name: String): Project`。
- Produces: 已选项目“更多”菜单中的“重命名项目”操作，以及预填名称的 `RenameProjectDialog`。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun projectHeaderOffersRenameOnlyWhenProjectIsSelected() {
    val layout = projectHeaderActionLayout(hasProject = true)

    assertEquals(
        listOf(
            ProjectHeaderAction.RENAME,
            ProjectHeaderAction.CLONE,
            ProjectHeaderAction.IMPORT,
            ProjectHeaderAction.EXPORT,
            ProjectHeaderAction.SHARE,
            ProjectHeaderAction.DELETE,
        ),
        layout.overflowActions,
    )
}
```

- [ ] **Step 2: 验证当前回归覆盖**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.project.ProjectSessionLaunchUiStateTest`

Expected: 编译失败，提示 `ProjectHeaderAction.RENAME` 未定义。

- [ ] **Step 3: 实现重命名交互**

```kotlin
fun renameProject(project: Project, name: String) {
    scope.launch {
        runCatching {
            withContext(container.dispatchers.io) {
                container.projectRepository.renameProject(project.id, name)
            }
        }.onSuccess { renamed ->
            showRenameProjectDialog = false
            projects = projects.map { if (it.id == renamed.id) renamed else it }
            statusText = "已重命名项目：${renamed.name}"
            onCurrentProjectNameChange(renamed.name)
        }.onFailure { statusText = it.toUserMessage() }
    }
}
```

在 `ProjectHeaderAction` 和 `projectHeaderActionLayout(true)` 中加入 `RENAME`，并在 `ProjectHeader` 的已选项目“更多”菜单中加入“重命名项目”。`RenameProjectDialog` 使用预填项目名称、单行输入框、保存与取消按钮，且保存按钮仅在非空输入时可用。

- [ ] **Step 4: 验证 UI 与构建**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt
git commit -m '界面：增加项目重命名入口'
```
