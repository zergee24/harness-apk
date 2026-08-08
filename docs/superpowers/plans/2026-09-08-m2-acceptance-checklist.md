# M2 跨端任务验收台账

> 文件名沿用实施计划约定；本次自动化执行日期为 2026-08-09。自动化证据与目标荣耀真机证据分开记账，任何模拟器结果都不替代 Push、OEM 后台、锁屏解锁或真实十分钟断网。

## 1. 候选与隔离环境

- 状态：自动化 `DONE`；目标荣耀真机/真实 Relay + Mac Bridge 黄金链路 `PENDING`
- 分支：`codex/m2-cross-device-run`
- 功能/计划基线：`c89e76c`（G6 台账）；G7 文档提交以本分支最终 `git log` 为准
- 原 `test` 基线：`77de5f4`；本分支未推送、未合并
- Android：`HarnessM2Api36`，API 36，ADB server `5039`，console/adb `15662/15663`，serial `emulator-15662`
- ADB 隔离：5039 只包含 `emulator-15662`；server 使用 `--one-device emulator-15662` 与 `ADB_LOCAL_TRANSPORT_MAX_PORT=5553`；未访问默认 5037 或 USB 真机
- Go：1.26.5 darwin/arm64，`GOTOOLCHAIN=local`
- Codex app-server 契约基线：`0.147.0-alpha.6.5`
- 故障矩阵：`remote/testdata/m2-fault-matrix.json`
- 自动化入口：`remote/scripts/m2-automated-acceptance.sh`

## 2. 自动化发布 Gate

执行命令：

```bash
export PATH=/Users/tony/.local/share/harness-apk-m2/go1.26.5/bin:$PATH
export GOTOOLCHAIN=local
export ANDROID_HOME=/Users/tony/Library/Android/sdk
export ANDROID_SDK_ROOT=/Users/tony/Library/Android/sdk
export HARNESS_M2_ADB_SERVER_PORT=5039
export HARNESS_M2_SERIAL=emulator-15662
export ADB_LOCAL_TRANSPORT_MAX_PORT=5553
remote/scripts/m2-automated-acceptance.sh
```

结果：

- [x] 脚本先确认非 5037、目标为 emulator serial，且独立 server 恰好只有一个声明设备。
- [x] Android JVM：`1015 tests / 0 failures / 0 errors / 0 skipped`。
- [x] Android Debug APK：`:app:assembleDebug` 退出码 0。
- [x] Android 设备：`210 tests / 0 failures / 0 errors / 0 skipped`，总时长 3m12s。
- [x] Go：`go test -race ./...` 全包退出码 0。
- [x] Go：`go vet ./...`、`go build ./cmd/relay ./cmd/bridge` 退出码 0。
- [x] 静态：`bash -n`、故障矩阵 JSON 解析、`plutil -lint`、`git diff --check` 退出码 0。
- [x] 10,000 Event 查询最近 100 条：样本 `[6, 6, 6, 5, 5, 5, 5, 4, 5, 5]ms`，p95=`6ms`；日志位于 `app/build/outputs/androidTest-results/connected/debug/HarnessM2Api36(AVD) - 16/logcat-com.harnessapk.ui.activity.RunDetailScreenTest-tenThousandEventsQueryOnlyLoadsLatestHundredUnder200MsP95.txt`。

首次全量 connected run 在 210 项中出现 4 项失败，均为 `HarnessApkAppNavigationTest` 使用 `onNodeWithText("我的")` 同时匹配顶部标题和底部 Tab。没有产品状态、路由或 M2 逻辑失败；将断言收紧为唯一 `nav-ME` 节点“可见且已选中”后，该类先单独 `5/5`，完整套件再 `210/210`。此过程保留在台账中，不删除首次失败证据。

## 3. 七条黄金链路

| ID | 链路 | 自动化 | 真机/真实服务 | 当前结论 |
| --- | --- | --- | --- | --- |
| M2-GOLD-1 | 绑定项目、输入目标、3 次点击内进入 QUEUED/STARTING | `DONE`：Binding/Run 原子事务、重复 start 单 Turn | `PENDING` | 代码可验收；真实点击预算待录屏 |
| M2-GOLD-2 | 断网 10 分钟，Mac 完成，恢复后补齐且一个 Turn | `DONE`：旧 Logical Event 重封装、resume/ACK/去重 | `PENDING` | 协议恢复通过；真实网络/OEM 后台待验 |
| M2-GOLD-3 | Pending Approval 时 kill Android，重启指向同一 Room row | `DONE`：文件型 Room 重开、Activity/Run/Approval 恢复 | `PENDING` | 持久层通过；荣耀通知/锁屏待验 |
| M2-GOLD-4 | 重复批准/拒绝/停止只产生一次副作用 | `DONE` | 非必需 | commandId/Outbox/Bridge cache 幂等通过 |
| M2-GOLD-5 | Bridge WebSocket 重连保持 route，不重启 app-server | `DONE`：route store/reconnect coordinator | `PENDING` | 持久路由通过；真实 PID/process epoch 待记录 |
| M2-GOLD-6 | 删除未 ACK Journal 形成 Gap，审批禁用，Snapshot 恢复 | `DONE` | 非必需 | compact/gap/snapshot/cursor/approval 全通过 |
| M2-GOLD-7 | 无已知测试命令，完成卡显示“测试未验证” | `DONE` | 非必需 | Agent 文案不变成测试证据，移动 UI 通过 |

## 4. 产品与安全检查

- [x] 通知仅提供“查看/拒绝/停止”；所有风险级别都不能从通知批准。
- [x] 允许一次必须进入解锁后的 Run Detail；高风险需展开证据后二次确认；无 `ALLOW_ALWAYS`。
- [x] `requestUserInput` 不写 Approval；Run 进入 `WAITING_USER`，提示在 Mac UI 重新发起。
- [x] steer/interrupt 先写 Outbox；Bridge Logical result 或 Snapshot 前不乐观改变 Run 终态。
- [x] Logical Event、Approval、Run completion 与 Snapshot completion 入 Room 前递归脱敏。
- [x] 完成卡文件/测试/Git/遗留均有事实值或“未验证”；不从 Agent 自述推断通过。
- [x] Run Detail 首屏 100 条并可每次向上加载 100 条；320dp/字体 1.3/48dp 动作/TalkBack 描述通过。
- [x] M2 不展示不可用的“沉淀到项目”，不自动 Commit/Push/Pull/Merge。
- [x] Room 只新增 `21 -> 22`；Bridge state v1 -> v2 的 credential/sequence 保留、Gap 强制、完整目录备份/兼容回滚已文档化。
- [x] G7 改动未触碰 M1 阿里云实时语音接入文件。

## 5. 目标荣耀真机执行单

状态：`PENDING`。执行前必须由人工提供并独占目标 serial；不要复用默认 5037，也不要把设备接入 M2 模拟器的 5039 server。

建议独立端口：

```bash
export HARNESS_M2_REAL_ADB_SERVER_PORT=5040
export HARNESS_M2_REAL_SERIAL='<目标荣耀真机 serial>'
/Users/tony/Library/Android/sdk/platform-tools/adb \
  -P "$HARNESS_M2_REAL_ADB_SERVER_PORT" \
  --one-device "$HARNESS_M2_REAL_SERIAL" start-server
/Users/tony/Library/Android/sdk/platform-tools/adb \
  -P "$HARNESS_M2_REAL_ADB_SERVER_PORT" devices -l
```

只有列表恰好包含目标 serial 后才执行：

1. 记录手机型号、MagicOS/Android、APK versionName/versionCode、通知权限、Bridge commit、Codex app-server 版本、Relay build。
2. 跑 M2-GOLD-1，录屏三次点击预算和同一 runId/threadId/turnId（ID 只进诊断附件，不进主 UI）。
3. 跑 M2-GOLD-2：断开手机网络十分钟，Mac 完成后恢复；记录单 Turn、Logical Event 去重、恢复时长与日志。
4. 跑 M2-GOLD-3：锁屏收到审批、kill app、重开；验证徽标/Activity/Run Detail 同一 Pending row，通知无批准按钮，解锁后才可允许一次。
5. 跑 M2-GOLD-5：只断 Bridge WebSocket，记录 app-server PID/process epoch 未变化、route 恢复；再单独重启 Bridge，确认旧审批 STALE/Snapshot 对账。
6. 恢复手机网络、通知、后台与电池设置；仅停止 5040 server，不停止 5037/5039 或其他任务进程。

每条人工证据必须写入：日期、Gate ID、commit SHA、完整命令与退出码、设备/Android/Bridge/Codex 版本、截图/日志绝对路径、已知限制。完成以上五项前，不得把 G7 总状态或 0.3.0 正式发布标成 `DONE`。

## 6. 人工合并回 test 清单

- [ ] 先让 M1 owner 提交或隔离当前阿里云实时语音改动，确认 `test` 工作树 clean。
- [ ] `git fetch` 后检查 `test` 与 `codex/m2-cross-device-run` ahead/behind；若 `test` 新增 Room migration，重新编号并复跑完整迁移链。
- [ ] 人工 review M2 中文 commits，重点检查协议、Room 22、Bridge state v2 和通知批准边界。
- [ ] 在合并后的 `test` 上用同一隔离脚本重跑 1015 JVM + 210 connected + Go race/vet/build；数字随合并后新增测试更新，不机械沿用本台账。
- [ ] 完成目标荣耀真机 PENDING 项，再决定 0.3.0 test 候选与生产发布；禁止本分支直接 push/merge。
