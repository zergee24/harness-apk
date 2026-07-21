package com.harnessapk.ui.agent

import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentBundleException
import com.harnessapk.agent.AgentImportPreview
import com.harnessapk.agent.AgentImportSession
import com.harnessapk.agent.AgentImportSessionUnavailableException
import com.harnessapk.agent.AgentPackageManifest
import com.harnessapk.agent.AgentPackageLoadProgress
import com.harnessapk.agent.ParsedAgentBundle
import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.AgentInsufficientStorageException
import com.harnessapk.agent.V2Authorship
import com.harnessapk.agent.V2InstallClass
import com.harnessapk.agent.V2PackageType
import com.harnessapk.agent.V2SourceGenre
import com.harnessapk.agent.V2SourceRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentUiStateTest {
    @Test
    fun finalStorageFailureRefreshesAvailableBytesAndReturnsActionableInlineError() = runTest {
        var statFsReads = 0

        val result = attemptAgentPackageInstall(
            sessionId = "session-1",
            profileId = "balanced",
            packageKind = AgentPackageKind.V2_BUNDLE,
            install = {
                throw AgentInsufficientStorageException(
                    requiredBytes = 300L,
                    availableBytes = 275L,
                )
            },
            refreshAvailableBytes = {
                statFsReads += 1
                250L
            },
        )

        assertEquals(1, statFsReads)
        assertTrue(result is AgentPackageInstallAttempt.Failure)
        result as AgentPackageInstallAttempt.Failure
        assertEquals(
            AgentStorageFailure(
                sessionId = "session-1",
                failedProfileId = "balanced",
                packageKind = AgentPackageKind.V2_BUNDLE,
                requiredBytes = 300L,
                availableBytes = 250L,
            ),
            result.storageFailure,
        )
        assertTrue(result.message.contains("需要 300 字节"))
        assertTrue(result.message.contains("可用 250 字节"))
        assertTrue(result.message.contains("释放空间后重试，或调整资料"))
        assertTrue(result.sessionRetained)
    }

    @Test
    fun standaloneStorageFailureOnlyOffersReleaseSpaceAndRetry() = runTest {
        val result = attemptAgentPackageInstall(
            sessionId = "standalone-session",
            profileId = "balanced",
            packageKind = AgentPackageKind.STANDALONE,
            install = {
                throw AgentInsufficientStorageException(
                    requiredBytes = 600L,
                    availableBytes = 200L,
                )
            },
            refreshAvailableBytes = { 150L },
        ) as AgentPackageInstallAttempt.Failure

        assertEquals(AgentPackageKind.STANDALONE, result.storageFailure?.packageKind)
        assertEquals(
            "安装空间不足：需要 600 字节，可用 150 字节。释放空间后重试。",
            result.message,
        )
        assertFalse(result.message.contains("调整资料"))
        assertTrue(result.sessionRetained)
    }

    @Test
    fun ordinaryInstallFailureIsNotRetainedForEitherPackageKind() = runTest {
        AgentPackageKind.entries.forEach { packageKind ->
            val result = attemptAgentPackageInstall(
                sessionId = "ordinary-failure",
                profileId = "balanced",
                packageKind = packageKind,
                install = { throw AgentBundleException("同一版本内容不同") },
                refreshAvailableBytes = { error("普通失败不应刷新空间") },
            ) as AgentPackageInstallAttempt.Failure

            assertEquals("同一版本内容不同", result.message)
            assertNull(result.storageFailure)
            assertFalse(result.sessionRetained)
        }
    }

    @Test
    fun readyAgentCanStartAndShowsCorpusCoverage() {
        val agent = agent(status = AgentStatus.READY, installed = 2, required = 2)

        assertTrue(canStartAgent(agent))
        assertEquals("可用", agentStatusLabel(agent.status))
        assertEquals("资料 2/2", agentCorpusCoverage(agent))
    }

    @Test
    fun waitingAgentCannotStart() {
        val agent = agent(status = AgentStatus.WAITING_FOR_CORPUS, installed = 1, required = 2)

        assertFalse(canStartAgent(agent))
        assertEquals("缺少资料", agentStatusLabel(agent.status))
    }

    @Test
    fun onlyReadyAgentCanStart() {
        AgentStatus.entries.forEach { status ->
            assertEquals(status == AgentStatus.READY, canStartAgent(agent(status, 1, 1)))
        }
    }

    @Test
    fun balancedInstallsDirectlyWhenExactSignedBytesFit() {
        val decision = installationDecision(plan(), availableBytes = 350L, requestedProfileId = null)

        assertEquals(AgentInstallationDecision.InstallDirectly("balanced"), decision)
    }

    @Test
    fun lowSpaceSuggestsLiteWithoutChangingBalancedSelection() {
        val decision = installationDecision(plan(), availableBytes = 250L, requestedProfileId = null)

        assertEquals(
            AgentInstallationDecision.ShowAdjustment(
                selectedProfileId = "balanced",
                suggestedProfileId = "lite",
                reason = "推荐安装空间不足",
            ),
            decision,
        )
    }

    @Test
    fun actualRepositoryBudgetBlocksOnlyFailedProfileWithinSameSession() {
        val failure = AgentStorageFailure(
            sessionId = "session-1",
            failedProfileId = "balanced",
            packageKind = AgentPackageKind.V2_BUNDLE,
            requiredBytes = 500L,
            availableBytes = 350L,
        )

        assertEquals(
            AgentInstallationDecision.ShowAdjustment(
                selectedProfileId = "balanced",
                suggestedProfileId = "lite",
                reason = "推荐安装空间不足",
            ),
            installationDecision(
                plan = plan(),
                availableBytes = 350L,
                requestedProfileId = "balanced",
                storageFailure = failure,
                sessionId = "session-1",
            ),
        )
        assertEquals(
            AgentInstallationDecision.InstallDirectly("lite"),
            installationDecision(
                plan = plan(),
                availableBytes = 350L,
                requestedProfileId = "lite",
                storageFailure = failure,
                sessionId = "session-1",
            ),
        )
        assertEquals(
            AgentInstallationDecision.InstallDirectly("balanced"),
            installationDecision(
                plan = plan(),
                availableBytes = 350L,
                requestedProfileId = "balanced",
                storageFailure = failure,
                sessionId = "different-session",
            ),
        )
        assertEquals(
            AgentInstallationDecision.InstallDirectly("balanced"),
            installationDecision(
                plan = plan(),
                availableBytes = 500L,
                requestedProfileId = "balanced",
                storageFailure = failure,
                sessionId = "session-1",
            ),
        )
    }

    @Test
    fun storageRefreshOnlyPollsKnownBundleThresholdAndClearsRecoveredFailure() {
        val known = AgentStorageFailure(
            sessionId = "session-1",
            failedProfileId = "balanced",
            packageKind = AgentPackageKind.V2_BUNDLE,
            requiredBytes = 500L,
            availableBytes = 350L,
        )
        val unknown = known.copy(requiredBytes = -1L)
        val standalone = known.copy(packageKind = AgentPackageKind.STANDALONE)
        val failure = AgentPackageInstallAttempt.Failure(
            message = "旧错误",
            storageFailure = known,
        )

        assertTrue(shouldAutoRefreshStorageFailure(known, "session-1"))
        assertFalse(shouldAutoRefreshStorageFailure(unknown, "session-1"))
        assertFalse(shouldAutoRefreshStorageFailure(standalone, "session-1"))
        assertFalse(shouldAutoRefreshStorageFailure(known, "different-session"))
        assertEquals(failure, clearRecoveredStorageFailure(failure, availableBytes = 499L))
        assertNull(clearRecoveredStorageFailure(failure, availableBytes = 500L))
        assertEquals(
            AgentPackageInstallAttempt.Failure("普通错误"),
            clearRecoveredStorageFailure(
                AgentPackageInstallAttempt.Failure("普通错误"),
                availableBytes = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun explicitCompleteAndSourceSelectionsInstallDirectlyWhenTheyFit() {
        assertEquals(
            AgentInstallationDecision.InstallDirectly("complete"),
            installationDecision(plan(), availableBytes = 450L, requestedProfileId = "complete"),
        )
        assertEquals(
            AgentInstallationDecision.InstallDirectly("source"),
            installationDecision(plan(), availableBytes = 550L, requestedProfileId = "source"),
        )
    }

    @Test
    fun explicitSelectionIsPreservedAndLargestRunnableLowerProfileIsSuggested() {
        assertEquals(
            AgentInstallationDecision.ShowAdjustment(
                selectedProfileId = "source",
                suggestedProfileId = "complete",
                reason = "所选资料空间不足",
            ),
            installationDecision(plan(), availableBytes = 450L, requestedProfileId = "source"),
        )
    }

    @Test
    fun profileSelectionIsLimitedByCurrentBundleAvailability() {
        val balancedBundle = plan(availablePackageIds = listOf("core", "recommended"))

        assertEquals(
            AgentInstallationDecision.InstallDirectly("balanced"),
            installationDecision(balancedBundle, availableBytes = 1_000L, requestedProfileId = null),
        )
        assertEquals(
            AgentInstallationDecision.BlockUnavailableProfile(
                profileId = "complete",
                missingPackageIds = listOf("optional"),
            ),
            installationDecision(balancedBundle, availableBytes = 1_000L, requestedProfileId = "complete"),
        )
        assertEquals(
            AgentInstallationDecision.BlockUnavailableProfile(
                profileId = "source",
                missingPackageIds = listOf("optional", "source"),
            ),
            installationDecision(balancedBundle, availableBytes = 1_000L, requestedProfileId = "source"),
        )
        assertTrue(profileAvailableInBundle(balancedBundle, "lite"))
        assertTrue(profileAvailableInBundle(balancedBundle, "balanced"))
        assertFalse(profileAvailableInBundle(balancedBundle, "complete"))
        assertFalse(profileAvailableInBundle(balancedBundle, "source"))
    }

    @Test
    fun lowSpaceSuggestionOnlyUsesProfilesPresentInCurrentBundle() {
        val completeOnlyBundle = plan(
            availablePackageIds = listOf("core", "recommended", "optional"),
        )

        assertEquals(
            AgentInstallationDecision.ShowAdjustment(
                selectedProfileId = "complete",
                suggestedProfileId = "balanced",
                reason = "所选资料空间不足",
            ),
            installationDecision(completeOnlyBundle, availableBytes = 350L, requestedProfileId = "complete"),
        )
    }

    @Test
    fun requiredShortageAndOverflowFailClosed() {
        assertEquals(
            AgentInstallationDecision.BlockMissingRequired(listOf("core")),
            installationDecision(plan(), availableBytes = 199L, requestedProfileId = "lite"),
        )
        assertTrue(
            installationDecision(
                plan(agentSizeBytes = Long.MAX_VALUE, coreSizeBytes = 1L),
                availableBytes = Long.MAX_VALUE,
                requestedProfileId = null,
            ) is AgentInstallationDecision.BlockMissingRequired,
        )
        assertTrue(
            installationDecision(plan(agentSizeBytes = 0L), Long.MAX_VALUE, null) is
                AgentInstallationDecision.BlockMissingRequired,
        )
        assertTrue(
            installationDecision(plan(coreSizeBytes = -1L), Long.MAX_VALUE, null) is
                AgentInstallationDecision.BlockMissingRequired,
        )
    }

    @Test
    fun unknownDuplicateAndMissingRequiredIdsFailClosed() {
        val duplicatePackage = plan().copy(
            packages = plan().packages + plan().packages.first(),
        )
        val duplicateProfilePackage = plan().copy(
            profiles = plan().profiles.map { profile ->
                if (profile.id == "lite") profile.copy(packageIds = listOf("core", "core")) else profile
            },
        )
        val unknownProfilePackage = plan().copy(
            profiles = plan().profiles.map { profile ->
                if (profile.id == "balanced") profile.copy(packageIds = profile.packageIds + "unknown") else profile
            },
        )
        val missingRequired = plan().copy(
            profiles = plan().profiles.map { profile ->
                if (profile.id == "lite") profile.copy(packageIds = emptyList()) else profile
            },
        )

        listOf(duplicatePackage, duplicateProfilePackage, unknownProfilePackage, missingRequired).forEach { malformed ->
            assertTrue(
                installationDecision(malformed, Long.MAX_VALUE, null) is
                    AgentInstallationDecision.BlockMissingRequired,
            )
        }
        assertTrue(
            installationDecision(plan(), Long.MAX_VALUE, "unknown") is
                AgentInstallationDecision.BlockMissingRequired,
        )
    }

    @Test
    fun malformedProfileClassMembershipAndPackageTypesFailClosed() {
        val malformedPlans = listOf(
            plan().copy(
                profiles = plan().profiles.map { profile ->
                    if (profile.id == "lite") profile.copy(packageIds = listOf("core", "source")) else profile
                },
            ),
            plan().copy(
                profiles = plan().profiles.map { profile ->
                    if (profile.id == "balanced") profile.copy(packageIds = listOf("core")) else profile
                },
            ),
            plan().copy(
                profiles = plan().profiles.map { profile ->
                    if (profile.id == "complete") {
                        profile.copy(packageIds = listOf("core", "recommended"))
                    } else {
                        profile
                    }
                },
            ),
            plan().copy(
                profiles = plan().profiles.map { profile ->
                    if (profile.id == "source") {
                        profile.copy(packageIds = listOf("core", "recommended", "optional"))
                    } else {
                        profile
                    }
                },
            ),
            plan().copy(
                packages = plan().packages.map { declaration ->
                    if (declaration.id == "source") declaration.copy(type = V2PackageType.CORPUS) else declaration
                },
            ),
            plan().copy(
                packages = plan().packages.map { declaration ->
                    if (declaration.id == "optional") declaration.copy(type = V2PackageType.SOURCE) else declaration
                },
            ),
        )

        malformedPlans.forEach { malformed ->
            assertTrue(
                installationDecision(malformed, Long.MAX_VALUE, null) is
                    AgentInstallationDecision.BlockMissingRequired,
            )
        }
    }

    @Test
    fun sourceLabelIsReadOnlyAndWrittenPersonaWarningUsesExactMetadataIntersection() {
        assertEquals("仅阅读核验，不参与回答", sourceParticipationLabel())
        assertTrue(
            shouldShowWrittenPersonaWarning(
                listOf(source("essay", V2SourceGenre.ESSAY, V2Authorship.DIRECT)),
            ),
        )
        assertTrue(
            shouldShowWrittenPersonaWarning(
                listOf(source("speech-secondary", V2SourceGenre.SPEECH, V2Authorship.SECONDARY)),
            ),
        )
        assertFalse(
            shouldShowWrittenPersonaWarning(
                listOf(source("interview-direct", V2SourceGenre.INTERVIEW, V2Authorship.EDITED_DIRECT)),
            ),
        )
    }

    @Test
    fun formatsPackageSizeWithoutExcessPrecision() {
        assertEquals("900 B", formatAgentPackageSize(900))
        assertEquals("1.5 MB", formatAgentPackageSize(1_572_864))
    }

    @Test
    fun packageLoadingProgressUsesSourceSizeForItsRealFractionAndLabel() {
        val progress = AgentPackageLoadProgress.Copying(copiedBytes = 786_432L, totalBytes = 1_572_864L)

        assertEquals(0.5f, agentPackageLoadFraction(progress))
        assertEquals("已读取 768.0 KB / 1.5 MB", agentPackageLoadDetail(progress))
    }

    @Test
    fun packageLoadingProgressDoesNotInventPercentWhenSourceSizeIsUnknown() {
        val progress = AgentPackageLoadProgress.Copying(copiedBytes = 1_572_864L, totalBytes = null)

        assertNull(agentPackageLoadFraction(progress))
        assertEquals("已读取 1.5 MB", agentPackageLoadDetail(progress))
    }

    @Test
    fun packageLoadingProgressIdentifiesValidationStage() {
        assertEquals("正在校验智能体包完整性", agentPackageLoadDetail(AgentPackageLoadProgress.Validating))
    }

    @Test
    fun replacingPreviewDiscardsNewSessionWhenPreviousDiscardFails() = runTest {
        val discarded = mutableListOf<String>()
        val viewModel = AgentImportPreviewViewModel<AgentImportSession>(
            discardImport = { session ->
                discarded += session.id
                if (session.id == "previous") throw AgentBundleException("previous expired")
            },
            stagedFile = AgentImportSession::stagedFile,
        )
        val previous = session("previous")
        val replacement = session("replacement")
        viewModel.replace(previous)

        assertTrue(runCatching { viewModel.replace(replacement) }.isFailure)
        assertTrue(viewModel.session.value === previous)
        assertEquals(listOf("previous", "replacement"), discarded)
    }

    @Test
    fun concurrentPreviewSelectionsDiscardLoserAndKeepCurrentSession() = runTest {
        val firstDiscardStarted = CompletableDeferred<Unit>()
        val allowFirstDiscard = CompletableDeferred<Unit>()
        var previousDiscardCalls = 0
        val discarded = mutableListOf<String>()
        val viewModel = AgentImportPreviewViewModel<AgentImportSession>(
            discardImport = { session ->
                discarded += session.id
                if (session.id == "previous" && ++previousDiscardCalls == 1) {
                    firstDiscardStarted.complete(Unit)
                    allowFirstDiscard.await()
                }
            },
            stagedFile = AgentImportSession::stagedFile,
        )
        val previous = session("previous")
        val first = session("first")
        val second = session("second")
        viewModel.replace(previous)

        val firstReplacement = async { viewModel.replace(first) }
        firstDiscardStarted.await()
        assertTrue(viewModel.replace(second))
        allowFirstDiscard.complete(Unit)

        assertFalse(firstReplacement.await())
        assertTrue(viewModel.session.value === second)
        assertTrue("first" in discarded)
    }

    @Test
    fun failedNewSessionCleanupDoesNotLeaveItsStagingFileOrReplaceValidPreview() = runTest {
        val viewModel = AgentImportPreviewViewModel<AgentImportSession>(
            discardImport = { session -> throw AgentBundleException("cannot discard ${session.id}") },
            stagedFile = AgentImportSession::stagedFile,
        )
        val previous = session("previous")
        val replacement = session("replacement", createStagedFile = true)
        viewModel.replace(previous)

        assertTrue(runCatching { viewModel.replace(replacement) }.isFailure)
        assertTrue(viewModel.session.value === previous)
        assertFalse(replacement.stagedFile.exists())
    }

    @Test
    fun dismissClearsPreviewWhenRepositoryConfirmsSessionIsUnavailable() = runTest {
        val viewModel = AgentImportPreviewViewModel<AgentImportSession>(
            discardImport = { throw AgentImportSessionUnavailableException() },
            stagedFile = AgentImportSession::stagedFile,
        )
        val expired = session("expired")
        viewModel.replace(expired)

        assertTrue(viewModel.discardIfCurrent(expired))
        assertNull(viewModel.session.value)
    }

    @Test
    fun dismissRetainsPreviewWhenRepositoryCleanupCanBeRetried() = runTest {
        val viewModel = AgentImportPreviewViewModel<AgentImportSession>(
            discardImport = { throw AgentBundleException("delete failed") },
            stagedFile = AgentImportSession::stagedFile,
        )
        val retryable = session("retryable")
        viewModel.replace(retryable)

        assertTrue(runCatching { viewModel.discardIfCurrent(retryable) }.isFailure)
        assertTrue(viewModel.session.value === retryable)
    }

    @Test
    fun cancelledDismissRetainsTheOnlyPreviewReference() = runTest {
        val discardStarted = CompletableDeferred<Unit>()
        val keepDiscardPending = CompletableDeferred<Unit>()
        val viewModel = AgentImportPreviewViewModel<AgentImportSession>(
            discardImport = {
                discardStarted.complete(Unit)
                keepDiscardPending.await()
            },
            stagedFile = AgentImportSession::stagedFile,
        )
        val session = session("cancelled")
        viewModel.replace(session)

        val dismiss = launch { viewModel.discardIfCurrent(session) }
        discardStarted.await()
        dismiss.cancelAndJoin()

        assertTrue(viewModel.session.value === session)
    }

    @Test
    fun replacingUnavailablePreviewKeepsTheNewSession() = runTest {
        val viewModel = AgentImportPreviewViewModel<AgentImportSession>(
            discardImport = { session ->
                if (session.id == "expired") throw AgentImportSessionUnavailableException()
            },
            stagedFile = AgentImportSession::stagedFile,
        )
        val expired = session("expired")
        val replacement = session("replacement")
        viewModel.replace(expired)

        assertTrue(viewModel.replace(replacement))
        assertTrue(viewModel.session.value === replacement)
    }

    private fun agent(status: AgentStatus, installed: Int, required: Int): Agent = Agent(
        id = "agent-1",
        name = "资料研究代理",
        summary = "基于资料模拟",
        activeVersion = 1,
        publisherFingerprint = "fingerprint",
        status = status,
        requiredCorpusCount = required,
        installedCorpusCount = installed,
    )

    private fun session(id: String, createStagedFile: Boolean = false): AgentImportSession {
        val staged = File.createTempFile("agent-preview-$id", ".hbundle").apply {
            if (!createStagedFile) delete()
        }
        val bundle = ParsedAgentBundle(
            file = staged,
            packageSha256 = "sha-$id",
            publisherPublicKey = byteArrayOf(1),
            publisherFingerprint = "publisher",
            manifestJson = "{}",
            agent = AgentPackageManifest(id, id, 1, "", "", "", "", "", "", emptyList()),
            corpora = emptyList(),
            persona = "",
            worldviewJsonl = "",
            compressedSizeBytes = 0L,
            uncompressedSizeBytes = 0L,
        )
        return AgentImportSession(
            id = id,
            stagedFile = staged,
            parsedBundle = bundle,
            preview = AgentImportPreview(id, id, 1, "", "publisher", emptyList(), 0L, false),
        )
    }

    private fun plan(
        agentSizeBytes: Long = 100L,
        coreSizeBytes: Long = 100L,
        availablePackageIds: List<String> = listOf("core", "recommended", "optional", "source"),
    ): AgentInstallationPlan = AgentInstallationPlan(
        agentPackageId = "fixture.hagent",
        agentSizeBytes = agentSizeBytes,
        packages = listOf(
            AgentInstallationPackage("core", V2PackageType.CORPUS, V2InstallClass.REQUIRED, coreSizeBytes),
            AgentInstallationPackage("recommended", V2PackageType.CORPUS, V2InstallClass.RECOMMENDED, 100L),
            AgentInstallationPackage("optional", V2PackageType.CORPUS, V2InstallClass.OPTIONAL, 100L),
            AgentInstallationPackage("source", V2PackageType.SOURCE, V2InstallClass.SOURCE, 100L),
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

    private fun source(
        id: String,
        genre: V2SourceGenre,
        authorship: V2Authorship,
    ): V2SourceRecord = V2SourceRecord(
        sourceId = id,
        title = id,
        fileName = "$id.md",
        storedName = "$id.md",
        sourceHash = "a".repeat(64),
        format = "md",
        genre = genre,
        authorship = authorship,
        period = "1926",
        rawSizeBytes = 1L,
        extractedChars = 1L,
    )
}
