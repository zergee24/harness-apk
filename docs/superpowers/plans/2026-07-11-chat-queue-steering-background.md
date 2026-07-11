# 会话排队、引导与后台流式执行 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 让每个会话在后台可靠执行流式回复，支持默认排队、队列项引导当前/编辑/删除，以及紧凑的思考过程展示。

**Architecture:** Room 保存发送任务和请求快照；ChatExecutionCoordinator 按会话维护一个活动任务、跨会话并行调度；ChatExecutionService 托管应用级协程并维持前台通知。聊天页只负责入队和观察持久化状态。通用 chat/completions 不能向 SSE 中途写入，引导当前通过停止当前流、保留部分输出、优先执行引导任务实现。

**Tech Stack:** Kotlin、Jetpack Compose、Room、Kotlin Coroutines、Android Foreground Service、OkHttp、JUnit 4、AndroidX Test。

## Global Constraints

- 同一会话最多一个 RUNNING 任务；不同会话允许并行运行。
- 用户输入默认入队；引导当前、编辑、删除只出现在未执行队列项菜单中。
- 有文字或图片时，输入框尾部始终是发送；输入为空时才显示终止按钮或添加图片按钮。
- 入队成功后才清空草稿；复制图片或入队失败时保留文字与图片。
- QUEUED 用户消息不得进入模型请求历史。
- 通知只显示“正在生成 N 个回复”，不提供操作按钮。
- 进程被杀死后，RUNNING 标记为 INTERRUPTED 且不自动重发；QUEUED 下次打开应用继续。
- 现有 DEFAULT_REASONING_LANGUAGE_INSTRUCTION 已默认要求中文思考，必须保留且不得覆盖用户明确指定语言。
- 每项完成后仅暂存该项文件并创建中文提交；不自动推送。

---

## File Structure

| 路径 | 责任 |
|---|---|
| app/src/main/java/com/harnessapk/storage/ChatExecutionEntryEntity.kt | Room 执行任务实体。 |
| app/src/main/java/com/harnessapk/storage/ChatExecutionEntryDao.kt | 队列查询、顺序与原子状态更新。 |
| app/src/main/java/com/harnessapk/chat/ChatExecutionModels.kt | 状态、请求快照和 UI 映射。 |
| app/src/main/java/com/harnessapk/chat/ChatExecutionRepository.kt | 入队、编辑、删除、引导、恢复和历史筛选事务。 |
| app/src/main/java/com/harnessapk/chat/QueuedAttachmentStore.kt | 入队图片复制到应用私有目录。 |
| app/src/main/java/com/harnessapk/chat/ChatExecutionCoordinator.kt | 会话内串行、跨会话并行执行。 |
| app/src/main/java/com/harnessapk/chat/ChatExecutionService.kt | 前台服务和通知。 |
| app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt | 执行既有用户消息，不能重复插入用户消息。 |
| app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt | 输入入队、队列菜单、按钮优先级和思考摘要。 |
| app/src/main/java/com/harnessapk/storage/AppDatabase.kt | 数据库版本、实体和 MIGRATION_8_9。 |
| app/src/main/java/com/harnessapk/common/AppContainer.kt | 执行器组装。 |

## Task 1: 持久化执行队列

**Files:**
- Create: app/src/main/java/com/harnessapk/storage/ChatExecutionEntryEntity.kt
- Create: app/src/main/java/com/harnessapk/storage/ChatExecutionEntryDao.kt
- Create: app/src/main/java/com/harnessapk/chat/ChatExecutionModels.kt
- Create: app/src/main/java/com/harnessapk/chat/ChatExecutionRepository.kt
- Modify: app/src/main/java/com/harnessapk/storage/AppDatabase.kt
- Modify: app/src/main/java/com/harnessapk/common/AppContainer.kt
- Modify: app/src/main/java/com/harnessapk/chat/ChatRepository.kt
- Test: app/src/test/java/com/harnessapk/chat/ChatExecutionRepositoryTest.kt
- Test: app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt

**Interfaces:**
- Produces enum ChatExecutionStatus with QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED and STEERED.
- Produces ChatExecutionEntry with conversationId, userMessageId, assistantMessageId, targetAssistantMessageId, sequence, type, status, provider/model settings, request snapshot and error.
- Produces enqueue(request), requestHistory(entryId), recoverAfterProcessDeath and observeForConversation(conversationId).

- [ ] **Step 1: Write the failing tests**

~~~kotlin
@Test
fun enqueueAssignsIncreasingSequenceWithinConversation() = runTest {
    val first = repository.enqueue(request("c1", "第一条"))
    val second = repository.enqueue(request("c1", "第二条"))

    assertEquals(1L, first.sequence)
    assertEquals(2L, second.sequence)
    assertEquals(ChatExecutionStatus.QUEUED, second.status)
}

@Test
fun requestHistoryExcludesOtherQueuedUserMessages() = runTest {
    val current = repository.enqueue(request("c1", "当前"))
    repository.enqueue(request("c1", "未来"))

    assertEquals(listOf("当前"), repository.requestHistory(current.id).map(ChatMessage::content))
}

@Test
fun recoveryInterruptsOnlyRunningEntries() = runTest {
    val running = repository.enqueue(request("c1", "运行中"))
    repository.markRunning(running.id, assistantMessageId = "a1")
    val queued = repository.enqueue(request("c1", "等待中"))

    repository.recoverAfterProcessDeath()

    assertEquals(ChatExecutionStatus.INTERRUPTED, repository.entry(running.id)!!.status)
    assertEquals(ChatExecutionStatus.QUEUED, repository.entry(queued.id)!!.status)
}
~~~

- [ ] **Step 2: Run tests to verify they fail**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.chat.ChatExecutionRepositoryTest
~~~

Expected: compilation fails because queue models and repository do not exist.

- [ ] **Step 3: Add entity, DAO and migration**

Create chat_execution_entries with a foreign key to conversations, a foreign key to the queued messages record, index conversationId, and unique conversationId + sequence. Required entity shape:

~~~kotlin
data class ChatExecutionEntryEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val userMessageId: String,
    val assistantMessageId: String?,
    val targetAssistantMessageId: String?,
    val sequence: Long,
    val type: String,
    val status: String,
    val providerId: String?,
    val model: String?,
    val reasoningEffort: String,
    val requestContextJson: String,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
~~~

Raise Room to version 9, add MIGRATION_8_9, expose the DAO, and register it in AppContainer.

- [ ] **Step 4: Implement transactional queue operations**

Use database.withTransaction for all state changes. enqueue must create the visible user message, allocate max(sequence) + 1, then insert the entry. requestHistory must include terminal history and target user message but omit every other queued user message. recoverAfterProcessDeath must only change RUNNING to INTERRUPTED and cancel their persisted assistant messages.

- [ ] **Step 5: Add Room instrumentation coverage**

Insert one conversation, one user message and one execution entity; assert DAO readback. Delete the conversation and assert the execution entry is removed by the foreign key.

- [ ] **Step 6: Verify and commit**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.chat.ChatExecutionRepositoryTest
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew assembleDebugAndroidTest
git add app/src/main/java/com/harnessapk/storage/ChatExecutionEntryEntity.kt app/src/main/java/com/harnessapk/storage/ChatExecutionEntryDao.kt app/src/main/java/com/harnessapk/storage/AppDatabase.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/main/java/com/harnessapk/chat/ChatExecutionModels.kt app/src/main/java/com/harnessapk/chat/ChatExecutionRepository.kt app/src/main/java/com/harnessapk/chat/ChatRepository.kt app/src/test/java/com/harnessapk/chat/ChatExecutionRepositoryTest.kt app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt
git commit -m "功能：持久化会话执行队列"
~~~

Expected: Gradle commands exit 0 before commit.

## Task 2: 持久化图片与执行既有消息

**Files:**
- Create: app/src/main/java/com/harnessapk/chat/QueuedAttachmentStore.kt
- Modify: app/src/main/java/com/harnessapk/chat/ChatRepository.kt
- Modify: app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt
- Test: app/src/test/java/com/harnessapk/chat/QueuedAttachmentStoreTest.kt
- Test: app/src/test/java/com/harnessapk/chat/SendMessageUseCaseSupportTest.kt

**Interfaces:**
- Produces QueuedAttachmentStore.persist(source).
- Produces SendMessageUseCase.execute(entry, history).
- execute consumes entry.userMessageId and never inserts another user message.

- [ ] **Step 1: Write the failing tests**

~~~kotlin
@Test
fun persistedAttachmentRemainsReadableAfterSourceUriIsGone() = runTest {
    val stored = attachmentStore.persist(PendingImageAttachment(sourceUri, "image/jpeg"))
    sourceFile.delete()

    assertTrue(File(requireNotNull(stored.uri.path)).exists())
}

@Test
fun executeQueuedEntryDoesNotInsertSecondUserMessage() = runTest {
    useCase.execute(entry = queuedEntry, history = listOf(existingUserMessage))

    assertEquals(1, repository.listMessages("c1").count { it.role == MessageRole.USER })
}
~~~

- [ ] **Step 2: Run tests to verify they fail**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.chat.QueuedAttachmentStoreTest --tests com.harnessapk.chat.SendMessageUseCaseSupportTest
~~~

Expected: compilation fails because QueuedAttachmentStore and execute do not exist.

- [ ] **Step 3: Implement private attachment storage**

Copy through ContentResolver into filesDir/chat-attachments/uuid. Write a temporary file and rename only after copy succeeds; delete the temporary file on exception. Store the returned private URI in message_attachments; do not use MediaStore.

- [ ] **Step 4: Refactor SendMessageUseCase**

Keep send as a compatibility wrapper. Add execute which reads attachments from entry.userMessageId, takes pre-filtered history, runs current capability validation, creates one assistant pending message, streams into it, and returns terminal status. It must not call insertUserMessage. Preserve ModelAwareRequestBuilder.withDefaultReasoningLanguage and its existing tests.

- [ ] **Step 5: Verify and commit**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.chat.QueuedAttachmentStoreTest --tests com.harnessapk.chat.SendMessageUseCaseSupportTest --tests com.harnessapk.chat.ModelAwareRequestBuilderTest
git add app/src/main/java/com/harnessapk/chat/QueuedAttachmentStore.kt app/src/main/java/com/harnessapk/chat/ChatRepository.kt app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt app/src/test/java/com/harnessapk/chat/QueuedAttachmentStoreTest.kt app/src/test/java/com/harnessapk/chat/SendMessageUseCaseSupportTest.kt
git commit -m "功能：执行持久化会话消息"
~~~

Expected: Gradle exits 0 before commit.

## Task 3: 会话级并行调度与引导当前

**Files:**
- Create: app/src/main/java/com/harnessapk/chat/ChatExecutionCoordinator.kt
- Modify: app/src/main/java/com/harnessapk/chat/ChatExecutionRepository.kt
- Modify: app/src/main/java/com/harnessapk/common/AppContainer.kt
- Test: app/src/test/java/com/harnessapk/chat/ChatExecutionCoordinatorTest.kt

**Interfaces:**
- Produces enqueue(request), steer(entryId), cancelActive(conversationId), resumePending and activeExecutionCount.
- Uses a supervisor scope and one Job per conversation id.

- [ ] **Step 1: Write failing coordinator tests**

~~~kotlin
@Test
fun entriesInOneConversationRunInSequence() = runTest {
    coordinator.enqueue(request("c1", "一"))
    coordinator.enqueue(request("c1", "二"))
    runner.complete("c1")

    assertEquals(listOf("一", "二"), runner.startedTextsFor("c1"))
}

@Test
fun differentConversationsRunInParallel() = runTest {
    coordinator.enqueue(request("c1", "甲"))
    coordinator.enqueue(request("c2", "乙"))

    assertEquals(setOf("c1", "c2"), runner.activeConversationIds())
}

@Test
fun steerCancelsOnlyItsConversationAndPromotesSelectedEntry() = runTest {
    coordinator.enqueue(request("c1", "原回复"))
    val guide = coordinator.enqueue(request("c1", "改为简要说明"))

    coordinator.steer(guide.id)

    assertEquals(listOf("c1"), runner.cancelledConversationIds())
    assertEquals("改为简要说明", runner.nextStartedText("c1"))
}
~~~

- [ ] **Step 2: Run tests to verify they fail**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.chat.ChatExecutionCoordinatorTest
~~~

Expected: compilation fails because the coordinator is absent.

- [ ] **Step 3: Implement per-conversation runners**

Use an application SupervisorJob and a MutableMap keyed by conversation id. Each runner transactionally claims the next queued entry, marks it running, calls SendMessageUseCase.execute, persists terminal state, then continues. A failed task must not stop following tasks.

steer(entryId) must transactionally promote the selected queue item, persist target active assistant id, cancel only that conversation job, mark its partial assistant entry STEERED, then start the same conversation runner. Other queued entries retain their relative order.

- [ ] **Step 4: Wire AppContainer, verify and commit**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.chat.ChatExecutionCoordinatorTest --tests com.harnessapk.chat.ChatExecutionRepositoryTest
git add app/src/main/java/com/harnessapk/chat/ChatExecutionCoordinator.kt app/src/main/java/com/harnessapk/chat/ChatExecutionRepository.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/test/java/com/harnessapk/chat/ChatExecutionCoordinatorTest.kt
git commit -m "功能：调度会话并行流式任务"
~~~

Expected: tests pass before commit.

## Task 4: 前台服务与后台连续执行

**Files:**
- Create: app/src/main/java/com/harnessapk/chat/ChatExecutionService.kt
- Modify: app/src/main/java/com/harnessapk/HarnessApkApplication.kt
- Modify: app/src/main/AndroidManifest.xml
- Modify: app/src/main/java/com/harnessapk/chat/ChatExecutionCoordinator.kt
- Test: app/src/androidTest/java/com/harnessapk/chat/ChatExecutionServiceTest.kt

**Interfaces:**
- Produces ChatExecutionService.start(context) and stopWhenIdle(context).
- Consumes activeExecutionCount and persistent queue.

- [ ] **Step 1: Write the failing service helper test**

~~~kotlin
@Test
fun foregroundNotificationSummarizesActiveConversationCount() {
    assertEquals("正在生成 2 个回复", foregroundNotificationText(activeCount = 2))
}
~~~

- [ ] **Step 2: Run test to verify it fails**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.chat.ChatExecutionServiceTest
~~~

Expected: compilation fails because the service helper is absent.

- [ ] **Step 3: Implement the service**

Create notification channel chat_execution, call startForeground immediately, collect coordinator count, and update text to “正在生成 N 个回复”. Do not add notification actions. Call recoverAfterProcessDeath once from application startup and resumePending after recovery. Start with ContextCompat.startForegroundService when a task enters the queue; stop only when active count and queued count are both zero.

Add exactly:

~~~xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<service
    android:name=".chat.ChatExecutionService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
~~~

- [ ] **Step 4: Verify and commit**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebugAndroidTest
git add app/src/main/java/com/harnessapk/chat/ChatExecutionService.kt app/src/main/java/com/harnessapk/chat/ChatExecutionCoordinator.kt app/src/main/java/com/harnessapk/HarnessApkApplication.kt app/src/main/AndroidManifest.xml app/src/androidTest/java/com/harnessapk/chat/ChatExecutionServiceTest.kt
git commit -m "功能：后台持续执行会话流式任务"
~~~

Expected: Gradle exits 0 before commit.

## Task 5: 输入排队、队列操作和思考摘要

**Files:**
- Modify: app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt
- Modify: app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt

**Interfaces:**
- Produces ChatInputTrailingAction.STOP.
- Produces reasoningPreviewLine(content).
- Consumes observeForConversation(conversationId) execution entries.

- [ ] **Step 1: Write failing UI-state tests**

~~~kotlin
@Test
fun nonEmptyDraftUsesSendWhileAssistantIsBusy() {
    assertEquals(
        ChatInputTrailingAction.SEND,
        chatInputTrailingAction(text = "下一轮", hasSelectedImage = false, isBusy = true),
    )
}

@Test
fun emptyDraftUsesStopWhileAssistantIsBusy() {
    assertEquals(
        ChatInputTrailingAction.STOP,
        chatInputTrailingAction(text = "", hasSelectedImage = false, isBusy = true),
    )
}

@Test
fun reasoningPreviewUsesLatestNonBlankLine() {
    assertEquals("正在比对接口字段", reasoningPreviewLine("先检查项目\n\n正在比对接口字段\n"))
}
~~~

- [ ] **Step 2: Run tests to verify they fail**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest
~~~

Expected: references to STOP and reasoningPreviewLine fail.

- [ ] **Step 3: Replace page-owned send with enqueue**

Capture body, image, selected provider/model, reasoning effort, session context and web search settings. Call container.chatExecutionCoordinator.enqueue. Clear text and selectedImage only after it returns successfully. Observe persisted execution entries and render their message labels.

- [ ] **Step 4: Add queue-item menu and exact tail-button priority**

For user messages linked to QUEUED entries, provide an overflow menu with 引导当前, 编辑, 删除. Edit restores the input draft and deletes the old entry; delete removes the entry and attachment; guide calls coordinator.steer(entry.id).

Use this priority:

~~~kotlin
when {
    text.isNotBlank() || hasSelectedImage -> ChatInputTrailingAction.SEND
    isBusy -> ChatInputTrailingAction.STOP
    else -> ChatInputTrailingAction.ATTACHMENT
}
~~~

STOP cancels only the current conversation's active job.

- [ ] **Step 5: Render one-line collapsed reasoning**

Keep full part content when expanded. When collapsed, render latest non-blank line through reasoningPreviewLine with maxLines = 1, TextOverflow.Ellipsis, and an ExpandMore or ExpandLess icon button. Preserve the label 思考过程.

- [ ] **Step 6: Verify and commit**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew assembleDebug
git add app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt
git commit -m "功能：会话队列与思考摘要交互"
~~~

Expected: both Gradle commands exit 0 before commit.

## Task 6: 集成验证

**Files:**
- Modify: app/src/androidTest/java/com/harnessapk/chat/ChatExecutionServiceTest.kt
- Modify: app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt

**Interfaces:**
- Consumes tasks 1-5 without adding production API.

- [ ] **Step 1: Add end-to-end state tests**

Cover persisted status and next runnable entry for:
- 同会话两项按 FIFO 执行，选中第二项引导当前后它优先执行。
- 两个会话同时拥有 RUNNING 项。
- 恢复后 RUNNING 为 INTERRUPTED，QUEUED 仍可运行。
- 当前任务失败不阻塞同会话下一项。

- [ ] **Step 2: Run complete verification**

~~~bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
git diff --check
git status --short --branch
~~~

Expected: Gradle exits 0; whitespace check has no output; worktree contains no unrelated changes.

- [ ] **Step 3: Run device smoke test if a device is connected**

Install debug APK, create two conversations, queue two messages in the first and one in the second, background/lock the app, then return. Verify notification presence, parallel updates, FIFO ordering, guide-current preservation and one-line reasoning summary. If no device is connected, report that gap and do not claim device verification.

- [ ] **Step 4: Commit verification changes**

~~~bash
git add app/src/androidTest/java/com/harnessapk/chat/ChatExecutionServiceTest.kt app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt
git commit -m "测试：覆盖会话队列与后台执行"
~~~

## Plan Self-Review

### Spec coverage

- 立即清空和失败保留草稿：Task 5。
- 每会话 FIFO、跨会话并行：Task 3。
- 队列项引导、编辑、删除：Task 3 和 Task 5。
- 后台/锁屏连续与无操作通知：Task 4。
- 进程杀死安全恢复：Task 1 和 Task 4。
- 图片可持久排队：Task 2。
- 中文思考默认与一行摘要：Task 2 保留现有语言指令，Task 5 实现展示。
- 未执行消息不进入模型上下文：Task 1 和 Task 2。

### Type consistency

- 所有持久化任务状态使用 ChatExecutionStatus。
- 所有后台入口使用 ChatExecutionCoordinator，页面不直接启动网络协程。
- ChatInputTrailingAction 新增 STOP，避免继续复用 SEND 代表终止。
- SendMessageUseCase.execute 只执行已持久化用户消息。

