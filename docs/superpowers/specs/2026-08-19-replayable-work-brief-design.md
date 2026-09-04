# Harness APK 可回放工作简报：画布、时间轴与录音纪要设计

日期：2026-08-19

评审收口：2026-08-20

状态：产品方向已确认；Spec 已准备开始 P0 技术试点，但尚未通过 P0；P1-P4 仅为候选实施包

首发设备：联想 Y900 平板 + 可连接手写笔，横屏优先

关联文档：

- `docs/product-plan.md` v5
- `docs/superpowers/specs/2026-08-07-m1-zero-friction-capture-design.md`
- `docs/superpowers/specs/2026-08-07-m2-cross-device-run-design.md`
- `docs/superpowers/specs/2026-08-07-m3-project-memory-closure-design.md`
- `CONTEXT.md`

## 1. 决策摘要

工作模块新增一种项目交付物：**可回放工作简报（Replayable Work Brief）**。

用户操作的不是一段孤立录音、一个白板文件或一条 Run 时间线，而是一份归属于明确项目的工作简报。用户可以在记录场次中用手写笔绘制有限画布、录音、查看或修正转写、插入决策/问题/待办标记，并把这些内容与项目文件、Diff、测试、Commit 或 Remote Run 证据建立时间锚点。结束后，系统形成可回放、可导出、可检索的项目交付物；AI 只能基于用户明确选择的证据生成纪要候选，不能把录音、手写或 OCR 直接变成代码动作、审批决定或项目事实。

本方向取代“复刻 ChatGPT 全双工语音”作为新增产品押注。现有语音能力继续承担低摩擦输入；本 Spec 不建设实时语音陪聊、语音打断 Agent、流式语音回复或仅靠语音批准高风险操作。

### 1.1 一句话主张

> 在 Y900 上边画边说，把某次 AI coding 的判断、代码依据和下一步保存成之后可以快速恢复的项目证据包。

### 1.2 北极星结果

另一个时间点的自己或同一项目 Agent，打开简报后能够在 90 秒内回答：

1. 当时要解决什么问题；
2. 做了哪些关键判断，为什么；
3. 判断关联哪些文件、Diff、测试、Commit 或 Run 证据；
4. 哪些内容仍未验证；
5. 下一步应该做什么。

“录得最多”不是目标；**以最短时间恢复可验证上下文**才是目标。

## 2. 背景与问题

Harness 当前已经具备项目会话、项目文件、Markdown 变更提案、Diff/Apply、Git 和语音转写，也已经为 M2 设计了 Remote Run 时间线，为 M3 设计了项目事实检索与沉淀流程。但这些能力仍遗漏一类信息：用户在讨论架构、复现问题、讲解交互或审核 Agent 结果时，常用空间关系、圈画、箭头、临时手写和口头解释表达“为什么”。

普通聊天记录只能保留顺序文本；屏幕截图只能保留单个状态；Run 时间线只能证明 Agent 做了什么；录音又难以快速定位。它们单独存在时，下一次恢复仍要重新拼接上下文。

可回放工作简报只补充**用户主动表达的工作上下文**，不建立第二套项目记忆，也不取代以下权威来源：

- 当前代码、文件和 Git：证明代码现在是什么；
- M2 落地后的 Run Event / Completion：证明 Agent 执行了什么；
- M3 落地后的项目 Markdown 和 Evidence Snapshot：证明项目已经确认什么；
- 可回放工作简报：证明用户当时画了什么、说了什么、标记了什么，以及这些表达引用了哪些证据。

### 2.1 当前实现基线与依赖

本 Spec 描述目标产品，不把尚未合入当前实现的 M2/M3 对象伪装成已可调用能力。2026-08-20 审阅时，当前工作分支 `main` 的 Room 为 v21，产品计划把 `test` 作为预验证基线，且本地 `test` 已包含更后的 M2/M3 实体。任何实施计划开始前必须：

1. 固定 `<branch>@<commit>` 和对应 Room 版本；
2. 确认 M2/M3 是否已合入该提交，并列出实际可用的 Run、Evidence Snapshot 与 Markdown Draft 接口；
3. 在实施包中选择当时下一个可用 migration，单独维护 `.hbrief schemaVersion`；
4. M2 未验收时隐藏结构化 Run 入口，不得用 `RemoteTimelineItem`、`ChatExecutionEntry` 或临时 UUID 冒充 Run Evidence；
5. M3 未验收时不开放直接“沉淀到项目”，只允许把选中证据带入项目会话草稿。

## 3. 目标、非目标与边界

### 3.1 目标

1. 在联想 Y900 横屏上提供低延迟、stylus-first 的有限画布体验。
2. 让笔迹、录音、转写、用户标记和代码证据共享可解释的时间轴。
3. 记录期间切后台、锁屏、断网、来电、进程死亡或空间不足时，不静默伪装为完整记录。
4. 允许用户回放到任意时间点，看到当时已经出现的笔迹、对应转写和证据锚点。
5. 让 AI 生成带时间与证据引用的纪要候选，并继续走现有 Markdown Diff/Apply 管线。
6. 让简报作为一个项目文件可查看、导出、导入和显式提交，同时避免把本地原始音频默认推入 Git。
7. 无手写笔、无麦克风权限、无网络或无转写 Provider 时，仍能保存可理解的降级简报。

### 3.2 明确非目标

- 不新增第四个底部 Tab、Notebook 模式、任务模式或独立白板项目。
- 不做可视化 Workflow 画布、节点编排、多 Agent 流程图或可执行连线。
- 不做无限画布、思维导图、复杂图形识别、自动排版或多人实时协作。
- 不录制 Mac 屏幕、IDE、终端、系统音频或所有用户操作。
- 不做通用会议系统、全局语音备忘录或跨项目音视频 RAG。
- 不以 OCR 或多模态模型输出替代用户确认，不把手写箭头自动转成代码依赖。
- 不因录音内容自动修改文件、启动 Run、批准命令、Commit 或 Push。
- 不隐藏同步 Y900、手机与 Mac 文件，不复制完整 Mac 工作区到平板。
- 不要求每个 Run 或项目会话都创建简报；它始终是可选交付物。
- 不复刻 ChatGPT 式全双工语音。

### 3.3 真实性边界

简报中的证据按以下权威顺序展示和提供给 AI：

1. `VERIFIED_RUN`：结构化文件、命令退出状态、测试和 Git 证据；
2. `REVIEWED_ARTIFACT`：已经 Diff/Apply 或用户直接维护的项目 Markdown；
3. `USER_CONFIRMED`：用户主动创建或确认的标记、修正转写、明确选中的画布区域；
4. `USER_STATED`：未确认的原始转写和普通笔迹；
5. `VISUAL_INFERENCE`：OCR、手写识别或多模态模型推断。

低权威内容不能覆盖高权威内容。简报可以证明“当时表达过”，不能单独证明“代码已修改”“测试已通过”或“已经决定”。

## 4. 核心用户与场景

### 4.1 首发用户

- 在联想 Y900 上使用手写笔进行产品、架构、调试或代码审核记录的个人开发者；
- **2026-09-04 修订：首发设备扩展为"Y900 或电纸书手写设备（HiBreak，自带手写笔）"——以用户每日真实携带的设备为准，Spike-画布先在 HiBreak 上采集能力矩阵与延迟数据；**
- 在 Mac 上运行 Codex 或其他 Agent，同时用 Y900 作为第二工作面板；
- 之后需要让自己或同一项目 Agent 快速恢复上下文的人。

首期不为多人会议交接或企业协作优化，但录音提示、导出和删除必须按可能包含第三方声音设计。

### 4.2 黄金场景

1. **需求与架构澄清**：画模块关系并口述约束，标记最终选择与被否决方案。
2. **复杂问题复现**：按发生顺序画状态变化、记录现象，并锚定日志、截图和相关文件。
3. **UI / 代码审核**：在截图或结构底图上圈画，锚定 Diff、Commit、文件行或 Run 完成证据。
4. **Agent 结果验收**：回放用户在测试前后的判断，把问题标记关联到测试与 Git 状态。
5. **次日继续工作**：不重听全部录音，直接从决策、未解决问题和代码锚点恢复。

### 4.3 不适用场景

- 只需说一句提示词或查询一次进度；使用现有语音输入。
- 只需证明 Agent 修改和测试结果；使用 M2 完成卡。
- 只需维护稳定项目结论；使用 M3 Markdown 沉淀。
- 没有空间关系、时间过程或复盘价值的普通聊天。

## 5. 产品对象与关系

```text
Project 1 ── N ReplayableWorkBrief
Project 1 ── N Run
ReplayableWorkBrief 1 ── 1 CaptureSession
ReplayableWorkBrief 0 ── N BriefRunEvidenceLink ── 1 Run
CaptureSession 1 ── N CaptureSegment
CaptureSession 1 ── N CanvasPage
CanvasPage 1 ── N Stroke
CaptureSession 1 ── N TranscriptSegment
CaptureSession 1 ── N TimelineEvent
TimelineEvent 0 ── 1 UserMarker
TimelineEvent 0 ── 1 CodeAnchor
ReplayableWorkBrief 1 ── N BriefRevision
ReplayableWorkBrief 0 ── N BriefSummaryCandidate
```

### 5.1 可回放工作简报

必须归属一个 `projectId`。它是用户在项目“文件夹”中看到的单个复合交付物，v1 包含且只包含一个记录场次，以及可回放素材、用户标记、证据引用和修订信息。

简报不允许在创建后静默改变项目。跨项目使用必须显式“复制到其他项目”，生成新 `briefId`，记录 `sourceBriefId + sourceRevision`，保留来源说明但不共享本地音频引用。已封存简报需要继续记录时创建新简报，并以只读 `continuationOfBriefId` 关联，不能向原简报追加新的工作日。

### 5.2 记录场次

一次用户明确开始、暂停、恢复并结束的捕获过程。v1 在 UI、Room 约束和 `.hbrief` schema 三层都强制一份简报只有一个场次；`sessions.length > 1` 的输入不按 v1 猜测导入。已封存场次的原始时间顺序不可修改。

### 5.3 捕获片段

同一记录场次内，由一次连续的进程和单调时钟产生的事件片段。暂停后恢复、应用重启后恢复或设备重启后继续都创建新片段，从而避免跨启动伪造连续的单调时间。

### 5.4 用户标记

类型固定为：

- `DECISION`：当时倾向或确认的决定；
- `QUESTION`：尚未回答的问题；
- `TODO`：后续动作；
- `BOOKMARK`：只有定位作用的书签。

标记默认只是 `USER_CONFIRMED` 或 `USER_STATED`，不会自动写入 `context.md`。

### 5.5 代码锚点

类型固定为：

- `PROJECT_FILE`
- `FILE_RANGE`
- `DIFF`
- `COMMIT`
- `TEST_EVIDENCE`
- `RUN_EVENT`
- `RUN_COMPLETION`
- `SCREENSHOT_REGION`

代码锚点只保存项目相对定位、内容哈希、可选 Git Blob/Commit、Run/Evidence ID 和必要的小型摘录。不得保存带凭证 URL、Mac 用户目录前缀或完整工作区副本。

### 5.6 Run 证据关联

Run 始终归属于 Project，不归属于 Brief。简报可以在 Run 前独立创建，也可以由 Run 页面发起；两者只能通过用户显式创建的 `BriefRunEvidenceLink` 关联：

```kotlin
data class BriefRunEvidenceLink(
    val briefId: String,
    val projectId: String,
    val runId: String,
    val runSnapshotId: String,
    val sourceRevision: Int,
    val relationType: BriefRunRelationType,
    val linkedAt: Long,
)
```

- 唯一键为 `(briefId, runId, runSnapshotId)`；创建时强制 `run.projectId == brief.projectId`；
- 关联只读取不可变 Run/Evidence Snapshot，不复制完整 Run 日志，也不在 Run 状态变化后静默改写；
- M2 稳定 `runId/evidenceId` 未进入实施基线时，禁用结构化 Run 关联，只允许用户创建安全手工标签；
- `.hbrief` 永远不能获得 `PROJECT_FACT` 权威级别，Run 关联也不会把其中的原始笔迹或转写升级为项目事实。

## 6. 信息架构与入口

### 6.1 主入口

```text
工作
  -> 项目
     -> 文件夹
        -> 新建
           -> 工作简报
```

- 不增加工作台 Tab；现有“会话 / 文件夹 / Git”结构不变。
- `ProjectArtifactType` 增加 `WORK_BRIEF`，展示名称“工作简报”。
- 项目文件筛选增加“简报”，但默认“全部”仍可看到。
- 简报列表行显示标题、最后更新时间、总记录时长、音频可用状态和未处理标记数。

### 6.2 次级入口

以下入口只有在对应对象已经归属项目时出现：

- 项目会话更多菜单：`新建工作简报`；
- Remote Run 详情：`在简报中记录`；
- Run 完成卡：`加入工作简报`，只创建证据锚点，不复制 Run 日志。

次级入口不得绕过项目归属确认。生活会话没有直接创建入口。

### 6.3 回放入口

点击 `.hbrief` 进入原生回放页，不依赖外部 App。外部文件管理器打开 `.hbrief` 时进入只读导入预览，用户确认目标项目后才安装到项目。

## 7. 主链路

```text
选择项目与“工作简报”
  -> 填写标题、选择空白/网格/图片底图
  -> 选择是否同时录音（默认关闭）；开启时确认转写与原音频策略
  -> 请求麦克风权限（仅录音时）
  -> 从可见 Activity 启动麦克风前台服务
  -> 开始记录
  -> 手写、翻页、标记、暂停/恢复、添加代码锚点
  -> 结束记录
  -> 本地封存与完整性检查
  -> 可选转写补全和 AI 纪要候选
  -> 用户审核简报
  -> 保存 `.hbrief`
  -> 可选“沉淀到项目”进入 Markdown Diff/Apply
```

### 7.1 动作预算

| 场景 | 正常路径上限 |
| --- | ---: |
| 从项目文件夹开始空白简报 | 新建 + 开始，共 2 个主动作 |
| 记录中插入书签 | 1 次点击或手写笔按钮 |
| 标记决策/问题/待办 | 2 次动作内 |
| 暂停或恢复 | 1 次动作 |
| 结束并进入回放 | 结束 + 确认，共 2 次动作 |
| 从 Run 完成卡加入证据 | 2 次动作内 |

首次权限、第三方录音提示和包含原音频的导出确认不计入日常动作预算。

## 8. 状态模型

### 8.1 简报状态

```text
DRAFT
  -> CAPTURING
  -> PROCESSING
  -> READY

CAPTURING -> RECOVERABLE
PROCESSING -> RECOVERABLE
RECOVERABLE -> CAPTURING | PROCESSING | READY | DELETING
READY -> PROCESSING              （生成新修订或纪要候选）
任一非终态 -> DELETING -> DELETED
无法恢复的结构损坏 -> CORRUPTED
```

- `READY` 只表示原始简报可以回放，不表示 AI 纪要已生成或已确认。
- `CORRUPTED` 必须保留仍可读取的页面、转写、标记或音频段，不能只显示“打开失败”。
- 同一设备全局只允许一个 `CAPTURING` 简报占用麦克风。

### 8.2 记录场次状态

```text
PREPARING
  -> ACTIVE
  -> PAUSED
  -> ACTIVE
  -> STOPPING
  -> SEALED

PREPARING | ACTIVE | PAUSED | STOPPING -> RECOVERABLE | FAILED
```

- `PAUSED` 停止音频写入和笔迹时间推进，但允许浏览已记录内容。
- 从 `PAUSED` 恢复创建新 `CaptureSegment`。
- 已 `SEALED` 的原始事件不可原地改写；转写修正、标记编辑和摘要产生新简报修订。

### 8.3 音频与转写子状态

音频：`DISABLED | RECORDING | PAUSED | LOCAL_AVAILABLE | RETENTION_PENDING | MISSING | DELETED | EMBEDDED`。

转写：`DISABLED | LIVE_PARTIAL | FINALIZING | READY | FAILED_RETRYABLE | UNAVAILABLE`。

音频和转写失败不能把仍有画布和标记的简报标为整体失败。

## 9. 画布交互

### 9.1 页面模型

- 画布是有限页面集合，不是无限平面。
- 默认页面逻辑尺寸为 `1600 x 1000`，适配 Y900 横屏 16:10；底图页面可使用自身宽高比。
- 所有笔迹坐标保存为页面逻辑坐标，不保存屏幕像素坐标。
- 首期最多 30 页；达到上限后允许删除空白页或新建另一份简报。
- 页面背景支持：空白、点阵、方格、单张本地图片、会话/Run 已有截图。
- 首期不支持 PDF 多页导入、网页实时快照、视频或 Mac 屏幕流。

### 9.2 首期工具

- 钢笔：颜色、基础宽度、可选压感；
- 荧光笔：固定透明度；
- 橡皮：整笔删除；
- 撤销 / 重做：只作用于当前场次内用户画布操作；
- 页面新增、切换和删除空白页；
- 套索、形状、文字框、贴纸、自动连线均顺延。

### 9.3 手写笔与触控规则

- 使用 Android 通用 `MotionEvent`，不绑定联想私有 SDK。
- 输入层必须取得原始 `MotionEvent`，按每个 pointer 调用 `getToolType(pointerIndex)`；不能假设高层 Compose pointer API 保留全部历史采样和工具信息。
- `TOOL_TYPE_STYLUS` 负责书写，`TOOL_TYPE_ERASER` 默认映射整笔橡皮。
- 手写笔在屏幕接触期间，手掌/手指事件默认只允许双指缩放和平移；单指触摸不产生笔迹。
- 手写笔未连接时，用户可显式开启“手指书写”，默认仍是单指平移、双指缩放。
- 压力、倾角和方向只在设备实际提供时保存；缺失时使用固定宽度，不伪造值。
- 必须消费 `MotionEvent` 的历史采样点；`ACTION_CANCEL`、`ACTION_POINTER_UP` 和带 `FLAG_CANCELED` 的 palm 事件取消尚未封存的对应笔画，不能误提交为短笔迹。
- 手写笔按键只在 Y900 真机确认稳定事件映射后启用，首期不得依赖按键才能完成任何动作。

Android 官方文档确认 `MotionEvent` 可提供工具类型、压力、倾角、方向、hover 与掌触相关事件；实现仍必须在目标 Y900 上逐项记录实际上报能力，不能只按 API 存在宣称支持。

### 9.4 笔迹数据

每条 `Stroke` 至少包含：

```kotlin
data class Stroke(
    val id: String,
    val pageId: String,
    val tool: StrokeTool,
    val colorArgb: Long,
    val baseWidth: Float,
    val startedAtOffsetMs: Long,
    val endedAtOffsetMs: Long,
    val pointsFile: String,
    val bounds: LogicalRect,
    val deletedByEventId: String?,
)
```

点序列包含逻辑坐标、相对时间、可选压力、倾角和方向。渲染层可以生成简化路径，但原始采样点在封存前不得因视觉平滑而被覆盖。

## 10. 时间轴与时钟

### 10.1 时间语义

每个 `CaptureSegment` 使用单调时钟作为唯一对齐基准：

- `segmentOriginElapsedRealtimeNanos`：片段内事件原点；
- `segmentBaseOffsetMs`：片段在场次时间轴中的起点；
- `offsetMs`：事件在完整场次中的逻辑位置；
- `wallClockStartedAt`：只用于显示日期和估算跨重启间隔。

禁止使用 `System.currentTimeMillis()` 直接对齐笔迹与音频。设备重启或进程恢复后创建新片段，片段间间隔标记为 `CAPTURE_GAP`，并标明 `EXACT` 或 `ESTIMATED`。

若音频引擎支持，使用与 `SystemClock.elapsedRealtimeNanos()` 同一 timebase 的 `AudioRecord.getTimestamp(..., TIMEBASE_BOOTTIME)` 对齐音频帧；设备不支持或返回失败时回退到片段单调时钟，并把精度标为 `ESTIMATED`。

### 10.2 时间线事件

`TimelineEventType` 首期固定为：

- `SESSION_STARTED`
- `SESSION_PAUSED`
- `SESSION_RESUMED`
- `SESSION_STOPPED`
- `CAPTURE_GAP`
- `CANVAS_NOT_VISIBLE`
- `CANVAS_VISIBLE`
- `PAGE_CREATED`
- `PAGE_CHANGED`
- `STROKE_COMMITTED`
- `STROKE_REMOVED`
- `TRANSCRIPT_SEGMENT`
- `USER_MARKER`
- `CODE_ANCHOR`
- `AUDIO_SEGMENT_STARTED`
- `AUDIO_SEGMENT_ENDED`
- `RUN_EVENT_LINKED`
- `SYSTEM_WARNING`

同一片段内使用严格递增的 `sequence` 消歧相同毫秒事件。墙上时间只显示，不参与排序。

### 10.3 回放

- 拖动时间轴时，画布显示该时刻已经提交且尚未删除的笔迹。
- 对应转写片段高亮；本地音频可用时同步 seek。
- 用户标记和代码锚点固定显示在轨道上，点击后定位到页面区域或证据详情。
- 暂停、来电、后台启动失败、转写断线、音频缺失都显示可见缺口。
- 回放不重演 Mac 屏幕、Agent 内部推理或所有 Run Item；Run 只显示用户显式关联的不可变结构化证据快照。
- 为避免从头重放全部笔画，每 30 秒或每 500 条笔画生成可重建的画布检查点；检查点不是事实源，损坏后可以从事件重建。

## 11. 录音与后台行为

### 11.1 开始条件

录音只能由可见 Activity 中的明确用户动作开始：

1. 用户在创建页勾选“同时录音并生成纪要”；
2. App 展示录音范围、转写 Provider 和原音频保留策略；
3. 用户授予 `RECORD_AUDIO`；
4. App 从前台启动 `microphone` 类型前台服务；
5. 服务进入前台并展示常驻通知后，才开始采集。

针对当前 `targetSdk 37`，Manifest 必须增加 `FOREGROUND_SERVICE_MICROPHONE` 和 `foregroundServiceType="microphone"`。Android 14 及以上不能在 App 已经进入后台后再创建依赖 while-in-use 麦克风权限的前台服务，因此恢复按钮必须先回到可见页面。

### 11.2 单一音频采集源

工作简报不能同时启动现有 `MediaRecorder` 与 `AudioRecord` 抢占麦克风。新增 `BriefAudioCaptureEngine`，使用一个输入源完成：

```text
AudioRecord PCM
  -> 时间戳与分段
  -> AAC/M4A 本地编码
  -> 可选实时转写流
  -> 电平与故障事件
```

现有 `PcmVoiceRecorder`、阿里云实时协议和 M4A 临时录音只能复用接口与错误经验，不能直接当成长时录音实现。

生产实现使用独立 `BriefAudioCaptureService`，声明 `FOREGROUND_SERVICE_MICROPHONE` 与 `foregroundServiceType="microphone"`，并和现有短语音输入共享一个全局麦克风租约；短语音、工作简报录音和其他录音流程不能同时占用输入设备。UI 的 `ON_STOP` 不得直接取消已经由该服务接管的采集，服务启动失败也不得显示“正在录音”。通知中的暂停/结束命令必须幂等。

### 11.3 后台与锁屏

本节是 P2 的正式产品行为，由 P0-B 先验证可行性；P0-B 未给出 `GO` 前不实施、不验收，也不对外承诺后台或锁屏录音。

- 已从可见 Activity 成功启动录音前台服务后，切后台或锁屏可以继续音频捕获。
- 画布只在前台接收笔迹；切后台时插入 `CANVAS_NOT_VISIBLE` 警告，不伪装为画布仍完整。
- 前台通知提供“暂停”和“结束”两个动作，不提供删除、AI 处理或项目写入。
- 来电、麦克风被占用或音频焦点永久丢失时暂停当前音频片段并插入 `CAPTURE_GAP`；不得无提示自动恢复。
- 用户回到可见页面后显式恢复，创建新捕获片段。
- 设备重启后绝不自动重新启用麦克风。

### 11.4 分段与上限

- 音频以最长 15 分钟或 32 MiB 的较小者分段；分段切换不得重置场次时间轴。
- 单场次最长 4 小时；到达上限前 5 分钟提示，并在上限安全封存。
- 开始前可用空间低于 500 MiB 时警告；低于 100 MiB 时安全停止音频并保留画布。
- 编码或转写失败时先保护本地可恢复数据，再展示错误。

### 11.5 原音频保留策略

录音首次和日常默认都为关闭。用户逐场次开启录音后，策略如下：

- `TRANSCRIPT_ONLY`：默认；最终转写、哈希和索引事务全部成功后删除本地原音频；
- `KEEP_LOCAL`：本场次显式勾选后，保留在 App 私有、禁止备份的本地音频仓中；不能被上次选择静默替代确认；
- `EMBED_ON_EXPORT`：不是持续状态，只在单次导出时把用户选择的音频复制进导出包，并再次确认。

每个 `CaptureSession` 保存 `audioPolicy`、`consentAt`、Provider、上传范围和删除结果。`TRANSCRIPT_ONLY` 遇到转写失败、索引未封存或进程中断时进入 `RETENTION_PENDING`，提示“保留并重试 / 保留本机 / 删除”；在用户选择前不得静默删除唯一音频，也不得把它视为用户同意长期保留。

本地原音频不自动进入项目目录、不自动加入 Git、不自动上传给纪要模型。转写 Provider 需要音频时必须在开始页清楚说明会发送什么。

## 12. 转写与修正

### 12.1 转写来源

- 阿里云实时转写可以生成 partial/final 文本；当前协议没有稳定片段时间字段时，使用本地 `AudioRecord` 帧边界推导范围并标记为 `ESTIMATED`；
- 硅基流动文件转写只在片段封存后执行，Provider 没有明确时间戳时同样标记为 `ESTIMATED`；
- Android 系统识别只用于短片段降级，不承诺覆盖四小时连续场次；
- 没有可用 Provider 时仍保存音频与画布，转写状态为 `UNAVAILABLE`。

只有 Provider 明确时间戳或可验证的本地音频帧边界才可标记 `EXACT`。150ms 是 P0-B 的实测目标，不是对当前 Provider 的能力声明。

### 12.2 转写对象

```kotlin
data class TranscriptSegment(
    val id: String,
    val sessionId: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val rawText: String,
    val correctedText: String?,
    val source: TranscriptSource,
    val timingQuality: TimingQuality,
    val final: Boolean,
    val redacted: Boolean,
    val createdAt: Long,
    val correctedAt: Long?,
)
```

- 回放与 AI 默认使用 `correctedText ?: rawText`。
- 修正必须保留“已修正”标记；关键否定词、文件名、分支名和命令不得依赖未确认转写自动升级为 `USER_CONFIRMED`。
- `redacted=true` 的片段不进入搜索、AI、纪要或默认导出。
- 首期不承诺说话人分离；用户可以给片段加人工标签。
- 删除转写文字不能保证从仍保留的原始音频中删除对应声音；界面必须明确提示，可选择删除全部原音频或导出时排除音频。

## 13. 用户标记与代码锚点

### 13.1 标记创建

用户可在记录中或回放时创建标记：

```text
决策：采用事件溯源，不做隐藏同步
问题：Y900 锁屏后音频是否连续
待办：在真机验证压感和橡皮事件
书签：登录返回路径图
```

记录中标记默认定位当前时间与当前页面视口；回放中标记定位当前 seek 时间。标记可以编辑文字和类型，但修改历史进入新 `BriefRevision`。

### 13.2 代码锚点字段

```kotlin
data class CodeAnchor(
    val id: String,
    val briefId: String,
    val sessionId: String,
    val offsetMs: Long,
    val projectId: String,
    val type: CodeAnchorType,
    val workspaceBindingId: String?,
    val repositoryFingerprint: String?,
    val branchName: String?,
    val headCommitSha: String?,
    val workingTreeDirty: Boolean?,
    val relativePath: String?,
    val lineStart: Int?,
    val lineEnd: Int?,
    val contentSha256: String?,
    val gitBlobId: String?,
    val commitSha: String?,
    val runId: String?,
    val evidenceId: String?,
    val excerpt: String?,
    val locatorLabel: String,
    val createdAt: Long,
)
```

- 绝对路径只用于当下打开，不写入 `.hbrief`；导出时只保留相对路径和安全标签。
- 文件、Diff、Commit 和 Test 锚点必须带安全的 `repositoryFingerprint`；同一 Project 绑定多个工作区时还必须带 `workspaceBindingId`。相对路径相同但仓库指纹不同，视为不同来源并阻断自动跳转。
- `branchName + headCommitSha + workingTreeDirty` 记录创建锚点时的仓库状态；未提交内容仍以 `contentSha256` 为准，不能只凭分支名还原。
- `excerpt` 必须受长度限制并计算哈希；不得为了“完整”复制整个源文件。
- 文件、行号或分支变化时显示“来源已变化”，继续展示记录时摘录，不静默跳到新内容。
- Run 锚点必须引用 M2 的稳定 `runId + logical event/evidence id`；M2 未实现时只允许手动保存安全标签，不伪造 Run 证据。
- 错误项目中的锚点创建必须阻断，不提供跨项目自动搜索。

## 14. AI 纪要与项目沉淀

### 14.1 纪要不是原始事实

结束记录后，系统可以生成 `BriefSummaryCandidate`：

```kotlin
data class BriefSummaryCandidate(
    val id: String,
    val briefId: String,
    val sourceRevision: Int,
    val status: CandidateStatus,
    val markdown: String,
    val evidenceRefs: List<String>,
    val providerId: String?,
    val model: String?,
    val createdAt: Long,
    val reviewedAt: Long?,
)
```

状态：`PENDING | READY | REVIEWED | REJECTED | FAILED`。

AI 失败、离线或没有支持图片的模型时，简报仍然可以成为 `READY`。

### 14.2 AI 输入预算

只有用户在“生成纪要”前确认的内容进入模型：

- 用户标记；
- 修正后的转写，或用户明确选择的原始片段；
- 被选择的画布关键帧/区域，默认不超过 12 张；
- 代码锚点的小型摘录与结构化 Run Evidence；
- 目标项目 `context.md` 的必要片段；
- 简报标题和记录范围。

默认不发送原音频、整份项目、完整聊天历史、隐藏推理、未选择页面或其他项目内容。

### 14.3 引用格式

候选纪要的每项决策、约束、问题和下一步必须使用可回跳引用：

- `⟦M1@00:12:40⟧`：用户标记；
- `⟦T4@00:13:05-00:13:18⟧`：转写片段；
- `⟦B2:P3@00:14:02⟧`：画布第 3 页区域；
- `⟦C5⟧`：代码锚点；
- `⟦R2⟧`：结构化 Run Evidence。

AI 视觉描述只能标记为“图中可能表达”，不得无引用地生成文件名、状态、测试结果或最终决策。

### 14.4 沉淀到项目

- 用户审核候选后点击“沉淀到项目”；
- M3 `MarkdownDraftOrigin` 已落地时，复用唯一 `MarkdownChangeDraft -> Diff -> Apply` 管线；
- 可建议更新 `context.md`，或创建 `research/`、`solutions/`、`reports/`、`sessions/` 中的 Markdown；
- 不能直接写 Markdown、自动 Commit 或自动 Push；
- `MarkdownDraftOrigin` 增加 `WORK_BRIEF_REVISION`，保留 `briefId + revision + sourceSha256`；
- 已应用 Markdown 是项目事实，简报候选仍保留为来源，不反向静默改写。

当前 Draft schema 仍强依赖 `conversationId + sourceUserMessageId` 时，不为简报新增第二套写入器，也不制造虚假消息：操作改为打开同项目会话草稿，附带 `BriefEvidenceSnapshot`，用户确认发送后再走现有消息来源的 Diff/Apply。只有 M3 的多来源 Draft schema 完成 migration 后，才启用简报页直接沉淀。

### 14.5 后续 Agent 使用

Agent 不自动摄入全部简报。用户可以：

1. 从简报选择标记、转写片段、画布区域和代码锚点；
2. 点击“在项目会话中继续”；
3. 系统创建带 `BriefEvidenceSnapshot` 的新请求草稿；
4. 用户确认发送；
5. 任何写文件或运行命令仍走现有安全边界。

### 14.6 Brief Evidence Snapshot

进入项目会话或 AI 纪要的每条证据都冻结为不可变快照：

```kotlin
data class BriefEvidenceSnapshot(
    val id: String,
    val projectId: String,
    val briefId: String,
    val briefRevision: Int,
    val entryId: String,
    val entryType: BriefEvidenceType,
    val authority: EvidenceAuthority,
    val locator: BriefEvidenceLocator,
    val startOffsetMs: Long?,
    val endOffsetMs: Long?,
    val sourceSha256: String,
    val redactionState: RedactionState,
    val createdAt: Long,
)
```

`BriefEvidenceLocator` 是带类型的 sealed value：画布使用 `pageId + logicalRect`，代码使用 `repositoryFingerprint + relativePath + lineRange + excerptHash`，转写/标记使用 `timeRange`，Run 使用 `runSnapshotId + evidenceId`。候选纪要中的每个事实性 claim 必须分别绑定至少一个已校验 Snapshot，不能只在整篇 Candidate 上挂一组无法对应的引用；缺少精确 locator 时拒绝事实性 claim 或标记“未验证”。

- `ContextSnapshotV3` 或等价后续版本保存选中的 snapshot ID；旧 V2 消息仍可读且不被重写；
- 引用校验器必须检查项目、revision、entry、hash、遮蔽/删除状态和权威级别；
- 证据丢失、漂移或未确认时拒绝生成事实性 claim，或明确显示“未验证”，不得重新检索后无提示替换；
- 删除/遮蔽后，未来请求不能继续发送原内容；历史已发送请求只保留不可逆副本提示。

## 15. 存储与 `.hbrief` 格式

### 15.1 Source of truth

- **记录中**：Room 元数据 + App 私有工作目录的追加日志是权威工作集；
- **READY 后**：项目中的 `.hbrief` 是可移植简报内容的权威版本；Room 是可重建索引和本机音频位置映射；
- **项目事实**：仍然是审核后的 Markdown、结构化 Run Evidence 和 Git，不是 `.hbrief` 中的 AI 候选。

当前实现基线没有稳定 Run Evidence、`BriefEvidenceSnapshot` 或多来源 `MarkdownDraftOrigin` 时，对应入口必须隐藏。文件/Diff/Git 安全标签可以先做，不能用临时远程 UI 状态替代结构化证据。

### 15.2 项目文件

默认路径：

```text
briefs/2026-08-19-登录回跳分析-<shortId>.hbrief
```

- `.hbrief` 是 ZIP 容器，MIME：`application/vnd.harness.work-brief+zip`；
- 文件写入使用同目录临时文件、`fsync`、原子 rename；
- 使用独立 `BriefBundleRepository` 实现 manifest/schema/hash/大小校验和原子写入，不复用只面向 Markdown 的直接写入或通用 ZIP 导入；
- Android 分享路由注册该 MIME，并在普通 Capture Draft 之前进入只读导入预览；
- 新增 `WORK_BRIEF` 时同步更新 Artifact filter、二进制预览的穷举分支与 `briefs/` 项目脚手架；
- 项目文件夹只展示一个 `.hbrief`，不展开内部笔迹文件；
- `.hbrief` 变更是二进制变更，Git 面板必须提示“工作简报不可逐行 Diff”；
- 简报不得自动进入白名单 Commit，只有用户显式选择才能提交。

### 15.3 Bundle v1

```text
manifest.json
summary/accepted.md                 # 可为空；只保存用户审核后的结构化摘要
timeline/events.jsonl
canvas/pages.json
canvas/strokes/<pageId>.bin
transcript/segments.jsonl
markers/markers.jsonl
anchors/anchors.jsonl
preview/cover.webp
assets/backgrounds/<sha256>.<ext>
audio/index.json                    # 记录可用性、哈希和策略
audio/<segmentId>.m4a               # 只有显式“包含原音频”导出时存在
```

`manifest.json` 至少包含：

- `schemaVersion`
- `minReaderVersion`
- `briefId`
- `projectLocator`（可移植安全标签，不含绝对路径）
- `title`
- `status`
- `revision`
- `sourceBriefId / sourceRevision`（首次创建可为空）
- `continuationOfBriefId`（可为空，只读关联）
- `createdAt / updatedAt`
- `captureSession`（v1 恰好一个）
- `durationMs / activeDurationMs`
- `audioPolicy / audioAvailability`
- `entryHashes`
- `sourceAppVersion`

### 15.4 本地原音频

默认保存在：

```text
noBackupFilesDir/work-brief-audio/<briefId>/<sessionId>/<segmentId>.m4a
```

- 不进入项目 ZIP 导出、Android cloud backup、搜索索引或 Git；
- Room 只保存相对位置、大小、哈希和状态，不保存绝对路径到 bundle；
- App 卸载后本地音频丢失；界面必须提前说明；
- 选择“包含原音频导出”时生成新的导出包，不原地改写项目文件；
- 导入含音频包时，用户必须选择“保留本地音频”或“只导入纪要”。

### 15.5 导入安全

- 拒绝绝对路径、`..`、空路径段、符号链接和重复规范化路径；
- 校验 manifest、schema、声明哈希、条目数、单条与总解压大小；
- v1 默认最多 10,000 个条目、2 GiB 解压总量、4 小时音频、30 页；
- 校验失败不写入半成品项目；
- 不读取或执行 bundle 内脚本、HTML、URI、Prompt 或命令；
- 未知 schema 只允许展示安全元数据，不尝试降级猜测。

导入身份规则：

- 外部包导入后总是生成新的本地 `briefId`，原 ID 写入 `sourceBriefId`；所有内部 entry ID 在导入事务中重映射；
- 保存 `sourceRevision + sourcePackageSha256`。同一项目已导入同一包时默认打开已有结果；只有用户显式“创建副本”才再次生成新 ID；
- 导入到其他项目不保留可写关联、不共享本机音频路径，Run 证据需在目标项目重新校验；
- 复制、导入或续篇都不能改写源 `.hbrief`，也不能把源项目的本地索引当成目标项目事实。

## 16. 本地数据模型

实现时使用冻结基线的下一个可用 Room migration，**不得在本 Spec 中硬编码为 21 -> 22**，因为 M2/M3/M4 可能先占用迁移号。P1 实施计划必须列出实际版本，并为“当前 v21、已落地 M2、已落地 M3”中仍受支持的升级路径提供 migration/instrumentation 矩阵；`.hbrief schemaVersion` 与 Room version 独立演进。

建议新增：

```kotlin
WorkBriefEntity(
    id, projectId, title, status, revision, bundleRelativePath,
    bundleSha256, totalDurationMs, activeDurationMs, audioAvailability,
    sourceBriefId, sourceRevision, continuationOfBriefId,
    createdAt, updatedAt, lastOpenedAt, errorMessage
)

CaptureSessionEntity(
    id, briefId, status, wallClockStartedAt,
    durationMs, activeDurationMs, audioPolicy, transcriptionProvider,
    consentAt, uploadScope, retentionResult,
    createdAt, updatedAt, errorMessage
)

CaptureSegmentEntity(
    id, sessionId, segmentIndex, baseOffsetMs, durationMs,
    wallClockStartedAt, segmentOriginElapsedRealtimeNanos,
    timebase, timingQuality, journalPath,
    audioPath, audioSha256, audioState, sampleRate,
    firstFramePosition, audioTimestampNs, audioClockDomain,
    driftEstimateMs, state
)

CanvasPageEntity(
    id, sessionId, pageIndex, logicalWidth, logicalHeight,
    backgroundType, backgroundRef, backgroundSha256, createdAt
)

TranscriptSegmentEntity(...)
TimelineEventEntity(...)
UserMarkerEntity(...)
CodeAnchorEntity(...)
BriefRunEvidenceLinkEntity(...)
BriefEvidenceSnapshotEntity(...)
BriefRevisionEntity(...)
BriefSummaryCandidateEntity(...)
```

高频笔迹点和音频帧不写 Room；Room 只保存块索引和元数据。每个笔迹点仍必须在二进制块内保存相对单调时间以及硬件实际提供的 pressure/tilt/orientation。统一换算式为：

```text
globalOffset      = segmentBaseOffset + monotonicOffset
globalAudioOffset = segmentBaseOffset +
                    (framePosition - firstFramePosition) / sampleRate
```

所有实体 ID 使用本机生成的随机 UUID；导入时按第 15.5 节重映射。`eventId` 为主键，`(sessionId, sequence)`、`(briefId, revision)` 和 `(briefId, runId, runSnapshotId)` 建唯一索引。v1 对 `CaptureSessionEntity.briefId` 建唯一索引，从数据库层阻止一份 Brief 多场次。

外键删除使用 `CASCADE`，但删除流程仍先进入 `DELETING` 并清理文件，避免数据库先删导致孤儿文件无法定位。

## 17. 原子性、恢复与一致性

### 17.1 追加日志

- 笔迹、事件和转写以带长度、类型、sequence 和 CRC 的追加记录写入工作日志；
- 每完成一笔立即写入；最多每 2 秒或 64 KiB 执行一次持久化 checkpoint；
- 日志尾部损坏时截断到最后一条 CRC 正确记录，并插入可见恢复警告；
- 同一 `eventId` 重放幂等，不重复生成笔迹、标记或转写。

提交顺序固定为：追加记录写入并通过 CRC -> journal `fsync` -> Room 事务写索引与唯一序列 -> 推进 UI 可见 checkpoint。崩溃发生在任一步时，重放只能得到“旧 checkpoint”或“包含该完整事件的新 checkpoint”，不能出现 Room 指向尚未落盘的笔迹块。

### 17.2 崩溃恢复

启动时扫描 `PREPARING | ACTIVE | PAUSED | STOPPING | PROCESSING`：

- 有可恢复日志：状态改为 `RECOVERABLE`，显示“继续记录 / 封存现有内容 / 删除”；
- 当前 M4A 片段损坏：保留之前已封存音频和全部非音频内容，标出缺口；
- 只有转写失败：允许重试转写或跳过；
- bundle 临时文件存在：校验完整后完成 rename，否则删除临时文件并从工作集重建；
- Room 与 bundle SHA 不一致：以完整且校验通过的较新修订为候选，要求用户确认，不静默覆盖。

设备重启后不自动继续录音。用户继续时创建新 `CaptureSegment`，片段间标记估算缺口。

### 17.3 并发

- 全局单写者：同一时间只有一个活动记录场次；
- 回放可并发读取已经 checkpoint 的数据；
- 导出基于固定 `BriefRevision`，记录继续时不改变正在导出的快照；
- 删除与导出冲突时，先完成或取消导出，再进入删除；
- M2 Run 事件接入使用幂等 `runId + runSnapshotId`，不得按实时到达次数追加；只读 snapshot 创建后不随 Run 更新。

## 18. 隐私、安全与删除

### 18.1 录音透明度

- 开始前明确说明是否保存原音频、是否上传转写以及当前 Provider；
- 录音期间页面和系统通知持续显示红色麦克风状态与时长；
- 可能录到他人声音时提示用户自行确认录音同意；
- App 不声称能自动识别或完成法律意义上的第三方同意。

### 18.2 传输边界

- 原音频在保留决策完成前只位于 App 私有目录；除用户明确选择的转写 Provider 上传范围外，不发送到其他位置；
- 使用云转写时，只发送当前场次明确选择的音频流或分段；
- 使用 AI 纪要时，只发送第 14.2 节列出的用户确认输入；
- Provider API Key、绝对路径、通知正文、日志和崩溃信息中不得出现音频、完整转写或项目秘密；
- 转写、画布 OCR 和 bundle 内文本全部视为不可信数据，不能覆盖系统指令或触发工具。

### 18.3 删除语义

删除简报时必须列出：

- 项目 `.hbrief`；
- App 私有工作日志、检查点、预览和缓存；
- 本地原音频；
- Room 索引与待处理转写/AI 任务；
- 尚未 Apply 的纪要候选和 Draft Origin。

已经 Apply 的项目 Markdown、Git Commit、已导出的文件或已推送远端不会随简报删除；UI 必须明确列出这些不可自动撤回的副本。删除完成后执行孤儿扫描。闪存介质不承诺物理安全擦除。

项目删除必须调用可恢复的 Brief 清理 hook：即使项目目录已经删除，仍可依据 Room 的 `projectId` 清理工作日志、私有音频、索引和待处理任务；中途中断后下次启动继续，最终执行孤儿扫描，不能留下可打开的跨项目残留。

## 19. 搜索、分享与 Git

### 19.1 搜索

本节只定义新增的 Brief 搜索字段，不删除或替换现有会话、消息、来源与项目索引。Brief 与 M3 Project 索引复用现有 `local_search_fts`，以 `projectId` 做查询隔离、删除和重建，不新建第二套 FTS。Brief 可索引：

- 标题；
- 用户标记；
- 未被遮蔽的修正转写；
- 用户审核后的摘要；
- 代码锚点安全标签。

不索引原音频、原始笔迹点、未确认 OCR、被遮蔽片段或其他项目内容。搜索结果进入简报对应时间锚点。

每条 Brief 搜索文档必须携带 `authority + briefRevision + entryId + sourceSha256`。查询意图为“当前决定 / 已完成 / 当前状态 / 项目事实”时，检索层排除 `USER_STATED` 与 `VISUAL_INFERENCE`，且未确认 Brief 内容不能单独作答或自动进入 Agent 请求；只有“当时讨论 / 用户原话 / 历史表达”等意图才允许返回低权威结果，并持续显示“历史用户表达”。

### 19.2 分享与导出

默认分享 `.hbrief` **不含原音频**，分享面板明确显示“含画布、纪要和证据引用，不含原录音”。用户选择包含音频时，重新确认范围和体积，并生成独立导出文件。

项目整体 ZIP 导出仍只包含项目目录中的 `.hbrief`，不自动把 `noBackupFilesDir` 音频注入项目包。

### 19.3 Git

- `.hbrief` 是显式项目交付物，可以由用户选择 Commit；
- Git 摘要显示文件大小、是否含嵌入音频、revision 和哈希，不能伪造行级 Diff；
- 自动提交信息可以建议“文档：更新登录回跳工作简报”，仍需用户确认；
- 大文件或包含音频时警告仓库膨胀，不自动配置 Git LFS；
- Push 继续单独确认。

## 20. 页面与交互规格

### 20.1 创建页

```text
新建工作简报

标题       登录回跳分析
底图       空白 / 网格 / 选择图片
录音       关闭（默认）
转写       阿里云实时
原音频     转写成功后删除（开启录音时默认）
[ ] 本次在本机保留原音频

可能录到他人声音，请先取得同意。

                         [开始记录]
```

无麦克风权限或 Provider 不可用时，保留“只记录画布”主路径。

### 20.2 Y900 横屏记录页

```text
┌─────────────────────────────────────────────────────────────┐
│ ← 登录回跳分析   ● 00:18:42   已保存   [暂停] [结束]       │
├──────────────────────────────────────────┬──────────────────┤
│                                          │  时间轴 / 纪要    │
│                 画布                     │  18:01 决策       │
│                                          │  18:06 问题       │
│                                          │  当前转写...      │
├──────────────────────────────────────────┴──────────────────┤
│ 钢笔  荧光笔  橡皮  撤销  重做  页 3/5   决策 问题 待办    │
└─────────────────────────────────────────────────────────────┘
```

- Y900 横屏宽度充足时使用画布 + 时间轴双栏；
- 记录 Activity 在 v1 锁定启动时的横屏方向；不在捕获中途自动旋转。未来解除锁定时必须证明逻辑坐标、音频和时间轴均不中断；
- 窗口宽度 `>= 840dp` 使用双栏；更窄窗口折叠时间轴为底部抽屉，画布仍占主区域；
- 当前录音、保存、转写和缺口状态必须同时可见，不能只靠颜色；
- 记录页只有“继续记录”一个主工作动作，AI 纪要不抢占记录界面。

### 20.3 回放页

```text
← 登录回跳分析                  [继续讨论] [更多]

画布第 3 页                       00:14:02 / 00:42:18
[决策] 采用弱绑定，不复制 Mac 工作区

────────────●────────────────────────────────────
转写：……
证据：ProjectRemoteBinding.kt · ⟦C5⟧

未解决 2    待办 3    来源已变化 1
```

- 默认停在最后一个用户标记或上次离开位置；
- 可以按“全部 / 决策 / 问题 / 待办 / 代码证据 / 缺口”筛选；
- “继续讨论”先打开证据选择，再生成项目会话草稿，不自动发送。

## 21. 无障碍与降级

- TalkBack 为工具、录音状态、时间轴事件、标记类型和证据漂移提供文字描述；
- 用户标记不能只靠颜色区分，同时使用图标和名称；
- 所有可点击目标至少 `48dp x 48dp`；正文对比度至少 4.5:1，大号文字和非文字控件至少 3:1；
- 字体缩放到 2.0 时，开始/暂停/结束、录音状态和缺口提示不得裁切或互相覆盖；TalkBack 焦点顺序按“顶栏状态 -> 画布工具 -> 画布摘要 -> 时间轴 -> 结束操作”；
- 外接键盘支持撤销、重做、切页和暂停，但首期不要求完整快捷键编辑；
- 系统“减少动态效果”时，回放只更新静态笔迹状态，不播放笔迹生长动画；
- 无手写笔：允许手指书写或只录音/标记；
- 无麦克风：完整画布、标记和代码锚点仍可使用；
- 无网络：本地记录、封存、回放和导出可用，转写与 AI 进入待处理；
- 模型不支持图片：只基于标记、转写和代码锚点生成候选，并明确“未分析画布”。

## 22. 性能与资源门槛

以联想 Y900 真机为首发门禁：

- 手写输入到预览路径 p95 小于 50ms，连续快速书写无肉眼可见断笔；
- 60 分钟、5 页、50,000 笔画点、1,000 个时间线事件下，记录 UI 保持可交互；
- checkpoint 后最多丢失正在书写的一笔，不能静默丢失已完成笔画；
- 回放任意 seek p95 小于 300ms；
- 进入已有简报首屏 p95 小于 1.5 秒，完整内容可延迟加载；
- 单次事件持久化不得阻塞 UI 线程；
- 后台录音 60 分钟不过度唤醒网络；实时转写失败时停止重连风暴，使用指数退避；
- 低电量、发热和存储不足只降级录音/转写，不破坏已保存画布。

性能数字均需目标真机实测，模拟器结果不能替代。

## 23. 异常行为表

| 场景 | 必须行为 |
| --- | --- |
| 手写笔断连或没电 | 当前已完成笔画保存；提示切换手指书写或继续语音，不结束场次 |
| 掌触被识别为普通触摸 | stylus 活跃时不生成笔迹；异常缩放可撤销，不丢原笔迹 |
| 麦克风权限拒绝 | 保留画布并以无录音模式开始；不循环弹权限 |
| Android 14+ 在后台尝试启动麦克风服务 | 阻断并要求回到可见页面，不捕获空音频 |
| 来电或麦克风被其他 App 占用 | 结束当前音频段、插入缺口、保持画布；用户显式恢复 |
| 实时转写断网 | 音频与画布继续；partial 不升级 final；恢复后重试或后处理 |
| 设备旋转 | Y900 默认锁定当前记录方向；若允许旋转，逻辑坐标不变且不中断音频 |
| 进程被杀 | 服务仍存活则继续音频并通知；服务也被杀则标记可恢复，不伪装连续 |
| 设备重启 | 不恢复麦克风；显示可恢复简报与估算缺口 |
| 空间不足 | 安全封存可写数据，停止音频，允许继续纯画布或结束 |
| 当前音频段损坏 | 保留前段、画布和转写，显示音频缺口 |
| AI 纪要失败 | 简报仍 Ready；允许重试或跳过 |
| 代码文件已变化 | 显示记录时摘录/哈希和当前漂移，不静默重定向 |
| 项目被删除 | 先执行项目删除用例统一清理简报索引和本地音频；不留下可打开孤儿 |
| 导出中继续记录 | 导出固定 revision；新增内容进入下一 revision |
| 删除时已有 Markdown 被 Apply | 只删除简报相关数据，明确 Markdown/Git 历史仍在 |

## 24. 测试与验收证据

### 24.1 JVM / 纯逻辑测试

- 简报、场次、片段和音频/转写状态机的合法与非法转换；
- 跨片段 offset 合成、sequence 排序、暂停和估算缺口；
- Stroke 二进制编码、CRC、尾部截断恢复和幂等重放；
- `.hbrief` manifest、路径净化、hash、zip bomb 和未知 schema；
- 标记与代码锚点权限、项目隔离、来源漂移；
- AI 输入选择、权威排序、引用格式和被遮蔽片段排除；
- 删除范围、导出有/无音频和 Git 摘要；
- Room migration、级联关系与 bundle/Room 对账。

### 24.2 Instrumentation / Compose

- per-pointer stylus/finger/eraser 路由、历史采样、`ACTION_POINTER_UP`、`ACTION_CANCEL` 与 `FLAG_CANCELED`；
- 横屏双栏、`840dp` 窄屏抽屉、字体 2.0、48dp 触控、对比度、TalkBack 顺序和减少动态效果；
- 权限首次授予、拒绝、永久拒绝和设置返回；
- 前台服务启动、通知暂停/结束、切后台和锁屏；
- 旋转、分屏、进程重建、低存储和 Provider 失败；
- 时间轴 seek 后画布、转写、音频与锚点一致；
- 导入预览和项目选择不发生跨项目泄漏。

### 24.3 联想 Y900 真机门禁

开始真机调试前继续遵守仓库 `AGENTS.md`：确认 ADB 授权，显式 `-s <serial>`，设置并验证仅 USB 供电常亮。

必须记录以下设备证据：

1. 手写笔 `toolType`、pressure、tilt、orientation、eraser/button 的实际上报矩阵；
2. 快速斜线、曲线、慢写、小字、掌触、双指缩放和笔/指交替；
3. 横屏连续书写 30 分钟无明显断笔、漂移、内存失控；
4. 录音 60 分钟，期间锁屏 10 分钟、切后台、断网、恢复网络；
5. 来电/其他录音 App 抢占后的可见缺口和显式恢复；
6. 强杀 Activity、强杀进程、系统回收服务和设备重启后的恢复；
7. 低电量、发热、剩余空间 500 MiB/100 MiB 门槛；
8. 回放时音频、转写和笔迹对齐误差：精确来源目标不超过 150ms，估算来源必须可见标记且不宣称精确；
9. 60 分钟黄金数据集上的打开、seek、导出、删除；
10. 无手写笔和无网络降级链路。

真机结果必须保存型号、Android 版本、App commit、输入设备描述、日志、屏幕录制和测试时间；模拟器不能替代。

### 24.4 端到端黄金链路

1. 项目文件夹 -> 新建简报 -> Y900 手写 + 录音 -> 决策标记 -> 结束 -> 回放。
2. 架构图 -> 锚定两个文件与一个 Commit -> 次日从标记恢复下一步。
3. Remote Run 进行中 -> 新建简报 -> 链接测试事件 -> 完成后显示结构化证据，不复制日志。
4. 断网 -> 继续画布和本地音频 -> 恢复后补转写 -> 原时间轴不重排。
5. 记录中进程死亡 -> 恢复 -> 封存已有内容 -> 明确显示缺口。
6. 代码变化 -> 打开旧简报 -> 显示记录时摘录和当前漂移。
7. AI 纪要引用决策/转写/画布/代码证据 -> 用户拒绝一项 -> Diff 只保留选中内容。
8. 导出不含音频 -> 另一项目导入 -> 回放画布和纪要，显示“原音频不可用”。
9. 导出包含音频 -> 二次确认 -> 导入后选择只保留纪要。
10. 删除简报 -> 本地音频、索引、缓存和未 Apply 候选清理；已 Apply Markdown/Git 明确保留。

## 25. 分阶段交付与范围熔断

### Spike-画布：手写笔 / 画布可行性试点（原 P0-A）

> **2026-09-04 修订：**
> **2026-09-04 结果：HiBreak 电纸书档 PASS**——压感/tilt 硬件上报确认，管道延迟 p50 12~21ms / p95 22~27ms，点级擦除与强杀恢复真机验证通过，报告见 [2026-09-04-spike-canvas-hibreak-report.md](2026-09-04-spike-canvas-hibreak-report.md)。**P1（本地简报核心）解锁。**

只做独立 debug prototype，不进入正式信息架构： ①试点更名——本文档 P0-A/P0-B 与《交付物层与入口收敛设计》已完成并上线的 P0-A/P0-B 重名，为消歧改为 Spike-画布 / Spike-录音（下同）。②首发设备扩展为 HiBreak 电纸书（自带手写笔）。③门禁分设备记账：输入预览 p95 < 50ms 对 Y900 保留为一票否决；HiBreak（e-ink）单独记录实测延迟分布，不套用 50ms 一票否决，由数据决定电纸书画布策略（快速局部刷新层 / 笔迹预测 / 接受更高延迟 / 降级截图批注）。其余退出条件不变。

只做独立 debug prototype，不进入正式信息架构：

- 采集 Y900 每个 pointer 的 MotionEvent 能力矩阵；
- 验证低延迟画布、掌触规则、横屏逻辑坐标和无笔降级；
- 验证 30 分钟书写、事件排序、追加日志、checkpoint 和强杀恢复；
- 生成包含型号、Android 版本、App commit、输入设备描述、日志与录屏的报告。

退出条件：输入预览延迟分设备记账（Y900 p95 < 50ms 一票否决；HiBreak 记录实测分布并出具电纸书画布策略建议）；掌触不产生笔迹；pressure/tilt/eraser/button 只按实际上报记录；横屏逻辑坐标与页面坐标一致；无手写笔时手指书写、只标记和退出均可完成；30 分钟内不静默丢失已完成笔画；强杀后恢复结果唯一且可解释。上述每项均需 PASS，P0-A 才能记为 `PASS`；它是 P1 的硬门禁。

### Spike-录音：Audio / Clock / Background 可行性试点（原 P0-B）

同样只做独立 debug prototype，不接入正式 `.hbrief`、AI、Markdown 或 Git：

- 验证单一 `AudioRecord -> M4A + 可选实时转写` tee；
- 验证 `AudioTimestamp`、frame position 与笔迹单调时钟对齐；
- 验证 microphone FGS、锁屏 10 分钟、切后台、通知暂停/结束、来电和服务被杀；
- 验证 60 分钟发热、电量、网络、存储与分段资源曲线；
- 只使用明确同意的测试材料，验证未授权上传为 0 和 `RETENTION_PENDING` 行为。

退出条件：给出 `GO / DEFER / NO-GO`。只有锁屏 10 分钟音频持续、通知始终可见且暂停/结束幂等、`RETENTION_PENDING` 三种选择可恢复、未授权上传为 0、精确来源同步误差 <=150ms（估算来源明确标记）、60 分钟内无静默丢段且所有中断都有缺口事件时，才可记为 `GO`。P0-B 是 P2 的硬门禁，但失败不阻断 P1 的无音频画布简报。

### P1：本地简报核心

进入条件：P0-A 通过；本 Spec 与 `CONTEXT.md` 已提交；实施计划已冻结目标 `<branch>@<commit>`、Room 版本、实际 migration 号和受支持升级矩阵。任一项缺失都不得开始 P1 编码。

- 项目入口、简报/场次/页面状态；
- 有限画布、笔迹持久化、标记、时间轴、回放；
- 最小只读文件锚点：项目相对路径、可选行范围、内容哈希和手工安全标签；
- 无录音也能完成一份简报；
- 崩溃恢复与 `.hbrief` 无音频 bundle；
- P0-A 通过。

退出条件：五个真实 AI coding 场景中，次日能在 90 秒内找到用户标记、关键判断、未解决问题、下一步和至少一个可回跳文件位置；任何静默丢笔、跨项目错绑或 v1 多场次输入被接受都阻断。

### P2：录音、转写与本地音频

- microphone 前台服务、分段录音、后台/锁屏；
- 实时/后处理转写、修正、遮蔽；
- 音频与画布同步回放；
- 本地保留策略、空间门槛和删除。

进入条件：P0-B 必须为 `GO`。退出条件：60 分钟黄金链路无静默缺口；任一未授权上传、无法解释的丢段、默认长期保留原音频或通知不可见都阻断。

### P3：代码/Run 锚点与 AI 纪要

- 丰富 Diff/Commit/Test 锚点与不可变 Evidence Snapshot；
- M2 可用后接入 Run Event/Completion；
- 带权威级别和回跳引用的纪要候选；
- M3 `MarkdownChangeDraft` 沉淀；
- 项目搜索与证据选择后继续会话。

退出条件：AI 不能越过用户选择与 Diff/Apply；关键约束错误执行必须为 0。

### P4：导出、导入与产品化

- `.hbrief` MIME、导入预览、分享有/无音频；
- Git 摘要、版本兼容、空间与孤儿清理；
- 完整无障碍、发布说明、回滚和迁移证据。

### 范围熔断

- P0-A 不通过，不用无限画布、OCR、更多工具或模型能力掩盖基础输入问题；只能明确降级为截图/触控方案或停止 P1。
- P0-B 为 `DEFER/NO-GO` 时，P1 仍可继续无音频简报，P2 不开始。
- P1 没有复盘增量价值，停止 P2-P4；可以保留轻量截图批注，不继续建设复合交付物。
- P2 隐私或可靠性门禁未通过，发布仅保留无录音简报。
- M2 未实现时，P3 不伪造 Run Evidence；先交付文件/Git 锚点。
- M3 未实现时，AI 纪要只保留候选，不新增第二套 Markdown 写入器。

## 26. 产品验证与 Kill Criteria

与现有 M2 完成卡、M3 Markdown 或普通会话记录进行对照，不以创建数、录音时长或笔画数作为成功指标。

在至少 20 个代表性 AI coding 场景中验证：

| 指标 | 通过门槛 | Kill 条件 |
| --- | --- | --- |
| 上下文恢复 | 次日找到正确下一步和证据的中位时间 <=90 秒，且比同类完成卡/Markdown 对照至少快 20% | 两周后没有可重复改善，或给出一次错误下一步 |
| 真实复用 | 至少 50% 已创建简报在 72 小时内被重新打开、引用或用于一次项目会话 | 主要行为只有创建，没有回放或引用 |
| 捕获成本 | 首段创建中位时间 <=45 秒；记录整理时间 <=任务活跃时间 5% | 中位 >60 秒，或 >30% 场次中途放弃 |
| 锚点质量 | 100% 简报有项目范围；>=99% 可交接简报有正确证据锚点或明确“未锚定” | 任一跨项目错绑或静默漂移 |
| 数据可靠性 | 强杀、断网、重启测试没有静默丢失已完成笔画/已封存段 | 出现无法解释的丢失、重复或覆盖 |
| 隐私 | 未经同意上传原音频为 0；录音状态始终可见 | 任一未授权上传或删除后应用可达残留 |
| AI 安全 | 所有纪要可回跳，所有项目写入经 Diff/Apply | 任一录音/OCR 未确认内容直接触发写入、执行或审批 |
| Y900 重复价值 | 连续两周每周至少创建 3 份含决策/约束/未解决项且被回看的简报 | 只有首次体验，没有稳定重复使用 |

如果用户最终只重听音频而不使用画布和锚点，应砍掉复杂画布；如果用户只使用画布截图而不回放时间轴，应砍掉长录音；功能必须按真实增量价值收缩。

### 26.1 评测协议

- 在试用前预注册 20 个任务 ID；每个任务写明 gold next step、允许证据、正确判定和适用的现有记录对照；
- 采用成对对照：同一任务分别只使用 Brief 与现有完成卡/Markdown/普通会话之一，从打开材料开始计时，到首次说出 gold next step 且指出允许证据时停止；顺序随机化；
- “已创建”定义为产生 `brief_ready`；草稿或损坏恢复不进入复用率分母；“重新使用”定义为发生 `brief_reopened`、`brief_evidence_used` 或由该证据触发的 `project_session_created`；
- `abandoned` 定义为用户开始捕获后主动退出、未达到 `READY`，且 24 小时内没有恢复；捕获成本以 `brief_ready` 样本计时，放弃率以所有开始捕获样本为分母；
- “正确下一步”由预注册答案或不知道实验条件的独立复核者判定，不由简报创建者临时自评；
- 两周结论同时要求完成 20 个预注册任务并达到各指标最低样本量；不能只等自然时间结束，也不能通过剔除失败任务改变分母。

## 27. 实施就绪结论

### 27.1 已经足够开始试点的部分

- 用户对象、项目归属和与 Run/Markdown/Git 的权威边界明确；
- Y900 stylus-first、有限画布、时间语义、录音前台服务和数据恢复目标明确；
- 原音频、本地 bundle、AI 输入和项目沉淀的授权边界明确；
- P0-A/P0-B 可以独立验证，不需要一次完成整个系统。

### 27.2 实施前必须由 P0-A/P0-B 证明的事项

以下不是继续补 PRD 的问题，而是目标设备技术证据：

1. Y900 手写笔实际上报的 pressure/tilt/eraser/button 能力；
2. Compose/View 渲染路径能否达到低延迟且正确处理掌触；
3. 单一 AudioRecord 同时编码和实时转写的稳定性；
4. AudioTimestamp 与笔迹时间轴在锁屏、暂停、恢复后的误差；
5. 60 分钟后台录音、热量、电量、网络和存储曲线。

### 27.3 就绪等级

**SPEC_READY_FOR_P0_SPIKE。P0-A/P0-B 尚未执行；P1-P4 尚未实施就绪。**

P0-A 通过后才可为 P1 编写冻结基线的实施计划；P0-B 为 `GO` 后才可为 P2 编写生产实施计划。P3 还依赖 M2/M3 的稳定接口，P4 依赖此前 bundle schema 冻结。任何阶段都不得在对应门禁完成前承诺发布日期。

## 28. 官方技术依据

- Android Developers, [Advanced stylus features](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)：`MotionEvent` 提供 stylus 工具类型、压力、倾角、方向、hover 与掌触相关能力，并要求优化高频事件处理。
- Android Developers, [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)：Android 14+ 对 microphone foreground service 和 while-in-use 权限的启动时机限制。
- Android Developers, [AudioTimestamp](https://developer.android.com/reference/android/media/AudioTimestamp)：音频帧位置和 `TIMEBASE_BOOTTIME` / `SystemClock.elapsedRealtimeNanos()` 对齐语义。
