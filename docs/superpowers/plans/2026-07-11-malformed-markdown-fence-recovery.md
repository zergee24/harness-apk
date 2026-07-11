# 异常 Markdown 围栏恢复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复异常 LLM Markdown 围栏吞掉后续正文的问题，并建立长文本和混合 Markdown 回归矩阵。

**Architecture:** 新增一个与 CommonMark 规则对齐的轻量围栏扫描器，由展示前归一化器和流式稳定块检测共同使用。归一化器只恢复行尾关闭围栏，以及空行后进入明确 Markdown 边界的未闭合 `text` 块；正常语言代码块保持原样。

**Tech Stack:** Kotlin 2.3、Android Compose、CommonMark 0.25.1、JUnit 4、Compose UI Test、Gradle 9.6.1。

## Global Constraints

- 不改写 Room 中持久化的消息原文，只修正展示前解析输入。
- 正常代码块保持等宽字体和横向滚动，不强制换行。
- 不把生成语料发往真实 LLM Provider，也不写入生产会话数据库。
- 不修改网络中断错误的展示和复制逻辑。
- 不引入新的第三方依赖。

## 文件结构

- 新建 `app/src/main/java/com/harnessapk/ui/markdown/MarkdownFenceScanner.kt`：统一解析打开围栏、合法关闭围栏、行尾关闭围栏和文本围栏属性。
- 修改 `app/src/main/java/com/harnessapk/ui/markdown/MarkdownMessageParser.kt`：使用扫描器执行保守恢复。
- 修改 `app/src/main/java/com/harnessapk/ui/markdown/IncrementalMarkdownBlockCache.kt`：复用扫描器判断流式未闭合围栏。
- 修改 `app/src/test/java/com/harnessapk/ui/markdown/MarkdownMessageParserTest.kt`：添加截图、反例、长文本和组合回归测试。
- 新建 `app/src/test/resources/markdown/malformed_fences_long.md`：固定的损坏围栏长语料。
- 修改 `app/src/androidTest/java/com/harnessapk/ui/chat/MarkdownMessageTest.kt`：验证异常标记不泄漏到 Compose 文本节点。

---

### Task 1: 用失败测试锁定截图根因

**Files:**
- Test: `app/src/test/java/com/harnessapk/ui/markdown/MarkdownMessageParserTest.kt`
- Create: `app/src/test/resources/markdown/malformed_fences_long.md`

**Interfaces:**
- Consumes: `parseMarkdownBlocks(markdown: String): List<MarkdownBlock>`。
- Produces: 两个失败回归测试和一份可复用语料。

- [ ] **Step 1: 添加截图语料失败测试**

```kotlin
@Test
fun recoversTrailingAndUnclosedTextFencesFromModelOutput() {
    val blocks = parseMarkdownBlocks(
        """
        ```text左侧16:18:
        上：代码下：Codex / Terminal```

        如果 Codex 需要长时间跑任务，也可以临时变成竖屏。

        ```text左侧16:18:
        上半：Codex 下半：Terminal / 测试 / 日志
        然后代码临时放右下 24 寸。

        ---

        ## 人体工学上要注意最重要的一点：

        > **你身体应该正对主屏**
        """.trimIndent(),
    )

    assertEquals(2, blocks.filterIsInstance<MarkdownBlock.Code>().size)
    assertTrue(blocks.any { it is MarkdownBlock.Divider })
    assertEquals(
        "人体工学上要注意最重要的一点：",
        blocks.filterIsInstance<MarkdownBlock.Heading>().single().text.plainText(),
    )
    val quote = blocks.filterIsInstance<MarkdownBlock.Quote>().single()
    val quoteParagraph = quote.blocks.single() as MarkdownBlock.Paragraph
    assertTrue(quoteParagraph.text.any { it is MarkdownInline.Strong })
    assertTrue(blocks.filterIsInstance<MarkdownBlock.Code>().none { "##" in it.literal || "> **" in it.literal })
}
```

- [ ] **Step 2: 添加真实代码保护测试**

```kotlin
@Test
fun doesNotRecoverMarkdownMarkersInsideRealCodeFence() {
    val source = """
        ```kotlin
        val divider = "---"
        # this is intentionally code-like text
        | column | value |

        fun keepGoing() = divider
        ```
    """.trimIndent()

    val code = parseMarkdownBlocks(source).single() as MarkdownBlock.Code

    assertTrue("---" in code.literal)
    assertTrue("# this is intentionally code-like text" in code.literal)
    assertTrue("| column | value |" in code.literal)
}
```

- [ ] **Step 3: 运行两个测试确认 RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.recoversTrailingAndUnclosedTextFencesFromModelOutput' --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.doesNotRecoverMarkdownMarkersInsideRealCodeFence' --console=plain
```

Expected: 截图语料测试失败，表现为代码块吞掉后续标题；保护测试通过。

- [ ] **Step 4: 添加固定长语料**

`malformed_fences_long.md` 必须包含：行尾关闭围栏、未闭合 `text` 围栏、紧凑标题、引用、嵌套列表、任务列表、宽表格、公式、emoji、长 URL 和至少 4,000 个可见字符。

- [ ] **Step 5: 提交测试基线**

```bash
git add app/src/test/java/com/harnessapk/ui/markdown/MarkdownMessageParserTest.kt app/src/test/resources/markdown/malformed_fences_long.md
git commit -m '测试：补充异常 Markdown 围栏回归语料'
```

### Task 2: 实现共享围栏扫描器和最小恢复

**Files:**
- Create: `app/src/main/java/com/harnessapk/ui/markdown/MarkdownFenceScanner.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/markdown/MarkdownMessageParser.kt:111-154`
- Test: `app/src/test/java/com/harnessapk/ui/markdown/MarkdownMessageParserTest.kt`

**Interfaces:**
- Produces: `parseMarkdownFenceOpening(line: String): MarkdownFence?`、`MarkdownFence.isClosingLine(line: String): Boolean`、`MarkdownFence.trailingCloseContent(line: String): String?`、`MarkdownFence.isTextLike: Boolean`。
- Consumes: Task 1 的截图和保护测试。

- [ ] **Step 1: 实现 CommonMark 对齐的围栏扫描器**

```kotlin
internal data class MarkdownFence(
    val marker: Char,
    val length: Int,
    val info: String,
) {
    val token: String = marker.toString().repeat(length)
    val isTextLike: Boolean = info.trim().startsWith("text", ignoreCase = true)

    fun isClosingLine(line: String): Boolean {
        val indent = line.takeWhile { it == ' ' }.length
        if (indent > 3) return false
        val trimmed = line.drop(indent)
        val runLength = trimmed.takeWhile { it == marker }.length
        return runLength >= length && trimmed.drop(runLength).isBlank()
    }

    fun trailingCloseContent(line: String): String? {
        val withoutTrailingSpace = line.trimEnd()
        val runLength = withoutTrailingSpace.takeLastWhile { it == marker }.length
        if (runLength < length) return null
        return withoutTrailingSpace.dropLast(runLength).takeIf { it.isNotBlank() }
    }
}

internal fun parseMarkdownFenceOpening(line: String): MarkdownFence? {
    val indent = line.takeWhile { it == ' ' }.length
    if (indent > 3) return null
    val trimmed = line.drop(indent)
    val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = trimmed.takeWhile { it == marker }.length
    if (length < 3) return null
    val info = trimmed.drop(length)
    if (marker == '`' && '`' in info) return null
    return MarkdownFence(marker = marker, length = length, info = info.trim())
}
```

- [ ] **Step 2: 在归一化器中使用显式状态**

将布尔 `inFence` 替换为 `var activeFence: MarkdownFence? = null` 和 `var previousFenceLineBlank = false`。处理顺序固定为：合法关闭行、行尾关闭恢复、仅 `text` 围栏的正文边界恢复、围栏内原样保留、新打开围栏、既有公式和普通 Markdown 归一化。

正文边界函数为：

```kotlin
private fun looksLikeRecoveredTextBoundary(trimmed: String): Boolean =
    thematicBreakLine.matches(trimmed) ||
        trimmed.startsWith("#") ||
        trimmed.startsWith("> ")
```

恢复时输出 `activeFence.token` 独占一行，再按普通 Markdown 处理当前边界行；不要修改输入持久化内容。

- [ ] **Step 3: 运行截图和保护测试确认 GREEN**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.recoversTrailingAndUnclosedTextFencesFromModelOutput' --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.doesNotRecoverMarkdownMarkersInsideRealCodeFence' --console=plain
```

Expected: 两个测试均 PASS。

- [ ] **Step 4: 添加波浪线和 info 规则测试并确认通过**

```kotlin
@Test
fun recoversTrailingTildeFence() {
    val blocks = parseMarkdownBlocks("~~~text\n日志内容~~~\n\n后续正文")
    assertEquals("日志内容", (blocks.first() as MarkdownBlock.Code).literal)
    assertEquals("后续正文", (blocks.last() as MarkdownBlock.Paragraph).text.plainText())
}

@Test
fun openingFenceWithInfoInsideCodeIsNotAClosingLine() {
    val blocks = parseMarkdownBlocks("```text\n第一段\n```text第二段")
    val code = blocks.single() as MarkdownBlock.Code
    assertTrue("```text第二段" in code.literal)
}
```

- [ ] **Step 5: 提交扫描器和解析修复**

```bash
git add app/src/main/java/com/harnessapk/ui/markdown/MarkdownFenceScanner.kt app/src/main/java/com/harnessapk/ui/markdown/MarkdownMessageParser.kt app/src/test/java/com/harnessapk/ui/markdown/MarkdownMessageParserTest.kt
git commit -m '修复：恢复异常 Markdown 代码围栏'
```

### Task 3: 对齐流式结构检测并扩展长文本测试

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/markdown/IncrementalMarkdownBlockCache.kt:80-105`
- Modify: `app/src/test/java/com/harnessapk/ui/markdown/MarkdownMessageParserTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `MarkdownFence` 和 `parseMarkdownFenceOpening()`。
- Produces: 与最终解析器一致的 `String.hasUnclosedFence(): Boolean` 行为。

- [ ] **Step 1: 添加流式失败测试**

```kotlin
@Test
fun incrementalParserDoesNotStabilizeMalformedTextFenceBeforeRecoveredBoundary() {
    val cache = IncrementalMarkdownBlockCache(maxStableChunkChars = 64, maxTailChars = 24)
    val source = "```text\n" + "长日志".repeat(40) + "\n\n---\n\n## 恢复后的标题"

    val chunks = cache.chunksFor(source)
    val blocks = chunks.flatMap { it.blocks }

    assertTrue(blocks.any { it is MarkdownBlock.Heading })
    assertTrue(blocks.filterIsInstance<MarkdownBlock.Code>().none { "##" in it.literal })
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.incrementalParserDoesNotStabilizeMalformedTextFenceBeforeRecoveredBoundary' --console=plain
```

Expected: FAIL，旧 `hasUnclosedFence()` 与 CommonMark 关闭规则不一致。

- [ ] **Step 3: 用共享扫描器重写未闭合检测**

```kotlin
private fun String.hasUnclosedFence(): Boolean {
    var activeFence: MarkdownFence? = null
    lineSequence().forEach { line ->
        val active = activeFence
        if (active == null) {
            activeFence = parseMarkdownFenceOpening(line)
        } else if (active.isClosingLine(line) || active.trailingCloseContent(line) != null) {
            activeFence = null
        }
    }
    return activeFence != null
}
```

- [ ] **Step 4: 添加固定种子长组合测试**

使用 `kotlin.random.Random(20260711)` 生成至少 40 个组合，每个组合包含普通段落和两种以上 Markdown 结构。断言 `parseMarkdownBlocks()` 不抛异常、返回非空 blocks，并检查每个 `TOKEN_<index>` 哨兵仍能从 `blocks.debugText()` 找到。长语料资源断言至少包含 heading、code、list、table、quote、math 六类结构。

- [ ] **Step 5: 运行 Markdown 单元测试集**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.ui.markdown.*' --console=plain
```

Expected: 全部 PASS，无测试失败。

- [ ] **Step 6: 提交流式和长文本回归**

```bash
git add app/src/main/java/com/harnessapk/ui/markdown/IncrementalMarkdownBlockCache.kt app/src/test/java/com/harnessapk/ui/markdown/MarkdownMessageParserTest.kt
git commit -m '测试：扩展 Markdown 长文本与流式回归'
```

### Task 4: Compose 渲染回归与完整验证

**Files:**
- Modify: `app/src/androidTest/java/com/harnessapk/ui/chat/MarkdownMessageTest.kt`

**Interfaces:**
- Consumes: Task 2 和 Task 3 的最终 parser 行为。
- Produces: Android Compose 渲染回归测试。

- [ ] **Step 1: 添加 Compose 结构标记泄漏测试**

```kotlin
@Test
fun malformedFencesDoNotLeakHeadingAndQuoteMarkers() {
    composeRule.setContent {
        MaterialTheme {
            MarkdownMessage(
                modifier = Modifier.width(360.dp),
                markdown = """
                    ```text
                    第一块内容```

                    ```text
                    第二块内容

                    ---
                    ## 恢复标题
                    > **身体正对主屏**
                """.trimIndent(),
            )
        }
    }

    composeRule.onNodeWithText("恢复标题").assertIsDisplayed()
    composeRule.onNodeWithText("身体正对主屏").assertIsDisplayed()
    composeRule.onNodeWithText("## 恢复标题").assertDoesNotExist()
    composeRule.onNodeWithText("> **身体正对主屏**").assertDoesNotExist()
}
```

- [ ] **Step 2: 编译 Android 测试**

Run:

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 运行完整单元测试和构建**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`，0 failed tests。

- [ ] **Step 4: 有设备时运行 Compose 测试和视觉复验**

Run:

```bash
adb devices -l
./gradlew :app:installDebug :app:connectedDebugAndroidTest --console=plain
```

若无设备，只运行 Step 2 并在最终报告中写明模拟器验证未执行；不要声称已完成设备视觉复验。

- [ ] **Step 5: 检查差异并提交本轮剩余测试**

```bash
git diff --check
git status --short
git add app/src/androidTest/java/com/harnessapk/ui/chat/MarkdownMessageTest.kt
git commit -m '测试：验证异常 Markdown 窄屏渲染'
```

- [ ] **Step 6: 最终验证提交状态**

Run:

```bash
git status --short --branch
git log -6 --oneline
```

Expected: 工作区无未提交的任务相关文件；分支只包含本轮设计、计划、修复和测试提交。

### Task 5: 解包 `text` 与中文正文粘连的异常围栏

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/markdown/MarkdownFenceScanner.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/markdown/MarkdownMessageParser.kt`
- Test: `app/src/test/java/com/harnessapk/ui/markdown/MarkdownMessageParserTest.kt`

**Interfaces:**
- Consumes: `MarkdownFence`、`parseMarkdownFenceOpening(line: String)` 和 `parseMarkdownBlocks(markdown: String)`。
- Produces: `MarkdownFence.gluedTextContent: String?`、`unwrapSingleLineGluedTextFence(line: String): String?` 和展示前解包流程。

- [ ] **Step 1: 添加误判回归和反例保护测试**

```kotlin
@Test
fun unwrapsGluedChineseTextFenceAsMarkdown() {
    val blocks = parseMarkdownBlocks(
        """
        ```text一级标题
        这里是普通正文，包含 **重点**。
        ```

        后续正文
        """.trimIndent(),
    )

    assertTrue(blocks.none { it is MarkdownBlock.Code })
    assertTrue(blocks.debugText(), "一级标题" in blocks.debugText())
    assertTrue(blocks.debugText(), "这里是普通正文" in blocks.debugText())
    assertTrue(blocks.debugText(), "后续正文" in blocks.debugText())
}

@Test
fun unwrapsSingleLineGluedChineseTextFence() {
    val blocks = parseMarkdownBlocks("```text左侧布局：主屏 + 日志屏```")
    assertTrue(blocks.none { it is MarkdownBlock.Code })
    assertEquals("左侧布局：主屏 + 日志屏", (blocks.single() as MarkdownBlock.Paragraph).text.plainText())
}

@Test
fun preservesValidTextAndTextLikeLanguageFences() {
    val samples = listOf("text", "plaintext", "text/plain", "kotlin")
    samples.forEach { info ->
        val code = parseMarkdownBlocks("```$info\n正文日志\n```").single()
        assertTrue("$info should remain code", code is MarkdownBlock.Code)
    }
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.unwrapsGluedChineseTextFenceAsMarkdown' --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.unwrapsSingleLineGluedChineseTextFence' --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.preservesValidTextAndTextLikeLanguageFences' --console=plain
```

Expected: 两个解包测试失败，合法代码块保护测试通过。

- [ ] **Step 3: 在扫描器中识别中文粘连内容**

```kotlin
internal val MarkdownFence.gluedTextContent: String?
    get() {
        if (!info.startsWith("text", ignoreCase = true)) return null
        val suffix = info.drop(TEXT_LANGUAGE_LENGTH)
        val first = suffix.firstOrNull() ?: return null
        return suffix.takeIf {
            Character.UnicodeScript.of(first.code) == Character.UnicodeScript.HAN
        }
    }

internal fun unwrapSingleLineGluedTextFence(line: String): String? {
    val match = singleLineGluedTextFence.matchEntire(line) ?: return null
    return match.groupValues[1]
}

private const val TEXT_LANGUAGE_LENGTH = 4
private val singleLineGluedTextFence = Regex(
    pattern = "^\\s{0,3}`{3,}text([\\p{IsHan}].*?)`{3,}\\s*$",
    option = RegexOption.IGNORE_CASE,
)
```

单行 helper 返回中文正文捕获组，不返回围栏 token；测试必须证明返回值精确为 `左侧布局：主屏 + 日志屏`。

- [ ] **Step 4: 在归一化前解包多行和单行异常围栏**

```kotlin
private fun unwrapGluedTextFences(markdown: String): String {
    var activeUnwrappedFence: MarkdownFence? = null
    return buildList {
        markdown.lineSequence().forEach { line ->
            activeUnwrappedFence?.let { fence ->
                val trailingContent = fence.trailingCloseContent(line)
                when {
                    fence.isClosingLine(line) -> activeUnwrappedFence = null
                    trailingContent != null -> {
                        add(trailingContent)
                        activeUnwrappedFence = null
                    }
                    else -> add(line)
                }
                return@forEach
            }
            unwrapSingleLineGluedTextFence(line)?.let {
                add(it)
                return@forEach
            }
            val openingFence = parseMarkdownFenceOpening(line)
            val gluedContent = openingFence?.gluedTextContent
            if (openingFence != null && gluedContent != null) {
                add(gluedContent)
                activeUnwrappedFence = openingFence
            } else {
                add(line)
            }
        }
    }.joinToString("\n")
}
```

`parseMarkdownBlocks()` 必须调用 `normalizeModelMarkdown(unwrapGluedTextFences(markdown))`。同时把 `isTextLike` 收窄到精确的 `text` 首 token，避免 `text一级` 再进入文本代码块恢复分支。

- [ ] **Step 5: 运行定向测试确认 GREEN**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.unwrapsGluedChineseTextFenceAsMarkdown' --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.unwrapsSingleLineGluedChineseTextFence' --tests 'com.harnessapk.ui.markdown.MarkdownMessageParserTest.preservesValidTextAndTextLikeLanguageFences' --console=plain
```

Expected: 三个测试全部 PASS。

- [ ] **Step 6: 更新旧截图和长语料断言**

原先 `text左侧...`、`text一级...` 语料不再断言产生代码块；断言其正文 token 存在且不在任何 `MarkdownBlock.Code.literal` 中。长语料追加一个标准 ` ```text` 日志块，并只断言该标准块产生代码块。

- [ ] **Step 7: 提交解析回归修复**

```bash
git add app/src/main/java/com/harnessapk/ui/markdown/MarkdownFenceScanner.kt app/src/main/java/com/harnessapk/ui/markdown/MarkdownMessageParser.kt app/src/test/java/com/harnessapk/ui/markdown/MarkdownMessageParserTest.kt app/src/test/resources/markdown/malformed_fences_long.md
git commit -m '修复：避免中文正文误判为 text 代码块'
```

### Task 6: 混合 Markdown Compose 回归与最终验证

**Files:**
- Modify: `app/src/androidTest/java/com/harnessapk/ui/chat/MarkdownMessageTest.kt`

**Interfaces:**
- Consumes: Task 5 的解包行为。
- Produces: 窄屏混合正文只渲染真实代码块的 Compose 回归测试。

- [ ] **Step 1: 添加 Compose 混合渲染测试**

```kotlin
@Test
fun mixedMarkdownOnlyRendersRealCodeFenceAsCode() {
    composeRule.setContent {
        MaterialTheme {
            MarkdownMessage(
                modifier = Modifier.width(360.dp),
                markdown = """
                    ```text一级标题
                    这是普通正文。
                    ```

                    > **身体正对主屏**

                    ```kotlin
                    val x = 1
                    ```
                """.trimIndent(),
            )
        }
    }

    composeRule.onNodeWithText("一级标题 这是普通正文。").assertIsDisplayed()
    composeRule.onNodeWithText("身体正对主屏").assertIsDisplayed()
    composeRule.onNodeWithText("text一级标题").assertDoesNotExist()
    composeRule.onNodeWithText("kotlin").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("复制代码").assertIsDisplayed()
}
```

- [ ] **Step 2: 运行完整单元测试和 Debug 构建**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL`，0 failed tests。

- [ ] **Step 3: 启动 `harness_api36` 并运行 Android 测试**

Run:

```bash
emulator -avd harness_api36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
./gradlew :app:connectedDebugAndroidTest --rerun-tasks --console=plain
```

Expected: 14 个以上 Android/Compose 测试全部通过，0 failed。

- [ ] **Step 4: 检查差异并提交测试**

```bash
git diff --check
git add app/src/androidTest/java/com/harnessapk/ui/chat/MarkdownMessageTest.kt
git commit -m '测试：验证混合 Markdown 仅保留真实代码块'
```

- [ ] **Step 5: 确认工作区状态**

Run:

```bash
git status --short --branch
git log -5 --oneline
```

Expected: 工作区干净；不推送远端。
