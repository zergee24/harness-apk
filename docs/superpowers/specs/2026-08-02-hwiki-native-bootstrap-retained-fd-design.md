# HWiki Native Bootstrap Retained-FD Handoff Design

日期：2026-08-02

## 1. 决策状态

本设计固化用户已批准的方案 B，用于补全
`cn-history-semantic-bootstrap-v17` 的 `bootstrap_main.c` 与
`python_loader.py`：native bootstrap 在同一进程内持有权威 BSD flock
open-file-description（OFD）和一组有界 retained descriptors，随后
`execve` 固定 Python 3.9；嵌入式 source-only loader 只从内部生成的严格
FD table、继承的 descriptors 和 descriptor-relative 枚举构造
`ResolvedInvocation` 与 activation observations。Python 阶段不得根据
caller、环境变量或任意绝对路径重新发现 workspace、worker、owner、锁、
implementation set 或 job artifacts。

本设计是现有批准边界的窄补充，不替代：

- `.hwiki` 总体设计与双史书实施计划；
- immutable formal reclose policy v4
  `f752c08bef0c69f3cad258e979dac9c9b7fbaa1892db2f859fe1a062a3307498`；
- immutable native verifier audit candidate manifest
  `3ce98ece9cb6ba26dd89eff1d3bccd138d1b9dfb3ea3361fc6815299a8d150d7`。
  该候选因未由 prebuild output profile 授权完整 ordered load-command
  semantics 已降级为 **NO-GO 审计证据**，不得作为 build/sign/release authority；
- runtime/native contract、source set、build plan、two-run evidence、release
  closure 和 implementation pointer 的既有 create-only 状态机。

## 2. 目标与成功标准

1. 权威 `run.lock` OFD 从 native 取锁成功起，跨同 PID `execve` 保持到整个
   mutating invocation 退出；任何路径都不调用 `LOCK_UN`。
2. Python loader 使用的所有路径身份来自 native 已验证的 owner/authority 和
   retained directory/file descriptors，而非 Python 重新解析 caller path。
3. `initial_implementation_activation_gate(...)` 继续接收现有 exact
   58-field `ResolvedInvocation`、17-field observations、10-field live rows、
   target implementation manifest 与 role payloads；pure gate API 不旋转。
4. 2509-job inventory 不通过同时打开数千文件实现。loader 从 retained
   `history-jobs`/worker collection dirfds 做两个完整、排序、byte-identical
   descriptor-relative 扫描，并在 pure gate 返回后、首次 mutation 前做第三次
   重认证。
5. 同一 workspace 不能由第二 worker、第二 lock inode、path alias、symlink
   ancestor、late replacement、partial tuple 或 gap 后 complete 绕过。
6. model/read-only descendants 不继承 lock OFD；唯一会写 workspace 的 builder
   lineage 显式继承同一个 OFD，且不能 reopen/reflock。
7. 在 source set、actual Mach-O、release receipt、runtime successor、formal
   successor、owner、implementation set/pointer 全部闭合前，二十四史保持 STOP，
   不启动 semantic long run、shared registry、completion、签名或 pack。

## 3. 非目标与威胁边界

- 不让 C 重写 Python pure gate，也不在 C 中构造完整 58/17/10 JSON。
- 不改变 semantic candidate、formal reclose、completion、alias freeze 或
  history relation aggregation 的业务规则。
- 不通过长期保留 2509 个 input fd 获得不可扩展的“安全感”。
- 不把内部 FD table 当作外部授权对象；它只是在已验证 native 进程内生成的
  handoff framing。
- 协议防合作进程并发、崩溃、路径替换、误操作和未授权入口；不宣称 0444、
  inode 或进程内数据能抵抗拥有同一 UID、可任意 ptrace/改写进程或重建所有
  anchor 的恶意主体。
- 不发布 actual binary，不签名，不 provision owner，不激活 pointer；这些属于
  后续独立 gate。
- 不在本设计中追认 native verifier candidate `3ce98ece…150d7`。其 successor
  必须在 prebuild output profile 中逐项冻结所有非自引用 load-command semantics，
  至少包括 dyld chained fixups、exports trie、function starts、data in code 的
  exact offset/size/payload SHA，并由 direct pair verifier 与 release closure
  同时复验；post-build request 自述不能充当 authority。

## 4. 组件边界

### 4.1 `bootstrap_main.c`

native bootstrap 独占以下职责：

- 校验 Apple `/usr/bin/env -i` 启动链、exact 14-field environment、selected
  Mach-O slice/signature/self bytes、bootstrap authority 或 owner；
- 在加载任何 private H1 Python 模块前，从 canonical owner 解析唯一 workspace、
  worker 和永久 `run.lock`；
- 对 exact bound lock inode 取得 nonblocking BSD flock，并验证 independent
  canonical probe 无法取得第二把锁；
- retained-open 有界 authority set、implementation set roots 和 collection
  roots；
- 在锁内完成 runtime distribution、repo object、source-lock/rights 与 mode
  schema 前置检查；
- 构造内部 FD table，关闭所有不在 `{0,1,2} + table fd` allowlist 的 fd，
  只为 Python `execve` 临时清除 table fd 的 `FD_CLOEXEC`；标准输入、输出、
  错误由 native 启动契约验证，但不属于 authority table；
- 以同 PID 执行固定 Python 3.9 与 embedded `python_loader.py` bytes。

native 不生成 activation target components，不解释 semantic markers，不遍历全部
job payload，也不调用 builder/model。

### 4.2 `python_loader.py`

embedded loader 独占以下职责：

- 解析并验证 internal FD table 的 framing、hash、size、exact fields、row order、
  descriptor stats 与 parent/basename binding；
- 立即把所有继承 fd 设回 non-inheritable；run lock 仅在明确的 mutating child
  launch 中通过 `pass_fds` 传递；
- 通过 retained dirfds 和 `openat`/`fstatat` 读取 owner、manifest、STOP、
  contracts、implementation set、job inputs 与 live tuple；
- 对 bounded regular evidence 保留同一 fd 直到 pure gate 与 post-gate
  recertification 完成；
- 对 unbounded collections 生成 exact ordered row stream，并执行双扫描加
  post-gate 第三扫描；
- 使用已经验证的 source bytes 进行 source-only `compile(decoded_text,
  exact_path, "exec")`，禁止邻接 pyc、普通 `SourceFileLoader`、worker-root
  set 外私有模块与 path-based dynamic import；
- 调用现有 pure activation/reclose/coordinator callable，并把 typed result 交给
  mode handler。

loader 不从 `cwd`、`HOME`、`PYTHONPATH`、第二个 workspace 参数或 filesystem
search 推断 authority。

### 4.3 现有 H1 pure gates

以下既有 callable 保持名称、keyword-only signature 和返回 shape：

- `initial_implementation_activation_gate(...) -> dict[str, object]`；
- `finalize_activation_component_transition(...) -> dict[str, object]`；
- `build_activation_pointer_projection(...) -> dict[str, object]`；
- formal reclose path/classifier/history/global-head pure gates；
- owner-bound `run_parallel_candidate(...)` 与 coordinator facade。

pure gate 只消费 loader 构造的 exact builtin snapshots。测试在进入 pure gate 后
继续用 `os.open/stat/lstat/Path` 哨兵证明它不执行 filesystem discovery。

## 5. Internal FD Table v1

### 5.1 传递方式

table 是 native 在锁内由已验证状态生成的 canonical compact UTF-8 JSON（无
LF）。它不落盘。native 将 raw bytes 做 unpadded base64url 编码，并构造唯一
internal Python argv：

```text
<python3.9> -I -S -B -E -X pycache_prefix=<exact-empty-prefix>
  -c <embedded-loader-bytes>
  --hwiki-internal-fd-table-b64 <unpadded-base64url>
  --hwiki-internal-fd-table-sha256 <64-lowerhex>
  --hwiki-internal-fd-table-size <canonical-decimal>
```

`sys.argv[1:]` 必须正好是上述 6 个 flag/value 项，无 `--` sentinel、尾参数、
`--x=y` 或环境 fallback。table raw 大小范围为 `1..65536` bytes；size 是无符号
十进制，除精确字符串 `0` 外不允许前导零。loader 必须验证 base64url
alphabet/padding absence、
decode size、SHA-256、strict JSON、duplicate/nonfinite rejection，以及重新序列化
byte-identical。

### 5.2 Exact object schema

顶层 exact fields、无 extra：

```text
type,schemaVersion,mode,externalArgv,rows
```

- `type = "hwiki-semantic-bootstrap-fd-table-v1"`
- `schemaVersion = 1`（exact non-bool integer）
- `mode` 必须是 formal mode schema 中当前 external invocation 的 exact mode。
- `externalArgv` 是 native 已验证的原始 mode argv exact string array；不含 native
  私有 stage token，也不含 Python internal argv。
- `rows` 按 `role` Unicode code-point 严格升序、唯一、非空。

每个 row exact fields、无 extra：

```text
role,fd,objectType,accessMode,canonicalPath,parentRole,basename,
device,inode,mode,nlink,sizeBytes,mtimeNs,ctimeNs,sha256,
enumerationPolicy,descendantPolicy
```

- `fd` 是 exact non-bool integer，范围 `3..OPEN_MAX-1`，全表唯一。
- `objectType` 为 `directory` 或 `regular`。
- `accessMode` 为 `read-only`；只有永久 `run-lock` row 为 `read-write`。
- `canonicalPath` 来自已闭合 authority/owner，不接受 caller 替换。
- `dir:/` row 的 `parentRole/basename` 都为 null；其余 row 二者均为 exact
  string，并通过 parent dirfd 的 nofollow lookup 绑定到 row
  `(device,inode)`。
- `device/inode` 均为 canonical uint64 decimal string；除精确字符串 `0` 外不允许
  前导零。`mode` 是 exact non-bool integer，等于 `st_mode & 07777`，并独立验证
  object type 和 special bits。
- directory 的 `sha256` 为 null，`enumerationPolicy` 只能是
  `name-binding-only` 或 `exact-managed-children`。纯 ancestor directory 使用前者，
  其 `nlink/sizeBytes/mtimeNs/ctimeNs` 均为 null，只闭合 retained fd 的
  `(device,inode,mode)` 与 canonical parent/name→inode，明确容许不相关 sibling
  churn。collection root、fixed authority root、implementation-set root 等 exact
  managed directory 使用后者，四个 stat 字段均为 canonical uint64 decimal
  string，并把 table 快照与 retained dirfd 当前 seven-stat、canonical name rebind
  和完整稳定枚举一起闭合。
- regular `sha256` 为 exact lowercase 64-hex；ordinary bounded evidence 为
  nlink1，root/owner/pointer hardlink pair 按各自状态机要求共享 exact inode/nlink，
  永久 run-lock 使用 owner-bound exact nlink/size。regular 的
  `nlink/sizeBytes/mtimeNs/ctimeNs` 均为 canonical uint64 decimal string，
  `enumerationPolicy` 为 null。同一 retained fd 完整读、
  pre/post seven-stat 与 canonical name rebind 必须闭合，不能用第二个 fd 替代
  retained fd 作为内容证据。
- `descendantPolicy` 仅为 `none` 或 `mutating-writer`；只有 `run-lock` 可为后者。

row set 不是“至少包含”的开放集合，而是以下 deterministic union，禁止 extra：

1. 当前 mode schema、validated owner/recovery namespace、target implementation set
   所要求的每个 regular evidence path 与 collection root；
2. 从 canonical `/` 到上述每个 path 的所有唯一 ancestor directory；
3. target set manifest 和 exact 11 个 `IMPLEMENTATION_ROLE_PATHS` payload，其中
   9 个为 Python role、2 个为 JSON policy role。

每个 directory role 精确为 `dir:<canonical-absolute-path>`；每个 target payload
role 精确为 `target-role:<implementation-role-name>`；其余 bounded regular role
使用 mode schema 的唯一字段名。相同 canonical path 只保留一个 directory row；
若它既是 ancestor 又是 managed root，则唯一 row 必须使用更严格的
`exact-managed-children`。root/owner/pointer 的两个 hardlink name 各有独立 regular
row并共同闭合同一 inode。mode-specific absent/unused object不得生成 row。table
生成后 native 重新计算上述 union、每个 directory 的 enumeration policy 并要求
exact equality，再按 role 排序。

## 6. Descriptor-relative observation

### 6.1 Bounded evidence

bootstrap authority、owner、manifest、source lock、rights、STOP、runtime/native/
formal/relation contracts、semantic bootstrap、Codex executable、builder validator、
target implementation manifest 和 11 role payloads属于 bounded evidence。11 个
payload 恰为 9 个 Python role与2个 JSON policy role。它们的 fd 从 native 跨
exec 保留；loader 在以下三个时点从同一 fd 完整重读并验证
seven-stat/hash/size/canonical name：

1. 构造 observations 前；
2. pure gate 调用前；
3. pure gate 返回后、首次持久化 mutation 前。

任一点漂移都 fail-closed，不能用先前 raw snapshot 继续。

### 6.2 Unbounded job inventory

loader 从 retained collection dirfds 按 manifest ordinal 构造 2509 个 exact
10-field live rows：

- 每个 input 必须存在、regular、nlink1，canonical no-LF bytes 与 manifest row
  的 job/input hash/scope/profile/wiki 闭合；
- prefix 内四个 formal artifact 必须全存在；rich 三项按 marker provenance
  exact present/nullable，legacy 三项 exact null 且 marker keys absent；
- tail 的九个非-input fields 必须 exact null；
- 任何 partial tuple、gap 后 complete、extra basename、symlink、duplicate、
  noncanonical JSON 或 type drift 都拒绝。

扫描算法固定为：完整 pass A → canonical ancestor/root rebind → 完整 pass B →
要求 canonical row stream byte-identical → pure gate → 完整 pass C → 要求与 A/B
byte-identical → 首次 mutation。每个 pass 内文件按 manifest ordinal打开、完整读、
same-fd pre/post seven-stat、hash 后关闭；collection dirfds与全祖先贯穿三个 pass。

## 7. Lock 与 descendant 拓扑

- native 是 top-level writer owner；flock 成功前不加载 H1/private Python。
- run lock inode 永久受 owner 绑定，不进入 cleanup、rename 或 rotation domain。
- native 与 exec 后 Python 使用同一 OFD；任何副本均禁止 `LOCK_UN`。
- Python 收到 fd 后立即 `os.set_inheritable(fd, False)`。
- `0/1/2` 仅作为 native 已验证的标准 I/O 保留，不出现在 FD table，也不得成为
  authority、lock 或 workspace mutation channel；除这三者和 table rows 外，
  loader 启动时存在任何 open fd 都 hard-fail。
- ordinary model/read-only/subprocess 使用 `close_fds=True` 且 `pass_fds=()`。
- mutating builder child 的唯一链为 Apple `env -i` → hash-qualified native
  `mutating-builder-child` → pinned Python/embedded loader；每一层显式传同一 lock
  OFD，child 只验证继承 OFD，不 reopen/reflock。
- parent 被 SIGKILL 时，只要 mutating descendant 仍可能写，descendant 持有的同一
  OFD 继续维持互斥；最后一个 writer fd 关闭才释放锁。

## 8. Data flow

```text
Apple env -i
  -> native stage 0/1 self + authority + environment validation
  -> owner-derived run.lock open + BSD flock on exact inode
  -> retained bounded authority/collection descriptors
  -> runtime/repo/mode-schema validation
  -> canonical internal FD table + exact fd allowlist
  -> same-PID exec pinned Python with embedded loader
  -> loader FD-table/descriptor recertification
  -> source-only H1 load
  -> exact 58-field ResolvedInvocation
  -> exact 17-field observations + 2509 liveRows
  -> existing pure gate
  -> post-gate bounded recertification + inventory pass C
  -> mode-specific crash-safe publisher or one owner-bound semantic batch
```

## 9. Failure handling

- pre-lock failure：无 workspace/worker mutation，返回稳定 native error。
- lock contention：立即 fail-closed；不等待、不创建第二 lock inode。
- FD table framing/hash/schema/stat/rebind失败：稳定前缀
  `semantic bootstrap fd table invalid`，不加载 H1。
- bounded evidence漂移：稳定前缀
  `semantic bootstrap retained evidence drift`，不持久化。
- collection两/三遍不一致：稳定前缀
  `semantic bootstrap inventory drift`，不持久化。
- pure gate错误原样保留其既有稳定前缀；loader只做进程退出映射，不把错误降级为
  warning或候选重试。
- installed I 等 formal-reclose intent 仍按 formal policy 获绝对恢复优先；
  candidate/create-jobs/completion 不得在 incomplete transition 上运行。
- native/Python crash 只靠既有 create-only/fsync/rename/link 状态机恢复；internal
  FD table不落盘、不是 recovery authority。

## 10. 测试设计

实施必须逐项 RED → GREEN：

1. C source-set exact 12 roles、mode schema、UTF-8/LF/compile `-nostdinc`。
2. 13-mode stage 0、14-mode stage 1、argv/environment/self/authority错误矩阵。
3. permanent run-lock exact inode、shared OFD、parent SIGKILL、第二 owner拒绝、无
   `LOCK_UN`。
4. internal FD table canonical/base64url/size/hash/exact-schema/row-order/type/stat/
   parent-binding反例。
5. unlisted fd 在 exec 前关闭；listed fd 跨 exec；loader立即恢复 non-inheritable。
6. symlink ancestor、canonical name→inode替换、same-inode byte drift、late STOP/
   owner/pointer/contract replacement。
7. 2509-row inventory双扫描与 post-gate pass C；prefix-only、tail partial、gap后
   complete、late add/remove/change全部拒绝。
8. pure gate 调用期 filesystem API 哨兵为零，证明它只消费 snapshots。
9. builder lineage继承同一 OFD；model/read-only descendants 无该 fd；parent
   SIGKILL 后第二 owner仍拿不到锁。
10. source-only loader拒 pyc、set 外 private module、normal SourceFileLoader、
    dynamic/path import 与 decoded-string漂移。
11. fault injection 覆盖 lock 后、table 前、exec 前、loader pre-gate、gate 后、
    publisher每个既有 durable edge。
12. real read-only canary 先只构造 H24 2509-row observations/projection，不发布
    pointer；全部独立复审通过后才进入 source-set/build/release/activation gates。

## 11. 发布顺序与停止条件

1. TDD 并独立复审 `bootstrap_main.c` 与 `python_loader.py`。
2. 形成 exact 12-role source manifest；create-only 发布 source set。
3. 发布并闭合 build contract、toolchain index、output profile、verifiers、loader、
   plan 与 selection authority；新的 output profile 必须授权完整 ordered typed
   load-command rows，且 retained probe 中移动 `LC_FUNCTION_STARTS` payload/offset、
   同步 UUID 和 CodeDirectory page hashes 的反例必须被 direct pair 与正式
   `verify_request` 同时拒绝。
4. 两次独立 build/sign，pair verifier 与 release-closure verifier全部通过。
5. create-only 发布 release receipt，再旋转 native/runtime/formal successor。
6. provision workspace owner，发布 implementation set，激活 pointer，执行单 job
   canary并恢复 STOP。
7. 双库各自 canary均绿且共同 policy/runtime hash闭合后，才恢复 semantic long
   run；shared registry/freeze仍等两侧全部 formal完成。

任何一步出现 schema歧义、hash/path漂移、不可恢复 crash state、actual binary
缺失、source-lock/rights变化或同一 job 三次失败，立即保持/恢复 STOP，不跳过、
不降门槛、不写 shared registry/completion/package。
