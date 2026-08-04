# “我的”Tab 来源主题继承与平滑过渡 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让“我的”Tab 继承最近生活/工作来源主题，并让生活与工作主题的颜色和圆角在 450ms 内平滑协同过渡。

**Architecture:** 将当前导航模式与有效主题模式分离：`mainMode` 决定页面，`themeSourceMode` 只保存最近的 `LIFE/WORK`，纯函数负责解析“我的”的有效主题。`HomeModeStore` 原子持久化两个状态；`ModeTheme` 使用单一 Compose `Transition` 同步驱动 `ColorScheme` 与 `Shapes`。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、SharedPreferences、JUnit 4、Compose UI Test、Gradle

## Global Constraints

- “我的”只继承视觉主题，不改变底部导航与返回行为。
- 来源主题只允许 `LIFE` 或 `WORK`；缺失、未知值或 `ME` 一律回退 `LIFE`。
- 主题过渡固定为 450ms，使用 `FastOutSlowInEasing`。
- 暖浅与深科技颜色值保持不变，不新增第三套主题。
- 不增加整页位移、缩放或交叉淡入动画。

---

### Task 1: 有效主题来源纯逻辑

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HomeUiState.kt`
- Test: `app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt`

**Interfaces:**
- Produces: `normalizeThemeSource(mode: MainMode): MainMode`
- Produces: `resolveThemeMode(mainMode: MainMode, themeSourceMode: MainMode): MainMode`
- Produces: `nextThemeSource(currentSource: MainMode, selectedMode: MainMode): MainMode`

- [ ] **Step 1: Write failing theme-source tests**

Add literal assertions covering `ME + LIFE`, `ME + WORK`, direct `LIFE/WORK`, invalid `ME` source fallback, and selecting `ME` without overwriting the source.

```kotlin
@Test
fun meModeInheritsLastBusinessTheme() {
    assertEquals(MainMode.LIFE, resolveThemeMode(MainMode.ME, MainMode.LIFE))
    assertEquals(MainMode.WORK, resolveThemeMode(MainMode.ME, MainMode.WORK))
}

@Test
fun businessTabsAlwaysUseTheirOwnTheme() {
    assertEquals(MainMode.LIFE, resolveThemeMode(MainMode.LIFE, MainMode.WORK))
    assertEquals(MainMode.WORK, resolveThemeMode(MainMode.WORK, MainMode.LIFE))
}

@Test
fun selectingMeKeepsNormalizedSource() {
    assertEquals(MainMode.WORK, nextThemeSource(MainMode.WORK, MainMode.ME))
    assertEquals(MainMode.LIFE, nextThemeSource(MainMode.ME, MainMode.ME))
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.ui.HomeModeUiStateTest`

Expected: compilation fails because the three theme-source functions do not exist.

- [ ] **Step 3: Implement minimal pure functions**

```kotlin
internal fun normalizeThemeSource(mode: MainMode): MainMode = when (mode) {
    MainMode.LIFE -> MainMode.LIFE
    MainMode.WORK -> MainMode.WORK
    MainMode.ME -> MainMode.LIFE
}

internal fun resolveThemeMode(mainMode: MainMode, themeSourceMode: MainMode): MainMode =
    if (mainMode == MainMode.ME) normalizeThemeSource(themeSourceMode) else mainMode

internal fun nextThemeSource(currentSource: MainMode, selectedMode: MainMode): MainMode = when (selectedMode) {
    MainMode.LIFE -> MainMode.LIFE
    MainMode.WORK -> MainMode.WORK
    MainMode.ME -> normalizeThemeSource(currentSource)
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.ui.HomeModeUiStateTest`

Expected: PASS.

---

### Task 2: 来源持久化与首页接线

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/HomeModeStore.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Test: `app/src/test/java/com/harnessapk/ui/HomeModeStoreTest.kt`
- Test: `app/src/androidTest/java/com/harnessapk/ui/TabNavigationTest.kt`

**Interfaces:**
- Consumes: `normalizeThemeSource`, `resolveThemeMode`, `nextThemeSource`
- Produces: `HomeModeStore.themeSourceMode: StateFlow<MainMode>`
- Produces: `HomeModeStore.save(mode: MainMode, themeSourceMode: MainMode)`
- Produces: `migrateStoredThemeSource(raw: String?): MainMode`

- [ ] **Step 1: Write failing persistence and navigation tests**

Add JVM assertions showing only `LIFE/WORK` migrate as source and `ME` falls back to `LIFE`. Update Compose navigation tests to cover the real click sequences `LIFE -> ME` and `WORK -> ME`; the effective theme result is protected by the pure-function assertions from Task 1 and `ModeTheme` token assertions from Task 3.

```kotlin
@Test
fun storedThemeSourceRejectsMeAndUnknownValues() {
    assertEquals(MainMode.LIFE, migrateStoredThemeSource(null))
    assertEquals(MainMode.LIFE, migrateStoredThemeSource("ME"))
    assertEquals(MainMode.LIFE, migrateStoredThemeSource("UNKNOWN"))
    assertEquals(MainMode.WORK, migrateStoredThemeSource("WORK"))
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.ui.HomeModeStoreTest`

Expected: compilation fails because `migrateStoredThemeSource` does not exist.

- [ ] **Step 3: Implement atomic persistence**

Store `main_mode` and normalized `theme_source_mode` in one `SharedPreferences.Editor` operation. Expose both as `StateFlow`; `reset()` reloads both values after clearing preferences.

```kotlin
fun save(mode: MainMode, themeSourceMode: MainMode) {
    val normalizedSource = nextThemeSource(themeSourceMode, mode)
    preferences.edit()
        .putString("main_mode", mode.name)
        .putString("theme_source_mode", normalizedSource.name)
        .apply()
    _mode.value = mode
    _themeSourceMode.value = normalizedSource
}
```

- [ ] **Step 4: Wire app state and tab actions**

Initialize `themeSourceMode` from the store. Every bottom Tab click updates the source through `nextThemeSource` before changing `mainMode`; `openWorkbench` sets both to `WORK`. Persist the pair in one `LaunchedEffect(mainMode, themeSourceMode)`. Call `ModeTheme(resolveThemeMode(mainMode, themeSourceMode))`.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.harnessapk.ui.HomeMode*Test'`

Expected: PASS.

---

### Task 3: 颜色与圆角协同动画

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/theme/Theme.kt`
- Modify: `app/src/test/java/com/harnessapk/ui/theme/ThemeTest.kt`
- Modify: `app/src/androidTest/java/com/harnessapk/ui/TabNavigationTest.kt`

**Interfaces:**
- Consumes: resolved `MainMode.LIFE` or `MainMode.WORK`
- Produces: one `Transition<MainMode>` driving animated `ColorScheme` and `Shapes`

- [ ] **Step 1: Replace the obsolete fixed-ME test with target-token tests**

Test that `themeColorScheme(LIFE)` and `themeColorScheme(WORK)` return the approved schemes, and that `themeShapes(LIFE/WORK)` expose the existing literal corner radii. The removed `ME -> techDark` assertion must not survive because `ME` is resolved before entering `ModeTheme`.

- [ ] **Step 2: Run focused theme tests and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.ui.theme.ThemeTest`

Expected: compilation fails because `themeColorScheme` and `themeShapes` do not exist.

- [ ] **Step 3: Implement one coordinated transition**

Use `updateTransition(targetState = mode, label = "mode-theme")`, a shared `tween(durationMillis = 450, easing = FastOutSlowInEasing)`, `transition.animateColor` for all `ColorScheme` fields, and `transition.animateDp` for the five corner radii. Rebuild `Shapes` from the animated radii and pass both animated values to `MaterialTheme`.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests com.harnessapk.ui.theme.ThemeTest`

Expected: PASS.

- [ ] **Step 5: Run full automated verification**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run emulator visual verification**

Install the Debug APK, exercise `生活 -> 我的`, `工作 -> 我的`, and `生活 <-> 工作`, capture screenshots after animations settle, and verify no flash, contrast loss, overlap, or shape jump.

- [ ] **Step 7: Commit the implementation**

```bash
git add app/src/main/java/com/harnessapk/ui/HomeUiState.kt \
  app/src/main/java/com/harnessapk/ui/HomeModeStore.kt \
  app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt \
  app/src/main/java/com/harnessapk/ui/theme/Theme.kt \
  app/src/test/java/com/harnessapk/ui/HomeModeUiStateTest.kt \
  app/src/test/java/com/harnessapk/ui/HomeModeStoreTest.kt \
  app/src/test/java/com/harnessapk/ui/theme/ThemeTest.kt \
  app/src/androidTest/java/com/harnessapk/ui/TabNavigationTest.kt
git commit -m "优化：我的 Tab 继承来源主题并平滑过渡"
```
