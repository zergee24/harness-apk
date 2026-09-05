# 现场证据来源与边界

这些截图来自 2026-09-05 的 HiBreak 走查，均为本任务已检查过的无密钥页面。它们是问题背景，不是 V2 设计稿，也不是 V2 验收通过证明。

| 文件 | 来源 | 能证明什么 |
| --- | --- | --- |
| before-life-empty.png | 本轮开始时手机上的旧调试包，0.5.0-debug/2000000 | 普通生活首页首用空白、入口方向不清；不能替代最新源码验证 |
| before-chat.png | 同一旧调试包的未配置聊天页 | 高级词汇和空态占据首屏，未配置发送缺少直接出口 |
| baseline-chat-320dp.png | 本轮附件修复候选包，600px/300dpi=320dp，字号1.3，IME开启 | 输入/附件/发送可达；长文档标题撑高顶栏，技术上下文仍占空间 |

上一轮最终附件修复源码：b7864e19d525d49ae1dff7157f6385597b2adf5d。最终本地 APK SHA-256：ae84ed1c9e3c7a1d846245470d77b6dbfa5574986782e48e3dd7bd656f6cfc96。主链与窄屏截图使用此前候选包；最后 helper 修正后重跑相关测试并对最终包完成真实问答冒烟。

此前已验证文字+TXT、TXT-only 真实发送与普通草稿返回恢复；未据此宣称 PDF/Word/Excel、真实录音/拍照、TalkBack、2倍字或卓易通全部通过。以上仅为改造前基线结论；V2 的持久化与恢复实现及测试见 ../validation.md。

提供给其他 agent 的例子只用于复现布局/流程。无需原用户模型配置，不得从对话、设备文件或其他目录搜寻凭证。


## V2 最终 APK 实测截图

以下来自 2026-09-06 最终代码 `bc46e77b30b5e4291cffb2172901184176ed3de3` 对应安装包，截图为原始设备输出，没有合成页面或填入假回复。安装包摘要与交付 APK 一致，详见 [验证报告](../validation.md)。

| 文件 | 当时环境 | 证据 |
| --- | --- | --- |
| [life-home.png](implemented/life-home.png) | HiBreak 原生 1264×1680、density 300、字号 1.2、简洁模式 | 三入口；真实 TXT-only 与文字问答的标题/摘要/完成状态 |
| [chat-file-answer.png](implemented/chat-file-answer.png) | 同上 | 文档卡片、真实答案 427、复制/追问与组合输入栏 |
| [chat-file-expanded.png](implemented/chat-file-expanded.png) | 同上 | 展开应用保存的 TXT 正文；正文与模型实际回复相符 |
| [chat-320dp-2x.png](implemented/chat-320dp-2x.png) | wm size 600×1280、density 300，即 320dp；字号 2.0；IME 关闭 | 两行输入操作仍可达，内容区可滚动 |
| [chat-320dp-2x-ime.png](implemented/chat-320dp-2x-ime.png) | 同一强制窗口；IME 开启 | 附件/语音/发送位于键盘上方，无遮盖；第三方拼音键盘本身在强制窄屏时裁切，未将其算为 APK 布局问题 |

窄屏截图是同一实体设备改变逻辑窗口后的压力走查，不代表另一款真实 320dp 手机。检查结束已恢复原生尺寸、字号 1.2、原输入法与普通模式。没有采集真实家庭录音或照片；截图中的 TXT 是本轮公开内容测试文件。
