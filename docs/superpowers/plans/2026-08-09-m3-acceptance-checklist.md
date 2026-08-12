# M3 项目记忆闭环验收台账

日期：2026-08-09
分支：`codex/m3-project-memory-closure`
基线：M2 `e697c1a`
发布候选：`0.4.0`

本台账区分两类结论：`PASS` 表示该行声明的自动化退出条件已有同一候选工作树证据；`MANUAL` 必须由人工完成。自动化已经通过，但任一人工 Gate 未关闭时，发布结论仍是 **NO-GO（MANUAL PENDING）**。

## 自动化 Gate

| Gate | 证据 | 状态 |
| --- | --- | --- |
| Room 22 -> 23，M1/M2/Search/Draft 保留 | 全量 connected 中的真实迁移 fixture | PASS |
| SQL `projectId` 强隔离 | DAO + 真实 Room 40-query 消息查询 | PASS |
| 40 条 Query，跨项目泄漏 0，No Match 注入 0 | Recall@6 `1.000`、leak `0`、No Match `0`、unexpected `0` | PASS |
| 10,000 Chunk p95 < 250ms | JVM `44.255 ms`；Room `5.124 ms`，均为 10k×10 | PASS |
| Evidence Snapshot、V2/V3、引用与漂移 | 全量 JVM/connected 的 Snapshot、终态引用与来源 UI | PASS |
| Assistant/Remote/Explicit 共用 Draft/Diff/Apply | 持久 Draft、稳定 ID、单写 Apply、共享 Diff | PASS |
| Apply 基线冲突不覆盖，Git 三步不合并 | JVM/Compose 自动化通过；真实 Git 操作为人工 Gate | PASS / MANUAL GIT |
| Bridge completion v2 冻结、路由、恢复、能力协商 | Go `92` pass test/subtest event + race/vet/build | PASS |
| 全量 Android + Go | `remote/scripts/m3-automated-acceptance.sh` | PASS；完整成功日志已保存 |

## 最终自动化证据

- JVM + assemble：`1087/1087`，0 failures、0 errors；使用 `-PversionNameOverride=0.4.0`。
- 40 Query：Recall@6 `1.000`、跨项目泄漏 `0`、No Match 注入 `0`、unexpected 注入 `0`。
- 性能：JVM 10k×10 p95 `44.255 ms`；Room FTS 10k×10 p95 `5.124 ms`；全局消息 50k p95 `27 ms`；Remote timeline 10k p95 `10 ms`。
- 专用 AVD：`222/222`、0 skipped、0 failed；测试结果 XML 为 `app/build/outputs/androidTest-results/connected/debug/TEST-HarnessM3Api36(AVD) - 16-_app-.xml`。
- Go：`92` 个 pass test/subtest event、11 个 package；`go test -race ./...`、`go vet ./...`、Relay/Bridge build 均退出 0。
- 完整日志：`/tmp/harness-m3-acceptance-20260809/final-full`。首次运行发现旧 50k 性能 fixture 缺 v23 必填列；`c99837f` 修复后定向 `2/2`，完整复跑通过。

## 最终全量检查

- [x] 当前最终工作树运行 JVM + `assembleDebug`，记录数量、版本覆盖参数和完整日志。
- [x] 隔离的 `HarnessM3Api36` / ADB `5041` / `emulator-15672` 上运行全量 connected `222/222`。
- [x] 刷新 40 Query 和 JVM/Room 10k×10 p95，全部满足门槛。
- [x] `remote/scripts/m3-automated-acceptance.sh` 完整运行，保存统一日志目录并验证退出清理。
- [x] 20 条黄金链路的确定性部分由全量 JVM/Room/Compose/Bridge 自动化覆盖；需要真实外部系统的部分列在人工 Gate。
- [x] 运行结束后确认 ADB `5041`、console `15672`、emulator ADB `15673` 均无监听。

## 20 条黄金链路

以下 20 条的确定性逻辑已经进入最终全量 JVM/Room/Compose/Bridge 自动化；涉及真实项目内容、Relay/Mac、荣耀真机或 Git 远端的现场行为仍需按人工 Gate 复验。

1. [x] `context.md` 决策可检索并带项目来源。
2. [x] 普通 Markdown 标题、段落、列表、表格和代码围栏可检索。
3. [x] 项目历史用户消息可检索。
4. [x] 冻结的 Remote completion evidence 可检索。
5. [x] 生活会话与其他项目不会进入当前项目证据。
6. [x] No Match 继续普通回答且不注入伪证据。
7. [x] 检索失败记录 `FAILED`，发消息链路不被阻断。
8. [x] 单轮最多 6 条、8,000 字，同一文件最多 2 条。
9. [x] 未知 `⟦P#⟧` 被移除并记录校验失败。
10. [x] Agent 关系记忆不生成 Project 引用。
11. [x] 来源未变化时可回到当前文件或消息。
12. [x] 来源修改后默认展示历史快照并显示变化状态。
13. [x] 来源删除后历史回答仍可读取原快照。
14. [x] Context Snapshot V2 可恢复，V3 可重试且不重检索覆盖。
15. [x] Assistant 结果经一次 Diff 生成持久 Draft。
16. [x] Remote Completion 经同一 Diff 生成无 synthetic conversation 的 Draft。
17. [x] 显式文件变更使用同一 Draft 状态机。
18. [x] 文件基线变化、新建冲突和部分失败都不静默覆盖。
19. [x] Apply 后只刷新 Files/Git 与本轮白名单，Commit 仍需独立确认。
20. [x] Push 仍需再次确认，失败不回滚已经成功的本地 Commit。

## 专用模拟器证据

- AVD：`HarnessM3Api36`
- ADB server：`5041`
- serial：`emulator-15672`
- console/adb：`15672/15673`
- `ADB_LOCAL_TRANSPORT_MAX_PORT=5553`
- 最终日志根目录：`/tmp/harness-m3-acceptance-20260809/final-full`
- 自动化前确认 server 中只有上述 serial；成功与失败退出后均确认 `5041/15672/15673` 无监听。

## 人工 Gate（不由模拟器替代）

- [ ] 用户提供明确 serial 后，在另一独立 ADB server 上验证荣耀真机窄屏、字体 1.3 与 TalkBack 听读。
- [ ] 真实 Relay + 当前 Mac Bridge + 当前 Codex app-server 完成 Run、断线恢复、审批和冻结 completion。
- [ ] 手机没有 Mac-only 文件时明确展示 Mac 路径；仅通过显式 Git Fetch 或用户确认的导入带回。
- [ ] Assistant 与 Remote Completion 分别审核 Diff，写入真实项目 Markdown。
- [ ] Commit 白名单准确；Push 非快进失败保持本地 Commit 且不自动合并/强推。
- [ ] 本地闭环报告不含正文、API Key、绝对私有路径或第三方分析 SDK。

第一批人工顺序：项目检索与回跳 -> 修改/删除后的历史快照 -> Assistant 沉淀 -> Remote Completion 沉淀 -> Commit -> 独立 Push 确认。

## 发布签署

- 当前结论：**自动化 PASS；发布 NO-GO（MANUAL PENDING）**。
- 自动化签署条件已满足：最终工作树 JVM/assemble、全量 connected、40 Query、两组 10k p95、Go 门禁和脚本清理均有同一候选版本成功日志。
- 人工签署条件：上列荣耀真机、真实 Relay/Mac/Codex、来源回跳/Diff、Commit/Push 边界全部完成并记录。
- 自动化 PASS 不授权自动发布、自动合并、自动 Commit/Push 或跳过真实外部系统人工验收。
