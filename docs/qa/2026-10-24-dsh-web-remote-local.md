# DSH 网页远程（硬化版）本机验证记录

日期：2026-10-24（Asia/Shanghai）
范围：Mac 本机 loopback + relay 上一条**临时** Caddy 配置验证；**未修改生产 Caddy、未开放公网入口**。

## 设计

```
手机 WebView（ZCode 同款壳）
   │  https://relay.zerg.work/dsh-<random>/   + Caddy basic auth
   ▼
relay Caddy  handle_path /dsh-<random>/* → 127.0.0.1:58233（SSH -R 隧道）
   │  access log（过滤 Authorization / Cookie）
   ▼
Mac  dsh-web-remote runner
   ├─ 本机 proxy :58233  注入 dsh web launch token、cookie Max-Age→1 天、透传 WebSocket
   └─ dsh web 0.2.1-rc.3  :58232（--public-url https://relay.zerg.work/dsh-<random>/）
```

- 手机 URL 不带 launch token；token 只在 Mac 代理进程内存里。
- 浏览器 cookie 由代理从 30 天改成 1 天（`Max-Age=86400`）。
- Caddy 层 basic auth 为第二因子；访问日志删除 `Authorization` 和 `Cookie` 头。
- 代理只监听 127.0.0.1；`dsh web` 只监听 127.0.0.1。

## 1. 代理 + 真实 dsh web 自测

代码：`remote/dsh/web-remote/{proxy.mjs,dsh-web-remote.mjs,selftest.mjs}`
命令：

```bash
cd remote/dsh/web-remote
node selftest.mjs
```

结果：**39/39 checks passed**。关键项：

| 项 | 结果 |
| --- | --- |
| 无 cookie 首次请求 | 303，`Location: ./`，`Set-Cookie: dsh-auth-…; Max-Age=86400` |
| 响应体/头含 launch token | 否 |
| 带 cookie 请求 | 200，`<title>DeepSeek Harness</title>` |
| 真实前端资产 | 200 |
| 真实 `dsh web` 首跳 | 303 + cookie |
| 真实页面 | 200 + title |
| WebSocket 透传（dummy） | `101 Switching Protocols`，token 只在上游 URL |
| 上游故障 | 502，响应体无 token |
| token 轮换 | 代理热更新，无重启浏览器端口 |
| 过期/失效 cookie | 代理对 GET/HEAD 自动带 token 重试一次，返回新 303 + 新 cookie |
| cookie 改写数 | 首次、轮换后、重试后各 1 次，共 3 次 |
| 代理日志 | 无 token，路径 `token=<redacted>` |
| runner/代理日志 | 无 `?token=<20+ chars>`，状态文件只有不带 token 的 `cleanUrl` |

真实 WebSocket（headless Chrome + CDP，2026-10-24 21:36 左右）：

```text
Network.webSocketCreated  ws://127.0.0.1:58243/api/remote.mux
Handshake                 101 Switching Protocols
Frames received           6
document.title            DeepSeek Harness
```

说明：真实 DSH 前端使用 `/api/remote.mux` 的 WebSocket；经代理后握手 101、有数据帧，且浏览器侧无 token。

## 2. Caddy basic auth + 访问日志（relay 临时配置）

在 relay 上以 `/tmp` 配置、`127.0.0.1:18443` 临时启动第二个 Caddy 进程（不动生产服务），配置片段：

```caddyfile
http://127.0.0.1:18443 {
    basic_auth {
        dshtest <bcrypt>
    }
    log {
        output file /tmp/dsh-caddy-test-access.log
        format filter {
            wrap json
            fields {
                request>headers>Authorization delete
                request>headers>Cookie delete
            }
        }
    }
    respond "ok" 200
}
```

验证：

- `caddy validate` → `Valid configuration`
- 无凭据 → 401
- `-u dshtest:…` → 200
- 访问日志字段：`ts / request.remote_ip / request.method / request.host / request.uri / request.headers(User-Agent, Accept) / user_id / status / duration`
- 日志中 `Authorization`、`Cookie`、明文密码命中数 **0**
- 测试进程已停止，临时文件已删除；生产 Caddyfile 未改动（`grep -c dsh /etc/caddy/Caddyfile` = 0）

## 3. 尚未覆盖

- relay 生产 Caddy 的实际 `handle_path` 路由与 SSH `-R` 隧道（待表决通过后执行，回滚点已备）。
- 手机端 ZCode 同款 WebView 壳（待实现；设置串为 `https://<basic-user>:<basic-pass>@relay.zerg.work/dsh-<random>/`）。
- 断网/重连的端到端表现（本机只覆盖了升级握手、上游故障、失效 cookie 重试）。
- 公网 basic auth 凭据泄露后的轮换流程（改 Caddy 用户/密码 + reload 即生效；DSH token 由代理热更新，不需要重新配置手机）。

## 4. 回滚

1. relay：恢复 `/etc/caddy/Caddyfile.bak-<ts>`，`caddy validate`，`systemctl reload caddy`。
2. Mac：`launchctl unload` 停止 web-remote runner 与 SSH 隧道（或 kill 进程），删除/停用 `~/.dsh-web-remote` 状态目录。
3. Android：不安装新 APK 即回退；DSH 卡片仍走原远程入口（若保留该分支）。
