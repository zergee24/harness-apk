# 家庭交付二期：拍照/语音一级入口与适老化细节 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**背景（2026-09-03）：** 一期（[2026-09-03-life-family-config-phase-1.md](2026-09-03-life-family-config-phase-1.md)）已发布 0.5.0 并在 HiBreak 真机（Android 14 水墨屏，横屏 1680x1264，手势导航）完成导入链路 E2E。用户指示 Task 6 按验收通过处理，Phase 2 立即启动。

**Goal:** 简洁模式下，父母「查问题」的两大高频动作——拍照提问、语音提问——成为生活页一级入口；修复真机发现的贴底按钮无法触达问题。

**范围（4 项）：**

1. **拍照提问一级入口**：简洁模式生活页大按钮 → 新建会话 → 自动拉起相机（权限走标准请求流程），照片挂到输入区等待补充文字或直接发送。
2. **语音提问一级入口**：简洁模式生活页大按钮 → 新建会话 → 自动进入语音输入（错误经聊天页既有错误文案呈现）。
3. **手势区避让**：配置包导出/导入页 Column 追加 `WindowInsets.navigationBars` padding——真机实测贴底按钮（y≈1196/1264）落在手势区内，注入点击与真实触摸均可能无法触达。
4. **过时文案**：搜索能力页「会话里仍需要手动打开“联网”开关才会搜索」→「开启后新会话默认联网，也可在会话里手动开关。」（一期已把新会话默认联网跟随全局开关）。

**实现要点：**

- `Routes.chat` 增加 `openCamera` / `startVoice` 可选 query 参数（默认 false，老路由不变），`chatRouteQuery` 同步扩展。
- `ChatScreen` 新增 `startWithCamera` / `startWithVoice` 参数：`LaunchedEffect` 在进入会话时分别 `cameraPermissionLauncher.launch(Manifest.permission.CAMERA)` 与 `onStartVoiceInput(text, defaultTranscriptionLanguage)`。
- `ConversationListScreen` 简洁模式顶行改为「拍照提问 (weight 1) + 语音提问 (weight 1) + 大号 +」；普通模式不变。

**Non-goals：** TTS 自动朗读（需默认值策略讨论）；回复朗读大按钮；1.3x 大字体全量回归；微信真实传输验证（随 Task 6 真实交付）。

**验收：**

- [x] 简洁模式生活页出现「拍照提问」「语音提问」大按钮；普通模式不出现。
- [x] 点拍照提问 → 新会话 + 相机拉起（相机权限拒绝时出现既有降级文案）；点语音提问 → 新会话 + 语音输入激活。
- [x] 配置包导出/导入页贴底按钮抬升至手势区上方。
- [x] `:app:testDebugUnitTest` 全量通过 + `:app:assembleDebug` 成功。

**实施记录（2026-09-03）：** 四项全部落地；真机（HiBreak）回归确认简洁模式首页两按钮渲染正常、新会话携带参数进入对应流程。
