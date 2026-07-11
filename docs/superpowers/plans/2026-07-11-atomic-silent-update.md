# 原子静默更新实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除发布过程中混合分片、客户端重复下载同一缓存文件，以及首次网络波动导致静默更新失败的问题。

**Architecture:** 发布端将 APK 和分片放入 `releases/<versionCode>/` 不可变目录，并显式最后上传顶层 `update.json`。客户端用原子临时文件、三次有限重试和应用级 `UpdateDownloadCoordinator` 统一启动页与更新页的下载状态。

**Tech Stack:** Python 3 `unittest`、Kotlin 2.3、Coroutines/StateFlow/Mutex、OkHttp 5.4、Android Compose、JUnit 4、Gradle 9.6.1。

## Global Constraints

- `test/update.json` 与 `prod/update.json` 公共地址保持不变。
- 不接入 WorkManager，不承诺进程退出后继续下载。
- 不实现 HTTP Range 断点续传。
- 不自动删除 OSS 历史版本目录。
- 不新增第三方依赖。
- manifest 与分片最多尝试 3 次；只重试网络异常、HTTP 408、429 和 5xx。
- SHA-256 不匹配不在同一次任务里自动重下整个 APK。
- 本轮提交不自动推送远端。

## 文件结构

- 新建 `scripts/tests/test_release_pipeline.py`：发布目录和上传顺序回归测试。
- 修改 `scripts/prepare_apk_release.py`：生成版本化不可变资源。
- 修改 `scripts/upload_to_oss.py`：显式把 manifest 排在最后。
- 修改 `scripts/release_apk.sh`：从版本目录重组校验。
- 修改 `docs/release-hosting.md`：记录新的 OSS 结构和原子发布约束。
- 修改 `app/src/main/java/com/harnessapk/updater/UpdateRepository.kt`：重试、缓存复用、临时文件和原子替换。
- 新建 `app/src/main/java/com/harnessapk/updater/UpdateDownloadCoordinator.kt`：应用级 single-flight 与共享状态。
- 修改 `app/src/main/java/com/harnessapk/common/AppContainer.kt`：创建协调器单例。
- 修改 `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`：启动静默下载改用协调器。
- 修改 `app/src/main/java/com/harnessapk/ui/updater/UpdateSettingsScreen.kt`：观察共享状态并提供重试。
- 修改对应 Kotlin 单元测试和 Android Compose 测试。

---

### Task 1: 生成版本化发布资源

**Files:**
- Create: `scripts/tests/test_release_pipeline.py`
- Modify: `scripts/prepare_apk_release.py:56-99`
- Modify: `scripts/release_apk.sh:159-172`
- Modify: `docs/release-hosting.md`

**Interfaces:**
- Consumes: `prepare_apk_release.py` 现有 CLI 参数。
- Produces: `update.json` 与 `releases/<versionCode>/<artifact>` 目录结构。

- [ ] **Step 1: 添加失败的 Python 发布目录测试**

```python
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

def load_script(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, REPO_ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module

class PrepareReleaseTest(unittest.TestCase):
    def test_outputs_versioned_immutable_urls(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk = root / "app-debug.apk"
            apk.write_bytes(b"apk-payload")
            output = root / "release"
            subprocess.run([
                sys.executable, "scripts/prepare_apk_release.py",
                "--apk", str(apk),
                "--output-dir", str(output),
                "--public-base-url", "https://example.com/harness-apk/test",
                "--version-code", "1016036",
                "--version-name", "0.1.16-debug",
                "--artifact-name", "app-debug.apk",
            ], cwd=REPO_ROOT, check=True)

            manifest = json.loads((output / "update.json").read_text())
            self.assertEqual(
                "https://example.com/harness-apk/test/releases/1016036/app-debug.apk",
                manifest["apkUrl"],
            )
            self.assertTrue(all("/releases/1016036/chunks/" in url for url in manifest["apkChunks"]))
            self.assertTrue((output / "releases/1016036/app-debug.apk").is_file())
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
python3 -m unittest scripts.tests.test_release_pipeline.PrepareReleaseTest -v
```

Expected: FAIL，旧输出仍在根目录且 URL 不含 `releases/1016036`。

- [ ] **Step 3: 修改 release 生成结构**

在 `prepare_apk_release.py` 中使用：

```python
release_prefix = Path("releases") / str(args.version_code)
release_dir = output_dir / release_prefix
chunks_dir = release_dir / "chunks"
release_dir.mkdir(parents=True, exist_ok=True)
hosted_apk = release_dir / artifact_name
shutil.copy2(apk_path, hosted_apk)

manifest = {
    "apkUrl": f"{base_url}/{release_prefix.as_posix()}/{artifact_name}",
    "apkChunks": [
        f"{base_url}/{release_prefix.as_posix()}/chunks/{name}"
        for name in chunk_names
    ],
}
```

保留原 manifest 的版本、SHA、说明和时间字段。

- [ ] **Step 4: 更新本地重组校验路径**

`release_apk.sh` 使用：

```bash
RELEASE_DIR="$OUTPUT_DIR/releases/$VERSION_CODE"
cat "$RELEASE_DIR/chunks/${ARTIFACT_NAME}.part-"* > "build/release-oss/${CHANNEL}-reassembled.apk"
```

- [ ] **Step 5: 运行 Python 测试确认 GREEN**

Run:

```bash
python3 -m unittest scripts.tests.test_release_pipeline.PrepareReleaseTest -v
```

Expected: PASS。

- [ ] **Step 6: 更新发布文档并提交**

文档必须明确顶层只有可变 `update.json`，APK/分片位于版本目录且历史目录不覆盖。

```bash
git add scripts/tests/test_release_pipeline.py scripts/prepare_apk_release.py scripts/release_apk.sh docs/release-hosting.md
git commit -m '修复：发布版本化不可变 APK 资源'
```

### Task 2: 显式最后上传 manifest

**Files:**
- Modify: `scripts/tests/test_release_pipeline.py`
- Modify: `scripts/upload_to_oss.py:101-130`

**Interfaces:**
- Produces: `ordered_release_files(source_dir: Path) -> list[Path]`。
- Consumes: Task 1 的版本化发布目录。

- [ ] **Step 1: 添加失败的上传顺序测试**

```python
class UploadOrderTest(unittest.TestCase):
    def test_manifest_is_always_last(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "update.json").write_text("{}")
            asset = root / "releases/2/chunks/app.part-000"
            asset.parent.mkdir(parents=True)
            asset.write_bytes(b"part")

            module = load_script("upload_to_oss", "scripts/upload_to_oss.py")
            ordered = module.ordered_release_files(root)

            self.assertEqual(root / "update.json", ordered[-1])
            self.assertEqual([asset, root / "update.json"], ordered)
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
python3 -m unittest scripts.tests.test_release_pipeline.UploadOrderTest -v
```

Expected: ERROR 或 FAIL，因为 helper 尚不存在。

- [ ] **Step 3: 实现显式排序并用于上传**

```python
def ordered_release_files(source_dir: Path) -> list[Path]:
    files = sorted(path for path in source_dir.rglob("*") if path.is_file())
    manifests = [path for path in files if path.relative_to(source_dir).as_posix() == "update.json"]
    assets = [path for path in files if path not in manifests]
    return assets + manifests
```

`main()` 的上传循环必须遍历 `ordered_release_files(source_dir)`。若资源上传抛错，Python 控制流不会进入最后的 manifest 上传。

- [ ] **Step 4: 运行全部 Python 测试**

Run:

```bash
python3 -m unittest scripts.tests.test_release_pipeline -v
```

Expected: 全部 PASS。

- [ ] **Step 5: 提交 uploader 修复**

```bash
git add scripts/tests/test_release_pipeline.py scripts/upload_to_oss.py
git commit -m '修复：确保更新清单最后上传'
```

### Task 3: Repository 重试和原子 APK 文件

**Files:**
- Modify: `app/src/test/java/com/harnessapk/updater/UpdateRepositoryTest.kt`
- Modify: `app/src/main/java/com/harnessapk/updater/UpdateRepository.kt`

**Interfaces:**
- Produces: `UpdateArtifactDownloader.downloadApk(manifest)`、可注入 `retryDelay`、版本码缓存文件。

- [ ] **Step 1: 添加失败的重试测试**

```kotlin
@Test
fun fetchManifestRetriesTransientServerFailure() {
    val attempts = AtomicInteger()
    val repository = repository(
        manifestUrl = "https://download.example.com/update.json",
        okHttpClient = clientResponding { request ->
            if (attempts.incrementAndGet() == 1) response(request, 500, "temporary")
            else response(request, 200, manifestJson())
        },
        retryDelay = {},
    )

    val result = repository.fetchManifest()

    assertTrue(result.updateAvailable)
    assertEquals(2, attempts.get())
}

@Test
fun downloadRetriesTransientChunkFailureWithoutDuplicatingBytes() {
    val partOneAttempts = AtomicInteger()
    val repository = repository(
        okHttpClient = clientResponding { request ->
            when (request.url.encodedPath) {
                "/part-0" -> response(request, 200, "a")
                "/part-1" -> if (partOneAttempts.incrementAndGet() == 1) {
                    response(request, 500, "temporary")
                } else {
                    response(request, 200, "pk")
                }
                else -> error("Unexpected ${request.url}")
            }
        },
        retryDelay = {},
    )

    val result = repository.downloadApk(manifest(apkUrl = null, apkChunks = listOf(
        "https://download.example.com/part-0",
        "https://download.example.com/part-1",
    ), sha256 = sha256("apk")))

    assertEquals("apk", result.file.readText())
    assertEquals(2, partOneAttempts.get())
}
```

- [ ] **Step 2: 添加原子文件与缓存复用失败测试**

```kotlin
@Test
fun checksumFailureKeepsPreviouslyVerifiedFinalFile() {
    val final = File(temp.root, "updates/harness-apk-2.apk").apply {
        parentFile.mkdirs()
        writeText("verified")
    }
    val repository = repository(okHttpClient = clientReturning("broken"), retryDelay = {})

    runCatching { repository.downloadApk(manifest(sha256 = sha256("expected"))) }

    assertEquals("verified", final.readText())
    assertFalse(File(temp.root, "updates/harness-apk-2.apk.part").exists())
}

@Test
fun validFinalFileIsReusedWithoutNetworkCall() {
    val requests = AtomicInteger()
    val final = File(temp.root, "updates/harness-apk-2.apk").apply {
        parentFile.mkdirs()
        writeText("apk")
    }
    val repository = repository(
        okHttpClient = clientResponding { request ->
            requests.incrementAndGet()
            response(request, 500, "unexpected")
        },
    )

    val result = repository.downloadApk(manifest(sha256 = sha256("apk")))

    assertEquals(final, result.file)
    assertEquals(0, requests.get())
}
```

- [ ] **Step 3: 运行定向测试确认 RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.updater.UpdateRepositoryTest' --console=plain
```

Expected: 新测试因无重试、旧文件名和删除逻辑失败。

- [ ] **Step 4: 实现窄接口、重试分类和原子文件**

```kotlin
fun interface UpdateArtifactDownloader {
    fun downloadApk(manifest: UpdateManifest): ApkDownloadResult
}

class UpdateRepository(...) : UpdateArtifactDownloader {
    private fun <T> retrying(label: String, block: () -> T): T {
        var last: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                if (!error.isRetryableUpdateFailure() || attempt == maxAttempts - 1) throw error
                last = error
                retryDelay(if (attempt == 0) 300L else 900L)
            }
        }
        throw checkNotNull(last)
    }
}
```

HTTP 408、429、5xx 抛内部 retryable exception；其他非 2xx 直接 `AppError.Update`。每个分片尝试前记录输出流位置，失败后 `channel.truncate(startOffset)` 并复位 position，防止重试追加重复字节。

最终文件名使用 `versionCode`，临时文件后缀 `.part`。SHA 成功后 `Files.move` 原子替换，`AtomicMoveNotSupportedException` 时回退普通替换。

- [ ] **Step 5: 运行 Repository 测试确认 GREEN**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.updater.UpdateRepositoryTest' --console=plain
```

Expected: 全部 PASS。

- [ ] **Step 6: 提交 Repository 修复**

```bash
git add app/src/main/java/com/harnessapk/updater/UpdateRepository.kt app/src/test/java/com/harnessapk/updater/UpdateRepositoryTest.kt
git commit -m '修复：更新下载增加重试与原子缓存'
```

### Task 4: 应用级 single-flight 下载协调器

**Files:**
- Create: `app/src/main/java/com/harnessapk/updater/UpdateDownloadCoordinator.kt`
- Create: `app/src/test/java/com/harnessapk/updater/UpdateDownloadCoordinatorTest.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`

**Interfaces:**
- Consumes: Task 3 的 `UpdateArtifactDownloader`。
- Produces: `UpdateDownloadState` StateFlow 与 `suspend fun download(manifest)`。

- [ ] **Step 1: 添加并发 single-flight 失败测试**

```kotlin
@Test
fun concurrentSameVersionDownloadsOnlyOnce() = runBlocking {
    val calls = AtomicInteger()
    val started = CountDownLatch(1)
    val gate = CountDownLatch(1)
    val file = temp.newFile("app.apk")
    val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    val coordinator = UpdateDownloadCoordinator(
        downloader = UpdateArtifactDownloader {
            calls.incrementAndGet()
            started.countDown()
            gate.await(5, TimeUnit.SECONDS)
            ApkDownloadResult(file, "sha")
        },
        ioDispatcher = dispatcher,
    )

    val first = async { coordinator.download(manifest()) }
    assertTrue(started.await(5, TimeUnit.SECONDS))
    val second = async { coordinator.download(manifest()) }
    gate.countDown()

    assertEquals(first.await(), second.await())
    assertEquals(1, calls.get())
    dispatcher.close()
}
```

- [ ] **Step 2: 添加状态复用与失败重试测试**

```kotlin
@Test
fun failedDownloadCanRetryAndBecomeReady() = runTest {
    val calls = AtomicInteger()
    val file = temp.newFile("retry.apk")
    val coordinator = UpdateDownloadCoordinator(
        downloader = UpdateArtifactDownloader {
            if (calls.incrementAndGet() == 1) error("首次网络失败")
            ApkDownloadResult(file, "sha")
        },
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    assertFailsWith<IllegalStateException> { coordinator.download(manifest()) }
    assertEquals(UpdateDownloadState.Failed(2, "首次网络失败"), coordinator.state.value)

    assertEquals(file, coordinator.download(manifest()).file)
    assertTrue(coordinator.state.value is UpdateDownloadState.Ready)
    assertEquals(2, calls.get())
}

@Test
fun readyFileIsReusedButDifferentVersionDownloadsAgain() = runTest {
    val calls = AtomicInteger()
    val file = temp.newFile("ready.apk")
    val coordinator = UpdateDownloadCoordinator(
        downloader = UpdateArtifactDownloader {
            calls.incrementAndGet()
            ApkDownloadResult(file, "sha")
        },
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    coordinator.download(manifest(versionCode = 2))
    coordinator.download(manifest(versionCode = 2))
    coordinator.download(manifest(versionCode = 3))

    assertEquals(2, calls.get())
}
```

测试类使用 `TemporaryFolder`，并提供返回 HTTPS URL、指定 versionCode 和 SHA 的 `manifest()` helper。

- [ ] **Step 3: 运行测试确认 RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.updater.UpdateDownloadCoordinatorTest' --console=plain
```

Expected: 编译失败，因为 coordinator 尚不存在。

- [ ] **Step 4: 实现协调器**

```kotlin
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val versionCode: Int) : UpdateDownloadState
    data class Ready(val versionCode: Int, val result: ApkDownloadResult) : UpdateDownloadState
    data class Failed(val versionCode: Int, val message: String) : UpdateDownloadState
}

class UpdateDownloadCoordinator(
    private val downloader: UpdateArtifactDownloader,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()

    suspend fun download(manifest: UpdateManifest): ApkDownloadResult = mutex.withLock {
        val ready = _state.value as? UpdateDownloadState.Ready
        if (ready?.versionCode == manifest.versionCode && ready.result.file.exists()) return ready.result
        _state.value = UpdateDownloadState.Downloading(manifest.versionCode)
        try {
            withContext(ioDispatcher) { downloader.downloadApk(manifest) }.also { result ->
                _state.value = UpdateDownloadState.Ready(manifest.versionCode, result)
            }
        } catch (error: Throwable) {
            _state.value = UpdateDownloadState.Failed(
                manifest.versionCode,
                error.message ?: "更新下载失败",
            )
            throw error
        }
    }
}
```

- [ ] **Step 5: 在 AppContainer 创建单例并运行测试**

```kotlin
val updateDownloadCoordinator = UpdateDownloadCoordinator(
    downloader = updateRepository,
    ioDispatcher = dispatchers.io,
)
```

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.updater.UpdateDownloadCoordinatorTest' --console=plain
```

Expected: 全部 PASS。

- [ ] **Step 6: 提交协调器**

```bash
git add app/src/main/java/com/harnessapk/updater/UpdateDownloadCoordinator.kt app/src/test/java/com/harnessapk/updater/UpdateDownloadCoordinatorTest.kt app/src/main/java/com/harnessapk/common/AppContainer.kt
git commit -m '修复：统一应用更新下载任务'
```

### Task 5: UI 共享下载状态和失败重试

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt:121-144,320-326`
- Modify: `app/src/main/java/com/harnessapk/ui/updater/UpdateSettingsScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/updater/UpdateUiState.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/updater/UpdateUiStateTest.kt`

**Interfaces:**
- Consumes: Task 4 的 `UpdateDownloadCoordinator.state` 与 `download()`。
- Produces: 可测试的 `updateDownloadStatusText(state)` 和 `canRetryUpdateDownload(state)`。

- [ ] **Step 1: 添加 UI 状态失败测试**

```kotlin
@Test
fun failedBackgroundDownloadShowsReasonAndRetry() {
    val state = UpdateDownloadState.Failed(6, "安装包分片 2/23 下载失败：HTTP 500")
    assertEquals("安装包分片 2/23 下载失败：HTTP 500", updateDownloadStatusText(state))
    assertTrue(canRetryUpdateDownload(state))
}

@Test
fun downloadingAndReadyStatesAreNotRetryable() {
    assertFalse(canRetryUpdateDownload(UpdateDownloadState.Downloading(6)))
    assertFalse(canRetryUpdateDownload(UpdateDownloadState.Idle))
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.ui.updater.UpdateUiStateTest' --console=plain
```

Expected: 编译失败，因为状态 helper 尚不存在。

- [ ] **Step 3: 实现状态 helper 并接入根页面**

```kotlin
internal fun updateDownloadStatusText(state: UpdateDownloadState): String? = when (state) {
    UpdateDownloadState.Idle -> null
    is UpdateDownloadState.Downloading -> "正在后台下载更新..."
    is UpdateDownloadState.Ready -> "下载完成，正在打开系统安装器..."
    is UpdateDownloadState.Failed -> state.message
}

internal fun canRetryUpdateDownload(state: UpdateDownloadState): Boolean =
    state is UpdateDownloadState.Failed
```

根页面删除 `downloadedUpdate`，启动下载改为：

```kotlin
runCatching { container.updateDownloadCoordinator.download(manifest) }
```

进入更新页只传 `initialResult`；下载结果和失败统一来自 coordinator StateFlow。

- [ ] **Step 4: 更新页面观察共享状态**

页面使用：

```kotlin
val downloadState by container.updateDownloadCoordinator.state.collectAsState()
val downloaded = (downloadState as? UpdateDownloadState.Ready)?.result
```

`LaunchedEffect(result?.manifest?.versionCode, downloadState)` 只在存在更新且当前版本不是 Downloading/Ready 时调用 coordinator。Failed 时不自动循环重试；显示错误和按钮，按钮点击后显式 launch `download(manifest)`。

- [ ] **Step 5: 运行 UI 状态测试和 Android 测试编译**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.ui.updater.UpdateUiStateTest' :app:compileDebugAndroidTestKotlin --console=plain
```

Expected: PASS 且 Android 测试编译成功。

- [ ] **Step 6: 提交 UI 修复**

```bash
git add app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/main/java/com/harnessapk/ui/updater/UpdateSettingsScreen.kt app/src/main/java/com/harnessapk/ui/updater/UpdateUiState.kt app/src/test/java/com/harnessapk/ui/updater/UpdateUiStateTest.kt
git commit -m '修复：展示静默更新失败并支持重试'
```

### Task 6: 完整验证和发布产物检查

**Files:**
- Modify only if verification exposes a task-related defect.

**Interfaces:**
- Consumes: Tasks 1-5 的最终状态。

- [ ] **Step 1: 运行 Python 发布测试**

```bash
python3 -m unittest scripts.tests.test_release_pipeline -v
```

- [ ] **Step 2: 生成本地 test 发布物并检查 manifest**

```bash
scripts/release_apk.sh test
python3 -m json.tool build/release-oss/test/update.json
```

断言 manifest URL 含当前 versionCode 目录，重组 APK 与 Debug APK 一致。

- [ ] **Step 3: 强制重跑完整单元测试和 Debug 构建**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL`，0 failed。

- [ ] **Step 4: 启动 API 36 模拟器并运行全部 Android 测试**

```bash
emulator -avd harness_api36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
./gradlew :app:connectedDebugAndroidTest --rerun-tasks --console=plain
```

Expected: 全部测试通过，0 failed。

- [ ] **Step 5: 检查最终差异和提交状态**

```bash
git diff --check
git status --short --branch
git log -8 --oneline
```

Expected: 工作区干净；当前 `test` 分支保留提交，不推送。
