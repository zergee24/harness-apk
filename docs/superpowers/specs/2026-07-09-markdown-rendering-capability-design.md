# Markdown Rendering Capability Design

## 背景

Harness APK 的聊天、项目预览、PDF 导出和 Markdown 文件变更审核都依赖 Markdown 渲染。用户已经反馈多种格式不兼容，包括嵌套列表、代码块、标题行距、复杂表格，以及长流式回复期间渲染卡死。

RikkaHub README 明确把 Markdown 渲染能力列为核心特性，并支持代码高亮、数学公式、表格和 Mermaid。RikkaHub 2.4.1 release 仍在修复化学公式显示问题，说明 Markdown 能力不是一次性组件，而是需要持续维护的渲染子系统。

参考资料：

- RikkaHub README Markdown 能力，https://github.com/rikkahub/rikkahub/blob/master/README_ZH_CN.md
- RikkaHub 2.4.1 release 公式修复，https://github.com/rikkahub/rikkahub/releases/tag/2.4.1

## 当前状态

当前代码的关键入口：

- `MarkdownMessageParser` 使用 commonmark parser 和 GFM tables extension。
- `MarkdownBlock` 已支持 heading、paragraph、bullet list、ordered list、quote、code、table、divider。
- `normalizeModelMarkdown()` 会修复部分模型输出中的紧凑标题和紧凑 fence。
- `MarkdownMessage` 使用 Compose 原生 Text/Surface/Row/Column 渲染。
- `IncrementalMarkdownBlockCache` 会把稳定前缀切块，只解析尾部。
- `MarkdownPdfDocument` 复用同一 parser 输出 PDF 行。

缺口：

- 没有代码语法高亮。
- 没有数学公式解析和渲染。
- 没有 Mermaid 图渲染。
- 列表嵌套和列表内代码/引用/表格的视觉层级仍弱。
- 表格缺少列宽、复制和极宽内容降级策略。
- 标题、段落、列表之间的垂直节奏还不稳定。
- 流式期间未闭合 Markdown 结构容易影响尾部渲染。

## 目标

1. Markdown 格式兼容
   正确展示常见 LLM 输出：标题、段落、强调、链接、行内代码、代码块、表格、引用、有序/无序/嵌套列表、任务列表、分隔线。

2. 代码块可用
   代码块显示语言、复制按钮、横向滚动、合理行距，并对常用语言提供语法高亮。

3. 数学公式可读
   支持 `$inline$`、`$$block$$`、`\(...\)`、`\[...\]`，优先保证公式可读和不崩溃。

4. Mermaid 后置但架构预留
   Mermaid fenced code 先作为可识别图表块，第一阶段可展示为代码块+“图表预览”占位，第二阶段再接本地 WebView 渲染。

5. 流式安全
   流式中未闭合代码块、未闭合表格、半截公式不能导致整条消息反复重排或崩溃。

6. 多场景复用
   聊天、项目预览、diff 审核、PDF 导出使用同一 parser AST，不同 renderer 可按场景取舍。

## 非目标

- 不支持任意 HTML 执行。HTML 默认作为文本展示或安全忽略。
- 不在第一阶段实现完整 GitHub Markdown 100% 兼容。
- 不在聊天气泡里渲染超大 Mermaid 或超宽表格为完整图片。
- 不为每种编程语言做精确 IDE 级高亮。

## Markdown 方言矩阵

### 第一阶段必须支持

| 能力 | 输入示例 | 展示要求 |
|---|---|---|
| 标题 | `#` 到 `######` | 字号和行距分级，中文标题不挤压 |
| 段落 | 普通文本 | 中文行距稳定，不贴边 |
| 粗体/斜体 | `**x**`、`*x*` | 支持嵌套 inline |
| 行内代码 | `` `x` `` | 等宽、浅底色 |
| 链接 | `[text](url)` | 下划线或高亮，点击可后续接系统打开 |
| 引用 | `> text` | 左侧竖线，嵌套内容缩进 |
| 无序列表 | `-`、`*`、`+` | 支持嵌套层级 |
| 有序列表 | `1.`、`1)` | 保留起始序号 |
| 任务列表 | `- [ ]`、`- [x]` | 只读 checkbox |
| 代码块 | fenced / indented | 语言、复制、横滚、高亮 |
| 表格 | GFM table | 横滚、表头突出、单元格换行 |
| 分隔线 | `---` | 与上下内容有间距 |

### 第二阶段支持

| 能力 | 展示要求 |
|---|---|
| 数学公式 | local KaTeX 渲染，失败回退源码 |
| Mermaid | 本地 WebView 渲染，默认按需展开 |
| 脚注 | 项目预览支持，聊天可简化为尾注 |
| 删除线 | GFM strikethrough |
| 自动链接 | URL 自动识别 |

### 明确降级

| 能力 | 降级策略 |
|---|---|
| HTML block | 作为纯文本或安全移除 |
| iframe/script | 禁止执行 |
| 过大表格 | 截断预览，提供“查看完整内容” |
| 过大代码块 | 默认展示前 200 行，复制仍复制完整代码 |
| Mermaid 渲染失败 | 回退代码块并显示错误摘要 |

## 架构设计

### Parser Core

新增稳定 AST 层：

```kotlin
data class MarkdownDocument(
    val blocks: List<MarkdownBlock>,
    val diagnostics: List<MarkdownDiagnostic>,
)

data class MarkdownDiagnostic(
    val level: WARNING | ERROR,
    val message: String,
    val sourceRange: IntRange?,
)
```

现有 `parseMarkdownBlocks(markdown)` 保留为兼容 API，但内部改为：

1. `MarkdownNormalizer.normalize(raw, mode)`
2. `MarkdownParser.parse(normalized)`
3. `MarkdownPostProcessor.apply(document)`
4. 返回 blocks 或 document。

### Normalizer

Normalizer 只做可解释、可测试的修复：

- `##标题` -> `## 标题`
- `正文###标题` -> 换行后标题
- 单行紧凑代码 fence 拆为多行。
- 流式期间未闭合 fence 只在 visual mode 临时补闭合，不写入持久化内容。
- 表格前后缺空行时补空行。

禁止：

- 猜测性重写用户内容。
- 删除模型输出。
- 在 final mode 中为了显示而改变代码块正文。

### Renderer Registry

按 block 类型注册 renderer：

- `ParagraphRenderer`
- `HeadingRenderer`
- `ListRenderer`
- `TaskListRenderer`
- `QuoteRenderer`
- `CodeBlockRenderer`
- `TableRenderer`
- `MathRenderer`
- `MermaidRenderer`
- `FallbackRenderer`

聊天、项目预览、PDF 导出可以使用不同 registry：

- Chat registry：轻量、可滚动、避免超大内容。
- Project registry：更完整，允许展开大块。
- Pdf registry：转为 PDF line/table/image。

### Incremental Parsing

保留 `IncrementalMarkdownBlockCache` 思路，但切块依据从“字符串边界”升级为“稳定 block 边界”：

- stable block 的 key = block type + source hash + source range。
- tail chunk 最大默认 1,200 字符。
- 遇到未闭合 fence、math、table 时，未闭合部分留在 tail，不把前文稳定块拉回重算。
- 流式期间每次只解析 tail 和新增 block。

## 视觉与交互规范

### 标题

- H1：20sp，lineHeight 28sp，top margin 10dp，bottom margin 6dp。
- H2：18sp，lineHeight 26sp，top margin 8dp，bottom margin 5dp。
- H3-H6：16sp，lineHeight 24sp，top margin 6dp，bottom margin 4dp。
- 聊天气泡内不使用过大的 hero 字号，避免一屏只能看几行。

### 段落

- 中文 body：15-16sp，lineHeight 23-25sp。
- 段间距 8dp。
- 同一 list item 内段落间距缩小到 4dp。

### 列表

- 每层缩进 18dp，不超过 4 层视觉缩进；更深层保持同宽但 marker 变化。
- marker 固定宽度，避免多位数字导致正文抖动。
- 列表内子 block 和父文本保持 6dp 间距。
- 任务列表 checkbox 只读，点击不改变 Markdown 原文。

### 代码块

头部：

- 左侧语言标签。
- 右侧复制按钮。
- 如果内容被截断，显示“已折叠，点开查看”。

主体：

- 等宽字体 13sp。
- lineHeight 19sp。
- 横向滚动。
- 深浅主题均保证对比度。
- 长行不强制换行，避免代码结构变形。

复制：

- 复制按钮复制完整原文，不复制行号。
- 长按代码区仍允许系统选择。

### 表格

- 表格整体横向滚动。
- 单元格最小宽 96dp，最大宽 260dp。
- 表头加粗。
- 单元格内支持 inline markdown。
- 超过 40 行时聊天内默认展示前 20 行和后 5 行，中间折叠；项目预览可展开完整表格。

### 数学公式

识别：

- Inline：`$...$`、`\(...\)`
- Block：`$$...$$`、`\[...\]`

渲染：

- 第一优先：本地 KaTeX assets + isolated WebView/bitmap cache。
- 第二优先：纯文本 fallback，保留原公式。
- 公式 block 可横向滚动。

缓存：

- cache key = formula source hash + theme + text size。
- 渲染失败记录 diagnostic，不阻塞整条消息。

### Mermaid

识别 fenced code：

````text
```mermaid
graph TD
...
```
````

第一阶段：

- 展示为带 `mermaid` 标签的代码块。
- 显示“图表预览暂不可用”或“点开预览”占位，具体文案保持轻量。

第二阶段：

- 使用本地 `mermaid.min.js`。
- 在 isolated WebView 中渲染。
- 默认按需展开，避免聊天列表初始化大量 WebView。
- 失败回退代码块。

## 代码高亮设计

### Highlighter 接口

```kotlin
interface SyntaxHighlighter {
    fun highlight(code: String, language: String?): HighlightedCode
}

data class HighlightedCode(
    val lines: List<HighlightedLine>,
    val diagnostics: List<MarkdownDiagnostic>,
)
```

### 第一阶段语言

- `json`
- `kotlin`
- `java`
- `python`
- `bash` / `shell` / `sh`
- `javascript` / `typescript`
- `xml` / `html`
- `css`
- `sql`
- `markdown`
- `text` fallback

### 实现策略

第一阶段采用轻量规则高亮：

- keyword
- string
- number
- comment
- punctuation
- function-like identifier

不做完整 AST，不追求 IDE 精准。目标是提升可读性且不引入大体积依赖。

第二阶段如果需要更高质量，再评估引入独立 highlight module 或成熟库。

## 安全策略

- Markdown 渲染不执行远程脚本。
- Mermaid 和 KaTeX 只使用本地 assets。
- WebView 禁止 file access、禁止 mixed content、禁止任意 URL navigation。
- 链接点击必须经系统 intent 或内部确认，不在 WebView 中直接打开。
- 诊断日志不记录完整敏感内容。

## 性能策略

- 聊天气泡内每条消息最多一个 active tail 参与高频重绘。
- 大代码块和大表格默认折叠，避免一次性 compose 数千个 Text。
- 高亮结果按 code hash 缓存。
- 数学和 Mermaid 渲染按 hash 缓存。
- 项目预览可以比聊天渲染更完整，但仍要分页或懒加载。

性能预算：

- 15k Markdown 流式期间 UI 无明显卡死。
- 单次 tail parse 目标小于 20ms。
- 单个代码块超过 200 行时默认折叠。
- 单个表格超过 40 行时聊天内折叠。

## 测试语料

新增 `app/src/test/resources/markdown/`：

- `nested_lists.md`
- `code_fences.md`
- `compact_model_markdown.md`
- `tables_wide.md`
- `math_chemistry.md`
- `mermaid_blocks.md`
- `long_git_usage.md`
- `streaming_unclosed_fence_steps.txt`
- `chinese_headings_spacing.md`

每个语料必须覆盖：

- parser 输出 blocks。
- renderer 不崩溃。
- plain text copy 结果。
- incremental cache 的 stable chunk 数量和 tail 长度。

## 测试策略

### 单元测试

- `MarkdownNormalizerTest`
  - 紧凑标题。
  - 紧凑 fence。
  - 表格前后空行。
  - visual mode 未闭合 fence。

- `MarkdownParserTest`
  - 嵌套列表。
  - 列表内代码块。
  - 表格。
  - math block/inline 识别。
  - mermaid block 识别。

- `IncrementalMarkdownBlockCacheTest`
  - stable block 不重复 parse。
  - 未闭合结构留在 tail。
  - 超长文本稳定切块。

- `SyntaxHighlighterTest`
  - 常用语言 token 分类。
  - unknown language fallback。
  - 超大代码块不会超时。

### Compose 测试

- 长 Markdown 气泡可以滚动。
- 代码块复制按钮存在。
- 表格横向滚动。
- 嵌套列表缩进正确。
- H1/H2/H3 行距合理。
- 暗色模式对比度可读。

### Emulator QA

固定脚本：

1. 安装 debug APK。
2. 导入测试 Provider 或 mock stream。
3. 打开 markdown regression 会话。
4. 注入长文本流式样例。
5. 截图检查标题、列表、代码块、表格和底部输入栏。
6. 快速滚动并复制代码块。

## 验收标准

1. 用户提供的 Git 使用指南 Markdown 能正确分段展示，不再把标题、代码 fence 和列表挤在一行。
2. 嵌套列表至少支持 4 层且层级可辨认。
3. 代码块有语言标签、复制按钮、横向滚动和基础高亮。
4. 表格在聊天内不撑破气泡，宽表可横向滚动。
5. 数学公式输入不会破坏整条消息，渲染失败时回退为源码。
6. Mermaid fenced block 被识别，第一阶段至少不会当普通段落打散。
7. 长流式 Markdown 只重绘尾部，不因前文稳定内容持续重排。
8. 所有 markdown regression 语料有单元测试。

## 实施顺序

1. 抽出 `MarkdownDocument` 和 diagnostics，保留旧 `parseMarkdownBlocks` API。
2. 增强 normalizer 和 parser，补任务列表、math、mermaid block 类型。
3. 重做列表、标题、表格和代码块视觉节奏。
4. 增加代码块复制和轻量语法高亮。
5. 增加 streaming visual mode，处理未闭合 fence/math/table。
6. 加入 markdown regression corpus。
7. 实现 KaTeX 本地公式渲染。
8. Mermaid 作为第二阶段按需 WebView 渲染。

## 风险与取舍

- KaTeX 和 Mermaid 会增加 APK 体积：通过本地 assets 按需加载控制影响。
- WebView 在聊天列表里代价高：Mermaid 默认不直接渲染，必须按需展开。
- 规则高亮不如专业库精准：第一阶段以不卡顿和可读性为优先。
- Normalizer 可能误伤原文：所有修正必须有测试，并区分 visual/final mode。
