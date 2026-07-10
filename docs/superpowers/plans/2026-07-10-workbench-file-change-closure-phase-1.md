# Workbench File Change Closure Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the project-conversation Markdown file-change loop so successful files survive partial failures and users can jump from the chat result to the refreshed project folder or Git state.

**Architecture:** Keep drafts in `ChatScreen` memory and keep real files in `FileProjectRepository`. Replace the all-or-throw gateway contract with per-file structured results, let `MarkdownFileChangeController` merge cumulative successes and retryable failures, and route one-shot project workbench targets through `HarnessApkApp` so `ProjectScreen` owns its own refresh behavior.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, coroutines, JUnit 4, Compose UI Test, Gradle, Android Emulator, adb.

## Global Constraints

- File-change drafts remain in memory; do not add Room entities or migrations.
- LLM-initiated writes remain limited to Markdown and must pass project-directory boundary validation.
- A failed file must not roll back or hide files that were written successfully.
- Retrying a partial application must submit only failed proposals.
- Applying changes must never commit or push Git automatically.
- Successful application stays on the chat screen and exposes “查看文件” and “查看 Git 变更”.
- Use the existing warm Material 3 theme and keep all action targets at least `48dp` high.
- Do not add third-party dependencies or expand Provider, search, voice, skills, or plugin behavior.
- Use Chinese commit messages and stage only files listed by the active task.

---

## File Structure

- `app/src/main/java/com/harnessapk/session/ProjectWorkspaceGateway.kt`: structured per-file and batch application result contracts.
- `app/src/main/java/com/harnessapk/project/FileProjectRepository.kt`: normalized Markdown path validation inside a project.
- `app/src/main/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapter.kt`: prevalidate the batch, write valid proposals independently, and return every result.
- `app/src/main/java/com/harnessapk/session/MarkdownFileChangeModels.kt`: cumulative applied paths, failed proposals, partial status, and retry selection.
- `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`: application orchestration, result events, result cards, and project-view intents.
- `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`: one-shot project workbench target ownership.
- `app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt`: target types and target-to-tab mapping.
- `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt`: target consumption and file/Git refresh.
- `app/src/test/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapterTest.kt`: partial real-file writes.
- `app/src/test/java/com/harnessapk/session/MarkdownFileChangeControllerTest.kt`: partial state and retry semantics.
- `app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt`: result copy and presentation decisions.
- `app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt`: target construction.
- `app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt`: target-to-tab behavior.
- `app/src/androidTest/java/com/harnessapk/ui/chat/MarkdownFileChangeCardTest.kt`: applied and partial card actions.

---

### Task 1: Structured Partial Batch Writes

**Files:**
- Modify: `app/src/main/java/com/harnessapk/session/ProjectWorkspaceGateway.kt:1-67`
- Modify: `app/src/main/java/com/harnessapk/project/FileProjectRepository.kt:155-178`
- Modify: `app/src/main/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapter.kt:1-84`
- Create: `app/src/test/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapterTest.kt`

**Interfaces:**
- Consumes: `MarkdownUpdateProposal`, `CreatedDeliverable`, `FileProjectRepository.writeMarkdownFile`.
- Produces: `MarkdownFileApplyStatus`, `MarkdownFileApplyResult`, `MarkdownBatchApplyResult`, `FileProjectRepository.validateMarkdownFilePath`, and temporary `ProjectWorkspaceGateway.applyMarkdownUpdatesWithResults(...)`. The existing list-returning method remains only as a compile bridge until Task 3 migrates both chat call sites.

- [ ] **Step 1: Write the failing adapter test**

Create `ProjectWorkspaceGatewayAdapterTest.kt`:

```kotlin
package com.harnessapk.project

import com.harnessapk.common.TimeProvider
import com.harnessapk.session.MarkdownFileApplyStatus
import com.harnessapk.session.MarkdownUpdateOperation
import com.harnessapk.session.MarkdownUpdateProposal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectWorkspaceGatewayAdapterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun applyMarkdownUpdatesKeepsSuccessfulFilesAndContinuesAfterInvalidPath() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 1L },
        )
        val project = repository.createProject("移动端 Harness")
        val gateway = ProjectWorkspaceGatewayAdapter(repository)

        val result = gateway.applyMarkdownUpdatesWithResults(
            projectId = project.id,
            updates = listOf(
                proposal("docs/first.md", "# First"),
                proposal("../escape.md", "# Escape"),
                proposal("docs/third.md", "# Third"),
            ),
        )

        assertEquals(
            listOf(
                MarkdownFileApplyStatus.SUCCEEDED,
                MarkdownFileApplyStatus.FAILED,
                MarkdownFileApplyStatus.SUCCEEDED,
            ),
            result.results.map { it.status },
        )
        assertEquals(listOf("docs/first.md", "docs/third.md"), result.succeeded.map { it.proposal.path })
        assertEquals(listOf("../escape.md"), result.failed.map { it.proposal.path })
        assertTrue(project.rootDirectory.resolve("docs/first.md").isFile)
        assertTrue(project.rootDirectory.resolve("docs/third.md").isFile)
        assertTrue(!temporaryFolder.root.resolve("escape.md").exists())
    }

    private fun proposal(path: String, markdown: String) = MarkdownUpdateProposal(
        operation = MarkdownUpdateOperation.CREATE,
        path = path,
        title = path.substringAfterLast('/').substringBeforeLast('.'),
        reason = "测试批量写入",
        markdown = markdown,
    )
}
```

- [ ] **Step 2: Run the adapter test and verify RED**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.project.ProjectWorkspaceGatewayAdapterTest
```

Expected: compilation fails because `MarkdownFileApplyStatus`, `MarkdownBatchApplyResult`, and the structured gateway return type do not exist.

- [ ] **Step 3: Add structured result contracts and change the gateway return type**

Add after `CreatedDeliverable` in `ProjectWorkspaceGateway.kt`:

```kotlin
enum class MarkdownFileApplyStatus { SUCCEEDED, FAILED }

data class MarkdownFileApplyResult(
    val proposal: MarkdownUpdateProposal,
    val status: MarkdownFileApplyStatus,
    val writtenDeliverable: CreatedDeliverable? = null,
    val errorMessage: String? = null,
)

data class MarkdownBatchApplyResult(
    val results: List<MarkdownFileApplyResult>,
) {
    val succeeded: List<MarkdownFileApplyResult>
        get() = results.filter { it.status == MarkdownFileApplyStatus.SUCCEEDED }
    val failed: List<MarkdownFileApplyResult>
        get() = results.filter { it.status == MarkdownFileApplyStatus.FAILED }
    val isFullyApplied: Boolean
        get() = results.isNotEmpty() && failed.isEmpty()
    val isPartiallyApplied: Boolean
        get() = succeeded.isNotEmpty() && failed.isNotEmpty()
}
```

Add the structured method beside the existing list-returning method; do not remove the old method in this task:

```kotlin
suspend fun applyMarkdownUpdatesWithResults(
    projectId: String,
    updates: List<MarkdownUpdateProposal>,
): MarkdownBatchApplyResult
```

Add the structured method to `EmptyProjectWorkspaceGateway`; leave its existing list-returning method in place:

```kotlin
override suspend fun applyMarkdownUpdatesWithResults(
    projectId: String,
    updates: List<MarkdownUpdateProposal>,
): MarkdownBatchApplyResult = MarkdownBatchApplyResult(
    results = updates.map { proposal ->
        MarkdownFileApplyResult(
            proposal = proposal,
            status = MarkdownFileApplyStatus.SUCCEEDED,
            writtenDeliverable = CreatedDeliverable(
                id = proposal.path,
                title = proposal.title,
                path = proposal.path,
            ),
        )
    },
)
```

- [ ] **Step 4: Add repository path prevalidation**

Add before `writeMarkdownFile` in `FileProjectRepository.kt`, then make `writeMarkdownFile` consume it:

```kotlin
fun validateMarkdownFilePath(projectId: String, relativePath: String): String {
    val project = projectDirectory(projectId)
    val normalizedPath = relativePath.trim().replace('\\', '/').trim('/')
    require(normalizedPath.isNotBlank()) { "Markdown 路径不能为空" }
    require(normalizedPath.endsWith(".md", ignoreCase = true)) { "只能写入 Markdown 文件" }
    checkedProjectFile(project, normalizedPath)
    return normalizedPath
}

suspend fun writeMarkdownFile(
    projectId: String,
    relativePath: String,
    markdown: String,
): ProjectDeliverable {
    val project = projectDirectory(projectId)
    val normalizedPath = validateMarkdownFilePath(projectId, relativePath)
    val file = checkedProjectFile(project, normalizedPath)
    file.parentFile?.mkdirs()
    file.writeText(markdown)
    return deliverableFromFile(project, file, templateFor(project, file))
}
```

- [ ] **Step 5: Implement prevalidate-all, write-independently behavior**

Import `com.harnessapk.common.toUserMessage` and the new session result types, then add the structured adapter method:

```kotlin
override suspend fun applyMarkdownUpdatesWithResults(
    projectId: String,
    updates: List<MarkdownUpdateProposal>,
): MarkdownBatchApplyResult {
    val validations = updates.map { proposal ->
        proposal to runCatching {
            proposal.copy(path = repository.validateMarkdownFilePath(projectId, proposal.path))
        }
    }
    return MarkdownBatchApplyResult(
        results = validations.map { (originalProposal, validation) ->
            validation.fold(
                onSuccess = { validatedProposal ->
                    runCatching {
                        val deliverable = repository.writeMarkdownFile(
                            projectId = projectId,
                            relativePath = validatedProposal.path,
                            markdown = validatedProposal.markdown,
                        )
                        MarkdownFileApplyResult(
                            proposal = originalProposal,
                            status = MarkdownFileApplyStatus.SUCCEEDED,
                            writtenDeliverable = CreatedDeliverable(
                                id = deliverable.id,
                                title = deliverable.title,
                                path = deliverable.relativePath,
                            ),
                        )
                    }.getOrElse { error ->
                        MarkdownFileApplyResult(
                            proposal = originalProposal,
                            status = MarkdownFileApplyStatus.FAILED,
                            errorMessage = error.toUserMessage(),
                        )
                    }
                },
                onFailure = { error ->
                    MarkdownFileApplyResult(
                        proposal = originalProposal,
                        status = MarkdownFileApplyStatus.FAILED,
                        errorMessage = error.toUserMessage(),
                    )
                },
            )
        },
    )
}
```

Replace the existing list-returning adapter method with a temporary compatibility delegate so current chat code still compiles until Task 3:

```kotlin
override suspend fun applyMarkdownUpdates(
    projectId: String,
    updates: List<MarkdownUpdateProposal>,
): List<CreatedDeliverable> {
    val result = applyMarkdownUpdatesWithResults(projectId, updates)
    check(result.failed.isEmpty()) {
        result.failed.joinToString("；") { failed ->
            "${failed.proposal.path}：${failed.errorMessage.orEmpty().ifBlank { "文件写入失败" }}"
        }
    }
    return result.succeeded.mapNotNull { it.writtenDeliverable }
}
```

- [ ] **Step 6: Run focused and repository tests**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.project.ProjectWorkspaceGatewayAdapterTest --tests com.harnessapk.project.ProjectRepositoryTest
```

Expected: both classes pass; the invalid path is one failed result and both valid files exist.

- [ ] **Step 7: Commit the structured write contract**

```bash
git add app/src/main/java/com/harnessapk/session/ProjectWorkspaceGateway.kt app/src/main/java/com/harnessapk/project/FileProjectRepository.kt app/src/main/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapter.kt app/src/test/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapterTest.kt
git commit -m "功能：支持 Markdown 批量写入部分成功"
```

---

### Task 2: Partial Application State and Retry Selection

**Files:**
- Modify: `app/src/main/java/com/harnessapk/session/MarkdownFileChangeModels.kt:5-164`
- Modify: `app/src/test/java/com/harnessapk/session/MarkdownFileChangeControllerTest.kt:1-123`

**Interfaces:**
- Consumes: `MarkdownBatchApplyResult` from Task 1.
- Produces: `MarkdownFileChangeStatus.PARTIALLY_APPLIED`, `MarkdownFileChangeFailure`, `appliedPaths`, `applyFailures`, `markApplyResult`, and `retryableProposals`.

- [ ] **Step 1: Write failing controller tests**

Add to `MarkdownFileChangeControllerTest.kt`:

```kotlin
@Test
fun markApplyResultKeepsSuccessesAndExposesOnlyFailedProposalForRetry() {
    val ready = readyStateWithTwoFiles()
    val result = MarkdownBatchApplyResult(
        listOf(
            successResult(ready.items[0].toProposal(), "requirements/prd.md"),
            failedResult(ready.items[1].toProposal(), "磁盘空间不足"),
        ),
    )

    val partial = controller.markApplyResult(ready, result)

    assertEquals(MarkdownFileChangeStatus.PARTIALLY_APPLIED, partial.draft.status)
    assertEquals(listOf("requirements/prd.md"), partial.appliedPaths)
    assertEquals(listOf("reports/review.md"), partial.applyFailures.map { it.proposal.path })
    assertEquals(listOf("reports/review.md"), controller.retryableProposals(partial).map { it.path })
}

@Test
fun retrySuccessMergesWithPreviousPathsAndFinishesApplied() {
    val ready = readyStateWithTwoFiles()
    val partial = controller.markApplyResult(
        ready,
        MarkdownBatchApplyResult(
            listOf(
                successResult(ready.items[0].toProposal(), "requirements/prd.md"),
                failedResult(ready.items[1].toProposal(), "第一次失败"),
            ),
        ),
    )

    val applied = controller.markApplyResult(
        partial,
        MarkdownBatchApplyResult(
            listOf(successResult(ready.items[1].toProposal(), "reports/review.md")),
        ),
    )

    assertEquals(MarkdownFileChangeStatus.APPLIED, applied.draft.status)
    assertEquals(listOf("requirements/prd.md", "reports/review.md"), applied.appliedPaths)
    assertTrue(applied.applyFailures.isEmpty())
}
```

Add these exact private helpers:

```kotlin
private fun MarkdownFileChangeItem.toProposal() = MarkdownUpdateProposal(
    operation = operation,
    path = path,
    title = title,
    reason = reason,
    markdown = markdown,
)

private fun successResult(proposal: MarkdownUpdateProposal, path: String) = MarkdownFileApplyResult(
    proposal = proposal,
    status = MarkdownFileApplyStatus.SUCCEEDED,
    writtenDeliverable = CreatedDeliverable(path, proposal.title, path),
)

private fun failedResult(proposal: MarkdownUpdateProposal, message: String) = MarkdownFileApplyResult(
    proposal = proposal,
    status = MarkdownFileApplyStatus.FAILED,
    errorMessage = message,
)
```

Add the two-file state helper:

```kotlin
private fun readyStateWithTwoFiles(): MarkdownFileChangeState =
    controller.markReady(
        state = controller.createPlanningDraft("conversation", "project", "user-1"),
        plan = MarkdownUpdatePlan(
            proposals = listOf(
                MarkdownUpdateProposal(
                    operation = MarkdownUpdateOperation.CREATE,
                    path = "requirements/prd.md",
                    title = "PRD",
                    reason = "新建需求文档",
                    markdown = "# PRD",
                ),
                MarkdownUpdateProposal(
                    operation = MarkdownUpdateOperation.CREATE,
                    path = "reports/review.md",
                    title = "Review",
                    reason = "新建复盘文档",
                    markdown = "# Review",
                ),
            ),
        ),
        snapshots = emptyList(),
    )
```

- [ ] **Step 2: Run controller tests and verify RED**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.session.MarkdownFileChangeControllerTest
```

Expected: compilation fails because partial status and result-merging APIs do not exist.

- [ ] **Step 3: Add partial state and in-memory result fields**

```kotlin
enum class MarkdownFileChangeStatus {
    PLANNING,
    READY,
    APPLIED,
    PARTIALLY_APPLIED,
    DISMISSED,
    FAILED,
}

data class MarkdownFileChangeFailure(
    val proposal: MarkdownUpdateProposal,
    val errorMessage: String,
)

data class MarkdownFileChangeState(
    val draft: MarkdownFileChangeDraft,
    val items: List<MarkdownFileChangeItem> = emptyList(),
    val diffs: List<List<MarkdownDiffLine>> = emptyList(),
    val appliedPaths: List<String> = emptyList(),
    val applyFailures: List<MarkdownFileChangeFailure> = emptyList(),
)
```

Make `markPlanning`, `markReady`, and generation-stage `markFailed` reset `appliedPaths` and `applyFailures`.

- [ ] **Step 4: Replace markApplied with cumulative result handling**

```kotlin
fun markApplyResult(
    state: MarkdownFileChangeState,
    result: MarkdownBatchApplyResult,
): MarkdownFileChangeState {
    val appliedPaths = (
        state.appliedPaths + result.succeeded.mapNotNull { it.writtenDeliverable?.path }
    ).distinct()
    val failures = result.failed.map { failed ->
        MarkdownFileChangeFailure(
            proposal = failed.proposal,
            errorMessage = failed.errorMessage.orEmpty().ifBlank { "文件写入失败" },
        )
    }
    val status = when {
        failures.isEmpty() && appliedPaths.isNotEmpty() -> MarkdownFileChangeStatus.APPLIED
        failures.isNotEmpty() && appliedPaths.isNotEmpty() -> MarkdownFileChangeStatus.PARTIALLY_APPLIED
        else -> MarkdownFileChangeStatus.FAILED
    }
    val summary = when (status) {
        MarkdownFileChangeStatus.APPLIED -> "已写入 ${appliedPaths.size} 个 Markdown 文件"
        MarkdownFileChangeStatus.PARTIALLY_APPLIED ->
            "已写入 ${appliedPaths.size} 个，失败 ${failures.size} 个 Markdown 文件"
        MarkdownFileChangeStatus.FAILED -> "${failures.size} 个 Markdown 文件写入失败"
        else -> state.draft.summary
    }
    return state.copy(
        draft = state.draft.copy(status = status, summary = summary, updatedAt = timeProvider()),
        appliedPaths = appliedPaths,
        applyFailures = failures,
    )
}

fun retryableProposals(state: MarkdownFileChangeState): List<MarkdownUpdateProposal> =
    state.applyFailures.map { it.proposal }
```

- [ ] **Step 5: Run controller tests**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.session.MarkdownFileChangeControllerTest
```

Expected: all controller tests pass, including cumulative retry behavior.

- [ ] **Step 6: Commit the state model**

```bash
git add app/src/main/java/com/harnessapk/session/MarkdownFileChangeModels.kt app/src/test/java/com/harnessapk/session/MarkdownFileChangeControllerTest.kt
git commit -m "功能：记录文件变更部分应用状态"
```

---

### Task 3: Chat Application Orchestration and Result Events

**Files:**
- Modify: `app/src/main/java/com/harnessapk/session/ProjectWorkspaceGateway.kt`
- Modify: `app/src/main/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapter.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt:621-855,1394-1444,1749-1769`
- Modify: `app/src/test/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapterTest.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt:630-669`

**Interfaces:**
- Consumes: `MarkdownBatchApplyResult` and `markApplyResult`.
- Produces: the final structured `ProjectWorkspaceGateway.applyMarkdownUpdates(...)`, `markdownWriteBackResultEvent`, `markdownWriteBackResultStatus`, `markdownWriteBackResultError`, failed-only retry, and legacy-review partial-result behavior.

- [ ] **Step 1: Write failing presentation tests**

Add to `ChatUiStateTest.kt`:

```kotlin
@Test
fun partialWriteBackEventAndFeedbackSeparateSuccessFromFailure() {
    val result = MarkdownBatchApplyResult(
        listOf(
            MarkdownFileApplyResult(
                proposal = proposal("docs/ok.md"),
                status = MarkdownFileApplyStatus.SUCCEEDED,
                writtenDeliverable = CreatedDeliverable("docs/ok.md", "OK", "docs/ok.md"),
            ),
            MarkdownFileApplyResult(
                proposal = proposal("docs/fail.md"),
                status = MarkdownFileApplyStatus.FAILED,
                errorMessage = "没有写入权限",
            ),
        ),
    )

    assertEquals(
        "已沉淀到项目：docs/ok.md；写入失败：docs/fail.md（没有写入权限）",
        markdownWriteBackResultEvent(result),
    )
    assertEquals("已写入 1 项 Markdown 更新，1 项失败", markdownWriteBackResultStatus(result))
    assertEquals("1 个文件写入失败，可仅重试失败项", markdownWriteBackResultError(result))
}
```

Add this local helper to `ChatUiStateTest.kt`:

```kotlin
private fun proposal(path: String) = MarkdownUpdateProposal(
    operation = MarkdownUpdateOperation.CREATE,
    path = path,
    title = path.substringAfterLast('/').substringBeforeLast('.'),
    reason = "测试结果反馈",
    markdown = "# Test",
)
```

- [ ] **Step 2: Run chat state tests and verify RED**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest
```

Expected: compilation fails because the structured presentation helpers do not exist.

- [ ] **Step 3: Add pure presentation helpers**

Replace `markdownWriteBackAppliedEvent` with:

```kotlin
internal fun markdownWriteBackResultEvent(result: MarkdownBatchApplyResult): String? {
    val succeeded = result.succeeded
        .mapNotNull { it.writtenDeliverable?.path }
        .filter { it.isNotBlank() }
        .take(MAX_WRITE_BACK_EVENT_PATHS)
    if (succeeded.isEmpty()) return null
    val successText = "已沉淀到项目：${succeeded.joinToString("、")}"
    val failedText = result.failed.take(MAX_WRITE_BACK_EVENT_PATHS).joinToString("、") { failed ->
        "${failed.proposal.path}（${failed.errorMessage.orEmpty().ifBlank { "文件写入失败" }}）"
    }
    return if (failedText.isBlank()) successText else "$successText；写入失败：$failedText"
}

internal fun markdownWriteBackResultStatus(result: MarkdownBatchApplyResult): String = when {
    result.failed.isEmpty() -> "已写入 ${result.succeeded.size} 项 Markdown 更新"
    result.succeeded.isEmpty() -> "${result.failed.size} 项 Markdown 更新写入失败"
    else -> "已写入 ${result.succeeded.size} 项 Markdown 更新，${result.failed.size} 项失败"
}

internal fun markdownWriteBackResultError(result: MarkdownBatchApplyResult): String? = when {
    result.failed.isEmpty() -> null
    result.succeeded.isEmpty() -> "${result.failed.size} 个文件写入失败，可重试失败项"
    else -> "${result.failed.size} 个文件写入失败，可仅重试失败项"
}
```

- [ ] **Step 4: Remove the temporary compatibility contract**

In `ProjectWorkspaceGateway`, `EmptyProjectWorkspaceGateway`, and `ProjectWorkspaceGatewayAdapter`, delete the list-returning `applyMarkdownUpdates`. Rename `applyMarkdownUpdatesWithResults` to the final structured name:

```kotlin
suspend fun applyMarkdownUpdates(
    projectId: String,
    updates: List<MarkdownUpdateProposal>,
): MarkdownBatchApplyResult
```

Update `ProjectWorkspaceGatewayAdapterTest` to call `applyMarkdownUpdates`. At this point both chat call sites will fail compilation until Step 5 replaces them, which is expected inside the same red-green task.

- [ ] **Step 5: Refactor draft application around explicit proposals**

Add this inner function in `ChatScreen`:

```kotlin
fun applyMarkdownFileChangeProposals(
    state: MarkdownFileChangeState,
    proposals: List<MarkdownUpdateProposal>,
) {
    if (proposals.isEmpty()) return
    scope.launch {
        runCatching {
            val result = container.projectWorkspaceGateway.applyMarkdownUpdates(
                projectId = state.draft.projectId,
                updates = proposals,
            )
            markdownWriteBackResultEvent(result)?.let { event ->
                container.chatRepository.insertSystemEvent(conversationId, event)
            }
            result
        }.onSuccess { result ->
            upsertMarkdownFileChangeState(markdownFileChangeController.markApplyResult(state, result))
            if (result.succeeded.isNotEmpty() && selectedProjectId == state.draft.projectId) {
                deliverables = container.projectWorkspaceGateway.listDeliverables(state.draft.projectId)
            }
            pendingMarkdownReview = null
            pendingMarkdownReviewDraftId = null
            retainedReviewIndexes = emptySet()
            sessionStatus = markdownWriteBackResultStatus(result)
            errorText = markdownWriteBackResultError(result)
        }.onFailure { error ->
            val feedback = markdownWriteBackFailureFeedback(error)
            errorText = feedback.errorText
            sessionStatus = feedback.statusText
        }
    }
}

fun retryFailedMarkdownFileChanges(state: MarkdownFileChangeState) {
    applyMarkdownFileChangeProposals(
        state = state,
        proposals = markdownFileChangeController.retryableProposals(state),
    )
}
```

Replace `applyMarkdownFileChangeState` with the complete retained-index wrapper:

```kotlin
fun applyMarkdownFileChangeState(
    state: MarkdownFileChangeState,
    retainedIndexes: Set<Int>,
) {
    val retained = state.items
        .filterIndexed { index, _ -> index in retainedIndexes }
        .map { item ->
            MarkdownUpdateProposal(
                operation = item.operation,
                path = item.path,
                title = item.title,
                reason = item.reason,
                markdown = item.markdown,
            )
        }
    if (retained.isEmpty()) {
        upsertMarkdownFileChangeState(markdownFileChangeController.dismiss(state))
        pendingMarkdownReview = null
        pendingMarkdownReviewDraftId = null
        retainedReviewIndexes = emptySet()
        return
    }
    applyMarkdownFileChangeProposals(state, retained)
}
```

- [ ] **Step 6: Adapt the legacy review path**

Replace `applyRetainedMarkdownUpdates` with this complete implementation so the legacy review path also preserves partial successes:

```kotlin
fun applyRetainedMarkdownUpdates(
    review: MarkdownUpdateReviewState,
    retainedIndexes: Set<Int>,
    draftId: String?,
) {
    if (draftId != null) {
        markdownFileChangeStates.firstOrNull { it.draft.id == draftId }?.let { state ->
            applyMarkdownFileChangeState(state, retainedIndexes)
        }
        return
    }
    val projectId = selectedProjectId
    if (projectId.isNullOrBlank()) {
        errorText = "请先选择项目"
        return
    }
    val retained = review.proposals.filterIndexed { index, _ -> index in retainedIndexes }
    if (retained.isEmpty()) {
        pendingMarkdownReview = null
        pendingMarkdownReviewDraftId = null
        retainedReviewIndexes = emptySet()
        sessionStatus = "已撤回全部 Markdown 更新"
        errorText = null
        return
    }
    scope.launch {
        runCatching {
            val result = container.projectWorkspaceGateway.applyMarkdownUpdates(projectId, retained)
            markdownWriteBackResultEvent(result)?.let { event ->
                container.chatRepository.insertSystemEvent(conversationId, event)
            }
            result
        }.onSuccess { result ->
            if (result.succeeded.isNotEmpty()) {
                deliverables = container.projectWorkspaceGateway.listDeliverables(projectId)
            }
            val failedPaths = result.failed.map { it.proposal.path }.toSet()
            if (failedPaths.isEmpty()) {
                pendingMarkdownReview = null
                pendingMarkdownReviewDraftId = null
                retainedReviewIndexes = emptySet()
            } else {
                pendingMarkdownReview = review
                retainedReviewIndexes = review.proposals.mapIndexedNotNull { index, proposal ->
                    index.takeIf { proposal.path in failedPaths }
                }.toSet()
            }
            sessionStatus = markdownWriteBackResultStatus(result)
            errorText = markdownWriteBackResultError(result)
        }.onFailure { error ->
            val feedback = markdownWriteBackFailureFeedback(error)
            errorText = feedback.errorText
            sessionStatus = feedback.statusText
        }
    }
}
```

- [ ] **Step 7: Run chat, controller, and gateway tests**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest --tests com.harnessapk.session.MarkdownFileChangeControllerTest --tests com.harnessapk.project.ProjectWorkspaceGatewayAdapterTest
```

Expected: all three classes pass and no old `List<CreatedDeliverable>` assumption remains.

- [ ] **Step 8: Commit chat orchestration**

```bash
git add app/src/main/java/com/harnessapk/session/ProjectWorkspaceGateway.kt app/src/main/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapter.kt app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/test/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapterTest.kt app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt
git commit -m "功能：完善文件变更写入结果处理"
```

---

### Task 4: One-Shot Project Workbench Navigation

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt:1-18`
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt:98-175,449-470`
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt:79-248`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt:152-160,1023-1041`
- Modify: `app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt`

**Interfaces:**
- Consumes: applied paths from Task 2.
- Produces: `ProjectWorkbenchDestination`, `ProjectWorkbenchTarget`, `projectWorkbenchTab`, chat navigation callbacks, and one-shot target consumption.

- [ ] **Step 1: Write failing target tests**

Add to `ProjectSessionLaunchUiStateTest.kt`:

```kotlin
@Test
fun workbenchTargetsMapToFolderAndGitTabs() {
    assertEquals(ProjectWorkbenchTab.FOLDER, projectWorkbenchTab(ProjectWorkbenchDestination.FILES))
    assertEquals(ProjectWorkbenchTab.GIT, projectWorkbenchTab(ProjectWorkbenchDestination.GIT))
}
```

Add to `HarnessApkAppStateTest.kt`:

```kotlin
@Test
fun workbenchTargetCarriesProjectPathAndRequestKey() {
    val target = projectWorkbenchTarget(
        projectId = "project-1",
        destination = ProjectWorkbenchDestination.FILES,
        selectedPath = "requirements/prd.md",
        requestKey = 7,
    )

    assertEquals("project-1", target.projectId)
    assertEquals(ProjectWorkbenchDestination.FILES, target.destination)
    assertEquals("requirements/prd.md", target.selectedPath)
    assertEquals(7, target.requestKey)
}
```

Add these imports to `HarnessApkAppStateTest.kt`:

```kotlin
import com.harnessapk.ui.project.ProjectWorkbenchDestination
```

- [ ] **Step 2: Run target tests and verify RED**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.HarnessApkAppStateTest --tests com.harnessapk.ui.project.ProjectSessionLaunchUiStateTest
```

Expected: compilation fails because target types and mapping functions do not exist.

- [ ] **Step 3: Add target types and mapping**

Add near `ProjectWorkbenchTab` in `ProjectUiState.kt`:

```kotlin
internal enum class ProjectWorkbenchDestination { FILES, GIT }

internal data class ProjectWorkbenchTarget(
    val projectId: String,
    val destination: ProjectWorkbenchDestination,
    val selectedPath: String?,
    val requestKey: Int,
)

internal fun projectWorkbenchTab(destination: ProjectWorkbenchDestination): ProjectWorkbenchTab =
    when (destination) {
        ProjectWorkbenchDestination.FILES -> ProjectWorkbenchTab.FOLDER
        ProjectWorkbenchDestination.GIT -> ProjectWorkbenchTab.GIT
    }
```

Add in `HarnessApkApp.kt`:

```kotlin
internal fun projectWorkbenchTarget(
    projectId: String,
    destination: ProjectWorkbenchDestination,
    selectedPath: String?,
    requestKey: Int,
): ProjectWorkbenchTarget = ProjectWorkbenchTarget(
    projectId = projectId,
    destination = destination,
    selectedPath = selectedPath,
    requestKey = requestKey,
)
```

- [ ] **Step 4: Route ChatScreen intents through HarnessApkApp**

Extend `ChatScreen` with default callbacks:

```kotlin
onOpenProjectFiles: (projectId: String, selectedPath: String?) -> Unit = { _, _ -> },
onOpenProjectGit: (projectId: String) -> Unit = {},
```

Add remembered target state in `HarnessApkApp`:

```kotlin
var workbenchTarget by remember { mutableStateOf<ProjectWorkbenchTarget?>(null) }
var workbenchRequestKey by rememberSaveable { mutableStateOf(0) }

fun openWorkbench(
    projectId: String,
    destination: ProjectWorkbenchDestination,
    selectedPath: String? = null,
) {
    workbenchRequestKey += 1
    workbenchTarget = projectWorkbenchTarget(
        projectId,
        destination,
        selectedPath,
        workbenchRequestKey,
    )
    mainMode = MainMode.PROJECT
    navController.popBackStack(Routes.Conversations, inclusive = false)
}
```

Add these imports to `HarnessApkApp.kt`:

```kotlin
import com.harnessapk.ui.project.ProjectWorkbenchDestination
import com.harnessapk.ui.project.ProjectWorkbenchTarget
```

Pass callbacks to `ChatScreen`:

```kotlin
onOpenProjectFiles = { projectId, path ->
    openWorkbench(projectId, ProjectWorkbenchDestination.FILES, path)
},
onOpenProjectGit = { projectId ->
    openWorkbench(projectId, ProjectWorkbenchDestination.GIT)
},
```

- [ ] **Step 5: Consume the target once in ProjectScreen**

Extend `ProjectScreen`:

```kotlin
workbenchTarget: ProjectWorkbenchTarget? = null,
onWorkbenchTargetConsumed: (requestKey: Int) -> Unit = {},
```

Track `projectsLoaded` and replace the initial loading effect so the target is never consumed before projects are available:

```kotlin
var projectsLoaded by remember { mutableStateOf(false) }

LaunchedEffect(Unit) {
    projects = withContext(container.dispatchers.io) {
        container.projectRepository.listProjects()
    }
    projectsLoaded = true
    selectedProjectId = selectedProjectId ?: projects.firstOrNull()?.id
    onCurrentProjectNameChange(projects.firstOrNull { it.id == selectedProjectId }?.name)
}
```

Replace the deliverable refresh helper with an explicit-target version:

```kotlin
var projectsLoaded by remember { mutableStateOf(false) }

fun refreshDeliverables(
    projectId: String = selectedProjectId ?: return,
    preferredPath: String? = null,
    query: String = searchQuery,
    filter: ProjectArtifactFilter = artifactFilter,
) {
    scope.launch {
        deliverables = withContext(container.dispatchers.io) {
            if (query.isBlank()) {
                container.projectRepository.listDeliverables(projectId)
            } else {
                container.projectRepository.searchDeliverables(projectId, query)
            }
        }
        val filtered = filterProjectArtifacts(deliverables, filter)
        selectedDeliverableId = when {
            preferredPath != null && filtered.any { it.id == preferredPath } -> preferredPath
            preferredPath != null -> {
                statusText = "文件已写入，请刷新后查看"
                filtered.firstOrNull()?.id
            }
            selectedDeliverableId != null && filtered.any { it.id == selectedDeliverableId } -> selectedDeliverableId
            else -> filtered.firstOrNull()?.id
        }
    }
}
```

Change the first line and initial project lookup of `refreshGitState` to accept an explicit project while retaining the existing body:

```diff
-fun refreshGitState() {
-    val project = selectedProject
+fun refreshGitState(project: Project? = selectedProject) {
     if (project == null) {
```

Add the target effect:

```kotlin
LaunchedEffect(workbenchTarget?.requestKey, projectsLoaded) {
    val target = workbenchTarget ?: return@LaunchedEffect
    if (!projectsLoaded) return@LaunchedEffect
    val project = projects.firstOrNull { it.id == target.projectId }
    if (project == null) {
        statusText = "项目不存在或已被删除"
        onWorkbenchTargetConsumed(target.requestKey)
        return@LaunchedEffect
    }

    selectedProjectId = project.id
    selectedTab = projectWorkbenchTab(target.destination)
    searchQuery = ""
    artifactFilter = ProjectArtifactFilter.ALL
    collapsedDirectoryPaths = emptySet()
    if (target.destination == ProjectWorkbenchDestination.FILES) {
        refreshDeliverables(
            projectId = project.id,
            preferredPath = target.selectedPath,
            query = "",
            filter = ProjectArtifactFilter.ALL,
        )
    } else {
        refreshGitState(project)
    }
    onWorkbenchTargetConsumed(target.requestKey)
}
```

Pass and consume the matching target in `HarnessApkApp`:

```kotlin
workbenchTarget = workbenchTarget,
onWorkbenchTargetConsumed = { requestKey ->
    if (workbenchTarget?.requestKey == requestKey) workbenchTarget = null
},
```

- [ ] **Step 6: Wire card intents without rendering them yet**

At the card call site:

```kotlin
onOpenFiles = {
    onOpenProjectFiles(state.draft.projectId, state.appliedPaths.firstOrNull())
},
onOpenGit = { onOpenProjectGit(state.draft.projectId) },
onRetryFailed = { retryFailedMarkdownFileChanges(state) },
```

Change only the existing card declaration; retain its current body for Task 5:

```diff
 @Composable
-private fun MarkdownFileChangeCard(
+internal fun MarkdownFileChangeCard(
     state: MarkdownFileChangeState,
     onShowDiff: () -> Unit,
     onApply: () -> Unit,
     onRetry: () -> Unit,
+    onRetryFailed: () -> Unit,
     onDismiss: () -> Unit,
+    onOpenFiles: () -> Unit,
+    onOpenGit: () -> Unit,
 ) {
```

- [ ] **Step 7: Run navigation and compile tests**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.HarnessApkAppStateTest --tests com.harnessapk.ui.project.ProjectSessionLaunchUiStateTest --tests com.harnessapk.ui.chat.ChatUiStateTest
```

Expected: all targeted tests pass and debug Kotlin compilation succeeds.

- [ ] **Step 8: Commit one-shot navigation**

```bash
git add app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/test/java/com/harnessapk/ui/HarnessApkAppStateTest.kt app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt
git commit -m "功能：打通聊天到项目工作台跳转"
```

---

### Task 5: Applied and Partially Applied Result Card

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt:1429-1438,1875-1975`
- Modify: `app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt:648-669`
- Create: `app/src/androidTest/java/com/harnessapk/ui/chat/MarkdownFileChangeCardTest.kt`

**Interfaces:**
- Consumes: state fields and callbacks from Tasks 2-4.
- Produces: accessible applied and partial card states with paths, navigation actions, and failed-only retry.

- [ ] **Step 1: Write failing title and Compose UI tests**

Add to the existing JVM card-title test:

```kotlin
assertEquals(
    "部分文件已写入",
    markdownFileChangeCardTitle(MarkdownFileChangeStatus.PARTIALLY_APPLIED, 2),
)
```

Create `MarkdownFileChangeCardTest.kt` with two tests:

```kotlin
package com.harnessapk.ui.chat

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.harnessapk.session.MarkdownFileChangeDraft
import com.harnessapk.session.MarkdownFileChangeFailure
import com.harnessapk.session.MarkdownFileChangeItem
import com.harnessapk.session.MarkdownFileChangeState
import com.harnessapk.session.MarkdownFileChangeStatus
import com.harnessapk.session.MarkdownUpdateOperation
import com.harnessapk.session.MarkdownUpdateProposal
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class MarkdownFileChangeCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appliedCardExposesFilesAndGitActions() {
        composeRule.setContent {
            HarnessApkTheme {
                MarkdownFileChangeCard(
                    state = state(
                        status = MarkdownFileChangeStatus.APPLIED,
                        appliedPaths = listOf("requirements/prd.md"),
                    ),
                    onShowDiff = {},
                    onApply = {},
                    onRetry = {},
                    onRetryFailed = {},
                    onDismiss = {},
                    onOpenFiles = {},
                    onOpenGit = {},
                )
            }
        }
        composeRule.onNodeWithText("requirements/prd.md").assertIsDisplayed()
        composeRule.onNodeWithText("查看文件").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("查看 Git 变更").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun partialCardShowsFailureAndRetriesOnlyFailedItems() {
        composeRule.setContent {
            HarnessApkTheme {
                MarkdownFileChangeCard(
                    state = state(
                        status = MarkdownFileChangeStatus.PARTIALLY_APPLIED,
                        appliedPaths = listOf("requirements/prd.md"),
                        failures = listOf(
                            MarkdownFileChangeFailure(
                                proposal = proposal("reports/review.md"),
                                errorMessage = "没有写入权限",
                            ),
                        ),
                    ),
                    onShowDiff = {},
                    onApply = {},
                    onRetry = {},
                    onRetryFailed = {},
                    onDismiss = {},
                    onOpenFiles = {},
                    onOpenGit = {},
                )
            }
        }
        composeRule.onNodeWithText("部分文件已写入").assertIsDisplayed()
        composeRule.onNodeWithText("reports/review.md").assertIsDisplayed()
        composeRule.onNodeWithText("没有写入权限").assertIsDisplayed()
        composeRule.onNodeWithText("仅重试失败项").assertIsDisplayed().assertHasClickAction()
    }
}
```

Add the following local helpers after the tests:

```kotlin
private fun proposal(path: String) = MarkdownUpdateProposal(
    operation = MarkdownUpdateOperation.CREATE,
    path = path,
    title = path.substringAfterLast('/').substringBeforeLast('.'),
    reason = "测试",
    markdown = "# Test",
)

private fun state(
    status: MarkdownFileChangeStatus,
    appliedPaths: List<String>,
    failures: List<MarkdownFileChangeFailure> = emptyList(),
): MarkdownFileChangeState {
    val proposals = (appliedPaths.map(::proposal) + failures.map { it.proposal })
        .distinctBy { it.path }
    return MarkdownFileChangeState(
        draft = MarkdownFileChangeDraft(
            id = "draft",
            conversationId = "conversation",
            projectId = "project",
            sourceUserMessageId = "user",
            status = status,
            summary = markdownFileChangeCardTitle(status, proposals.size),
            createdAt = 1L,
            updatedAt = 1L,
        ),
        items = proposals.map { proposal ->
            MarkdownFileChangeItem(
                draftId = "draft",
                operation = proposal.operation,
                path = proposal.path,
                title = proposal.title,
                reason = proposal.reason,
                markdown = proposal.markdown,
                addedLineCount = 1,
                removedLineCount = 0,
                retained = true,
            )
        },
        appliedPaths = appliedPaths,
        applyFailures = failures,
    )
}
```

- [ ] **Step 2: Run UI test compilation and verify RED**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.chat.MarkdownFileChangeCardTest
```

Expected: compilation fails because the card is private and does not render result actions or partial state.

- [ ] **Step 3: Update title handling and expose the card to module tests**

Change the card to `internal fun MarkdownFileChangeCard`. Update the title helper:

```kotlin
internal fun markdownFileChangeCardTitle(
    status: MarkdownFileChangeStatus,
    itemCount: Int,
): String = when (status) {
    MarkdownFileChangeStatus.PLANNING -> "正在生成 Markdown 文件变更..."
    MarkdownFileChangeStatus.READY -> "已生成 $itemCount 个 Markdown 文件变更"
    MarkdownFileChangeStatus.APPLIED -> "已写入项目"
    MarkdownFileChangeStatus.PARTIALLY_APPLIED -> "部分文件已写入"
    MarkdownFileChangeStatus.DISMISSED -> "已撤回 Markdown 文件变更"
    MarkdownFileChangeStatus.FAILED -> "Markdown 文件变更失败"
}
```

Replace the existing header `Text` with a stable title plus optional detail text, so controller count summaries do not hide `PARTIALLY_APPLIED`:

```kotlin
val cardTitle = markdownFileChangeCardTitle(state.draft.status, state.items.size)
Text(
    text = cardTitle,
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.SemiBold,
)
if (state.draft.summary.isNotBlank() && state.draft.summary != cardTitle) {
    Text(
        text = state.draft.summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

- [ ] **Step 4: Render result lists and accessible actions**

Add:

```kotlin
@Composable
private fun AppliedPathList(paths: List<String>) {
    val visible = paths.take(3)
    visible.forEach { path ->
        Text(path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
    if (paths.size > visible.size) {
        Text(
            "另有 ${paths.size - visible.size} 个文件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailedPathList(failures: List<MarkdownFileChangeFailure>) {
    failures.take(3).forEach { failure ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(failure.proposal.path, style = MaterialTheme.typography.bodyMedium)
            Text(
                failure.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
```

Add these imports:

```kotlin
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import com.harnessapk.ui.theme.HarnessSpacing
```

Replace terminal card branches with:

```kotlin
MarkdownFileChangeStatus.APPLIED -> {
    AppliedPathList(state.appliedPaths)
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = HarnessSpacing.minimumTouchTarget),
        onClick = onOpenFiles,
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null)
        Text("查看文件", modifier = Modifier.padding(start = 8.dp))
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = HarnessSpacing.minimumTouchTarget),
        onClick = onOpenGit,
    ) {
        Icon(Icons.Outlined.AccountTree, contentDescription = null)
        Text("查看 Git 变更", modifier = Modifier.padding(start = 8.dp))
    }
}
MarkdownFileChangeStatus.PARTIALLY_APPLIED -> {
    Text("已写入", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    AppliedPathList(state.appliedPaths)
    Text("写入失败", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    FailedPathList(state.applyFailures)
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = HarnessSpacing.minimumTouchTarget),
        onClick = onOpenFiles,
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null)
        Text("查看文件", modifier = Modifier.padding(start = 8.dp))
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = HarnessSpacing.minimumTouchTarget),
        onClick = onOpenGit,
    ) {
        Icon(Icons.Outlined.AccountTree, contentDescription = null)
        Text("查看 Git 变更", modifier = Modifier.padding(start = 8.dp))
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = HarnessSpacing.minimumTouchTarget),
        onClick = onRetryFailed,
    ) {
        Icon(Icons.Outlined.Refresh, contentDescription = null)
        Text("仅重试失败项", modifier = Modifier.padding(start = 8.dp))
    }
}
MarkdownFileChangeStatus.FAILED -> {
    if (state.applyFailures.isNotEmpty()) FailedPathList(state.applyFailures)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = if (state.applyFailures.isEmpty()) onRetry else onRetryFailed) {
            Text(if (state.applyFailures.isEmpty()) "重试" else "重试失败项")
        }
        TextButton(onClick = onDismiss) { Text("撤回") }
    }
}
MarkdownFileChangeStatus.DISMISSED -> Unit
```

- [ ] **Step 5: Run card JVM and device tests**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.chat.MarkdownFileChangeCardTest
```

Expected: JVM tests and both Compose tests pass.

- [ ] **Step 6: Commit result card UI**

```bash
git add app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt app/src/androidTest/java/com/harnessapk/ui/chat/MarkdownFileChangeCardTest.kt
git commit -m "优化：展示文件变更写入结果操作"
```

---

### Task 6: Full Regression and Device Acceptance

**Files:**
- Verify only; modify production files only if a failing check identifies a phase-one defect.

**Interfaces:**
- Consumes: completed Tasks 1-5.
- Produces: fresh automated, build, install, and visual evidence.

- [ ] **Step 1: Run the complete JVM suite and Debug build**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug
```

Expected: `BUILD SUCCESSFUL`, zero failed tests.

- [ ] **Step 2: Run focused Compose device tests**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.chat.MarkdownFileChangeCardTest,com.harnessapk.ui.components.WarmComponentsTest
```

Expected: all focused device tests pass.

- [ ] **Step 3: Install and launch the final Debug APK**

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew :app:installDebug --console=plain
/Users/tony/Library/Android/sdk/platform-tools/adb devices
/Users/tony/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am start -n com.harnessapk.debug/com.harnessapk.MainActivity
```

Expected: installation succeeds and `MainActivity` starts on the connected emulator.

- [ ] **Step 4: Exercise the success loop**

Using a configured test Provider:

1. Create or select a project and open a project conversation.
2. Generate at least two Markdown changes.
3. Review and apply them.
4. Confirm the card shows actual paths.
5. Tap “查看文件”; confirm folder refresh and first-path selection.
6. Return to chat and tap “查看 Git 变更”; confirm Git refresh.

Expected: no silent write and no automatic Git commit/push.

- [ ] **Step 5: Exercise partial failure and failed-only retry**

Use fault injection or an invalid second path while first and third paths are valid. Verify successful files remain, success/failure groups are accurate, both view actions remain available, and failed-only retry does not rewrite successful paths.

Expected: a later successful retry transitions to `APPLIED` with cumulative unique paths.

- [ ] **Step 6: Capture visual evidence**

```bash
mkdir -p /tmp/harness-apk-ui
/Users/tony/Library/Android/sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p > /tmp/harness-apk-ui/file-change-applied-light.png
/Users/tony/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell cmd uimode night yes
/Users/tony/Library/Android/sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p > /tmp/harness-apk-ui/file-change-applied-dark.png
/Users/tony/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell settings put system font_scale 1.3
/Users/tony/Library/Android/sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p > /tmp/harness-apk-ui/file-change-partial-large-font.png
```

Inspect every screenshot. Expected: no overlap, safe path truncation, readable action labels, and error red only on failures.

- [ ] **Step 7: Restore emulator defaults and verify repository state**

```bash
/Users/tony/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell settings put system font_scale 1.0
/Users/tony/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell cmd uimode night no
git diff --check
git status --short --branch
```

Expected: normal font scale and light mode are restored; `git diff --check` passes; no required task files remain uncommitted. Do not push unless explicitly requested.
