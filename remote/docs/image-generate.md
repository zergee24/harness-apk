# 生图命令协议（image.generate）

> 2026-10-29 定义。链路：`Harness APK ⇄ 阿里云 relay（只转发 AES-GCM 密文）⇄ Mac bridge → OpenAI Images API`。
> **relay 与 `internal/protocol` 结构体都不需要改动**：命令参数走 `Command.Params`（`json.RawMessage`），
> 结果走 `Event.Payload`（`json.RawMessage`），命令/事件类型用普通字符串标识（与 `host.status` 同风格）。

## 1. 命令：`image.generate`（APK → bridge）

```jsonc
{
  "type": "image.generate",
  "requestId": "<客户端生成的唯一 id>",
  "params": {
    "prompt": "A minimal flat-design icon of ...", // 必填，≤ 4000 字符
    "size": "1024x1024",                          // 可选；枚举 1024x1024 | 1024x1536 | 1536x1024；默认 1024x1024
    "quality": "medium",                          // 可选；枚举 low | medium | high；默认 medium
    "model": "gpt-image-2.5-flare"                // 可选；枚举 gpt-image-2.5-flare | gpt-image-2.5-sunburst | gpt-image-2；默认 flare
  }
}
```

- `backendId` 留空：这是 **host 级命令**，不依赖 codex/dsh 后端（与 `host.status` 同一层分发）。
- 已配对设备才能发命令（消息本身按设备密钥加密），bridge 额外做**每设备限流**。

## 2. 成功事件：`image.generated`（bridge → APK）

```jsonc
{
  "type": "image.generated",
  "requestId": "<原样回传>",
  "payload": {
    "id": "<bridge 生成的 id>",
    "prompt": "<原 prompt>",
    "model": "gpt-image-2.5-flare",
    "size": "1024x1024",
    "quality": "medium",
    "mimeType": "image/png",       // image/png | image/jpeg（超预算时 bridge 转 JPEG）
    "dataBase64": "<图片本体 base64，不带 data: 前缀>",
    "bytes": 864733,                // 返回图片的原始字节数
    "width": 1024,
    "height": 1024,
    "durationMs": 8123,             // bridge 侧从请求到拿到图片的耗时
    "costEstimateUsd": 0.053,       // 粗估（按 quality+size 的公开价），仅用于展示
    "savedPath": "/Users/tony/.harness-remote/imagegen/<id>.png" // Mac 上归档的原图路径（仅调试用）
  }
}
```

## 3. 失败事件：`image.error`（bridge → APK）

```jsonc
{
  "type": "image.error",
  "requestId": "<原样回传>",
  "message": "人类可读中文错误，例如：OpenAI 401 / 额度耗尽 / prompt 过长 / 限流",
  "payload": { "code": "OPENAI_HTTP_401" } // 可选
}
```

## 4. 尺寸预算（关键约束）

relay 的 WS 单帧上限 `maxWireMessageBytes = 8 << 20`（8 MiB，`remote/cmd/relay/main.go:22`）。
一次回包的膨胀链：`PNG → JSON base64(×4/3) → AES-GCM 密文 → WireMessage.ciphertext base64(×4/3)`，
总膨胀约 **×2.37**。因此在 bridge 侧：

- 原始图片 ≤ **400 KB**：直接回 PNG；
- 超过：bridge 用 Go 标准库解码后压成 **JPEG（q=85，长边 ≤ 2048）** 回传（通常 100–250 KB）；
- 原始 > **8 MB**：直接报 `image.error`（`IMAGE_TOO_LARGE`），不冒险发超大帧。

> ⚠️ **实测（2026-10-29）**：回包 PNG 约 1.5 MB（线上帧 ≈ 2.7 MB）时，
> bridge 日志显示已发送、设备也收到 ack 前的握手，但 **APK 侧 `handleEvent` 完全没有收到该消息**
> （加日志验证：无任何 image 事件）；改成 JPEG 后（回包 ≈ 150 KB、线上帧 ≈ 350 KB）**一次性打通**。
> 结论：**这条链路上「大帧」不可靠**（relay 静默丢弃 / OkHttp 帧处理都未排除），
> 所以桥侧宁可变 JPEG 也不能回 1 MB+ 的原图；若将来要传大图，必须走分片协议而不是单帧。

## 5. bridge 侧实现约束

- **key 解析顺序**：`OPENAI_API_KEY` 环境变量 → `~/.dsh/.credentials.yaml` 的 `refs.OPENAI_API_KEY`（每次请求现读）。
- **代理**：Go 标准库 `http.Transport` 默认读 `HTTPS_PROXY/HTTP_PROXY`，所以只需给 bridge 进程注入
  `HTTPS_PROXY=http://127.0.0.1:7897`（写进 `remote/deploy/com.harnessapk.remote-bridge.plist` 的 EnvironmentVariables，
  同时提供 `--image-proxy` 或 env `HARNESS_IMAGE_PROXY` 作为显式覆盖，缺省走 env）。
- **端点**：`POST {OPENAI_BASE_URL:-https://api.openai.com/v1}/images/generations`
  body：`{ model, prompt, size, quality }`（gpt-image 系列默认返回 `b64_json`）。
- **限流**：每设备同时最多 1 个生图请求；滑动窗口 4 次/分钟；每日 200 次（bridge 内存记账即可，重启清零可接受）。
- **落盘**：原图归档到 `~/.harness-remote/imagegen/<id>.png`（目录 0700），失败不落盘。
- **日志**：只记 `requestId/deviceId 前 8 位/model/size/quality/bytes/durationMs`，**不要记 prompt 全文与 key**。

## 6. APK 侧实现约束

- 发送：复用现有远程命令通道（`RemoteClient`/`RemoteConnectionService` 现成的加密 + outbox + 重试链路），
  新增 `image.generate` 命令封装；`requestId` 自己生成并用于关联事件。
- 接收：订阅 `image.generated` / `image.error`（走现有事件分发/reducer），交给 UI 的 StateFlow。
- 展示：`dataBase64` → `BitmapFactory.decodeByteArray`；保存走 MediaStore（`Pictures/Harness`），分享用 `FileProvider`。
- 超时：bridge 生图可能 5–30s（high 更久），UI 侧给 ≥ 120s 的超时与明确的"仍在生成"状态。

## 7. 不需要改动的东西

- relay（`cmd/relay`）与 `internal/protocol` 结构体：零改动。
- DSH / MCP 生图工具：与本链路无关（那边是 Mac 本地工具，供桌面会话用）。

## 9. 部署说明（本机 2026-10-29 实况）

- **部署的 bridge 二进制不是从 `test` 分支构建的**：本机已安装的 bridge 来自分支
  `codex/zcode-webremote-l3`（含 `--codex-proxy`、状态文件雪崩修复、credits 基线等功能，
  这些都不在 `test` 分支上）。因此生图特性是**移植到该分支**后构建部署的。
- 移植产物（可直接复现）：`remote/docs/image-generate-codex.patch`
  ```bash
  git worktree add --detach /tmp/hb-image codex/zcode-webremote-l3
  cd /tmp/hb-image && git apply /Users/tony/Documents/harness-apk/remote/docs/image-generate-codex.patch
  cd remote && go build -trimpath -o /tmp/harness-bridge ./cmd/bridge
  ```
- 已安装的 launchd plist（`~/Library/LaunchAgents/com.harnessapk.remote-bridge.plist`）
  环境变量：`HTTPS_PROXY`（OpenAI 走代理）、`NO_PROXY`（relay 域名直连）、`PATH`
  **必须保留 `/Users/tony/.nvm/versions/node/v24.13.0/bin` 前缀**，否则 `dsh` 后端会报
  `env: node: No such file or directory`。
- 健康检查：`tail -5 /tmp/harness-remote-bridge.error.log` 应无 `node: No such file`；
  `launchctl print gui/$(id -u)/com.harnessapk.remote-bridge | grep state` 应为 running。
- APK 侧：debug 变体（`com.harnessapk.debug`）可直接原地升级测试；**连接是懒启动**——
  必须先进「工作 → Agent 副屏」或远程会话页建立连接，生图页才会显示已连接。
