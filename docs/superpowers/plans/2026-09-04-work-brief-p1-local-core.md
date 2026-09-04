# 工作简报 P1（本地简报核心）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**背景（2026-09-04）：** Spike-画布 HiBreak 档 PASS（[2026-09-04-spike-canvas-hibreak-report.md](../specs/2026-09-04-spike-canvas-hibreak-report.md)），P1 解锁。设计边界见《可回放工作简报设计》§15-17、§25 P1。

**冻结基线（P1 编码前固化，缺失不得开工）：**

- 分支@commit：`test @ 245ea20`（Spike 报告提交后的 HEAD）
- Room：当前 **v24**（24 个 migration），P1 使用 **MIGRATION_24_25 → v25**
- 受支持升级矩阵：v23 → v24 → v25、v24 → v25（当前装机均为 v24）；v22 及以下不支持
- `.hbrief schemaVersion` 独立于 Room version，v1 从 1 起
- 锚点服务纯模型层（`project/anchor/`）已在库，P1 文件锚点直接消费

**范围（spec §25 P1）：** 项目入口（`WORK_BRIEF` artifact）、简报/场次/页面状态、有限画布+笔迹持久化（journal）、4 类用户标记、时间轴与回放、最小只读文件锚点、无音频可封存、崩溃恢复与无音频 `.hbrief` bundle 导出。

**Non-goals（范围熔断）：** 录音/转写/音频 bundle（P2，等 Spike-录音 GO）、Diff/Commit/Run 锚点与 AI 纪要（P3）、导入与分享（P4）、OCR/HWR、无限画布。

**Task 1: Room schema v25**
- 新实体（`storage/brief/`）：`WorkBriefEntity`、`CaptureSessionEntity`（briefId 唯一索引）、`CaptureSegmentEntity`（音频字段可空，P1 全 null）、`CanvasPageEntity`、`TimelineEventEntity`（(sessionId, sequence) 唯一）、`UserMarkerEntity`、`CodeAnchorEntity`（P1 最小：relativePath/startLine/endLine/contentHash/manualLabel）、`BriefRevisionEntity`（(briefId, revision) 唯一）
- `MIGRATION_24_25` 建全部表 + 索引 + 外键 CASCADE；AppDatabase 注册 DAO，version 25
- androidTest：DAO 往返 + 迁移路径（24→25 用 helper 建空表升级）
- [ ] 实现与测试通过

**Task 2: 笔迹 journal（纯 JVM）**
- `brief/journal/StrokeJournal`：追加记录（长度+type+sequence+CRC32）、checkpoint（每 2s / 64KiB）、尾部 CRC 损坏截断、同 eventId 幂等重放
- JVM 测试：追加/重放/截断恢复/幂等
- [ ] 实现与测试通过

**Task 3: WorkBriefRepository 与状态机**
- create（Brief+Session+Page0 同事务）、状态机（PREPARING→ACTIVE→PAUSED→STOPPING→PROCESSING→READY；RECOVERABLE 扫描）、activeDuration 累计、revision 快照
- [ ] 实现与测试通过

**Task 4: 记录页（正式版画布）**
- 复用 Spike 渲染架构（离屏位图+局部失效+压感变宽+点级橡皮）接入 StrokeJournal；标记 4 型按钮；页增删/翻页；暂停/恢复/结束
- androidTest 冒烟 + 真机
- [ ] 实现与测试通过

**Task 5: 回放页**
- 按时间轴重放笔画出现过程、页切换、标记高亮；拖动进度条
- [ ] 实现与测试通过

**Task 6: IA 接入**
- `ProjectArtifactType.WORK_BRIEF` + 文件夹列表行 + 新建入口 + 回放入口（文件详情分派）
- [ ] 实现与测试通过

**Task 7: 最小文件锚点**
- 锚点选择器（项目文件树 → 文件 + 行区间）→ `CodeAnchorEntity` + `ProjectEvidenceAnchor`（PROJECT_FILE/FILE_RANGE）
- [ ] 实现与测试通过

**Task 8: `.hbrief` 无音频 bundle 导出**
- manifest/summary/timeline/canvas(strokes.bin)/markers/anchors/preview(每页 PNG) + SHA-256 + 校验；导出基于固定 revision
- [ ] 实现与测试通过

**Task 9: 全量验证与真机验收**
- JVM 全量 + assembleDebug + HiBreak 真机走查（创建→书写→标记→擦除→暂停恢复→强杀恢复→回放→封存→导出）
- [ ] 完成

**验收（spec §25 P1 退出条件）：** 五个真实 AI coding 场景次日 90 秒内可恢复标记/判断/问题/下一步/可回跳位置——验收期另约；工程门槛：全量单测绿 + 真机走查清单全过。

**实施记录（2026-09-04，进行中）：**

- Task 1 完成：Room v25（MIGRATION_24_25），8 实体 + 2 DAO；androidTest（HiBreak）DAO 往返/唯一索引/FK 级联 5 项全过。
- Task 2 完成：StrokeJournal（CRC32/单调序列/2s·64KiB 自动 checkpoint/尾部损坏截断），6 项 JVM 测试全过。修复过程中发现并修正两处自伤：写入与重放的 CRC 位置顺序不一致、只读阶段截断依赖未创建的输出通道。
- Task 3 完成：BriefStateMachine（§8.1/8.2 转移表）+ WorkBriefRepository（创建/开始/暂停/恢复/结束/标记/文件锚点）；6 项 JVM 测试全过。
- Task 4 部分：PageInk/Spike 渲染核心迁移为正式版 BriefInkView + BriefCaptureController；真机验证创建→画布墨迹→journal 落盘全通。
- **P1-1 已修复（2026-09-04 晚）**：「添加标记」抛"没有记录场次"的根因是 `appendTimeline(sessionId, ...)` 内部误调 `requireSession(sessionId)`——把场次 id 当简报 id 查询（`WHERE briefId = 场次id`）永远查不到。诊断靠 requireSession 失败路径 dump 全表：失败的 brief 值恰好等于本轮 session id，规律锁定。修复：appendTimeline 不再查场次，序列按场次内 timeline 自算；WorkBriefDao 增加 `sessionById`。真机复测标记添加成功（状态条"已添加标记：决策"），WorkBriefRepo 错误 0。协程层 runCatching 加固保留（错误上屏不崩）。
- 另发现：本 ROM 的 `install -r` 偶发清空应用数据（本轮复现一次），测试 fixture 需每次重建。
- 遗留：Task 4 收尾（暂停态 UI 细节）、Task 5-9。
