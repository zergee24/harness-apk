# relay 新镜像部署指引（配置包中继端点上线）

> 给执行 agent 的任务书。目标：把 relay.zerg.work 的 relay 镜像更新到包含
> 配置包中继端点（`/v1/config-blobs`）的最新代码，并完成验收。
> 浏览器当前已登录阿里云控制台，区域 cn-hangzhou，当前页 = ECS 实例详情。

## 背景

- relay.zerg.work 跑在阿里云 ECS（cn-hangzhou）上：Mac bridge / 手机 ↔ HTTPS/WSS ↔ 中继 ↔ codex app-server
- 部署方式：服务器上的仓库 checkout 内 `cd remote/deploy && docker compose up -d --build`
  （compose `build: ..`，Dockerfile 在仓库 `remote/Dockerfile`；前置 Caddy/Nginx 把 443 转到 127.0.0.1:8080）
- 本次新增：配置包中继端点 `remote/cmd/relay/configblobs.go`
  （POST/GET/DELETE `/v1/config-blobs`，commit 1a652a2 引入，test/main 均已推送）
- 现状：服务器镜像仍旧版，`POST /v1/config-blobs` 返回 404

## 操作步骤（浏览器 Workbench 路线）

1. **关闭产品面板**：实例详情页左侧「Products and Services」面板，按 Escape 或点外部关闭。
2. **连终端**：实例详情页找「远程连接」区（或实例列表页该实例操作列）的
   「Workbench 远程连接」/「立即登录」→ 新 tab 打开网页终端。
   若要求密码：输入该 ECS 的 root/用户密码（实例创建时设置，agent 不掌握，向用户索要）。
3. **定位仓库与 compose 目录**（服务器上 checkout 位置未知，按序探测）：
   ```bash
   # 方法 A：从运行中容器读 compose 工作目录（最可靠）
   C=$(docker ps -q --filter "ancestor=harness-apk-remote" | head -1)
   [ -z "$C" ] && C=$(docker ps -q | head -1)
   docker inspect "$C" --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'
   # 方法 B：全盘找
   sudo find / -maxdepth 6 -type d -name deploy -path "*remote*" 2>/dev/null
   ```
   记下仓库根目录 REPO（deploy 的上一级，即包含 `remote/` 的目录）。
4. **更新代码**：
   ```bash
   cd $REPO && git status --short && git log --oneline -1
   git pull origin main   # 或服务器 clone 所跟踪的分支；main 已含全部改动
   git log --oneline -1   # 应 ≥ 42c09f7，且存在 remote/cmd/relay/configblobs.go
   ```
   git pull 失败（凭证/网络）→ 用「备选：手动同步代码」。
5. **重建并启动**：
   ```bash
   cd $REPO/remote/deploy
   docker compose up -d --build
   docker compose ps
   docker compose logs --tail 20 relay
   ```
6. **服务器上自验**：
   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/healthz          # 200
   curl -s -X POST http://127.0.0.1:8080/v1/config-blobs \
        -H "Content-Type: application/json" -d '{}' -w " [%{http_code}]\n"        # 400 = 新端点生效
   ```

## 验收（公网，Mac 或手机）

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://relay.zerg.work/healthz                 # 200
curl -s -X POST https://relay.zerg.work/v1/config-blobs \
     -H "Content-Type: application/json" -d "{}" -w " [%{http_code}]\n"                  # 400 = 部署成功；404 = 镜像仍旧
```

端到端（手机）：我的 → 配置包 → 「通过中继传送（免发文件）」→ 生成领取码
（XXXX-XXXX + 倒计时）→ 配置包页第 1 步输入领取码 → 从中继领取 → 自动解密
→ 应用配置。领取后再输原码应提示无效（领取即焚）。

## 回滚

```bash
cd $REPO/remote && git checkout <上一个提交> && cd deploy && docker compose up -d --build
```
回滚后行为：`/v1/config-blobs` 404，配对/远程控制/心跳不受影响。

## 备选：手动同步代码（服务器 git pull 不可用时）

方法 A（推荐）：Mac 上打包差异并 base64，经 Workbench 终端 base64 -d 还原：
```bash
# Mac：导出本次新增/修改的文件
git archive origin/main remote/cmd/relay/configblobs.go remote/cmd/relay/main.go remote/cmd/relay/configblobs_test.go | gzip > /tmp/relay-patch.tgz
base64 -i /tmp/relay-patch.tgz -o /tmp/relay-patch.b64
# Workbench 终端：粘贴 base64 内容到 /tmp/relay-patch.b64（可用分块 cat >> 追加）
base64 -d /tmp/relay-patch.b64 | tar -xzf - -C $REPO   # 覆盖 remote/ 下文件
```
方法 B：文件小，直接在终端用 `cat > 文件 <<'EOF'` 逐个重建（configblobs.go 约 200 行，
main.go 改动 3 处：server struct 加 `blobs *configBlobStore`、main() 加
`s.blobs = newConfigBlobStore()` + `s.sweepConfigBlobs()`、路由注册 3 行）。

## 红线

- `remote/deploy/.env`（bootstrap token / 阿里云 push 凭证）不读取、不修改、不上传
- `relay-data` volume 不删（配对凭证、路由元数据、一次性票据）
- 不动前置 Caddy/Nginx 配置；不动 443 证书
