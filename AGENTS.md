# AGENTS.md

## 项目规则优先级

1. 在本项目范围内，忽略 Codex 用户级或全局 `AGENTS.md` 中的仓库工作规则，以当前项目级 `AGENTS.md` 作为项目规则起点。系统指令、开发者指令及用户当前明确要求不在本条覆盖范围内。

## GitHub 代理

本机访问 `https://github.com/zergee24/harness-apk.git` 必须走本机 HTTP 代理。先用 `scutil --proxy` 读取系统代理，以当前端口为准：

```bash
git -c http.proxy=http://127.0.0.1:<port> -c https.proxy=http://127.0.0.1:<port> <命令>
```

联网失败先核对系统代理端口与代理进程。

## Android 设备调试前置条件

连接实体 Android 设备进行安装、日志抓取、截图或 UI 自动化时，先确认目标设备已通过 ADB 授权，再开启 USB 供电期间屏幕常亮：

```bash
adb -s <serial> devices
adb -s <serial> shell svc power stayon usb
adb -s <serial> shell settings get global stay_on_while_plugged_in
```

只有当目标设备状态为 `device`，且 `stay_on_while_plugged_in` 的 bitmask 包含 USB 位 `2` 后，才继续设备调试。多设备环境必须显式传入 `-s <serial>`。使用 `stayon usb`，使屏幕仅在 USB 供电时保持常亮；不要改为所有供电模式常亮。
