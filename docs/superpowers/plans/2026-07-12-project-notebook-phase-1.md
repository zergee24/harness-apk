# 项目会话 Markdown 记事本 Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让后台流式执行在服务回收后自动恢复，并为项目会话 Markdown 记事本建立可持久化的关联与审核草稿基础。

**Architecture:** 前台服务重投递后，协调器只恢复没有本进程 runner 的运行中任务，保留旧助手部分回复并从原用户消息重新执行。Room 10 新增会话 Markdown 弱关联、草稿和草稿项；项目文件系统仍是已应用 Markdown 的唯一事实来源。

**Tech Stack:** Kotlin、Room、Jetpack Compose、kotlinx-coroutines、Android foreground service、JUnit。

## Global Constraints

- 不重命名项目目录、不向项目 Markdown 注入 Harness 元数据。
- 不保存项目现有 Markdown 副本；仅保存未应用模型提案和其 SHA-256 基线。
- 同一会话只能有一个 runner，不同会话可并行；用户主动停止仍必须停止当前执行。
- 对 Android 前台服务只承诺普通后台切换与系统回收后的自动重启；不绕过用户强制停止应用。
- 每个行为先写失败测试，再写最小实现；提交信息使用中文。

---

### Task 1: 后台服务重投递与未完成会话恢复

**Files:**
- Modify: `app/src/main/java/com/harnessapk/chat/ChatExecutionModels.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/ChatExecutionRepository.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/ChatExecutionCoordinator.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/ChatExecutionService.kt`
- Modify: `app/src/main/java/com/harnessapk/HarnessApkApplication.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/ChatExecutionEntryDao.kt`
- Modify: `app/src/test/java/com/harnessapk/chat/ChatExecutionModelsTest.kt`

**Interfaces:**
- Produces `recoverUntrackedRunning(conversationId): Boolean` 的协调器内部恢复路径。
- Produces `hasOpenWork(): Boolean` 与包含 `QUEUED`、`RUNNING` 的待恢复会话查询。

- [ ] 写测试：断点恢复把 `RUNNING` 变为 `QUEUED`；服务仅在 `activeCount == 0 && !hasOpenWork` 时停止。
- [ ] 运行 `./gradlew testDebugUnitTest --tests com.harnessapk.chat.ChatExecutionModelsTest`，确认缺少恢复语义时失败。
- [ ] 实现仓储重排、协调器仅恢复无 runner 的运行中任务、服务 `START_REDELIVER_INTENT` 与空闲判断。
- [ ] 重跑目标单测并提交 `修复：后台恢复未完成会话执行`。

### Task 2: Notebook Room 存储与弱关联仓储

**Files:**
- Create: `app/src/main/java/com/harnessapk/storage/ConversationMarkdownLinkEntity.kt`
- Create: `app/src/main/java/com/harnessapk/storage/ConversationMarkdownLinkDao.kt`
- Create: `app/src/main/java/com/harnessapk/storage/MarkdownChangeDraftEntity.kt`
- Create: `app/src/main/java/com/harnessapk/storage/MarkdownChangeDraftDao.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/AppDatabase.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Create: `app/src/main/java/com/harnessapk/session/MarkdownNotebookRepository.kt`
- Create: `app/src/test/java/com/harnessapk/session/MarkdownNotebookRepositoryTest.kt`

**Interfaces:**
- Produces `MarkdownNotebookRepository` 的关联新增、去重、移除、有效关联过滤和草稿保存 API。
- 草稿项持有 `baselineSha256: String?` 与 `expectedAbsent: Boolean`，以支持后续写前冲突检测。

- [ ] 写失败测试：同项目关联去重、跨项目拒绝、丢失文件不作为有效上下文，以及草稿保存后可重读。
- [ ] 运行 `./gradlew testDebugUnitTest --tests com.harnessapk.session.MarkdownNotebookRepositoryTest`，确认缺少仓储实现时失败。
- [ ] 新增 Room 10 migration、实体、DAO 和仓储；数据库外键只指向会话，不对文件路径建立外键。
- [ ] 重跑目标单测和 `assembleDebugAndroidTest`，提交 `功能：持久化项目会话 Markdown 草稿`。

### Task 3: 写前基线校验与部分成功结果

**Files:**
- Modify: `app/src/main/java/com/harnessapk/session/MarkdownUpdateModels.kt`
- Modify: `app/src/main/java/com/harnessapk/session/ProjectWorkspaceGateway.kt`
- Modify: `app/src/main/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapter.kt`
- Modify: `app/src/main/java/com/harnessapk/project/FileProjectRepository.kt`
- Modify: `app/src/test/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapterTest.kt`

**Interfaces:**
- Extends `MarkdownUpdateProposal` with planning baseline data.
- Returns each file's stale-baseline failure without blocking unrelated proposals.

- [ ] 写失败测试：已修改 update 文件和已存在 create 目标均被拒绝；同批无冲突文件继续写入。
- [ ] 运行 `./gradlew testDebugUnitTest --tests com.harnessapk.project.ProjectWorkspaceGatewayAdapterTest`，确认当前实现会覆盖冲突文件。
- [ ] 实现 UTF-8 SHA-256 基线比较和逐文件冲突结果。
- [ ] 重跑目标单测，提交 `修复：校验 Markdown 写入基线`。

### Task 4: Phase 1 集成验证

**Files:**
- Modify: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`
- Modify: `app/src/test/java/com/harnessapk/chat/ChatExecutionModelsTest.kt`

- [ ] 补 Room 9 到 10 迁移与后台恢复模型回归测试。
- [ ] 运行 `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`。
- [ ] 在连接设备上验证切后台仍显示前台通知、恢复后同一用户消息自动继续；无设备时记录该验证缺口。
- [ ] 合并前只提交与本期相关文件。
