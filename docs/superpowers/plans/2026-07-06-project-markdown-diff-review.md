# Project Markdown Diff Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add confirmation-first Markdown auto-management where project chat generates multi-file Markdown update proposals, users review diffs, keep or withdraw each proposal, and only kept proposals are written.

**Architecture:** Introduce a session-layer update planner that asks the active OpenAI-compatible model for JSON proposals, a shared line diff utility for review, and repository/gateway write APIs for safe relative Markdown paths. Chat UI keeps only project selection and prompt settings; Markdown relationships become many-to-many through proposals rather than single-document binding.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization JSON, existing OpenAI-compatible client, file-backed project repository, Gradle Android unit tests.

## Global Constraints

- Do not stage or revert unrelated dirty files.
- Preserve existing web-search/settings changes already present in the worktree.
- Use Chinese commit messages.
- Do not push unless explicitly requested.
- Follow TDD for behavior changes.
- Markdown writes must stay inside the project directory.
- User-facing auto-management must support diff review, per-item keep, and per-item withdraw before writing.

---

### Task 1: Shared Markdown Renderer

**Files:**
- Move: `app/src/main/java/com/harnessapk/ui/chat/MarkdownMessage.kt`
- Move: `app/src/main/java/com/harnessapk/ui/chat/MarkdownMessageParser.kt`
- Modify imports in `ChatScreen.kt`, `ProjectScreen.kt`, and markdown parser tests.

**Interfaces:**
- Produces: `com.harnessapk.ui.markdown.MarkdownMessage(markdown, modifier, textColor)`

- [ ] **Step 1: Move package declarations to `com.harnessapk.ui.markdown`.**
- [ ] **Step 2: Update imports in chat, project, and tests.**
- [ ] **Step 3: Run markdown parser tests.**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ANDROID_SDK_ROOT=/Users/tony/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.harnessapk.ui.markdown.MarkdownMessageParserTest --console=plain`

### Task 2: Markdown Update Models, Parser, and Diff

**Files:**
- Create: `app/src/main/java/com/harnessapk/session/MarkdownUpdateModels.kt`
- Create: `app/src/test/java/com/harnessapk/session/MarkdownUpdatePlannerTest.kt`

**Interfaces:**
- Produces: `MarkdownUpdateProposal`, `MarkdownUpdatePlan`, `MarkdownDiffLine`, `parseMarkdownUpdatePlanResponse`, `buildMarkdownDiff`

- [ ] **Step 1: Write failing parser and diff tests.**
- [ ] **Step 2: Implement JSON extraction, operation normalization, and LCS line diff.**
- [ ] **Step 3: Run targeted tests.**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ANDROID_SDK_ROOT=/Users/tony/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.harnessapk.session.MarkdownUpdatePlannerTest --console=plain`

### Task 3: Safe Multi-File Project Writes

**Files:**
- Modify: `FileProjectRepository.kt`
- Modify: `ProjectWorkspaceGateway.kt`
- Modify: `ProjectWorkspaceGatewayAdapter.kt`
- Modify: `ProjectRepositoryTest.kt`

**Interfaces:**
- Produces: `writeMarkdownFile(projectId: String, relativePath: String, markdown: String): ProjectDeliverable`
- Produces: `applyMarkdownUpdates(projectId: String, updates: List<MarkdownUpdateProposal>): List<CreatedDeliverable>`

- [ ] **Step 1: Write failing repository test for updating an existing Markdown and creating a new Markdown by relative path.**
- [ ] **Step 2: Implement repository validation and gateway adapter.**
- [ ] **Step 3: Run project repository tests.**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ANDROID_SDK_ROOT=/Users/tony/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.harnessapk.project.ProjectRepositoryTest --console=plain`

### Task 4: Chat UI Removes Binding and Adds Diff Review

**Files:**
- Modify: `ChatScreen.kt`
- Modify: `ProjectScreen.kt`
- Modify: chat UI-state tests if present.

**Interfaces:**
- Consumes: `MarkdownUpdatePlannerUseCase.plan(...)`
- Consumes: `ProjectWorkspaceGateway.applyMarkdownUpdates(...)`
- Produces: review dialog with per-item 保留/撤回 state.

- [ ] **Step 1: Remove Markdown selector from session config and binding bar.**
- [ ] **Step 2: Change assistant action text to “沉淀到项目”.**
- [ ] **Step 3: On click, generate update plan, build diffs, show review dialog.**
- [ ] **Step 4: Write only kept proposals on confirmation; do nothing if all proposals are withdrawn.**

### Task 5: Verification and Diff Audit

**Files:**
- All changed files in this plan only.

- [ ] **Step 1: Run targeted tests.**
- [ ] **Step 2: Run full `:app:testDebugUnitTest`.**
- [ ] **Step 3: Run `:app:assembleDebug`.**
- [ ] **Step 4: Review `git diff` for product risks: no single Markdown binding, no silent overwrite, no unrelated web-search/settings staging.**
- [ ] **Step 5: Stage only this plan’s files and commit with `功能：支持项目 Markdown 更新审核`.**
