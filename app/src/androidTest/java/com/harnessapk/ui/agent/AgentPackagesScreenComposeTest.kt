package com.harnessapk.ui.agent

import android.graphics.Bitmap
import android.view.ViewGroup
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.room.withTransaction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentRepository
import com.harnessapk.agent.AgentBundleReader
import com.harnessapk.agent.AgentImportPreview
import com.harnessapk.agent.AgentTransactionRunner
import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.V2Bundle
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AgentVersionEntity
import com.harnessapk.storage.AgentVersionPackageEntity
import com.harnessapk.storage.AppDatabase
import com.harnessapk.ui.theme.HarnessApkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.FileOutputStream
import java.util.UUID

class AgentPackagesScreenComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyStateShowsImportActionAndInvokesCallback() {
        var importRequests = 0

        composeRule.setContent {
            HarnessApkTheme {
                AgentPackagesEmptyState(
                    errorText = null,
                    onRequestImport = { importRequests += 1 },
                )
            }
        }

        composeRule.onNodeWithText("还没有智能体包").assertIsDisplayed()
        composeRule.onNodeWithText("导入智能体包").assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals(1, importRequests) }
    }

    @Test
    fun installProgressShowsIndexingFeedback() {
        composeRule.setContent {
            HarnessApkTheme {
                AgentInstallProgress()
            }
        }

        composeRule.onNodeWithText("正在安装智能体并建立资料索引，请保持应用开启。").assertIsDisplayed()
    }

    @Test
    fun enoughSpaceShowsCompactPreviewAndInstallsBalancedDirectly() {
        var installedProfile: String? = null

        composeRule.setContent {
            HarnessApkTheme {
                AgentV2InstallPreview(
                    name = "研究者",
                    version = 2,
                    publisherFingerprint = "fingerprint",
                    plan = plan(),
                    availableBytes = 350L,
                    sourceRecords = emptyList(),
                    isInstalling = false,
                    onDismiss = {},
                    onInstall = { installedProfile = it },
                )
            }
        }

        composeRule.onNodeWithText("推荐安装").assertIsDisplayed()
        composeRule.onNodeWithText("人物身份 · 必装").assertIsDisplayed()
        composeRule.onNodeWithText("核心证据 · 覆盖核心立场与评测").assertIsDisplayed()
        composeRule.onNodeWithText("推荐资料 · 补充谈话、时期和体裁").assertIsDisplayed()
        composeRule.onNodeWithText("准确安装大小：300 B").assertIsDisplayed()
        assertEquals(1, composeRule.onAllNodesWithText("调整资料").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("轻量").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("安装").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("balanced", installedProfile) }
    }

    @Test
    fun lowSpaceShowsOneWarningAndFourProfileAdjustmentWithoutCorpusDialogs() {
        var installedProfile: String? = null
        composeRule.setContent {
            HarnessApkTheme {
                AgentV2InstallPreview(
                    name = "研究者",
                    version = 2,
                    publisherFingerprint = "fingerprint",
                    plan = plan(),
                    availableBytes = 250L,
                    sourceRecords = emptyList(),
                    isInstalling = false,
                    onDismiss = {},
                    onInstall = { installedProfile = it },
                )
            }
        }

        composeRule.onNodeWithText("推荐安装空间不足").assertIsDisplayed()
        composeRule.onNodeWithText("安装").assertIsNotEnabled()
        composeRule.onNodeWithText("调整资料").performClick()
        listOf("轻量", "推荐", "完整证据", "包含原文").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed().assertHasClickAction()
        }
        composeRule.onNodeWithText("轻量").performClick()
        composeRule.onNodeWithText("准确安装大小：200 B").assertIsDisplayed()
        composeRule.onNodeWithText("安装").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("lite", installedProfile) }
        assertEquals(0, composeRule.onAllNodesWithText("选择资料包").fetchSemanticsNodes().size)
    }

    @Test
    fun finalStorageRaceStaysInDialogAndCanRetryTheSameFlowWithLite() {
        val availableBytes = mutableStateOf(350L)
        val inlineFailure = mutableStateOf<AgentPackageInstallAttempt.Failure?>(null)
        val installedProfiles = mutableListOf<String>()
        composeRule.setContent {
            HarnessApkTheme {
                AgentV2InstallPreview(
                    name = "研究者",
                    version = 2,
                    publisherFingerprint = "fingerprint",
                    sessionId = "session-1",
                    plan = plan(),
                    availableBytes = availableBytes.value,
                    sourceRecords = emptyList(),
                    isInstalling = false,
                    installFailure = inlineFailure.value,
                    onDismiss = {},
                    onInstall = { profile ->
                        if (installedProfiles.isEmpty()) {
                            installedProfiles += profile
                            inlineFailure.value = AgentPackageInstallAttempt.Failure(
                                message =
                                    "安装空间不足：需要 500 字节，可用 350 字节。释放空间后重试，或调整资料。",
                                storageFailure = AgentStorageFailure(
                                    sessionId = "session-1",
                                    failedProfileId = "balanced",
                                    packageKind = AgentPackageKind.V2_BUNDLE,
                                    requiredBytes = 500L,
                                    availableBytes = 350L,
                                ),
                            )
                        } else {
                            installedProfiles += profile
                            inlineFailure.value = null
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("安装").performClick()
        composeRule.onNodeWithText(
            "安装空间不足：需要 500 字节，可用 350 字节。释放空间后重试，或调整资料。",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("推荐安装空间不足").assertIsDisplayed()
        composeRule.onNodeWithText("安装").assertIsNotEnabled()
        assertEquals(1, composeRule.onAllNodesWithText("调整资料").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("调整资料").performClick()
        composeRule.onNodeWithText("轻量").performClick()
        assertEquals(
            0,
            composeRule.onAllNodesWithText(
                "安装空间不足：需要 500 字节，可用 350 字节。释放空间后重试，或调整资料。",
            ).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("安装").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("balanced", "lite"), installedProfiles)
        }
    }

    @Test
    fun releasedSpaceClearsKnownFailureAndReenablesSameProfileWithoutStaleText() {
        val availableBytes = mutableStateOf(350L)
        val failure = mutableStateOf<AgentPackageInstallAttempt.Failure?>(
            AgentPackageInstallAttempt.Failure(
                message =
                    "安装空间不足：需要 500 字节，可用 350 字节。释放空间后重试，或调整资料。",
                storageFailure = AgentStorageFailure(
                    sessionId = "session-1",
                    failedProfileId = "balanced",
                    packageKind = AgentPackageKind.V2_BUNDLE,
                    requiredBytes = 500L,
                    availableBytes = 350L,
                ),
            ),
        )
        composeRule.setContent {
            HarnessApkTheme {
                AgentV2InstallPreview(
                    name = "研究者",
                    version = 2,
                    publisherFingerprint = "fingerprint",
                    sessionId = "session-1",
                    plan = plan(),
                    availableBytes = availableBytes.value,
                    sourceRecords = emptyList(),
                    isInstalling = false,
                    installFailure = failure.value,
                    onDismiss = {},
                    onInstall = {},
                )
            }
        }

        composeRule.onNodeWithText(failure.value!!.message).assertIsDisplayed()
        composeRule.onNodeWithText("安装").assertIsNotEnabled()
        composeRule.runOnIdle {
            availableBytes.value = 500L
            failure.value = clearRecoveredStorageFailure(failure.value, 500L)
        }
        assertEquals(
            0,
            composeRule.onAllNodesWithText(
                "安装空间不足：需要 500 字节，可用 350 字节。释放空间后重试，或调整资料。",
            ).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("安装").assertIsEnabled()
        assertEquals(1, composeRule.onAllNodesWithText("调整资料").fetchSemanticsNodes().size)
    }

    @Test
    fun standaloneStorageModalNeverOffersProfileAdjustment() {
        val failure = AgentPackageInstallAttempt.Failure(
            message = "安装空间不足：需要 600 字节，可用 150 字节。释放空间后重试。",
            storageFailure = AgentStorageFailure(
                sessionId = "standalone-session",
                failedProfileId = "balanced",
                packageKind = AgentPackageKind.STANDALONE,
                requiredBytes = 600L,
                availableBytes = 150L,
            ),
        )
        composeRule.setContent {
            HarnessApkTheme {
                AgentImportDialog(
                    preview = AgentImportPreview(
                        agentId = "fixture.agent",
                        name = "研究者",
                        version = 2,
                        summary = "",
                        publisherFingerprint = "fingerprint",
                        corpora = emptyList(),
                        compressedSizeBytes = 100L,
                        includesOriginalSources = false,
                    ),
                    sourceReadOnly = false,
                    isInstalling = false,
                    installFailure = failure,
                    onDismiss = {},
                    onInstall = {},
                )
            }
        }

        composeRule.onNodeWithText(failure.message).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("调整资料", substring = true).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("安装").assertIsEnabled()
    }

    @Test
    fun largeFontPreviewKeepsActionsVisibleAndProvidesScrollableBody() {
        val density = composeRule.activity.resources.displayMetrics.density
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.window.setLayout((360 * density).toInt(), (520 * density).toInt())
        }
        try {
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = LocalDensity.current.density,
                        fontScale = 2f,
                    ),
                ) {
                    HarnessApkTheme {
                        AgentV2InstallPreview(
                            name = "资料研究者",
                            version = 2,
                            publisherFingerprint = "fingerprint-".repeat(16),
                            plan = plan(),
                            availableBytes = 250L,
                            sourceRecords = emptyList(),
                            isInstalling = false,
                            onDismiss = {},
                            onInstall = {},
                        )
                    }
                }
            }

            composeRule.onNodeWithText("调整资料").assertIsDisplayed().performClick()
            composeRule.onNode(hasScrollAction()).assertExists()
            composeRule.onNodeWithText("安装").assertIsDisplayed()
            composeRule.onNodeWithText("收起").assertIsDisplayed()
            assertEquals(0, composeRule.onAllNodesWithText("调整资料").fetchSemanticsNodes().size)
            assertEquals(1, composeRule.onAllNodesWithText("收起").fetchSemanticsNodes().size)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                composeRule.activity.window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        }
    }

    @Test
    fun sourceProfileIsExplicitlyReadOnlyForAnswers() {
        composeRule.setContent {
            HarnessApkTheme {
                AgentV2InstallPreview(
                    name = "研究者",
                    version = 2,
                    publisherFingerprint = "fingerprint",
                    plan = plan(),
                    availableBytes = 550L,
                    sourceRecords = emptyList(),
                    isInstalling = false,
                    initialProfileId = "source",
                    onDismiss = {},
                    onInstall = {},
                )
            }
        }

        composeRule.onNodeWithText("仅阅读核验，不参与回答").assertIsDisplayed()
    }

    @Test
    fun balancedConvenienceBundleDisablesProfilesWhosePackagesAreAbsent() {
        composeRule.setContent {
            HarnessApkTheme {
                AgentV2InstallPreview(
                    name = "研究者",
                    version = 2,
                    publisherFingerprint = "fingerprint",
                    plan = plan(availablePackageIds = listOf("core", "recommended")),
                    availableBytes = Long.MAX_VALUE,
                    sourceRecords = emptyList(),
                    isInstalling = false,
                    onDismiss = {},
                    onInstall = {},
                )
            }
        }

        composeRule.onNodeWithText("调整资料").performClick()
        composeRule.onNodeWithText("轻量").assertIsEnabled()
        composeRule.onNodeWithText("推荐").assertIsEnabled()
        composeRule.onNodeWithText("完整证据").assertIsNotEnabled()
        composeRule.onNodeWithText("包含原文").assertIsNotEnabled()
    }

    @Test
    fun capturesCompactPreviewAndDirectBalancedAcceptanceArtifacts() {
        composeRule.setContent {
            HarnessApkTheme {
                AgentV2InstallPreview(
                    name = "资料研究者",
                    version = 2,
                    publisherFingerprint = "fixture-publisher",
                    plan = plan(),
                    availableBytes = 350L,
                    sourceRecords = emptyList(),
                    isInstalling = false,
                    onDismiss = {},
                    onInstall = {},
                )
            }
        }

        composeRule.onNodeWithText("安装").assertIsEnabled()
        recordUiArtifact("preview")
        recordUiArtifact("direct-balanced")
    }

    @Test
    fun capturesLowSpaceAdjustmentAcceptanceArtifact() {
        composeRule.setContent {
            HarnessApkTheme {
                AgentV2InstallPreview(
                    name = "资料研究者",
                    version = 2,
                    publisherFingerprint = "fixture-publisher",
                    plan = plan(),
                    availableBytes = 250L,
                    sourceRecords = emptyList(),
                    isInstalling = false,
                    onDismiss = {},
                    onInstall = {},
                )
            }
        }

        composeRule.onNodeWithText("调整资料").performClick()
        composeRule.onNodeWithText("推荐安装空间不足").assertIsDisplayed()
        recordUiArtifact("low-space-adjustment")
    }

    @Test
    fun capturesReadyDetailAndStandaloneCoverageAcceptanceArtifacts() {
        val detail = mutableStateOf(roomRecoveredDetail(optionalInstalled = 1, expansionAt = null))
        composeRule.setContent {
            HarnessApkTheme {
                Box(Modifier.padding(16.dp)) {
                    AgentPackageRow(
                        agent = Agent(
                            id = "fixture.researcher",
                            name = "资料研究者",
                            summary = "",
                            activeVersion = 2,
                            publisherFingerprint = "fixture-publisher",
                            status = AgentStatus.READY,
                            requiredCorpusCount = 1,
                            installedCorpusCount = 3,
                        ),
                        detail = detail.value,
                        expanded = true,
                        onToggleDetail = {},
                        onStartConversation = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("运行状态：READY").assertIsDisplayed()
        composeRule.onNodeWithText("开始对话").assertIsEnabled()
        recordUiArtifact("ready-detail")
        composeRule.runOnIdle {
            detail.value = roomRecoveredDetail(optionalInstalled = 2, expansionAt = 1_752_979_200_000L)
        }
        composeRule.onNodeWithText("可选：2/2").assertIsDisplayed()
        composeRule.onNodeWithText("最近资料扩展：", substring = true).assertIsDisplayed()
        recordUiArtifact("standalone-coverage")
    }

    @Test
    fun realRepositoryInstallClickRecomposesRoomBackedPersistentDetail() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = context.cacheDir.resolve("agent-compose-install-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = database.agentDao(),
            conversationDao = database.conversationDao(),
            reader = AgentBundleReader(temporaryDirectory = root.resolve("reader")),
            transactionRunner = AgentTransactionRunner { block -> database.withTransaction { block() } },
            timeProvider = TimeProvider { 1_000L },
            ioDispatcher = Dispatchers.IO,
        )
        val bundleName = "fixture.researcher-v2-balanced.hbundle"
        val session = runBlocking {
            repository.preparePackageImport(bundleName) { assets.open(bundleName) }
        }
        val bundle = session.parsedPackage as V2Bundle
        val installedAgent = mutableStateOf<Agent?>(null)
        val installedDetail = mutableStateOf<AgentPackageDetailUiState?>(null)
        val active = mutableStateOf(true)
        try {
            composeRule.setContent {
                val scope = rememberCoroutineScope()
                HarnessApkTheme {
                    val agent = installedAgent.value
                    if (!active.value) {
                        Box(Modifier.fillMaxSize())
                    } else if (agent == null) {
                        AgentV2InstallPreview(
                            name = bundle.agent.manifest.name,
                            version = bundle.agent.manifest.version,
                            publisherFingerprint = bundle.publisherFingerprint,
                            plan = bundle.toTestInstallationPlan(),
                            availableBytes = Long.MAX_VALUE,
                            sourceRecords = bundle.corpora.flatMap { it.sources },
                            isInstalling = false,
                            onDismiss = {},
                            onInstall = { profileId ->
                                scope.launch {
                                    val result = repository.installPackage(session, profileId)
                                    installedDetail.value = repository.packageDetail(
                                        result.agent.id,
                                        result.agent.activeVersion,
                                    )
                                    installedAgent.value = result.agent
                                }
                            },
                        )
                    } else {
                        AgentPackageRow(
                            agent = agent,
                            detail = installedDetail.value,
                            expanded = true,
                            onToggleDetail = {},
                            onStartConversation = {},
                        )
                    }
                }
            }

            composeRule.onNodeWithText("安装").assertIsEnabled().performClick()
            composeRule.waitUntil(timeoutMillis = 20_000L) {
                installedAgent.value != null && installedDetail.value != null
            }
            composeRule.onNodeWithText("运行状态：READY").assertIsDisplayed()
            composeRule.onNodeWithText("安装档位：推荐").assertIsDisplayed()
            composeRule.onNodeWithText("开始对话").assertIsEnabled()
            val persisted = runBlocking {
                repository.packageDetail("fixture.researcher", 2)
            }
            assertEquals(installedDetail.value, persisted)
        } finally {
            composeRule.runOnIdle { active.value = false }
            database.close()
            root.deleteRecursively()
        }
    }

    private fun recordUiArtifact(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(250L)
        val roots = composeRule.onAllNodes(isRoot(), useUnmergedTree = true)
        val directory = requireNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        ).resolve("phase-b-ui").apply { mkdirs() }
        FileOutputStream(directory.resolve("$name.png")).use { output ->
            val screenshot = requireNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
            )
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val tree = roots.fetchSemanticsNodes().indices.joinToString("\n\n") { index ->
            roots[index].printToString()
        }
        directory.resolve("$name-tree.txt").writeText(tree, Charsets.UTF_8)
    }

    private fun roomRecoveredDetail(
        optionalInstalled: Int,
        expansionAt: Long?,
    ): AgentPackageDetailUiState = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = context.cacheDir.resolve("agent-detail-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            database.agentDao().upsertAgent(
                AgentEntity(
                    id = "fixture.researcher",
                    name = "资料研究者",
                    summary = "",
                    activeVersion = 2,
                    publisherPublicKey = byteArrayOf(1),
                    publisherFingerprint = "fixture-publisher",
                    installSource = "LOCAL_FILE",
                    status = AgentStatus.READY.name,
                    requiredCorpusCount = 1,
                    installedCorpusCount = 1,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            val agentFile = root.resolve("agent.hagent").apply { writeBytes(ByteArray(100)) }
            database.agentDao().insertVersion(
                AgentVersionEntity(
                    agentId = "fixture.researcher",
                    version = 2,
                    schemaVersion = 2,
                    bundlePath = agentFile.absolutePath,
                    bundleSha256 = "a".repeat(64),
                    manifestJson = "{}",
                    persona = "",
                    worldviewJsonl = "",
                    installedAt = 1L,
                    state = AgentStatus.READY.name,
                    lastEvidenceExpandedAt = expansionAt,
                    agentPackageSizeBytes = 100L,
                    selectedProfileId = if (optionalInstalled == 2) "complete" else "balanced",
                ),
            )
            val declarations = listOf(
                Triple("core", "required", true),
                Triple("recommended", "recommended", true),
                Triple("optional-1", "optional", optionalInstalled >= 1),
                Triple("optional-2", "optional", optionalInstalled >= 2),
                Triple("source", "source", false),
            )
            database.agentDao().upsertVersionPackages(
                declarations.map { (id, installClass, installed) ->
                    AgentVersionPackageEntity(
                        agentId = "fixture.researcher",
                        version = 2,
                        packageId = id,
                        type = if (installClass == "source") "hsource" else "hcorpus",
                        fileName = "$id.package",
                        installClass = installClass,
                        packageSha256 = id.padEnd(64, 'a').take(64),
                        packageSizeBytes = 100L,
                        installed = installed,
                        filePath = "",
                        installedAt = 1L.takeIf { installed },
                    )
                },
            )
            requireNotNull(
                AgentRepository(
                    filesDir = root.resolve("files"),
                    cacheDir = root.resolve("cache"),
                    dao = database.agentDao(),
                    timeProvider = TimeProvider { 2L },
                    ioDispatcher = Dispatchers.IO,
                ).packageDetail("fixture.researcher", 2),
            )
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private fun plan(
        availablePackageIds: List<String> = listOf("core", "recommended", "optional", "source"),
    ): AgentInstallationPlan = AgentInstallationPlan(
        agentPackageId = "fixture.hagent",
        agentSizeBytes = 100L,
        packages = listOf(
            AgentInstallationPackage("core", com.harnessapk.agent.V2PackageType.CORPUS, com.harnessapk.agent.V2InstallClass.REQUIRED, 100L),
            AgentInstallationPackage("recommended", com.harnessapk.agent.V2PackageType.CORPUS, com.harnessapk.agent.V2InstallClass.RECOMMENDED, 100L),
            AgentInstallationPackage("optional", com.harnessapk.agent.V2PackageType.CORPUS, com.harnessapk.agent.V2InstallClass.OPTIONAL, 100L),
            AgentInstallationPackage("source", com.harnessapk.agent.V2PackageType.SOURCE, com.harnessapk.agent.V2InstallClass.SOURCE, 100L),
        ),
        profiles = listOf(
            AgentInstallationProfile("lite", listOf("core")),
            AgentInstallationProfile("balanced", listOf("core", "recommended")),
            AgentInstallationProfile("complete", listOf("core", "recommended", "optional")),
            AgentInstallationProfile("source", listOf("core", "recommended", "optional", "source")),
        ),
        requiredPackageIds = listOf("core"),
        availablePackageIds = availablePackageIds,
    )

    private fun V2Bundle.toTestInstallationPlan(): AgentInstallationPlan = AgentInstallationPlan(
        agentPackageId = manifest.agent.fileName,
        agentSizeBytes = manifest.agent.sizeBytes,
        packages = agent.installPlan.packages.map { declaration ->
            AgentInstallationPackage(
                id = declaration.id,
                type = declaration.type,
                installClass = declaration.installClass,
                sizeBytes = declaration.sizeBytes,
            )
        },
        profiles = agent.installPlan.profiles.map { profile ->
            AgentInstallationProfile(profile.id, profile.packageIds)
        },
        requiredPackageIds = agent.installPlan.requiredCorpusIds,
        availablePackageIds = manifest.selectedPackageIds,
    )
}
