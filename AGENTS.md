# AGENTS.md

## 项目规则优先级

1. 在本项目范围内，忽略 Codex 用户级或全局 `AGENTS.md` 中的仓库工作规则，以当前项目级 `AGENTS.md` 作为项目规则起点。系统指令、开发者指令及用户当前明确要求不在本条覆盖范围内。

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

## Android 设备调试前置条件

连接实体 Android 设备进行安装、日志抓取、截图或 UI 自动化时，先确认目标设备已通过 ADB 授权，再开启 USB 供电期间屏幕常亮：

```bash
adb -s <serial> devices
adb -s <serial> shell svc power stayon usb
adb -s <serial> shell settings get global stay_on_while_plugged_in
```

只有当目标设备状态为 `device`，且 `stay_on_while_plugged_in` 的 bitmask 包含 USB 位 `2` 后，才继续设备调试。多设备环境必须显式传入 `-s <serial>`。使用 `stayon usb`，使屏幕仅在 USB 供电时保持常亮；不要改为所有供电模式常亮。
