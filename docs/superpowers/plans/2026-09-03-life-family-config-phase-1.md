# 家庭交付一期：`.hconfig` 配置包与生活简洁模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**决策记录（2026-09-03 讨论定稿）：**

1. **目标用户**：父母，华为手机 50+，使用场景是「查问题」——涉及隐私和高智力要求的提问，不便于使用豆包等消费级 AI。**不是**人物陪伴场景，人物 / Wiki 分发不进本计划。
2. **交付方式**：配置包 + 密钥。生成端（Tony 手机）导出 `.hconfig`，经微信发给父母设备导入；**不走 relay、不当面配机为唯一手段**。
3. **密钥保护**：口令二次加密（AES-GCM），**口令加密的包额外带有效期**（默认 12h，可选 12/24/72h），过期不可导入。密钥不经微信明文落腾讯服务器。
4. **运行环境**：父母手机为 HarmonyOS NEXT + 卓易通（安卓兼容容器）。
5. **不做卓易通真机预验证**（2026-09-03 定）。容器兼容性按最坏情况防御实现：四个已知失效点（Keystore / 系统语音识别 / TTS / 分享路由）全部内置自动降级，**首次真实交付即验证**，不设人工 gate。

**实现期修正（2026-09-03 实现完成时确认）：**

1. **`updateChannel` 从配置包 payload 移除。** 更新通道是构建期注入的 `BuildConfig.UPDATE_MANIFEST_URL`（app/build.gradle.kts 按 buildType 区分 test/prod），不存在运行时开关；「父母走 prod 通道」通过安装 release APK 达成，配置包管不了也不该管。
2. **信封与 payload 为手写 JSON 编解码。** 仓库未启用 kotlinx.serialization 编译插件（只有 runtime 依赖，全仓库无 `@Serializable`），与 `ProviderRepository` 的 `JsonObject` 构建风格一致。
3. **Keystore 回退覆盖全部三个密钥库**（provider / voice / remote，`AppContainer` 统一替换为 `ResilientStringCipher`），而非仅导入路径——容器里任何密钥写入都会走回退，不只 `.hconfig` 导入。
4. **TTS 缺引擎的降级简化**：applier 不改 `ttsEnabled`（保持默认关，聊天页朗读按钮走现有「请先在设置 -> 语音能力启用回复朗读」文案），未新增「设备不支持」设置项展示；如首交付实测需要，二期补。
5. Task 2–5 已实现并通过 `:app:testDebugUnitTest`（1188 例，含 13 例新增：codec 8 + cipher 4 + destinations 1 修订）与 `:app:assembleDebug`；Task 6 留待首次真实交付回填。
6. **联网搜索需三道门全开，配置包补齐两道（2026-09-03 增补）。** 模型自带联网（provider 级 `nativeWebSearchMode`）生效的前提是同时满足：全局「搜索能力」开关（`WebSearchSettings.enabled`）、会话内联网开关（ChatScreen 每次进入默认 false）、查询启发式。配置包原设计只带第三层 → 增补：payload 加 `webSearchEnabled`（applier 写 `setWebSearchEnabled`，导出页默认勾选）；ChatScreen 新增会话默认联网跟随全局开关（`webSearchSettings.enabled && !isAgentConversation`，人物会话仍强制关）——这改变了全局开关开启时所有设备的行为，Tony 自己若习惯手动开关，可在会话内点掉。

**Goal:** 父母拿到手机后，完成「点开微信文件 → 输口令 → 导入配置 → 在生活 tab 问出第一个问题」全程不需要 Tony 在场。生活 tab 对父母收敛为：新建提问 + 最近会话，隐藏开发者词汇入口。

**Architecture:** 新增 `.hconfig` 单文件 JSON 配置包格式（口令 PBKDF2 派生 + AES-256-GCM，有效期入 AAD 防篡改），复用现有分享导入漏斗（`IncomingShareParser` + manifest intent filter 扩展 MIME）。导出 / 导入 UI 挂在「我的」。生活 tab 新增「简洁模式」开关（存储于 AppSettingsStore，由配置包写入，也可手动开关）。

**Tech Stack:** Kotlin / Jetpack Compose；加密只用平台自带 `PBKDF2WithHmacSHA256` + `AES/GCM/NoPadding`（AndroidKeystore 不参与配置包加解密——它只保护本机静态存储，配置包要跨设备迁移，两者无关）。无新增第三方依赖。

---

## 全局约束

- 配置包内明文 payload 只在解密后内存中出现；不写日志、不进崩溃上报、不落临时文件。
- 导入预览界面不得展示 apiKey 全文（只显示 provider 名称 / 地址 / 模型）。
- 口令错误与文件被篡改对外文案必须一致：「口令不正确或文件已损坏」，不区分两种失败。
- 有效期判定使用导入设备系统时间，过期即拒；失败文案提示检查系统时间（老年设备时钟错乱是真实场景）。
- 简洁模式只收敛生活 tab，不新增第四个底部 Tab，不动「我的」结构（产品计划不可协商原则）。

## `.hconfig` 格式定稿

文件扩展名 `.hconfig`，MIME `application/vnd.harness.hconfig`，单文件 JSON（非 zip，内容除 base64 外均为明文结构）：

```json
{
  "kind": "harness.hconfig",
  "version": 1,
  "kdf": { "algo": "PBKDF2WithHmacSHA256", "iterations": 310000, "saltB64": "…" },
  "cipher": "AES/GCM/NoPadding",
  "issuedAtMs": 1790000000000,
  "expiresAtMs": 1790043200000,
  "nonceB64": "…",
  "cipherTextB64": "…"
}
```

- AAD = `kind` + `version` + `issuedAtMs` + `expiresAtMs`（信封字段篡改直接 GCM 校验失败）。
- 解密后 payload（JSON，以 `ConfigPackagePayload` 实现为准）：
  - `providers[]`：name / baseUrl / apiKey（明文，保护核心）/ defaultModel / defaultVisionModel / supportsVision / nativeWebSearchMode / availableModels / customHeaders / customBodyJson；
  - `aliyunVoiceApiKey` / `siliconFlowVoiceApiKey`：语音凭证（可选，null 表示不带）；
  - `webSearchEnabled`：全局「搜索能力」开关（2026-09-03 增补，见实现期修正 6）；
  - `simpleMode`：生活简洁模式；
  - `generatedFrom`：导出端 app 版本，仅诊断用。
- 导出端默认口令生成规则：8 位字母数字、去易混字符（0O1lI），可手改；不接受纯 6 位数字（离线爆破成本不够）。

---

## Task 1: `.hconfig` 编解码与加密模块（已完成 2026-09-03）

**Files:**
- Create: `app/src/main/java/com/harnessapk/packageformat/ConfigPackageCodec.kt`
- Create: `app/src/main/java/com/harnessapk/packageformat/ConfigPackageModels.kt`
- Test: `app/src/test/java/com/harnessapk/packageformat/ConfigPackageCodecTest.kt`

- [x] **Step 1: 写失败测试。** 覆盖：编解码往返一致；AAD 任一字段被改 → 校验失败；口令错误与篡改返回同一错误类型；过期（含恰好等于 expiresAtMs 边界）拒绝；`iterations` / `version` 不认识时拒绝；口令生成规则（8 位、无易混字符、不含纯数字）。
- [x] **Step 2:** 实现 envelope 序列化、PBKDF2（310000 轮、16B salt）、AES-256-GCM（12B nonce）、AAD 绑定、有效期校验、口令生成器。版本字段向前兼容：`version > 1` 拒绝导入并提示升级 app。

## Task 2: 导出（我的 → 配置包）（已完成 2026-09-03）

**Files:**
- Create: `app/src/main/java/com/harnessapk/ui/settings/ConfigPackageExportScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/…/SettingsScreen.kt`（新增「配置包」入口）
- Test: 导出口令确认逻辑 / 有效期默认值单测

- [x] **Step 1:** 导出页：勾选要带上的 Provider 配置（默认勾选当前默认 profile）、语音凭证开关（已配置则默认开）、有效期选择 12h（默认）/ 24h / 72h、口令输入 + 随机生成按钮 + 确认口令。
- [x] **Step 2:** 生成 `.hconfig` 经 `FileProvider` 拉系统分享（微信发送）。导出完成页显示有效期倒计时提醒（「请在 12 小时内导入」）。

## Task 3: 导入链路（分享漏斗 + 应用内兜底）（已完成 2026-09-03）

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`（VIEW / SEND intent filter 增加 `application/vnd.harness.hconfig`）
- Modify: `app/src/main/java/com/harnessapk/capture/IncomingShareParser.kt`（识别 `.hconfig` 并路由）
- Create: `app/src/main/java/com/harnessapk/ui/settings/ConfigPackageImportScreen.kt`

- [x] **Step 1:** 导入页：展示将应用的内容（provider 名称 / 地址 / 模型、语音、更新通道、简洁模式，不显示 key）→ 口令输入 → 解密校验。
- [x] **Step 2:** 失败文案定稿：口令错 / 篡改 =「口令不正确或文件已损坏」；过期 =「配置包已过期。请检查手机系统时间是否正确；若时间正确，请让家人重新发一份」。
- [x] **Step 3:** 应用内兜底入口：「我的 → 配置包 → 导入」，从文件选择器选 `.hconfig`（微信分享路由失败时的主路径）。

## Task 4: 导入落地（应用配置 + 容器防御性降级 + 完成引导）（已完成 2026-09-03）

**Files:**
- Create: `app/src/main/java/com/harnessapk/configpackage/ConfigPackageApplier.kt`（或并入 packageformat，实施时定）
- Modify: `app/src/main/java/com/harnessapk/security/ApiKeyCipher.kt`（软件密钥回退）
- Test: provider upsert / 通道切换 / simpleMode 写入 / Keystore 回退单测

- [x] **Step 1:** 应用规则：provider 按 name + baseUrl upsert，导入的第一个设为默认；语音凭证写入 `VoiceCredentialStore`；更新通道写 prod；simpleMode 写入 AppSettingsStore。
- [x] **Step 2: Keystore 回退（卓易通防御点 1）。** 导入保存 key 时 AndroidKeyStore 初始化或加解密失败 → 自动回退软件密钥（应用沙箱保护），行为静默、不阻塞导入；在 HANDOFF 记录安全权衡（回退后静态保护强度 = 应用沙箱，弱于硬件 keystore）。
- [x] **Step 3: 语音路径自检（防御点 2 / 3）。** 导入完成后检测：无系统 recognition service 且已导入阿里云凭证 → 语音输入默认走阿里云；TTS 引擎缺失 → 「回复朗读」设置项显示「设备不支持」，聊天页入口隐藏。若现有降级逻辑已覆盖，仅补文案。
- [x] **Step 4:** 完成后落到生活 tab，展示一次性引导：「配置完成。点下方 + 试试问一个问题」。导入成功后立即清除内存中的明文 payload。

## Task 5: 生活简洁模式 UI（已完成 2026-09-03）

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/settings/SettingsScreen.kt`（「简洁模式」手动开关）
- Test: `ConversationListUiState` 简洁模式过滤单测

- [x] **Step 1:** 简洁模式开：QuickEntryRow 隐藏「智能体 / 知识库」与全局搜索 icon，仅保留大号「新建对话」按钮；会话列表与顶栏不动（减少改动面，活动铃铛保留）。
- [x] **Step 2:** 简洁模式关：维持现状。开关只在「我的」，生活 tab 内不出现任何模式提示。

## Task 6: 端到端验收（首次真实交付时执行）

> 2026-09-03：用户指示按验收通过处理，Phase 2 已启动；真实交付后的实测结论仍应回填 6.5（卓易通容器行为）。

本任务不阻塞开发合并；验收发生在第一次真实给父母发包交付时，验收记录回填此处。

- [ ] **6.1** Tony 手机导出（含语音凭证、12h）→ 微信 → 父母手机导入，全程 ≤ 5 分钟且 Tony 无需到场操作。
- [ ] **6.2** 导入后：问一个文字问题（联网搜索默认开）、问一个语音问题、拍一张药盒提问；流式回复正常。
- [ ] **6.3** 过期包导入被拒，文案可懂；错误口令多次被拒不锁死（无防暴力需求，有效期已是主防线）。
- [ ] **6.4** Tony 自己的生活 tab（简洁模式关）无任何回归；工作 / 我的 tab 回归抽查。
- [ ] **6.5** 卓易通容器实测结论回填决策记录 #4 旁：Keystore 是否走回退、语音走哪条路径、TTS 是否可用、微信分享路由是否成功。

---

## Non-goals（一期）

- 卓易通真机预验证（决策 #5：首次交付即验证，降级已内置）。
- 拍照提问一级入口、语音入口强化（Phase 2）；全面错误文案适老化与 1.3x 大字体布局验证（Phase 2）。
- 扫码配对、家庭 relay、多用户 / 会话隔离。
- 人物（`.hagent`）/ 知识库（`.hwiki`）进配置包分发。
- HarmonyOS 原生（ArkTS）应用改造。
- 配置包的发布者签名（口令 AEAD + 有效期已覆盖威胁模型；签名是可选的二期加固）。

## 风险与开放问题

1. **容器行为未预验证（决策 #5 的代价）**：卓易通内问题只会在首次交付时暴露，且 HarmonyOS 6/7 无法经卓易通调试 APK，远程修复成本高。缓解：四个失效点的降级已在 Task 4 内置；导入链路失败文案必须自解释（「让家人重新发一份」覆盖最常见的远程可恢复场景）；Task 6.5 的实测结论是二期是否需要真机验证的依据。
2. **微信更新可能改变文件分享行为**：兜底路径（应用内导入）保证不阻塞交付。
3. **父母设备时钟错乱**：过期文案已含系统时间提示；导入成功页顺带展示「当前时间」便于核对。
4. **口令传递依赖电话 / 当面念**：若实测老年人口令输入错误率高，二期考虑口令改为 4 组词语（汉字口令对 50+ 更友好），一期不预做。
5. **ApiKeyCipher 软件回退是安全降级**：回退路径必须有测试覆盖且只在 Keystore 真实失败时触发，避免在正常设备上悄然弱化保护。
