# 锚点服务协议 P0-B 与文件历史 P1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**背景（2026-09-04）：** 设计见 [2026-09-03-deliverable-layer-entry-consolidation-design.md](../specs/2026-09-03-deliverable-layer-entry-consolidation-design.md) §5.3/§5.2 与 §7。用户指示提前启动 P0-B 与 P1。P0-B 原定随简报 P0 落地（首个消费方为简报标记），本次先交付**纯模型层**（模型 + 校验 + 存取 API + 单测），按 spec §8 约束**不产生 Room 迁移**（落库随简报 P0 的 Room 版本一起编号）。P1 本次只做"文件详情内嵌历史"，简报回放页接入同一骨架部分待简报落地后补。

**范围（3 项）：**

1. **P0-B 锚点协议纯模型层**：`AnchorType`（8 型：PROJECT_FILE/FILE_RANGE/DIFF/COMMIT/TEST_EVIDENCE/RUN_EVENT/RUN_COMPLETION/SCREENSHOT_REGION）+ `ProjectEvidenceAnchor`（projectId、relativePath、contentHash、gitRef、runSnapshotId、FILE_RANGE 行区间、excerpt）+ 校验（SHA-256 十六进制哈希必填、禁存凭证 URL 与 `/Users/` Mac 用户目录前缀、excerpt ≤500 字符、按类型必填字段）+ 存取接口（创建即不可变：无更新/删除；按项目列举）。内存实现，接口留给 Room 版本。
2. **P1 文件历史数据层**：`GitCommitSummary`（shortId/message/authorName/timeMillis）+ `JGitEngine.fileLog(directory, relativePath, limit=20)`（`log().addPath(path)`，非仓库/失败走既有错误文案通道）。
3. **P1 预览面板"历史"区**：`ArtifactPreviewPanel` 工具栏加「历史」图标钮，展开后列出该文件的提交（shortId + 作者 + 时间 + 信息）；选中文件变化时若展开态则重载。

**Non-goals：** 锚点落库（随简报 P0）；完成卡/M3 消费方迁移；每提交文件 diff 视图（留 P2 锚点选择器）；简报回放页骨架。

**验收：**

- [x] `ProjectEvidenceAnchorTest` 覆盖：合法锚点（8 型最小字段）、哈希格式、禁存字段、excerpt 上限、类型必填、存储不可变语义。
- [x] `JGitEngineTest` 新增 fileLog 用例：只返回触及该文件的提交、最新在前、limit 生效。
- [x] `:app:testDebugUnitTest` 全量通过 + `:app:assembleDebug` 成功。
- [x] 真机：P0 项目选 README.md → 「历史」显示已提交记录。

**实施记录（2026-09-04）：**

- 三项全部落地。`:app:testDebugUnitTest` 全量 1211 个用例通过（新增锚点协议 6 个 + fileLog 1 个），`:app:assembleDebug` 成功。
- P0-B：`app/src/main/java/com/harnessapk/project/anchor/` 三文件（模型/校验器/接口+内存实现），零 Room 迁移；校验覆盖 SHA-256 哈希、凭证 URL 与 `/Users/` 前缀禁存、excerpt 上限、类型必填字段与行区间作用域；内存存储带自增序号保证"最新创建在前"的确定性排序（首版仅按 createdAt 排序在同毫秒创建时不稳定，测试暴露后修正）。
- P1：`GitCommitSummary` + `JGitEngine.fileLog`（`log().addPath(path)`，含 limit 与空路径守卫）；`ArtifactPreviewPanel` 工具栏新增「历史」图标（`contentDescription = "历史"/"隐藏历史"`），展开在工具栏分隔线下渲染 `ProjectFileHistorySection`（加载中/空历史/提交列表：shortId chip + 作者·时间 + 提交信息）；选中文件路径变化且展开态时经 `LaunchedEffect` 自动重载。
- 真机（HiBreak）：重装 APK 后发现该 ROM 的 `install -r` 清空了应用数据（P0 验收时的项目与 Git 身份配置丢失）——重建 fixture（项目"1"、补数字身份）顺带把 P0-A 全链路再回归一遍（初始化 → 提交 → 干净态）；选中 context.md 点「历史」正确列出提交 `2cde34a8 · 0 · 2026-09-04 08:12` 及提交信息。
- 备注：数据清空仅影响测试机 fixture，不影响用户设备上的正常升级（Release 安装不切换签名/ABI 时 `install -r` 保留数据；此为该 e-ink ROM 对 debug 包的行为，已记录备查）。
