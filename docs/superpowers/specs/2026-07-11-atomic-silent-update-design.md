# 原子静默更新修复设计

## 背景与已确认根因

应用最近经常出现首次更新失败、再次进入更新页后成功。现场检查确认线上对象本身当前可访问，但发布与客户端均存在竞态。

### 发布端竞态

当前每次发布覆盖固定路径：

- `app-debug.apk` / `app-release.apk`
- `chunks/*.part-NNN`
- `update.json`

2026-07-11 的 test 发布中，APK 于 06:49:20 UTC 写入，首片于 06:49:24 写入，末片于 06:50:15 写入，manifest 于 06:50:18 写入。近一分钟内，旧 manifest 仍指向正在逐个覆盖的固定分片，客户端可能拼接出新旧混合 APK，最终校验失败。

### 客户端竞态

根 Compose 页面启动后会静默下载；用户进入更新页时，页面还会独立触发下载。两个调用写入相同缓存路径，并都先删除目标文件，可能发生截断、删除和校验竞态。

### 错误恢复不足

- manifest 和每个分片只有一次请求机会。
- 任一请求失败会删除全部下载结果。
- 启动任务用 `getOrNull()` 丢弃错误，更新页无法展示后台失败原因。
- 当前“后台”只是前台进程中的 `LaunchedEffect`，不是 WorkManager 或长期系统后台任务。

## 目标

1. 发布过程中任何时刻，已发布 manifest 只引用完整、不可变的一组 APK 资源。
2. 同一进程、同一版本最多只有一个实际下载任务。
3. manifest 和分片遇到短暂网络错误时自动有限重试。
4. APK 只在完整下载并通过 SHA-256 后才成为可安装文件。
5. 静默下载失败原因可在更新页看到，并可通过重新进入或显式重试再次下载。
6. 保持现有 `test/update.json`、`prod/update.json` 地址和旧客户端解析兼容。

## 非目标

- 本轮不接入 WorkManager，不承诺应用进程被系统杀死后继续下载。
- 本轮不实现 HTTP Range 断点续传。
- 本轮不自动删除 OSS 上的历史版本目录。
- 本轮不绕过 Android 系统安装器或未知来源安装权限。

## 发布端设计

### 版本化不可变资源

`prepare_apk_release.py` 生成以下结构：

```text
update.json
releases/<versionCode>/app-debug.apk
releases/<versionCode>/chunks/app-debug.apk.part-000
releases/<versionCode>/chunks/app-debug.apk.part-001
...
```

prod 使用相同结构和 `app-release.apk`。manifest 中的 `apkUrl` 与 `apkChunks` 全部指向 `releases/<versionCode>/...`。

旧客户端只依赖 manifest 中的 URL，不要求固定文件名，因此可以直接下载版本化路径。顶层 `update.json` 地址保持不变。

### manifest 最后提交

`upload_to_oss.py` 明确分两阶段上传：

1. 上传除 `update.json` 外的全部 APK 和分片。
2. 所有资源上传成功后，最后上传 `update.json`。

不能只依赖字典序碰巧让 manifest 排在最后。若任一资源上传失败，脚本退出且旧 manifest 保持不变。

### 历史版本

历史 `releases/<versionCode>` 保留，确保正在下载旧 manifest 的客户端不会因新发布而丢失资源。清理策略后续单独设计。

## 客户端设计

### 应用级下载协调器

新增 `UpdateDownloadCoordinator`，由 `AppContainer` 创建单例，根页面和更新页共同使用。

协调器依赖一个窄接口，便于并发测试而不模拟整个 Repository：

```kotlin
fun interface UpdateArtifactDownloader {
    fun downloadApk(manifest: UpdateManifest): ApkDownloadResult
}
```

`UpdateRepository` 实现该接口；协调器另接收 IO dispatcher，并在内部切换线程。

状态：

```kotlin
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val versionCode: Int) : UpdateDownloadState
    data class Ready(val versionCode: Int, val result: ApkDownloadResult) : UpdateDownloadState
    data class Failed(val versionCode: Int, val message: String) : UpdateDownloadState
}
```

协调器使用 `Mutex` 包围同版本下载：

- 已有相同版本 `Ready` 且文件存在时直接复用。
- 一个调用正在下载时，后续调用等待同一临界区，不再启动第二次网络下载。
- 下载成功写入 `Ready`。
- 下载失败写入 `Failed` 并继续抛出原错误，调用方决定是否提示。

根页面负责启动静默下载，但不再持有独立 `downloadedUpdate`。更新页观察协调器状态，复用下载结果、进度语义和失败原因。

### 原子缓存文件

`UpdateRepository.downloadApk()` 使用：

- 临时文件：`harness-apk-<versionCode>.apk.part`
- 最终文件：`harness-apk-<versionCode>.apk`

流程：

1. 删除旧临时文件，不删除已校验的最终文件。
2. 若最终文件存在且 SHA-256 匹配 manifest，直接复用。
3. 顺序写入临时文件。
4. 校验临时文件 SHA-256。
5. 校验成功后使用 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` 原子替换最终文件；文件系统不支持原子移动时回退到 `REPLACE_EXISTING`。
6. 失败只删除临时文件。

版本码进入文件名，避免相同 `versionName` 的测试构建互相覆盖。

### 有限重试

manifest 与每个 APK URL 最多尝试 3 次：

- 网络异常、HTTP 408、429 和 5xx 可以重试。
- 其他 4xx 直接失败。
- 退避间隔为 300ms、900ms；延迟函数可注入，单元测试不真实等待。
- 最终错误包含请求阶段、分片序号和最后一次异常或 HTTP 状态。

SHA-256 不匹配不在同一次任务中盲目重试整个 APK，避免掩盖发布错误；协调器记录失败，用户重试时重新开始。

## UI 数据流

启动：

```text
fetch manifest -> 更新红点 -> coordinator.download(manifest)
                              -> Downloading / Ready / Failed
```

更新页：

- `Downloading`：显示“正在后台下载…”和进度条。
- `Ready`：打开系统安装器；若缺少权限，进入未知来源设置。
- `Failed`：显示后台失败原因，并提供“重试下载”按钮。
- `Idle` 且存在更新：调用同一个 coordinator，不创建独立下载任务。

## 测试设计

### 发布脚本

1. manifest URL 含 `releases/<versionCode>/`。
2. 生成目录中的 APK 与分片位于版本目录。
3. uploader dry-run 顺序保证 `update.json` 最后。
4. 资源上传失败时不会执行 manifest 上传。

### Repository

1. manifest 首次 500、第二次成功时返回成功。
2. 某分片首次断连、第二次成功时正确重组 APK。
3. 非重试 404 只请求一次。
4. 下载先写 `.part`，SHA 失败后最终文件不被破坏。
5. 已存在且 SHA 正确的最终文件直接复用，不发网络请求。

### Coordinator

1. 两个并发调用同一版本只调用 Repository 一次。
2. 成功状态被后续页面复用。
3. 失败状态保留可读错误，下一次显式重试可以成功。
4. 不同版本不会复用旧 APK。

### UI 与集成

1. 启动动作和更新页共享 coordinator 状态。
2. 后台失败文案可见且存在重试按钮。
3. Ready 状态只对同一 SHA 启动一次安装器。
4. 运行全部单元测试、Debug APK 构建和 Android 模拟器测试。

## 验收标准

- 发布物不再覆盖 manifest 正在引用的 APK/分片。
- 同版本并发请求的实际下载次数为 1。
- 可重试的首次网络失败能自动恢复。
- 校验失败不会破坏已验证的最终 APK。
- 更新页能展示静默下载失败原因并重试。
- 旧客户端仍通过不变的顶层 manifest URL 获取更新。
