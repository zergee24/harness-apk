# 可移植语料人格智能体实施包 1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 M4 Mac mini 的桌面 Codex 中把本地书籍构建为签名 `.hbundle`，并让现有 Harness APK 完成本地导入、校验、持久化、检索和固定版本智能体会话。

**Architecture:** 桌面端由仓库内确定性 Python CLI 负责提取、分块、校验、签名和打包，Codex Skill 负责分层语义蒸馏。Android 端直接接入现有 Room、`AppContainer`、会话和 Compose 首页，通过 FTS4 检索当前 Agent 版本声明的语料，并在现有 `SendMessageUseCase` 中注入严格资料上下文。

**Tech Stack:** Python 3、`cryptography`、`pypdf`、ZIP/JSONL、Kotlin、Room 2.8、SQLite FTS4、Jetpack Compose、JUnit。

## Global Constraints

- 桌面端唯一验收环境是 Apple M4 Mac mini、macOS 26.x、`darwin/arm64`。
- 桌面能力以 Codex Skill + 仓库内 CLI 提供，不开发独立 GUI 或常驻桌面应用。
- Android 功能必须集成进现有 Harness APK，不创建第二个 App、独立聊天实现或独立数据库。
- Agent 回答使用第一人称，但必须显示“基于资料模拟”，且不得用模型通用知识补写人物立场。
- `Conversation` 固定保存 `agentId + agentVersion`；Agent 升级不得改变历史会话。
- 第一期只实现本地 `.hbundle` 导入；远端 catalog、二维码和私人同步不在本计划范围。
- 包只允许声明式 JSON、JSONL、Markdown 和文本资料，不执行任何包内代码。

---

## 文件结构

### 桌面构建器

- Create: `tools/agent_builder/__init__.py`：包入口。
- Create: `tools/agent_builder/models.py`：manifest、source、chunk 和构建报告模型。
- Create: `tools/agent_builder/extractors.py`：TXT、Markdown、EPUB、文本 PDF 提取。
- Create: `tools/agent_builder/builder.py`：稳定分块、中文 n-gram、校验、checksums、Ed25519 和 ZIP 打包。
- Create: `tools/agent_builder/cli.py`：`prepare`、`validate`、`pack` 命令。
- Create: `tools/agent_builder/__main__.py`：`python -m tools.agent_builder` 入口。
- Create: `tools/agent_builder/requirements.txt`：Codex 运行时之外的可复现依赖声明。
- Create: `tools/agent_builder/tests/test_builder.py`：确定性构建与安全校验测试。
- Create: `scripts/agent-builder.sh`：优先发现 Codex 随附 Python 的 M4 macOS 启动器。
- Create: `.codex/skills/agent-builder/SKILL.md`：会话式构建流程。

### Android 包、数据与运行时

- Create: `app/src/main/java/com/harnessapk/agent/AgentModels.kt`：领域模型、导入预览、状态和运行时证据。
- Create: `app/src/main/java/com/harnessapk/agent/AgentBundleReader.kt`：安全 ZIP、checksum、Ed25519、manifest 和 JSONL 解析。
- Create: `app/src/main/java/com/harnessapk/agent/AgentRepository.kt`：staging、原子安装、Room 写入、FTS 检索和提示上下文。
- Create: `app/src/main/java/com/harnessapk/storage/AgentEntities.kt`：Agent、版本、资料、交叉引用、chunk 和 FTS4 表。
- Create: `app/src/main/java/com/harnessapk/storage/AgentDao.kt`：安装与检索 DAO。
- Modify: `app/src/main/java/com/harnessapk/storage/AppDatabase.kt`：数据库版本 11、实体、DAO 和 10->11 migration。
- Modify: `app/src/main/java/com/harnessapk/storage/ConversationEntity.kt`：增加可空 `agentId`、`agentVersion`。
- Modify: `app/src/main/java/com/harnessapk/chat/ChatModels.kt`：领域会话暴露固定 Agent 版本。
- Modify: `app/src/main/java/com/harnessapk/chat/ChatRepository.kt`：创建 Agent 会话并映射字段。
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`：装配 AgentRepository 和运行时上下文提供器。
- Modify: `app/src/main/java/com/harnessapk/session/SessionContextBuilder.kt`：接受额外 Agent system context。
- Modify: `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt`：执行时检索 Agent 证据、禁用 Agent 会话联网补写并注入上下文。

### Android UI

- Create: `app/src/main/java/com/harnessapk/ui/agent/AgentScreen.kt`：现有首页内的 Agent 列表、导入确认和开始对话。
- Create: `app/src/main/java/com/harnessapk/ui/agent/AgentUiState.kt`：无 Compose 依赖的状态转换。
- Modify: `app/src/main/java/com/harnessapk/ui/HomeUiState.kt`：新增 `MainMode.AGENT`。
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`：复用现有首页、TopAppBar、NavHost 和聊天路由接入 Agent。

### 测试

- Create: `app/src/test/java/com/harnessapk/agent/AgentBundleReaderTest.kt`。
- Create: `app/src/test/java/com/harnessapk/agent/AgentRetrievalTest.kt`。
- Create: `app/src/test/java/com/harnessapk/ui/agent/AgentUiStateTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/chat/ChatRepositoryTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/session/SessionContextBuilderTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt`。
- Modify: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`。

---

### Task 1: 桌面确定性构建器与 Codex Skill

**Interfaces:**
- Produces: `prepare_workspace(inputs, output_dir, agent_id, name, version) -> Path`。
- Produces: `validate_workspace(workspace) -> BuildReport`。
- Produces: `pack_workspace(workspace, output_dir, private_key_path) -> PackResult`，返回 `.hagent`、每份 `.hcorpus`、可选 `.hsource` 和 `.hbundle` 路径。
- Produces: `.hbundle` 根条目 `bundle-manifest.json`、`agent/*`、`corpora/*`、可选 `sources/*`、`checksums.json`、`signature.json`。

- [x] **Step 1: 写失败的 Python 测试**

```python
def test_prepare_and_pack_is_deterministic(self):
    first = build_fixture_bundle(self.temp / "first")
    second = build_fixture_bundle(self.temp / "second")
    self.assertEqual(sha256(first), sha256(second))

def test_rejects_source_without_extractable_text(self):
    with self.assertRaisesRegex(BuildError, "没有可提取文本"):
        prepare_workspace([empty_source], workspace, "agent-1", "测试", 1)
```

- [x] **Step 2: 运行测试确认 RED**

Run: `CODEX_PYTHON=/Users/tony/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_builder -v`

Expected: FAIL，原因是 `tools.agent_builder` 尚不存在。

- [x] **Step 3: 实现提取、分块、验证、签名和打包**

`bundle-manifest.json` 使用稳定 schema：

```json
{
  "schemaVersion": 1,
  "agent": {
    "id": "person.mao",
    "name": "毛泽东思想研究代理",
    "version": 1,
    "summary": "基于用户导入资料构建的模拟代理",
    "personaPath": "agent/persona.md",
    "worldviewPath": "agent/worldview.jsonl",
    "requiredCorpora": ["corpus.mao.selected"]
  },
  "corpora": [{
    "id": "corpus.mao.selected",
    "title": "选定资料",
    "sourceHash": "sha256",
    "chunksPath": "corpora/corpus.mao.selected/chunks.jsonl",
    "required": true
  }]
}
```

每条 chunk 写入 `id`、`sourceTitle`、`location`、`text`、`keywords`、`ngrams`。`pack` 同时生成核心 `.hagent`、按 corpus 拆分的 `.hcorpus`、用户显式要求时才生成的 `.hsource`，以及组合安装用 `.hbundle`。各包的 `checksums.json` 对除自身和 `signature.json` 外的全部条目按路径排序；签名算法固定为 Ed25519，签名对象是 canonical checksums bytes。

- [x] **Step 4: 实现 CLI 和 Skill**

```text
agent-builder prepare --agent-id ID --name NAME --version N --output WORKSPACE INPUT...
agent-builder validate WORKSPACE
agent-builder pack WORKSPACE --output DIST --key KEY
```

Skill 必须先执行 `prepare`，再让 Codex 分批补全 `persona.md`、`worldview.jsonl`、`examples.jsonl`、`eval.jsonl`，最后执行 `validate` 和 `pack`；任何 validation failure 都不能打包为可安装版本。

- [x] **Step 5: 运行 Python 测试确认 GREEN**

Run: `CODEX_PYTHON=/Users/tony/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_builder -v`

Expected: PASS，包含 TXT/MD/EPUB、稳定 chunk ID、路径安全、checksum 和签名测试；PDF 使用文本层 fixture 或 `pypdf` mock reader 验证。

- [x] **Step 6: 提交**

```bash
git add .codex/skills/agent-builder scripts/agent-builder.sh tools/agent_builder
git commit -m "功能：新增桌面智能体构建器"
```

### Task 2: Android 包读取与密码学校验

**Interfaces:**
- Produces: `AgentBundleReader.read(file: File): ParsedAgentBundle`。
- Produces: `AgentBundleReader.inspect(file: File): AgentImportPreview`。
- Produces: `JcaEd25519Verifier.verify(publicKey, payload, signature): Boolean`。

- [x] **Step 1: 写失败的包读取测试**

```kotlin
@Test fun readsValidSignedBundle() {
    val parsed = AgentBundleReader().read(validBundle())
    assertEquals("agent-1", parsed.manifest.agent.id)
    assertEquals(1, parsed.corpora.single().chunks.size)
}

@Test fun rejectsTraversalAndChecksumMismatch() {
    assertFailsWith<AgentBundleException> { reader.read(bundleWith("../escape")) }
    assertFailsWith<AgentBundleException> { reader.read(tamperedBundle()) }
}
```

- [x] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentBundleReaderTest`

Expected: FAIL，原因是读取器和模型尚不存在。

- [x] **Step 3: 实现安全读取器**

读取器必须拒绝绝对路径、`..`、符号链接声明、重复条目、超过 50,000 个条目、声明解压体积超过 4 GiB、未列入 checksums 的内容和未知可执行扩展；逐流计算 SHA-256，不把整个语料包读入单个 ByteArray。

- [x] **Step 4: 实现 Ed25519 校验**

将 32 字节 raw public key 包装为 RFC 8410 X.509 SubjectPublicKeyInfo，使用 `KeyFactory.getInstance("Ed25519")` 与 `Signature.getInstance("Ed25519")` 验证 canonical checksums bytes。算法不可用时返回明确“不支持该签名算法”，不能降级为跳过签名。

- [x] **Step 5: 运行测试确认 GREEN**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentBundleReaderTest`

Expected: PASS。

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agent app/src/test/java/com/harnessapk/agent/AgentBundleReaderTest.kt
git commit -m "功能：校验智能体本地包"
```

### Task 3: Room 数据、语料 FTS 和会话固定版本

**Interfaces:**
- Produces: `AgentDao.observeAgents(): Flow<List<AgentEntity>>`。
- Produces: `AgentDao.searchChunkKeys(corpusKeys, ftsQuery, limit): List<String>`。
- Changes: `ChatRepository.createConversation(title, projectId, agentId, agentVersion)`。

- [x] **Step 1: 写失败的会话与数据库测试**

```kotlin
@Test fun createConversationPinsAgentVersion() = runTest {
    val id = repository.createConversation("研究", agentId = "agent-1", agentVersion = 3)
    assertEquals("agent-1", repository.conversation(id)!!.agentId)
    assertEquals(3, repository.conversation(id)!!.agentVersion)
}
```

Instrumentation test 插入 Agent、版本、资料和 chunk 后，以中文 n-gram FTS 查询并断言返回正确 chunk。

- [x] **Step 2: 运行单元测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.chat.ChatRepositoryTest`

Expected: FAIL，原因是会话尚无 Agent 字段。

- [x] **Step 3: 实现实体、DAO 和 migration**

数据库升级为 11。`MIGRATION_10_11` 增加会话字段并创建 `agents`、`agent_versions`、`agent_corpora`、`agent_version_corpora`、`agent_chunks` 和 `agent_chunk_fts`。大正文只在 chunk 表与文件资产中存在，不复制到 Agent 主表。

- [x] **Step 4: 实现会话固定版本**

`agentId` 与 `agentVersion` 必须同时为空或同时有值；`ChatRepository.createConversation` 对不完整组合执行 `require`。普通会话行为保持不变。

- [x] **Step 5: 运行测试确认 GREEN**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.chat.ChatRepositoryTest`

Run: `./gradlew assembleDebug`

Expected: PASS；Room KSP schema validation 通过。

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/storage app/src/main/java/com/harnessapk/chat app/src/test/java/com/harnessapk/chat app/src/androidTest/java/com/harnessapk/storage
git commit -m "功能：持久化智能体与固定会话版本"
```

### Task 4: 本地安装、去重与 Top 8 检索

**Interfaces:**
- Produces: `AgentRepository.prepareImport(uri): AgentImportSession`。
- Produces: `AgentRepository.install(session): AgentInstallResult`。
- Produces: `AgentRepository.runtimeContext(agentId, version, query, limit = 8): AgentRuntimeContext`。

- [x] **Step 1: 写失败的检索测试**

```kotlin
@Test fun buildsStrictFirstPersonContextFromTopEvidence() = runTest {
    val context = repository.runtimeContext("agent-1", 1, "为什么要先调查", 8)
    assertTrue(context.systemPrompt.contains("第一人称"))
    assertTrue(context.systemPrompt.contains("不得使用模型通用知识"))
    assertEquals("chunk-investigation", context.evidence.first().chunkId)
}
```

- [x] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentRetrievalTest`

Expected: FAIL，原因是 Repository 尚不存在。

- [x] **Step 3: 实现 staging 和原子安装**

`prepareImport` 把 Content URI 流式复制到 `cacheDir/agent-staging/<uuid>.hbundle` 并完成预检。`install` 在数据库事务前把已验证文件原子移动到 `filesDir/agents/<agentId>/<version>/bundle.hbundle`；同版本同 SHA-256 幂等，同版本不同 SHA-256 拒绝，资料按 `corpusId + sourceHash` 去重。

- [x] **Step 4: 实现中文检索和严格上下文**

查询归一化为词项与中文 2-gram，FTS4 取候选后按关键词覆盖、连续词命中和 chunk 顺序稳定排序，最多返回 8 条。无证据时 system prompt 必须要求明确回答“当前资料不足”；有证据时每条以 `[资料 N] 标题 · 位置` 呈现。

- [x] **Step 5: 运行测试确认 GREEN**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentRetrievalTest`

Expected: PASS。

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agent/AgentRepository.kt app/src/test/java/com/harnessapk/agent/AgentRetrievalTest.kt
git commit -m "功能：安装并检索本地智能体资料"
```

### Task 5: 接入现有会话执行链路

**Interfaces:**
- Changes: `buildSessionOutgoingMessages(context, baseMessages, webSearchContext, agentSystemContext)`。
- Changes: `SendMessageUseCase` 注入 `agentContextProvider: suspend (conversationId, query) -> AgentRuntimeContext?`。

- [x] **Step 1: 写失败的 system message 测试**

```kotlin
@Test fun agentContextPrecedesProjectAndHistory() {
    val messages = buildSessionOutgoingMessages(
        context = projectContext,
        baseMessages = history,
        agentSystemContext = "严格资料上下文",
    )
    assertTrue(messages.first().text.startsWith("严格资料上下文"))
}
```

- [x] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.session.SessionContextBuilderTest`

Expected: FAIL，原因是尚无 Agent context 参数。

- [x] **Step 3: 实现执行时注入**

`SendMessageUseCase.execute` 根据 `Conversation.agentId + agentVersion` 和当前用户文本获取上下文。Agent 会话忽略 web search context，并将 native web search 设置为禁用；普通会话保持原行为。上下文必须随后台恢复重新检索，不依赖 Compose 临时状态。

- [x] **Step 4: 运行测试确认 GREEN**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.session.SessionContextBuilderTest --tests com.harnessapk.chat.SendMessageUseCaseSupportTest`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/session app/src/main/java/com/harnessapk/chat app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/test/java/com/harnessapk/session app/src/test/java/com/harnessapk/chat
git commit -m "功能：在原会话链路注入智能体资料"
```

### Task 6: 现有 Harness APK 中的智能体入口

**Interfaces:**
- Adds: `MainMode.AGENT("智能体")`。
- Produces: `AgentScreen(container, contentPadding, importRequestKey, onImportRequestConsumed, onStartConversation)`。

- [ ] **Step 1: 写失败的 UI 状态测试**

```kotlin
@Test fun agentModeUsesAgentTitleAndCreateAction() {
    assertEquals("智能体", topLevelTitle(MainMode.AGENT, "项目名"))
    assertTrue(homePrimaryAction(MainMode.AGENT) is HomePrimaryAction.ImportAgent)
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.HomeModeUiStateTest --tests com.harnessapk.ui.agent.AgentUiStateTest`

Expected: FAIL，原因是第三种模式和状态尚不存在。

- [ ] **Step 3: 实现列表与一次导入确认**

Agent 模式使用现有 TopAppBar 的加号触发 Android `OpenDocument`。选择后只展示一次确认弹窗：名称、版本、发布者指纹、核心包、资料包、总大小；确认后安装，取消则删除 staging。原始文件默认不导入。

- [ ] **Step 4: 实现开始对话**

READY Agent 行直接提供“开始对话”，调用现有 `ChatRepository.createConversation` 固定 active version 并导航到现有 `ChatScreen`。WAITING_FOR_CORPUS 禁用开始按钮并显示缺少资料，不增加第二个聊天页面。

- [ ] **Step 5: 运行测试与构建确认 GREEN**

Run: `./gradlew testDebugUnitTest`

Run: `./gradlew assembleDebug`

Expected: 全部 PASS，生成 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui app/src/test/java/com/harnessapk/ui
git commit -m "功能：在现有应用加入智能体模式"
```

### Task 7: 端到端验收与文档收口

**Interfaces:**
- Consumes: 前六项产物。
- Produces: 可被 APK 安装的 fixture `.hbundle`、构建报告和验收记录。

- [ ] **Step 1: 用桌面 CLI 构建最小 fixture**

Run: `scripts/agent-builder.sh prepare --agent-id fixture.researcher --name 资料研究代理 --version 1 --output build/agent-fixture app/src/test/resources/agent/source.md`

Run: `scripts/agent-builder.sh validate build/agent-fixture`

Run: `scripts/agent-builder.sh pack build/agent-fixture --output build/agent-dist --key build/agent-fixture/test-key.pem`

Expected: 生成签名 `.hbundle` 和通过的 `build-report.json`。

- [ ] **Step 2: 执行完整自动验证**

Run: `CODEX_PYTHON=/Users/tony/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 scripts/agent-builder.sh -m unittest discover -s tools/agent_builder/tests -v`

Run: `./gradlew testDebugUnitTest assembleDebug`

Run: `git diff --check`

Expected: 全部退出码为 0。

- [ ] **Step 3: 更新 README 使用入口**

README 只增加两段：桌面 Codex 中如何触发 `agent-builder` Skill，以及 `.hbundle` 在现有 APK 的“智能体”模式中如何导入。不复制完整设计规格。

- [ ] **Step 4: 提交验收收口**

```bash
git add README.md app/src/test/resources/agent
git commit -m "文档：补充智能体构建与导入说明"
```

---

## 自审结果

- 规格覆盖：实施包 1 的桌面构建、`.hagent/.hcorpus/.hsource/.hbundle` 产物、签名、本地 `.hbundle` 导入、Room、FTS、Agent 会话和现有 App 入口均有对应任务。
- 明确延期：远端 catalog、Android 独立 `.hagent/.hcorpus` 安装入口、二维码、私人同步、OCR 和来源展开式 UI 不在本计划；`.hbundle` 内部仍保持 Agent 与 corpus 的逻辑分层，后续可无损拆包。
- 类型一致：会话固定字段统一为 `agentId: String?` 与 `agentVersion: Int?`；桌面 manifest 与 Android reader 均使用 schema version 1。
- 测试边界：Python 验证确定性与包安全，JVM 验证解析/检索/上下文，Room KSP 与 Android instrumentation 覆盖真实 FTS 表，完整 APK 构建作为收口门槛。
