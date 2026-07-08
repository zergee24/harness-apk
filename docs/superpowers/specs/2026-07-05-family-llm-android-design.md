# 家用 LLM Android APK 设计规格

## 目标

构建一个家人自用的 Android APK，用于在手机端和国内外 LLM 进行多轮对话。第一版重点是稳定聊天、本地配置、本地历史、截图输入和自建 APK 更新。后续再扩展云端历史、多模态和更完整的家庭管理能力。

## 背景和约束

- 分发方式：自建 APK，不走应用商店。
- 更新机制：App 可下载新版 APK，校验后拉起系统安装器。
- 技术路线：原生 Android，Kotlin + Jetpack Compose。
- 供应商范围：第一版统一支持 OpenAI-compatible Chat Completions 风格接口。
- API Key：由用户在手机本地填写，并使用 Android Keystore 加密保存。
- 使用对象：家庭成员，默认不假设用户具备调试或开发经验。
- 数据策略：第一版不做云端同步，会话和消息保存在手机本地。

## 第一版范围

第一版必须包含：

- 多会话聊天。
- 文本消息发送、流式回复展示和失败重试。
- 会话列表、会话重命名、删除会话。
- 本地保存会话和消息历史。
- Provider 配置管理，包括名称、Base URL、模型、是否支持图片。
- 本地加密保存 API Key。
- 截图或图片选择入口，发送前由用户确认。
- 自建版本检测、更新提示、APK 下载、SHA-256 校验和系统安装器拉起。
- 设置页，包括 Provider 管理、默认模型、更新检测、历史清理和隐私提示。

第一版不包含：

- 云端同步会话历史。
- 用户账号和登录。
- 家庭共享后台。
- 静默安装或后台无感升级。
- 应用商店发布。
- Agent 工具调用。
- 文档、音频、视频等复杂多模态输入。
- 付款、订阅或额度管理。

## 产品结构

App 使用四个主入口：

1. 会话列表：展示本地会话，支持新建、搜索、重命名和删除。
2. 聊天页：展示消息流，支持输入文本、选择截图、切换模型、重新生成和复制回复。
3. Provider 设置：维护 OpenAI-compatible Provider 配置和 API Key。
4. 更新设置：检查当前版本、展示新版本、下载进度和安装动作。

导航结构采用单 Activity + Compose Navigation。手机竖屏为第一适配目标。

## 技术架构

App 分为五层：

- UI 层：Jetpack Compose 页面和状态展示。
- Domain 层：会话管理、消息发送、Provider 选择、更新判断。
- Data 层：Room 保存会话和消息，DataStore 保存非密设置。
- Security 层：Android Keystore 管理密钥，负责 API Key 加密和解密。
- Network 层：LLM API 客户端、图片 payload 适配、更新 manifest 下载和 APK 下载。

模块边界：

- `chat`：会话、消息、发送流程和聊天 UI。
- `provider`：Provider 配置、模型配置和 API Key 管理。
- `updater`：版本检测、下载、校验和安装器拉起。
- `storage`：Room、DataStore 和加密存储基础能力。
- `common`：错误模型、日志、时间工具和 UI 基础组件。

## 数据模型

本地 Room 保存以下核心表：

### Conversation

- `id`：本地 UUID。
- `title`：会话标题。
- `createdAt`：创建时间。
- `updatedAt`：更新时间。
- `defaultProviderId`：默认 Provider，可为空。
- `defaultModel`：默认模型，可为空。
- `isArchived`：是否归档。

### Message

- `id`：本地 UUID。
- `conversationId`：所属会话。
- `role`：`system`、`user`、`assistant` 或 `error`。
- `content`：文本内容。
- `status`：`pending`、`streaming`、`succeeded` 或 `failed`。
- `providerId`：实际使用的 Provider。
- `model`：实际使用的模型。
- `createdAt`：创建时间。
- `updatedAt`：更新时间。
- `errorCode`：失败码，可为空。
- `errorMessage`：脱敏后的失败信息，可为空。

### MessageAttachment

- `id`：本地 UUID。
- `messageId`：所属消息。
- `type`：第一版仅支持 `image`。
- `uri`：本地内容 URI 或缓存文件 URI。
- `mimeType`：图片 MIME 类型。
- `createdAt`：创建时间。

### ProviderProfile

- `id`：本地 UUID。
- `name`：展示名称，例如 Kimi、DeepSeek、OpenAI。
- `baseUrl`：OpenAI-compatible Base URL。
- `apiKeyAlias`：加密密钥引用，不保存明文 Key。
- `defaultModel`：默认文本模型。
- `defaultVisionModel`：默认图片模型，可为空。
- `supportsVision`：是否启用图片输入。
- `enabled`：是否启用。
- `createdAt`：创建时间。
- `updatedAt`：更新时间。

## Provider 适配

第一版只实现 OpenAI-compatible Chat Completions 适配器。Provider 配置中的 Base URL、模型和 Vision 开关可编辑。

发送文本消息时，请求结构遵循 Chat Completions 的常见形态：

- `model`
- `messages`
- `stream`
- `temperature`

发送截图或图片时，Network 层通过 ProviderProfile 判断是否支持 Vision。支持 Vision 的 Provider 使用图片 content part；不支持 Vision 时，UI 阻止发送并提示用户切换模型或 Provider。

第一版内置 Provider 模板：

- OpenAI：用户填写 Base URL 和 Key。
- Kimi：预置常见 Base URL，用户填写 Key 和模型。
- DeepSeek：预置常见 Base URL，用户填写 Key 和模型。
- 自定义：完全手动填写 Base URL、模型和能力开关。

内置模板只是配置快捷入口，不写死供应商逻辑。实际请求都走统一适配器。

## API Key 安全

API Key 不进入 Room、日志、崩溃信息、导出文件或截图。

保存流程：

1. 用户在 Provider 设置页输入 API Key。
2. App 使用 Android Keystore 生成或读取本机密钥。
3. App 加密 API Key。
4. App 只保存密文和 `apiKeyAlias`。
5. 发送请求前短暂解密到内存，完成后不持久化明文。

设置页展示 API Key 时只显示脱敏结果，例如 `sk-****abcd`。复制、导出和分享配置默认不包含 API Key。

## 会话和消息流程

发送文本消息：

1. 用户在聊天页输入内容。
2. App 创建本地 user message，状态为 `succeeded`。
3. App 创建 assistant message，状态为 `pending`。
4. Domain 层选择 Provider 和模型。
5. Network 层发起流式请求。
6. UI 随流式返回更新 assistant message。
7. 请求成功后，assistant message 状态变为 `succeeded`。
8. 请求失败后，assistant message 状态变为 `failed`，保留可重试入口。

发送截图消息：

1. 用户选择系统图片或截图文件。
2. App 展示预览和隐私确认。
3. 用户确认后，App 将图片作为附件关联到 user message。
4. Network 层根据 Provider Vision 能力构造请求。
5. 如果 Provider 不支持图片，消息不发送，并给出可执行提示。

重试逻辑：

- 用户消息重试会复用原始用户内容和附件。
- 失败的 assistant message 不删除，重试生成新的 assistant message。
- 重试前允许用户切换 Provider 或模型。

## 更新机制

更新服务第一版使用静态 manifest 文件，不要求后端服务。

发布物托管要求：

- manifest 和 APK 都放在对象存储中，通过 HTTPS 自定义域名访问。
- 推荐路径为 `https://download.example.com/harness-apk/update.json` 和 `https://download.example.com/harness-apk/releases/<versionName>/app-release.apk`。
- 可选云服务为阿里云 OSS 或腾讯云 COS，两者都满足第一版要求。
- 不依赖 OSS/COS 默认 bucket 域名分发 APK，避免 APK 下载限制和域名稳定性问题。
- 如果 bucket 位于中国内地，自定义域名需要按云厂商要求完成 ICP 备案。
- 首版发布文件可以公开读；安全性依赖 HTTPS、APK 签名和 manifest 中的 SHA-256 校验。

Manifest 示例：

```json
{
  "versionCode": 2,
  "versionName": "0.1.1",
  "minSupportedVersionCode": 1,
  "apkUrl": "https://example.com/harness-apk-0.1.1.apk",
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "releaseNotes": [
    "修复对话发送失败",
    "优化截图上传"
  ],
  "publishedAt": "2026-07-05T00:00:00Z"
}
```

更新流程：

1. App 启动后延迟检查更新，避免阻塞首屏。
2. 用户也可以在设置页手动检查。
3. App 下载 manifest 并比较 `versionCode`。
4. 如果当前版本小于 `minSupportedVersionCode`，进入强制更新模式。
5. 用户确认下载后，App 下载 APK 到私有缓存目录。
6. 下载完成后计算 SHA-256。
7. 校验通过后，通过 FileProvider 获取安装 URI。
8. App 拉起系统安装器。
9. 如果系统要求“安装未知应用”权限，App 跳转到对应授权页面并在返回后继续安装。

更新失败处理：

- manifest 下载失败：提示网络失败，保留重试。
- APK 下载失败：保留重试，不删除当前可用版本。
- SHA-256 不匹配：删除下载文件，提示安装包校验失败。
- 用户取消安装：不重复弹窗骚扰，设置页保留安装入口。
- 强制更新失败：允许重新下载或退出 App，不继续使用不兼容版本。

## 错误处理

错误统一映射为面向用户的状态：

- Provider 未配置：引导进入 Provider 设置。
- API Key 不存在或解密失败：要求重新输入 API Key。
- 请求超时：提示重试或切换网络。
- 供应商鉴权失败：提示检查 API Key。
- 模型不存在：提示切换模型。
- 余额或限额不足：展示供应商返回的脱敏说明。
- 图片不支持：提示切换支持图片的模型或移除图片。
- 更新校验失败：删除安装包并要求重新下载。

技术日志可以记录错误类别、HTTP 状态码和请求 ID，但不能记录 API Key、完整 prompt、图片内容或供应商返回的敏感正文。

## 隐私和权限

第一版需要的权限和能力：

- 网络访问。
- 图片选择器或系统文档选择器。
- 安装 APK 相关授权引导。
- 通知权限仅在下载进度需要后台展示时申请。

隐私原则：

- 截图或图片必须由用户主动选择。
- 图片发送前必须显示预览和确认。
- 本地历史默认保留在设备内。
- 删除会话会删除本地消息和附件引用。
- 不做后台自动上传。

## 测试策略

单元测试：

- ProviderProfile 校验。
- Chat request 构造。
- Vision 能力判断。
- API Key 加密保存和解密读取。
- 更新版本比较。
- SHA-256 校验。
- 错误映射。

集成测试：

- Room 会话和消息读写。
- DataStore 设置读写。
- 模拟 OpenAI-compatible 服务的文本回复和流式回复。
- 更新 manifest 下载和 APK 校验。

UI 测试：

- 新建会话并发送文本消息。
- Provider 未配置时的引导。
- 选择图片后确认发送。
- 更新可用时的提示、下载和校验失败状态。

人工验收：

- 在一台真实 Android 手机上安装首版 APK。
- 配置至少一个真实 Provider。
- 完成一次文本多轮对话。
- 完成一次截图或图片对话。
- 通过静态 manifest 触发一次更新检查。
- 下载测试 APK，校验通过后拉起系统安装器。

## 后续扩展

云端历史：

- 增加家庭后端。
- 增加账号或设备绑定。
- 本地 Room 作为离线缓存。
- 服务端保存会话、消息、附件元数据。

更多多模态：

- 扩展 MessageAttachment 类型。
- 增加文档、音频和视频处理。
- Provider 能力从布尔值改为能力矩阵。

家庭共享：

- 后端代理统一管理 Provider Key。
- 家庭成员无需本地填写 Key。
- 后端做额度、审计和模型路由。

升级增强：

- 支持灰度版本。
- 支持多渠道 manifest。
- 支持回滚提示。
- 支持更新包 CDN 镜像。

## 验收标准

第一版完成时必须满足：

- 用户能安装 APK 并打开 App。
- 用户能新增 Provider 并加密保存 API Key。
- 用户能创建多个会话。
- 用户能完成文本多轮对话。
- 用户能选择截图或图片，并在确认后发送给支持 Vision 的 Provider。
- 用户能看到失败原因，并能重试失败消息。
- 用户能在设置页手动检查更新。
- App 能识别新版本 manifest。
- App 能下载 APK，并校验 SHA-256。
- App 能拉起系统安装器。
- API Key 不出现在数据库明文、日志、错误提示或导出内容中。
