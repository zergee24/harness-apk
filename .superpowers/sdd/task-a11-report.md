# A11 TOCTOU Review Report

Baseline: `6f8314a`.

## RED

- Added the secure-stream race fixtures before the implementation. The focused Android test compilation failed because `QueuedAttachmentStoreTestHooks`, secure capability metadata, and the `testHooks` constructor argument did not exist.

## GREEN

- `QueuedAttachmentStore` now requires `SecureDirectoryStream<Path>` for the trusted parent and `chat-attachments` child, creates the raw child only for the initial create race, and rejects unavailable secure providers, symlinks, and non-directories.
- A batch writes and moves UUID-derived relative names through its pinned child handle. It stores only immutable capability names plus the child `fileKey`, reopens the current child through the pinned parent after `onBatchPersisted`, and reclaims the original handle if identity changed.
- Cleanup reopens and pins the current child with `NOFOLLOW_LINKS`, verifies its `fileKey`, validates each file URI/name and no-follow regular-file attributes, and removes only via `SecureDirectoryStream.deleteFile`.
- New hooks are no-ops in production and cover root replacement before persist write, root replacement before cleanup delete, and concurrent first-directory creation.

## Verification

- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.chat.QueuedAttachmentStoreInstrumentedTest --console=plain` passed twice: 21 tests each run on `harness_api36(AVD) - 16`.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.chat.ChatExecutionRepositoryInstrumentedTest --console=plain` passed: 8 tests on `harness_api36(AVD) - 16`.
- `./gradlew testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin --console=plain` passed.
- `git diff --check` passed.

---

## Root mkdir TOCTOU closeout

Baseline: `531c894`.

### RED

- Added a constructor-time adversarial fixture with an injectable temporary root. Its `beforeChildDirectoryCreate` hook renames the opened root to a pinned location and replaces the original path with a symlink to a separate replacement root.
- The prior raw `Files.createDirectory(absolutePath.resolve(...))` implementation failed the fixture because it created `chat-attachments` through the replacement-root symlink before rejecting the root replacement.

### GREEN

- Construction now opens the absolute root with `Os.open(O_RDONLY | O_CLOEXEC | O_NOFOLLOW)`, verifies its directory `st_dev`/`st_ino` identity with `Os.fstat`, and keeps that descriptor open while initialization runs.
- `ParcelFileDescriptor.dup(rootFd).use` supplies the stable descriptor number used for `Os.mkdir` and no-follow child validation through `/proc/self/fd/<fd>/chat-attachments`. `EEXIST` remains the allowed concurrent-constructor outcome; all other mkdir errors fail construction.
- The raw root path is re-opened with `O_NOFOLLOW` after the anchored create and must retain the recorded device/inode identity. Runtime trusted-parent opens retain both the existing `fileKey` check and pre/post root identity checks.
- The new fixture proves construction fails after replacement, leaves the replacement root empty, and creates the child only under the original pinned root. The existing symlink-root fixture now asserts the required earlier construction failure.

### Verification

- RED focused connected fixture failed as expected against the baseline: `Construction must not create the managed child in the replacement root`.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.chat.QueuedAttachmentStoreInstrumentedTest --rerun-tasks --console=plain` passed twice consecutively: 29 tests each run on `harness_api36(AVD) - 16`.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.chat.ChatExecutionRepositoryInstrumentedTest --rerun-tasks --console=plain` passed: 8 tests on `harness_api36(AVD) - 16`.
- `./gradlew testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin --console=plain` passed.
- `git diff --check` passed.

---

## Final review: owned temporary and final files

Baseline: `5ebf746`.

### RED

- Added an instrumentation fixture that pre-creates the generated temporary name in `beforeWrite`. On the old implementation, `CREATE_NEW` failed and the catch cleanup deleted that pre-existing file; the test then failed reading it with `ENOENT`.
- Added the final-name collision and temporary-to-final copy-failure fixtures before adding the required narrow hooks. Android test compilation failed because `beforeFinalCreate` and `copyTemporaryToFinal` did not exist.

### GREEN

- `persistOne` now marks the temporary and final entries as owned only after their respective secure `CREATE_NEW` channel opens successfully.
- It creates the final entry with `CREATE_NEW`, `WRITE`, and `NOFOLLOW_LINKS`, then copies from the temporary entry opened through the same secure directory handle with `READ` and `NOFOLLOW_LINKS`. It no longer uses `SecureDirectoryStream.move`, so an existing final entry cannot be replaced.
- Exception cleanup only attempts entries marked as created by this call, suppresses cleanup failures to preserve the original exception instance, and clears the temporary ownership flag after normal deletion. Batch cleanup still receives only completed final names.
- The focused instrumentation class now covers a pre-existing temporary collision, a pre-existing final collision after this call created its temporary entry, and a mid-copy failure after this call created both entries.

### Verification

- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.chat.QueuedAttachmentStoreInstrumentedTest --console=plain` passed twice consecutively: 24 tests each run on `harness_api36(AVD) - 16`.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.chat.ChatExecutionRepositoryInstrumentedTest --console=plain` passed: 8 tests on `harness_api36(AVD) - 16`.
- `./gradlew testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin --console=plain` passed.

---

## Final review: trusted root and owned leaf identity

Baseline: `fc0f812`.

### RED

- Added constructor-time child initialization and root-replacement fixtures. The focused Android test compilation failed because the root provider seam and the owned-entry move hook did not exist.

### GREEN

- Store construction records the original absolute files root, rejects symlinked roots, verifies its canonical path resolves to the same no-follow `fileKey`, and creates `chat-attachments` only during construction under a process-wide initialization lock with root-key checks on both sides. Runtime persist only opens the existing child through a secure parent handle; a missing child is a failure, not a recreation.
- Each secure `CREATE_NEW` temporary/final file receives an immediate no-follow regular-file `fileKey`. `PersistedAttachmentBatch` keeps immutable final `(name, fileKey)` capabilities.
- Every owned-file cleanup path now uses `deleteOwnedEntry`: no-follow key check, secure move to an unpredictable unused quarantine name, no-follow key recheck, then delete only the matching quarantined inode. A mismatch is restored when the original name remains absent, otherwise quarantine is preserved.
- Added coverage for concurrent Store construction, a deleted child after construction, replacement of the root path after construction, cleanup replacement between precheck and move, and failure cleanup with a replaced final inode.

### Verification

- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.chat.QueuedAttachmentStoreInstrumentedTest --console=plain` passed twice consecutively: 28 tests each run on `harness_api36(AVD) - 16`.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.chat.ChatExecutionRepositoryInstrumentedTest --console=plain` passed: 8 tests on `harness_api36(AVD) - 16`.
- `./gradlew testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin --console=plain` passed.
