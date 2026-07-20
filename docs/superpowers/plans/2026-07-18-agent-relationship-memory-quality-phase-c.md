# 人物关系记忆与质量闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为同一人物身份提供跨日常会话和项目会话的本地关系连续性、完整用户控制和可重复的人格质量评测，同时严格阻止项目事实进入人物关系记忆。

**Architecture:** Room 16 新增按 `agentId` 作用域的离散关系记忆，纯策略层先验证类别、来源和项目事实边界，再由仓库以稳定 dedupe key 合并；LLM 只生成候选，不直接写数据库。应用级 `AgentMemoryCoordinator` 在每 10 个完成回合和离开会话时异步尝试提取，失败不影响回复；阶段 B 的 `AgentContextAssembler` 通过只读 provider 注入关系记忆。桌面 benchmark 把证据、立场、时间、语言套话和 V1/V2 盲测做成可重复报告。

**Tech Stack:** Kotlin、Room 2.8.4、Kotlin Coroutines、Kotlin Serialization、现有 OpenAI-compatible client、Jetpack Compose、Python 3.12、JSONL、JUnit 4。

## Global Constraints

- 人物关系记忆只按 `agentId` 召回，可跨该身份的日常会话和项目会话连续。
- 只保存称呼偏好、稳定偏好、共同经历和关系变化。
- 项目目标、文件、任务、决定、业务事实和只对当前话题有效的内容不得进入人物关系记忆。
- 会话摘要继续按 `conversationId` 保存，不复制到人物关系记忆表。
- 项目事实只能通过项目 Markdown 和项目上下文跨会话共享。
- 所有记忆均为可审计的离散事实，必须保留来源会话和来源消息，不保存隐藏推理。
- 用户手工编辑后的记忆不得被后续自动提取覆盖；编辑只改变内容、置信度和 `userEdited`，保留原始来源审计字段。
- 自动提取失败不得阻断回复、弹重试或改变当前回答。
- 不增加每轮“是否记住”确认；用户可查看、编辑、删除单条和清空当前人物记忆。
- 关系记忆只保存在本机 Room，不进入项目 Git、`.hbundle` 或远端服务。
- 本阶段把 Room 从 15 升级到 16；`MIGRATION_15_16` 只新增人物关系记忆，不重写既有迁移。
- V1/V2 包、固定会话版本、项目/Markdown 弱关联和普通会话必须保持兼容。
- 人格质量必须用固定回归集和报告证明，不能只凭提示词主观判断。

---

## File Structure

### 关系记忆数据与策略

- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryModels.kt`：kind、domain、candidate、extraction input/result。
- Create: `app/src/main/java/com/harnessapk/storage/AgentMemoryEntity.kt`：Room entity。
- Create: `app/src/main/java/com/harnessapk/storage/AgentMemoryDao.kt`：按 agent 查询、upsert、编辑、删除和清空。
- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryRepository.kt`：稳定 ID、去重、冲突更新和来源审计。
- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryPolicy.kt`：允许类别、项目事实指纹和本地防线。
- Modify: `app/src/main/java/com/harnessapk/storage/AppDatabase.kt`：version 16、DAO 和 `MIGRATION_15_16`。
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`：注册 migration 和仓库。

### 自动提取与运行时

- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryCandidateGenerator.kt`：可测试生成接口。
- Create: `app/src/main/java/com/harnessapk/agentmemory/LlmAgentMemoryCandidateGenerator.kt`：现有模型生成严格 JSON 候选。
- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryExtractionUseCase.kt`：生成、策略过滤和仓库合并。
- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryCoordinator.kt`：每 10 回合和离开触发、去重并发与失败隔离。
- Modify: `app/src/main/java/com/harnessapk/chat/ChatExecutionCoordinator.kt`：成功回复后通知。
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`：离开会话时通知。
- Modify: `app/src/main/java/com/harnessapk/agent/AgentContextAssembler.kt`：注入只读关系记忆。

### 用户控制 UI

- Create: `app/src/main/java/com/harnessapk/ui/agent/AgentMemoryUiState.kt`：分组、来源和确认规则纯状态。
- Create: `app/src/main/java/com/harnessapk/ui/agent/AgentMemorySheet.kt`：查看、编辑、删除和清空。
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ConversationIdentityPicker.kt`：身份详情进入人物关系记忆。
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`：来源会话跳转回调。

### 质量闭环

- Create: `tools/agent_builder/persona_benchmark.py`：固定用例、单输出评分、盲测配对和汇总。
- Modify: `tools/agent_builder/cli.py`：`benchmark-score` 与 `benchmark-blind`。
- Create: `tools/agent_builder/tests/test_persona_benchmark.py`。
- Create: `tools/agent_builder/tests/fixtures/persona-regression.jsonl`：至少 30 立场题、20 voice 题、12 temporal 题和 20 盲测问题。
- Create: `tools/agent_builder/tests/fixtures/persona-regression-passing-responses.jsonl`：确定性通过样例，用于 CLI 回归。
- Create: `app/src/test/java/com/harnessapk/agentmemory/AgentMemoryPolicyTest.kt`。
- Create: `app/src/test/java/com/harnessapk/agentmemory/AgentMemoryRepositoryTest.kt`。
- Create: `app/src/test/java/com/harnessapk/agentmemory/AgentMemoryExtractionUseCaseTest.kt`。
- Create: `app/src/test/java/com/harnessapk/agentmemory/AgentMemoryCoordinatorTest.kt`。
- Create: `app/src/test/java/com/harnessapk/ui/agent/AgentMemoryUiStateTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/agent/AgentContextAssemblerTest.kt`。
- Modify: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`。

---

### Task 1: Room 15 -> 16 人物关系记忆与审计来源

**Files:**
- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryModels.kt`
- Create: `app/src/main/java/com/harnessapk/storage/AgentMemoryEntity.kt`
- Create: `app/src/main/java/com/harnessapk/storage/AgentMemoryDao.kt`
- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryRepository.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/AppDatabase.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Create: `app/src/test/java/com/harnessapk/agentmemory/AgentMemoryRepositoryTest.kt`
- Modify: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`

**Interfaces:**
- Produces: `enum class AgentMemoryKind`。
- Produces: `data class AgentMemory` 和 `data class AgentMemoryCandidate`。
- Produces: `AgentMemoryRepository.observe(agentId): Flow<List<AgentMemory>>`。
- Produces: `merge(agentId, conversationId, candidates): AgentMemoryMergeResult`。
- Produces: `edit(id, content)`、`delete(id)`、`clear(agentId)`。

- [ ] **Step 1: 写仓库与迁移失败测试**

```kotlin
@Test
fun mergeUsesStableKeyAndKeepsLatestSourceForConflict() = runTest {
    repository.merge(
        agentId = "a1",
        conversationId = "c1",
        candidates = listOf(candidate("address", "称呼我为 Tony", "m1")),
    )
    repository.merge(
        agentId = "a1",
        conversationId = "c2",
        candidates = listOf(candidate("address", "称呼我为老唐", "m2")),
    )

    val memories = repository.list("a1")
    assertEquals(1, memories.size)
    assertEquals("称呼我为老唐", memories.single().content)
    assertEquals("c2", memories.single().sourceConversationId)
    assertEquals("m2", memories.single().sourceMessageId)
}

@Test
fun clearOnlyDeletesCurrentAgentMemories() = runTest {
    repository.merge("a1", "c1", listOf(candidate("language", "默认中文", "m1")))
    repository.merge("a2", "c2", listOf(candidate("language", "默认中文", "m2")))

    repository.clear("a1")

    assertTrue(repository.list("a1").isEmpty())
    assertEquals(1, repository.list("a2").size)
}
```

Instrumentation migration test 创建 version 15 数据库，写入会话、消息、Markdown link、V2 agent/chunk，再应用 `MIGRATION_15_16`，断言旧表逐项未变且 `agent_memories` 为空。

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agentmemory.AgentMemoryRepositoryTest`

Expected: FAIL，memory domain、DAO 和 repository 尚不存在。

- [ ] **Step 3: 实现实体、DAO 和 migration**

```kotlin
enum class AgentMemoryKind {
    USER_PREFERENCE,
    ADDRESS_PREFERENCE,
    SHARED_HISTORY,
    RELATIONSHIP_EVENT,
}

@Entity(
    tableName = "agent_memories",
    indices = [Index(value = ["agentId", "updatedAt"])],
)
data class AgentMemoryEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val kind: String,
    val content: String,
    val sourceConversationId: String,
    val sourceMessageId: String,
    val confidence: Double,
    val userEdited: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
```

```kotlin
@Dao
interface AgentMemoryDao {
    @Query("SELECT * FROM agent_memories WHERE agentId = :agentId ORDER BY updatedAt DESC, id ASC")
    fun observeForAgent(agentId: String): Flow<List<AgentMemoryEntity>>

    @Query("SELECT * FROM agent_memories WHERE agentId = :agentId ORDER BY updatedAt DESC, id ASC")
    suspend fun listForAgent(agentId: String): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_memories WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AgentMemoryEntity?

    @Upsert suspend fun upsert(entity: AgentMemoryEntity)
    @Query("DELETE FROM agent_memories WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM agent_memories WHERE agentId = :agentId") suspend fun clear(agentId: String)
}
```

`MIGRATION_15_16` 只执行 `CREATE TABLE agent_memories` 和复合索引，不 ALTER 任何既有表。

- [ ] **Step 4: 实现稳定 dedupe key 和编辑约束**

```kotlin
data class AgentMemoryCandidate(
    val kind: AgentMemoryKind,
    val dedupeKey: String,
    val content: String,
    val sourceMessageId: String,
    val sourceQuote: String,
    val confidence: Double,
)

internal fun agentMemoryId(agentId: String, kind: AgentMemoryKind, dedupeKey: String): String {
    val input = "$agentId|${kind.name}|${dedupeKey.trim().lowercase()}"
    return MessageDigest.getInstance("SHA-256")
        .digest(input.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
```

`merge` 对同一稳定 ID 执行事务内 insert-or-update，保留首次 `createdAt`；仅当现有记录
`userEdited == false` 时更新 content、来源、confidence 和 `updatedAt`。`edit` trim 后拒绝空内容，
把 confidence 设为 `1.0`、`userEdited` 设为 `true`，来源字段保持不变。

- [ ] **Step 5: 运行仓库和迁移测试**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agentmemory.AgentMemoryRepositoryTest`

Run: `./gradlew connectedDebugAndroidTest --tests com.harnessapk.storage.AppDatabaseTest`

Expected: PASS；`agentId` 作用域隔离、稳定冲突更新、来源审计、编辑、删除和清空全部通过。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agentmemory/AgentMemoryModels.kt app/src/main/java/com/harnessapk/storage/AgentMemoryEntity.kt app/src/main/java/com/harnessapk/storage/AgentMemoryDao.kt app/src/main/java/com/harnessapk/agentmemory/AgentMemoryRepository.kt app/src/main/java/com/harnessapk/storage/AppDatabase.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/test/java/com/harnessapk/agentmemory/AgentMemoryRepositoryTest.kt app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt
git commit -m "功能：持久化人物关系记忆"
```

### Task 2: 关系记忆候选策略与项目事实双重过滤

**Files:**
- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryPolicy.kt`
- Create: `app/src/test/java/com/harnessapk/agentmemory/AgentMemoryPolicyTest.kt`

**Interfaces:**
- Produces: `data class AgentMemoryExtractionInput`。
- Produces: `AgentMemoryPolicy.filter(input, candidates): List<AgentMemoryCandidate>`。
- Produces: `projectFactFingerprints(projectContext): Set<String>`。

- [ ] **Step 1: 写允许项和项目事实拒绝测试**

```kotlin
@Test
fun keepsStableRelationshipFacts() {
    val accepted = policy.filter(
        input(),
        listOf(
            candidate(ADDRESS_PREFERENCE, "address", "用户希望我称呼他为 Tony"),
            candidate(USER_PREFERENCE, "language", "用户希望默认使用中文回答"),
        ),
    )
    assertEquals(2, accepted.size)
}

@Test
fun rejectsProjectGoalsFilesTasksDecisionsAndBusinessFacts() {
    val input = input(
        projectId = "p1",
        projectFacts = listOf(
            "项目目标是发布 Harness APK",
            "需要修改 requirements/prd.md",
            "决定本周五上线",
        ),
    )
    val rejected = policy.filter(
        input,
        listOf(
            candidate(USER_PREFERENCE, "goal", "用户的项目目标是发布 Harness APK"),
            candidate(SHARED_HISTORY, "file", "我们修改了 requirements/prd.md"),
            candidate(RELATIONSHIP_EVENT, "decision", "我们决定本周五上线"),
        ),
    )
    assertTrue(rejected.isEmpty())
}

@Test
fun rejectsTransientTopicAndHiddenReasoning() {
    val accepted = policy.filter(
        input(),
        listOf(
            candidate(USER_PREFERENCE, "temporary", "用户这次临时想看三条建议"),
            candidate(SHARED_HISTORY, "reasoning", "模型内部先判断了风险"),
        ),
    )
    assertTrue(accepted.isEmpty())
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agentmemory.AgentMemoryPolicyTest`

Expected: FAIL，policy 尚不存在。

- [ ] **Step 3: 实现输入与本地硬边界**

```kotlin
data class AgentMemoryExtractionInput(
    val agentId: String,
    val conversationId: String,
    val projectId: String?,
    val conversationSummary: String,
    val recentMessages: List<ChatMessage>,
    val projectFacts: List<String>,
)
```

本地拒绝规则固定包括：

```kotlin
private val projectArtifactPattern =
    Regex("""(?i)([\w./-]+\.md\b|commit\s+[0-9a-f]{7,40}\b|项目目标|项目任务|需求项|接口地址|上线日期|文件变更)""")
private val hiddenReasoningPattern =
    Regex("""(隐藏推理|内部推理|思维链|模型内部|先在心里)""")
private val transientPattern =
    Regex("""(这次临时|仅本轮|当前话题|刚才这一次)""")
```

- [ ] **Step 4: 实现项目事实指纹相似度**

```kotlin
internal fun normalizedMemoryTerms(text: String): Set<String> =
    Regex("""[A-Za-z0-9_./-]{2,}|[\u3400-\u9fff]{2}""")
        .findAll(text.lowercase())
        .map(MatchResult::value)
        .toSet()

internal fun overlapsProjectFact(candidate: String, projectFacts: List<String>): Boolean {
    val candidateTerms = normalizedMemoryTerms(candidate)
    if (candidateTerms.isEmpty()) return false
    return projectFacts.any { fact ->
        val factTerms = normalizedMemoryTerms(fact)
        val union = candidateTerms union factTerms
        union.isNotEmpty() && (candidateTerms intersect factTerms).size.toDouble() / union.size >= 0.6
    }
}
```

`filter` 先检查 kind、content、confidence `[0.0,1.0]`，再要求 `sourceMessageId` 指向输入中
`SUCCEEDED` 的 USER 消息，且 trim 后的 `sourceQuote` 是该消息正文的精确非空子串。随后应用三类
pattern 和项目事实相似度，最后按 `(kind,dedupeKey)` 保留最高 confidence。不得以 assistant
消息、会话摘要或模型自行生成的内容作为事实来源。

- [ ] **Step 5: 运行策略测试确认 GREEN**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agentmemory.AgentMemoryPolicyTest`

Expected: PASS；项目事实 fixture 的持久化数量严格为 0，关系和语言偏好被保留。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agentmemory/AgentMemoryPolicy.kt app/src/test/java/com/harnessapk/agentmemory/AgentMemoryPolicyTest.kt
git commit -m "功能：隔离关系记忆与项目事实"
```

### Task 3: LLM 候选生成和可失败的提取用例

**Files:**
- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryCandidateGenerator.kt`
- Create: `app/src/main/java/com/harnessapk/agentmemory/LlmAgentMemoryCandidateGenerator.kt`
- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryExtractionUseCase.kt`
- Create: `app/src/test/java/com/harnessapk/agentmemory/AgentMemoryExtractionUseCaseTest.kt`

**Interfaces:**
- Produces: `fun interface AgentMemoryCandidateGenerator`。
- Produces: `AgentMemoryExtractionUseCase.extract(conversationId): AgentMemoryExtractionResult`。
- Consumes: Task 1 repository、Task 2 policy、现有 provider/client/chat/project gateway。

- [ ] **Step 1: 写“生成器不能直写”和失败隔离测试**

```kotlin
@Test
fun onlyPolicyAcceptedCandidatesReachRepository() = runTest {
    generator.result = listOf(
        candidate(USER_PREFERENCE, "language", "用户希望默认中文回答"),
        candidate(USER_PREFERENCE, "project", "用户的项目目标是发布 APK"),
    )

    val result = useCase.extract("c1")

    assertEquals(1, result.savedCount)
    assertEquals(listOf("用户希望默认中文回答"), repository.saved.map { it.content })
}

@Test
fun generationFailureReturnsFailedWithoutWritingOrThrowing() = runTest {
    generator.error = IOException("offline")

    val result = useCase.extract("c1")

    assertEquals(AgentMemoryExtractionStatus.FAILED, result.status)
    assertTrue(repository.saved.isEmpty())
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agentmemory.AgentMemoryExtractionUseCaseTest`

Expected: FAIL，生成接口和 use case 尚不存在。

- [ ] **Step 3: 定义严格生成接口和 JSON**

```kotlin
fun interface AgentMemoryCandidateGenerator {
    suspend fun generate(input: AgentMemoryExtractionInput): List<AgentMemoryCandidate>
}
```

系统提示固定为：

```text
只提取用户与当前人物以后如何相处仍有价值的离散事实。
允许：称呼偏好、稳定偏好、共同经历、关系变化。
禁止：项目目标、文件、任务、决定、业务事实、当前临时话题、模型推理。
只输出 JSON 数组；每项字段为 kind、dedupeKey、content、sourceMessageId、sourceQuote、confidence。
sourceQuote 必须逐字摘自对应的成功 USER 消息。
没有合格内容时输出 []。
```

`LlmAgentMemoryCandidateGenerator` 使用当前会话最后一个成功 assistant message 的 provider/model；不存在时使用默认文本 provider。temperature 固定 `0.0`，收集 `streamChat` 文本后只接受 JSON array，未知 kind 或字段缺失直接丢弃该项。

- [ ] **Step 4: 实现提取用例**

```kotlin
suspend fun extract(conversationId: String): AgentMemoryExtractionResult {
    val conversation = chatRepository.conversation(conversationId)
        ?: return AgentMemoryExtractionResult.skipped("会话不存在")
    val agentId = conversation.agentId
        ?: return AgentMemoryExtractionResult.skipped("普通会话")
    val completed = chatRepository.listMessages(conversationId)
        .filter { it.status == MessageStatus.SUCCEEDED }
    if (completed.none { it.role == MessageRole.ASSISTANT }) {
        return AgentMemoryExtractionResult.skipped("没有完成回合")
    }
    return runCatching {
        val input = buildExtractionInput(conversation, completed)
        val accepted = policy.filter(input, generator.generate(input))
        val merge = repository.merge(agentId, conversationId, accepted)
        AgentMemoryExtractionResult.succeeded(merge.savedCount)
    }.getOrElse {
        AgentMemoryExtractionResult.failed(it.message.orEmpty())
    }
}
```

`buildExtractionInput` 优先使用现有 `conversation_memory.summary`，再附最近 20 条成功消息；项目事实从 `SessionRequestContext` 可恢复字段和当前项目 Markdown 读取，仅用于过滤，不写入 candidate prompt 的允许区域。

- [ ] **Step 5: 运行提取测试确认 GREEN**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agentmemory.AgentMemoryExtractionUseCaseTest --tests com.harnessapk.agentmemory.AgentMemoryPolicyTest`

Expected: PASS；生成异常、JSON 异常、普通会话和无完成回合全部返回状态，不向聊天 UI 抛错。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agentmemory/AgentMemoryCandidateGenerator.kt app/src/main/java/com/harnessapk/agentmemory/LlmAgentMemoryCandidateGenerator.kt app/src/main/java/com/harnessapk/agentmemory/AgentMemoryExtractionUseCase.kt app/src/test/java/com/harnessapk/agentmemory/AgentMemoryExtractionUseCaseTest.kt
git commit -m "功能：从会话提取关系记忆候选"
```

### Task 4: 每 10 回合与离开会话的后台触发

**Files:**
- Create: `app/src/main/java/com/harnessapk/agentmemory/AgentMemoryCoordinator.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/ChatExecutionCoordinator.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Create: `app/src/test/java/com/harnessapk/agentmemory/AgentMemoryCoordinatorTest.kt`

**Interfaces:**
- Produces: `AgentMemoryCoordinator.onReplyCompleted(conversationId)`。
- Produces: `AgentMemoryCoordinator.onConversationLeft(conversationId)`。
- Consumes: Task 3 `AgentMemoryExtractionUseCase.extract`。

- [ ] **Step 1: 写触发频率和不阻断测试**

```kotlin
@Test
fun extractsAtEveryTenthCompletedRound() = runTest {
    repeat(9) { coordinator.onReplyCompleted("c1") }
    assertEquals(0, extractor.calls)

    coordinator.onReplyCompleted("c1")
    advanceUntilIdle()

    assertEquals(1, extractor.calls)
}

@Test
fun leavingAfterOneCompletedRoundExtractsOnce() = runTest {
    completedRounds["c1"] = 1
    coordinator.onConversationLeft("c1")
    coordinator.onConversationLeft("c1")
    advanceUntilIdle()

    assertEquals(1, extractor.maxConcurrentCalls)
}

@Test
fun extractionFailureDoesNotFailCompletedChatExecution() = runTest {
    extractor.status = AgentMemoryExtractionStatus.FAILED
    coordinator.onReplyCompleted("c1")
    advanceUntilIdle()
    assertTrue(chatResultRemainsSucceeded)
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agentmemory.AgentMemoryCoordinatorTest`

Expected: FAIL，coordinator 尚不存在。

- [ ] **Step 3: 实现应用级 SupervisorJob 与去重**

```kotlin
class AgentMemoryCoordinator(
    private val extractionUseCase: AgentMemoryExtractionUseCase,
    private val completedRoundCount: suspend (String) -> Int,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val completedCheckpoints = ConcurrentHashMap.newKeySet<String>()

    fun onReplyCompleted(conversationId: String) {
        scope.launch {
            val rounds = completedRoundCount(conversationId)
            if (rounds > 0 && rounds % 10 == 0) schedule(conversationId, "round:$rounds")
        }
    }

    fun onConversationLeft(conversationId: String) {
        scope.launch {
            val rounds = completedRoundCount(conversationId)
            if (rounds > 0) schedule(conversationId, "leave:$rounds")
        }
    }

    private suspend fun schedule(conversationId: String, checkpoint: String) {
        val key = "$conversationId:$checkpoint"
        if (!completedCheckpoints.add(key) || !running.add(conversationId)) return
        try {
            val result = extractionUseCase.extract(conversationId)
            if (result.status == AgentMemoryExtractionStatus.FAILED) completedCheckpoints.remove(key)
        } finally {
            running.remove(conversationId)
        }
    }
}
```

- [ ] **Step 4: 接入成功回复和 Compose 离开**

`ChatExecutionCoordinator` 仅在 `ChatExecutionStatus.SUCCEEDED` 后调用 `onReplyCompleted`，不 await 提取结果。`ChatScreen` 使用：

```kotlin
DisposableEffect(conversationId) {
    onDispose {
        container.agentMemoryCoordinator.onConversationLeft(conversationId)
    }
}
```

不得显示 toast、进度条或重试按钮。

- [ ] **Step 5: 运行 coordinator 与聊天队列回归**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agentmemory.AgentMemoryCoordinatorTest --tests com.harnessapk.chat.ChatExecutionModelsTest`

Expected: PASS；第 10/20 回合和离开触发，重复离开不并发，失败不改变聊天 execution terminal state。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agentmemory/AgentMemoryCoordinator.kt app/src/main/java/com/harnessapk/chat/ChatExecutionCoordinator.kt app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/test/java/com/harnessapk/agentmemory/AgentMemoryCoordinatorTest.kt
git commit -m "功能：自动沉淀人物关系记忆"
```

### Task 5: 把关系记忆注入单一 Context Assembler

**Files:**
- Modify: `app/src/main/java/com/harnessapk/agent/AgentContextAssembler.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Modify: `app/src/test/java/com/harnessapk/agent/AgentContextAssemblerTest.kt`

**Interfaces:**
- Consumes: `AgentMemoryRepository.list(agentId): List<AgentMemory>`。
- Preserves: `AgentContextAssembler.assemble(request): AgentRuntimeContext?` 唯一入口。
- Produces: system prompt 的“我们之间”区段。

- [ ] **Step 1: 写跨会话召回和清空失败测试**

```kotlin
@Test
fun sameAgentReceivesRelationshipMemoryAcrossDailyAndProjectConversations() = runTest {
    memories["a1"] = listOf(memory("用户希望我称呼他为 Tony", sourceConversationId = "daily"))

    val context = assembler.assemble(request(conversationId = "project-conversation"))

    assertTrue(context!!.systemPrompt.contains("用户希望我称呼他为 Tony"))
}

@Test
fun differentAgentAndClearedMemoryAreNotInjected() = runTest {
    memories["a2"] = listOf(memory("不属于 a1"))
    assertFalse(assembler.assemble(request(agentId = "a1"))!!.systemPrompt.contains("不属于 a1"))

    memories.clear()
    assertFalse(assembler.assemble(request(agentId = "a1"))!!.systemPrompt.contains("我们之间"))
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentContextAssemblerTest`

Expected: FAIL，阶段 B provider 默认返回空列表。

- [ ] **Step 3: 注入只读 provider**

```kotlin
val agentContextAssembler = AgentContextAssembler(
    chatRepository = chatRepository,
    agentRepository = agentRepository,
    relationshipMemoryProvider = { agentId ->
        agentMemoryRepository.list(agentId).map { memory ->
            "${memory.kind.promptLabel}：${memory.content}"
        }
    },
)
```

prompt 区段固定为：

```text
我们之间：
- 称呼偏好：...
- 稳定偏好：...
- 共同经历：...
- 关系变化：...
```

不包含来源会话 ID、项目名、文件或内部 confidence；来源只在 UI 查看。

- [ ] **Step 4: 限制注入预算**

按 `updatedAt DESC` 选择最多 12 条、总计最多 1,800 字；同 kind 最多 5 条。超限只影响本轮注入，不删除数据库内容。

- [ ] **Step 5: 运行 assembler 与发送链路回归**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentContextAssemblerTest --tests com.harnessapk.chat.SendMessageUseCaseSupportTest`

Expected: PASS；同 agent 跨项目/日常可召回，不同 agent 隔离，清空后不再注入。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agent/AgentContextAssembler.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/test/java/com/harnessapk/agent/AgentContextAssemblerTest.kt
git commit -m "功能：延续同一人物的关系上下文"
```

### Task 6: 身份详情中的查看、编辑、删除与清空

**Files:**
- Create: `app/src/main/java/com/harnessapk/ui/agent/AgentMemoryUiState.kt`
- Create: `app/src/main/java/com/harnessapk/ui/agent/AgentMemorySheet.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ConversationIdentityPicker.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- Create: `app/src/test/java/com/harnessapk/ui/agent/AgentMemoryUiStateTest.kt`

**Interfaces:**
- Produces: `agentMemorySections(memories): List<AgentMemorySection>`。
- Produces: `AgentMemorySheet(agentId, repository, onOpenSourceConversation, onDismiss)`。
- Consumes: Task 1 repository CRUD。

- [ ] **Step 1: 写 UI 状态失败测试**

```kotlin
@Test
fun groupsMemoriesByReaderFacingKind() {
    val sections = agentMemorySections(
        listOf(
            memory(ADDRESS_PREFERENCE, "称呼我为 Tony"),
            memory(USER_PREFERENCE, "默认中文回答"),
        ),
    )
    assertEquals(listOf("称呼偏好", "稳定偏好"), sections.map { it.title })
}

@Test
fun clearRequiresOneExplicitDestructiveConfirmation() {
    assertEquals(
        AgentMemoryClearDecision.SHOW_CONFIRMATION,
        clearDecision(memoryCount = 3, confirmed = false),
    )
    assertEquals(
        AgentMemoryClearDecision.CLEAR,
        clearDecision(memoryCount = 3, confirmed = true),
    )
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.agent.AgentMemoryUiStateTest`

Expected: FAIL，UI state 尚不存在。

- [ ] **Step 3: 实现紧凑分组和来源操作**

每条记忆显示 content、类型、更新时间和“查看来源”；点击来源使用 `sourceConversationId` 打开现有 Chat route 并定位 `sourceMessageId`。编辑使用一个 `OutlinedTextField`，保存调用 repository.edit；删除单条使用现有 overflow menu。

- [ ] **Step 4: 实现一次清空确认**

清空入口位于 sheet 底部危险操作区，只弹一次：

```text
清空与“李德胜”的人物关系记忆？
这不会删除会话、项目文件或智能体包，且无法撤销。
[取消] [清空]
```

不得增加逐条确认或每轮记忆开关。清空成功后 sheet 保持打开并展示空状态。

- [ ] **Step 5: 运行 UI 状态与 Compose 编译**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.agent.AgentMemoryUiStateTest`

Run: `./gradlew assembleDebug`

Expected: PASS；身份详情可进入关系记忆，所有操作均只作用于当前 agent。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/agent/AgentMemoryUiState.kt app/src/main/java/com/harnessapk/ui/agent/AgentMemorySheet.kt app/src/main/java/com/harnessapk/ui/chat/ConversationIdentityPicker.kt app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/test/java/com/harnessapk/ui/agent/AgentMemoryUiStateTest.kt
git commit -m "功能：管理人物关系记忆"
```

### Task 7: 人格 benchmark、盲测和质量门槛

**Files:**
- Create: `tools/agent_builder/persona_benchmark.py`
- Modify: `tools/agent_builder/cli.py`
- Create: `tools/agent_builder/tests/test_persona_benchmark.py`
- Create: `tools/agent_builder/tests/fixtures/persona-regression.jsonl`
- Create: `tools/agent_builder/tests/fixtures/persona-regression-passing-responses.jsonl`

**Interfaces:**
- Produces: `score_benchmark(cases, responses) -> PersonaBenchmarkReport`。
- Produces: `build_blind_pairs(v1, v2, seed=20260718) -> list[BlindPair]`。
- Produces: `score_blind_choices(pairs, choices) -> BlindComparisonReport`。
- Adds CLI: `benchmark-score`、`benchmark-blind`。

- [ ] **Step 1: 写质量指标失败测试**

```python
def test_score_reports_grounding_stance_voice_temporal_and_continuity(self):
    report = score_benchmark(fixture_cases(), fixture_responses())
    self.assertEqual(
        {"grounding", "stance", "voice", "temporal", "continuity"},
        set(report.metrics),
    )

def test_voice_avoid_pattern_rate_is_measured(self):
    report = score_benchmark(
        [case(avoid_patterns=["当前资料不足", "以下三点"])],
        [response("当前资料不足。以下三点：")],
    )
    self.assertEqual(1.0, report.voice_avoid_pattern_rate)

def test_blind_pair_order_is_deterministic_and_hidden(self):
    first = build_blind_pairs(v1(), v2(), seed=20260718)
    second = build_blind_pairs(v1(), v2(), seed=20260718)
    self.assertEqual(first, second)
    self.assertNotIn("v1", json.dumps(first[0].to_public_dict()))
    self.assertNotIn("v2", json.dumps(first[0].to_public_dict()))
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_persona_benchmark -v`

Expected: FAIL，benchmark 模块尚不存在。

- [ ] **Step 3: 实现固定 JSONL 契约**

Case：

```json
{"id":"stance-001","category":"stance","question":"我们现在该怎么走？","requiredEvidence":["chunk-id"],"requiredPeriods":[],"forbiddenPatterns":["当前资料不足","以下三点","我的回答不是"],"continuityFacts":[]}
```

Response：

```json
{"caseId":"stance-001","answer":"先把事实摸清，再决定道路。","evidenceIds":["chunk-id"],"usedPeriods":[],"recalledFacts":[]}
```

报告计算：

```text
grounding = requiredEvidence 命中
stance = stance 类用例全部命中有效 evidence
voice = forbiddenPatterns 命中率
temporal = requiredPeriods 覆盖且不把历史事实写成今日事实
continuity = continuityFacts 召回且无跨 agent 泄漏
```

- [ ] **Step 4: 实现盲测配对与门槛**

`benchmark-blind prepare` 用固定 seed 对每题随机交换 A/B，只把映射写入私有 answer key；评分文件只接受 `A|B|TIE`。门槛固定：

```python
MIN_STANCE_GROUNDED_COUNT = 30
MIN_BLIND_PAIR_COUNT = 20
MIN_V2_PREFERENCE_RATE = 0.70
MAX_AVOID_PATTERN_RATE = 0.05
```

- [ ] **Step 5: 运行 benchmark 测试和 fixture 校验**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_persona_benchmark -v`

Run: `scripts/agent-builder.sh benchmark-score tools/agent_builder/tests/fixtures/persona-regression.jsonl --responses tools/agent_builder/tests/fixtures/persona-regression-passing-responses.jsonl`

Expected: PASS；fixture 数量满足 30 stance、20 voice、12 temporal 和 20 blind questions。

- [ ] **Step 6: 提交**

```bash
git add tools/agent_builder/persona_benchmark.py tools/agent_builder/cli.py tools/agent_builder/tests/test_persona_benchmark.py tools/agent_builder/tests/fixtures
git commit -m "功能：建立人格质量基准与盲测"
```

### Task 8: 连续迁移、失败恢复和真实 V1/V2 验收

**Files:**
- Modify: `README.md`
- Modify: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`
- Modify: `app/src/test/java/com/harnessapk/agent/AgentContextAssemblerTest.kt`
- Modify: `app/src/test/java/com/harnessapk/agentmemory/AgentMemoryExtractionUseCaseTest.kt`

**Interfaces:**
- Consumes: Tasks 1-7 与阶段 A/B 全部产物。
- Produces: Room 11 -> 12 -> 13 -> 14 -> 15 -> 16 连续迁移证据、APK 和人格 benchmark 报告。

- [ ] **Step 1: 增加连续迁移 instrumentation test**

测试从手工创建的 version 11 schema 写入：

```text
普通日常会话
人格日常会话
普通项目会话
人格项目会话
消息和 message parts
ConversationMarkdownLink
V1 agent/version/corpus/chunk
```

依次应用 `MIGRATION_11_12`、`MIGRATION_12_13`、`MIGRATION_13_14`、`MIGRATION_14_15`、`MIGRATION_15_16`，再由 Room 16 打开并逐项断言值不变。

- [ ] **Step 2: 增加运行时降级矩阵**

`AgentContextAssemblerTest` 覆盖：

```text
V1 + 无关系记忆 + 无项目
V1 + 关系记忆 + 项目
V2 + 无关系记忆 + 项目
V2 + 关系记忆 + 项目
项目已删除后 projectId 清空
关系记忆清空后不再注入
缺失 optional corpus 仍可运行
缺失 required corpus 拒绝开始
```

- [ ] **Step 3: 运行全部自动测试**

Run: `scripts/agent-builder.sh -m unittest discover -s tools/agent_builder/tests -v`

Run: `./gradlew testDebugUnitTest assembleDebug`

Run: `./gradlew connectedDebugAndroidTest`

Run: `git diff --check`

Expected: 全部退出码 0；设备不可用时必须明确记录 instrumentation 未执行并在设备接入后补跑。

- [ ] **Step 4: 执行真实关系记忆冒烟**

固定路径：

```text
李德胜日常会话：用户说“以后叫我老唐，默认中文”
-> 完成一轮后离开
-> 新建同身份项目会话
-> 能自然沿用称呼和语言偏好
-> 不注入上一个项目的目标、文件或决定
-> 身份详情查看来源
-> 编辑称呼
-> 删除单条
-> 清空全部
-> 新一轮请求不再注入已清空内容
```

- [ ] **Step 5: 执行真实 V1/V2 盲测**

使用同一模型、同一 20+ 问题和关闭联网的条件分别生成 V1/V2 responses，运行：

```bash
scripts/agent-builder.sh benchmark-blind prepare --v1 build/benchmark/v1-responses.jsonl --v2 build/benchmark/v2-responses.jsonl --output build/benchmark/blind
scripts/agent-builder.sh benchmark-blind score --pairs build/benchmark/blind/pairs.jsonl --answer-key build/benchmark/blind/answer-key.json --choices build/benchmark/blind/choices.jsonl --output build/benchmark/report.json
```

Expected: V2 偏好率不低于 70%，avoidPatterns 命中率低于 5%，30 道立场题全部有有效证据，时期冲突题保留差异。真实响应和报告留在 `build/`，不提交含原文或私密会话的数据。

- [ ] **Step 6: 更新 README 和提交**

README 说明人物关系记忆的本地作用域、用户控制、项目事实边界和清空路径。

```bash
git add README.md app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt app/src/test/java/com/harnessapk/agent/AgentContextAssemblerTest.kt app/src/test/java/com/harnessapk/agentmemory/AgentMemoryExtractionUseCaseTest.kt
git commit -m "验证：完成人格关系与质量闭环"
```

---

## Self-Review

- Spec coverage: `agent_memories` 字段、按 agent 召回、自动沉淀时机、稳定四类内容、项目事实过滤、失败隔离、查看/编辑/删除/清空、Context Assembler 注入和本地隐私边界均有对应任务。
- No hidden project memory: LLM prompt 是第一道边界，本地 pattern + project fingerprint 是第二道边界；只有通过纯策略的 candidate 才能进入 repository。
- Database sequencing: `MIGRATION_15_16` 只创建关系记忆表；最终 instrumentation 从 11 连续经过 12、13、14、15 和 16，保护会话、Markdown 和 V1/V2 索引。
- Type consistency: `AgentMemoryKind`、`AgentMemoryCandidate.dedupeKey`、稳定 ID 和 UI section 使用同一枚举；assembler 只读取 domain memory，不直接访问 DAO。
- Quality proof: 固定回归集覆盖 grounding、stance、voice、temporal、continuity，盲测隐藏 V1/V2 映射并执行 70%/5%/30 题门槛。
