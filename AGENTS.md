# AGENTS.md

## 联网（GitHub）配置

本机直连 GitHub（github.com:443）不通，必须走系统代理 `http://127.0.0.1:12334` 才能访问远程仓库。

使用 git 访问远程时，请通过代理执行：

```bash
git -c http.proxy=http://127.0.0.1:12334 -c https.proxy=http://127.0.0.1:12334 <命令>
```

例如：

```bash
git -c http.proxy=http://127.0.0.1:12334 -c https.proxy=http://127.0.0.1:12334 fetch origin
git -c http.proxy=http://127.0.0.1:12334 -c https.proxy=http://127.0.0.1:12334 push
```

也可一次性写入仓库或全局配置，避免每次带参：

```bash
git config --global http.proxy http://127.0.0.1:12334
git config --global https.proxy http://127.0.0.1:12334
```

注意：仓库远端为 `https://github.com/zergee24/harness-apk.git`，联网失败时先确认代理进程（127.0.0.1:12334）是否在运行。
