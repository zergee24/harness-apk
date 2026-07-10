# Warm Accessible Core UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the approved warm, accessible Material 3 visual system to the shared theme, home conversation experience, chat screen, and project workbench without changing business behavior.

**Architecture:** Keep the existing Repository and screen state flows unchanged. Centralize Material 3 theme tokens in `ui/theme`, add a small set of stateless shared Compose components in `ui/components`, and update the three target screens to consume those primitives. Put new presentation decisions behind pure functions where a JVM test is practical, and use Compose instrumentation tests plus emulator screenshots for rendered behavior.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, JUnit 4, Compose UI Test, Gradle, Android Emulator, adb.

## Global Constraints

- Use `#9F5167` with `#FFF9F7` in light mode and `#FFB3AA` with `#5F1410` in dark mode for primary actions.
- Use `#FAF7F6` / `#FFFDFC` / `#211A19` for light background, surface, and text.
- Use `#17181A` / `#202124` / `#F3EDEC` for dark background, surface, and text.
- Body text defaults to about `17sp`; supporting text must not be smaller than `14sp`.
- Primary touch targets must be at least `48dp`; important input and action surfaces should be `52-56dp`.
- Preserve routes, navigation labels, Repository calls, data models, and business state transitions.
- Do not add a third-party UI framework, decorative gradients, outer glows, continuous animation, or nested cards.
- Keep the system light and dark mode behavior and verify both modes.
- Use Chinese wording for commits and stage only files from the active task.

---

### Task 1: Warm Material 3 Theme Tokens

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/theme/Theme.kt`
- Create: `app/src/test/java/com/harnessapk/ui/theme/ThemeTest.kt`

**Interfaces:**
- Consumes: Existing `HarnessApkTheme(content: @Composable () -> Unit)` entry point.
- Produces: `warmLightColorScheme()`, `warmDarkColorScheme()`, `HarnessTypography`, `HarnessShapes`, and `HarnessSpacing` for later UI tasks.

- [ ] **Step 1: Write the failing theme token test**

```kotlin
package com.harnessapk.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {
    @Test
    fun lightThemeUsesApprovedWarmAccessibleTokens() {
        val scheme = warmLightColorScheme()

        assertEquals(Color(0xFF9F5167), scheme.primary)
        assertEquals(Color(0xFFFFF9F7), scheme.onPrimary)
        assertEquals(Color(0xFFFAF7F6), scheme.background)
        assertEquals(Color(0xFFFFFDFC), scheme.surface)
        assertEquals(Color(0xFF211A19), scheme.onBackground)
    }

    @Test
    fun darkThemeUsesApprovedWarmAccessibleTokens() {
        val scheme = warmDarkColorScheme()

        assertEquals(Color(0xFFFFB3AA), scheme.primary)
        assertEquals(Color(0xFF5F1410), scheme.onPrimary)
        assertEquals(Color(0xFF17181A), scheme.background)
        assertEquals(Color(0xFF202124), scheme.surface)
        assertEquals(Color(0xFFF3EDEC), scheme.onBackground)
    }

    @Test
    fun typeAndSpacingKeepReadableComfortableDefaults() {
        assertEquals(17.sp, HarnessTypography.bodyLarge.fontSize)
        assertEquals(14.sp, HarnessTypography.bodySmall.fontSize)
        assertEquals(48.dp, HarnessSpacing.minimumTouchTarget)
        assertEquals(56.dp, HarnessSpacing.primaryControlHeight)
    }
}
```

- [ ] **Step 2: Run the theme test and verify RED**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.theme.ThemeTest`

Expected: compilation fails because `warmLightColorScheme`, `warmDarkColorScheme`, `HarnessTypography`, and `HarnessSpacing` do not exist.

- [ ] **Step 3: Implement the approved theme**

Replace private fixed schemes with internal factory functions and add the shared tokens:

```kotlin
internal fun warmLightColorScheme() = lightColorScheme(
    primary = Color(0xFF9F5167),
    onPrimary = Color(0xFFFFF9F7),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3F071D),
    secondary = Color(0xFF6E5E5A),
    onSecondary = Color(0xFFFFFBFA),
    background = Color(0xFFFAF7F6),
    onBackground = Color(0xFF211A19),
    surface = Color(0xFFFFFDFC),
    onSurface = Color(0xFF211A19),
    surfaceVariant = Color(0xFFF1E5E2),
    onSurfaceVariant = Color(0xFF564341),
    outline = Color(0xFF887370),
    outlineVariant = Color(0xFFDBC1BD),
    error = Color(0xFFBA1A1A),
)

internal fun warmDarkColorScheme() = darkColorScheme(
    primary = Color(0xFFFFB3AA),
    onPrimary = Color(0xFF5F1410),
    primaryContainer = Color(0xFF862824),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFD8C2BD),
    onSecondary = Color(0xFF3B2D2A),
    background = Color(0xFF17181A),
    onBackground = Color(0xFFF3EDEC),
    surface = Color(0xFF202124),
    onSurface = Color(0xFFF3EDEC),
    surfaceVariant = Color(0xFF343437),
    onSurfaceVariant = Color(0xFFD8C2BD),
    outline = Color(0xFFA98C87),
    outlineVariant = Color(0xFF554440),
    error = Color(0xFFFFB4AB),
)

internal val HarnessTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

internal object HarnessSpacing {
    val minimumTouchTarget = 48.dp
    val primaryControlHeight = 56.dp
    val pageHorizontal = 16.dp
    val section = 24.dp
    val item = 12.dp
}
```

Set `HarnessApkTheme` to use the warm scheme, typography, and approved shapes.

- [ ] **Step 4: Run the theme test and full unit suite**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest`

Expected: all JVM tests pass.

- [ ] **Step 5: Commit the theme foundation**

```bash
git add app/src/main/java/com/harnessapk/ui/theme/Theme.kt app/src/test/java/com/harnessapk/ui/theme/ThemeTest.kt
git commit -m "优化：建立温暖无障碍主题规范"
```

### Task 2: Shared Components and Home Conversation Screen

**Files:**
- Create: `app/src/main/java/com/harnessapk/ui/components/WarmComponents.kt`
- Create: `app/src/androidTest/java/com/harnessapk/ui/components/WarmComponentsTest.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt`

**Interfaces:**
- Consumes: `HarnessSpacing`, Material 3 `ColorScheme`, existing `MainMode`, existing conversation grouping state.
- Produces: `WarmSegmentedControl`, `ActionableEmptyState`, `InlineStatusMessage`, `ComfortListRow`, and a home empty-state create callback.

- [ ] **Step 1: Write failing Compose tests for shared semantics**

```kotlin
package com.harnessapk.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class WarmComponentsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun segmentedControlExposesBothLargeTextChoices() {
        composeRule.setContent {
            HarnessApkTheme {
                WarmSegmentedControl(
                    options = listOf("会话", "项目"),
                    selectedIndex = 0,
                    onSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("会话").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("项目").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun emptyStateProvidesVisiblePrimaryAction() {
        composeRule.setContent {
            HarnessApkTheme {
                ActionableEmptyState(
                    title = "还没有会话",
                    message = "新建会话后，内容会保存在本机。",
                    actionLabel = "新建会话",
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("还没有会话").assertIsDisplayed()
        composeRule.onNodeWithText("新建会话").assertIsDisplayed().assertHasClickAction()
    }
}
```

- [ ] **Step 2: Run the instrumentation test and verify RED**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.components.WarmComponentsTest`

Expected: compilation fails because `WarmSegmentedControl` and `ActionableEmptyState` do not exist.

- [ ] **Step 3: Implement stateless shared components**

Create `WarmComponents.kt` with:

```kotlin
@Composable
fun WarmSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
fun ActionableEmptyState(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
)

@Composable
fun InlineStatusMessage(
    text: String,
    tone: StatusTone,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)

@Composable
fun ComfortListRow(
    title: String,
    supportingText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    metadata: String? = null,
    trailingContent: @Composable (() -> Unit)? = null,
)
```

All click surfaces use `heightIn(min = HarnessSpacing.minimumTouchTarget)`. Cards use one surface layer with no elevation unless the component is a true floating panel.

- [ ] **Step 4: Apply shared components to home**

In `HarnessApkApp.kt`, replace `ModeSwitcher` internals with `WarmSegmentedControl`, preserving `MainMode.entries` and callbacks. Pass the existing create-conversation action into `ConversationListScreen` as `onCreateConversation`.

In `ConversationListScreen.kt`:

- use `HarnessSpacing.pageHorizontal` and `HarnessSpacing.item`;
- replace elevated conversation cards with `ComfortListRow` plus a single divider between rows;
- replace ASCII `>` / `v` collapse markers with Material icons and content descriptions;
- use `ActionableEmptyState` with the “新建会话” callback;
- preserve edit, archive, grouping, and project-name behavior.

- [ ] **Step 5: Run tests and verify home behavior**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.components.WarmComponentsTest
```

Expected: JVM and shared component instrumentation tests pass.

- [ ] **Step 6: Commit shared components and home**

```bash
git add app/src/main/java/com/harnessapk/ui/components/WarmComponents.kt app/src/androidTest/java/com/harnessapk/ui/components/WarmComponentsTest.kt app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt
git commit -m "优化：首页采用温暖舒适视觉层级"
```

### Task 3: Chat Message and Composer Presentation

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt`

**Interfaces:**
- Consumes: existing `MessageRole`, `ChatBubbleSide`, `ChatInputTrailingAction`, and chat callbacks.
- Produces: `ChatBubblePresentation` and `chatBubblePresentation(role)` used by `MessageBubble`.

- [ ] **Step 1: Write the failing bubble presentation test**

Add to `ChatUiStateTest.kt`:

```kotlin
@Test
fun assistantMessagesAreUnframedWhileUserMessagesStayWarm() {
    assertEquals(ChatBubblePresentation.UNFRAMED, chatBubblePresentation(MessageRole.ASSISTANT))
    assertEquals(ChatBubblePresentation.WARM_USER, chatBubblePresentation(MessageRole.USER))
    assertEquals(ChatBubblePresentation.NEUTRAL_EVENT, chatBubblePresentation(MessageRole.SYSTEM))
}
```

- [ ] **Step 2: Run the chat test and verify RED**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest`

Expected: compilation fails because `ChatBubblePresentation` and `chatBubblePresentation` do not exist.

- [ ] **Step 3: Implement message presentation and composer hierarchy**

Add:

```kotlin
internal enum class ChatBubblePresentation { UNFRAMED, WARM_USER, NEUTRAL_EVENT }

internal fun chatBubblePresentation(role: MessageRole): ChatBubblePresentation = when (role) {
    MessageRole.ASSISTANT -> ChatBubblePresentation.UNFRAMED
    MessageRole.USER -> ChatBubblePresentation.WARM_USER
    MessageRole.SYSTEM -> ChatBubblePresentation.NEUTRAL_EVENT
}
```

Update `MessageBubble` so assistant content uses no filled card, user content uses `primaryContainer`, and system content uses a light neutral surface. Preserve selection, copy, status, Markdown, reasoning, search results, and file-change rendering.

Update `ChatInputBar` so:

- the whole composer is one `Surface` with `18dp` top corners and tonal elevation;
- the text field has at least `56dp` height and supports multiline growth;
- send, stop, voice, and attachment buttons retain existing callbacks and content descriptions;
- model, reasoning, web search, and context status appear in one secondary row;
- keyboard insets keep the composer visible;
- no control depends only on color to communicate mode.

- [ ] **Step 4: Run chat and full JVM tests**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest
```

Expected: all JVM tests pass.

- [ ] **Step 5: Commit chat presentation**

```bash
git add app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt
git commit -m "优化：提升聊天消息与输入区可读性"
```

### Task 4: Project Workbench Hierarchy

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt`

**Interfaces:**
- Consumes: existing `ProjectWorkbenchTab`, project actions, artifact tree, and Git panel callbacks.
- Produces: `ProjectHeaderActionLayout`, `projectHeaderActionLayout(hasProject)`, and a reduced header action arrangement.

- [ ] **Step 1: Write the failing header action test**

Add to `ProjectSessionLaunchUiStateTest.kt`:

```kotlin
@Test
fun projectHeaderKeepsFrequentActionsDirectAndMovesLowFrequencyActionsToOverflow() {
    val layout = projectHeaderActionLayout(hasProject = true)

    assertEquals(listOf(ProjectHeaderAction.NEW_SESSION), layout.directActions)
    assertEquals(
        listOf(
            ProjectHeaderAction.CLONE,
            ProjectHeaderAction.IMPORT,
            ProjectHeaderAction.EXPORT,
            ProjectHeaderAction.SHARE,
            ProjectHeaderAction.DELETE,
        ),
        layout.overflowActions,
    )
}
```

- [ ] **Step 2: Run the project UI test and verify RED**

Run: `ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.project.ProjectSessionLaunchUiStateTest`

Expected: compilation fails because the project header action types and function do not exist.

- [ ] **Step 3: Implement the project header action model**

In `ProjectUiState.kt`, add:

```kotlin
internal enum class ProjectHeaderAction { NEW_SESSION, CLONE, IMPORT, EXPORT, SHARE, DELETE }

internal data class ProjectHeaderActionLayout(
    val directActions: List<ProjectHeaderAction>,
    val overflowActions: List<ProjectHeaderAction>,
)

internal fun projectHeaderActionLayout(hasProject: Boolean): ProjectHeaderActionLayout =
    if (hasProject) {
        ProjectHeaderActionLayout(
            directActions = listOf(ProjectHeaderAction.NEW_SESSION),
            overflowActions = listOf(
                ProjectHeaderAction.CLONE,
                ProjectHeaderAction.IMPORT,
                ProjectHeaderAction.EXPORT,
                ProjectHeaderAction.SHARE,
                ProjectHeaderAction.DELETE,
            ),
        )
    } else {
        ProjectHeaderActionLayout(
            directActions = emptyList(),
            overflowActions = listOf(ProjectHeaderAction.CLONE, ProjectHeaderAction.IMPORT),
        )
    }
```

- [ ] **Step 4: Recompose the workbench without changing behavior**

In `ProjectScreen.kt`:

- make the selected project name and selector the primary header content;
- keep new project and new project session directly visible;
- render clone, import, export, share, and delete through one overflow menu using `projectHeaderActionLayout`;
- retain the existing delete confirmation dialog;
- replace project conversation cards with flat comfortable rows;
- use `ActionableEmptyState` for missing project and missing project conversation states;
- use Material 3 tabs with at least `48dp` height and clear selected indicators;
- keep folder search, artifact filters, tree selection, preview/edit, and Git behavior unchanged;
- remove nested elevation from `ArtifactPreviewPanel`, using one bordered or tonal surface instead;
- render status text with `InlineStatusMessage`.

- [ ] **Step 5: Run project and full JVM tests**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.project.ProjectSessionLaunchUiStateTest
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest
```

Expected: all JVM tests pass.

- [ ] **Step 6: Commit the project workbench**

```bash
git add app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt
git commit -m "优化：重整项目工作台操作层级"
```

### Task 5: Full Verification and Core Screenshots

**Files:**
- Modify only files required by defects found during verification.
- Create screenshots outside the repository under `/tmp/harness-apk-ui/`.

**Interfaces:**
- Consumes: completed theme, home, chat, and project workbench.
- Produces: verified debug APK and screenshots for the user.

- [ ] **Step 1: Run full automated verification**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest connectedDebugAndroidTest assembleDebug
git diff --check
```

Expected: all tests and the debug build pass; `git diff --check` has no output.

- [ ] **Step 2: Install and launch on an emulator**

```bash
adb devices
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew installDebug --console=plain
adb shell cmd package resolve-activity --brief com.harnessapk.debug
adb shell am start -n com.harnessapk.debug/com.harnessapk.MainActivity
```

Expected: the main activity launches without a crash.

- [ ] **Step 3: Validate core flows using the UI tree**

Dump the tree before every tap, derive coordinates from bounds, and verify:

1. Home conversation mode and project mode switching.
2. Empty or populated conversation list and create action.
3. Chat composer, model status, multiline input, and keyboard visibility.
4. Project header, workbench tabs, overflow menu, and empty states.
5. Light mode, dark mode, and enlarged font scale.

- [ ] **Step 4: Capture screenshots**

Create and capture at least:

```bash
mkdir -p /tmp/harness-apk-ui
adb exec-out screencap -p > /tmp/harness-apk-ui/home.png
adb exec-out screencap -p > /tmp/harness-apk-ui/chat.png
adb exec-out screencap -p > /tmp/harness-apk-ui/project.png
adb exec-out screencap -p > /tmp/harness-apk-ui/dark-home.png
```

Inspect every image for overlap, truncation, low contrast, blank content, and inconsistent spacing.

- [ ] **Step 5: Check logs and final repository state**

```bash
adb logcat -b crash -d
git status --short --branch
git log -5 --oneline
```

Expected: no app crash entries, only task-related committed changes, and no automatic push.

- [ ] **Step 6: Commit verification fixes if needed**

If verification required code fixes, first reproduce each issue with a failing test, apply the minimal fix, rerun the full command from Step 1, then commit only those files:

```bash
git add app/src/main/java/com/harnessapk/ui/theme/Theme.kt app/src/main/java/com/harnessapk/ui/components/WarmComponents.kt app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/main/java/com/harnessapk/ui/project/ProjectScreen.kt app/src/main/java/com/harnessapk/ui/project/ProjectUiState.kt app/src/test/java/com/harnessapk/ui/theme/ThemeTest.kt app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt app/src/test/java/com/harnessapk/ui/project/ProjectSessionLaunchUiStateTest.kt app/src/androidTest/java/com/harnessapk/ui/components/WarmComponentsTest.kt
git commit -m "修复：完善核心界面视觉验收"
```
