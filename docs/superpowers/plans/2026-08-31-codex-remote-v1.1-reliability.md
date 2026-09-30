# Codex 远程控制 v1.1 可靠性修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复海信 A5CC 实机审计发现的「新建线程静默失败 + 列表缺状态/时间 + 详情页标题与双返回 + item 标签外露 + bridge WSS 卡死」五个问题，让 Codex 远程控制从「发起面可用」升级为「监督面基本可靠」。

> **审查记录：** 已经反方审查（challenger）压测，结论「有条件通过」。P0-1（看门狗吞迟到响应 + 超时预算过低）、P1-1（pendingCommands 非线程安全）、P1-2（turn.start 失败不清工作态）、P1-3（跨线程 turn/started 覆写 activeTurnId）均已并入本计划 Task 3/4/5；P2-1（乐观条目被列表冲掉）、P2-3（系统返回手势）、P2-4（install 属主）、P2-5（pendingCreateCwd 失败清理）也已在对应 Task 落点。bridge 心跳并发与 steer `return nil` 两处担忧经源码核实不成立。

**Architecture:** 全部改动分两棵树：Android App（主仓 `/Users/tony/Documents/harness-apk`，生产 APK 的源码）与 Mac bridge（`/Users/tony/Documents/harness-apk/.worktrees/m4-multi-backend-bridge/remote`，生产二进制的源码）。App 侧治「静默」：给命令加超时看门狗、thread.start 响应健壮解析 + 乐观插入、列表补相对时间与运行中徽标、item 标签本地化、详情页标题/去双返回；bridge 侧治「卡死」：WSS 心跳 + turn.steer 吞错回发。

**Tech Stack:** Kotlin / Jetpack Compose / Room / OkHttp（App）；Go 1.23 + coder/websocket（bridge）；Gradle 9.6.1 / JDK 17（构建）。

**关键前提（部署纪律，违反则白改）：** 生产运行中的 `/Users/tony/.local/bin/harness-bridge` 是 m4 分支 8/18 的 dirty 构建（vcs.revision=29ed3e1+modified），与 worktree HEAD、主仓 HEAD 三份代码并存。任何 bridge 修复必须：①在 `.worktrees/m4-multi-backend-bridge/remote` 修改并构建，②替换 `~/.local/bin/harness-bridge`，③`launchctl kickstart -k gui/$(id -u)/com.harnessapk.remote-bridge`，④读 `/tmp/harness-remote-bridge.error.log` 确认 `serve backends:` 新行。App 侧改完必须 `assembleDebug` 重装到设备（序列号 `20200611222647`，adb 前缀 `-L tcp:5092 -s 20200611222647`）。

**Non-goals（v1.2+）:** Run 全过程工作项呈现（commandExecution/fileChange 卡片增强）、新建线程的项目选择器（替换手输路径）、阿里云推送、codex 模型清单刷新被 Cloudflare 拦截（需服务端代理配置，另行处理）。

**根因摘要（据此设计）：**
1. 新建线程静默失败：App `pendingCommands`（RemoteClient.kt:98）发出后无超时看门狗；实测创建时恰逢 bridge 重启窗口，命令在传输层丢失，UI 永久静默。codex 对 `/tmp` 并不拒绝（隔离 CODEX_HOME 探针实测成功），非白名单拦截。
2. 空 codex 线程可能不立即出现在 thread/list；即使 thread.start 成功回包，`result.thread.id` 解析若遇 shape 漂移也会静默跳过（`id != null` 才 selectThread）。
3. bridge `run()`（main.go:440）的读 goroutine `conn.Read(ctx)` 用无 deadline 的 serveCtx，连接半死（无 RST/FIN）时永久阻塞 → 8/20 断线后 11 天不重连（serve 循环本有 `Sleep(3s)` 重试，但 `run()` 不返回就永远走不到）。
4. bridge `turn.steer` 的 `claimThread` 同步错误只 `return err`（main.go:926-928），仅进日志，不发回设备。

---

## Task 1: 相对时间纯函数

**Files:**
- Create: `app/src/main/java/com/harnessapk/remote/RelativeTimeFormat.kt`
- Test: `app/src/test/java/com/harnessapk/remote/RelativeTimeFormatTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/harnessapk/remote/RelativeTimeFormatTest.kt`:

```kotlin
package com.harnessapk.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeFormatTest {
    private val now = 1_800_000_000_000L // 固定基准

    @Test
    fun zeroTimeReturnsEmpty() {
        assertEquals("", formatRelativeTime(now, 0L))
    }

    @Test
    fun underOneMinute() {
        assertEquals("刚刚", formatRelativeTime(now, now - 59_000))
    }

    @Test
    fun minutes() {
        assertEquals("5 分钟前", formatRelativeTime(now, now - 5 * 60_000))
    }

    @Test
    fun hours() {
        assertEquals("3 小时前", formatRelativeTime(now, now - 3 * 3_600_000))
    }

    @Test
    fun yesterday() {
        assertEquals("昨天", formatRelativeTime(now, now - 26 * 3_600_000))
    }

    @Test
    fun days() {
        assertEquals("4 天前", formatRelativeTime(now, now - 4 * 86_400_000))
    }

    @Test
    fun futureTimeReturnsEmpty() {
        assertEquals("", formatRelativeTime(now, now + 60_000))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.remote.RelativeTimeFormatTest -q`
Expected: FAIL（`Unresolved reference: formatRelativeTime`）

- [ ] **Step 3: 实现**

`app/src/main/java/com/harnessapk/remote/RelativeTimeFormat.kt`:

```kotlin
package com.harnessapk.remote

import java.util.Calendar
import java.util.concurrent.TimeUnit

fun formatRelativeTime(nowMs: Long, timeMs: Long): String {
    if (timeMs <= 0L) return ""
    val diff = nowMs - timeMs
    return when {
        diff < 0L -> ""
        diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)} 分钟前"
        diff < TimeUnit.DAYS.toMillis(1) -> "${diff / TimeUnit.HOURS.toMillis(1)} 小时前"
        diff < TimeUnit.DAYS.toMillis(2) -> "昨天"
        diff < TimeUnit.DAYS.toMillis(7) -> "${diff / TimeUnit.DAYS.toMillis(1)} 天前"
        else -> {
            val cal = Calendar.getInstance().apply { timeInMillis = timeMs }
            "${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.remote.RelativeTimeFormatTest -q`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/remote/RelativeTimeFormat.kt app/src/test/java/com/harnessapk/remote/RelativeTimeFormatTest.kt
git commit -m "feat: 新增远程线程相对时间格式化"
```

---

## Task 2: 线程标题/预览清洗

**Files:**
- Modify: `app/src/main/java/com/harnessapk/remote/RemoteModels.kt:151-165`（parseThreads）+ 新增 `sanitizeThreadText`
- Test: `app/src/test/java/com/harnessapk/remote/RemoteModelsTest.kt`

- [ ] **Step 1: 写失败测试（追加到 RemoteModelsTest）**

在 `RemoteModelsTest.kt` 末尾类内追加：

```kotlin
@Test
fun sanitizeThreadTextCollapsesBlankLinesAndStripsMarkers() {
    val raw = "<codex_delegation>\n  <source_thread_id>x</source_thread_id>\n  <input>请负责任务\n\n\n## My request:\n  做这个\n</input>\n</codex_delegation>"
    val out = sanitizeThreadText(raw)
    assertEquals("请负责任务 做这个", out)
}

@Test
fun sanitizeThreadTextTruncates() {
    val out = sanitizeThreadText((1..400).joinToString("") { "a" })
    assertEquals(160, out.length)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.remote.RemoteModelsTest -q`
Expected: FAIL（`Unresolved reference: sanitizeThreadText`）

- [ ] **Step 3: 实现**

`app/src/main/java/com/harnessapk/remote/RemoteModels.kt` 在 `parseThreads` 上方插入：

```kotlin
private val threadMarkerPrefixes = listOf(
    "<codex_delegation>", "</codex_delegation>", "<source_thread_id>", "<input>",
    "# Files mentioned by the user:", "## My request:",
)

internal fun sanitizeThreadText(raw: String): String = raw
    .replace("\r\n", "\n")
    .split("\n")
    .map(String::trim)
    .filterNot { it.isBlank() || threadMarkerPrefixes.any(it::startsWith) }
    .joinToString(" ")
    .take(160)
```

把 `parseThreads` 中两处改为：

```kotlin
title = item.string("name") ?: sanitizeThreadText(item.string("preview").orEmpty()).take(60).ifBlank { "未命名线程" },
preview = sanitizeThreadText(item.string("preview").orEmpty()),
```

（原 `title` 行是 `item.string("name") ?: item.string("preview")?.take(60) ?: "未命名线程"`，原 `preview` 行是 `item.string("preview").orEmpty()`。）

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.remote.RemoteModelsTest -q`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/remote/RemoteModels.kt app/src/test/java/com/harnessapk/remote/RemoteModelsTest.kt
git commit -m "feat: 清洗远程线程标题与预览中的内部标记和空行"
```

---

## Task 3: 命令超时看门狗（P0 重设计版：超时不吞迟到响应）

> 设计要点（源自 challenger 审查）：超时**只标记、不 remove**，迟到响应仍能正常处理（只抑制重复提示）；按命令类型分级预算（bridge 侧 thread.read/list 预算 30s、turn.start 预算 2 分钟）；`pendingCommands` 换并发容器（第三个并发访问点）；turn.start 失败/超时要清 `isWorking`。

**Files:**
- Modify: `app/src/main/java/com/harnessapk/remote/RemoteClient.kt`（构造器 + send + handleRpcResponse 错误分支 + disconnect + 新方法）
- Test: `app/src/test/java/com/harnessapk/remote/RemoteRepositoryTest.kt`

- [ ] **Step 1: 换并发容器 + 构造器加超时覆盖参数**

`RemoteClient.kt` 顶部 import 区加：

```kotlin
import java.util.concurrent.ConcurrentHashMap
```

字段区：

```kotlin
private val pendingCommands = ConcurrentHashMap<String, String>()
private val timedOut = ConcurrentHashMap.newKeySet<String>()
```

构造器加尾参：

```kotlin
class RemoteRepository(
    private val profileStore: RemoteProfileProvider,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val commandTimeoutOverrideMillis: Long? = null,
) {
```

- [ ] **Step 2: 写失败测试（追加到 RemoteRepositoryTest）**

```kotlin
@Test
fun commandTimeoutSurfacesErrorWithoutConsumingResponse() {
    val repo = repository(timeoutMillis = 60L)
    repo.armCommandTimeout("thread.start:test", "thread.start")
    Thread.sleep(200)
    assertTrue(repo.state.value.errorMessage?.contains("超时") == true)
    // 迟到响应仍能处理：kind 仍映射到 thread.start
    repo.handleRpcResponse(
        "thread.start",
        RemoteEvent(
            type = "rpc.response",
            payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"result":{"id":"t-late"}}"""),
        ),
    )
    assertEquals("t-late", repo.state.value.selectedThreadId)
}
```

同时把文件内私有 helper 改为可传超时：

```kotlin
private fun repository(timeoutMillis: Long? = null): RemoteRepository = RemoteRepository(
    profileStore = FakeRemoteProfileProvider(profile),
    httpClient = OkHttpClient(),
    scope = CoroutineScope(SupervisorJob()),
    commandTimeoutOverrideMillis = timeoutMillis,
)
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.remote.RemoteRepositoryTest -q`
Expected: FAIL（`Unresolved reference: armCommandTimeout`）

- [ ] **Step 4: 实现分级超时 + 非破坏标记**

`RemoteClient.kt` 新增：

```kotlin
private fun commandTimeoutMillis(kind: String): Long = when (kind) {
    "turn.start" -> 130_000L
    "thread.list", "thread.read" -> 35_000L
    else -> 30_000L
}

internal fun armCommandTimeout(requestId: String, kind: String) {
    scope.launch {
        delay(commandTimeoutOverrideMillis ?: commandTimeoutMillis(kind))
        if (pendingCommands.containsKey(requestId) && timedOut.add(requestId)) {
            _state.value = _state.value.copy(
                errorMessage = "命令 $kind 超时，Mac 未响应",
                isWorking = if (kind == "turn.start") false else _state.value.isWorking,
            )
            _notifications.tryEmit(RemoteNotification("Codex 无响应", "命令 $kind 超时，请检查 Mac bridge 是否在线"))
        }
    }
}
```

`send` 成功路径末尾（`if (!activeSocket.send(...)) {...; return false}` 之后、`return true` 之前）加：

```kotlin
armCommandTimeout(command.requestId, command.type)
```

- [ ] **Step 5: rpc.response 错误分支按 turn.start 清 isWorking**

`handleRpcResponse` 内：

```kotlin
if (event.payload?.jsonObject?.get("error") != null) {
    _state.value = _state.value.copy(errorMessage = event.payload.toString())
    _notifications.tryEmit(RemoteNotification("Codex 任务失败", "Mac 返回了错误，请打开 Harness 查看详情"))
    return
}
```

改为：

```kotlin
if (event.payload?.jsonObject?.get("error") != null) {
    _state.value = _state.value.copy(
        errorMessage = event.payload.toString(),
        isWorking = if (kind == "turn.start") false else _state.value.isWorking,
    )
    _notifications.tryEmit(RemoteNotification("Codex 任务失败", "Mac 返回了错误，请打开 Harness 查看详情"))
    return
}
```

- [ ] **Step 6: disconnect 清空挂起命令**

`disconnect()` 内（`_state.value = _state.value.copy(connectionStatus = ...)` 之后）加：

```kotlin
pendingCommands.clear()
timedOut.clear()
```

- [ ] **Step 7: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.remote.RemoteRepositoryTest -q`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/harnessapk/remote/RemoteClient.kt app/src/test/java/com/harnessapk/remote/RemoteRepositoryTest.kt
git commit -m "fix: 远程命令增加分级超时看门狗，杜绝静默丢失"
```

---

## Task 4: thread.start 健壮解析 + 乐观插入

**Files:**
- Modify: `app/src/main/java/com/harnessapk/remote/RemoteClient.kt:73`（createThread）、`211-231`（handleRpcResponse）
- Test: `app/src/test/java/com/harnessapk/remote/RemoteRepositoryTest.kt`

- [ ] **Step 1: 重构 handleRpcResponse 为 internal 可测**

把：

```kotlin
private fun handleRpcResponse(event: RemoteEvent) {
    val kind = pendingCommands.remove(event.requestId)
    if (event.payload?.jsonObject?.get("error") != null) {
```

改为：

```kotlin
private fun handleRpcResponse(event: RemoteEvent) {
    handleRpcResponse(pendingCommands.remove(event.requestId), event)
}

internal fun handleRpcResponse(kind: String?, event: RemoteEvent) {
    if (event.payload?.jsonObject?.get("error") != null) {
```

（方法体其余不变，仅拆签名。）

- [ ] **Step 2: createThread 记录待建 cwd**

类内字段区（`private val pendingCommands = ...` 之后）加：

```kotlin
private var pendingCreateCwd: String? = null
```

`createThread` 改为：

```kotlin
fun createThread(cwd: String) {
    pendingCreateCwd = cwd.trim()
    send(RemoteCommand(type = "thread.start", requestId = requestId("thread.start"), cwd = cwd.trim()))
}
```

- [ ] **Step 3: thread.start 分支健壮解析 + 乐观插入**

把 `handleRpcResponse` 内：

```kotlin
"thread.start" -> {
    val id = event.payload?.jsonObject?.get("result")?.jsonObject?.get("thread")?.jsonObject?.string("id")
    if (id != null) selectThread(id)
    refreshThreads()
}
```

替换为：

```kotlin
"thread.start" -> {
    val obj = event.payload?.jsonObject?.get("result")?.jsonObject
    val id = obj?.get("thread")?.jsonObject?.string("id")
        ?: obj?.string("id")
        ?: obj?.string("threadId")
    if (id != null) {
        val existing = _state.value.threads.firstOrNull { it.id == id }
        val optimistic = existing ?: RemoteThread(
            id = id, title = "新线程", preview = "", cwd = pendingCreateCwd,
            updatedAt = System.currentTimeMillis(), status = "",
        )
        _state.value = _state.value.copy(
            threads = listOf(optimistic) + _state.value.threads.filterNot { it.id == id },
        )
        selectThread(id)
    }
    pendingCreateCwd = null
    refreshThreads()
}
```

- [ ] **Step 3b: 错误路径清理 pendingCreateCwd（P2-5）**

Task 3 Step 5 的 rpc.response 错误分支，在 `copy(...)` 之前加：

```kotlin
if (kind == "thread.start") pendingCreateCwd = null
```

- [ ] **Step 3c: thread.list 合并保留选中线程的乐观条目（P2-1）**

把 `handleRpcResponse` 内：

```kotlin
"thread.list" -> _state.value = _state.value.copy(threads = parseThreads(event))
```

替换为：

```kotlin
"thread.list" -> {
    val parsed = parseThreads(event)
    val selectedId = _state.value.selectedThreadId
    val merged = if (selectedId != null && parsed.none { it.id == selectedId }) {
        _state.value.threads.filter { it.id == selectedId }.take(1) + parsed
    } else parsed
    _state.value = _state.value.copy(threads = merged)
}
```

- [ ] **Step 4: 写测试（追加到 RemoteRepositoryTest）**

```kotlin
@Test
fun threadStartResponseParsesNestedShapeAndSelectsOptimisticThread() {
    val repo = repository()
    repo.createThread("/Users/tony/Documents/x")
    repo.handleRpcResponse(
        "thread.start",
        RemoteEvent(
            type = "rpc.response",
            payload = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"result":{"thread":{"id":"t-1","cwd":"/Users/tony/Documents/x"}}}""",
            ),
        ),
    )
    assertEquals("t-1", repo.state.value.selectedThreadId)
    assertEquals("新线程", repo.state.value.threads.first().title)
}

@Test
fun threadStartResponseParsesFlatIdShape() {
    val repo = repository()
    repo.createThread("/tmp")
    repo.handleRpcResponse(
        "thread.start",
        RemoteEvent(
            type = "rpc.response",
            payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"result":{"id":"t-2"}}"""),
        ),
    )
    assertEquals("t-2", repo.state.value.selectedThreadId)
}
```

- [ ] **Step 5: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.remote.RemoteRepositoryTest -q`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/remote/RemoteClient.kt app/src/test/java/com/harnessapk/remote/RemoteRepositoryTest.kt
git commit -m "fix: thread.start 响应健壮解析并乐观插入新线程"
```

---

## Task 5: 运行中线程徽标数据源（activeThreadId）

**Files:**
- Modify: `app/src/main/java/com/harnessapk/remote/RemoteModels.kt:104-113`（RemoteUiState）
- Modify: `app/src/main/java/com/harnessapk/remote/RemoteClient.kt`（startTurn / handleCodexEvent / error / disconnect）
- Test: `app/src/test/java/com/harnessapk/remote/RemoteRepositoryTest.kt`

- [ ] **Step 1: 模型加字段**

`RemoteUiState` 加一行（`activeTurnId` 之后）：

```kotlin
val activeThreadId: String? = null,
```

- [ ] **Step 2: startTurn 设置**

`startTurn` 内：

```kotlin
_state.value = _state.value.copy(
    isWorking = true,
    activeThreadId = threadId,
    timeline = _state.value.timeline + RemoteTimelineItem(UUID.randomUUID().toString(), "userMessage", text),
)
```

- [ ] **Step 3: 事件处理不再被选中线程过滤掉 turn 状态**

把 `handleCodexEvent` 开头至 `when (method)` 前改为两段式（状态事件对任意线程生效，时间线事件仍只看选中线程）：

```kotlin
private fun handleCodexEvent(event: RemoteEvent) {
    val raw = event.payload?.jsonObject ?: return
    val method = raw.string("method") ?: event.method.orEmpty()
    val params = raw["params"]?.jsonObject ?: JsonObject(emptyMap())
    val eventThreadId = params.string("threadId")
    val selected = matchesSelectedThread(eventThreadId)
    when (method) {
        "turn/started" -> {
            // P1-3 守卫：仅当事件属于选中线程，或当前无活动 turn 时才覆写，
            // 避免后台委托线程的 turn/started 污染前台线程的 activeTurnId 导致 interrupt 错配。
            if (selected || _state.value.activeThreadId == null) {
                _state.value = _state.value.copy(
                    activeTurnId = params["turn"]?.jsonObject?.string("id"),
                    activeThreadId = eventThreadId,
                    isWorking = selected,
                )
            }
        }
        "turn/completed" -> {
            // 只清匹配当前活动线程/回合的状态，避免不相关线程的完成事件误清。
            if (eventThreadId == _state.value.activeThreadId) {
                _state.value = _state.value.copy(activeTurnId = null, activeThreadId = null, isWorking = false)
                _notifications.tryEmit(RemoteNotification("Codex 已完成", "远程任务已结束，点击查看结果"))
                refreshThreads()
            }
        }
    }
    if (!selected) return
    when (method) {
        "item/started", "item/completed" -> params["item"]?.let { item ->
            timelineItem(item)?.let { addOrReplaceTimeline(it) }
        }
        "item/agentMessage/delta" -> appendAgentDelta(params.string("delta").orEmpty())
    }
}
```

（原 `handleCodexEvent` 只有单个 `when(method)`，`turn/started`/`turn/completed` 与 item 事件混在一起，且整体在 `matchesSelectedThread` 之后。）

- [ ] **Step 4: 清空活动态（error / disconnect / rpc error / 超时）**

`"error"` 事件分支 copy 增加两个字段：

```kotlin
_state.value = _state.value.copy(errorMessage = message, isWorking = false, activeThreadId = null, activeTurnId = null)
```

`disconnect()` 内 copy 增加两个字段：

```kotlin
_state.value = _state.value.copy(connectionStatus = RemoteConnectionStatus.DISCONNECTED, isWorking = false, activeThreadId = null, activeTurnId = null)
```

回填 Task 3 Step 4（看门狗）与 Task 3 Step 5（rpc.error 分支）：`isWorking = if (kind == "turn.start") false else ...` 处同步加 `activeThreadId` / `activeTurnId` 清空：

```kotlin
isWorking = if (kind == "turn.start") false else _state.value.isWorking,
activeThreadId = if (kind == "turn.start") null else _state.value.activeThreadId,
activeTurnId = if (kind == "turn.start") null else _state.value.activeTurnId,
```

- [ ] **Step 5: 写测试（追加到 RemoteRepositoryTest）**

```kotlin
@Test
fun turnStartedEventSetsActiveThreadEvenWhenNotSelected() {
    val repo = repository()
    repo.handleEvent(
        RemoteEvent(
            type = "codex.event",
            payload = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"method":"turn/started","params":{"threadId":"t-run","turn":{"id":"turn-1"}}}""",
            ),
        ),
    )
    assertEquals("t-run", repo.state.value.activeThreadId)
    assertEquals("turn-1", repo.state.value.activeTurnId)
}
```

- [ ] **Step 6: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.remote.RemoteRepositoryTest -q`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/harnessapk/remote/RemoteModels.kt app/src/main/java/com/harnessapk/remote/RemoteClient.kt app/src/test/java/com/harnessapk/remote/RemoteRepositoryTest.kt
git commit -m "feat: 追踪运行中线程 id 供列表徽标使用"
```

---

## Task 6: 列表卡片徽标 + 相对时间 + 省略号

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/remote/RemoteScreen.kt:78-86`

- [ ] **Step 1: 替换卡片内容**

把 `RemoteThreadList` 内卡片：

```kotlin
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(thread.title, style = MaterialTheme.typography.titleMedium)
    if (thread.preview.isNotBlank()) Text(thread.preview, maxLines = 2)
    Text(thread.cwd ?: thread.status, style = MaterialTheme.typography.bodySmall)
}
```

替换为：

```kotlin
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            thread.title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (state.activeThreadId == thread.id) {
            Text("进行中", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (thread.preview.isNotBlank()) {
        Text(thread.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            thread.cwd ?: "", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            formatRelativeTime(System.currentTimeMillis(), thread.updatedAt),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] **Step 2: 补 import**

`RemoteScreen.kt` 头部 import 区确认已有（缺失则加）：

```kotlin
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import com.harnessapk.remote.formatRelativeTime
```

（`Alignment` 通常已有；`TextOverflow` 与 `formatRelativeTime` 需确认。）

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/remote/RemoteScreen.kt
git commit -m "feat: 线程列表增加进行中徽标、相对时间与省略号"
```

---

## Task 7: item 类型标签本地化

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/remote/RemoteScreen.kt:137-149`（TimelineCard）

- [ ] **Step 1: 替换标签**

在 `TimelineCard` 上方加映射：

```kotlin
private val timelineKindLabels = mapOf(
    "userMessage" to "用户",
    "agentMessage" to "助手",
    "reasoning" to "思考",
    "commandExecution" to "命令",
    "fileChange" to "文件变更",
)
```

`TimelineCard` 内：

```kotlin
Text(item.kind, style = MaterialTheme.typography.labelMedium)
```

替换为：

```kotlin
Text(timelineKindLabels[item.kind] ?: item.kind, style = MaterialTheme.typography.labelMedium)
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/remote/RemoteScreen.kt
git commit -m "feat: 本地化远程消息 item 类型标签"
```

---

## Task 8: 详情页标题 + 统一返回行为

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt:201`（新增 collect）、`281`（标题）、`381-401`（navigationIcon）
- Modify: `app/src/main/java/com/harnessapk/ui/remote/RemoteScreen.kt:99-102`（去内部返回按钮）

- [ ] **Step 1: 采集远程 UI 状态**

在 `HarnessApkApp.kt` 的 `val remoteProfile by ...`（约 201 行）之后加：

```kotlin
val remoteUiState by container.remoteRepository.state.collectAsState()
```

- [ ] **Step 2: 标题按选中线程显示**

把标题 when 分支：

```kotlin
Routes.RemoteControl -> "远程控制"
```

替换为：

```kotlin
Routes.RemoteControl -> remoteUiState.threads
    .firstOrNull { it.id == remoteUiState.selectedThreadId }?.title ?: "远程控制"
```

- [ ] **Step 3: navigationIcon 统一返回**

把 `onClick` 内 `when (route)` 增加 `RemoteControl` 分支（在 `Routes.WikiLibrary` 分支之后）：

```kotlin
Routes.RemoteControl -> {
    if (remoteUiState.selectedThreadId != null) {
        container.remoteRepository.clearSelection()
        return@IconButton
    }
}
```

（其余分支与 `navController.popBackStack()` 不变；`return@IconButton` 使选中线程时先清选中而非退出路由。）

- [ ] **Step 4: 删除详情页内部返回按钮**

`RemoteScreen.kt` `RemoteThreadDetail` 内：

```kotlin
Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    IconButton(onClick = container.remoteRepository::clearSelection) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    if (state.isWorking) IconButton(onClick = container.remoteRepository::interrupt) { Icon(Icons.Outlined.Cancel, "停止") }
}
```

替换为：

```kotlin
Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
    if (state.isWorking) IconButton(onClick = container.remoteRepository::interrupt) { Icon(Icons.Outlined.Cancel, "停止") }
}
```

- [ ] **Step 5: 系统返回手势对齐（P2-3 BackHandler）**

在 `RemoteScreen.kt` 的 `RemoteScreen` 顶层 composable（`if (state.selectedThreadId == null) ...` 之前）加：

```kotlin
BackHandler(enabled = state.selectedThreadId != null) {
    container.remoteRepository.clearSelection()
}
```

并在 `RemoteScreen.kt` import 区加：

```kotlin
import androidx.activity.compose.BackHandler
```

（这样系统返回手势与 AppBar 返回一致：选中线程时先清选中回列表，而非直接 pop 路由。）

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/main/java/com/harnessapk/ui/remote/RemoteScreen.kt
git commit -m "feat: 远程详情页显示线程标题并统一返回行为"
```

---

## Task 9: bridge WSS 心跳 + steer 吞错回发

**Files:**
- Modify: `/Users/tony/Documents/harness-apk/.worktrees/m4-multi-backend-bridge/remote/cmd/bridge/main.go:440-487`（run 读循环）、`926-928`（turn.steer claimThread）

- [ ] **Step 1: run() 加心跳看门狗**

在 `run()` 中 `errorsCh := make(chan error, 2)` 之后、读 goroutine 之前插入：

```go
// Keepalive: ping the relay every 30s and fail the connection if the pong
// doesn't arrive within 15s. Without this, a half-dead TCP connection (NAT
// timeout, vanished peer without RST/FIN) leaves conn.Read blocked forever
// and the serve loop can never retry.
go func() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			pingCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
			err := conn.Ping(pingCtx)
			cancel()
			if err != nil {
				errorsCh <- fmt.Errorf("keepalive ping failed: %w", err)
				return
			}
		}
	}
}()
```

- [ ] **Step 2: turn.steer claimThread 错误回发设备**

把：

```go
case "turn.steer":
	if err := b.claimThread(command, deviceID); err != nil {
		return err
	}
	return b.requestTurnAppServer(ctx, deviceID, command, bd, "turn/steer", map[string]any{"threadId": command.ThreadID, "expectedTurnId": command.ExpectedTurnID, "input": []map[string]string{{"type": "text", "text": command.Text}}})
```

替换为：

```go
case "turn.steer":
	if err := b.claimThread(command, deviceID); err != nil {
		_ = b.sendCommandEvent(ctx, deviceID, command, protocol.Event{
			Type: "rpc.response", RequestID: command.RequestID,
			Payload: mustJSON(map[string]any{"error": err.Error()}), CreatedAt: time.Now().UnixMilli(),
		}, "")
		return nil
	}
	return b.requestTurnAppServer(ctx, deviceID, command, bd, "turn/steer", map[string]any{"threadId": command.ThreadID, "expectedTurnId": command.ExpectedTurnID, "input": []map[string]string{{"type": "text", "text": command.Text}}})
```

- [ ] **Step 3: 校验**

Run（在 `.worktrees/m4-multi-backend-bridge/remote`）:
```bash
go vet ./cmd/bridge && go test ./cmd/bridge -run TestMobileThreadList -count=1 && go build -trimpath -o /tmp/harness-bridge-new ./cmd/bridge
```
Expected: 无输出错误，生成 `/tmp/harness-bridge-new`。

- [ ] **Step 4: 提交**

```bash
git add remote/cmd/bridge/main.go
git commit -m "fix: bridge 增加 WSS 心跳看门狗并回发 steer 认领错误"
```
（在 `.worktrees/m4-multi-backend-bridge` 根执行，注意该树有用户未提交改动 `remote/dsh/` 两文件，只 `git add` 本任务文件。）

---

## Task 10: 部署新 bridge

**Files:**
- 部署 `/Users/tony/.local/bin/harness-bridge`

- [ ] **Step 1: 备份并替换二进制**

```bash
cp /Users/tony/.local/bin/harness-bridge /Users/tony/.local/bin/harness-bridge.bak.$(date +%Y%m%d)
install -o tony -g staff -m 0755 /tmp/harness-bridge-new /Users/tony/.local/bin/harness-bridge
```

（P2-4：不用 `sudo install`，避免 root:wheel 属主导致下次用户级覆盖/回滚被拒；`~/.local/bin` 属主本就是 tony。）

- [ ] **Step 2: 重启服务并验证**

```bash
launchctl kickstart -k gui/$(id -u)/com.harnessapk.remote-bridge
sleep 5
ps aux | grep "[h]arness-bridge serve" | awk '{print $2}'
grep -v -E "codex_models|models_manager|env: node" /tmp/harness-remote-bridge.error.log | tail -3
```

Expected: 新 PID，日志出现新的 `serve backends: codex=... dsh=...` 行，无 `bridge disconnected`。

- [ ] **Step 3: 观察 90 秒无异常**

```bash
sleep 90; grep -v -E "codex_models|models_manager" /tmp/harness-remote-bridge.error.log | awk '$1 >= "'$(date +%Y/%m/%d)'"' | tail -5
```

Expected: 无 `bridge disconnected`、无 `keepalive ping failed`。

---

## Task 11: 构建 APK + 真机走查

**Files:**
- 构建 `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: 全量单测 + 构建**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug -q
```
Expected: BUILD SUCCESSFUL（若 testDebugUnitTest 与 assembleDebug 同跑报 task 冲突，拆两步跑。）

- [ ] **Step 2: 安装到设备**

```bash
ADB="/Users/tony/Library/Android/sdk/platform-tools/adb -L tcp:5092 -s 20200611222647"
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`

- [ ] **Step 3: 真机走查清单**

用 `/tmp/uidump.sh`、`/tmp/uitap.sh` 与 `screencap` 逐项核验（App：工作 → 进入远程控制）：
1. 新建线程弹窗输入 `/Users/tony/Documents/项目/CRM/project` → 创建 → **立即进入新线程详情页**（标题栏显示「新线程」或路径对应线程名，不再静默）。
2. 详情页输入消息发送 → 列表刷新后该线程出现，卡片显示「进行中」→ 完成后徽标消失、出现相对时间（「刚刚/N 分钟前」）。
3. 卡片标题/预览无 `<codex_delegation>` 等内部标记，多行有省略号。
4. 详情页消息上方标签为「用户/助手/思考」而非 userMessage/agentMessage/reasoning。
5. 详情页顶部无内部返回箭头，AppBar 标题为线程名；点 AppBar 返回先回列表、再点返回退出路由。
6. 断网场景（可选）：发送后立即 `$ADB shell svc wifi disable`，15 秒后应看到「命令 … 超时」错误提示；再 `svc wifi enable`。

- [ ] **Step 4: 记录走查证据**

对每步 `screencap` 存 `/tmp/v1.1-walkthrough/` 并记录结论；任何一步失败 → 回到对应 Task 修，不提交走查产物。

---

## 自查（Self-Review）

- **规格覆盖**：静默失败 → Task 3/4；列表状态+时间 → Task 1/5/6；预览清洗/省略号 → Task 2/6；item 标签 → Task 7；详情标题/双返回 → Task 8；bridge 卡死 → Task 9/10；部署纪律 → 头部前提 + Task 10。全部落点。
- **占位符扫描**：无 TBD/TODO；所有代码步骤含完整代码；Task 9/11 的「观察/走查」含具体命令与预期。
- **类型一致性**：`activeThreadId` 在 RemoteUiState(Task5) 与 RemoteScreen(Task6)、HarnessApkApp(Task8) 一致；`sanitizeThreadText`(Task2) 与 RemoteModelsTest 一致；`armCommandTimeout`(Task3) 与 RemoteRepositoryTest 一致；`handleRpcResponse(kind,event)`(Task4) 双参签名全 Task 一致。
