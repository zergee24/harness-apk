# 人格包 V2 与大语料推荐安装 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把桌面构建器升级为可处理约 1 GB 原始资料的 V2 蒸馏、去重、分片和推荐安装流水线，并让 Android 安全安装、补装和动态检索 V1/V2 人格包。

**Architecture:** 桌面侧将 V2 schema、文本准备、人物资产校验、资料分片、覆盖集合选择和签名打包拆成明确模块；独立资料包先生成，安装计划记录其真实哈希与体积，再生成 `.hagent` 和便利 `.hbundle`。Android 通过兼容 reader 识别 V1 平铺 bundle 与 V2 嵌套签名包，Room 12 用物理 chunk 与 corpus cross-reference 分离实现 `sourceHash + chunkId` 去重，单一 `AgentContextAssembler` 按意图和动态预算组装人物资产及证据。

**Tech Stack:** Python 3.12、标准库 ZIP/JSON/SimHash、`cryptography`、`pypdf`、Kotlin、Room 2.8.4、SQLite FTS4、Jetpack Compose、JUnit 4。

## Global Constraints

- 桌面构建环境是 Apple M4 Mac mini、macOS 26.x、`darwin/arm64`。
- 桌面能力继续以仓库根目录 Codex Skill + Python CLI 提供，不开发独立 GUI 或常驻服务。
- 用户自行选择并确认有权使用的 TXT、Markdown、EPUB 或文本 PDF；不下载、不内置受版权保护全文。
- `.hagent` 必须与 required `.hcorpus` 同时存在才可运行；`.hsource` 默认不安装且不参与检索。
- V2 包继续使用 canonical checksums、Ed25519 和安全 ZIP 规则；不得执行包内代码。
- 所有人物事实、立场、经历、关系和风格资产必须引用真实 chunk ID。
- 二手材料不得作为第一人称经历或语言风格的唯一证据。
- 去重不得合并不同时期、不同条件或相互冲突的观点。
- 推荐安装依据独有覆盖增益，不按文件数、原始字节数或手机剩余空间猜测。
- Skill 默认选择 `balanced` 推荐方案；Android 仅在空间不足或用户主动调整时增加一步。
- V1 包和既有固定版本会话继续可用；缺失 V2 资产时标记“基础人格”，不伪造内容。
- 阶段 B 把 Room 从 11 升级到 12；阶段 C 再从 12 升级到 13。
- 远端 catalog、OSS 分发、OCR 服务、向量数据库和远端 embedding 不在本计划范围。

---

## File Structure

### 桌面构建器

- Create: `tools/agent_builder/schema_v2.py`：V2 枚举、dataclass、JSON 编解码和 workspace 路径。
- Create: `tools/agent_builder/corpus_pipeline.py`：来源元数据、层级节点、上下文化 chunk、精确/近重复去重。
- Create: `tools/agent_builder/install_planner.py`：core evidence、来源/时期分片、覆盖矩阵和约束式推荐。
- Create: `tools/agent_builder/evaluation.py`：六类评测的离线检索与质量汇总。
- Modify: `tools/agent_builder/models.py`：扩展 `BuildReport`、`PackResult` 和包产物。
- Modify: `tools/agent_builder/builder.py`：协调 prepare/validate/pack，保留 V1 API 兼容层。
- Modify: `tools/agent_builder/cli.py`：增加 metadata、profile 和 report 输出。
- Modify: `.agents/skills/agent-builder/SKILL.md`：一次批量澄清、推荐摘要和默认选择。
- Modify: `tools/agent_builder/tests/test_builder.py`：现有 V1 回归。
- Create: `tools/agent_builder/tests/test_corpus_pipeline.py`。
- Create: `tools/agent_builder/tests/test_install_planner.py`。
- Create: `tools/agent_builder/tests/test_schema_v2.py`。
- Create: `tools/agent_builder/tests/test_evaluation.py`。

### Android 包与存储

- Create: `app/src/main/java/com/harnessapk/agent/AgentV2Models.kt`：identity、voice、stance、episode、example、install plan。
- Modify: `app/src/main/java/com/harnessapk/agent/AgentModels.kt`：schema 能力、资料包预览和安装 profile。
- Modify: `app/src/main/java/com/harnessapk/agent/AgentBundleReader.kt`：V1/V2 分发、嵌套签名包和独立 `.hcorpus`。
- Modify: `app/src/main/java/com/harnessapk/storage/AgentEntities.kt`：V2 资产列、物理 chunk 和 corpus-chunk cross-reference。
- Modify: `app/src/main/java/com/harnessapk/storage/AgentDao.kt`：包交叉引用、去重 chunk、分片过滤和多样性候选。
- Modify: `app/src/main/java/com/harnessapk/storage/AppDatabase.kt`：`MIGRATION_11_12`。
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`：注册 migration 和 assembler。
- Modify: `app/src/main/java/com/harnessapk/agent/AgentRepository.kt`：V1/V2 安装、独立补装、卸载约束和 coverage。

### Android 运行时与 UI

- Create: `app/src/main/java/com/harnessapk/agent/AgentRetrievalPolicy.kt`：意图分类、动态预算和多样性重排。
- Create: `app/src/main/java/com/harnessapk/agent/AgentContextAssembler.kt`：唯一运行时上下文入口。
- Modify: `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt`：只消费不可变 `AgentRuntimeContext`。
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`：人格空会话显示包内开场，不增加开场配置。
- Modify: `app/src/main/java/com/harnessapk/ui/agent/AgentPackagesScreen.kt`：推荐/轻量/完整/原文汇总、补装和空间提示。
- Modify: `app/src/main/java/com/harnessapk/ui/agent/AgentUiState.kt`：安装方案和调整决策纯状态。

### Android 测试

- Create: `app/src/test/java/com/harnessapk/agent/AgentV2BundleReaderTest.kt`。
- Create: `app/src/test/java/com/harnessapk/agent/AgentContextAssemblerTest.kt`。
- Create: `app/src/test/java/com/harnessapk/agent/AgentRetrievalPolicyTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/agent/AgentRetrievalTest.kt`。
- Modify: `app/src/test/java/com/harnessapk/ui/agent/AgentUiStateTest.kt`。
- Modify: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`。

---

### Task 1: V2 workspace schema 与人物资产契约

**Files:**
- Create: `tools/agent_builder/schema_v2.py`
- Modify: `tools/agent_builder/models.py`
- Modify: `tools/agent_builder/builder.py`
- Modify: `tools/agent_builder/cli.py`
- Create: `tools/agent_builder/tests/test_schema_v2.py`

**Interfaces:**
- Produces: `prepare_workspace_v2(inputs, output_dir, agent_id, name, version, source_catalog_path=None) -> Path`。
- Produces: `load_workspace_v2(workspace: Path) -> WorkspaceV2`。
- Produces: `SourceGenre`、`Authorship`、`InstallClass`、`SourceRecord`、`AgentAssetPaths`、`WorkspaceV2`。
- Preserves: 现有 `prepare_workspace(...)`、`validate_workspace(...)` 和 V1 测试。

- [ ] **Step 1: 写 V2 schema 失败测试**

```python
def test_prepare_v2_creates_all_runtime_asset_files(self):
    workspace = prepare_workspace_v2(
        [self.source],
        self.root / "workspace",
        agent_id="person.li-de-sheng",
        name="李德胜",
        version=2,
    )

    manifest = json.loads((workspace / "workspace.json").read_text("utf-8"))
    self.assertEqual(2, manifest["schemaVersion"])
    self.assertEqual(
        [
            "concepts.json",
            "episodes.jsonl",
            "eval.jsonl",
            "examples.jsonl",
            "identity.json",
            "openers.json",
            "persona.md",
            "voice.json",
            "worldview.jsonl",
        ],
        sorted(path.name for path in (workspace / "agent").iterdir()),
    )

def test_workspace_rejects_unknown_packaged_source_metadata(self):
    workspace = self.prepare_v2()
    report = validate_workspace_v2(workspace)
    self.assertIn("来源元数据仍有未确认项", report.errors)
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_schema_v2 -v`

Expected: FAIL，`schema_v2` 和 `prepare_workspace_v2` 尚不存在。

- [ ] **Step 3: 实现强类型 schema**

```python
class SourceGenre(StrEnum):
    ESSAY = "essay"
    SPEECH = "speech"
    CONVERSATION = "conversation"
    LETTER = "letter"
    INTERVIEW = "interview"
    MEMOIR = "memoir"
    SECONDARY = "secondary"
    UNKNOWN = "unknown"  # 仅允许出现在未发布 workspace


class Authorship(StrEnum):
    DIRECT = "direct"
    EDITED_DIRECT = "edited_direct"
    SECONDARY = "secondary"
    UNKNOWN = "unknown"  # 仅允许出现在未发布 workspace


class InstallClass(StrEnum):
    REQUIRED = "required"
    RECOMMENDED = "recommended"
    OPTIONAL = "optional"
    SOURCE = "source"


@dataclass(frozen=True)
class AgentAssetPaths:
    persona: str = "agent/persona.md"
    identity: str = "agent/identity.json"
    voice: str = "agent/voice.json"
    worldview: str = "agent/worldview.jsonl"
    episodes: str = "agent/episodes.jsonl"
    concepts: str = "agent/concepts.json"
    examples: str = "agent/examples.jsonl"
    openers: str = "agent/openers.json"
    eval: str = "agent/eval.jsonl"


@dataclass(frozen=True)
class SourceRecord:
    source_id: str
    title: str
    file_name: str
    stored_name: str
    source_hash: str
    format: str
    genre: str
    authorship: str
    period: str
    raw_size_bytes: int
    extracted_chars: int
```

`WorkspaceV2` 序列化固定使用 camelCase；读取时拒绝绝对路径、`..`、重复 source ID 和非 2 的 schemaVersion。

- [ ] **Step 4: 生成完整空资产模板和 metadata 汇总**

`prepare_workspace_v2` 必须创建：

```json
// identity.json
{"relationships":[],"roles":[],"selfNames":[],"timeHorizon":""}
```

```json
// voice.json
{"avoidPatterns":[],"defaultForm":"","evidence":[],"preferredTerms":[],"rhetoricalMoves":[],"sentenceRhythm":[]}
```

```json
// concepts.json
{"concepts":[]}
```

```json
// openers.json
{"alternatives":[],"default":""}
```

未提供 source catalog 时，`genre/authorship/period` 写 `unknown`；CLI 输出一份 `source-catalog.json`，Skill 在 Task 5 一次性向用户确认这些项。

- [ ] **Step 5: 运行 V1/V2 schema 测试**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_schema_v2 tools.agent_builder.tests.test_builder -v`

Expected: PASS；V1 确定性包测试不变，V2 空 workspace 因未完成语义资产而不可发布。

- [ ] **Step 6: 提交**

```bash
git add tools/agent_builder/schema_v2.py tools/agent_builder/models.py tools/agent_builder/builder.py tools/agent_builder/cli.py tools/agent_builder/tests/test_schema_v2.py
git commit -m "功能：定义人格包 V2 工作区"
```

### Task 2: 上下文化 chunk、层级索引和近重复压缩

**Files:**
- Create: `tools/agent_builder/corpus_pipeline.py`
- Modify: `tools/agent_builder/builder.py`
- Create: `tools/agent_builder/tests/test_corpus_pipeline.py`

**Interfaces:**
- Consumes: Task 1 的 `SourceRecord` 与现有 `ExtractedDocument`。
- Produces: `build_corpus_index(documents, source_records) -> CorpusIndexResult`。
- Produces: `HierarchyNode`、`ContextualChunk`、`DeduplicationStats`。
- Writes: `corpora/index/nodes.jsonl`、`corpora/index/chunks.jsonl`、`corpora/index/duplicates.jsonl`。

- [ ] **Step 1: 写去重、时期和层级失败测试**

```python
def test_exact_and_near_duplicates_share_one_physical_chunk(self):
    result = build_corpus_index(
        documents=[doc("a", "调查以后再下结论。"), doc("b", "调查之后，再下结论。")],
        source_records=[source("a", period="1926"), source("b", period="1926")],
    )

    self.assertEqual(1, len(result.chunks))
    self.assertEqual({"a", "b"}, set(result.chunks[0].source_aliases))
    self.assertEqual(1, result.stats.near_duplicate_count)

def test_same_words_in_different_periods_are_not_merged(self):
    result = build_corpus_index(
        documents=[doc("early", "组织形式应当调整。"), doc("late", "组织形式应当调整。")],
        source_records=[source("early", period="1926"), source("late", period="1945")],
    )
    self.assertEqual(2, len(result.chunks))

def test_chunk_contains_parent_path_and_short_context(self):
    chunk = build_fixture_index().chunks[0]
    self.assertEqual(["source-1", "chapter-1", "section-1"], chunk.parent_ids)
    self.assertIn("第一章", chunk.context)
    self.assertLessEqual(len(chunk.context), 320)
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_corpus_pipeline -v`

Expected: FAIL，corpus pipeline 尚不存在。

- [ ] **Step 3: 实现确定性去重键和 SimHash**

```python
def normalize_for_dedup(text: str) -> str:
    return re.sub(r"[\W_]+", "", unicodedata.normalize("NFKC", text)).lower()


def simhash64(text: str) -> int:
    features = _character_shingles(normalize_for_dedup(text), width=4)
    vector = [0] * 64
    for feature in features:
        digest = int.from_bytes(hashlib.sha256(feature.encode("utf-8")).digest()[:8], "big")
        for bit in range(64):
            vector[bit] += 1 if digest & (1 << bit) else -1
    return sum((1 << bit) for bit, value in enumerate(vector) if value >= 0)


def may_merge(left: ContextualChunk, right: ContextualChunk) -> bool:
    if left.period != right.period:
        return False
    if left.conflict_key != right.conflict_key:
        return False
    return (
        left.normalized_hash == right.normalized_hash
        or (left.simhash >> 48 == right.simhash >> 48 and (left.simhash ^ right.simhash).bit_count() <= 3)
    )
```

重复项合并为一个物理 chunk，同时在 `sourceAliases` 保留所有来源；不同 `period` 或 `conflictKey` 永不合并。

- [ ] **Step 4: 实现层级节点和上下文**

```python
@dataclass(frozen=True)
class ContextualChunk:
    id: str
    source_id: str
    source_hash: str
    source_title: str
    period: str
    genre: str
    authorship: str
    location: str
    parent_ids: list[str]
    context: str
    text: str
    keywords: list[str]
    ngrams: list[str]
    near_duplicate_group: str | None
    source_aliases: list[str]
    conflict_key: str
```

`context` 只包含来源名、卷/章/节路径、时期和作者性质，最多 320 字；节点摘要只用于路由，写入 `nodes.jsonl`，不得替代原始 chunk 证据。

- [ ] **Step 5: 运行大语料指标测试**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_corpus_pipeline -v`

Expected: PASS，并断言报告包含原始字节、提取字符/估算 token、去重前后 chunk、近重复比例、无法提取文件和元数据覆盖。

- [ ] **Step 6: 提交**

```bash
git add tools/agent_builder/corpus_pipeline.py tools/agent_builder/builder.py tools/agent_builder/tests/test_corpus_pipeline.py
git commit -m "功能：压缩重复语料并保留层级"
```

### Task 3: 人物资产证据校验与六类质量报告

**Files:**
- Create: `tools/agent_builder/evaluation.py`
- Modify: `tools/agent_builder/builder.py`
- Create: `tools/agent_builder/tests/test_evaluation.py`
- Modify: `tools/agent_builder/tests/test_schema_v2.py`

**Interfaces:**
- Consumes: Task 2 的 chunk 与 hierarchy node 索引。
- Produces: `validate_agent_assets(workspace, chunks_by_id) -> list[str]`。
- Produces: `evaluate_workspace(workspace) -> EvaluationReport`。
- Produces metrics: `grounding`、`stance`、`voice`、`temporal`、`diversity`、`global`。

- [ ] **Step 1: 写证据边界失败测试**

```python
def test_secondary_source_cannot_ground_voice_or_first_person_episode(self):
    workspace = complete_workspace()
    set_chunk_authorship(workspace, "chunk-voice", "secondary")

    report = validate_workspace_v2(workspace)

    self.assertIn("voice 只能引用 direct 或 edited_direct", "\n".join(report.errors))
    self.assertIn("第一人称 episode 不能只引用 secondary", "\n".join(report.errors))

def test_evaluation_reports_each_required_category(self):
    report = evaluate_workspace(complete_workspace())
    self.assertEqual(
        {"grounding", "stance", "voice", "temporal", "diversity", "global"},
        set(report.category_metrics),
    )
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_evaluation -v`

Expected: FAIL，尚无 V2 evaluator。

- [ ] **Step 3: 实现结构化资产校验**

校验器遍历下列字段中的每个 evidence ID：

```python
EVIDENCE_ASSETS = {
    "identity": ("relationships",),
    "voice": ("evidence",),
    "worldview": ("evidence",),
    "episodes": ("evidence",),
    "examples": ("evidence",),
    "eval": ("expectedEvidence",),
}
```

所有 ID 必须存在；`voice` 的全部证据必须为 `direct|edited_direct`；episode 若使用第一人称 `summary`，至少一条证据必须为直接材料；`generationType == "synthesized"` 是 examples 合成回答的必填值。

- [ ] **Step 4: 实现分层评测与报告门槛**

```python
@dataclass(frozen=True)
class CategoryMetric:
    total: int
    passed: int
    rate: float


@dataclass(frozen=True)
class EvaluationReport:
    category_metrics: dict[str, CategoryMetric]
    by_period: dict[str, CategoryMetric]
    by_authorship: dict[str, CategoryMetric]
    by_corpus: dict[str, CategoryMetric]
```

发布门槛固定为：

```python
MINIMUM_EVAL_COUNTS = {
    "grounding": 20,
    "stance": 30,
    "voice": 20,
    "temporal": 12,
    "diversity": 10,
    "global": 8,
}
MIN_GROUNDING_RATE = 0.85
MIN_STANCE_RATE = 1.0
```

每个 required/recommended corpus 至少两道可归因检索题；报告分别输出事实、立场、对话资料和风格可用度，不再用单一 Top 8 代替。

- [ ] **Step 5: 运行评测测试确认 GREEN**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_evaluation tools.agent_builder.tests.test_schema_v2 -v`

Expected: PASS；未知 evidence、二手 voice、缺失时期、单一来源垄断和不足题量均有具体错误。

- [ ] **Step 6: 提交**

```bash
git add tools/agent_builder/evaluation.py tools/agent_builder/builder.py tools/agent_builder/tests/test_evaluation.py tools/agent_builder/tests/test_schema_v2.py
git commit -m "功能：校验人物资产与分层评测"
```

### Task 4: Corpus 分片、core evidence 和推荐安装集合

**Files:**
- Create: `tools/agent_builder/install_planner.py`
- Modify: `tools/agent_builder/builder.py`
- Modify: `tools/agent_builder/models.py`
- Create: `tools/agent_builder/tests/test_install_planner.py`

**Interfaces:**
- Produces: `plan_corpus_shards(workspace) -> list[CorpusShard]`。
- Produces: `choose_install_profiles(shards) -> InstallPlan`。
- Produces profiles: `lite`、`balanced`、`complete`、`source`。
- Produces: `pack_workspace_v2(workspace, output_dir, private_key_path, profile_id="balanced", emit_sources=False) -> PackResult`。

- [ ] **Step 1: 写集合覆盖和两阶段打包失败测试**

```python
def test_balanced_profile_covers_core_and_unique_dialogue_gain(self):
    plan = choose_install_profiles(
        [
            shard("core", REQUIRED, {"identity", "stance"}),
            shard("dialogue", RECOMMENDED, {"voice", "period-late"}),
            shard("duplicate-background", OPTIONAL, {"stance"}),
        ]
    )

    self.assertEqual(["core"], plan.profile("lite").packages)
    self.assertEqual(["core", "dialogue"], plan.profile("balanced").packages)
    self.assertNotIn("duplicate-background", plan.profile("balanced").packages)

def test_pack_records_actual_signed_package_sizes_and_hashes(self):
    result = pack_workspace_v2(complete_workspace(), self.dist, self.key)
    plan = json.loads(result.install_plan.read_text("utf-8"))
    for package in plan["packages"]:
        path = self.dist / package["fileName"]
        self.assertEqual(path.stat().st_size, package["sizeBytes"])
        self.assertEqual(sha256(path), package["sha256"])
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_install_planner -v`

Expected: FAIL，安装 planner 与 V2 pack 尚不存在。

- [ ] **Step 3: 实现物理分片和 core evidence**

```python
@dataclass(frozen=True)
class CorpusShard:
    id: str
    file_name: str
    source_ids: list[str]
    periods: set[str]
    genres: set[str]
    authorships: set[str]
    coverage_gain: set[str]
    install_class: str
    reason: str
    chunk_ids: list[str]
```

分片顺序固定：

1. 收集 `persona/identity/voice/worldview/episodes/examples/eval` 的全部 evidence。
2. 复制 evidence chunk、必要父节点和来源 metadata 到唯一 `core-evidence.hcorpus`。
3. 其余 chunk 按来源集合和时期分片；单一来源过大时按卷/章切分并保留共同 `sourceId`。
4. 主题只写标签，不作为跨来源复制边界。
5. `.hsource` 按来源文件或卷独立输出。

- [ ] **Step 4: 实现约束式推荐和无循环两阶段 pack**

```python
def choose_balanced(shards: list[CorpusShard]) -> list[str]:
    selected = [item for item in shards if item.install_class == InstallClass.REQUIRED]
    uncovered = required_coverage_dimensions(shards) - combined_gain(selected)
    candidates = [item for item in shards if item.install_class != InstallClass.REQUIRED]
    while uncovered:
        best = max(candidates, key=lambda item: recommendation_score(item, uncovered, selected))
        unique_gain = best.coverage_gain & uncovered
        if not unique_gain:
            break
        selected.append(best)
        candidates.remove(best)
        uncovered -= unique_gain
    return [item.id for item in selected]
```

打包顺序必须是：

```text
独立 hcorpus/hsource -> 读取真实 size+sha256 -> install-plan.json
-> 签名 hagent -> 把签名 hagent 与所选签名 hcorpus 原样放入 packages/
-> 签名 <agent>-<profile>.hbundle -> 外部 build report
```

便利 bundle 内不展开子包，避免 Android 对两套内容计算不同哈希。

- [ ] **Step 5: 运行 planner 和确定性测试**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_install_planner tools.agent_builder.tests.test_builder -v`

Expected: PASS；默认输出 `<agent>-recommended.hbundle`、全部独立 `.hcorpus`、可选 `.hsource`、build report 和 install plan。

- [ ] **Step 6: 提交**

```bash
git add tools/agent_builder/install_planner.py tools/agent_builder/builder.py tools/agent_builder/models.py tools/agent_builder/tests/test_install_planner.py
git commit -m "功能：生成大语料推荐安装组合"
```

### Task 5: Skill 一次澄清、推荐摘要和默认执行

**Files:**
- Modify: `.agents/skills/agent-builder/SKILL.md`
- Modify: `tools/agent_builder/cli.py`
- Test: `tools/agent_builder/tests/test_schema_v2.py`

**Interfaces:**
- Adds CLI: `prepare-v2`、`validate`、`recommend`、`pack --profile`。
- Default: `pack --profile balanced`。
- Produces: 人类可读推荐摘要和机器可读 `install-plan.json`。

- [ ] **Step 1: 写 CLI 默认行为失败测试**

```python
def test_pack_defaults_to_balanced_profile(self):
    args = build_parser().parse_args(["pack", "workspace", "--output", "dist"])
    self.assertEqual("balanced", args.profile)

def test_recommend_prints_one_summary_not_per_book_questions(self):
    output = format_recommendation_summary(fixture_install_plan())
    self.assertEqual(1, output.count("推荐安装（默认）"))
    self.assertIn("轻量", output)
    self.assertIn("完整证据", output)
    self.assertIn("包含原文", output)
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `scripts/agent-builder.sh -m unittest tools.agent_builder.tests.test_schema_v2 -v`

Expected: FAIL，CLI 尚无 `profile` 和推荐摘要。

- [ ] **Step 3: 实现固定 CLI**

```text
agent-builder prepare-v2 INPUT... --agent-id ID --name NAME --version N --output WORKSPACE
agent-builder validate WORKSPACE
agent-builder recommend WORKSPACE
agent-builder pack WORKSPACE --output DIST --key KEY --profile {lite,balanced,complete,source}
```

`--profile` 默认 `balanced`；`source` profile 自动设置 `emit_sources=True`，但不把原文写入运行时 corpus。

现有 V1 `pack --include-sources` 参数继续接受，并映射到原 V1 `include_sources=True`；不能因 V2 profile 参数破坏已有脚本。

- [ ] **Step 4: 重写 Skill 决策流程**

Skill 必须按以下顺序执行：

```text
确认输入权利和路径
-> prepare-v2
-> 把 source-catalog.json 中全部 unknown 项合并成一个问题
-> 完成人物资产
-> validate
-> recommend
-> 只展示一次 balanced 默认和 lite/complete/source 三个替代方案
-> 用户未回答、说“按建议”或“自动”时直接 pack balanced
-> 报告 Android 应安装的 recommended.hbundle
```

Skill 必须明确说明推荐依据是身份证据、时期、体裁、直接作者材料和独有覆盖，不猜测手机空间；私钥、原文和构建 workspace 不进入 Git。

- [ ] **Step 5: 运行 CLI 与 Skill 静态验证**

Run: `scripts/agent-builder.sh --help`

Run: `scripts/agent-builder.sh pack --help`

Run: `rg -n "推荐安装|轻量|完整证据|包含原文|用户未回答|按建议|自动" .agents/skills/agent-builder/SKILL.md`

Expected: 命令退出码为 0；Skill 命中全部交互规则，且不包含逐本连续确认流程。

- [ ] **Step 6: 提交**

```bash
git add .agents/skills/agent-builder/SKILL.md tools/agent_builder/cli.py tools/agent_builder/tests/test_schema_v2.py
git commit -m "功能：默认推荐人格资料安装方案"
```

### Task 6: Android V1/V2 reader 与 Room 11 -> 12 去重存储

**Files:**
- Create: `app/src/main/java/com/harnessapk/agent/AgentV2Models.kt`
- Modify: `app/src/main/java/com/harnessapk/agent/AgentModels.kt`
- Modify: `app/src/main/java/com/harnessapk/agent/AgentBundleReader.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/AgentEntities.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/AgentDao.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/AppDatabase.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Create: `app/src/test/java/com/harnessapk/agent/AgentV2BundleReaderTest.kt`
- Modify: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`

**Interfaces:**
- Produces: `AgentBundleReader.readPackage(file: File): ParsedAgentPackage`。
- Produces: `ParsedAgentPackage.V1Bundle`、`V2Bundle`、`V2Corpus`、`V2Source`。
- Produces: `AgentInstallPlan`、`AgentIdentityAsset`、`AgentVoiceAsset`、`AgentEpisode`、`AgentExample`。
- Changes: Room version `11 -> 12`。

- [ ] **Step 1: 写 V2 解析与迁移失败测试**

```kotlin
@Test
fun readsNestedSignedV2BundleAndVerifiesDeclaredHashAndSize() {
    val parsed = reader.readPackage(validV2Bundle()) as ParsedAgentPackage.V2Bundle
    assertEquals("balanced", parsed.installPlan.recommendedProfile)
    assertEquals(listOf("corpus-core", "corpus-dialogue"), parsed.selectedCorpusIds)
}

@Test
fun rejectsUndeclaredOrMismatchedNestedCorpus() {
    assertThrows(AgentBundleException::class.java) { reader.readPackage(bundleWithUndeclaredCorpus()) }
    assertThrows(AgentBundleException::class.java) { reader.readPackage(bundleWithWrongNestedSize()) }
}
```

Instrumentation migration test 从真实 version 11 fixture 打开 version 12，并断言会话、消息、Markdown link、V1 agent/version/corpus/chunk 全部保留。

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentV2BundleReaderTest`

Expected: FAIL，V2 模型和 package 分发不存在。

- [ ] **Step 3: 实现 V2 模型与 reader 分发**

```kotlin
sealed interface ParsedAgentPackage {
    data class V1Bundle(val bundle: ParsedAgentBundle) : ParsedAgentPackage
    data class V2Bundle(
        val agent: AgentPackageV2,
        val installPlan: AgentInstallPlan,
        val selectedCorpusIds: List<String>,
        val nestedPackages: List<VerifiedNestedPackage>,
    ) : ParsedAgentPackage
    data class V2Corpus(val corpus: AgentCorpusPackageV2) : ParsedAgentPackage
    data class V2Source(val source: AgentSourcePackageV2) : ParsedAgentPackage
}
```

reader 先执行现有外层 ZIP/checksum/Ed25519 校验，再按 `schemaVersion/type` 分发；V2 bundle 的 `packages/*` 子包逐个落临时文件后执行完整签名校验，并与 install plan 的 `fileName/sha256/sizeBytes` 精确比对。

- [ ] **Step 4: 实现 version 12 物理 chunk 模型**

`AgentVersionEntity` 增加：

```kotlin
val identityJson: String = "",
val voiceJson: String = "",
val episodesJsonl: String = "",
val conceptsJson: String = "",
val examplesJsonl: String = "",
val openersJson: String = "",
val installPlanJson: String = "",
val lastEvidenceExpandedAt: Long? = null,
```

`AgentChunkEntity.chunkKey` 改为 `"${sourceHash}:${chunkId}"`，并保留：

```kotlin
val sourceHash: String,
val chunkId: String,
val sourceId: String,
val sourceTitle: String,
val period: String,
val genre: String,
val authorship: String,
val location: String,
val parentPath: String,
val contextText: String,
val text: String,
val keywordsText: String,
val nearDuplicateGroup: String?,
```

新增：

```kotlin
@Entity(
    tableName = "agent_corpus_chunks",
    primaryKeys = ["corpusId", "corpusHash", "chunkKey"],
)
data class AgentCorpusChunkCrossRef(
    val corpusId: String,
    val corpusHash: String,
    val chunkKey: String,
)
```

`AgentVersionCorpusCrossRef` 增加：

```kotlin
val installClass: String = AgentInstallClass.REQUIRED.name,
val packageSha256: String = "",
val packageSizeBytes: Long = 0L,
val installedAt: Long = 0L,
```

层级路由使用独立表：

```kotlin
@Entity(tableName = "agent_hierarchy_nodes")
data class AgentHierarchyNodeEntity(
    @PrimaryKey val nodeKey: String,
    val corpusId: String,
    val corpusHash: String,
    val nodeId: String,
    val parentId: String?,
    val title: String,
    val summary: String,
    val period: String,
    val keywordsText: String,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "agent_hierarchy_node_fts")
data class AgentHierarchyNodeFtsEntity(
    val nodeKey: String,
    val corpusKey: String,
    val searchableText: String,
)
```

原文包只保存本地文件和版本引用：

```kotlin
@Entity(tableName = "agent_sources")
data class AgentSourceEntity(
    @PrimaryKey val sourceHash: String,
    val sourceId: String,
    val title: String,
    val filePath: String,
    val sizeBytes: Long,
    val installedAt: Long,
)

@Entity(
    tableName = "agent_version_sources",
    primaryKeys = ["agentId", "version", "sourceHash"],
)
data class AgentVersionSourceCrossRef(
    val agentId: String,
    val version: Int,
    val sourceHash: String,
    val packageSha256: String,
)
```

`MIGRATION_11_12` 创建 source、hierarchy、hierarchy FTS 和 cross-reference 表，通过新表复制旧 chunk，生成 corpus-chunk cross-reference，再替换旧表；V1 缺失字段使用空字符串，旧 `chunkKey` 映射为 `sourceHash:chunkId`。

- [ ] **Step 5: 运行 reader、Room 和 V1 回归**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentV2BundleReaderTest --tests com.harnessapk.agent.AgentBundleReaderTest`

Run: `./gradlew connectedDebugAndroidTest --tests com.harnessapk.storage.AppDatabaseTest`

Expected: PASS；迁移保留全部已有数据，同一 `sourceHash+chunkId` 被多个 corpus 引用时 `agent_chunks` 只有一行。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agent/AgentV2Models.kt app/src/main/java/com/harnessapk/agent/AgentModels.kt app/src/main/java/com/harnessapk/agent/AgentBundleReader.kt app/src/main/java/com/harnessapk/storage/AgentEntities.kt app/src/main/java/com/harnessapk/storage/AgentDao.kt app/src/main/java/com/harnessapk/storage/AppDatabase.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/test/java/com/harnessapk/agent/AgentV2BundleReaderTest.kt app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt
git commit -m "功能：安装并去重人格包 V2"
```

### Task 7: V2 安装、独立补装与资料移除约束

**Files:**
- Modify: `app/src/main/java/com/harnessapk/agent/AgentRepository.kt`
- Modify: `app/src/main/java/com/harnessapk/agent/AgentModels.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/AgentDao.kt`
- Modify: `app/src/test/java/com/harnessapk/agent/AgentRetrievalTest.kt`

**Interfaces:**
- Produces: `preparePackageImport(sourceName, openInputStream): AgentPackageImportSession`。
- Produces: `installPackage(session, profileId = "balanced"): AgentInstallResult`。
- Produces: `removeOptionalCorpus(agentId, version, corpusId): AgentCorpusRemovalResult`。
- Produces: `setAgentEnabled(agentId, enabled)` 和 `removeVersion(agentId, version): AgentVersionRemovalResult`。
- Changes: `AgentEvidence` 增加稳定 `chunkKey: String`。
- Preserves: `prepareImport/install` 作为 V1 调用兼容包装。

- [ ] **Step 1: 写补装和删除失败测试**

```kotlin
@Test
fun installingCompatibleCorpusExpandsEvidenceWithoutChangingVersion() = runTest {
    repository.installPackage(v2AgentSession())
    repository.installPackage(dialogueCorpusSession())

    val agent = repository.agent("a1")!!
    assertEquals(2, agent.activeVersion)
    assertEquals(2, agent.installedCorpusCount)
}

@Test
fun requiredCorpusCannotBeRemoved() = runTest {
    val result = repository.removeOptionalCorpus("a1", 2, "corpus-core")
    assertEquals(AgentCorpusRemovalResult.REQUIRED, result)
}

@Test
fun referencedOptionalCorpusIsNotPhysicallyDeleted() = runTest {
    dao.referencedChunkKeys = setOf("hash:chunk-1")
    val result = repository.removeOptionalCorpus("a1", 2, "corpus-dialogue")
    assertEquals(AgentCorpusRemovalResult.REFERENCED, result)
}

@Test
fun referencedVersionCanBeDisabledButNotRemoved() = runTest {
    conversationDao.versionReferenceCount = 1

    repository.setAgentEnabled("a1", enabled = false)
    val removal = repository.removeVersion("a1", 2)

    assertEquals(AgentStatus.DISABLED, repository.agent("a1")!!.status)
    assertEquals(AgentVersionRemovalResult.REFERENCED, removal)
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentRetrievalTest`

Expected: FAIL，Repository 只接受单一 V1 bundle。

- [ ] **Step 3: 实现统一安装会话**

```kotlin
data class AgentPackageImportSession(
    val id: String,
    val stagedFile: File,
    val parsed: ParsedAgentPackage,
    val preview: AgentPackageImportPreview,
)
```

V2 bundle 只安装其 `selectedCorpusIds`；required 缺失时状态为 `WAITING_FOR_CORPUS`，不能开始会话。独立 corpus 必须匹配现有 `agentId/version/publisherFingerprint` 和签名 install plan；未声明 corpus、哈希或体积不匹配均拒绝。

独立 `.hsource` 按 `sourceHash` 去重复制到 `filesDir/agents/sources/<sourceHash>/`，写入 `agent_sources` 和 version cross-reference；不创建 FTS row，也不进入 `AgentContextAssembler`。

安装事务在 READY 前必须验证 `identity/voice/worldview/episodes/examples/eval` 的每个 evidence ID 均能由本次已安装 required corpus 解析；任一缺失时抛出包含 asset 类型和 evidence ID 的 `AgentBundleException`。optional corpus 不得成为核心人物评测通过的隐式依赖。

- [ ] **Step 4: 实现引用安全删除**

删除 optional corpus 前依次检查：

```text
installClass != required
-> 是否仍被其他版本引用
-> 其 chunk 是否被已持久化 AGENT_SOURCES part 引用
-> 删除 corpus cross-reference
-> 仅删除不再被任何 corpus cross-reference 引用的物理 chunk 与 FTS row
```

历史消息只保存稳定来源显示和 chunk key metadata，不重写；`agentVersion` 不改变。

`appendAgentSourcesPart` 从本阶段开始把使用过的 `chunkKey` 写入 part metadata：

```kotlin
metadata = mapOf(
    "chunkKeys" to evidence.joinToString("\u001f", transform = AgentEvidence::chunkKey),
)
```

旧 V1 消息没有该 metadata 时，涉及的旧 corpus 保守视为不可自动移除。

停用把 `AgentStatus` 更新为 `DISABLED`，因此新会话建议和选择器不再显示该身份；既有会话仍按固定 version 读取。物理删除 version 前用 `ConversationDao.countByAgentVersion(agentId, version)` 检查引用，非 0 返回 `REFERENCED`；最后一个 version 删除后才允许清理 agent 主记录。

```kotlin
enum class AgentStatus {
    READY,
    WAITING_FOR_CORPUS,
    DRAFT,
    DISABLED,
    FAILED,
}
```

- [ ] **Step 5: 运行安装回归**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentRetrievalTest --tests com.harnessapk.agent.AgentV2BundleReaderTest`

Expected: PASS；V1 幂等安装、V2 推荐安装、独立 corpus/source 补装、required 禁删、被引用禁删、停用和无引用卸载均通过。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agent/AgentRepository.kt app/src/main/java/com/harnessapk/agent/AgentModels.kt app/src/main/java/com/harnessapk/storage/AgentDao.kt app/src/test/java/com/harnessapk/agent/AgentRetrievalTest.kt
git commit -m "功能：补装并管理人格资料分片"
```

### Task 8: AgentContextAssembler、动态预算和多样性重排

**Files:**
- Create: `app/src/main/java/com/harnessapk/agent/AgentRetrievalPolicy.kt`
- Create: `app/src/main/java/com/harnessapk/agent/AgentContextAssembler.kt`
- Modify: `app/src/main/java/com/harnessapk/agent/AgentRepository.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- Create: `app/src/test/java/com/harnessapk/agent/AgentRetrievalPolicyTest.kt`
- Create: `app/src/test/java/com/harnessapk/agent/AgentContextAssemblerTest.kt`

**Interfaces:**
- Produces: `AgentQueryIntent`。
- Produces: `AgentRetrievalBudget`。
- Produces: `AgentContextAssembler.assemble(request: AgentContextRequest): AgentRuntimeContext?`。
- Produces: `AgentRepository.opening(agentId, version): String?`，只用于空会话。
- Changes: `AgentRuntimeContext` 增加 identity、relationship placeholder、stances、episodes、examples、evidence 和 diagnostics，但调用方仍只读取 `systemPrompt/evidence`。

- [ ] **Step 1: 写意图和多样性失败测试**

```kotlin
@Test
fun greetingUsesIdentityWithoutCorpusRetrieval() {
    val policy = retrievalPolicy("你好，我们接着昨天的话说")
    assertEquals(AgentQueryIntent.RELATIONSHIP, policy.intent)
    assertEquals(0, policy.budget.maxEvidenceChunks)
}

@Test
fun temporalQuestionRequiresMultiplePeriods() {
    val policy = retrievalPolicy("你早期和晚期对此看法有什么变化")
    assertEquals(AgentQueryIntent.TEMPORAL, policy.intent)
    assertTrue(policy.requirePeriodDiversity)
}

@Test
fun rerankerLimitsOneSourceAndNearDuplicateGroup() {
    val selected = rerankEvidence(candidates(), budget(maxChunks = 6))
    assertTrue(selected.groupingBy { it.sourceHash }.eachCount().values.max() <= 2)
    assertEquals(selected.size, selected.map { it.nearDuplicateGroup ?: it.chunkId }.distinct().size)
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentRetrievalPolicyTest --tests com.harnessapk.agent.AgentContextAssemblerTest`

Expected: FAIL，policy 和 assembler 尚不存在。

- [ ] **Step 3: 实现固定意图与预算**

```kotlin
enum class AgentQueryIntent {
    RELATIONSHIP,
    EXACT_FACT,
    STANCE_METHOD,
    TEMPORAL,
    GLOBAL,
}

data class AgentRetrievalBudget(
    val maxStances: Int,
    val maxEpisodes: Int,
    val maxExamples: Int,
    val maxEvidenceChunks: Int,
    val maxEvidenceChars: Int,
    val maxChunksPerSource: Int,
    val requirePeriodDiversity: Boolean,
)
```

预算映射固定为：

```kotlin
RELATIONSHIP -> AgentRetrievalBudget(0, 0, 0, 0, 0, 0, false)
EXACT_FACT -> AgentRetrievalBudget(1, 0, 0, 4, 4_800, 2, false)
STANCE_METHOD -> AgentRetrievalBudget(3, 1, 2, 6, 7_200, 2, false)
TEMPORAL -> AgentRetrievalBudget(4, 2, 1, 8, 9_600, 2, true)
GLOBAL -> AgentRetrievalBudget(6, 2, 2, 12, 14_400, 2, true)
```

- [ ] **Step 4: 实现唯一 assembler**

```kotlin
data class AgentContextRequest(
    val conversationId: String,
    val query: String,
    val sessionContext: SessionRequestContext?,
    val conversationMemory: ConversationMemory?,
)

class AgentContextAssembler(
    private val chatRepository: ChatRepository,
    private val agentRepository: AgentRepository,
    private val relationshipMemoryProvider: suspend (String) -> List<String> = { emptyList() },
) {
    suspend fun assemble(request: AgentContextRequest): AgentRuntimeContext? {
        val conversation = chatRepository.conversation(request.conversationId) ?: return null
        val agentId = conversation.agentId ?: return null
        val version = conversation.agentVersion ?: return null
        return agentRepository.runtimeContextV2(
            agentId = agentId,
            version = version,
            query = request.query,
            conversationMemory = request.conversationMemory,
            relationshipMemory = relationshipMemoryProvider(agentId),
            projectContext = request.sessionContext,
        )
    }
}
```

V1 adapter 只提供 persona/worldview/evidence；V2 依次组装身份、事实边界、会话摘要、空的关系记忆 provider、可选项目、stances、最多两个 episodes、最多两个 examples、无编号证据。

`ChatScreen` 在 `messages.isEmpty()` 时读取固定 version 的 `openers.default` 并作为非持久化空状态文案显示；没有 opener 的 V1 继续显示现有空状态。alternatives 只随包保留，本阶段不增加“换一个”按钮，也不把 opener 注入每轮 system prompt。

- [ ] **Step 5: 接入发送链路并运行回归**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.agent.AgentRetrievalPolicyTest --tests com.harnessapk.agent.AgentContextAssemblerTest --tests com.harnessapk.chat.SendMessageUseCaseSupportTest`

Expected: PASS；普通会话不调用 assembler，关系问候不检索 corpus，时期问题覆盖至少两个时期，全局问题先选 hierarchy node 再下钻 chunk。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/agent/AgentRetrievalPolicy.kt app/src/main/java/com/harnessapk/agent/AgentContextAssembler.kt app/src/main/java/com/harnessapk/agent/AgentRepository.kt app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/test/java/com/harnessapk/agent/AgentRetrievalPolicyTest.kt app/src/test/java/com/harnessapk/agent/AgentContextAssemblerTest.kt
git commit -m "功能：按问题动态组装人格上下文"
```

### Task 9: Android 推荐安装 UI、空间校验与阶段 B 验收

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/agent/AgentPackagesScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/agent/AgentUiState.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/agent/AgentUiStateTest.kt`
- Modify: `README.md`

**Interfaces:**
- Produces: `installationDecision(plan, availableBytes, requestedProfile): AgentInstallationDecision`。
- Produces: `sealed interface AgentInstallationDecision`，包含 `InstallDirectly`、`ShowAdjustment`、`BlockMissingRequired`。
- Consumes: Task 4 签名 install plan 和 Task 7 安装 API。

- [ ] **Step 1: 写无冗余交互失败测试**

```kotlin
@Test
fun balancedProfileInstallsDirectlyWhenSpaceIsEnough() {
    val decision = installationDecision(plan(), availableBytes = 2_000_000_000, requestedProfile = null)
    assertEquals(AgentInstallationDecision.InstallDirectly("balanced"), decision)
}

@Test
fun lowSpaceSuggestsLiteWithoutSilentlyDroppingPackages() {
    val decision = installationDecision(plan(), availableBytes = 100_000_000, requestedProfile = null)
    assertEquals(
        AgentInstallationDecision.ShowAdjustment(
            selectedProfileId = "balanced",
            suggestedProfileId = "lite",
            reason = "推荐安装空间不足",
        ),
        decision,
    )
}

@Test
fun missingRequiredNeverStartsConversation() {
    assertFalse(canStartAgent(agent(status = AgentStatus.WAITING_FOR_CORPUS)))
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.agent.AgentUiStateTest`

Expected: FAIL，当前安装页只有单包确认。

- [ ] **Step 3: 实现安装摘要和调整入口**

默认确认页只显示：

```text
推荐安装
人物身份 · 必装
核心证据 · 覆盖全部核心立场与评测
对话资料 · 补齐谈话和书信语言
总大小
[调整资料] [安装]
```

空间充足时“安装”直接执行 balanced；空间不足时保留原选择并显示“空间不足，建议轻量”，用户进入调整页后用 segmented control 选择轻量/推荐/完整证据/包含原文。不得逐 corpus 连续弹窗。

- [ ] **Step 4: 显示补装、覆盖与降级状态**

人物详情显示 schema、基础/完整人格、required/recommended/optional/source 覆盖、最近证据扩展时间。独立 `.hcorpus` 从系统文件导入后只更新覆盖；`.hsource` 标记“仅阅读核验，不参与回答”。

当 source metadata 中不存在 `speech|conversation|letter|interview` 且 `authorship` 为 `direct|edited_direct` 的组合时，安装预览固定显示“书面人格，对话还原度有限”；该提示不阻断安装。

- [ ] **Step 5: 执行完整自动验证**

Run: `scripts/agent-builder.sh -m unittest discover -s tools/agent_builder/tests -v`

Run: `./gradlew testDebugUnitTest assembleDebug`

Run: `./gradlew connectedDebugAndroidTest`

Run: `git diff --check`

Expected: 全部退出码 0；若无设备，记录该项未执行并在真实设备接入后补跑，不能静默跳过。

- [ ] **Step 6: 用 fixture 构建推荐 V2 包**

Run:

```bash
scripts/agent-builder.sh prepare-v2 app/src/test/resources/agent/source.md --agent-id fixture.researcher --name 资料研究代理 --version 2 --output build/agent-v2-fixture
```

测试 fixture 必须由测试 helper 写入完整 source metadata、人物资产和评测后执行：

```bash
scripts/agent-builder.sh validate build/agent-v2-fixture
scripts/agent-builder.sh recommend build/agent-v2-fixture
scripts/agent-builder.sh pack build/agent-v2-fixture --output build/agent-v2-dist --key build/agent-v2-fixture/test-key.pem
```

Expected: `fixture.researcher-v2-recommended.hbundle` 可由 APK 安装。真实李德胜 V2 发布在执行本任务时通过 Skill 重新选择原始资料和现有发布者私钥；这些外部输入不写入仓库或实施计划。

- [ ] **Step 7: 更新 README 并提交**

README 说明默认发送 `*-recommended.hbundle` 到 Android；独立 `.hcorpus` 用于后续补装，`.hsource` 仅在用户要求时生成。

```bash
git add app/src/main/java/com/harnessapk/ui/agent/AgentPackagesScreen.kt app/src/main/java/com/harnessapk/ui/agent/AgentUiState.kt app/src/test/java/com/harnessapk/ui/agent/AgentUiStateTest.kt README.md
git commit -m "验证：完成人格包 V2 推荐安装"
```

---

## Self-Review

- Spec coverage: V2 九类人物资产、来源类型、上下文化 chunk、层级索引、去重、冲突时期、core evidence、来源/时期分片、四个安装 profile、准确体积/哈希、Skill 默认推荐、Android 空间判断、独立补装、动态预算和 V1 降级均有任务。
- Database sequencing: 本阶段唯一 migration 是 `11 -> 12`，负责 V2 asset columns、物理 chunk 去重和 corpus cross-reference；关系记忆严格留给阶段 C 的 `12 -> 13`。
- Package consistency: 独立 corpus 先签名，install plan 再记录实际产物，`.hagent` 和便利 `.hbundle` 最后签名，消除了 size/hash 循环。
- Type consistency: Python `required|recommended|optional|source` 与 Android `AgentInstallClass` 一一对应；profile ID 固定 `lite|balanced|complete|source`，默认始终 `balanced`。
- External release boundary: 自动化 fixture 可复现；真实人物原文和发布者私钥由用户在 Skill 执行时本地选择，不进入 Git、测试资源或聊天内容。
