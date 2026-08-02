# Harness 三 Tab 底部导航设计（双模式首页 v2）

日期：2026-08-02

## 1. 决策状态

本设计把 0.2.3 的双面板 Pager 首页（生活/工作 + 顶部 Tab + 滑动）重构为**底部导航三 Tab：生活 / 工作 / 我的**。0.2.3 的双主题、模式持久化、能力横切原则保留，双模式叙事保留。

本设计确认以下产品关系：

- 底部导航是唯一一级导航；移除顶部模式 Tab 与 HorizontalPager 滑动面板。
- **会话列表不属于任何模式专属**：会话是横切能力。生活 Tab 的会话列表只放普通会话，不出现"按项目分组"等项目概念；项目会话在项目工作台内管理。
- **项目概念只出现在工作 Tab**：工作 Tab = 项目列表 + 项目工作台（会话/文件夹/Git）+ 远程控制入口卡片（保留现状）。
- **"我的" Tab = 设置聚合页**：内嵌现有 `SettingsScreen`（Provider/搜索/语音/Git/技能/智能体包/知识库/更新/远程节点），顶栏设置按钮移除。
- 双主题分配：**生活 = 暖浅；工作、我的 = 深科技**。
- 模式选择持久化：启动恢复上次 Tab；旧值迁移（LIFE→LIFE、WORK→WORK）。
- 本次改动边界：只动 UI 导航层与主题映射，能力层（仓库、引擎、服务）零改动。

## 2. 问题定义

0.2.3 双面板 Pager 首页在真机使用中被判定为**层级过多**：

- 顶部模式 Tab + 滑动面板 + 面板内次级 Tab（会话列表的"最近会话/按项目分组"、项目工作台三 Tab）叠了三层导航。
- "生活"面板内出现"按项目分组"视图，把项目概念注入了会话列表，与"项目属于工作"的认知冲突。
- "会话列表只有生活才有"的隐含假设不成立——项目会话同样是会话，会话是横切能力。
- 设置入口在顶栏图标，与"我的"聚合页概念割裂。

## 3. 产品目标

1. 一级导航为底部三 Tab：生活 / 工作 / 我的，切换一次点按，无嵌套层级。
2. 生活 Tab = 普通会话列表 + 智能体/知识库快捷入口 + 新建会话；无项目概念。
3. 工作 Tab = 远程控制入口卡片（配对后可见）+ 项目列表 + 项目工作台。
4. 我的 Tab = 设置聚合页（内嵌现有 SettingsScreen），顶栏设置按钮移除。
5. 双主题：生活=暖浅，工作/我的=深科技，300ms 过渡保留。
6. 启动恢复上次 Tab；进程内状态保留。

### 成功标准

- 从任意 Tab 一次点按到达另一 Tab；页面内不再出现模式切换控件。
- 会话列表不含任何项目分组/项目标识概念。
- 顶栏只承载上下文操作（新建、搜索、设置类的当前页操作），不再承载导航。
- 现有能力（项目工作台、Wiki、智能体、远程控制、自更新）全部可用，无功能回归。

## 4. 非目标

- 不保留顶部模式 Tab / 滑动面板（Pager 移除）。
- 不做"我的"页的个性化内容（头像、账号信息），仅设置聚合。
- 不改动 Chat、Wiki、Settings 子页面结构（SettingsScreen 本身只改挂载方式）。
- 不新增主题套数（仍两套：暖浅/深科技）。
- 不做会话列表的分组视图（"按项目分组"移除，不换形式回归）。

## 5. 设计

### 5.1 导航结构

```
底部导航： 生活 | 工作 | 我的        （Scaffold bottomBar，Material3 NavigationBar）
   │         │      │
   │         │      └─ 设置聚合：内嵌 SettingsScreen
   │         └── Column: [远程入口卡片?] + ProjectScreen（项目列表→工作台）
   └── ConversationListScreen（普通会话 + 快捷入口 + FAB）
```

- `MainMode` 重定义为 `LIFE / WORK / ME`（`HomeUiState.kt`），辅助函数同步：
  - `homePrimaryAction`：LIFE→CREATE_CONVERSATION；WORK/ME→NONE。
  - `topLevelTitle`：生活 / 工作（带项目名）/ 我的。
- 首页（`Routes.Conversations`）内容区按 `when (mainMode)` 渲染三个 Tab 内容；`HorizontalPager`、`pagerState` 及相关同步 LaunchedEffect 全部移除。
- 顶栏：移除 `ModeSwitcher`（模式切换由底部导航承担）；移除设置按钮（设置入口在"我的"）。

### 5.2 生活 Tab

- 现有 `ConversationListScreen`，**移除"按项目分组"全部逻辑**（`groupedByProject` 状态、`ConversationListHeader`、`ProjectConversationGroupHeader`、`ProjectSessionsToggleRow`、分组构建函数），仅保留普通会话列表。
- 智能体/知识库快捷入口行保留；新建会话 FAB 保留。

### 5.3 工作 Tab

- 现状保留：`Column`（顶部 inset 消费）内 `RemoteEntryCard`（profile 非空显示，点击进 `Routes.RemoteControl`）+ `ProjectScreen(modifier = weight(1f))`。
- 项目工作台（会话/文件夹/Git）不变。

### 5.4 我的 Tab

- 内嵌 `SettingsScreen(container 所需回调 + showUpdateBadge)`，导航回调指向既有 route（Providers/Search/Voice/Git/Skills/AgentPackages/WikiLibrary/Updates/RemoteSettings）。
- 顶栏设置按钮与 `Routes.Settings` route 移除（SettingsScreen 不再作为独立页）。

### 5.5 主题映射

`ModeTheme`（`Theme.kt`）映射更新：

| 模式 | ColorScheme | Shapes |
|---|---|---|
| LIFE | warmLight | HarnessShapes |
| WORK | techDark | TechShapes |
| ME | techDark | TechShapes |

300ms 颜色过渡（`animateColorScheme`）保留。

### 5.6 持久化

- `HomeModeStore` 存储三态：`save(LIFE/WORK/ME)`；`migrateStoredMode` 更新：
  - "LIFE"→LIFE；"WORK"→WORK；"ME"→ME；null/未知→LIFE。
- 启动恢复：`rememberSaveable` + store 初值（沿用现有接线，仅去掉 pager 相关）。

## 6. 技术落地清单

| 文件 | 改动 |
|---|---|
| `ui/HomeUiState.kt` | MainMode 加 ME；homePrimaryAction/topLevelTitle 同步 |
| `ui/HomeModeStore.kt` | migrateStoredMode 加 "ME"→ME |
| `ui/theme/Theme.kt` | ModeTheme 映射 ME→techDark+TechShapes |
| `ui/HarnessApkApp.kt` | 首页换底部 NavigationBar 三 Tab；移除 Pager/顶部 Tab/设置按钮/Routes.Settings；我的 Tab 内嵌 SettingsScreen |
| `ui/conversation/ConversationListScreen.kt` | 移除按项目分组全部逻辑 |
| `ui/settings/SettingsScreen.kt` | 仅调整挂载方式（如需要：去掉仅被 route 使用的东西），主体不动 |
| 测试 | HomeModeUiStateTest、HomeModeStoreTest、DualModeHomePagerTest（重写为 Tab 导航测试）、HarnessApkAppStateTest 源码断言、LifePanelQuickEntryTest/WorkPanelRemoteEntryTest 适配 |

不动：ChatScreen、Wiki 全部、ProjectScreen 主体（仅保持现状挂载）、RemoteScreen、能力层全部。

## 7. 测试与验收

- 单测：MainMode 三态、迁移（含 ME）、topLevelTitle/homePrimaryAction。
- Compose 测试：底部导航三 Tab 切换、生活 Tab 无项目分组、我的 Tab 显示设置项、主题断言（LIFE 暖浅 / WORK、ME 深科技）。
- 回归：全量 JVM；androidTest 重写/适配后在设备补跑。
- 真机验收：
  1. 底部三 Tab 一次点按切换；无顶部 Tab/滑动残留。
  2. 生活 Tab 会话列表无项目分组；项目会话只能在项目工作台看到。
  3. 工作 Tab 深科技、我的 Tab 深科技、生活 Tab 暖浅。
  4. 重启恢复上次 Tab；从 0.2.3 升级（旧 LIFE/WORK 持久化值）不崩溃、Tab 正确。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 移除 Pager 后状态恢复 | rememberSaveable 保留；去 Pager 同步逻辑后 mainMode 单一来源 |
| 会话列表去分组影响既有用户 | 项目会话仍在项目工作台可访问；会话数据不迁移不删除 |
| SettingsScreen 内嵌后 route 移除 | 检查无残留导航到 Routes.Settings 的入口 |
| 双主题映射改 ME | ThemeTest 补 ME→techDark 断言 |
