# Spec：ZCode 远程连接二维码套壳（工作模式子模式）+ 手机端手动重连（L3）

> 2026-09-15。背景与证据见本文件「已验证事实」；Spike 于 Tony 的 Mac mini（ZCode.app 3.11.2）当场实测通过。

## 目标

1. harness-apk 工作模式新增「ZCode 远程」子模式：WebView 套壳 ZCode 桌面端的「移动端远程控制」（WebRemoteControl）网页客户端，手机端直接控制 Mac 上 ZCode 当前工作区。
2. 基于 harness bridge 的既有指令通道，手机端一键触发 Mac 侧重连（L3 全自动：唤屏 → 打开对话框 → 刷新二维码 → 复制链接 → 新 URL 回推手机 → WebView 自动换链重载）。

## 已验证事实（Spike，2026-09-15）

- ZCode WebRemoteControl = 「桌面端 ↔ zcode 云端 relay ↔ 手机网页」；二维码内容即配对 URL：
  `https://zcode.z.ai/remote/v4?sid=<配对id>&hash=<签名>&t=<ms>&mid=<机器id>&name=<主机名>&app_version=<版本>`
- 桌面端对话框入口：侧边栏按钮「移动端远程控制」；对话框内按钮：`停止`、`刷新二维码`、`复制链接`；状态文案：`等待手机连接` → `已就绪`。
- 刷新二维码弹**二级确认**（「刷新后，之前复制或扫码得到的远程控制链接将失效」），确认按钮文案同为「刷新二维码」（带 ⏎ 后缀），取消为 esc。
- 刷新后 sid 变化（实测 `d_nCe1n…` → `d_VYtjM…`），旧 URL 作废；刷新完成后状态自动回到「已就绪」。
- **复制链接 → 系统剪贴板**是拿 URL 的最稳通道（AXPress + pbpaste），无需解析二维码图片或读 AX 静态文本。
- Electron 的 AX 树可被辅助功能客户端完整读取（computer-use broker 实测可点全部按钮）；osascript/System Events 同样可行，但宿主进程需要 TCC「辅助功能」授权（首用弹一次授权框）。
- 配对 URL **不落盘**（userData/localStorage/~/.zcode 均无），拿 URL 必须走对话框「复制链接」。
- `zcode://` 深链只有 oauth/payment/workspace/open 三个 route，无远程连接对话框直达；bridge 需用 AX 点侧边栏按钮。
- relay 同一时间只允许一个手机页面，多开会互踢。

## 架构与协议

### 命令（App → bridge，复用既有加密 wire）

`RemoteCommand.type = "zcode.webremote"`，新增 `action` 字段（wire 上复用 `method` 位传输，见下）：

| action | 语义 | Mac 侧动作 |
|---|---|---|
| `refresh` | 全自动重连 | caffeinate 唤屏 → `open -a ZCode` 置前 → AX：开对话框（若未开）→ 点「刷新二维码」→ 点确认弹窗「刷新二维码」→ 等 1.5s → 点「复制链接」→ pbpaste 校验 URL |
| `link` | 只取当前链接 | 唤屏 → AX：开对话框（若未开）→ 点「复制链接」→ pbpaste 校验 URL |

### 回执（bridge → App，plain 帧，同 dashboard.focus 模式）

事件 `zcode.webremote`，payload：`{ ok: bool, action, url?, stage?, message? }`
- `stage` 标注失败环节：`zcode-not-running` / `ax-denied`（TCC 未授权，提示去 Mac 授权）/ `dialog-not-found` / `ax-empty`（AX 树未激活）/ `clipboard-invalid`。
- 成功时 `url` 必带，App 端持久化并 reload WebView。

### App 端子模式

- 入口：工作模式 → 远程 Hub 区，与副屏 Dashboard 入口并列。
- 配对通道（2026-09-15 依 Tony 反馈做减法，"支持复制链接就够"）：① 粘贴链接（主路径，Mac 端「复制链接」后经任意通道传到手机）；② bridge `link` 动作「从 Mac 取链接」。~~扫码/读取图片~~已移除（CAMERA 声明未授权会 SecurityException 的坑在 Codex 配对扫码处保留修复）。
- WebView：加载 URL；onError 显示「重连」按钮 → 发 `refresh` 命令 → 收到回执后 `loadUrl(new)`。
- 单页面互踢约束显性化：页头提示「同一时间只允许一台手机页面打开」。

## Bridge 实现（m4 分支 `codex/zcode-webremote-l3`）

- 新包 `remote/internal/webremote`：osascript 脚本模板 + 步骤编排 + `ParseRemoteURL` 校验；`CommandRunner` 接口注入，单测用假 runner 断言步骤顺序与脚本内容。
- AX 激活：脚本先尝试 `AXManualAccessibility`/`AXEnhancedUserInterface` 置 true，再递归找按钮（`entire contents` 过滤 name 前缀）。
- dispatch 新增 `case "zcode.webremote"`，回执走 `sendDashboardFrame`（plain 帧，不经 journal，避免 test 线 reducer 未知 run 异常——同 focus 的坑）。

## 风险与边界

- **TCC**：bridge 宿主首用需在 Mac 上点一次「辅助功能」授权；`stage=ax-denied` 时 App 端明示引导。授权前 L1（WebView 自刷新）始终可用。
- **非官方集成面**：按钮文案/对话框结构随 ZCode 版本可能变动（与 codex rollout 同级风险），脚本按 name 前缀匹配并返回精确 stage 便于跟随修复。
- **安全**：URL 含 hash 签名，等于工作区控制权；仅存于 App 本地 SharedPreferences 与 bridge 内存，不入日志。
- 副屏只读轨道不受影响：本功能写通道是 ZCode 官方 WebRemoteControl，与 bridge observer（rollout/sqlite 只读）零交集，无双进程驱动问题。

## 验收清单

1. App 内粘贴 URL → WebView 打开 ZCode 远程控制页，可操作 Mac 工作区任务。
2. 手机熄屏断连后，点「重连」→ Mac 唤屏自动刷新二维码 → WebView 自动换新 URL 恢复。
3. Mac 端未授权时点「重连」→ App 提示去 Mac 授权（stage=ax-denied）。
4. ZCode 未运行/对话框未开时 `refresh` 仍能全程自动完成。
