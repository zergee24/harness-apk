# Project Session-Driven Markdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make project work session-driven by removing manual document creation/classification from the project page and allowing chat write-back to auto-create Markdown when no document is bound.

**Architecture:** Keep file-backed Markdown storage, but move user intent to chat. Project UI becomes a workspace browser and launcher; chat write-back chooses between updating a bound Markdown file and creating a session Markdown file.

**Tech Stack:** Kotlin, Jetpack Compose, coroutine-based repository APIs, Gradle Android unit tests.

## Global Constraints

- Do not stage or revert unrelated dirty files.
- Use Chinese commit messages.
- Do not push unless explicitly requested.
- Follow TDD for behavior changes.
- Keep Markdown as the primary persisted artifact format.

---

### Task 1: Write-Back Eligibility

**Files:**
- Modify: `app/src/test/java/com/harnessapk/session/SessionContextBuilderTest.kt`
- Modify: `app/src/main/java/com/harnessapk/session/SessionContextBuilder.kt`

**Interfaces:**
- Consumes: `canWriteBackMarkdown(projectId: String?, deliverableId: String?, markdown: String)`
- Produces: Updated function where `deliverableId` may be null.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun writeBackRequiresProjectAndAssistantMarkdownButNotExistingDeliverable() {
    assertTrue(canWriteBackMarkdown("project", "deliverable", "# 建议"))
    assertTrue(canWriteBackMarkdown("project", null, "# 新沉淀"))
    assertFalse(canWriteBackMarkdown(null, null, "# 建议"))
    assertFalse(canWriteBackMarkdown("project", null, "  "))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ANDROID_SDK_ROOT=/Users/tony/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.harnessapk.session.SessionContextBuilderTest --console=plain`
Expected: FAIL because null deliverable currently blocks write-back.

- [ ] **Step 3: Implement minimal change**

```kotlin
fun canWriteBackMarkdown(
    projectId: String?,
    deliverableId: String?,
    markdown: String,
): Boolean =
    !projectId.isNullOrBlank() && markdown.isNotBlank()
```

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command. Expected: PASS unless unrelated test compilation errors exist.

### Task 2: Auto-Create Markdown From Chat Write-Back

**Files:**
- Modify: `app/src/main/java/com/harnessapk/session/ProjectWorkspaceGateway.kt`
- Modify: `app/src/main/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapter.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- Modify: `app/src/test/java/com/harnessapk/project/ProjectRepositoryTest.kt`

**Interfaces:**
- Consumes: `FileProjectRepository.saveSessionSummary(projectId, summary): ProjectDeliverable`
- Produces: `ProjectWorkspaceGateway.saveSessionSummary(projectId, sessionSummary): CreatedDeliverable`

- [ ] **Step 1: Write the failing repository expectation**

```kotlin
val session = repository.saveSessionSummary(
    projectId = project.id,
    summary = ProjectSessionSummary(
        id = "conversation-1",
        title = "会话写回",
        markdown = "# 会话写回\n\n完整 Markdown 内容。",
    ),
)

assertEquals(DeliverableTemplate.SESSION, session.template)
assertTrue(session.relativePath.startsWith("sessions/conversation-1-"))
assertTrue(repository.readDeliverable(project.id, session.id).contains("完整 Markdown 内容"))
```

- [ ] **Step 2: Run test to verify current behavior**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ANDROID_SDK_ROOT=/Users/tony/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.harnessapk.project.ProjectRepositoryTest --console=plain`
Expected: PASS for repository behavior; gateway/chat API still needs implementation.

- [ ] **Step 3: Return created deliverable from gateway**

Change gateway and adapter so chat can learn the generated Markdown id/title/path.

- [ ] **Step 4: Update chat write-back**

When `selectedDeliverableId` is non-null, write to it. When it is null, call `saveSessionSummary`, refresh deliverables, set `selectedDeliverableId` to the returned id, and show a status that Markdown was generated.

### Task 3: Project Page Removes Manual Document Creation

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt`

**Interfaces:**
- Produces: `projectDeliverableSupportingText(deliverable: ProjectDeliverable): String`

- [ ] **Step 1: Write the failing UI-state test**

```kotlin
@Test
fun projectDeliverableSupportingTextHidesTemplateClassification() {
    val deliverable = ProjectDeliverable(
        id = "sessions/conversation-1.md",
        title = "会话写回",
        relativePath = "sessions/conversation-1.md",
        template = DeliverableTemplate.REQUIREMENT,
        updatedAt = 0L,
    )

    assertEquals("sessions/conversation-1.md", projectDeliverableSupportingText(deliverable))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ANDROID_SDK_ROOT=/Users/tony/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.harnessapk.ui.project.ProjectSessionLaunchUiStateTest --console=plain`
Expected: FAIL because helper does not exist yet.

- [ ] **Step 3: Implement project UI change**

Remove `NewDeliverableDialog`, remove the “新建文档” button, remove template chips/imports, and use `projectDeliverableSupportingText` in `DeliverableRow`.

- [ ] **Step 4: Verify**

Run targeted tests, then `./gradlew :app:assembleDebug --console=plain`.

### Task 4: Commit Scoped Changes

**Files:**
- Stage only files modified by this plan.

- [ ] **Step 1: Inspect status**

Run: `git status --short --branch`

- [ ] **Step 2: Check staged diff**

Run: `git diff --cached --check`

- [ ] **Step 3: Commit**

Run: `git commit -m "功能：项目会话驱动 Markdown 沉淀"`
