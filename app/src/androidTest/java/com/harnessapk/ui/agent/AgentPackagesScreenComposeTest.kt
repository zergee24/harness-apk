package com.harnessapk.ui.agent

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.FileOutputStream

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
        val detail = mutableStateOf(detail(optionalInstalled = 1, expansionAt = null))
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
            detail.value = detail(optionalInstalled = 2, expansionAt = 1_752_979_200_000L)
        }
        composeRule.onNodeWithText("可选：2/2").assertIsDisplayed()
        composeRule.onNodeWithText("最近资料扩展：", substring = true).assertIsDisplayed()
        recordUiArtifact("standalone-coverage")
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

    private fun detail(optionalInstalled: Int, expansionAt: Long?) = AgentPackageDetailUiState(
        schemaVersion = 2,
        selectedProfileId = "balanced",
        exactInstalledBytes = 300L + optionalInstalled * 100L,
        required = AgentPackageCount(1, 1),
        recommended = AgentPackageCount(1, 1),
        optional = AgentPackageCount(optionalInstalled, 2),
        source = AgentPackageCount(0, 1),
        lastEvidenceExpansionAt = expansionAt,
        missingRequiredPackageIds = emptyList(),
    )

    private fun plan(): AgentInstallationPlan = AgentInstallationPlan(
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
    )
}
