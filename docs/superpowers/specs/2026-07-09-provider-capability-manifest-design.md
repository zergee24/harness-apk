# Provider Capability Manifest Design

## 背景

Harness APK 当前支持本地 Provider 配置、API key 加密存储、默认供应商和模型、模型上下文窗口、压缩比例、vision 和模型内搜索配置。随着 Kimi、DeepSeek、OpenAI 中转、GLM、语音、联网、reasoning effort、上下文窗口不断变化，继续把模型清单和能力硬编码在 `ProviderTemplates.kt` 会导致两个问题：

1. 每次模型名、上下文、搜索参数、reasoning effort 变化都要发新版 APK。
2. 聊天页无法可靠知道某个模型到底支持图片、联网、推理强度、语音、工具调用、最大输出、超时时间等能力。

RikkaHub 的公开说明显示它支持多供应商、自定义 API/URL/模型、自定义 HTTP header/body、搜索、语音和多模态。这类客户端如果想长期稳定，必须把“模型 ID 列表”和“模型能力”作为一等配置，而不是 UI 里的自由文本。

参考资料：

- RikkaHub README provider/search/speech/Markdown 能力说明，https://github.com/rikkahub/rikkahub/blob/master/README_ZH_CN.md
- RikkaHub AGENTS 模块结构：`ai`、`search`、`speech`、`workspace` 等模块，https://github.com/rikkahub/rikkahub/blob/master/AGENTS.md

## 当前状态

当前代码的关键入口：

- `ProviderTemplates.defaults` 硬编码 Kimi、DeepSeek、OpenAI、GLM。
- `ProviderProfile` 包含 `defaultModel`、`defaultVisionModel`、`supportsVision`、`nativeWebSearchMode`、`availableModels`、`modelConfigs`。
- `ModelConfig` 只有 `id`、`contextWindowTokens`、`compressionThresholdPercent`。
- `ProviderProfileEntity.availableModels` 用换行字符串编码 `id|contextWindow|compressionThreshold`。
- `defaultModelConfig()` 通过 provider/model 字符串前缀猜测上下文窗口。
- `OpenAiCompatibleClient` 只支持固定的 OpenAI-compatible chat completions body，并根据 `NativeWebSearchMode` 拼接搜索参数。

## 问题定义

1. 能力字段不足
   目前模型只知道上下文和压缩阈值，无法表达 reasoning effort、vision、audio、web search、tool calling、max output、temperature 策略、timeout、endpoint 兼容性。

2. 能力来源不清
   同一个模型能力可能来自内置模板、远程 manifest、用户手动覆盖或接口拉取。当前 UI 无法展示来源，也无法一键恢复默认。

3. 更新成本高
   DeepSeek、GLM、Kimi、OpenAI 中转的默认模型变化需要改代码和打 APK。

4. UI 配置不够结构化
   模型配置列表现在更像表单，用户难以按模型维护 context/compression/search/reasoning 等能力。

5. 请求构造缺少能力约束
   不支持 reasoning effort 的模型仍可能被传 reasoning 参数；不支持 vision 的模型需要发送前拦截；不同 provider 的 web search body 不应散落在聊天层。

## 目标

1. 模型能力中心化
   每个模型都有独立能力记录，聊天、搜索、语音、图片、上下文压缩、诊断日志都从同一个 capability resolver 获取信息。

2. 远程 manifest 可更新
   模型清单和能力可以通过 OSS 上的 JSON manifest 更新，不需要发新版 APK。

3. 本地覆盖优先
   用户可以编辑 provider、模型和能力字段，本地覆盖不会被远程更新覆盖。

4. 默认供应商/模型明确
   设置页有一级默认供应商和默认模型配置，聊天新会话默认使用它。

5. 请求构造受能力约束
   不支持的参数不会被发送；缺能力时 UI 隐藏或禁用对应按钮。

6. 兼容旧数据
   现有 provider 配置、API key、模型列表和上下文设置必须平滑迁移。

## 非目标

- 不把用户 API key 上传到云端。
- 不在第一阶段做统一后端代理。
- 不自动用用户 key 扫描所有 provider 的 `/models`，避免产生费用和隐私风险。
- 不承诺远程 manifest 永远准确，用户本地仍可覆盖。

## 总体方案

采用“四层合并”的模型能力系统：

1. Bundled Catalog
   APK 内置保底 provider/model capability JSON。

2. Remote Catalog
   从自建 OSS 拉取最新 provider capability manifest。

3. Provider Instance
   用户保存的 provider 实例，包括 base URL、API key、enabled、默认模型。

4. Local Overrides
   用户对某个 provider/model 的本地能力覆盖。

最终聊天使用：

```text
ResolvedModelCapability =
  Bundled Catalog
  + Remote Catalog
  + Provider Instance
  + Local Overrides
```

越靠后优先级越高。

## Manifest 托管

### URL

建议和 APK 更新同域托管：

```text
https://www.zerg.work/harness-apk/catalog/provider-capabilities.json
https://www.zerg.work/harness-apk/catalog/provider-capabilities.sha256
```

测试通道可使用：

```text
https://www.zerg.work/harness-apk/test/provider-capabilities.json
```

### 拉取策略

- App 启动后后台检查，不阻塞进入聊天。
- 打开模型配置页时检查一次。
- 同一通道 24 小时内最多自动检查一次。
- 手动“更新模型清单”不受 24 小时限制。
- 网络超时 3 秒。
- 下载后校验 sha256。
- 解析失败保留旧缓存。
- 没网时使用本地缓存；没有缓存时使用 bundled catalog。

### 版本策略

字段：

- `schemaVersion`
- `catalogVersion`
- `generatedAt`
- `minAppVersionCode`
- `providers`

规则：

- App 不认识的字段忽略。
- `schemaVersion` 大版本不兼容时拒绝使用 remote catalog。
- `catalogVersion` 用于诊断日志和 UI 展示。

## Manifest Schema

### ProviderCatalog

```json
{
  "schemaVersion": 1,
  "catalogVersion": "2026.07.09.1",
  "generatedAt": "2026-07-09T00:00:00+08:00",
  "providers": []
}
```

### ProviderTemplateV2

```json
{
  "providerId": "openai",
  "displayName": "OpenAI",
  "baseUrl": "https://happycode.vip/v1",
  "auth": {
    "type": "bearer"
  },
  "compatibility": {
    "chatEndpoint": "openai_chat_completions",
    "streamProtocol": "sse_openai",
    "modelsEndpoint": "openai_models"
  },
  "defaultModelId": "gpt-5.5",
  "models": []
}
```

### ModelCapability

```json
{
  "modelId": "gpt-5.5",
  "displayName": "GPT 5.5",
  "aliases": ["gpt-5.5"],
  "contextWindowTokens": 200000,
  "maxOutputTokens": 32000,
  "defaultCompressionThresholdPercent": 70,
  "inputModalities": ["text", "image"],
  "outputModalities": ["text"],
  "supportsStreaming": true,
  "supportsReasoningEffort": true,
  "reasoningEffortOptions": ["low", "medium", "high", "xhigh"],
  "defaultReasoningEffort": "high",
  "webSearch": {
    "mode": "openai_web_search_options",
    "defaultEnabled": false
  },
  "toolCalling": {
    "supported": false
  },
  "timeouts": {
    "connectMs": 15000,
    "readMs": 180000,
    "writeMs": 30000
  },
  "temperature": {
    "default": 0.2,
    "editable": true
  }
}
```

### Capability 字段定义

| 字段 | 用途 |
|---|---|
| `contextWindowTokens` | 上下文状态、自动压缩阈值、请求前估算 |
| `maxOutputTokens` | 后续输出长度控制 |
| `defaultCompressionThresholdPercent` | 每模型默认压缩比例 |
| `inputModalities` | 控制图片、文档、语音输入入口 |
| `outputModalities` | 控制 TTS、图像输出等后续能力 |
| `supportsReasoningEffort` | 控制 OpenAI reasoning selector 是否展示 |
| `reasoningEffortOptions` | 支持 `low/medium/high/xhigh` 等选项 |
| `webSearch.mode` | 控制请求 body 中搜索参数 |
| `toolCalling.supported` | 后续 MCP/技能工具调用入口 |
| `timeouts` | 模型级超时策略，默认读超时 180s |
| `temperature` | 请求默认 temperature 和是否允许编辑 |

## 本地数据模型

### ProviderCatalogEntity

- `id: String`
- `source: BUNDLED | REMOTE`
- `catalogVersion: String`
- `schemaVersion: Int`
- `json: String`
- `sha256: String?`
- `fetchedAt: Long`
- `status: ACTIVE | FAILED | IGNORED`
- `errorMessage: String?`

### ProviderProfileEntity 扩展

新增字段：

- `providerTemplateId: String?`
- `catalogVersion: String?`
- `customHeadersJson: String`
- `customBodyJson: String`

保留：

- API key 加密字段不变。
- base URL、本地 enabled 和默认模型不被 remote catalog 覆盖。

### ModelCapabilityOverrideEntity

- `id: String`
- `providerProfileId: String`
- `modelId: String`
- `displayNameOverride: String?`
- `contextWindowTokensOverride: Int?`
- `maxOutputTokensOverride: Int?`
- `compressionThresholdPercentOverride: Int?`
- `inputModalitiesOverrideJson: String?`
- `outputModalitiesOverrideJson: String?`
- `reasoningEffortOverrideJson: String?`
- `webSearchModeOverride: String?`
- `timeoutOverrideJson: String?`
- `hidden: Boolean`
- `createdAt: Long`
- `updatedAt: Long`

### ResolvedModelCapability

Domain 层只暴露 resolve 后结果：

- `providerProfileId`
- `providerName`
- `modelId`
- `displayName`
- `capabilitySourceSummary`
- `contextWindowTokens`
- `compressionThresholdPercent`
- `maxOutputTokens`
- `inputModalities`
- `outputModalities`
- `reasoningEffort`
- `webSearchMode`
- `timeouts`
- `requestCompatibility`

## 合并规则

1. provider 匹配顺序：
   - `providerTemplateId`
   - provider name case-insensitive
   - base URL host

2. model 匹配顺序：
   - exact `modelId`
   - alias exact
   - case-insensitive exact

3. capability 优先级：
   - local override 非空字段最高。
   - provider instance 默认模型高于 catalog default。
   - remote catalog 高于 bundled catalog。
   - 仍缺失时使用 conservative fallback。

4. conservative fallback：
   - `contextWindowTokens = 200000`
   - `compressionThresholdPercent = 70`
   - `supportsStreaming = true`
   - `inputModalities = ["text"]`
   - `supportsReasoningEffort = false`
   - `webSearch.mode = "disabled"`
   - `readTimeoutMs = 180000`

5. 用户删除/隐藏模型：
   - remote catalog 更新不会重新显示 hidden model，除非用户点“恢复默认”。

## UI 设计

### 模型配置首页

结构：

- 默认模型区域
  - 默认供应商。
  - 默认模型。
  - 当前 catalog version。
  - 更新模型清单按钮。

- Provider 列表
  - 名称。
  - base URL host。
  - enabled 状态。
  - 默认模型。
  - 模型数量。
  - 本地覆盖数量。

- 新增 Provider
  - 从模板新增。
  - 自定义 OpenAI-compatible。

### Provider 详情页

分区：

1. 基本信息
   - 名称。
   - Base URL。
   - API key。
   - 启用/停用。
   - 自定义 headers/body，高级区折叠。

2. 默认模型
   - 下拉选择模型。
   - 支持搜索模型 ID。

3. 模型能力列表
   - 每个模型一行。
   - 显示 context window 数据条。
   - 显示压缩阈值 slider 或数据条。
   - 图标显示 vision/search/reasoning/audio/tool。
   - 本地覆盖显示“已覆盖”标记。
   - 支持隐藏、编辑、恢复默认。

4. 从接口获取模型
   - 用户手动点击。
   - 使用当前 API key 请求 `/models`。
   - 只导入模型 ID，不猜测完整能力。
   - 新发现模型使用 fallback capability，并提示可手动编辑。

### 模型编辑弹窗

字段：

- 模型 ID。
- 显示名称。
- 上下文窗口 tokens。
- 最大输出 tokens。
- 自动压缩阈值百分比。
- 输入能力：文本、图片、文档、音频。
- 输出能力：文本、图片、音频。
- 推理强度：关闭、低、中、高、超高。
- 联网搜索：关闭、OpenAI web search options、enable_search、GLM tool、外部 Bing。
- 超时：读超时默认 180s。
- 恢复默认。

交互：

- 数据条展示上下文和压缩阈值，不用让用户在一堆数字框里迷路。
- 高级字段默认折叠。
- 保存前校验数值范围。

### 聊天页联动

- 模型选择器只展示 enabled provider 和未 hidden model。
- OpenAI 且模型支持 reasoning effort 时展示推理强度，默认高，包含超高。
- 模型不支持 search 或全局搜索关闭时，不展示联网按钮。
- 模型不支持 vision 时，图片入口可展示但发送前明确提示；如果用户要求极简，也可直接隐藏。
- 上下文状态 chip 使用 resolved capability 的 context window。
- 诊断日志包含 capability source 和 catalog version。

## 请求构造

### Request Builder

新增 `ModelAwareRequestBuilder`：

输入：

- `ProviderWithKey`
- `ResolvedModelCapability`
- `UiMessage` 或 outgoing messages
- `ChatRequestOptions`

输出：

- `ChatRequest`
- `RequestDiagnostics`

规则：

- reasoning effort 只有支持时才发送。
- web search 只有 session 开启、全局开启、模型支持时才发送。
- image part 只有模型支持 image input 时才发送。
- timeout 使用模型级配置。
- custom headers/body 在最后合并，但不能覆盖 Authorization。
- 不支持的选项写入 diagnostics，而不是静默失败。

### Web Search Modes

支持：

- `disabled`
- `openai_web_search_options`
- `enable_search_boolean`
- `glm_web_search_tool`
- `external_bing`

`external_bing` 不进入 provider request body，而是在发送前由本地搜索模块生成 search context。

## Manifest 发布流程

### 文件位置

仓库新增：

```text
catalog/provider-capabilities.json
catalog/provider-capabilities.schema.json
catalog/provider-capabilities.test.json
```

发布：

- GitHub Actions 上传到 OSS。
- 生成 sha256。
- test/prod 可使用不同 catalog。

### 更新规范

每次更新模型清单必须记录：

- provider。
- modelId。
- 变更字段。
- 信息来源 URL。
- 更新时间。

不确定字段用 conservative fallback，不能编造。

## 迁移策略

### 阶段 1：兼容当前字段

- `ModelConfig` 保留。
- 新增 `ResolvedModelCapability`，从现有 `ModelConfig` 构造。
- UI 仍能读写旧 provider。

### 阶段 2：引入 catalog

- 内置 bundled JSON，内容等价当前 `ProviderTemplates.defaults`。
- `ProviderTemplates` 改为从 bundled catalog 生成。
- 增加 remote catalog 拉取和缓存。

### 阶段 3：本地 overrides

- 模型详情页写入 `ModelCapabilityOverrideEntity`。
- 旧 `availableModels` 字符串迁移为 model override 或 provider instance model list。

### 阶段 4：请求层切换

- `OpenAiCompatibleClient` 不再直接看 `ProviderProfile.nativeWebSearchMode`，而是使用 resolved capability。
- `ChatRequestOptions` 增加 resolved capability 引用或快照。

### 阶段 5：清理旧字段

- 一个版本周期后，`availableModels` 字符串只作为兼容缓存。
- 新 UI 完全基于 capability list。

## 测试策略

### 单元测试

- `ProviderCatalogParserTest`
  - schemaVersion 解析。
  - unknown fields ignored。
  - incompatible schema rejected。

- `ModelCapabilityResolverTest`
  - bundled fallback。
  - remote 覆盖 bundled。
  - local override 覆盖 remote。
  - hidden model 不显示。
  - alias 匹配。

- `ProviderMigrationTest`
  - 旧 `availableModels` 字符串迁移。
  - API key 加密字段不变。
  - 默认供应商/模型保留。

- `ModelAwareRequestBuilderTest`
  - unsupported reasoning 不发送参数。
  - OpenAI web search body。
  - Kimi `enable_search`。
  - GLM tool body。
  - external Bing 注入 search context。
  - no vision model 拦截 image。

- `ProviderSettingsUiStateTest`
  - 默认模型区域。
  - 模型能力列表排序。
  - 数据条百分比。
  - 本地覆盖和恢复默认。

### 网络测试

- catalog 下载成功。
- sha256 mismatch 回退旧缓存。
- 解析失败回退旧缓存。
- 3 秒超时不阻塞设置页。

### 手工 QA

1. 新装 App，默认模板可用。
2. 配置 OpenAI happycode，默认 gpt-5.5 context 200k，reasoning 默认高且有超高。
3. 配置 Kimi，模型清单和 context 正确。
4. 配置 DeepSeek，模型清单来自 catalog。
5. 手动更新 catalog 后，新模型出现。
6. 本地修改某模型 context 后，远程更新不覆盖。
7. 恢复默认后，远程能力重新生效。

## 验收标准

1. 不改 APK 的情况下，可以通过 OSS manifest 更新 provider/model 能力。
2. 模型配置页能逐模型展示 context window 和压缩阈值数据条。
3. 每个模型能独立维护 vision/search/reasoning/audio/tool/timeout 能力。
4. 本地覆盖优先于远程 manifest。
5. 旧 provider 配置和 API key 不丢失。
6. 聊天页只展示当前模型支持的能力入口。
7. OpenAI gpt-5.5 默认 context 为 200k，reasoning 默认高，并支持超高选项。
8. catalog 下载失败不影响聊天。
9. 诊断日志能说明使用的是 bundled、remote 还是 local override capability。

## 风险与取舍

- 远程 manifest 可能过期：保留本地覆盖和接口手动获取模型 ID。
- schema 过度设计：第一阶段只落最常用字段，但 JSON schema 预留扩展。
- UI 变复杂：默认只展示模型列表和关键数据条，高级能力折叠。
- 兼容迁移复杂：通过 resolver 先兼容旧字段，再逐步迁移存储。
- Provider 非 OpenAI-compatible：第一阶段仍以 OpenAI-compatible 为主，通过 `compatibility.chatEndpoint` 预留扩展。
