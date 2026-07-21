# Harness `.hwiki` Android Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the existing Harness Android app safely import, version, browse, search, and inspect citations from a signed schema-v1 `.hwiki` without starting a conversation.

**Architecture:** Keep each Wiki's immutable `content.sqlite` in app-private storage and open it read-only through a focused `WikiContentStore`; Room stores only package metadata and active-version state. Extract the generic ZIP/checksum/Ed25519 verifier from the existing Agent reader so `.hagent` and `.hwiki` share security behavior, while their manifests and repositories remain separate. Add a Wiki library under Settings plus source-first browse, search, and reader routes; do not add a third home mode.

**Tech Stack:** Kotlin, Android SDK 26+, Jetpack Compose, Navigation Compose, Room 2.8.4, `android.database.sqlite.SQLiteDatabase`, Kotlin serialization, BouncyCastle/Ed25519, JUnit, Android instrumented tests.

**Authoritative Spec:** `docs/superpowers/specs/2026-07-22-offline-hwiki-knowledge-package-design.md`. If implementation evidence requires changing a product boundary, amend and re-approve the Spec before changing this plan.

## Global Constraints

- This is work package 2 of 5 and consumes the signed fixture from `2026-07-22-hwiki-package-builder.md`.
- `.hwiki` is globally available like `.hagent`, but it is a knowledge asset, not an identity and not a new top-level app mode.
- Import must work from the system document picker and from an external `ACTION_VIEW`/`ACTION_SEND` intent.
- Treat the package as untrusted input: bounded copy, bounded ZIP entries, exact checksums, Ed25519 verification, schema allowlist, SQLite integrity and foreign-key checks, no symlinks or executable payloads.
- Use 64-bit sizes and Zip64. Production `WIKI_PACKAGE_POLICY` allows at most 4 GiB compressed, one `content.sqlite` up to 8 GiB expanded, three metadata entries up to 4 MiB each, exactly four entries, and expansion ratio at most 100:1; fail before extraction when declared sizes exceed a bound and fail during streaming if actual bytes diverge.
- A self-contained public key proves integrity, not publisher identity. First import of an unknown fingerprint requires an explicit confirmation showing publisher name, key ID, fingerprint, package size, and declared capabilities.
- Never execute SQL shipped outside `content.sqlite`; open the database read-only and reject triggers, views, unknown virtual-table modules, writable-schema changes, and unsupported `PRAGMA user_version`.
- Show original source text for quotations. Normalized text, n-grams, summaries, and terms may support search but must never replace cited source text.
- Keep installed versions immutable. Activating a version changes metadata only; do not rewrite an existing version directory.
- Do not implement conversation mounts, automatic retrieval, project Markdown writeback, remote catalogs, vector search, GraphRAG, or generated-page editing in this work package.
- Use TDD and create one scoped Chinese commit at the end of every task; do not push.

## Plan Series

This plan follows `2026-07-22-hwiki-package-builder.md` and is followed by:

1. `2026-07-22-hwiki-chat-retrieval.md`
2. `2026-07-22-hwiki-project-integration.md`
3. `2026-07-22-hwiki-two-history-build.md`

---

### Task 1: Extract The Shared Android Signed-Package Verifier

**Files:**
- Create: `app/src/main/java/com/harnessapk/packageformat/SignedPackageModels.kt`
- Create: `app/src/main/java/com/harnessapk/packageformat/SignedPackageVerifier.kt`
- Create: `app/src/test/java/com/harnessapk/packageformat/SignedPackageVerifierTest.kt`
- Modify: `app/src/main/java/com/harnessapk/agent/AgentBundleReader.kt`
- Test: `app/src/test/java/com/harnessapk/agent/AgentBundleReaderTest.kt`
- Test: `app/src/test/java/com/harnessapk/agent/AgentV2BundleReaderTest.kt`

**Interfaces:**
- Produces `SignedPackagePolicy`, `VerifiedPackage`, `VerifiedPackageEntry`, `PublisherFingerprint`, and `SignedPackageVerifier.verify(stagedArchive, policy)`.
- Preserves every existing public `AgentBundleReader` result and error mapping.

- [ ] **Step 1: Write failing verifier tests against the desktop fixture**

```kotlin
class SignedPackageVerifierTest {
    @Test
    fun `verifies checksums signature and exact entry allowlist`() {
        val verified = SignedPackageVerifier().verify(
            stagedArchive = fixturePath("wiki/fixture.history-v1.hwiki"),
            policy = SignedPackagePolicy(
                allowedPayloads = setOf("manifest.json", "content.sqlite"),
                maxArchiveBytes = 256L * 1024 * 1024,
                maxExpandedBytes = 768L * 1024 * 1024,
                maxEntryCount = 4,
            ),
        )

        assertEquals(setOf("manifest.json", "content.sqlite"), verified.payloads.keys)
        assertEquals(64, verified.publisherFingerprint.hex.length)
    }

    @Test
    fun `rejects duplicate traversal and undeclared entries`() {
        listOf("duplicate-entry.hwiki", "parent-traversal.hwiki", "extra-entry.hwiki").forEach { name ->
            assertFailsWith<SignedPackageException> {
                SignedPackageVerifier().verify(fixturePath("wiki/$name"), WIKI_FIXTURE_POLICY)
            }
        }
    }
}
```

- [ ] **Step 2: Run the focused test and verify the package is absent**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.packageformat.SignedPackageVerifierTest`

Expected: compilation fails because `com.harnessapk.packageformat` does not exist.

- [ ] **Step 3: Move only generic archive verification into the shared package**

```kotlin
data class SignedPackagePolicy(
    val allowedPayloads: Set<String>,
    val maxArchiveBytes: Long,
    val maxExpandedBytes: Long,
    val maxEntryCount: Int,
)

data class PublisherFingerprint(
    val algorithm: String,
    val keyId: String?,
    val hex: String,
)

data class VerifiedPackage(
    val manifestBytes: ByteArray,
    val payloads: Map<String, VerifiedPackageEntry>,
    val publisherFingerprint: PublisherFingerprint,
)
```

The verifier must canonicalize entry names before duplicate checks, stream hashes without loading `content.sqlite` into memory, verify that `checksums.json` covers every payload exactly once, require `signature.json.signedFile == "checksums.json"`, and calculate the fingerprint from the raw Ed25519 public key. It must never parse Agent or Wiki business fields.

- [ ] **Step 4: Make `AgentBundleReader` delegate to the shared verifier**

Keep Agent-specific package variants, manifests, compatibility errors, and extracted models in `com.harnessapk.agent`. Replace only duplicated ZIP, checksum, signature, path, and expansion-limit code. Add regression assertions that existing valid and invalid `.hagent` fixtures produce the same public results.

- [ ] **Step 5: Run verifier and Agent regression tests**

Run: `./gradlew testDebugUnitTest --tests 'com.harnessapk.packageformat.*' --tests 'com.harnessapk.agent.Agent*BundleReaderTest'`

Expected: all tests pass; no Agent fixture output changes.

- [ ] **Step 6: Commit the shared verifier**

```bash
git add app/src/main/java/com/harnessapk/packageformat app/src/main/java/com/harnessapk/agent/AgentBundleReader.kt app/src/test/java/com/harnessapk/packageformat app/src/test/java/com/harnessapk/agent
git commit -m "重构：统一移动端知识包校验"
```

### Task 2: Parse And Inspect Wiki Packages Before Installation

**Files:**
- Create: `app/src/main/java/com/harnessapk/wiki/WikiModels.kt`
- Create: `app/src/main/java/com/harnessapk/wiki/WikiManifestParser.kt`
- Create: `app/src/main/java/com/harnessapk/wiki/WikiPackageReader.kt`
- Create: `app/src/test/java/com/harnessapk/wiki/WikiManifestParserTest.kt`
- Create: `app/src/test/java/com/harnessapk/wiki/WikiPackageReaderTest.kt`
- Create: `app/src/androidTest/java/com/harnessapk/wiki/WikiPackageReaderInstrumentedTest.kt`
- Modify: `app/build.gradle.kts`
- Generate fixture from: `app/src/test/resources/wiki/source.md`

**Interfaces:**
- Produces `WikiRef(wikiId: String, version: Int)`, `WikiManifest`, `WikiCapabilities`, `WikiImportInspection`, and `WikiPackageReader.inspect(stagedArchive: Path)`.
- Accepts schema version `1` and content `PRAGMA user_version=1` only.

- [ ] **Step 1: Write strict manifest tests**

```kotlin
@Test
fun `schema v1 manifest maps all capability fields`() {
    val manifest = WikiManifestParser.parse(validManifestBytes())
    assertEquals(WikiRef("fixture.history", 1), manifest.ref)
    assertTrue(manifest.capabilities.sourceHierarchy)
    assertEquals(GeneratedPages.NONE, manifest.capabilities.generatedPages)
}

@Test
fun `unsupported or overstated manifests fail closed`() {
    listOf(
        manifestWith("schemaVersion", 2),
        manifestWith("type", "hagent"),
        manifestWith("capabilities.vectorIndex", true),
        manifestWith("capabilities.sourceAttachments", true),
    ).forEach { bytes -> assertFailsWith<WikiPackageException> { WikiManifestParser.parse(bytes) } }
}
```

- [ ] **Step 2: Run tests and verify the parser is missing**

Run: `./gradlew testDebugUnitTest --tests 'com.harnessapk.wiki.WikiManifestParserTest' --tests 'com.harnessapk.wiki.WikiPackageReaderTest'`

Expected: compilation fails on missing Wiki classes.

- [ ] **Step 3: Implement immutable package models and parser**

```kotlin
data class WikiRef(val wikiId: String, val version: Int)

data class WikiManifest(
    val ref: WikiRef,
    val title: String,
    val description: String,
    val languages: List<String>,
    val contentHash: String,
    val publisherKeyId: String,
    val publisherName: String,
    val conceptNamespace: String,
    val conceptRegistryHash: String,
    val builderProfile: String,
    val capabilities: WikiCapabilities,
)
```

Use Kotlin serialization DTOs with `ignoreUnknownKeys=false`. Reject blank titles, noncanonical IDs, nonpositive versions, invalid hashes, duplicate languages, unsupported generated-page values, and capabilities forbidden by phase one.

- [ ] **Step 4: Inspect the SQLite payload without installing it**

`WikiPackageReader` must extract `content.sqlite` into a caller-owned staging directory, open it with `OPEN_READONLY or NO_LOCALIZED_COLLATORS`, verify `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, `PRAGMA user_version`, exact required tables/FTS4 modules, no triggers/views, and `manifest.wiki.contentHash == SHA-256(content.sqlite)`. Return an inspection object; never mutate repository state. Keep archive/signature/manifest tests on the JVM; cover every real `SQLiteDatabase` operation in the instrumented test.

```kotlin
data class WikiImportInspection(
    val manifest: WikiManifest,
    val publisherFingerprint: PublisherFingerprint,
    val archiveSizeBytes: Long,
    val contentSizeBytes: Long,
    val stagedDatabase: Path,
)
```

- [ ] **Step 5: Generate and sync the signed fixture into Android test assets**

Add a cacheable `Exec` task that runs `scripts/wiki-builder.sh -m tools.wiki_builder.tests.fixture` into `build/generated/wikiFixture`, followed by a `Sync` task into `build/generated/assets/wikiDebugAndroidTest`. Register that directory with the Android test source set and make `mergeDebugAndroidTestAssets` depend on the sync task. Do not check the generated `.hwiki` into Git.

- [ ] **Step 6: Run parsing and inspection tests**

Run: `./gradlew testDebugUnitTest --tests 'com.harnessapk.wiki.WikiManifestParserTest' --tests 'com.harnessapk.wiki.WikiPackageReaderTest'`

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.wiki.WikiPackageReaderInstrumentedTest`

Expected: valid fixture passes; tampered content, bad schema, a trigger, and mismatched hash fail before install. The JVM task never invokes an unmocked Android SQLite API.

- [ ] **Step 7: Commit the package parser**

```bash
git add app/build.gradle.kts app/src/main/java/com/harnessapk/wiki app/src/test/java/com/harnessapk/wiki app/src/androidTest/java/com/harnessapk/wiki/WikiPackageReaderInstrumentedTest.kt
git commit -m "功能：解析并检查 hwiki 包"
```

### Task 3: Persist Wiki Metadata And Install Versions Atomically

**Files:**
- Create: `app/src/main/java/com/harnessapk/storage/WikiEntities.kt`
- Create: `app/src/main/java/com/harnessapk/storage/WikiDao.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/AppDatabase.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Create: `app/src/main/java/com/harnessapk/wiki/WikiFileOps.kt`
- Create: `app/src/main/java/com/harnessapk/wiki/WikiRepository.kt`
- Create: `app/src/test/java/com/harnessapk/wiki/WikiRepositoryTest.kt`
- Create: `app/src/androidTest/java/com/harnessapk/wiki/WikiPackageInstallInstrumentedTest.kt`
- Modify: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`

**Interfaces:**
- Raises Room from version `16` to `17` through `MIGRATION_16_17`.
- Produces `WikiEntity`, `WikiVersionEntity`, `WikiDao`, `WikiFileOps`, and `WikiRepository`.
- Installs under `files/wikis/<wiki-id>/<version>/content.sqlite` with staging under `cache/wiki-import/`.

- [ ] **Step 1: Write the migration and atomic-install tests**

```kotlin
@Test
fun migrate16To17CreatesWikiMetadataOnly() {
    helper.createDatabase(TEST_DB, 16).close()
    helper.runMigrationsAndValidate(TEST_DB, 17, true, AppDatabase.MIGRATION_16_17).use { db ->
        assertTableExists(db, "wikis")
        assertTableExists(db, "wiki_versions")
        assertTableMissing(db, "conversation_wiki_mounts")
    }
}

@Test
fun failedInstallLeavesNeitherMetadataNorFinalDirectory() = runTest {
    fileOps.failBeforeAtomicMove = true
    assertFails { repository.install(confirmedInspection) }
    assertNull(dao.findVersion("fixture.history", 1))
    assertFalse(fileOps.versionDirectory(WikiRef("fixture.history", 1)).exists())
}
```

Also test free space exactly below/at the required peak, a forged negative/overflowing ZIP size, a ratio over `100:1`, and a valid sparse large-file fixture using `Long` throughout.

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.wiki.WikiRepositoryTest`

Expected: compilation fails because Wiki persistence classes do not exist.

- [ ] **Step 3: Add Room metadata and migration `16→17`**

```kotlin
@Entity(tableName = "wikis")
data class WikiEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val activeVersion: Int?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "wiki_versions",
    primaryKeys = ["wikiId", "version"],
    foreignKeys = [ForeignKey(
        entity = WikiEntity::class,
        parentColumns = ["id"],
        childColumns = ["wikiId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class WikiVersionEntity(
    val wikiId: String,
    val version: Int,
    val contentPath: String,
    val schemaVersion: Int,
    val contentHash: String,
    val packageHash: String,
    val publisherKeyId: String,
    val publisherFingerprint: String,
    val manifestJson: String,
    val sizeBytes: Long,
    val enabledForNewConversations: Boolean,
    val state: String,
    val installedAt: Long,
)
```

Add unique `(wikiId, version)`, publisher-fingerprint, active-version, and `enabledForNewConversations` lookup indices. In one transaction, enforce that each `wikiId` has at most one `READY` version enabled for new conversations. Register `wikiDao()` and `MIGRATION_16_17` in both `AppDatabase` and `AppContainer`; never use destructive fallback.

- [ ] **Step 4: Implement atomic install and immutable version rules**

Install sequence: copy URI to a bounded staging archive, inspect, calculate peak required bytes as staged archive size plus expanded database size plus 128 MiB safety margin, and compare with `StatFs.availableBytes` before extraction. Then ask the UI for trust and default-scope confirmation, fsync staged SQLite, atomically move the version directory, and insert Room metadata in one transaction. On any exception, remove staging and final files created by that attempt. An identical installed package is idempotent; the same `WikiRef` with a different package hash is rejected. Activation updates only `wikis.activeVersion`; enabling a version for new conversations disables the former default version of the same Wiki in the same transaction.

- [ ] **Step 5: Implement deletion with explicit storage semantics**

For work package 2, deletion may remove only an unreferenced version. Define a `WikiVersionReferenceChecker` interface returning zero until work package 3 supplies the Room-backed implementation. Delete metadata first only after moving the version directory into a private trash path; restore it if the transaction fails, otherwise remove trash after commit.

- [ ] **Step 6: Run migration and install tests**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.wiki.WikiRepositoryTest`

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.storage.AppDatabaseTest,com.harnessapk.wiki.WikiPackageInstallInstrumentedTest`

Expected: all tests pass; interrupted installs leave no visible partial version.

- [ ] **Step 7: Commit storage and installation**

```bash
git add app/src/main/java/com/harnessapk/storage app/src/main/java/com/harnessapk/wiki app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/test/java/com/harnessapk/wiki app/src/androidTest/java/com/harnessapk
git commit -m "功能：安装并管理 Wiki 版本"
```

### Task 4: Query The Immutable Wiki Database

**Files:**
- Create: `app/src/main/java/com/harnessapk/wiki/WikiContentModels.kt`
- Create: `app/src/main/java/com/harnessapk/wiki/WikiContentStore.kt`
- Create: `app/src/main/java/com/harnessapk/wiki/WikiSourceSearch.kt`
- Create: `app/src/test/java/com/harnessapk/wiki/WikiSourceSearchTest.kt`
- Create: `app/src/androidTest/java/com/harnessapk/wiki/WikiContentStoreInstrumentedTest.kt`

**Interfaces:**
- Produces `WikiDocument`, `WikiSection`, `WikiChunk`, `WikiSummary`, `WikiTerm`, `WikiSourceLocator`, `WikiSourceHit`, `WikiVersionHealthReporter`, and `WikiContentStore.withDatabase(ref, block)`.
- Exposes hierarchy, source search, exact chunk lookup, evidence expansion, terms, aliases, annotations, and links without leaking raw database handles to UI.

- [ ] **Step 1: Write hierarchy, search, and source-locator tests**

```kotlin
@Test
fun originalAndNormalizedChannelsResolveToOriginalText() = withInstalledFixture { store ->
    val result = store.searchSources(WikiRef("fixture.history", 1), "司马光", limit = 20)
    assertTrue(result.isNotEmpty())
    assertTrue(result.first().originalText.contains("司马光"))
    assertNotNull(result.first().locator)
}

@Test
fun unknownIdsAndOversizedLimitsFailClosed() = withInstalledFixture { store ->
    assertNull(store.findChunk(FIXTURE_REF, "missing"))
    assertFailsWith<IllegalArgumentException> { store.searchSources(FIXTURE_REF, "史", limit = 500) }
}
```

Also corrupt the installed fixture after registration and assert that the exact version becomes `INVALID`, no alternate version opens, and already saved citation snapshots remain outside this content-store mutation.

- [ ] **Step 2: Run tests and verify the store is missing**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.wiki.WikiSourceSearchTest`

Expected: compilation fails on `WikiContentStore`.

- [ ] **Step 3: Implement read-only lifecycle and typed queries**

Open by the canonical path recorded in Room, then resolve its real path and require it to stay under `files/wikis/<id>/<version>`. Set `PRAGMA query_only=ON`; use parameterized queries only; close cursors and the database in `finally`. Cap page sizes at 100 and snippets at 320 Unicode code points. If open/integrity/schema verification later fails, mark that exact version `INVALID` with a sanitized reason and exclude it from browsing/default scope; never fall back to another version under the same Wiki ID.

```kotlin
interface WikiContentStore {
    suspend fun listDocuments(ref: WikiRef): List<WikiDocument>
    suspend fun listSections(ref: WikiRef, parentSectionId: String?): List<WikiSection>
    suspend fun findChunk(ref: WikiRef, chunkId: String): WikiChunk?
    suspend fun searchSources(ref: WikiRef, query: String, limit: Int = 20): List<WikiSourceHit>
    suspend fun evidenceFor(ref: WikiRef, ownerType: String, ownerId: String): List<WikiChunk>
}
```

- [ ] **Step 4: Implement deterministic source search**

Query `chunks_original_fts`, `chunks_normalized_fts`, `summaries_fts`, and `terms_aliases_fts`. Use the same normalization and 2/3-gram contract recorded in `build_metadata`; resolve summary hits through `evidence_refs` and term/alias hits through `mentions` plus evidence before ranking source chunks. Fuse channel rankings with RRF `k=60` and hydrate only final IDs from `chunks`. Search results always expose `original_text`, the matching channel/term label, and a validated `source_locators` record; summaries and terms are never returned as quotation text.

- [ ] **Step 5: Run unit and device SQLite tests**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.wiki.WikiSourceSearchTest`

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.wiki.WikiContentStoreInstrumentedTest`

Expected: FTS4 queries work on the minimum supported Android image and all returned quotations match source chunks byte-for-byte after UTF-8 decoding.

- [ ] **Step 6: Commit the read-only content store**

```bash
git add app/src/main/java/com/harnessapk/wiki app/src/test/java/com/harnessapk/wiki app/src/androidTest/java/com/harnessapk/wiki
git commit -m "功能：离线浏览与检索 Wiki 原文"
```

### Task 5: Add Local Import Entry Points And Trust Confirmation

**Files:**
- Create: `app/src/main/java/com/harnessapk/wiki/ExternalWikiPackageIntent.kt`
- Create: `app/src/main/java/com/harnessapk/ui/WikiPackageImportState.kt`
- Modify: `app/src/main/java/com/harnessapk/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Create: `app/src/test/java/com/harnessapk/wiki/ExternalWikiPackageIntentTest.kt`
- Create: `app/src/test/java/com/harnessapk/ui/WikiPackageImportStateTest.kt`
- Modify: `app/src/androidTest/java/com/harnessapk/ui/HarnessApkAppNavigationTest.kt`

**Interfaces:**
- Produces `Intent.wikiPackageUri()`, a reducer-style `WikiPackageImportState`, and one pending-import handoff to the Wiki library route.
- Accepts document picker MIME `application/vnd.harness.hwiki+zip`, plus `application/zip` and `application/octet-stream` only after content inspection confirms `.hwiki` structure.

- [ ] **Step 1: Write intent and reducer tests**

Cover `ACTION_VIEW`, `ACTION_SEND`, persisted read permission, duplicate intents, route recreation, user cancellation, unknown-publisher confirmation, known-publisher fast path, invalid package error, and process-state restoration with only the URI string persisted.

- [ ] **Step 2: Run the focused tests**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.wiki.ExternalWikiPackageIntentTest --tests com.harnessapk.ui.WikiPackageImportStateTest`

Expected: compilation fails on missing Wiki import state.

- [ ] **Step 3: Add manifest filters without hijacking all ZIP files**

Register the vendor MIME directly. For generic ZIP/octet-stream shares, use narrow `ACTION_SEND` handling already owned by `MainActivity`; do not advertise a broad `ACTION_VIEW application/zip` filter. The in-app picker may request all three MIME types and always inspect bytes before routing.

- [ ] **Step 4: Build the explicit trust sheet**

The confirmation sheet shows title, Wiki ID/version, publisher name, key ID, full fingerprint with copy action, archive/expanded size, capability list, and a `用于新会话` switch. The first installed usable Wiki defaults this switch on; later installs preserve the user's choice from this sheet and do not ask again after installation. Primary action is `安装`; secondary action is `取消`. A fingerprint is considered known only while another verified installed version carries it; after the last such version is removed, the next import requires confirmation again. Package updates signed by a different fingerprint always require confirmation.

- [ ] **Step 5: Run intent and navigation tests**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.wiki.ExternalWikiPackageIntentTest --tests com.harnessapk.ui.WikiPackageImportStateTest`

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.HarnessApkAppNavigationTest`

Expected: external and picker imports converge on one reducer flow; cancellation leaves no staged file after cleanup.

- [ ] **Step 6: Commit local import**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/harnessapk/MainActivity.kt app/src/main/java/com/harnessapk/wiki app/src/main/java/com/harnessapk/ui app/src/test/java/com/harnessapk app/src/androidTest/java/com/harnessapk/ui
git commit -m "功能：接入 hwiki 本地导入"
```

### Task 6: Build The Wiki Library, Browser, Search, And Source Reader

**Files:**
- Create: `app/src/main/java/com/harnessapk/ui/wiki/WikiLibraryScreen.kt`
- Create: `app/src/main/java/com/harnessapk/ui/wiki/WikiLibraryUiState.kt`
- Create: `app/src/main/java/com/harnessapk/ui/wiki/WikiBrowserScreen.kt`
- Create: `app/src/main/java/com/harnessapk/ui/wiki/WikiBrowserUiState.kt`
- Create: `app/src/main/java/com/harnessapk/ui/wiki/WikiSearchScreen.kt`
- Create: `app/src/main/java/com/harnessapk/ui/wiki/WikiSourceReaderScreen.kt`
- Create: `app/src/main/java/com/harnessapk/ui/wiki/WikiRoutes.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/settings/SettingsDestinations.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Create: `app/src/test/java/com/harnessapk/ui/wiki/WikiBrowserUiStateTest.kt`
- Modify: `app/src/androidTest/java/com/harnessapk/ui/HarnessApkAppNavigationTest.kt`

**Interfaces:**
- Adds Settings destination key `wikis` and routes `wiki-library`, `wiki/{wikiId}/{version}`, `wiki/{wikiId}/{version}/search`, and `wiki/{wikiId}/{version}/source/{chunkId}`.
- Uses percent-encoded route arguments and validates every decoded ID before querying.

- [ ] **Step 1: Write UI-state tests before Composables**

Test empty library, installed versions, active-version switch, import in progress, hierarchy loading, breadcrumb derivation, source search, exact source opening, deleted-version error, and state restoration from route arguments.

- [ ] **Step 2: Run focused UI-state tests**

Run: `./gradlew testDebugUnitTest --tests com.harnessapk.ui.wiki.WikiBrowserUiStateTest`

Expected: compilation fails because the Wiki UI state does not exist.

- [ ] **Step 3: Implement the library as an operational list**

Use a top app bar with import icon, installed Wiki rows, active version, source count, storage size, `用于新会话` switch, publisher fingerprint action, version menu, activate, and delete. Switching the default version is transactional and does not mutate existing conversations. Do not add marketing copy, nested cards, or a separate home tab. Empty state has one direct action: `导入 .hwiki`.

- [ ] **Step 4: Implement source-first browsing and search**

The browser opens at document hierarchy, not a chat prompt. It shows document/section rows, available summary and term sections only when declared by capabilities, a search icon, and breadcrumbs. Search groups source hits by document and section and highlights matched text without changing the stored quotation. Offer document, period, or term-kind filters only when the package actually contains those facet values; keep the generic UI free of hard-coded history filters.

- [ ] **Step 5: Implement the exact source reader**

Show Wiki title/version, document and section path, locator label, immutable original text, previous/next chunk controls, copy quotation, and `查看关联信息` for evidence-backed terms/annotations. There is no edit control. Dynamic text must wrap without overlapping controls at 320dp width and with font scale 1.3.

- [ ] **Step 6: Add Compose navigation coverage**

Test Settings → Wiki library → browser → search → source → back, deep restoration from source route, active-version changes, and missing-version recovery. Keep route titles `Wiki 知识库`, manifest title, `搜索`, and `原文`.

- [ ] **Step 7: Run UI and navigation tests**

Run: `./gradlew testDebugUnitTest --tests 'com.harnessapk.ui.wiki.*'`

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.HarnessApkAppNavigationTest`

Expected: all tests pass and no Wiki navigation changes Agent package routing.

- [ ] **Step 8: Commit the Wiki browser**

```bash
git add app/src/main/java/com/harnessapk/ui/wiki app/src/main/java/com/harnessapk/ui/settings app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt app/src/test/java/com/harnessapk/ui/wiki app/src/androidTest/java/com/harnessapk/ui
git commit -m "功能：提供 Wiki 浏览与原文阅读"
```

### Task 7: Verify Work Package 2 On Android

**Files:**
- Verify all Task 1-6 files.
- Do not modify production code unless a failing check identifies a defect.

- [ ] **Step 1: Run the full unit suite**

Run: `./gradlew testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run focused instrumented tests on API 26 and the current target image**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.storage.AppDatabaseTest,com.harnessapk.wiki.WikiPackageReaderInstrumentedTest,com.harnessapk.wiki.WikiPackageInstallInstrumentedTest,com.harnessapk.wiki.WikiContentStoreInstrumentedTest,com.harnessapk.ui.HarnessApkAppNavigationTest`

Expected: all selected tests pass on both emulator images; FTS4 is available and source text is identical.

- [ ] **Step 3: Perform the manual import and browse smoke test**

Install the debug APK, import `fixture.history-v1.hwiki` through DocumentsUI, confirm the publisher, close and relaunch the app, open Settings → Wiki 知识库, browse to a source chunk, search `司马光`, and switch away and back to the installed version.

Expected: install survives restart; search opens the exact original source; no conversation is created.

- [ ] **Step 4: Check storage and repository hygiene**

Run: `adb shell run-as com.harnessapk find files/wikis -maxdepth 4 -type f -print`

Expected: one immutable `content.sqlite` under the canonical version directory and no file under `cache/wiki-import`.

Run: `git status --short`

Expected: no APK, `.hwiki`, private key, extracted database, or emulator artifact is tracked or untracked outside ignored build directories.

No final verification commit is needed when the worktree is clean.
