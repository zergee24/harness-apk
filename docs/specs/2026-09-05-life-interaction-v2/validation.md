# 生活交互 V2 · 实现与验证报告

2026-09-06。结论：生活首页和聊天 V2 已完成本轮实现，最终调试包构建成功，1,312 条单元测试与 39 条精选真机仪器测试通过，真实问答/草稿恢复/归档主链已走查。下述证据不代表 A01–A50 的全部设备、格式与辅助技术组合均已执行。

## 1. 源码与交付边界

| 项目 | 已核对结果 |
| --- | --- |
| 仓库 | harness-apk；Kotlin + Jetpack Compose + Room |
| 隔离分支 | `codex/life-interaction-experience` |
| 最终实现提交 | `bc46e77b30b5e4291cffb2172901184176ed3de3`，优化生活首页与聊天交互并完善草稿发送恢复 |
| 草稿底座提交 | `20387ca4ea9d6b851faa1e4e04ab736f92be8076`；测试适配 `ee7d5a2cd4cdcff7444b5ce69a94311f5766af23` |
| 附件基线 | `b7864e19d525d49ae1dff7157f6385597b2adf5d` |
| 根 test 核对 | `aef65adc6722710004ebd5f9dcb5074f5cc6a61f`；没有自动合入本地分支 |
| 远端状态 | 本分支没有 upstream；本轮未推送、合并或发布 |
| Room | version 26；无表结构变化或破坏性重建 |
| APK | `harness-life-v2-bc46e77-debug.apk`；包名 `com.harnessapk.debug`；0.5.0-debug / 2000000 |
| 本地与已安装 APK SHA-256 | `d91135b440f2162fca0918a25d2148e5a5320f4d7b4dedfa17a157c44a26e622`；通过设备 `pm path` 返回的真实 base.apk 计算，完全一致 |

源码提交由验证过的候选源码原样提交生成；提交操作没有改动 app/build。随后只更新本目录的说明和截图。APK 不包含此报告中的机器路径或测试模型密钥。

APK 保存在仓库根 `out/life-ux-2026-09-05/harness-life-v2-bc46e77-debug.apk`，与文档同名交付目录中的 provenance JSON 一起核对。文档 ZIP 是完整规格与示意包，不包含源码实现全集或 APK；源码可从上述本地分支取得。

## 2. 实现内容

1. 生活概览使用批量 Room 查询结合草稿、发送恢复和来源元数据，显示可读标题、摘要、草稿及实际执行状态。取消相机/语音后未产生内容的新入口记录不显示为垃圾历史，用户主动命名与旧记录保留。
2. 简洁聊天只保留返回、标题、更多；技术设置进入次级入口。附件、说话、发送始终保留，窄屏大字可换行。文档使用文件卡片与折叠正文；本地追问仅回填草稿，已有文字需确认替换。
3. 草稿持久保存原始文字与 typed 图片/文档，使用应用私有副本和稳定 ID、内容 hash、URI 版本。读取失败、导入失败或附件失效有具体恢复入口；离开时等待保存，失败可复制/重试/留在页面或明确放弃。
4. 发送前先持久化意图，再调用既有幂等队列。UNKNOWN 用原 requestId 自动/手动单飞重查，采用 1/2/4/8/15 秒最多 5 次退避。查询耗时不等于失败，持久化终态失败会保留门禁并可重新确认。已落地内容从首页草稿投影清除，后来写入的下一条内容保留。
5. 语音新增 REVIEW 确认编辑；确认后追加一次到草稿，仍需明确发送。录音与整理均可取消，迟到回调不能回填。切后台取消活动采集/整理，已完成的 REVIEW 保留；明确离开聊天取消该次流程。录音前停止本应用朗读。
6. 归档先保存撤销期限，再由 SQL 和发送 guard 共同检查是否可归档；支持即时撤销和长期恢复。读取 Error 保留缓存，恢复成功会重新订阅，即使先前源已读取失败也能更新列表。
7. 暖色主色从浅珊瑚调整为更深的棕红，正文与按钮对比达到 4.5:1 门槛；普通/深色主题既有行为保留。简洁新建等待实际设置加载并使用 Assistant 身份，异步新建完成时核对原路由与模式。

## 3. 自动化验证

构建命令在隔离工作树执行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --console=plain
git diff --check
```

最终结果：BUILD SUCCESSFUL；172 个单元测试类，共 **1,312 tests，0 failures，0 errors，0 skipped**。instrumentation APK 构建成功。已有 Gradle 弃用提示不影响当前构建，本轮未升级构建工具链。

设备上对以下 8 个类执行 `am instrument -w -r -e class ... com.harnessapk.debug.test/androidx.test.runner.AndroidJUnitRunner`：

- `com.harnessapk.chat.ConversationDraftStoreInstrumentedTest`
- `com.harnessapk.chat.ChatSendRecoveryPersistenceInstrumentedTest`
- `com.harnessapk.storage.LifeConversationMetadataStoreInstrumentedTest`
- `com.harnessapk.storage.LifeConversationOverviewDaoInstrumentedTest`
- `com.harnessapk.ui.chat.LifeChatComposerTest`
- `com.harnessapk.ui.chat.LifeDraftPresentationInstrumentedTest`
- `com.harnessapk.ui.conversation.LifeOverviewErrorStateTest`
- `com.harnessapk.chat.ChatExecutionRepositoryInstrumentedTest`

最终结果：**OK (39 tests)，16.907 秒**。这些测试使用独立 fixture、临时私有文件或 in-memory Room；没有清空用户数据库。测试覆盖完整组合输入栏，不是把按钮拆开测试；包含 320dp、2 倍字、280dp 可用高度、Enter 换行与 Ctrl+Enter 一次发送。

故障回归包括：元数据 commit=false 不发布新状态；归档 Room 失败且清理元数据也失败时不承诺成功、不能误恢复 active 行；读取 Error + 缓存归档行恢复后重订阅；IN_FLIGHT/UNKNOWN 与归档互斥；已归档会话拒绝插入 USER；持久 journal 重建及终态落盘异常的原 ID 重查；LANDED 只清本轮附件；新草稿不会被后台结果覆盖。

随包保留 [机器可读测试摘要](evidence/test-summary.json)。日志在仓库根 `out/life-ux-2026-09-05/v2-final-build-test.log`、`v2-final-device-tests.log`，测试 XML 在工作树 `app/build/test-results/testDebugUnitTest/`。源码中的回归 fixture 可复跑，无需真实账号。

## 4. 设备主链证据

设备为本轮重新确认已授权的 HiBreak；调试前设置并核对 USB stay-on=2。同签名覆盖安装，没有清除应用数据；原模型设置与既有历史仍能使用。

| 实际操作 | 观察到的结果 |
| --- | --- |
| 输入 `1+3=?`，加入 TXT | 附件入口一直可用；发送得到真实回复 `4` |
| 含文字与 TXT 草稿强制停止后重新打开 | 原文字和结构化文件项恢复 |
| 导入 TXT 后删除外部源文件，再发送/展开 | 应用私有副本仍可用；文件正文可展开读取 |
| 生成期间准备下一条 | 后续草稿保留，未自动发送；走查后已清理本轮临时文字 |
| TXT-only 发送后立即返回首页 | 真实回复 `427`；首页显示文件名、答案摘要与完成状态，没有错误的草稿标签 |
| 归档 → 五秒内撤销 → 再归档 → 归档列表恢复 | 原会话可找回、内容保持，恢复有明确反馈 |
| 新建拍照/说话，拒绝本次权限后返回 | 能改用相册/打字，返回后无新增可见空壳 |
| 320dp、2 倍字、IME 开/关 | 输入/附件/语音/发送与返回可达，工具栏换行；系统输入法自身窄屏裁切单列为环境边界 |
| 最终包展开文档、返回首页、恢复普通模式与进入工作 | 实际数据可读，导航与原模式入口可用 |

最终实测截图与各自窗口参数见 [evidence/README.md](evidence/README.md)。HappyCodeAI 使用用户此前授权配置的现有连接完成问答，本轮报告不复制任何 API key。

## 5. 仍未完整验证的组合

- 没有录制真实语音、采集真实照片或验证云识别准确率；语音/TTS 的真实引擎、播放与网络失败组合仍需现场专项走查。
- 真实发送使用 TXT；PDF/扫描 PDF/Word/Excel/多图片混合及不同系统 picker 的全部组合未逐一跑设备。
- UNKNOWN 的重启恢复由管理器与持久存储 fixture 验证；未在真实未知入队时强杀系统进程。所有历史版本分别升级、500 条历史性能也未逐一验证。
- 完成组合输入栏压力与代表页面检查，未宣称 18 状态×三种宽度×全部字号/主题×TalkBack 全矩阵通过。
- 普通模式/工作入口可达与既有单元回归通过，不等于所有 Wiki、工具、智能体和工作任务均完成真实执行。
- 当前交付为本地调试 APK；没有远端合并、发布签名或上线结论。

设备参数恢复：原生 1264×1680，无 wm size override；density 300 无 override；font_scale=1.2；原拼音 IME；simpleMode=false。摄像头/麦克风仍未授予，测试产生的拒绝标志已清理；USB 供电常亮保持规定值 2。仅删除本轮创建的外部 TXT/临时 UI XML，保留用户应用数据与问答历史。

## 6. 文档与示意检查

- README、产品规格、聊天状态、代码交接、A01–A50 用例和本报告相互链接。
- HTML 内含 H01–H04、C01–C14 共 18 状态，内联 JavaScript 语法通过，不依赖外部脚本/样式；提供宽度、字号、主题切换。
- 设计示意与真实 APK 截图分别标注；示例按钮不伪装模型结果或操作真实数据。
- 基线 format-patch 对声明父提交的临时副本通过 git apply --check；上下文行空格按补丁数据保存，目录属性排除其文本空白误报。
- 文档和补丁使用凭证模式扫描，没有发现 API key 或私钥；ZIP 完整性与内部文件链接在交付前核对。
