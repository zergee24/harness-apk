# 生活模式交互审计

审计日期 2026-09-30 · 分支 `test` @ `3f66546`（`app/` 源码与 `3ce426c` 逐字节一致，两个新提交只动了 `.gitignore` 与 `docs/`）

> **修复状态**：P0 四项（F04 / F10 / F21 / F22）已修复，见 [第 16 节](#16-修复记录p0)。
>
> **注意行号漂移**：本文档的行号基于 `3f66546`。此后有并行会话在工作树上重构工作页（新增 `ui/work/WorkHomeScreen.kt`、work hub 入口、`remote/dsh/*`），`HarnessApkApp.kt` 与 `TabNavigationTest.kt` 的行号已经漂移，核对时请以符号名为准。

方法：三条并行审计线（生活历史/归档/空壳过滤、生活简洁模式、导航与状态机）产出候选发现，再由人工逐条复核代码。**未在真机或模拟器上运行过任何链路**，全部结论来自源码与测试文件阅读。

## 0. 怎么读这份文档

每条发现带两个标记：

| 标记 | 含义 |
| --- | --- |
| **复核 ✅** | 本轮人工逐行读过代码，结论直接可查 |
| **复核 ⬜** | 来自审计线，代码路径清楚，但本轮未逐行复核 |
| **可信度 高** | 代码路径完整，不依赖运行时数据 |
| **可信度 中** | 机制确定，但触发依赖存量数据或特定状态组合 |
| **🔬** | 需要真机 DB / 日志 / 截图才能判定是否真的会发生 |

**「可信度 中」+ 🔬 的条目不要当成已确认的 bug 去改**，先做现场取证。

## 1. 基线声明（先读这条）

当前 `test` 的实现已经和 `docs/specs/2026-09-05-life-interaction-v2/` 分道扬镳：规格描述的是「生活首页 = 打字/拍照/说话三个一级入口 + 最近聊过列表」，实现是「生活 Tab 直接内嵌聊天页 + 独立历史页」（见 `HarnessApkApp.kt:833-851`）。`LifePanelQuickEntryTest` 甚至反过来断言"想问点什么？"不存在。

**已定方向：C —— 保留"生活页 = 聊天框"，把语义按规格对齐。**

具体含义，本文档的判定标准以此为准：

- **结构不动**：生活 Tab 保持"直接打开当前问题"，历史保持独立页，不恢复三个一级入口。
- **语义对齐**：
  1. 生活页主屏会话不继承 `projectId`；
  2. 简洁模式下生活页主屏会话的身份必须是普通助手 `Assistant`；普通模式允许继承，但必须可见（规格 §1.1 原话："新建时明确展示实际身份，不静默伪装为普通助手"）；
  3. 顶栏必须让用户知道自己正在哪条会话里；
  4. 空壳判定只认"用户主动保留"信号，不认创建时系统自动写入的字段。
- 因此下文凡写"与规格 §X 偏差"，指的是**语义没对齐**，不是"界面元素没恢复"。

## 2. 根因地图

32 条发现是 7 个根因的重复投影。修根因比修发现划算。

> 编号说明：三条审计线共报 29 条，其中 3 条（F30–F32）在正文里独立成条，另有 2 条被并入既有条目（`currentProjectId` 导致会话从历史消失 → 并入 F10；导出页说明文案 → 并入 F16）。

| # | 根因 | 覆盖发现 | 在方向 C 下的处理 |
| --- | --- | --- | --- |
| 1 | `homeModeStore.lifeConversationId` 是唯一的主屏会话指针，**只在自动创建时写入** | F01 F02 F03 | C-2.3 顶栏可见 + C-2.1 语义 |
| 2 | 空壳过滤拿 Room 行字段当"用户配置过"的证据，而创建路径会自动写 `agentId`/`agentVersion` | F04 F05 F06 | C-2.4 判据换成 `userRetained` |
| 3 | `simpleMode` 走 DataStore 冷流且两处 `initial` 假设不一致；`mainMode` 却是 SharedPreferences 同步读 | F07 F08 | 单独修，与 C 无关 |
| 4 | `openWorkbench()` 绕过 `leaveHomeChat` 直接切 `mainMode=WORK`，防踢逻辑只挂在 `simpleMode` 上 | F09 | 单独修 |
| 5 | `currentProjectId` 是 App 级 `rememberSaveable`，从不清空 | F10 F11 F12 | C-2.1 直接根治生活路径 |
| 6 | 减法改造删了界面元素，文案/断言/死参数没跟着清 | F13–F19 | 纯清理，最低风险 |
| 7 | 同一条会话在不同页面走不同的数据装配路径 | F20 | 单独修 |

## 3. 无条件必修（P0，与方向无关）

这四条在任何方向下都是 bug，建议先做：

1. **F21** 配置包导入静默关闭接收方的简洁模式
2. **F10** `currentProjectId` 残留把生活会话变成项目会话，并从生活历史里彻底消失
3. **F04** 空壳过滤把系统写入的 `agentId` 当成用户配置，导致普通模式空壳永不隐藏
4. **F22** 撤销归档横幅在长列表里不可见

---

## 4. 根因 1：主屏会话指针只在创建时写入

### F01 生活主屏不跟随你实际使用的会话
- **现象**：在"历史记录"里点开会话 A、追问一句、返回 → 生活主屏显示的是 B（上次自动创建的那条）。而顶栏永远写"生活"，用户没有任何线索。
- **位置**：`app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt:452`（`saveLifeConversation` 全仓唯一调用点，在创建分支内）、`:439-444`（复用分支）、`:982`（历史页 `onOpenChat` 只 navigate）
- **机制**：`lifeConversationId` 只在 `prepareHomeConversation` 真正新建时写入；打开/继续任何已有会话都不会更新它。
- **复核 ✅ · 可信度 高**

### F02 同一条会话有两个名字
- **现象**：同一条会话，在生活主屏顶栏叫"生活"，从历史打开后叫它的真实标题。
- **位置**：`HarnessApkApp.kt:644`（字面量 `title = "生活"`）对 `:733-741`（独立聊天页显示真实标题）
- **机制**：生活分支的 `ChatAppBarTitle` 标题写死；只有 `Routes.ChatPattern` 分支才走 `title` 计算。
- **复核 ✅ · 可信度 高**

### F03 简洁模式不决定首页会话身份，关掉模式也不回滚
- **现象**：先普通模式用过一次智能体，再开简洁模式 → 顶栏副标题仍挂着那个智能体的名字，而不是"普通助手"。反向也一样：关掉简洁模式，首页还是那个"新问题/普通助手"会话。
- **位置**：`HarnessApkApp.kt:438-444`（复用分支优先于身份逻辑）、`:516`（分享路径压根不传 `simpleMode`）、`app/src/main/java/com/harnessapk/ui/NewConversationRequest.kt:11-16`
- **机制**：复用分支命中时不会重新决定身份；只有新建才调 `homeConversationRequest(useSimpleMode)`。
- **与规格**：§1.1 表格里"从生活一级入口新建…不能继承最近智能体"与"打开已有会话保持原会话身份"在这里互相打架——取决于你把生活页那条会话算"新建"还是"打开已有"。方向 C 的 C-2.2 给了裁决。
- **复核 ⬜ · 可信度 高**

---

## 5. 根因 2：空壳判定用错了证据

### F04 普通模式空壳永不隐藏（P0）
- **现象**：普通模式下，一旦用过智能体，之后每次点"新问题"，被弃用的空会话就永久留在"历史记录"里（标题"新会话"、点进去空白、带"已安装人物 · 基于资料模拟"标签）。简洁模式因为 `identity=Assistant`、`agentId=null`，反而不会留痕。
- **位置**：`app/src/main/java/com/harnessapk/chat/LifeConversationOverview.kt:475-481`（`hasStoredConfiguration` 含 `row.agentId != null || row.agentVersion != null`）、`:486-497`（隐藏条件要求 `!hasStoredConfiguration`）
- **机制**：`agentId` 是**创建时系统自动写入**的：`NewConversationRequest.kt:14-16`（`Suggested`）→ `app/src/main/java/com/harnessapk/chat/NewConversationUseCase.kt:39-49`（`identityRepository.suggest()` → `chatRepository.createConversation(agentId = selected.agentId, ...)`）。真正代表"用户主动改身份"的信号是 `markUserRetained`（`app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt:498-501`，由 `markExplicitConversationChoice` 调用），只在用户手动选身份/模型/Wiki 时触发。
- **与规格**：§3.2"用户未主动改标题、改身份或保存会话配置"；§1.1 明确"系统默认挂载不算用户主动保存的会话内容"。代码把系统挂载算成了用户配置。
- **修法**：隐藏判据改用 `facts.userRetained` + `customTitle`，去掉对 `row.agentId/agentVersion` 的直接依赖（C-2.4）。
- **复核 ✅（端到端追到 `createConversation`）· 可信度 高**

### F05 工作会话的智能体会漏进全新生活会话
- **现象**：在项目会话里用过的智能体，会被继承进一条全新的生活会话——这是 F03 顶栏挂名字的来源之一。
- **位置**：`app/src/main/java/com/harnessapk/agent/ConversationIdentityRepository.kt:18-23`
- **机制**：`suggest()` 走 `conversationDao.findLatestActive()`，**没有 projectId 过滤**；同一个文件旁边就有 `findLatestActiveInProject(projectId)` 但没被用。
- **复核 ✅ · 可信度 高**

### F06 草稿损坏被当成"空"，本该保留的被隐藏
- **现象**：一条只选了照片、没打字的会话，如果草稿记录损坏或来自更高 schema 版本且可恢复文本为空，这条会话会从历史里直接消失——用户连"草稿读取失败，请重选"都看不到。
- **位置**：`LifeConversationOverview.kt:21-48`、`:52-76`（`fromConversationDraft` 完全丢弃 `draft.error`）、`:486-497`；`app/src/main/java/com/harnessapk/chat/ConversationDraftStore.kt:843-855`、`:918-926`
- **机制**：`LifeConversationDraftEntry` 没有 error 字段，所以"error 非空但内容为空"的草稿在概览里表现为 `hasContent=false`、`hasUnavailableAttachments=false` → 满足全部隐藏条件。
- **与规格**：§3.2"任一事实未知时保留，不把加载中的对象视为空"。
- **复核 ⬜ · 可信度 中 · 🔬 需确认存量里有损坏草稿**

---

## 6. 根因 3：simpleMode 与 mainMode 的加载时序不一致

### F07 冷启动必闪，且会把"上次停在工作页"改写掉
- **现象**：冷启动瞬间底部先出现三个 Tab，"工作"随后消失；若上次停在过工作页，会先看到整屏工作页（深色工作主题）再跳回生活页。同一时刻"我的"里的简洁模式开关也短暂显示为关。**每次冷启动都重演**，并且用户上次选择的工作页被静默改写。
- **位置**：`HarnessApkApp.kt:271-272`（`collectAsState(initial = null)`）、`:274-279`（把 WORK 弹回 LIFE）、`:260-267`（把这次改动持久化）；`app/src/main/java/com/harnessapk/ui/HomeModeStore.kt:54`（`mainMode` 同步读 SharedPreferences）
- **机制**：`simpleMode` 是 DataStore 冷流（`app/src/main/java/com/harnessapk/storage/AppSettingsStore.kt:44`），首帧必然拿不到真实值 → `simpleMode == false`；而 `mainMode` 首帧就有值。二者不同源、不同步。
- **复核 ✅（机制）· 可信度 高 · 🔬 可见帧数需真机冷启动录屏**

### F08 聊天页自己又收一次同一个设置，且假设不同
- **现象**：进入会话/生活页的首帧按普通模式布局——有未完成执行时会先闪出"执行队列"条再消失；简洁模式专属状态晚一帧出现，造成跳动。
- **位置**：`app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt:381`（`initial = false`）对比 `HarnessApkApp.kt:271`（`initial = null`）
- **复核 ⬜ · 可信度 中 · 🔬**

---

## 7. 根因 4：进工作页的路径绕过了模式约束

### F09 简洁模式挡不住"分享/导入项目"进工作页
- **现象**：开着简洁模式，用系统分享一个普通文件 → 弹层里仍有"导入项目"与"最近项目"（`CaptureDestinationSheet` 完全不受 `simpleMode` 约束）；点项目后整屏切成深色工作主题、顶栏变"工作 · 项目名"，而**底部只有生活/我的且两个都不高亮**。此时系统返回直接退出 App，用户没有任何"我在工作页"的提示，离开后也没有工作 Tab 可以再回来。
- **位置**：`HarnessApkApp.kt:491-492`（`openWorkbench()` 无条件 `mainMode = WORK`）、`:1299`（`onImportToProject` 成功后调用）、`:274`（防踢的 `LaunchedEffect` 只以 `simpleMode` 为 key，`mainMode` 之后再变成 WORK 不会触发）、`:806-810`（`visibleModes` 过滤掉 WORK）、`app/src/main/java/com/harnessapk/ui/capture/CaptureDestinationSheet.kt:95-155`
- **副作用**：这次停留会把 WORK 写回偏好 → F07 每次启动都复现。
- **复核 ⬜ · 可信度 高（触发前提"用户会分享普通文件"属使用场景假设）**

---

## 8. 根因 5：currentProjectId 从不清空

### F10 生活页建出来的问题会从"历史记录"里消失（P0）
- **现象**：只要你**曾经**在工作页选过某个项目 → 回到生活 → 生活工具 → 知识库 → 打开一个 wiki → "用这个提问" → 新建的这条会话被挂到那个残留项目上，**它不会出现在"历史记录"里**。用户视角是"我刚建的问题不见了"。关掉简洁模式也救不回来。
- **位置**：`HarnessApkApp.kt:1102-1108`（"用这个提问"直接拿 `currentProjectId` 当 projectId）、`:882-885`（`currentProjectId` 只在 `onCurrentProjectChange` 写入，离开 WORK 无清空逻辑）、`:220-221`（`rememberSaveable`，跨旋转与进程死亡存活）；`app/src/main/java/com/harnessapk/storage/ConversationDao.kt:98-99`（生活概览 SQL 写死 `WHERE c.projectId IS NULL`）
- **复核 ✅ · 可信度 高**

### F11 被强制切模式时工作页选择丢失
- **现象**：被 F07 踢出工作页后再回去，原来选的项目/标签页变了，回到列表第一个项目。
- **位置**：`HarnessApkApp.kt:833-914`（`when (mainMode)` 切换 dispose `ProjectScreen`）；`app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt:821-828`
- **机制**：`selectedProjectId` 只是 `remember`，重进时 `LaunchedEffect(Unit)` 自动选第一个项目。
- **复核 ⬜ · 可信度 高**

### F12 workbenchTarget 未清，下次进工作页跳回旧位置
- **位置**：`HarnessApkApp.kt:241`；`ProjectScreen.kt:830-862`
- **复核 ⬜ · 可信度 中**

---

## 9. 根因 6：减法残留（纯清理，最低风险）

### F13 配置包欢迎语指向不存在的"下方 +"
- **位置**：`app/src/main/java/com/harnessapk/ui/settings/ConfigPackageImportScreen.kt:267`
- **原文**：`"配置完成。点下方 + 试试问一个问题。"`
- **与规格**：§2.3 点名禁止——"引导文案改为'配置完成，选一种方式开始提问'，**不得继续指向不存在的'下方 +'**"。
- **现状**：+ 在主屏**顶栏右侧**，且主屏没有 FAB。
- **复核 ✅ · 可信度 高**

### F14 导入成功后不回生活页
- **现象**：从"我的 → 配置包"导入的人会停在"我的"页，看到 F13 那句文案。
- **位置**：`HarnessApkApp.kt:1020-1023`（`onApplied` 只设欢迎语 + `popBackStack`，不改 `mainMode`）
- **与规格**：§2.3"外部配置导入成功后也应回到有意义的生活入口"。
- **复核 ✅ · 可信度 高**

### F15 "恢复到最近聊过"指向一个已被删除的区块
- **位置**：`app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt:715`（菜单项）、`:900`（提示"已恢复到最近聊过"）
- **机制**：生活页早已没有"最近聊过"这个区块（列表页叫"历史记录"）。而且 `restoreFromArchiveList`（`app/src/main/java/com/harnessapk/chat/LifeConversationOverview.kt:381-389`）只做 un-archive，**并不会把这条会话变成"最近聊过"的那条**——F01 的指针没被更新。
- **复核 ✅ · 可信度 高**

### F16 导出页说明描述的是旧版生活首页
- **位置**：`app/src/main/java/com/harnessapk/ui/settings/ConfigPackageExportScreen.kt:171`
- **原文**：`"对方导入后生活页只保留新建对话和最近会话"` —— 现在生活页是内嵌聊天，没有"最近会话"列表。
- **复核 ✅ · 可信度 高**

### F17 输入框上方的"调整会话上下文"条永不显示
- **位置**：`ChatScreen.kt:3180`（`showContextBar = onContextSummaryChanged == null`）；全仓唯一调用点 `HarnessApkApp.kt:565` 永远传非 null
- **与规格**：§1.1 普通模式"可保留已有直接入口及上下文栏"。现在普通模式也没有了，只剩 更多 → 本次提问设置。
- **复核 ✅ · 可信度 高**

### F18 归档列表的"返回生活"按钮永不显示 + 标题重复
- **位置**：`ConversationListScreen.kt:877-882`（需要 `onBack`）对 `HarnessApkApp.kt:992-997`（没传，默认 null）；同时 `:864-884` 还会再渲染一遍"归档列表"标题，与顶栏重复。
- **复核 ✅ · 可信度 高**

### F19 三个死参数 + 两个僵尸枚举
- **位置**：`ConversationListScreen.kt:104-106`（`onOpenAgentPackages`/`onOpenWikiLibrary`/`onOpenGlobalSearch` 声明了但从没用过）对 `HarnessApkApp.kt:984-986`（仍在传）；`app/src/main/java/com/harnessapk/storage/LifeConversationMetadataStore.kt:20-21`（`LIFE_PHOTO`/`LIFE_VOICE` 对应的一级入口已不存在，`recordOrigin` 全仓唯一调用点只写 `LIFE_TEXT` 或 `STANDARD`）
- **复核 ✅ · 可信度 高**

---

## 10. 根因 7：同一条会话在不同页面的装配路径不同

### F20 归档列表把智能体身份降级成占位符
- **现象**：同一个智能体会话，历史里是"小王 · 基于资料模拟"，进了归档列表变成"已安装人物 · 基于资料模拟"。用户会以为身份丢了。
- **位置**：`ConversationListScreen.kt:958`（硬传 `lifeAgentLabel(item, emptyMap())`）、`:818-823`（fallback `?: "已安装人物"`）；历史页对照：同文件 `:526` 传的是真实 `agentsById`
- **机制**：`ArchivedConversationListScreen` 只接收 repository，没有 agent 数据源。
- **与规格**：§3.5"恢复原 ID、消息、草稿和身份"。
- **复核 ✅ · 可信度 高**

---

## 11. 独立发现（不归属以上根因）

### F21 配置包导入会静默关闭接收方的简洁模式（P0）
- **现象**：(a) 包里没带 `simpleMode` 字段（旧版本包，或导出者取消勾选——这读起来像"不带这项"）时，接收方**原本开着的简洁模式被静默关掉**，工作 Tab 冒出来，导入预览里只有 `true` 才提示"将开启生活简洁模式"，**没有"将关闭"提示**；(b) 导出侧该勾选默认就是勾上的，且与导出者自己的设置无关——从没开过简洁模式的人也会发出去一个"给别人开启简洁模式"的包。
- **位置**：`app/src/main/java/com/harnessapk/configpackage/ConfigPackageApplier.kt:70`（无条件 `setSimpleMode(payload.simpleMode)`）、`:78`（`AppliedSummary.simpleModeEnabled` 被计算但 `welcomeMessage` 从不使用）；`app/src/main/java/com/harnessapk/packageformat/ConfigPackageCodec.kt:153`（只有 true 才写字段）、`:191`（缺省 false）；`ConfigPackageImportScreen.kt:206-208`（只在 true 时提示）；`ConfigPackageExportScreen.kt:74`（`mutableStateOf(true)`）、`:322`
- **复核 ✅ · 可信度 高**

### F22 撤销归档的入口在长列表里看不到（P0）
- **现象**：在列表中部归档一条记录，该行消失，但"已移到归档 · 撤销"渲染在列表最顶端；列表没有传 `LazyListState`，归档回调里也没有任何 `animateScrollToItem`。LazyColumn 会锚定当前可见项，所以横幅插在视口上方——**5 秒后静默消失**，用户唯一感知是"那行不见了"。连续归档两条时 `undoNotice` 是单槽，前一条的撤销入口被直接覆盖。
- **位置**：`ConversationListScreen.kt:227-244`（横幅 item）、`:136-141`（到期自动清空）、`:264-284`（归档回调，无滚动定位）
- **与规格**：§3.5"操作后显示'已移到归档 · 撤销'，默认 5 秒"——渲染确实存在，但用户看不到。"归档列表始终可恢复"这一半是成立的。
- **复核 ✅（结构）· 可信度 高 · 🔬 个别机型锚定行为可能让横幅短暂可见，需真机截图**

### F23 快速连点，后一次操作被静默吞掉
- **现象**：在生活主屏快速连点两个底部 Tab（或先点"历史"再点 Tab），**第二次点击永久丢失**。用户观感是"点了没反应"。
- **位置**：`ChatScreen.kt:450`（`leaveAfterSaving` 第一行 `if (leaveInProgress) return`）配合 `HarnessApkApp.kt:465-470`（先 `onBackRequestConsumed()` 清空闩锁，再调 `leaveAfterSaving`）
- **机制**：第二次动作既没执行、闩锁也已经清掉，谁也救不回来。窗口不短：草稿保存是**同步 `commit()`**（`app/src/main/java/com/harnessapk/chat/ConversationDraftStore.kt:81`）且排在 `draftWriteMutex` 后面，而逐键 autosave（`ChatScreen.kt:786-789`）会一直占着这把锁——**刚打完字立刻切 Tab 最容易触发**。
- **测试盲区**：`app/src/androidTest/java/com/harnessapk/ui/TabNavigationTest.kt:102-113`（`workToMeKeepsWorkTheme`）正好是连点 WORK→ME 不 wait，但只断言 `theme-WORK`，两种结果都能过。
- **复核 ✅ · 可信度 高**

### F24 "+" 和"历史"在草稿保存窗口内可点但无反应
- **位置**：`HarnessApkApp.kt:657`（`enabled = !homeCreating`）；真正阻塞发生在 `leaveAfterSaving` 的保存阶段，那时 `homeCreating` 还是 false。
- **复核 ✅ · 可信度 高**

### F25 生活主屏按系统返回 = 直接退出 App
- **位置**：`HarnessApkApp.kt:563`（内嵌时 `handleSystemBack = !embeddedHome` = false，等于关掉聊天自己的返回处理）、`:219`（主页 `canGoBack` 为 false）；全仓没有"再按一次退出"。
- **与规格**：§4.2"系统返回先关闭弹层/键盘，再离开页面"——在这条链路上等于失效。
- **复核 ✅ · 可信度 高**

### F26 回车键语义两个模式不一致
- **位置**：`ChatScreen.kt:5507`（`(!simpleMode || event.isCtrlPressed || event.isMetaPressed)`）、`:5522-5523`（`imeAction` 按模式在 `Default`/`Send` 间切）、`:5668-5676`（`shouldSendChatInputOnKeyEvent` 本身不要求修饰键）
- **判定**：规格 §4.2 把"软键盘默认换行…实体键盘 Ctrl/Cmd+Enter 可显式发送"写在"交互共性"里，是**通用规则**。所以**简洁模式是照规格做的，普通模式的"Enter 直接发送"才是偏差**。触屏家人用户走发送按钮，不受影响；撞到的是接物理键盘的场景。
- **复核 ✅ · 可信度 高**

### F27 每点一次"+"留一条看不见也删不掉的空会话
- **位置**：`HarnessApkApp.kt:428-455`（`forceNew = true` 无条件新建）；过滤条件见 `LifeConversationOverview.kt:462-497`
- **机制**：过滤本身严谨，所以用户看不见；但历史页没有批量删除，规格也明确不做永久删除——只增不减。
- **注意**：F04 修好之后，这些空壳会被正确隐藏；但已产生的行仍然存在。
- **复核 ✅ · 可信度 高**

### F28 草稿每敲一个字同步 commit 一次，无防抖
- **位置**：`ChatScreen.kt:786-789`（key 里含 `text`）；`ConversationDraftStore.kt:138`（`synchronized` + `commit()`）
- **与规格**：§3.3.1 本来就允许"UI 可短暂防抖"。在墨水屏设备上代价不小。
- **复核 ✅ · 可信度 高**

### F29 摘要与主标签优先级的两处偏差
- **现象**：(a) 纯附件草稿（选了照片/文件但没打字）在历史摘要里显示的仍是"上一次已发送的问题"，而不是规格要求的"已选 N 张照片/《文件名》"；(b) 主标签显示"已停止/正在生成/等待处理"时，行下方仍印着"附件或问题仍在准备，完成后再整理"，同屏自相矛盾。
- **位置**：`LifeConversationOverview.kt:664-691`（`requestSummary` 排在纯附件分支之前）、`:504-518`（`lifeDisplayStatus` 是"执行状态整段优先、草稿派生状态整段在后"，而规格给的是跨来源全序优先级）、`:446-450`
- **与规格**：§3.4（摘要规则、主标签优先级）；附带一处文案偏差：§3.5 指定的是"问题仍在处理，结束后再整理"。
- **复核 ⬜ · 可信度 高（顺序确定）· 判定取决于是否按规格字面读成跨来源全序**

### F30 打开旧式图片草稿的会话会把记录时间刷成"现在"
- **现象**：用户只是点开一条老记录看看，返回历史后这一行已经跳到最前、时间显示为刚才、分组变成"今天"。
- **位置**：`ChatScreen.kt:772-785`（载入草稿后对旧式内联附件调 `appendSelectedImages`）、`:527-540`；`ConversationDraftStore.kt:281-315`（`importImage` 先追加 IMPORTING 记录并 `save()`）、`:170-177`（内容变了就 `updatedAt = nowMillis()`）；`LifeConversationOverview.kt:433-438`（`updatedAt = maxOf(row.updatedAt, lastMessageAt, draft.updatedAt, recovery.updatedAt)`）
- **机制**：进入页面触发了内容变更（重新导入），于是绕过了"内容未变才保留旧时间戳"的保护。
- **与规格**：§3.4"轮询、页面进入、状态重读、无内容改变的 autosave 不更新时间，避免历史自动乱跳"——这里是页面进入引发了内容变更，结果就是乱跳。
- **复核 ⬜ · 可信度 中 · 🔬 需确认存量里有"只有 attachments、没有 imageMetadata"的草稿**

### F31 `【附件：文件名】` 会被当成历史标题
- **现象**：(a) 部分老记录在历史里的标题就是"【附件：报告.pdf】"；(b) 附件块里带着用户问题（"帮我总结"）的老记录，标题显示文件名而不是那句话。
- **位置**：`LifeConversationOverview.kt:530-539`、`:541-560`、`:593-640`；块格式定义 `app/src/main/java/com/harnessapk/chat/DocumentTextExtractor.kt:163-175`；相关测试 `app/src/test/java/com/harnessapk/chat/LifeConversationOverviewTest.kt:415-423`
- **机制**：纯文本兜底 `looksLikeAttachmentProtocol()` 被 `legacyAttachmentTitleMatched &&` 短路，只有 `row.title` 本身以"【附件："开头才生效；另一条兜底要求能用可解码的 requestContext 精确重建整块内容。
- **与规格**：§3.4"禁止从【附件：…】或工具协议中截标题"。测试显示"把手写【附件：…】当标题保留"是有意取舍，但同一放行逻辑会放过真实附件块。
- **复核 ⬜ · 可信度 中 · 🔬 需 DB 抽样确认前提组合存在**

### F32 归档拦截与界面状态互相矛盾（终态回执遗留）
- **现象**：历史里那一行的"更多 → 移到归档"是可点状态（无拦截说明），点下去固定弹"问题还在提交或确认发送状态，请稍后再整理"，直到用户打开过该会话一次。
- **位置**：`app/src/main/java/com/harnessapk/common/AppContainer.kt:581-584`；`app/src/main/java/com/harnessapk/chat/ChatSendRecoveryStore.kt:168-196`（`:180` `consumedTerminals.containsKey` → 返回 null）、`:385-401`、`:488-526`；对照 `LifeConversationOverview.kt:425-450`
- **机制**：`consumeTerminal` 把终态记录移出 `states` 放进 `consumedTerminals`，概览只观察 `states`，所以行上认为"没有进行中任务"，而 `withArchiveGuard` 却因 `consumedTerminals` 命中而拒绝。清理入口只有打开会话时的 `ChatScreen.kt:1484-1496`。当前分支已无 `consumeTerminal` 调用点，属升级路径遗留。
- **与规格**：§3.5 要求被拦时给"问题仍在处理，结束后再整理"，且拦截应与界面状态一致（§4.1"状态必须来自真实流程"）。
- **复核 ⬜ · 可信度 中 · 🔬 需确认升级用户日志里存在 `consumed=true` 的终态记录**

---

## 12. 待现场验证（🔬 需要真机数据，先取证再改）

| 关联 | 需要什么数据 | 判定什么 |
| --- | --- | --- |
| F06 | `conversation_drafts` 私有 SharedPreferences 存量 | 是否存在"解析失败且可恢复文本为空"的草稿 |
| F30 / F31 | 同上 + `conversations`/`messages`/`chat_execution_entries` 三表抽样 | 是否存在"只有 attachments、没有 imageMetadata"的草稿；是否存在"标题为占位符 + 首条 USER 消息含真实附件块 + 无可解码 requestContextJson" |
| F22 | 长列表中部归档后立刻截图 | 横幅是完全看不到还是短暂闪一下 |
| F07 | 冷启动录屏 / Macrobenchmark | 闪烁实际可见帧数 |
| F11 竞态 | 设备日志 | 冷启动被踢出时 `currentProjectId` 是否已被写入 |
| F32 | `ChatSendRecoveryPersistence` 日志 | 升级用户里是否存在 `consumed=true` 的终态记录 |

另有两条未确认的疑点：

- **`LifeConversationDraftEntry.pendingAttachmentCount` / `isPreparing` 在生产恒为 0/false**（`LifeConversationOverview.kt:52-76` 不赋值，`app/src/main/java/com/harnessapk/common/AppContainer.kt:571-575` 也不传），所以 `hasPendingWork` 实际只依赖 `importingCount`，摘要里"正在读取附件"（`LifeConversationOverview.kt:673`）是死分支。当前不出错，但将来新增 PENDING 类附件状态会静默漏判。
- **是否有别的模块把待导入附件写进草稿**（未通读 `QueuedAttachmentStore.kt`，只确认它被注入 `AppContainer.kt:550`）。

## 13. 已排除（查过确认没问题，不要动）

- **归档守卫**：等待处理/生成中/待确认/发送准备中确实都被拦住（`LifeConversationOverview.kt:319-330`、`:445-450`；`ConversationDao.kt:162-177` 还有 DAO 二次校验）。
- **归档的副作用范围**：只把 `isArchived` 置 1，不动 `updatedAt`、id、消息、草稿、`agentId`（`ConversationDao.kt:162-177`）；元数据只写 `undoDeadline`（`LifeConversationMetadataStore.kt:66-71`）。
- **撤销窗口**：5 秒（或无障碍建议时长）限制，归档列表恢复无时限无守卫；窗口过期时页面明确提示"可在归档列表恢复"（`ConversationListScreen.kt:238`）。
- **时间戳不自跳的正常路径**：正常打开/离开会话的重复保存走 `contentUnchanged && priorDraft.updatedAt > 0` 分支（`ConversationDraftStore.kt:170-177`），空草稿是 `commitRemove` 而非写入（`:154-164`），概览本身无轮询、无进入页面写入。
- **简洁模式隐藏模型名**：符合 `docs/qa/2026-09-08-life-chat-reduction.md` 的声明（`ChatScreen.kt:898`）。

## 14. 未决问题

1. **顶栏在方向 C 下具体显示什么？** 新建的空会话标题是"新会话/新问题"，直接显示在"生活"Tab 顶栏是否可接受？还是显示"生活"+ 可展开查看真实标题？
2. **普通模式是否允许继承智能体？** 规格 §1.1 两种读法都成立（"不能继承最近智能体"只写在简洁模式列，但普通模式列要求"明确展示实际身份"）。C-2.2 目前按"允许但必须可见"处理。
3. **F27 的存量空壳要不要追溯清理？** 规格明确不做永久删除；但如果 F04 修好后它们被隐藏，就永远没有清理入口。
4. **F14 导入后是否强制切回生活页？** 会打断"我只想改配置"的用户。

## 15. 建议的修复顺序

1. **P0 四条**（F21 F10 F04 F22）——与方向无关，风险最低，收益最直接。
2. **根因 6 清理**（F13–F19）——纯文案与死代码，可打包成一次提交。
3. **根因 5 + C-2.1**（F10 已在 P0 里，连带 F11 F12）——生活路径彻底不继承 `projectId`。
4. **根因 3**（F07 F08）——需要给 `simpleMode` 一个同步可读的缓存，或统一 `initial` 语义。
5. **根因 1 + C-2.3**（F01 F02 F15）——顶栏可见性 + 主屏指针语义。
6. **根因 4**（F09）——需要决定 `openWorkbench` 在简洁模式下的行为（禁用 / 提示 / 允许但补高亮）。
7. **F23 F24**（输入被吞）——需要重构 `leaveAfterSaving` 的"进行中"语义，建议改成"排队最后一次意图"而不是直接 return。
8. **F25 F26 F28 F29**——逐条小修。

每一步都要跟 `app/src/androidTest` 的既有断言核对，特别是 `TabNavigationTest`（F23 的盲区）和 `LifePanelQuickEntryTest`（它断言了旧首页元素不存在，反向锁定了当前结构）。

## 16. 修复记录（P0）

方向：C（保留"生活页 = 聊天框"，对齐语义）。以下四项与方向无关，已实现。

### F04 空壳判定改用"用户主动保留"信号
- **改动**：`LifeConversationOverview.kt` 的 `hasStoredConfiguration` 移除 `row.agentId != null || row.agentVersion != null`。`defaultProviderId` / `defaultModel` / `prompt*` 保留为配置证据——`ChatRepository.createConversation` 在创建时把它们写成 `null` / `""`，只有用户真的设置过才会非空（已核对创建路径）。
- **保留的兜底**：用户手动改身份会走 `markExplicitConversationChoice` → `markUserRetained`，隐藏条件里的 `!facts.userRetained` 继续保护这类会话。
- **测试**：新增 `LifeConversationOverviewTest.systemSuggestedIdentityDoesNotKeepAnEmptyShellVisible`。

### F10 currentProjectId 残留不再污染生活/知识库入口
- **改动**：`HarnessApkApp.kt` 的 `onUseInNewConversation` 改为 `currentProjectId.takeIf { mainMode == MainMode.WORK }`，与 `AgentPackageImportState.kt:37` 已有的同名守卫保持一致。
- **为什么不动 store**：`currentProjectId` 同时供工作页顶栏标题使用，直接清空会影响工作页；按调用点加守卫是既有先例，改动面最小。
- **遗留**：F11 / F12（被强制切模式时工作页选择丢失、`workbenchTarget` 未清）未在本次范围内。

### F21 配置包 simpleMode 改为三态
- **格式**：`ConfigPackagePayload.simpleMode` 由 `Boolean = false` 改为 `Boolean? = null`；编码器 `payload.simpleMode?.let { put("simpleMode", it) }`（`true` / `false` 都显式写出）；解码器去掉 `?: false`。
- **应用**：`ConfigPackageApplier` 改为 `payload.simpleMode?.let { settingsStore.setSimpleMode(it) }`，`AppliedSummary.simpleModeEnabled: Boolean` 改名 `simpleModeApplied: Boolean?`。
- **告知**：导入预览新增"将关闭生活简洁模式"，完成弹窗新增"生活简洁模式已开启/已关闭"——改动不再是静默的。
- **导出 UI**：单个 Switch 改为"不包含 / 开启 / 关闭"三个 chip，默认跟随导出者自己的设置（修掉 F21(d)"自己没开也给别人开"），用户手动改过之后不再覆盖。
- **测试**：新增 `ConfigPackageCodecTest.simpleModeTriStateSurvivesRoundTrip`（null / true / false 三种都往返一致）。
- **未做**：F13（"点下方 +"文案）与 F14（导入后不回生活页）属根因 6，留给清理批次。

### F22 撤销归档横幅改为浮层
- **改动**：`ConversationListScreen` 的根节点由 `LazyColumn` 改为 `Box`；撤销横幅从列表 item 改为 `Modifier.align(Alignment.BottomCenter)` 的浮层；横幅出现时给列表底部预留 `UndoBannerReservedHeight`（72dp）避免遮住最后一条记录。
- **理由**：横幅原本固定在列表顶端，用户在长列表中部归档时它落在视口上方，5 秒后静默消失——撤销入口等于不存在。改为浮层后与滚动位置无关，也避免了"跳到顶部"这种会丢失阅读位置的替代方案。
- **未做**：连续归档两条时 `undoNotice` 仍是单槽（前一条的撤销入口会被覆盖），归档列表恢复不受影响；如需改为队列可另开一项。

### 验证
- `./gradlew testDebugUnitTest --tests LifeConversationOverviewTest --tests ConfigPackageCodecTest` → BUILD SUCCESSFUL，35 项全通过（含两个新增回归测试）。
- 全量 `testDebugUnitTest` 首次以 `java.io.EOFException` 失败（测试 worker 中途崩溃，已完成的 19 个套件 0 失败）——同一工作树当时有并行构建在跑，判为环境问题，已重跑复核。
- **未做**：`assembleDebug`、仪器测试、真机验证。涉及 UI 结构改动（`ConversationListScreen` 根节点换了）与新增交互（三态 chip），建议在设备上补一次目视核对。

