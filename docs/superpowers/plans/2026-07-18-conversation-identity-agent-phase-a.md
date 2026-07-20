# 会话身份与人格回答急救 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把智能体收回统一会话体系，在首页和项目会话首发前自动预选、允许切换并原子固定身份，同时让现有 V1 人格回答不再呈现为资料审校员。

**Architecture:** 新增一个只负责会话身份生命周期的 `ConversationIdentityRepository`，以“是否已有用户消息”区分草稿选择和固定身份，并在现有聊天队列事务内完成首发锁定。UI 复用现有聊天输入区与设置导航；回答急救通过独立提示词契约和输出净化接入既有 `SendMessageUseCase`，不创建第二套聊天、项目或 Markdown 模型。

**Tech Stack:** Kotlin、Room 2.8.4、Jetpack Compose、Navigation Compose、Kotlin Coroutines、JUnit 4、Android instrumentation。

## Global Constraints

- `Agent` 是全局安装的人物身份资产，不归某个项目所有。
- `Conversation` 是发生交互、固定身份版本和驱动 Markdown 的实体。
- `Project` 是可选工作上下文，不是智能体容器。
- `Conversation.projectId` 与 `Conversation.agentId` 相互独立，不新增 `Project <-> Agent` 表。
- 第一条用户消息写入时必须在同一 Room 事务中固定 `agentId + agentVersion`。
- 身份只在首条用户消息前可切换，之后不得改变历史会话身份版本。
- 首页顶层模式只能是“会话 | 项目”；智能体包管理位于“设置 -> 智能体包”。
- 人格会话继续使用现有模型配置、聊天队列、项目上下文和 Markdown 弱关联。
- UI 始终保留“基于资料模拟”，回复正文不重复模拟声明。
- 正文不得出现 `[资料N]`、chunk ID、内部文件位置或惯例资料免责声明。
- 阶段 A 不修改 Room schema 版本；现有 V1 包和会话数据必须原样可读。

---

## File Structure

### 会话身份领域

- Create: `app/src/main/java/com/harnessapk/agent/ConversationIdentity.kt`：身份种子、选择结果和草稿/固定状态。
- Create: `app/src/main/java/com/harnessapk/agent/ConversationIdentityRepository.kt`：最近身份推导、草稿切换和首发原子锁定。
- Create: `app/src/main/java/com/harnessapk/chat/NewConversationUseCase.kt`：首页、项目和安装完成共用的新建会话入口。
- Modify: `app/src/main/java/com/harnessapk/storage/ConversationDao.kt`：查询全局或项目内最近人格会话。
- Modify: `app/src/main/java/com/harnessapk/storage/MessageDao.kt`：统计用户消息，作为身份锁定边界。
- Modify: `app/src/main/java/com/harnessapk/storage/AgentDao.kt`：读取可用于新会话的 READY 身份。
- Modify: `app/src/main/java/com/harnessapk/chat/ChatExecutionRepository.kt`：在首条用户消息插入前锁定身份。
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`：装配身份仓库和新建会话用例。

### 信息架构与 UI

- Modify: `app/src/main/java/com/harnessapk/ui/HomeUiState.kt`：删除 `MainMode.AGENT` 和导入主操作。
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`：增加“智能体包”设置路由，统一新建会话入口。
- Rename: `app/src/main/java/com/harnessapk/ui/agent/AgentScreen.kt` -> `app/src/main/java/com/harnessapk/ui/agent/AgentPackagesScreen.kt`：只保留安装、覆盖、版本和开始对话。
- Create: `app/src/main/java/com/harnessapk/ui/chat/ConversationIdentityUiState.kt`：身份选择器的纯状态和锁定规则。
- Create: `app/src/main/java/com/harnessapk/ui/chat/ConversationIdentityPicker.kt`：紧凑身份 chip、选择弹窗和身份详情。
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`：首发前身份选择、首发后一次性披露。
- Modify: `app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt`：统一列表显示人格名称与模拟标识。
- Modify: `app/src/main/java/com/harnessapk/ui/settings/SettingsDestinations.kt`：增加“智能体包”。
- Modify: `app/src/main/java/com/harnessapk/ui/settings/SettingsScreen.kt`：接入智能体包设置项。

### 回答契约

- Create: `app/src/main/java/com/harnessapk/agent/AgentPromptContract.kt`：V1/V2 共用的事实边界和自然回答契约。
- Modify: `app/src/main/java/com/harnessapk/agent/AgentRepository.kt`：调用提示词契约，不再内联资料审校提示。
- Modify: `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt`：持久化前移除内部资料标签，参考资料仍单独保存。
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`：删除仅用于展示层补救的资料编号依赖。

### 项目生命周期

- Create: `app/src/main/java/com/harnessapk/project/DeleteProjectUseCase.kt`：删除项目文件后解除数据库中的项目引用。
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt`：只通过删除用例删除项目。
- Modify: `app/src/main/java/com/harnessapk/storage/ConversationMarkdownLinkDao.kt`：按项目清理弱关联。
- Modify: `app/src/main/java/com/harnessapk/storage/MarkdownChangeDraftEntity.kt`：按项目清理未应用草稿。

### 测试

- Create: `app/src/test/java/com/harnessapk/agent/ConversationIdentityRepositoryTest.kt`。
- Create: `app/src/test/java/com/harnessapk/chat/NewConversationUseCaseTest.kt`。
- Create: `app/src/test/java/com/harnessapk/agent/AgentPromptContractTest.kt`。
- Create: `app/src/androidTest/java/com/harnessapk/chat/ChatExecutionRepositoryInstrumentedTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/chat/SendMessageUseCaseSupportTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt`。
- Create: `app/src/test/java/com/harnessapk/ui/chat/ConversationIdentityUiStateTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/ui/conversation/ConversationListUiStateTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/ui/settings/SettingsDestinationsTest.kt`。

---

### Task 1: 会话身份建议、草稿切换与首发锁定

**Files:**
- Create: `app/src/main/java/com/harnessapk/agent/ConversationIdentity.kt`
- Create: `app/src/main/java/com/harnessapk/agent/ConversationIdentityRepository.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/ConversationDao.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/MessageDao.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/AgentDao.kt`
- Test: `app/src/test/java/com/harnessapk/agent/ConversationIdentityRepositoryTest.kt`

**Interfaces:**
- Produces: `sealed interface InitialConversationIdentity`，包含 `Suggested`、`Assistant`、`Agent(agentId: String)`。
- Produces: `data class ConversationIdentitySelection(val agentId: String?, val agentVersion: Int?, val name: String?, val locked: Boolean)`。
- Produces: `ConversationIdentityRepository.suggest(projectId: String?): ConversationIdentitySelection`。
- Produces: `ConversationIdentityRepository.selectDraft(conversationId: String, agentId: String?): ConversationIdentitySelection`。
- Produces: `ConversationIdentityRepository.pinForFirstMessage(conversationId: String): ConversationIdentitySelection`。
- Consumes: `ConversationEntity.agentId/agentVersion` 和 `AgentEntity.activeVersion/status`。

- [ ] **Step 1: 写身份规则失败测试**

```kotlin
@Test
fun projectSuggestionUsesMostRecentReadyIdentityWithoutCreatingAssociation() = runTest {
    conversationDao.rows += conversation("old", projectId = "p1", agentId = "a1", agentVersion = 1, updatedAt = 10)
    agentDao.rows["a1"] = readyAgent("a1", activeVersion = 3)

    val result = repository.suggest("p1")

    assertEquals(ConversationIdentitySelection("a1", 3, "李德胜", locked = false), result)
}

@Test
fun selectDraftRejectsChangesAfterFirstUserMessage() = runTest {
    messageDao.userMessageCount = 1

    assertThrows(IllegalStateException::class.java) {
        repository.selectDraft("c1", "a1")
    }
}

@Test
fun firstMessageRefreshesToLatestReadyVersionAndLocks() = runTest {
    conversationDao.rows += conversation("c1", agentId = "a1", agentVersion = 1)
    agentDao.rows["a1"] = readyAgent("a1", activeVersion = 4)

    val result = repository.pinForFirstMessage("c1")

    assertEquals(4, conversationDao.findById("c1")!!.agentVersion)
    assertTrue(result.locked)
}

@Test
fun disabledSuggestedIdentityFallsBackToAssistant() = runTest {
    conversationDao.rows += conversation("recent", agentId = "a1", agentVersion = 1)
    agentDao.rows["a1"] = readyAgent("a1", activeVersion = 2).copy(status = AgentStatus.DRAFT.name)

    assertEquals(null, repository.suggest(projectId = null).agentId)
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.ConversationIdentityRepositoryTest`

Expected: FAIL，原因是 `ConversationIdentityRepository` 和新增 DAO 方法尚不存在。

- [ ] **Step 3: 增加精确 DAO 契约**

```kotlin
// ConversationDao.kt
@Query("""
    SELECT * FROM conversations
    WHERE isArchived = 0 AND agentId IS NOT NULL
    ORDER BY updatedAt DESC LIMIT 1
""")
suspend fun findLatestWithAgent(): ConversationEntity?

@Query("""
    SELECT * FROM conversations
    WHERE isArchived = 0 AND projectId = :projectId AND agentId IS NOT NULL
    ORDER BY updatedAt DESC LIMIT 1
""")
suspend fun findLatestWithAgentInProject(projectId: String): ConversationEntity?

// MessageDao.kt
@Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND role = 'USER'")
suspend fun countUserMessages(conversationId: String): Int

// AgentDao.kt
@Query("SELECT * FROM agents WHERE status = 'READY' ORDER BY updatedAt DESC")
suspend fun listReadyAgents(): List<AgentEntity>
```

- [ ] **Step 4: 实现领域类型和仓库**

```kotlin
sealed interface InitialConversationIdentity {
    data object Suggested : InitialConversationIdentity
    data object Assistant : InitialConversationIdentity
    data class Agent(val agentId: String) : InitialConversationIdentity
}

data class ConversationIdentitySelection(
    val agentId: String?,
    val agentVersion: Int?,
    val name: String?,
    val locked: Boolean,
)

class ConversationIdentityRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val agentDao: AgentDao,
    private val timeProvider: TimeProvider,
) {
    suspend fun suggest(projectId: String?): ConversationIdentitySelection {
        val recent = if (projectId.isNullOrBlank()) {
            conversationDao.findLatestWithAgent()
        } else {
            conversationDao.findLatestWithAgentInProject(projectId)
        }
        return selectionFor(recent?.agentId, locked = false)
    }

    suspend fun selectDraft(conversationId: String, agentId: String?): ConversationIdentitySelection {
        check(messageDao.countUserMessages(conversationId) == 0) { "首条消息发送后不能切换身份" }
        val conversation = requireNotNull(conversationDao.findById(conversationId)) { "会话不存在" }
        val selected = selectionFor(agentId, locked = false)
        conversationDao.update(
            conversation.copy(
                agentId = selected.agentId,
                agentVersion = selected.agentVersion,
                updatedAt = timeProvider.nowMillis(),
            ),
        )
        return selected
    }

    suspend fun pinForFirstMessage(conversationId: String): ConversationIdentitySelection {
        val conversation = requireNotNull(conversationDao.findById(conversationId)) { "会话不存在" }
        if (messageDao.countUserMessages(conversationId) > 0) {
            return selectionForPinned(conversation)
        }
        val selected = selectionFor(conversation.agentId, locked = true)
        conversationDao.update(
            conversation.copy(
                agentId = selected.agentId,
                agentVersion = selected.agentVersion,
                updatedAt = timeProvider.nowMillis(),
            ),
        )
        return selected
    }

    private suspend fun selectionFor(agentId: String?, locked: Boolean): ConversationIdentitySelection {
        val agent = agentId?.let(agentDao::findAgent)?.takeIf { it.status == AgentStatus.READY.name }
        return ConversationIdentitySelection(
            agentId = agent?.id,
            agentVersion = agent?.activeVersion,
            name = agent?.name,
            locked = locked,
        )
    }

    private suspend fun selectionForPinned(conversation: ConversationEntity): ConversationIdentitySelection {
        val agent = conversation.agentId?.let(agentDao::findAgent)
        return ConversationIdentitySelection(
            agentId = conversation.agentId,
            agentVersion = conversation.agentVersion,
            name = agent?.name,
            locked = true,
        )
    }
}
```

- [ ] **Step 5: 运行测试确认 GREEN**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.ConversationIdentityRepositoryTest`

Expected: PASS，覆盖项目最近身份、全局最近身份、普通助手、停用回退、首发刷新版本和首发后拒绝切换。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agent/ConversationIdentity.kt app/src/main/java/com/harnessapk/agent/ConversationIdentityRepository.kt app/src/main/java/com/harnessapk/storage/ConversationDao.kt app/src/main/java/com/harnessapk/storage/MessageDao.kt app/src/main/java/com/harnessapk/storage/AgentDao.kt app/src/test/java/com/harnessapk/agent/ConversationIdentityRepositoryTest.kt
git commit -m "功能：建立会话身份锁定规则"
```

### Task 2: 统一新建会话入口并把锁定纳入队列事务

**Files:**
- Create: `app/src/main/java/com/harnessapk/chat/NewConversationUseCase.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/ChatExecutionRepository.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Test: `app/src/test/java/com/harnessapk/chat/NewConversationUseCaseTest.kt`
- Test: `app/src/androidTest/java/com/harnessapk/chat/ChatExecutionRepositoryInstrumentedTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `InitialConversationIdentity`、`ConversationIdentityRepository.suggest/selectDraft/pinForFirstMessage`。
- Produces: `NewConversationUseCase.create(title: String = "新会话", projectId: String? = null, identity: InitialConversationIdentity = Suggested): String`。
- Changes: `ChatExecutionRepository` 构造参数增加 `identityRepository: ConversationIdentityRepository`。

- [ ] **Step 1: 写新建与原子锁定失败测试**

```kotlin
@Test
fun suggestedProjectConversationPersistsIndependentProjectAndIdentity() = runTest {
    identityRepository.suggested = ConversationIdentitySelection("a1", 2, "李德胜", false)

    val id = useCase.create(projectId = "p1")

    val conversation = chatRepository.conversation(id)!!
    assertEquals("p1", conversation.projectId)
    assertEquals("a1", conversation.agentId)
    assertEquals(2, conversation.agentVersion)
}

@Test
fun enqueuePinsLatestIdentityAndInsertsFirstUserMessage() = runBlocking {
    insertReadyAgent(id = "a1", activeVersion = 4)
    insertConversation(id = "c1", agentId = "a1", agentVersion = 1)

    repository.enqueue(request("c1"))

    assertEquals(4, database.conversationDao().findById("c1")!!.agentVersion)
    assertEquals(1, database.messageDao().listForConversation("c1").count { it.role == "USER" })
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.chat.NewConversationUseCaseTest`

Run: `./gradlew connectedDebugAndroidTest --tests com.harnessapk.chat.ChatExecutionRepositoryInstrumentedTest`

Expected: FAIL，原因是新建用例不存在且队列未调用身份锁定。

- [ ] **Step 3: 实现共用新建用例**

```kotlin
class NewConversationUseCase(
    private val chatRepository: ChatRepository,
    private val identityRepository: ConversationIdentityRepository,
) {
    suspend fun create(
        title: String = "新会话",
        projectId: String? = null,
        identity: InitialConversationIdentity = InitialConversationIdentity.Suggested,
    ): String {
        val selected = when (identity) {
            InitialConversationIdentity.Suggested -> identityRepository.suggest(projectId)
            InitialConversationIdentity.Assistant -> ConversationIdentitySelection(null, null, null, false)
            is InitialConversationIdentity.Agent ->
                identityRepository.selectionForNewConversation(identity.agentId)
        }
        return chatRepository.createConversation(
            title = title,
            projectId = projectId,
            agentId = selected.agentId,
            agentVersion = selected.agentVersion,
        )
    }
}
```

同时把 Task 1 仓库中的私有解析抽为：

```kotlin
suspend fun selectionForNewConversation(agentId: String): ConversationIdentitySelection =
    selectionFor(agentId, locked = false)
```

- [ ] **Step 4: 在现有事务内先锁定再写用户消息**

```kotlin
suspend fun enqueue(request: EnqueueChatRequest): ChatExecutionEntry = database.withTransaction {
    identityRepository.pinForFirstMessage(request.conversationId)
    val now = timeProvider.nowMillis()
    val userMessageId = chatRepository.insertUserMessage(
        conversationId = request.conversationId,
        content = request.content,
        attachments = request.attachments,
    )
    // 保留现有 ChatExecutionEntryEntity 创建、insert 和 toDomain 代码。
}
```

`AppContainer` 必须只创建一个身份仓库实例，并传给 `NewConversationUseCase` 与 `ChatExecutionRepository`。

- [ ] **Step 5: 运行测试与四组合回归**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.chat.NewConversationUseCaseTest --tests com.harnessapk.chat.ChatRepositoryTest`

Run: `./gradlew connectedDebugAndroidTest --tests com.harnessapk.chat.ChatExecutionRepositoryInstrumentedTest`

Expected: PASS，四种 `projectId/agentId` 组合与非法半绑定组合全部通过。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/chat/NewConversationUseCase.kt app/src/main/java/com/harnessapk/chat/ChatExecutionRepository.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/test/java/com/harnessapk/chat/NewConversationUseCaseTest.kt app/src/androidTest/java/com/harnessapk/chat/ChatExecutionRepositoryInstrumentedTest.kt
git commit -m "功能：首发时原子固定会话身份"
```

### Task 3: 收回顶层智能体模式并迁移包管理入口

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HomeUiState.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Rename: `app/src/main/java/com/harnessapk/ui/agent/AgentScreen.kt` -> `app/src/main/java/com/harnessapk/ui/agent/AgentPackagesScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/settings/SettingsDestinations.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/settings/SettingsScreen.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/settings/SettingsDestinationsTest.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/agent/AgentScreenStateTest.kt`

**Interfaces:**
- Changes: `MainMode.entries == [SESSION, PROJECT]`。
- Adds: `Routes.AgentPackages = "agent-packages"`。
- Produces: `AgentPackagesScreen(..., onStartConversation: (Agent) -> Unit, onDone: () -> Unit)`。
- Consumes: Task 2 的 `NewConversationUseCase.create`。

- [ ] **Step 1: 改写导航失败测试**

```kotlin
@Test
fun homeOnlyContainsConversationAndProject() {
    assertEquals(listOf(MainMode.SESSION, MainMode.PROJECT), MainMode.entries.toList())
}

@Test
fun settingsContainsAgentPackagesAsLowFrequencyManagement() {
    val destination = settingsDestinations().single { it.id == "agents" }
    assertEquals("智能体包", destination.title)
}

@Test
fun externalBundleNavigatesDirectlyToPackageImport() {
    assertEquals(Routes.AgentPackages, incomingAgentBundleDestination(hasUri = true))
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.HomeModeUiStateTest --tests com.harnessapk.ui.HarnessApkAppStateTest --tests com.harnessapk.ui.settings.SettingsDestinationsTest`

Expected: FAIL，现有首页仍包含 `AGENT`，设置没有智能体包路由。

- [ ] **Step 3: 删除第三模式和导入主操作**

```kotlin
enum class MainMode(val label: String) {
    SESSION("会话"),
    PROJECT("项目"),
}

enum class HomePrimaryAction {
    CREATE_CONVERSATION,
    NONE,
}

internal fun homePrimaryAction(mode: MainMode): HomePrimaryAction = when (mode) {
    MainMode.SESSION -> HomePrimaryAction.CREATE_CONVERSATION
    MainMode.PROJECT -> HomePrimaryAction.NONE
}
```

`HomeTopBar` 删除 `onImportAgent`；首页 `when (mainMode)` 只保留会话和项目分支。首页和项目的创建回调全部调用 `container.newConversationUseCase.create(...)`。

- [ ] **Step 4: 把包管理接入设置并保留外部直达**

```kotlin
object Routes {
    const val AgentPackages = "agent-packages"
}

internal fun incomingAgentBundleDestination(hasUri: Boolean): String? =
    Routes.AgentPackages.takeIf { hasUri }
```

`HarnessApkApp` 同时保存当前项目 ID：

```kotlin
var currentProjectId by rememberSaveable { mutableStateOf<String?>(null) }
var agentImportSourceProjectId by rememberSaveable { mutableStateOf<String?>(null) }

// ProjectScreen 回调
onCurrentProjectChange = { project ->
    currentProjectId = project?.id
    currentProjectName = project?.name
}

// 收到外部 bundle 时
agentImportSourceProjectId = currentProjectId.takeIf { mainMode == MainMode.PROJECT }
navController.navigate(Routes.AgentPackages)
```

`AgentPackagesScreen` 接收 `sourceProjectId: String?`，它只影响安装成功后的“开始对话”，不写项目与智能体关联；从设置手动进入时固定传 `null`。

`settingsDestinations()` 增加：

```kotlin
SettingsDestination(
    id = "agents",
    title = "智能体包",
    description = "安装、更新并查看人物身份与资料覆盖。",
)
```

安装成功后保存 `installedAgent`，显示仅有“开始对话”和“完成”的成功弹窗；开始对话调用：

```kotlin
container.newConversationUseCase.create(
    title = agent.name,
    projectId = sourceProjectId,
    identity = InitialConversationIdentity.Agent(agent.id),
)
```

不得显示项目选择器，也不得创建项目关联记录。

- [ ] **Step 5: 运行导航与状态测试确认 GREEN**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.HomeModeUiStateTest --tests com.harnessapk.ui.HarnessApkAppStateTest --tests com.harnessapk.ui.settings.SettingsDestinationsTest --tests com.harnessapk.ui.agent.AgentScreenStateTest`

Expected: PASS；源码与测试中不再出现 `MainMode.AGENT` 或 `IMPORT_AGENT`。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/HomeUiState.kt app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/main/java/com/harnessapk/ui/agent app/src/main/java/com/harnessapk/ui/settings app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt app/src/test/java/com/harnessapk/ui/agent app/src/test/java/com/harnessapk/ui/settings
git commit -m "功能：将智能体收回统一会话入口"
```

### Task 4: 首发前身份选择和统一会话披露

**Files:**
- Create: `app/src/main/java/com/harnessapk/ui/chat/ConversationIdentityUiState.kt`
- Create: `app/src/main/java/com/harnessapk/ui/chat/ConversationIdentityPicker.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt`
- Create: `app/src/test/java/com/harnessapk/ui/chat/ConversationIdentityUiStateTest.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/conversation/ConversationListUiStateTest.kt`

**Interfaces:**
- Produces: `data class ConversationIdentityUiState(val selectedAgentId: String?, val selectedName: String, val mutable: Boolean, val options: List<ConversationIdentityOption>)`。
- Produces: `conversationIdentityUiState(conversation, messages, agents): ConversationIdentityUiState`。
- Produces: `ConversationIdentityPicker(state, onSelectAgentId, onShowDetails)`。
- Consumes: Task 1 的 `ConversationIdentityRepository.selectDraft`。

- [ ] **Step 1: 写 UI 状态失败测试**

```kotlin
@Test
fun identityIsMutableOnlyBeforeFirstUserMessage() {
    val draft = conversationIdentityUiState(conversation(agentId = "a1"), emptyList(), listOf(agent("a1")))
    val sent = conversationIdentityUiState(
        conversation(agentId = "a1"),
        listOf(userMessage("m1")),
        listOf(agent("a1")),
    )

    assertTrue(draft.mutable)
    assertFalse(sent.mutable)
}

@Test
fun assistantIsAlwaysTheFirstOption() {
    val state = conversationIdentityUiState(conversation(), emptyList(), listOf(agent("a1")))
    assertEquals(null, state.options.first().agentId)
    assertEquals("普通助手", state.options.first().name)
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ConversationIdentityUiStateTest --tests com.harnessapk.ui.conversation.ConversationListUiStateTest`

Expected: FAIL，身份 UI 状态尚不存在。

- [ ] **Step 3: 实现纯状态**

```kotlin
data class ConversationIdentityOption(
    val agentId: String?,
    val name: String,
    val version: Int?,
)

data class ConversationIdentityUiState(
    val selectedAgentId: String?,
    val selectedName: String,
    val mutable: Boolean,
    val options: List<ConversationIdentityOption>,
)

internal fun conversationIdentityUiState(
    conversation: Conversation?,
    messages: List<ChatMessage>,
    agents: List<Agent>,
): ConversationIdentityUiState {
    val options = listOf(ConversationIdentityOption(null, "普通助手", null)) +
        agents.filter { it.status == AgentStatus.READY }.map {
            ConversationIdentityOption(it.id, it.name, it.activeVersion)
        }
    val selected = options.firstOrNull { it.agentId == conversation?.agentId } ?: options.first()
    return ConversationIdentityUiState(
        selectedAgentId = selected.agentId,
        selectedName = selected.name,
        mutable = messages.none { it.role == MessageRole.USER },
        options = options,
    )
}
```

- [ ] **Step 4: 接入紧凑 chip、详情和会话列表**

身份 chip 与现有模型 chip 位于同一输入配置行：

```kotlin
ConversationIdentityPicker(
    state = identityState,
    onSelectAgentId = { agentId ->
        scope.launch {
            container.conversationIdentityRepository.selectDraft(conversationId, agentId)
            conversation = container.chatRepository.conversation(conversationId)
        }
    },
    onShowDetails = { showIdentityDetails = true },
)
```

首发后不再提供选择菜单，只在聊天顶部显示可点击的：

```text
李德胜 · 基于资料模拟
```

详情只显示固定版本、资料覆盖和发布者指纹。会话列表的 metadata 使用：

```kotlin
internal fun conversationIdentityLabel(conversation: Conversation, agents: Map<String, Agent>): String? =
    conversation.agentId?.let { id ->
        "${agents[id]?.name ?: "已安装人物"} · 基于资料模拟"
    }
```

- [ ] **Step 5: 运行测试与 Compose 编译**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ConversationIdentityUiStateTest --tests com.harnessapk.ui.conversation.ConversationListUiStateTest`

Run: `./gradlew assembleDebug`

Expected: PASS；首发前可切换，首发后没有身份配置操作，普通会话不显示模拟标识。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/chat/ConversationIdentityUiState.kt app/src/main/java/com/harnessapk/ui/chat/ConversationIdentityPicker.kt app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt app/src/test/java/com/harnessapk/ui/chat/ConversationIdentityUiStateTest.kt app/src/test/java/com/harnessapk/ui/conversation/ConversationListUiStateTest.kt
git commit -m "功能：在新会话中选择人物身份"
```

### Task 5: V1 人格回答契约与内部标签净化

**Files:**
- Create: `app/src/main/java/com/harnessapk/agent/AgentPromptContract.kt`
- Modify: `app/src/main/java/com/harnessapk/agent/AgentRepository.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt`
- Test: `app/src/test/java/com/harnessapk/agent/AgentPromptContractTest.kt`
- Modify: `app/src/test/java/com/harnessapk/agent/AgentRetrievalTest.kt`
- Modify: `app/src/test/java/com/harnessapk/chat/SendMessageUseCaseSupportTest.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/chat/AgentMessagePresentationTest.kt`

**Interfaces:**
- Produces: `buildAgentSystemPrompt(version: AgentVersionEntity, evidence: List<AgentEvidence>): String`。
- Produces: `sanitizeAgentCitationMarkers(snapshot: StreamingMessageSnapshot): StreamingMessageSnapshot`。
- Consumes: 现有 `SessionRequestContext`、压缩会话消息和独立 `AGENT_SOURCES` part。

- [ ] **Step 1: 写李德胜回归问题的失败测试**

```kotlin
@Test
fun methodAdviceContractAnswersBeforeConditionsWithoutDatabaseDisclaimers() {
    val prompt = buildAgentSystemPrompt(version(), evidence())

    assertTrue(prompt.contains("先直接回答用户"))
    assertTrue(prompt.contains("默认使用自然段"))
    assertTrue(prompt.contains("只追问一个最关键的现实条件"))
    assertFalse(prompt.contains("当前资料不足"))
    assertFalse(prompt.contains("[资料 1]"))
}

@Test
fun relationshipConversationDoesNotRequireOriginalEvidence() {
    val prompt = buildAgentSystemPrompt(version(), emptyList())

    assertTrue(prompt.contains("问候、承接前文和关系互动不要求原文证据"))
    assertFalse(prompt.contains("无法据此判断"))
}

@Test
fun sanitizerRemovesInternalMarkersBeforePersistence() {
    val sanitized = sanitizeAgentCitationMarkers(snapshot("先调查。[资料 1]再决定。[资料2]"))

    assertEquals("先调查。再决定。", sanitized.legacyVisibleText())
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentPromptContractTest --tests com.harnessapk.agent.AgentRetrievalTest --tests com.harnessapk.chat.SendMessageUseCaseSupportTest`

Expected: FAIL；当前提示词包含资料编号和强制“当前资料不足”。

- [ ] **Step 3: 实现单一提示词契约**

```kotlin
internal fun buildAgentSystemPrompt(
    version: AgentVersionEntity,
    evidence: List<AgentEvidence>,
): String = buildString {
    appendLine("你以包内人物身份使用第一人称与用户交谈；这是基于资料的模拟，不得冒充真实人物。")
    appendLine("先直接回答用户，再说明结论成立的条件。默认使用自然段，内容确有并列关系时才使用列表。")
    appendLine("历史事实、人物经历和核心立场必须由人物资料支持；不得用通用知识补写。")
    appendLine("问候、承接前文和关系互动不要求原文证据，可自然回应。")
    appendLine("今日事实只来自本轮、当前会话和已经提供的项目上下文。条件不足时先给方法判断，再只追问一个最关键的现实条件。")
    appendLine("不要提资料库、chunk、资料编号、当前资料不足或内部文件位置。用户直接询问真实性时才明确说明是基于资料的模拟。")
    appendLine("不要机械套用“以下三点”“我的回答不是……而是……”等通用 AI 结构。")
    appendLine()
    appendLine("身份内核：")
    appendLine(version.persona.trim())
    version.worldviewJsonl.trim().takeIf(String::isNotBlank)?.let {
        appendLine()
        appendLine("人物立场：")
        appendLine(it)
    }
    if (evidence.isNotEmpty()) {
        appendLine()
        appendLine("可用于本轮事实与立场判断的原始证据：")
        evidence.forEach { item ->
            appendLine(item.text.trim())
            appendLine()
        }
    }
}
```

证据不再携带编号、标题、位置；来源 UI 继续从 `AgentRuntimeContext.evidence` 生成。

- [ ] **Step 4: 在成功持久化前净化模型泄漏标签**

```kotlin
internal fun sanitizeAgentCitationMarkers(
    snapshot: StreamingMessageSnapshot,
): StreamingMessageSnapshot = snapshot.copy(
    parts = snapshot.parts.map { part ->
        if (part.type == UiMessagePartType.TEXT) {
            part.copy(content = part.content.replace(Regex("""\s*\[资料\s*\d+\]"""), ""))
        } else {
            part
        }
    },
)
```

`SendMessageUseCase` 仅在 `agentContext != null` 时对最终 snapshot 调用净化，再追加 `AGENT_SOURCES` part。普通联网搜索编号不受影响。

- [ ] **Step 5: 运行回答契约与普通聊天回归**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentPromptContractTest --tests com.harnessapk.agent.AgentRetrievalTest --tests com.harnessapk.chat.SendMessageUseCaseSupportTest --tests com.harnessapk.session.SessionContextBuilderTest --tests com.harnessapk.ui.chat.AgentMessagePresentationTest`

Expected: PASS；V1 人格获得会话现实与可选项目上下文，普通会话和联网来源编号不回归。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agent/AgentPromptContract.kt app/src/main/java/com/harnessapk/agent/AgentRepository.kt app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt app/src/test/java/com/harnessapk/agent/AgentPromptContractTest.kt app/src/test/java/com/harnessapk/agent/AgentRetrievalTest.kt app/src/test/java/com/harnessapk/chat/SendMessageUseCaseSupportTest.kt app/src/test/java/com/harnessapk/ui/chat/AgentMessagePresentationTest.kt
git commit -m "修复：让人格回答先回应用户"
```

### Task 6: 删除项目时解除会话和 Markdown 弱关联

**Files:**
- Create: `app/src/main/java/com/harnessapk/project/DeleteProjectUseCase.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/ConversationDao.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/ConversationMarkdownLinkDao.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/MarkdownChangeDraftEntity.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt`
- Create: `app/src/androidTest/java/com/harnessapk/project/DeleteProjectUseCaseInstrumentedTest.kt`

**Interfaces:**
- Produces: `DeleteProjectUseCase.delete(projectId: String)`。
- Preserves: 会话 ID、消息、`agentId` 和 `agentVersion`。
- Changes: 被删项目的 `Conversation.projectId` 置空，并删除该项目的 Markdown link 和未应用 draft。

- [ ] **Step 1: 写项目删除失败测试**

```kotlin
@Test
fun deletingProjectDetachesConversationWithoutChangingAgentOrMessages() = runBlocking {
    insertProjectDirectory("p1")
    insertConversation(id = "c1", projectId = "p1", agentId = "a1", agentVersion = 2)
    insertUserMessage(id = "m1", conversationId = "c1")
    insertMarkdownLink(conversationId = "c1", projectId = "p1", path = "notes.md")

    useCase.delete("p1")

    val conversation = database.conversationDao().findById("c1")!!
    assertEquals(null, conversation.projectId)
    assertEquals("a1", conversation.agentId)
    assertEquals(2, conversation.agentVersion)
    assertEquals(1, database.messageDao().listForConversation("c1").size)
    assertTrue(database.conversationMarkdownLinkDao().listForConversation("c1").isEmpty())
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew connectedDebugAndroidTest --tests com.harnessapk.project.DeleteProjectUseCaseInstrumentedTest`

Expected: FAIL，当前 `ProjectScreen` 只删除文件目录，数据库引用仍保留。

- [ ] **Step 3: 增加精确清理查询**

```kotlin
// ConversationDao.kt
@Query("UPDATE conversations SET projectId = NULL, updatedAt = :updatedAt WHERE projectId = :projectId")
suspend fun clearProject(projectId: String, updatedAt: Long)

// ConversationMarkdownLinkDao.kt
@Query("DELETE FROM conversation_markdown_links WHERE projectId = :projectId")
suspend fun deleteForProject(projectId: String)

// MarkdownChangeDraftDao
@Query("DELETE FROM markdown_change_drafts WHERE projectId = :projectId")
suspend fun deleteForProject(projectId: String)
```

- [ ] **Step 4: 实现单一删除用例并接入 UI**

```kotlin
class DeleteProjectUseCase(
    private val projectRepository: FileProjectRepository,
    private val database: AppDatabase,
    private val timeProvider: TimeProvider,
) {
    suspend fun delete(projectId: String) {
        projectRepository.deleteProject(projectId)
        database.withTransaction {
            database.conversationDao().clearProject(projectId, timeProvider.nowMillis())
            database.conversationMarkdownLinkDao().deleteForProject(projectId)
            database.markdownChangeDraftDao().deleteForProject(projectId)
        }
    }
}
```

`ProjectScreen.deleteProject` 改为调用 `container.deleteProjectUseCase.delete(project.id)`，不再直接调用文件 repository。

- [ ] **Step 5: 运行删除测试确认 GREEN**

Run: `./gradlew connectedDebugAndroidTest --tests com.harnessapk.project.DeleteProjectUseCaseInstrumentedTest`

Expected: PASS；普通和人格项目会话都保留，项目上下文与 Markdown 弱关联停止注入。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/project/DeleteProjectUseCase.kt app/src/main/java/com/harnessapk/storage/ConversationDao.kt app/src/main/java/com/harnessapk/storage/ConversationMarkdownLinkDao.kt app/src/main/java/com/harnessapk/storage/MarkdownChangeDraftEntity.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt app/src/androidTest/java/com/harnessapk/project/DeleteProjectUseCaseInstrumentedTest.kt
git commit -m "修复：删除项目后解除会话引用"
```

### Task 7: 阶段 A 全量验证与真实 APK 冒烟

**Files:**
- Modify: `README.md`
- Test: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`

**Interfaces:**
- Consumes: Tasks 1-5 的统一会话入口、身份锁定和 V1 回答契约。
- Produces: 可安装的 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 1: 增加既有数据库兼容断言**

在 `AppDatabaseTest` 增加四种合法会话写入和读取测试；不得新增 migration：

```kotlin
val combinations = listOf(
    Triple<String?, String?, Int?>(null, null, null),
    Triple<String?, String?, Int?>("p1", null, null),
    Triple<String?, String?, Int?>(null, "a1", 1),
    Triple<String?, String?, Int?>("p1", "a1", 1),
)
combinations.forEachIndexed { index, (projectId, agentId, agentVersion) ->
    database.conversationDao().insert(
        conversationEntity(
            id = "combination-$index",
            projectId = projectId,
            agentId = agentId,
            agentVersion = agentVersion,
        ),
    )
}
assertEquals(4, database.conversationDao().observeActive().first().size)
```

- [ ] **Step 2: 运行完整 JVM 与 APK 构建**

Run: `./gradlew testDebugUnitTest assembleDebug`

Expected: PASS，APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 3: 运行设备测试**

Run: `./gradlew connectedDebugAndroidTest`

Expected: PASS；如果当前没有已连接设备，保留命令输出并使用已安装模拟器后重跑，不能把“无设备”记为通过。

- [ ] **Step 4: 执行交互冒烟**

在测试设备完成以下固定路径：

```text
首页新建 -> 自动预选最近身份 -> 切换普通助手 -> 首发 -> 身份不可再切换
项目新建 -> 继承项目最近身份 -> 首发 -> projectId 与 agentId 同时保留
设置 -> 智能体包 -> 导入 V1 hbundle -> 开始对话 -> 不创建项目
打开既有无项目人格会话 -> 消息、版本和无项目状态不变
提问“我们现在该怎么走” -> 正文不以资料免责声明开头且无 [资料N]
```

- [ ] **Step 5: 更新 README 的唯一入口说明**

README 明确：人物身份在新会话输入区选择；安装与更新位于“设置 -> 智能体包”；项目不是智能体容器。

- [ ] **Step 6: 检查差异并提交**

Run: `git diff --check`

Expected: 退出码 0。

```bash
git add README.md app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt
git commit -m "验证：收口会话身份阶段 A"
```

---

## Self-Review

- Spec coverage: 阶段 A 覆盖顶层模式移除、统一身份选择器、最近身份自动预选、首发原子固定、项目独立、安装后开始对话、V1 新契约、项目现实注入、项目删除清理、统一会话列表和内部标签净化。
- Deliberate boundary: V2 结构化人物资产、资料拆包、动态检索和关系记忆分别由阶段 B、C 计划实现；阶段 A 不伪造缺失资产。
- Type consistency: `ConversationIdentitySelection` 在身份仓库、新建用例和 UI 中保持同一字段；首发锁定只从 `ChatExecutionRepository.enqueue` 的现有 Room transaction 进入。
- Regression safety: Room 仍为 11；现有 V1 `agent_versions`、会话、消息、Markdown 弱关联和普通聊天链路均不迁移。
