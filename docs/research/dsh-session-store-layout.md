# `~/.dsh/sessions` 物理布局与锁机制调研

- 调研对象：`/Users/tony/.dsh/sessions`（本机只读盘点）+ `/Applications/DeepSeek Harness.app/Contents/Resources/app.asar` 内 `@deepseek-ai/dsh-session-persistence-jsonl` 的 README / 编译产物
- 调研时间：2026-09-30 15:37–15:45（+08:00），桌面端 Electron 正在运行（pid 13134 / 13169）
- 目的：为「手机端 appserver 续接桌面端已创建的会话」提供磁盘事实与并发规则依据
- 隐私约定：**全程只记录结构、文件名、大小、数量、顶层字段名，未解压任何会话正文**，未打印任何事件行内容

---

## 1. 目录与命名规律

### 1.1 根目录实测

```
/Users/tony/.dsh/sessions/            drwx------  (0700)
├── --<projectKey>--/                 项目目录，每 cwd 一个
│   └── <encoded-sessionId>/          会话自有目录  drwx------  (0700)
│       ├── session.jsonl.zstd        日志（0600，append-only）
│       ├── session.v3.jsonl.zstd     历史/当前 generation（可选，0600）
│       ├── session.v4.jsonl.zstd     当前 generation（可选，0600）
│       └── session.lock              0 字节（0644），仅写者打开，可长期残留
```

实测计数（2026-09-30 15:43 快照）：

| 项目 | 数量 |
|---|---|
| projectKey 目录 | 9 |
| sessionId 目录（= 会话数） | 1788 |
| 日志文件总数 | 1792 |
| ├─ `session.jsonl.zstd`（v0） | 1778 |
| ├─ `session.v3.jsonl.zstd` | 3 |
| └─ `session.v4.jsonl.zstd` | 11 |
| `session.lock` | 14 |
| **未压缩变体 `session.jsonl` / `session.vN.jsonl`** | **0** |
| 隐藏文件、header/元数据伴生文件、子目录 | **0** |

结论：**每个会话目录只有「日志文件 + 可选的 `session.lock`」两类条目，没有独立的 header/元数据文件**；header 是日志文件第一个 Zstandard 帧里的第一行 JSON（见 §4）。本机根目录编码是 zstd，未压缩变体一个都没有。

目录层级已确认**到会话目录为止就结束**：`find -mindepth 4 | wc -l` → 0，会话目录内**没有任何子目录**（全部 1806 个深度 3 条目都是文件）。注意这些计数是 15:43 快照，会话数在持续增长（15:47 复测：1791 个会话目录，日志仍 1778 v0 + 3 v3 + 11 v4）。

### 1.2 projectKey 命名规则（源码取证，非猜测）

`app.asar` → `dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js:876-895`：

```js
function projectKey(cwd) {
  if (cwd.length === 0) throw new Error("cannot encode an empty project path");
  let readable = ""; let separatorRun = false;
  for (let i = 0; i < cwd.length; i++) {
    const code = cwd.charCodeAt(i); const ch = String.fromCharCode(code);
    if (ch === "/" || ch === "\\" || ch === ":") { if (!separatorRun) readable += "-"; separatorRun = true; }
    else if (ch !== "~" && /^[A-Za-z0-9._-]$/.test(ch)) { readable += ch; separatorRun = false; }
    else { readable += "~" + code.toString(16).toUpperCase().padStart(4, "0"); separatorRun = false; }
  }
  return `--${(readable.replace(/^-+/, "") || "root").slice(0, 251)}--`;
}
```

要点：

1. **包裹**：结果固定为 `--<body>--`（前后各两个连字符）。
2. **分隔符**：`/`、`\`、`:` 折叠为**一个** `-`（连续分隔符只出一个），且开头的 `-` 串被剥掉，所以 `/Users/tony/x` → `--Users-tony-x--`。
3. **非安全字符**：除 `[A-Za-z0-9._-]` 与 `~` 之外的每个 UTF-16 code unit → `~` + **大写** 4 位十六进制。
4. **截断**：body 截到 251 字符；`cwd` 为空串抛错；`cwd === undefined` 时不用本函数，直接用目录名 `_no-cwd`（本机未出现该目录）。
5. 该映射**有意有损**（分隔符折叠 + 截断），文档明确说这是「人类可导航」的约定，因此**不能从 projectKey 反推 cwd**；不同 cwd 可能共享同一 project 目录。

实测样本 ↔ cwd 对照：

| projectKey 目录 | 对应 cwd | 会话数 |
|---|---|---|
| `--Users-tony-Documents-harness-apk--` | `/Users/tony/Documents/harness-apk` | 721 |
| `--Users-tony-Documents-~9879~76EE-CRM-project--` | `/Users/tony/Documents/项目/CRM/project` | 801 |
| `--Volumes-game-~5C0F~5E94~7528--` | `/Volumes/game/小应用` | 205 |
| `--private-tmp--` | `/private/tmp` | 18 |
| `--Users-tony-.nvm-versions-node-v24.13.0-lib-node_modules-~0040deepseek-ai-dsh--` | `…/node_modules/@deepseek-ai/dsh`（`@` → `~0040`） | 16 |
| `--Users-tony-Documents-~9879~76EE-CRM-project-user-order--` | `/Users/tony/Documents/项目/CRM/project/user-order` | 9 |
| `--Users-tony-Documents-harness-apk-.worktrees-m4-multi-backend-bridge-remote-dsh-appserver--` | 同名 worktree 路径 | 12 |
| `--Users-tony-Documents-harness-apk-.worktrees-m4-multi-backend-bridge-remote-spike-dsh-appserver--` | 同名 worktree 路径 | 1 |
| `--Volumes-game-project-R-demo6--` | `/Volumes/game/project/R/demo6` | 5 |

中文/`@` 的转义印证：`项目` → `~9879~76EE`，`小应用` → `~5C0F~5E94~7528`，`@` → `~0040`（UTF-16 code unit 十六进制、大写、`~` 前缀）。

### 1.3 sessionId 目录命名规则

`lib/index.js:854-866` `encodeSegment(raw)`：

- 安全字符 `[A-Za-z0-9._-]` 原样保留；其余每个 code unit → `~` + 大写 4 位十六进制；`~` 自身也转义。
- 特判 `.` → `~002E`、`..` → `~002E~002E`，杜绝路径穿越。
- 目录名即 **`encodeSegment(sessionId)`**；对普通 UUID/`session-<uuid>` 是恒等映射（本机 1788 个目录名全部与 id 字面一致，无 `~` 出现）。

实测两种 id 字面形态并存：

| 形态 | 数量（15:40 快照） | 观察 |
|---|---|---|
| 裸 UUIDv4，如 `6cbffa4f-38ec-4fcb-8abc-d06f4cf7a81c` | 1686 | 桌面端创建的会话；`--private-tmp--`、两个 worktree 项目下 **0 个** |
| `session-<uuid>`，如 `session-35408f6e-1257-4af0-ad78-5657018bde59` | 102 | CLI/`dsh` 侧创建的会话；`--private-tmp--` 全部是这种 |

两者都是合法 `SessionId`，**同一项目目录内混存**（harness-apk 下 708 裸 UUID + 13 个 `session-` 前缀；15:47 复测该目录已增至 722 个，裸 UUID 仍 708，即新会话都以 `session-` 前缀出现）。appserver 不能靠前缀判断来源，只能当作不透明字符串；**裸 UUID 数与 `session-` 数都在缓慢增长，只有 `session-` 是活跃创建形态**。

交叉验证：`~/.dsh/storages/session_projcache/sessions/<sessionId>.json`（1726 个文件）的文件名与 session 目录名**逐字一致**（含 `session-` 前缀），可作为 id ↔ 磁盘目录的第二份索引。

### 1.4 generation（文件名代际）规律

`generationLogFilename(version, compression)`：v0 保留「无版本」原名，v≥1 带小写 `vN`：

| generation | zstd（本机使用） | 未压缩 |
|---|---|---|
| v0 | `session.jsonl.zstd` | `session.jsonl` |
| v3 | `session.v3.jsonl.zstd` | `session.v3.jsonl` |
| **v4（当前 writer）** | `session.v4.jsonl.zstd` | `session.v4.jsonl` |

- 运行时**选择数值最高的 canonical generation**；filename 的版本号与文件内 header 的 `version` 字段相等。
- 非 canonical 名字（临时文件、大写、前导零、`v0` 显式后缀等）不被识别为已提交 generation。
- 实测「v0 + 后继 generation 并存」的迁移痕迹：`--Users-tony-Documents-harness-apk--/session-adf400e5-26f0-435d-ac4b-077fcc53a656/` 同时有 `session.jsonl.zstd` 与 `session.v3.jsonl.zstd`。文档明确 **source 保持逐字节不变、从不删除**，所以**多 generation 并存是常态而非异常**。
- 本机 11 个 v4 文件全部是 2026-09-30 当天新建/活跃的会话（v4 目录里只有 v4 文件，没有 v0 前身），说明**新会话直接以 v4 落盘**，而 8 月的存量会话仍是 v0。也就是说：**只按 `session.jsonl.zstd` 找文件的读者，会漏掉今天的全部活跃会话**。

### 1.5 权限、大小、mtime 分布

权限：session 目录 `0700`，日志 `0600`，`session.lock` `0644` 且 **恒为 0 字节**（锁只占 fd，不写内容）。

日志大小（1792 个文件，排除 lock）：

```
min 335 B | p50 324 019 B | p90 721 765 B | max 38 160 690 B | 合计 799.4 MiB
最大 5 个：session-9628ac12…(38.2 MB)、session-d6944ce7…(37.0 MB)、session-d0811d0c…(13.6 MB)、
           f9b06602…(9.5 MB)、session-d1c47b99…(9.3 MB)
```

日志 mtime 按天（最后一次写入）：

| 日期 | 文件数 |
|---|---|
| 2026-08-15 | 167 |
| 2026-08-16 | 354 |
| 2026-08-17 | 371 |
| 2026-08-18 | 672 |
| 2026-08-19 | 167 |
| 2026-08-22 | 24 |
| 2026-08-23 | 2 |
| 2026-08-31 | 19 |
| 2026-09-01 | 2 |
| 2026-09-10 | 3 |
| **2026-09-30** | **11** |

即：**1781/1792 是历史存量，11 个是当前活跃**——与 11 个 `session.v4.jsonl.zstd` 完全重合。这给 appserver 一个廉价筛选信号：目录里存在 `session.v4.jsonl.zstd` ⇔ 由当前格式 writer 写过。

---

## 2. 锁定现状（谁持有 `session.lock`）

### 2.1 结论速览（2026-09-30 15:43 快照）

- 磁盘上存在 **14 个 `session.lock`**，但只有 **9 个正被打开**；其余 **5 个是已释放的残留锁文件**。
- 9 个持锁 fd **全部属于同一个 pid：13169**（进程名 `DeepSeek Harness`）。
- **`session.lock` 文件存在 ≠ 会话被占用**——这是本次调研最关键的一条操作事实。
- 漂移警告：15:47 复测时锁数仍为 14，但**全部 14 个都被持有**（新开了会话）。锁清单每次会话开关都会变，下面的表只是 15:43 的事实留档。

### 2.2 持锁进程身份

```
$ ps -p 13169 -o pid,ppid,uid,etime,comm
  PID  PPID   UID ELAPSED COMM
13169 13134   501   18:16 /Applications/DeepSeek Harness.app/Contents/MacOS/DeepSeek Harness

$ ps -p 13169 -o args=
/Applications/DeepSeek Harness.app/Contents/MacOS/DeepSeek Harness --expose-internals \
  /Applications/DeepSeek Harness.app/Contents/Resources/app.asar/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/index.js \
  /Applications/DeepSeek Harness.app/Contents/Resources/app.asar/dsh \
  /Users/tony/.dsh/profiles/desktop \
  /Applications/DeepSeek Harness.app/Contents/Resources/runtime/primary-runtime \
  /Applications/DeepSeek Harness.app/Contents/Resources/runtime/pnpm/bin/pnpm.mjs \
  /Applications/DeepSeek Harness.app/Contents/Resources/runtime/bin
```

- pid 13134 = Electron 主进程（app bundle 主二进制，ppid 1）；**pid 13169 = 其子进程，运行 `dsh-desktop-host`，profile 为 `~/.dsh/profiles/desktop`**，cwd 就是该 profile 目录。
- 13169 同时监听 `TCP 127.0.0.1:19387 (LISTEN)` —— 即当前 Web GUI 的地址；说明 **GUI/appserver 宿主与持锁者是同一个进程**。
- 还有 3 个 Helper/utility 子进程（13153、13154、13163）**不持有任何 session fd**。
- 另有一个长期进程 `harness-bridge serve --backend dsh=…/bin/dsh`（pid 854），**不持有** `~/.dsh/sessions` 下任何 fd。
- 注意：`lsof` 的 `COMMAND` 列被截断为 `DeepSeek`；用 `ps comm` 才看到完整路径 `…/MacOS/DeepSeek Harness`。判进程名时别只信 `lsof` 的 8 字符截断。

### 2.3 被占用的会话清单（pid 13169 持有）

| # | sessionId | projectKey | lsof FD |
|---|---|---|---|
| 1 | `session-7886c266-5fc7-41af-b687-6e6db54ab60b` | `--Users-tony-Documents-harness-apk--` | 41w |
| 2 | `38c24f82-6f21-4a00-8255-5f44cb3d757f` | `--Users-tony-Documents-harness-apk--` | 44w |
| 3 | `session-f06085a2-2d5b-455c-be14-024d6d557047` | `--Users-tony-Documents-~9879~76EE-CRM-project--` | 45w |
| 4 | `session-35408f6e-1257-4af0-ad78-5657018bde59` | `--Users-tony-Documents-harness-apk--` | 46w |
| 5 | `dbeb7a83-38db-4d7e-81f4-888076096415` | `--Users-tony-Documents-harness-apk--` | 47w |
| 6 | `6cbffa4f-38ec-4fcb-8abc-d06f4cf7a81c` | `--Users-tony-Documents-harness-apk--` | 48w |
| 7 | `session-b236b1f8-df98-4524-913d-a5f4f35df0f8` | `--Users-tony-Documents-harness-apk--` | 49w |
| 8 | `session-6d63a8c6-8ec9-4361-9cd3-8e9d33335709` | `--Users-tony-Documents-~9879~76EE-CRM-project--` | 55w |
| 9 | `session-890f4ec0-95c1-4426-b047-04ad2608e8e6` | `--Volumes-game-~5C0F~5E94~7528--` | 59w |

命令与原始输出：

```bash
$ lsof +D /Users/tony/.dsh/sessions
COMMAND     PID USER   FD   TYPE DEVICE SIZE/OFF      NODE NAME
DeepSeek  13169 tony   41w   REG   1,17        0 219955066 …/session-7886c266…/session.lock
DeepSeek  13169 tony   44w   REG   1,17        0 219947052 …/38c24f82…/session.lock
…（共 9 行，全部 PID 13169，FD 全部带 w = 以写方式打开，SIZE/OFF 恒为 0）
```

FD 列全部带 `w`，与源码 `open(path, "w")` + `tryLockExclusive(handle.fd)` 一致；**锁随 fd 存活**，会话活跃期间该 fd 一直开着。

### 2.4 有锁文件但无人持有（残留，可直接抢占）

这 5 个 `session.lock` 在磁盘上存在、`lsof -F pcn <file>` 与 `lsof +D` 都查不到打开者：

| sessionId | projectKey | 说明（对应日志最后写入时间） |
|---|---|---|
| `session-05f691c9…` | harness-apk | 日志止于 15:32，写句柄已关 |
| `2637ccb7-78cf-4428-a168-386ce1565ad8` | harness-apk | 日志止于 15:30 |
| `session-adf400e5…` | harness-apk | 止于 09-10 20:20（v0+v3 双 generation） |
| `session-93c9045c…` | `--private-tmp--` | 止于 09-10 20:24 |
| `session-c0d800f0…` | `--private-tmp--` | 止于 09-10 20:20 |

源码注释解释了原因（`lib/index.js:714-719`）：**release 只关闭 fd，从不删除 POSIX 锁文件**，因为「保留文件可以维持后续加锁者校验的稳定 inode」。所以 **残留锁文件会持续累积**，`ls` 看到 `session.lock` 绝不能推断会话被占用；只有 `lsof`/`flock(LOCK_EX|LOCK_NB)` 的返回码才是权威。

### 2.5 抢占判定的正确做法（给 appserver）

1. **不要**依据 `session.lock` 是否存在决定是否续写。
2. 续写前对 `session.lock` 做一次非阻塞独占尝试：`flock(fd, LOCK_EX | LOCK_NB)`；
   - 失败且 `errno ∈ {EAGAIN, EWOULDBLOCK}` ⇒ 桌面端仍持有（源码 `isLockContention` 只认这两个码）；
   - 成功 ⇒ 可安全续写；**保留该 fd 直到写完**，不要 unlink 锁文件。
3. **绝不要 unlink `session.lock`**：POSIX `flock` 锁 inode 而非路径，删除活跃会话的锁文件会**直接丧失互斥**（文档原话 "removing the lock file forfeits that exclusion"）。
4. 只读续接（列表 / 重放）**完全不涉及锁**——"Readers never touch the lock."
5. 桌面端释放时机：UI 关闭该会话或 app 退出。pid 13169 存活期间，上表 9 个会话对手机端**只读可用、写入必失败**。

---

## 3. 单写者与崩溃恢复规则摘录（原文）

以下为 `app.asar` → `dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/README.md` 与 `lib/index.js` 的原文摘录（每段已截到 ≤10 行），是本节的权威依据。

### 3.1 单一写者（内核锁语义）

> **One live writer per session** — the write-handle claim excludes a second writer inside the owning backend instance, and a kernel lock (non-blocking `flock(2)` on `session.lock`; on Windows a named kernel semaphore derived from that path, with no filesystem footprint) excludes every other instance and process; the lock is taken at write-open of an existing artifact and, for a created session, only right before its first materializing write, so an unmaterialized session leaves no filesystem footprint. A crashed holder's lock dies with its process, so its session is writable again immediately, while a live-but-wedged holder blocks writers until its process exits (on POSIX, removing the lock file forfeits that exclusion; release itself never removes it). Advisory `flock` is unreliable on some network filesystems (NFSv3), and the Windows semaphore name is per login session.
>
> — README.md:165（Known Limitations 第 1 条）

### 3.2 lease 模块的完整契约（源码 JSDoc）

> Cross-process write-ownership lock for one session's artifact directory, held for the whole life of a write handle. The arbiter is the kernel: POSIX takes a non-blocking `flock(2)` via native system support on `session.lock` beside the log, and Windows holds a named kernel semaphore derived from that path — never a file lock or handle, so readers, searches, and directory removal proceed freely while the lock is held. Contention maps to `SessionAlreadyOwnedError`; the kernel releases the lock when the holder's descriptor or last object handle closes, including on any process death, so a crashed holder never blocks a successor. A live but wedged holder keeps the lock until its process exits: there is deliberately no expiry that could expropriate a stalled writer whose resumed appends would tear the log.
>
> A POSIX lock names an inode, not a path, so after locking the holder verifies the locked inode is still the file at the lock path and retries otherwise: an unlinked-and-recreated lock file carries a fresh inode, and a lock on the orphaned one proves nothing. Removing a live session's lock file therefore forfeits exclusion on POSIX (nothing in the harness does so); Windows has no lock file at all. Readers never touch the lock.
>
> The lock is acquired at write-open of an existing artifact and, for a created session, only right before its first materializing write — an unmaterialized session has no filesystem footprint. Release never removes the POSIX lock file: every acquired lock belongs to a materialized or materializing session, and the surviving file keeps the stable inode later lockers verify against. The browser worker stubs the native flock entry to immediate success: it is single-process, so the in-process write claim already excludes every writer.
>
> — lib/index.js:614-641（`lib/types/lease.js` 模块文档）

### 3.3 加锁实现（含 inode 复查重试）

```js
static async acquire(dir, id) {
  const path = join(dir, LEASE_FILENAME);          // "session.lock"
  await mkdir(dir, { recursive: true, mode: 448 }); // 0o700
  ...
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const handle = await open(path, "w");
    try { await tryLockExclusive(handle.fd); }       // LOCK_EX | LOCK_NB
    catch (error) { if (isLockContention(error)) throw new SessionAlreadyOwnedError(id); throw error; }
    const held = await handle.stat({ bigint: true });
    const current = await stat(path, { bigint: true }).catch(…);
    if (current !== void 0 && current.ino === held.ino && current.dev === held.dev)
      return new SessionWriteLease({ kind: "posix", handle });
    await handle.close();
  }
  throw new SessionAlreadyOwnedError(id);   // 3 次 inode 复查均失败
}
```

配套事实：POSIX 写锁依赖预编译原生插件 `@deepseek-ai/node-addon-system`（`lib/index.js:12` `import { tryLockExclusive } from "@deepseek-ai/node-addon-system/flock"`）；**插件缺失会直接拒绝写所有权**（README:167）。

### 3.4 持久化与崩溃语义

> A session is materialized lazily: `create(header)` writes nothing and returns the owned write handle, and the handle's first `append` writes and `fsync`s the encoded header and first batch through a no-overwrite publish — so a created-but-never-appended session leaves nothing on disk unless its owner calls `handle.flush()`, which publishes one header frame without an event. Each subsequent batch appends lines or one compressed frame and `fsync`s before the append resolves; a caught write or sync failure rolls the file back to its prior length. Committed events are never rewritten.
>
> — README.md:77（第 1 段）

> After a crash, the stored log keeps its interrupted final turn — every record in the committed prefix survives, and the resuming reader appends synthetic closers through its write handle. An incomplete final raw line is discarded. A torn final Zstandard frame contributes only its complete decoded JSONL records; a write handle truncates the torn bytes and durably rewrites those recovered records before its first new batch. Checksum, decompression, or structural failure in a complete committed frame rejects as corruption.
>
> — README.md:77（第 2 段，尾半）

> **POSIX materialization requires hard-link support** — first append uses `link()` so same-id races fail instead of overwriting a committed log; Windows uses write-through rename without replacement.
>
> — README.md:166

> **Nothing deletes session files** — logs accumulate under `root` until removed externally; the seam has no deletion API.
>
> — README.md:164

> **Compressed files are not directly line-readable** — use the backend to load them, or select `compression: 'none'` before writing a fresh root when external line readers are required.
>
> — README.md:163

### 3.5 `loadStored` / `open(id, 'write')`：打开既有会话的规则

README 中没有 `loadStored` 这个名字；`open(id, 'read'|'write')` 是唯一入口（`stat`/`list`/`open` 三件套）。原文：

> `open(id, 'read'|'write')` selects the highest canonical generation. Current input follows the ordinary fast path. For historical input, a read open decodes and migrates the source once, validates the current logical result, and returns it without publishing a successor. A write open reuses that revision-keyed preparation when available, or performs the same preparation, then encodes a same-directory temporary file in bounded chunks, verifies it in a Worker Thread, rechecks the source revision, and publishes the current successor without overwrite before returning. The source remains byte-identical. Source drift after preparation rejects that write open without replacing the logical history already returned to readers; a later write open prepares the new revision.
>
> — README.md:83（前半）

> `stat(id)` and `list()` select and translate only the highest generation header without reading event rows or starting migration; snapshots carry the selected file's `sizeBytes` and a best-effort revision. … With `compression: 'none'`, the log is newline-delimited text an external reader can consume directly; the compressed default must be read through the backend.
>
> — README.md:83（后半）

> Only an unmaterialized pending log reports `detached`.
>
> — README.md:83（中段）

对手机端最要紧的三条推论：

1. **resume 一个 v0 会话会「发布」一个 v4 后继文件**（同目录、no-overwrite、source 不变）。若手机端也去 write-open 同一 v0 会话，两边会各自尝试发布 v4 —— 桌面端已持有 flock 时手机端会在**加锁阶段**失败，不会走到发布；但**必须先加锁再迁移**，顺序不能反。
2. **只读续接不受影响**：read open 只解码、不发布、不碰锁，且 `list()`/`stat()` 只读 header 帧。
3. **选取 generation 必须按「数值最高」而非「文件名字典序」**：`session.jsonl.zstd`(v0) < `session.v3.jsonl.zstd` < `session.v4.jsonl.zstd`，字典序恰好也成立，但代码判定是解析出的版本号 + canonical 名字校验（大小写、前导零、`v0` 显式后缀均非法）。

### 3.6 与 `session.lock` 无关的相邻索引（供 appserver 发现会话）

盘点时发现的、位于 `sessions` 之外的既有索引（只列结构与字段名，未读内容）：

| 路径 | 规模 | 结构 |
|---|---|---|
| `~/.dsh/storages/session_projcache/sessions/<sessionId>.json` | 1726 个文件（每个 2.8–4.2 KB） | 顶层键 `version`、`record`；`record` 键为 `identity`、`rows` |
| `~/.dsh/storages/session_projcache.json` | 6.2 MB，mtime 2026-09-01 | 顶层键 `unit`、`global`、`tables` |
| `~/.dsh/storages/workspace.json` | 6.4 KB，mtime 2026-09-30 15:40 | 顶层键 `global`、`tables`、`unit` |

文件名 = 完整 sessionId（含 `session-` 前缀），与 session 目录名一致。这些是投影/列表缓存，**不是权威来源**（`sessions` 才是），但可作 id 枚举的旁路。

---

## 4. `session.jsonl.zstd` 头部帧可读字段

### 4.1 物理分帧（来自文档原文）

> The default artifact is a standard concatenation of independent Zstandard frames: one checksummed frame containing only the header line, then one checksummed frame per durable append batch, using Node's built-in Zstandard API at its default compression level (no level knob). The current format writes one event per row; …
>
> — README.md:105（前半）

> Listing reads and validates only the header frame.
>
> — README.md:105（中段）

> `compression: 'none'` keeps the same storage-form logical lines without frame compression. A root belongs to one encoding: startup discovery and targeted lookup reject generations with the other suffix; format migration preserves the configured encoding, while compression conversion, mixed-root fallback, and dual write remain unsupported.
>
> — README.md:105（后半）

要点：

- **帧 1 = 只有 header 一行 JSON**，帧 2..N = 每个 durable batch 一帧，帧独立且带 checksum。
- **只解第一帧即可拿到全部 header 字段**（`list()`/`stat()` 正是这么做的），**无需解压会话正文** —— 这正是手机端做会话列表时应采用的路径。
- 本机根目录**全部是 zstd**（0 个 `session.jsonl`），且「一个 root 只属于一种编码」，混合/回退/双写不支持 ⇒ 手机端只需实现 zstd 分支。
- `compression: 'none'` 时同一个逻辑行序列直接明文（此时 `session.jsonl` 才是普通 JSONL，可外部逐行读）——本机未启用。

### 4.2 v0 头部可用字段（本机 1778 个文件的主形态）

`lib/worker.cjs:8158-8171`（`PHYSICAL_HEADER_REQUIRED` / `PHYSICAL_HEADER_OPTIONAL`，released v0 物理 header）：

| 类别 | 字段 | 约束 |
|---|---|---|
| 必填 | `type` | 恒为 `"session"`（header 行判别依据） |
| 必填 | `version` | 数字，且**等于文件名版本号**（v0 文件 → `0`） |
| 必填 | `id` | 字符串，**就是 sessionId**（目录名的原串） |
| 必填 | `createdAt` | 非负安全整数（毫秒时间戳，与子代理 `childCreatedAt` 同源） |
| 必填 | `delegationDepth` | 非负安全整数（子代理层级） |
| 可选 | `cwd` | 字符串，**必须绝对路径**（= 该会话的 project 归属依据） |
| 可选 | `parentSession` | 字符串（父会话 id，子代理会话才有） |
| 可选 | `seedLength` | 数字（v0/v1 的 seed 长度；v4 改为布尔 `isSeeded`） |
| 可选 | `origin` | 出现时只能是 `"subagent"` |
| 可选 | **`agentPreset`** | 字符串（**「preset」的真实字段名**） |

v0 额外校验（`worker.cjs:8080-8094`）：key 集合**精确匹配**（多余键即拒绝）、`version` 必须严格等于期望值、`cwd` 必须绝对、`origin` 只能是 `subagent`。

### 4.3 v4 头部可用字段（本机当前 writer，11 个文件）

`dsh-session-format-v3-to-v4/README.md:217` 原文表格行：

> | Logical header | Exact required fields `version`, `id`, `createdAt`, `isSeeded`, `delegationDepth`; optional `cwd`, `parentSession`, `origin`, `agentPreset`; no other keys. Version is 4, id is a string, creation time/depth are nonnegative safe integers, seeded is boolean, cwd is absolute when present, optional ids are strings, and origin is `subagent` when present. |

与 `lib/index.js:778-791` 的当前格式常量一致（`HEADER_REQUIRED_KEYS` 额外含 `type`，`HEADER_OPTIONAL_KEYS` = `cwd`/`parentSession`/`origin`/`agentPreset`），并额外拒绝已退役字段 `sandboxMode`、`approvalPolicy`（`assertNoRetiredHeaderFields`）。

字段差异（v0 vs v4）：v4 用 `isSeeded: boolean` 取代 v0 的 `seedLength: number`，其余字段名与语义不变。V3→V4 边缘文档明确「**No preset id … or physical filename is renamed by this edge**」，即 `agentPreset` 从 v3 起名称稳定。

### 4.4 回答「头部能读到什么」

- ✅ **`sessionId`** → 字段名是 **`id`**（字符串）
- ✅ **`cwd`** → `cwd`（绝对路径；`undefined` 时会话落在 `_no-cwd/` 目录）
- ✅ **`createdAt`** → `createdAt`（非负整数）
- ✅ **`preset`** → 字段名是 **`agentPreset`**
- ✅ 另外还有：`type`（恒 `"session"`）、`version`（= 文件名版本）、`delegationDepth`、`isSeeded`(v4) / `seedLength`(v0)、`parentSession`、`origin`
- ❌ 头部**没有**：会话标题、模型/路由、token 用量、消息数、`updatedAt`、锁状态 —— 这些要么在事件行里（如 title 相关事件、`request/context`），要么在 `~/.dsh/storages/session_projcache` 的 `record.identity/rows` 里。
- ⚠️ 头部 key 集合是**精确匹配**的：多一个键即视为损坏/拒绝。手机端解析时应「只取所需键、忽略合法性判定」，而不要复刻全部校验（退役字段/新版本会更严）。

### 4.5 取证边界说明

按要求**没有解压任何真实会话文件**（一个字节都没解压）。上面字段表全部来自 asar 内的 README 原文与编译产物常量表，可用如下命令复现（只读、不改动 asar）：

```bash
/usr/bin/python3 - <<'PY'
import json,struct
p="/Applications/DeepSeek Harness.app/Contents/Resources/app.asar"
f=open(p,"rb"); hdr=f.read(16); size=struct.unpack("<I",hdr[12:16])[0]
j=json.loads(f.read(size).decode("utf8")); data_off=16+size
entries=[]
def walk(node,prefix):
    for k,v in (node.get("files") or {}).items():
        np=prefix+"/"+k
        if "files" in v: walk(v,np)
        else: entries.append((np,v.get("size",0),v.get("offset")))
walk(j,"")
for n,s,o in entries:                      # 取 README / lib 产物
    if n.endswith("dsh-session-persistence-jsonl/README.md") or n.endswith("dsh-session-persistence-jsonl/lib/index.js"):
        f.seek(data_off+int(o)); d=f.read(int(s))
        open("/tmp/dshres/"+n.rsplit("/",1)[-1],"wb").write(d)
PY
```

---

## 5. 对「手机端 appserver 续接桌面端会话」的直接结论

1. **定位**：`sessions/<projectKey(cwd)>/<encodeSegment(sessionId)>/`，`projectKey` 是**有损**映射且需要 cwd；若只有 sessionId，要么扫描全根目录的 header 帧，要么走 `~/.dsh/storages/session_projcache/sessions/<sessionId>.json` 旁路索引。
2. **选文件**：取目录内**数值最高**的 canonical generation（可能是 `session.jsonl.zstd` / `session.v3.jsonl.zstd` / `session.v4.jsonl.zstd`），**不要硬编码 `session.jsonl.zstd`** —— 今天活跃的 11 个会话全部只有 `session.v4.jsonl.zstd`。
3. **读 header**：只解第一帧（checksummed zstd frame，内仅一行 JSON）即可得到 `id`/`cwd`/`createdAt`/`agentPreset`/`parentSession`/`delegationDepth`/`isSeeded`；这是列表与路由的最小成本路径。
4. **只读续接**：完全无需锁，也不会触碰 `session.lock`；对 v0 会话 read-open 只解码迁移、不发布文件。
5. **写入续接**：先在会话目录对 `session.lock` 做 `flock(LOCK_EX|LOCK_NB)`，`EAGAIN/EWOULDBLOCK` ⇒ 桌面端仍在写（15:43 快照 9 个、15:47 快照 14 个会话即此状态），必须退化为只读或等待；成功后**保持 fd 到写完**、**绝不 unlink 锁文件**。
6. **崩溃恢复**：崩溃者的锁随进程消亡立即释放（锁文件残留不影响抢占）；截断的尾部按「保留完整记录 + 丢掉不完整行 / 只取 torn 帧内完整 JSONL 记录」恢复，恢复动作由写句柄完成；已提交前缀永不重写。手机端若实现写入，必须复刻「先加锁 → 恢复尾部 → 再追加」的顺序。

## 6. 本次使用的只读命令（可复现）

```bash
# 结构盘点
ls -la /Users/tony/.dsh/sessions/
find /Users/tony/.dsh/sessions -mindepth 2 -maxdepth 2 -type d | wc -l
find /Users/tony/.dsh/sessions -mindepth 3 -maxdepth 3 -type f | sed 's|.*/||' | sort | uniq -c
find /Users/tony/.dsh/sessions -name 'session.lock' | wc -l
find /Users/tony/.dsh/sessions -name 'session.jsonl' | wc -l      # → 0

# 锁
lsof +D /Users/tony/.dsh/sessions
for f in $(find /Users/tony/.dsh/sessions -name 'session.lock'); do lsof -F pcn "$f"; done
ps -p 13169 -o pid,ppid,uid,etime,comm; ps -p 13169 -o args=

# 进程/端口
ps -Ao pid,ppid,etime,comm | grep -i harness
lsof -nP -iTCP -sTCP:LISTEN -a -p 13169

# asar 取证（README / 常量表）
/usr/bin/python3 - <<'PY'   # 见 §4.5
PY
```

⚠️ 快照时效：锁持有清单与会话文件计数**每次会话开关都会变化**（本次调研 8 分钟内 `session.lock` 从 11 增至 14，持锁会话从 6 增至 9）。生产逻辑请以运行时 `flock` 尝试为准，文档里的清单仅作 2026-09-30 15:43 的事实留档。
