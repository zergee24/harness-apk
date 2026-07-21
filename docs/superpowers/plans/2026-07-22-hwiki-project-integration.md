# Harness `.hwiki` Project Markdown Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let project conversations turn Wiki-backed answers and comparison research into portable Markdown with readable source footnotes, while preserving the existing user-reviewed file-change workflow and weak associations.

**Architecture:** Read verified citation snapshots from the source assistant messages, convert Harness-only inline citation links into deterministic standard Markdown footnotes before invoking the existing planner, and validate every returned proposal before showing its diff. The project remains the owner of Markdown; Wikis remain global read-only assets, and `conversation_markdown_links` remains the only weak link between conversation and notes. Comparison coverage is carried as explicit planner context so missing evidence cannot be rewritten as a claim that a source is silent.

**Tech Stack:** Kotlin, existing `MarkdownUpdatePlannerUseCase`, existing Markdown draft/diff/apply pipeline, Room citation reads from work package 3, Kotlin serialization, JUnit, Android instrumented tests.

**Authoritative Spec:** `docs/superpowers/specs/2026-07-22-offline-hwiki-knowledge-package-design.md`. If implementation evidence requires changing a product boundary, amend and re-approve the Spec before changing this plan.

## Global Constraints

- This is work package 4 of 5 and consumes the verified citation records from `2026-07-22-hwiki-chat-retrieval.md`.
- A Wiki is never assigned to or owned by a project. Do not add `project_wikis`, `markdown_wikis`, package-copy, or hidden front matter.
- Only conversations with a nonblank `projectId` may enter the existing Markdown planning and apply paths.
- No assistant response, retrieval hit, or citation automatically creates or writes a file. Every change remains a visible draft with per-file diff and explicit apply action.
- Portable Markdown must contain normal footnote references and definitions; it must not contain `harness-wiki://`, citation UUIDs, chunk IDs, app-private paths, or SQL metadata.
- Every Wiki footnote contains at least source title, section path, exact Wiki title/version, and readable locator. It may contain a bounded original quotation but never a full page.
- Citation metadata comes from immutable `MessageWikiCitationEntity` snapshots, not from whichever package version is currently active.
- Wiki upgrades do not rewrite existing Markdown. Updating sources requires a new conversation retrieval and a new reviewed diff.
- A missing comparison side is labeled as missing evidence. “No record exists”, “the two sources agree”, and equivalent claims require verified evidence from every compared Wiki.
- Keep ordinary non-Wiki Markdown behavior unchanged.
- Use TDD and create one scoped Chinese commit at the end of every task; do not push.

## Plan Series

This plan follows:

1. `2026-07-22-hwiki-package-builder.md`
2. `2026-07-22-hwiki-android-library.md`
3. `2026-07-22-hwiki-chat-retrieval.md`

It is followed by `2026-07-22-hwiki-two-history-build.md`.

---

### Task 1: Define Portable Wiki Footnotes And Deterministic Rewriting

**Files:**
- Create: `app/src/main/java/com/harnessapk/session/WikiMarkdownCitation.kt`
- Create: `app/src/main/java/com/harnessapk/session/WikiMarkdownCitationFormatter.kt`
- Create: `app/src/main/java/com/harnessapk/session/WikiMarkdownProposalValidator.kt`
- Create: `app/src/test/java/com/harnessapk/session/WikiMarkdownCitationFormatterTest.kt`
- Create: `app/src/test/java/com/harnessapk/session/WikiMarkdownProposalValidatorTest.kt`

**Interfaces:**
- Produces `WikiMarkdownCitation`, `WikiMarkdownCitationSet`, `WikiMarkdownCitationFormatter.toPortableMarkdown`, and `WikiMarkdownProposalValidator.validate`.
- Footnote labels use deterministic `hwiki-<message-short-id>-<display-ordinal>` values containing lowercase ASCII letters, digits, and hyphens only.

- [ ] **Step 1: Write rewriting and validation tests**

```kotlin
@Test
fun `internal citation anchor becomes portable footnote`() {
    val result = formatter.toPortableMarkdown(
        markdown = "司马光作《资治通鉴》。[¹](harness-wiki://citation/$CITATION_ID)",
        citations = citationSet,
    )

    assertEquals(
        """
        司马光作《资治通鉴》。[^hwiki-a1b2c3d4-1]

        [^hwiki-a1b2c3d4-1]: 《资治通鉴》· 卷第一；资治通鉴 v1；周威烈王二十三年。
        """.trimIndent(),
        result,
    )
}

@Test
fun `proposal validator rejects Harness-only links and invented footnotes`() {
    assertFailsWith<WikiMarkdownValidationException> {
        validator.validate(proposalWith("[来源](harness-wiki://citation/$CITATION_ID)"), citationSet)
    }
    assertFailsWith<WikiMarkdownValidationException> {
        validator.validate(proposalWith("结论[^hwiki-invented-9]"), citationSet)
    }
}
```

- [ ] **Step 2: Run focused tests and verify the formatter is missing**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.session.WikiMarkdownCitationFormatterTest --tests com.harnessapk.session.WikiMarkdownProposalValidatorTest`

Expected: compilation fails on missing Wiki Markdown classes.

- [ ] **Step 3: Map saved citation rows into portable domain values**

```kotlin
data class WikiMarkdownCitation(
    val citationId: String,
    val sourceMessageId: String,
    val displayOrdinal: Int,
    val wikiId: String,
    val wikiVersion: Int,
    val wikiTitle: String,
    val sourceTitle: String,
    val sectionPath: String,
    val locatorLabel: String,
    val originalTextSnapshot: String,
    val originalTextSha256: String,
)
```

Validate UUID, positive ordinal/version, 64-hex source hash, nonblank readable labels, and duplicate IDs before formatting.

- [ ] **Step 4: Rewrite internal anchors and append standard footnotes**

Parse Markdown links through the existing CommonMark parser instead of regex-only replacement. Replace only internal citation links present in the supplied set. Repeated links share one footnote definition; definitions follow first-use order. Preserve ordinary HTTP links and pre-existing user footnotes. Escape line breaks and footnote-control syntax in metadata.

Footnote definition format is exactly:

```text
[^label]: 《sourceTitle》· sectionPath；wikiTitle v<version>；locatorLabel。
```

When `sectionPath` already begins with the source title, omit the duplicate title segment. Include at most 160 Unicode code points of original quotation only when the caller explicitly requests quotation detail.

- [ ] **Step 5: Validate planner proposals structurally**

Reject Harness schemes, app-private paths, UUID leakage, undefined `hwiki-*` references, definitions not present in the supplied set, altered source title/version/locator, duplicate definitions, and a proposal that turns a citation snapshot into a full-page copy. Remove supplied but unused definitions deterministically; preserve unrelated user footnotes.

- [ ] **Step 6: Run formatter and validator tests**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.session.WikiMarkdownCitationFormatterTest --tests com.harnessapk.session.WikiMarkdownProposalValidatorTest`

Expected: all tests pass, including Chinese punctuation, duplicate source use, missing package, existing footnotes, and malformed internal links.

- [ ] **Step 7: Commit portable citation formatting**

```bash
git add app/src/main/java/com/harnessapk/session/WikiMarkdownCitation.kt app/src/main/java/com/harnessapk/session/WikiMarkdownCitationFormatter.kt app/src/main/java/com/harnessapk/session/WikiMarkdownProposalValidator.kt app/src/test/java/com/harnessapk/session
git commit -m "功能：将 Wiki 引用转换为 Markdown 脚注"
```

### Task 2: Supply Verified Citations To The Existing Markdown Planner

**Files:**
- Modify: `app/src/main/java/com/harnessapk/session/MarkdownUpdatePlannerUseCase.kt`
- Modify: `app/src/main/java/com/harnessapk/session/MarkdownUpdateModels.kt`
- Modify: `app/src/test/java/com/harnessapk/session/MarkdownUpdatePlannerTest.kt`
- Create: `app/src/test/java/com/harnessapk/session/MarkdownUpdatePlannerWikiCitationTest.kt`

**Interfaces:**
- Adds `wikiCitations: WikiMarkdownCitationSet = WikiMarkdownCitationSet.EMPTY` and `wikiCoverage: WikiEvidenceCoverage = WikiEvidenceCoverage.NONE` to both `plan` and `planFromUserRequest`.
- Adds citation inputs to `buildMarkdownUpdatePlanningMessages` and `buildMarkdownFileChangePlanningMessages`.

- [ ] **Step 1: Write prompt and proposal-validation tests**

Test no-citation byte-equivalence, one source, repeated source, two-Wiki comparison, missing comparison side, bad planner footnote, planner-emitted internal link, and citation metadata containing Markdown punctuation.

- [ ] **Step 2: Run focused planner tests and observe missing parameters**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.session.MarkdownUpdatePlannerTest --tests com.harnessapk.session.MarkdownUpdatePlannerWikiCitationTest`

Expected: compilation fails on `wikiCitations` and `WikiEvidenceCoverage`.

- [ ] **Step 3: Convert source text before the planner request**

For `plan`, transform the assistant Markdown into portable footnote Markdown before writing `本轮助手输出`. For `planFromUserRequest`, transform every cited excerpt included in `conversationContext`; the user's current request remains unchanged. Add a bounded `可用 Wiki 脚注` section containing exact definitions the planner may preserve, but no internal UUIDs.

- [ ] **Step 4: Add precise planner rules**

Extend the system message with:

```text
若来源内容包含 [^hwiki-*] 脚注，保留相关事实所需的脚注引用和定义。
只能使用“可用 Wiki 脚注”中给出的来源，不得编造或改写书名、卷目、版本和位置。
若比较覆盖信息标记某一知识库无证据，只能写“当前检索未找到依据”，不能写“该书没有记载”或“两书一致”。
禁止输出 harness-wiki://、引用 UUID、chunk ID 和应用内部路径。
```

- [ ] **Step 5: Validate every parsed proposal before returning the plan**

Run `WikiMarkdownProposalValidator` after `parseMarkdownUpdatePlanResponse`. If one proposal fails, reject the complete plan with a readable planning error; do not silently drop only that file and do not write any file.

- [ ] **Step 6: Run planner regression tests**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.session.MarkdownUpdatePlannerTest --tests com.harnessapk.session.MarkdownUpdatePlannerWikiCitationTest`

Expected: all tests pass; ordinary plans without Wiki citations preserve their previous request payload and output parsing.

- [ ] **Step 7: Commit planner integration**

```bash
git add app/src/main/java/com/harnessapk/session/MarkdownUpdatePlannerUseCase.kt app/src/main/java/com/harnessapk/session/MarkdownUpdateModels.kt app/src/test/java/com/harnessapk/session
git commit -m "功能：让 Markdown 规划保留 Wiki 依据"
```

### Task 3: Build Citation And Comparison Context From Message History

**Files:**
- Create: `app/src/main/java/com/harnessapk/session/WikiMarkdownContextRepository.kt`
- Create: `app/src/main/java/com/harnessapk/session/WikiEvidenceCoverage.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/ConversationWikiDao.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Create: `app/src/test/java/com/harnessapk/session/WikiMarkdownContextRepositoryTest.kt`
- Create: `app/src/test/java/com/harnessapk/session/WikiEvidenceCoverageTest.kt`

**Interfaces:**
- Produces `WikiMarkdownSourceContext(citations, coverage)` for one assistant message or a bounded set of recent message IDs.
- `WikiEvidenceCoverage` records requested comparison Wiki refs, queried refs, cited counts, and missing refs from the persisted retrieval run.

- [ ] **Step 1: Write exact-message and bounded-history tests**

Test one assistant message, recent conversation context, deleted message, old-version citation, duplicate chunk, `HIT`, `NO_HIT`, failed side, explicit comparison with one missing side, and an unrelated earlier citation excluded by the recent message-ID set.

- [ ] **Step 2: Run focused tests and verify missing repository**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.session.WikiMarkdownContextRepositoryTest --tests com.harnessapk.session.WikiEvidenceCoverageTest`

Expected: compilation fails on missing context repository.

- [ ] **Step 3: Add typed DAO reads without new relationships**

Read retrieval run, usages, and citations by assistant message ID in display order. Do not query the currently active Wiki version and do not add a new Room entity. For conversation-context planning, accept an explicit ordered list of at most 12 message IDs and at most 40 citations; never pull the unbounded conversation.

- [ ] **Step 4: Derive comparison coverage conservatively**

A Wiki is “covered” only when it was queried and has at least one verified citation. `NO_HIT`, failed query, or queried without a final citation is a missing side. Preserve the difference between “not queried” and “queried but no reliable evidence”; neither permits a source-absence claim.

- [ ] **Step 5: Run context repository tests**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.session.WikiMarkdownContextRepositoryTest --tests com.harnessapk.session.WikiEvidenceCoverageTest`

Expected: all tests pass and returned source order matches message order then citation ordinal.

- [ ] **Step 6: Commit context construction**

```bash
git add app/src/main/java/com/harnessapk/session app/src/main/java/com/harnessapk/storage/ConversationWikiDao.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/test/java/com/harnessapk/session
git commit -m "功能：汇总项目沉淀所需的 Wiki 证据"
```

### Task 4: Integrate Both Existing Markdown Change Entry Paths

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/session/MarkdownFileChangeModels.kt`
- Modify: `app/src/test/java/com/harnessapk/session/MarkdownFileChangeControllerTest.kt`
- Create: `app/src/test/java/com/harnessapk/ui/ProjectWikiMarkdownPlanningTest.kt`

**Interfaces:**
- Assistant-output planning passes citations from that exact assistant message.
- User file-change request planning passes citations only from the bounded conversation messages included in `conversationContext`.
- Draft persistence remains the existing `MarkdownChangeDraftEntity` and `MarkdownChangeDraftItemEntity` format.

- [ ] **Step 1: Write project-gate and source-selection tests**

Cover project assistant output, project user request referring to earlier answer, ordinary conversation, archived/missing project, planning retry, restored draft, citation package removed after response, and planner validation failure.

- [ ] **Step 2: Run focused tests and observe missing integration**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.ProjectWikiMarkdownPlanningTest --tests com.harnessapk.session.MarkdownFileChangeControllerTest`

Expected: Wiki-backed planning assertions fail while existing draft tests pass.

- [ ] **Step 3: Pass exact assistant citations at the assistant-output call site**

Before `markdownUpdatePlanner.plan`, load `WikiMarkdownSourceContext` for the selected assistant message ID and pass its citation set and coverage. If no citations exist, use the ordinary path. If citation loading fails, stop planning with `无法读取本轮引用，未生成文件变更`; do not discard the citations and continue as if unsupported.

- [ ] **Step 4: Pass bounded historical citations at the user-request call site**

Build `conversationContext` and its message-ID list from the same bounded recent history. Load only citations for those IDs and pass them to `planFromUserRequest`. This supports “把上面的比较写进笔记” without attaching every citation in the conversation.

- [ ] **Step 5: Keep review and apply behavior unchanged**

The generated standard footnote lines appear directly in the existing per-file diff. Retain/dismiss/apply/retry controls are unchanged. Applying writes only the reviewed proposal through the current project repository and updates the existing conversation-Markdown weak link.

- [ ] **Step 6: Make Wiki validation failures visible in draft state**

Use the existing `FAILED` draft status with a concise reason. Do not persist raw prompt context, internal citation IDs, or source snapshots into draft summaries. Retrying rebuilds citation context from immutable message rows.

- [ ] **Step 7: Run project planning and draft tests**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.ProjectWikiMarkdownPlanningTest --tests com.harnessapk.session.MarkdownFileChangeControllerTest --tests com.harnessapk.session.MarkdownUpdatePlannerWikiCitationTest`

Expected: all tests pass; no project file changes before explicit apply.

- [ ] **Step 8: Commit project flow integration**

```bash
git add app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/main/java/com/harnessapk/session app/src/test/java/com/harnessapk
git commit -m "功能：在项目 Markdown 变更中保留 Wiki 脚注"
```

### Task 5: Add A Two-Wiki Comparison Closure Test

**Files:**
- Create: `app/src/androidTest/java/com/harnessapk/session/TwoWikiProjectMarkdownFlowTest.kt`
- Create: `app/src/test/java/com/harnessapk/session/TwoWikiComparisonPolicyTest.kt`
- Create: `app/src/test/resources/wiki/project-comparison-provider-response.json`
- Reuse: signed fixture packages from work package 1.

**Interfaces:**
- Exercises install → default scope → project conversation → comparison retrieval → verified citations → Markdown draft → review → apply → portable file.

- [ ] **Step 1: Write the failing comparison policy tests**

Cases must cover both sides cited, only one side cited, one side `NO_HIT`, one side failed, a provider inventing a third footnote, a provider claiming “两书均无记载”, and a valid cautious sentence `当前检索未在资治通鉴中找到可核对原文`.

- [ ] **Step 2: Run policy tests and confirm missing enforcement**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.session.TwoWikiComparisonPolicyTest`

Expected: unsafe comparison claims are not yet rejected.

- [ ] **Step 3: Add deterministic comparison-language validation**

When coverage has missing sides, reject absolute source-absence/equivalence phrases tied to those sources, including `没有记载`, `从未提及`, `均未记载`, `两书一致`, and `两书都认为`. Permit explicitly scoped retrieval language containing `当前检索`, `未找到可靠原文`, or `证据不足`. This guard complements the prompt; it does not attempt general historical fact checking.

- [ ] **Step 4: Implement the instrumented project flow**

Use two tiny signed fixture Wikis with shared concept namespace and a deterministic fake Provider response containing valid `⟦W1⟧` and `⟦W2⟧` tokens. Create a real project workspace in the test sandbox, produce a Markdown draft, assert no file exists before apply, apply it, then inspect UTF-8 file contents.

- [ ] **Step 5: Assert the final file is portable**

The file must include both readable footnote definitions and no `harness-wiki://`, UUID, chunk ID, absolute path, or hidden front matter. Reopening it through the existing Markdown notebook shows the same visible content without requiring either `.hwiki` package.

- [ ] **Step 6: Run policy and instrumented tests**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.session.TwoWikiComparisonPolicyTest`

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.session.TwoWikiProjectMarkdownFlowTest`

Expected: all tests pass and the generated file changes only after the test invokes apply.

- [ ] **Step 7: Commit the comparison closure**

```bash
git add app/src/main/java/com/harnessapk/session app/src/test/java/com/harnessapk/session app/src/test/resources/wiki app/src/androidTest/java/com/harnessapk/session
git commit -m "验证：闭环双 Wiki 项目沉淀"
```

### Task 6: Verify Work Package 4 End To End

**Files:**
- Verify all Task 1-5 files.
- Do not modify production code unless a failing check identifies a defect.

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run the focused instrumented flow**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.session.TwoWikiProjectMarkdownFlowTest,com.harnessapk.ui.HarnessApkAppNavigationTest`

Expected: all selected tests pass.

- [ ] **Step 3: Perform a manual project smoke test**

In a project conversation with both Wikis mounted, ask for a comparison, open and inspect both answer citations, request `把这个比较写入项目笔记`, inspect the generated file diff and footnotes, apply it, then open the Markdown from the project.

Expected: no write occurs before apply; footnotes remain readable outside the chat; source versions match the original answer rather than active versions.

- [ ] **Step 4: Perform the ordinary-conversation negative test**

Ask the same Wiki-backed question in a conversation with no project and attempt the equivalent file-write request.

Expected: no project or Markdown file is silently created and no apply control appears.

- [ ] **Step 5: Confirm repository hygiene**

Run: `git status --short`

Expected: no test project, generated Markdown, `.hwiki`, database, or APK artifact is outside ignored build directories.

No final verification commit is needed when the worktree is clean.
