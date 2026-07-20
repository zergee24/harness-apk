package com.harnessapk.agent

import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentChunkEntity
import com.harnessapk.storage.AgentChunkFtsEntity
import com.harnessapk.storage.AgentCorpusChunkCrossRef
import com.harnessapk.storage.AgentCorpusEntity
import com.harnessapk.storage.AgentCorpusHierarchyCrossRef
import com.harnessapk.storage.AgentCorpusSourceCrossRef
import com.harnessapk.storage.AgentDao
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AgentHierarchyFtsEntity
import com.harnessapk.storage.AgentHierarchyNodeEntity
import com.harnessapk.storage.AgentSourceFileEntity
import com.harnessapk.storage.AgentVersionCorpusCrossRef
import com.harnessapk.storage.AgentVersionEntity
import com.harnessapk.storage.AgentVersionPackageEntity
import com.harnessapk.storage.AgentVersionSourceCrossRef
import com.harnessapk.storage.ConversationDao
import com.harnessapk.storage.ConversationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.Executors

class AgentRetrievalTest {
    @Test
    fun v1CompatibilitySessionRejectsCopiesForgeriesExpiryAndReuse() = runTest {
        val root = Files.createTempDirectory("agent-v1-session-test").toFile().apply { deleteOnExit() }
        val source = root.resolve("source.hbundle").apply { writeText("validated package") }
        val parsed = v1ParsedBundle(source)
        var now = 10L
        val reader = FakeAgentBundleAccess(parsedBundle = parsed)
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = FakeAgentDao(),
            reader = reader,
            timeProvider = TimeProvider { now },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val legitimate = repository.prepareImport("source.hbundle") { source.inputStream() }
        val copied = legitimate.copy()
        assertTrue(runCatching { repository.install(copied) }.exceptionOrNull() is AgentBundleException)

        val forged = AgentImportSession(
            id = "forged",
            stagedFile = legitimate.stagedFile,
            parsedBundle = legitimate.parsedBundle,
            preview = legitimate.preview,
        )
        assertTrue(runCatching { repository.install(forged) }.exceptionOrNull() is AgentBundleException)

        val expiring = repository.prepareImport("source.hbundle") { source.inputStream() }
        now += 24L * 60L * 60L * 1000L
        assertTrue(runCatching { repository.install(expiring) }.exceptionOrNull() is AgentBundleException)

        now = 10L
        val singleUse = repository.prepareImport("source.hbundle") { source.inputStream() }
        repository.install(singleUse)
        assertTrue(runCatching { repository.install(singleUse) }.exceptionOrNull() is AgentBundleException)
    }

    @Test
    fun unifiedV2BalancedInstallIsSingleUseAndUsesBoundedChunkBatches() = runTest {
        val fixture = v2InstallFixture(chunkCount = 450)
        val session = fixture.repository.preparePackageImport("balanced.hbundle") {
            "bundle".byteInputStream()
        }

        val result = fixture.repository.installPackage(session, profileId = "balanced")

        assertEquals(AgentStatus.READY, result.agent.status)
        assertEquals(1, result.agent.requiredCorpusCount)
        assertEquals(1, result.agent.installedCorpusCount)
        assertEquals(450, fixture.dao.chunks.size)
        assertTrue(fixture.dao.maxChunkBatchSize <= 200)
        assertEquals(1, fixture.dao.sources.size)
        assertFalse(session.stagedFile.exists())
        val reused = runCatching { fixture.repository.installPackage(session) }.exceptionOrNull()
        assertTrue(reused is AgentBundleException)
    }

    @Test
    fun v2InstallRecapturesPrivateFilesystemSpaceAndFailsBeforeFinalCopy() = runTest {
        var availableBytes = 16L
        var captures = 0
        val fixture = v2InstallFixture(
            privateInstallAvailableBytes = {
                captures += 1
                availableBytes
            },
        )
        val blocked = fixture.repository.preparePackageImport("balanced.hbundle") {
            "bundle".byteInputStream()
        }

        val failure = runCatching { fixture.repository.installPackage(blocked, "balanced") }.exceptionOrNull()

        assertTrue(failure is AgentBundleException)
        assertEquals(1, captures)
        assertTrue(fixture.dao.findAgent("agent-v2") == null)
        assertTrue(fixture.repositoryRoot.resolve("files").walkTopDown().none(File::isFile))

        availableBytes = 17L
        val installable = fixture.repository.preparePackageImport("balanced.hbundle") {
            "bundle".byteInputStream()
        }
        assertEquals(AgentStatus.READY, fixture.repository.installPackage(installable, "balanced").agent.status)
        assertEquals(2, captures)
    }

    @Test
    fun balancedConvenienceBundleCanInstallLiteWithoutCopyingUnselectedSourcePackage() = runTest {
        val fixture = v2InstallFixture()
        val session = fixture.repository.preparePackageImport("balanced.hbundle") {
            "bundle".byteInputStream()
        }

        val result = fixture.repository.installPackage(session, profileId = "lite")

        assertEquals(AgentStatus.READY, result.agent.status)
        assertFalse(fixture.dao.versionPackages.getValue("agent-v2:2:source-package").installed)
        assertTrue(
            fixture.repositoryRoot.resolve("files/agents/sources").walkTopDown().none(File::isFile),
        )
    }

    @Test
    fun unifiedImportRejectsExternallyModifiedSessionWithoutInstallingAnything() = runTest {
        val fixture = v2InstallFixture()
        val session = fixture.repository.preparePackageImport("balanced.hbundle") {
            "bundle".byteInputStream()
        }
        session.stagedFile.appendText("tampered")

        val failure = runCatching { fixture.repository.installPackage(session) }.exceptionOrNull()

        assertTrue(failure is AgentBundleException)
        assertTrue(fixture.dao.findAgent("agent-v2") == null)
        assertFalse(session.stagedFile.exists())
    }

    @Test
    fun installAndVersionDeleteAreSerializedAcrossFileAndDatabaseLifecycle() = runTest {
        val coordinator = AgentLifecycleCoordinator()
        val fileOps = BlockingFinalMoveAgentFileOps("agent.hagent")
        val fixture = v2InstallFixture(
            fileOps = fileOps,
            lifecycleCoordinator = coordinator,
            conversationDao = RemovalConversationDao(referenceCount = 0),
        )
        val session = fixture.repository.preparePackageImport("agent.hagent") {
            "agent".byteInputStream()
        }

        val install = async { fixture.repository.installPackage(session) }
        fileOps.moveEntered.await()
        val removal = async { fixture.repository.removeVersion("agent-v2", 2) }
        val removalCompletedBeforeInstall = removal.isCompleted
        fileOps.releaseMove.complete(Unit)

        assertEquals(AgentInstallOutcome.INSTALLED, install.await().outcome)
        assertFalse(removalCompletedBeforeInstall)
        assertEquals(AgentVersionRemovalOutcome.REMOVED, removal.await().outcome)
        assertTrue(fixture.dao.findAgent("agent-v2") == null)
    }

    @Test
    fun discardAndInstallCompareOwnedIdentityInsideTheSameLifecycleBoundary() = runTest {
        val coordinator = AgentLifecycleCoordinator()
        val fileOps = BlockingFinalMoveAgentFileOps("agent.hagent")
        val fixture = v2InstallFixture(
            fileOps = fileOps,
            lifecycleCoordinator = coordinator,
        )
        val session = fixture.repository.preparePackageImport("agent.hagent") {
            "agent".byteInputStream()
        }

        val install = async { fixture.repository.installPackage(session) }
        fileOps.moveEntered.await()
        val discard = async {
            runCatching { fixture.repository.discardPackageImport(session) }.exceptionOrNull()
        }
        val discardCompletedBeforeInstall = discard.isCompleted
        fileOps.releaseMove.complete(Unit)

        assertEquals(AgentInstallOutcome.INSTALLED, install.await().outcome)
        assertFalse(discardCompletedBeforeInstall)
        assertTrue(discard.await() is AgentBundleException)
        assertEquals(AgentStatus.WAITING_FOR_CORPUS, fixture.repository.agent("agent-v2")!!.status)
    }

    @Test
    fun setEnabledWaitsForTheSharedLifecycleBoundary() = runTest {
        val coordinator = AgentLifecycleCoordinator()
        val fixture = optionalRemovalFixture(required = false, lifecycleCoordinator = coordinator)
        val lockEntered = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val holder = launch {
            coordinator.serialized {
                lockEntered.complete(Unit)
                releaseLock.await()
            }
        }
        lockEntered.await()

        val enabling = async { fixture.repository.setAgentEnabled("agent-remove", enabled = false) }
        val completedWhileLifecycleBusy = enabling.isCompleted
        releaseLock.complete(Unit)
        holder.join()
        enabling.await()

        assertFalse(completedWhileLifecycleBusy)
        assertEquals(AgentStatus.DISABLED, fixture.repository.agent("agent-remove")!!.status)
    }

    @Test
    fun discardFailsClosedForCopiesUnknownSessionsAndConsumedSessionsWithoutDeletingCallerPaths() = runTest {
        val fixture = v2InstallFixture()
        val session = fixture.repository.preparePackageImport("agent.hagent") {
            "agent".byteInputStream()
        }
        val forgedPath = fixture.repositoryRoot.resolve("forged.package").apply { writeText("caller-owned") }
        val copied = session.copy(stagedFile = forgedPath)

        assertTrue(
            runCatching { fixture.repository.discardPackageImport(copied) }.exceptionOrNull() is AgentBundleException,
        )
        assertTrue(forgedPath.isFile)
        assertTrue(session.stagedFile.isFile)

        val unknownPath = fixture.repositoryRoot.resolve("unknown.package").apply { writeText("caller-owned") }
        assertTrue(
            runCatching {
                fixture.repository.discardPackageImport(session.copy(id = "unknown", stagedFile = unknownPath))
            }.exceptionOrNull() is AgentBundleException,
        )
        assertTrue(unknownPath.isFile)

        fixture.repository.installPackage(session)
        val consumedPath = fixture.repositoryRoot.resolve("consumed.package").apply { writeText("caller-owned") }
        assertTrue(
            runCatching {
                fixture.repository.discardPackageImport(session.copy(stagedFile = consumedPath))
            }.exceptionOrNull() is AgentBundleException,
        )
        assertTrue(consumedPath.isFile)
    }

    @Test
    fun discardDeleteFailureKeepsOwnedSessionRetryable() = runTest {
        val fileOps = FailingAgentFileOps(failDelete = true)
        val fixture = v2InstallFixture(fileOps = fileOps)
        val session = fixture.repository.preparePackageImport("agent.hagent") {
            "agent".byteInputStream()
        }

        val failure = runCatching {
            fixture.repository.discardPackageImport(session)
        }.exceptionOrNull()

        assertTrue(failure is AgentBundleException)
        assertTrue(session.stagedFile.isFile)

        fileOps.failDelete = false
        fixture.repository.discardPackageImport(session)

        assertFalse(session.stagedFile.exists())
        assertTrue(
            runCatching { fixture.repository.discardPackageImport(session) }.exceptionOrNull() is AgentBundleException,
        )
    }

    @Test
    fun consumedSessionInstallsOnlyFromPrivateSnapshotWhenOriginalPathIsReplaced() = runTest {
        val fileOps = StagedMutationAgentFileOps(StagedMutationMode.REPLACE_PATH)
        val fixture = v2InstallFixture(fileOps = fileOps)
        val session = fixture.repository.preparePackageImport("agent.hagent") {
            "agent".byteInputStream()
        }
        fileOps.stagedFile = session.stagedFile

        val result = fixture.repository.installPackage(session)

        assertEquals(AgentInstallOutcome.INSTALLED, result.outcome)
        assertEquals(
            "agent",
            fixture.repositoryRoot.resolve("files/agents/agent-v2/2/agent.hagent").readText(),
        )
        assertFalse(session.stagedFile.exists())
    }

    @Test
    fun consumedSessionRejectsInPlaceOverwriteObservedWhileBuildingPrivateSnapshot() = runTest {
        val fileOps = StagedMutationAgentFileOps(StagedMutationMode.OVERWRITE_IN_PLACE)
        val fixture = v2InstallFixture(fileOps = fileOps)
        val session = fixture.repository.preparePackageImport("agent.hagent") {
            "agent".byteInputStream()
        }
        fileOps.stagedFile = session.stagedFile

        val failure = runCatching { fixture.repository.installPackage(session) }.exceptionOrNull()

        assertTrue(failure is AgentBundleException)
        assertTrue(fixture.dao.findAgent("agent-v2") == null)
        assertFalse(session.stagedFile.exists())
        assertTrue(
            fixture.repositoryRoot.resolve("cache/agent-install-snapshots")
                .listFiles().orEmpty().none(File::isFile),
        )
    }

    @Test
    fun v1CompatibilityInstallConsumesValidatesAndCleansUpOnIoDispatcher() = runTest {
        val root = Files.createTempDirectory("agent-v1-dispatcher-test").toFile().apply { deleteOnExit() }
        val source = root.resolve("source.hbundle").apply { writeText("validated package") }
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "agent-v1-io")
        }.asCoroutineDispatcher()
        val observedThreads = mutableListOf<String>()
        try {
            val repository = AgentRepository(
                filesDir = root.resolve("files"),
                cacheDir = root.resolve("cache"),
                dao = FakeAgentDao(),
                reader = FakeAgentBundleAccess(parsedBundle = v1ParsedBundle(source)),
                timeProvider = TimeProvider {
                    observedThreads += Thread.currentThread().name
                    10L
                },
                ioDispatcher = dispatcher,
            )
            val session = repository.prepareImport("source.hbundle") { source.inputStream() }
            observedThreads.clear()

            repository.install(session)

            assertTrue(observedThreads.isNotEmpty())
            assertTrue(observedThreads.all { it == "agent-v1-io" })
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun missingRequiredEvidenceKeepsV2VersionWaiting() = runTest {
        val fixture = v2InstallFixture(requiredEvidenceId = "missing-evidence")
        val session = fixture.repository.preparePackageImport("balanced.hbundle") {
            "bundle".byteInputStream()
        }

        val result = fixture.repository.installPackage(session)

        assertEquals(AgentStatus.WAITING_FOR_CORPUS, result.agent.status)
        assertEquals(AgentStatus.WAITING_FOR_CORPUS.name, fixture.dao.version!!.state)
    }

    @Test
    fun recommendedEvaluationAttributionDoesNotBlockReadyWhenEvidenceIsRequired() = runTest {
        val fixture = v2InstallFixture(evaluationCorpusId = "corpus-recommended")
        val session = fixture.repository.preparePackageImport("balanced.hbundle") {
            "bundle".byteInputStream()
        }

        val result = fixture.repository.installPackage(session)

        assertEquals(AgentStatus.READY, result.agent.status)
        assertEquals(AgentStatus.READY.name, fixture.dao.version!!.state)
    }

    @Test
    fun standaloneRequiredCorpusCompletesWaitingVersionWithoutChangingActiveVersion() = runTest {
        val fixture = v2InstallFixture()
        val agentSession = fixture.repository.preparePackageImport("agent.hagent") {
            "agent".byteInputStream()
        }
        val waiting = fixture.repository.installPackage(agentSession)
        val activeVersion = waiting.agent.activeVersion
        val corpusSession = fixture.repository.preparePackageImport("core.hcorpus") {
            "corpus".byteInputStream()
        }

        val completed = fixture.repository.installPackage(corpusSession)

        assertEquals(AgentStatus.READY, completed.agent.status)
        assertEquals(activeVersion, completed.agent.activeVersion)
        assertEquals(1, fixture.dao.versionCorpora.size)
        assertEquals("corpus-core", fixture.dao.versionCorpora.single().corpusId)
    }

    @Test
    fun standaloneCorpusOnlyRefreshesTargetVersionAndNeverEnablesDisabledAgent() = runTest {
        val historical = v2InstallFixture()
        historical.repository.installPackage(
            historical.repository.preparePackageImport("agent.hagent") { "agent".byteInputStream() },
        )
        val stored = requireNotNull(historical.dao.findAgent("agent-v2"))
        historical.dao.upsertAgent(
            stored.copy(
                activeVersion = 3,
                status = AgentStatus.READY.name,
                requiredCorpusCount = 7,
                installedCorpusCount = 6,
            ),
        )

        historical.repository.installPackage(
            historical.repository.preparePackageImport("core.hcorpus") { "corpus".byteInputStream() },
        )

        val unchanged = requireNotNull(historical.dao.findAgent("agent-v2"))
        assertEquals(3, unchanged.activeVersion)
        assertEquals(AgentStatus.READY.name, unchanged.status)
        assertEquals(7, unchanged.requiredCorpusCount)
        assertEquals(6, unchanged.installedCorpusCount)
        assertEquals(AgentStatus.READY.name, historical.dao.version!!.state)

        val disabled = v2InstallFixture()
        disabled.repository.installPackage(
            disabled.repository.preparePackageImport("agent.hagent") { "agent".byteInputStream() },
        )
        disabled.repository.setAgentEnabled("agent-v2", enabled = false)
        disabled.repository.installPackage(
            disabled.repository.preparePackageImport("core.hcorpus") { "corpus".byteInputStream() },
        )
        assertEquals(AgentStatus.DISABLED, disabled.repository.agent("agent-v2")!!.status)
        assertEquals(AgentStatus.READY.name, disabled.dao.version!!.state)
    }

    @Test
    fun standaloneCorpusRejectsExistingHashNamedFileWithDifferentValidPackageIdentity() = runTest {
        val fixture = v2InstallFixture(existingCorpusTransform = { corpus ->
            corpus.copy(
                manifestJson = """{"different":true}""",
                manifest = corpus.manifest.copy(agentId = "other-agent"),
            )
        })
        fixture.repository.installPackage(
            fixture.repository.preparePackageImport("agent.hagent") { "agent".byteInputStream() },
        )
        fixture.repositoryRoot.resolve("files/agents/packages").apply { mkdirs() }
            .resolve("$V2_CORPUS_PACKAGE_HASH.hcorpus")
            .writeText("existing-corpus")

        val failure = runCatching {
            fixture.repository.installPackage(
                fixture.repository.preparePackageImport("core.hcorpus") { "corpus".byteInputStream() },
            )
        }.exceptionOrNull()

        assertTrue(failure is AgentBundleException)
        assertTrue(fixture.dao.versionCorpora.isEmpty())
        assertEquals(AgentStatus.WAITING_FOR_CORPUS.name, fixture.dao.version!!.state)
    }

    @Test
    fun v2PhysicalChunkReuseRequiresAllImmutableMetadata() = runTest {
        val mutations = listOf<(AgentChunkEntity) -> AgentChunkEntity>(
            { it.copy(conflictKey = "different") },
            { it.copy(sourceAliasesJson = """["other-source"]""") },
            { it.copy(simHash = "f".repeat(16)) },
        )
        mutations.forEachIndexed { index, mutate ->
            val fixture = v2InstallFixture()
            fixture.dao.chunks["${V2_SOURCE_HASH}:chunk-0"] = mutate(v2StoredChunk())

            val failure = runCatching {
                fixture.repository.installPackage(
                    fixture.repository.preparePackageImport("balanced-$index.hbundle") {
                        "bundle".byteInputStream()
                    },
                )
            }.exceptionOrNull()

            assertTrue("metadata mutation $index", failure is AgentBundleException)
        }

        val matching = v2InstallFixture()
        matching.dao.chunks["${V2_SOURCE_HASH}:chunk-0"] = v2StoredChunk()
        val result = matching.repository.installPackage(
            matching.repository.preparePackageImport("balanced.hbundle") { "bundle".byteInputStream() },
        )
        assertEquals(AgentInstallOutcome.INSTALLED, result.outcome)
    }

    @Test
    fun requiredAndPersistedMessageReferencedCorporaCannotBeRemoved() = runTest {
        val required = optionalRemovalFixture(required = true)
        assertEquals(
            AgentCorpusRemovalOutcome.REQUIRED,
            required.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra").outcome,
        )

        val referenced = optionalRemovalFixture(required = false)
        referenced.dao.referencedChunkKeys += "source-extra:chunk-extra"
        assertEquals(
            AgentCorpusRemovalOutcome.REFERENCED,
            referenced.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra").outcome,
        )
        assertEquals(1, referenced.dao.corpusChunkRefs.size)
    }

    @Test
    fun legacyAgentSourcesMetadataConservativelyBlocksOptionalRemoval() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        fixture.dao.legacyAgentSourceVersions += "agent-remove:1"

        val result = fixture.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra")

        assertEquals(AgentCorpusRemovalOutcome.REFERENCED, result.outcome)
        assertEquals(1, fixture.dao.corpusChunkRefs.size)
    }

    @Test
    fun legacyAgentSourcesFromUnrelatedAgentVersionDoNotBlockOptionalRemoval() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        fixture.dao.legacyAgentSourceVersions += "agent-other:9"

        val result = fixture.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra")

        assertEquals(AgentCorpusRemovalOutcome.REMOVED, result.outcome)
    }

    @Test
    fun removingSharedCorpusReferenceKeepsPhysicalEvidenceUntilFinalReference() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        fixture.dao.versionCorpora = fixture.dao.versionCorpora +
            fixture.dao.versionCorpora.single().copy(agentId = "agent-other", version = 9)

        val first = fixture.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra")

        assertEquals(AgentCorpusRemovalOutcome.REMOVED, first.outcome)
        assertEquals(1, fixture.dao.chunks.size)
        assertEquals(1, fixture.dao.searchRows.size)
        assertEquals(1, fixture.dao.corpusChunkRefs.size)

        val final = fixture.repository.removeOptionalCorpus("agent-other", 9, "corpus-extra")

        assertEquals(AgentCorpusRemovalOutcome.REMOVED, final.outcome)
        assertTrue(fixture.dao.chunks.isEmpty())
        assertTrue(fixture.dao.searchRows.isEmpty())
        assertTrue(fixture.dao.corpusChunkRefs.isEmpty())
    }

    @Test
    fun disabledAgentLeavesFixedVersionRuntimeResolvable() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        fixture.dao.searchResult = listOf("source-extra:chunk-extra")

        fixture.repository.setAgentEnabled("agent-remove", enabled = false)

        assertEquals(AgentStatus.DISABLED, fixture.repository.agent("agent-remove")!!.status)
        assertTrue(fixture.dao.listReadyAgents().none { it.id == "agent-remove" })
        assertEquals(1, fixture.repository.runtimeContext("agent-remove", 1, "证据")!!.evidence.size)
    }

    @Test
    fun historicalCoverageUsesRequestedVersionCrossReferences() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        fixture.dao.version = fixture.dao.version!!.copy(requiredCorpusCount = 2)
        fixture.dao.versionCorpora = fixture.dao.versionCorpora + AgentVersionCorpusCrossRef(
            "agent-remove", 1, "corpus-required", "required-hash", true,
        )

        val coverage = fixture.repository.versionCoverage("agent-remove", 1)

        assertEquals(2, coverage.requiredCorpusCount)
        assertEquals(1, coverage.installedRequiredCorpusCount)
        assertEquals(2, coverage.installedCorpusCount)
    }

    @Test
    fun standaloneSourceDeduplicatesOnDiskWithoutCreatingEvidenceRows() = runTest {
        val fixture = v2InstallFixture()
        fixture.repository.installPackage(
            fixture.repository.preparePackageImport("agent.hagent") { "agent".byteInputStream() },
        )

        val first = fixture.repository.installPackage(
            fixture.repository.preparePackageImport("source.hsource") { "source".byteInputStream() },
        )
        val second = fixture.repository.installPackage(
            fixture.repository.preparePackageImport("source.hsource") { "source".byteInputStream() },
        )

        assertEquals(AgentInstallOutcome.INSTALLED, first.outcome)
        assertEquals(AgentInstallOutcome.ALREADY_INSTALLED, second.outcome)
        val stored = fixture.dao.sources.values.single()
        assertTrue(File(stored.filePath).isFile)
        assertEquals("raw-source", File(stored.filePath).readText())
        assertTrue(fixture.dao.chunks.isEmpty())
        assertTrue(fixture.dao.searchRows.isEmpty())
    }

    @Test
    fun standaloneSourceUsesCanonicalHashPayloadPathRegardlessOfStoredName() = runTest {
        val fixture = v2InstallFixture()
        fixture.repository.installPackage(
            fixture.repository.preparePackageImport("agent.hagent") { "agent".byteInputStream() },
        )
        fixture.repository.installPackage(
            fixture.repository.preparePackageImport("source.hsource") { "source".byteInputStream() },
        )

        val stored = fixture.dao.sources.values.single()
        val sourceDirectory = fixture.repositoryRoot.resolve("files/agents/sources/$V2_SOURCE_HASH")
        assertEquals(sourceDirectory.resolve("payload").absolutePath, stored.filePath)
        assertEquals(listOf("payload"), sourceDirectory.listFiles().orEmpty().map(File::getName))
        assertFalse(sourceDirectory.resolve(stored.storedName).exists())
    }

    @Test
    fun recoveryMovesLegacyStoredNameSourceToCanonicalPayloadAndUpdatesAllDescriptors() = runTest {
        val fixture = v2InstallFixture()
        val sourceDirectory = fixture.repositoryRoot.resolve("files/agents/sources/$V2_SOURCE_HASH").apply {
            mkdirs()
        }
        val legacy = sourceDirectory.resolve("legacy-name.txt").apply { writeText("raw-source") }
        fixture.dao.sources["source-1:$V2_SOURCE_HASH"] = AgentSourceFileEntity(
            sourceId = "source-1",
            sourceHash = V2_SOURCE_HASH,
            title = "来源",
            fileName = "source.txt",
            storedName = "legacy-name.txt",
            format = "txt",
            genre = "essay",
            authorship = "direct",
            period = "test",
            rawSizeBytes = legacy.length(),
            filePath = legacy.absolutePath,
            packageSha256 = "package",
            installedAt = 1L,
        )

        fixture.repository.recoverFileLifecycle()

        val canonical = sourceDirectory.resolve("payload")
        assertTrue(canonical.isFile)
        assertFalse(legacy.exists())
        assertEquals(canonical.canonicalPath, fixture.dao.sources.values.single().filePath)
    }

    @Test
    fun recoveryRepairsDatabaseWhenLegacyMoveCompletedBeforeDatabaseCommit() = runTest {
        val fixture = v2InstallFixture()
        val sourceDirectory = fixture.repositoryRoot.resolve("files/agents/sources/$V2_SOURCE_HASH").apply {
            mkdirs()
        }
        val missingLegacy = sourceDirectory.resolve("legacy-name.txt")
        val canonical = sourceDirectory.resolve("payload").apply { writeText("raw-source") }
        fixture.dao.sources["source-1:$V2_SOURCE_HASH"] = AgentSourceFileEntity(
            sourceId = "source-1",
            sourceHash = V2_SOURCE_HASH,
            title = "来源",
            fileName = "source.txt",
            storedName = "legacy-name.txt",
            format = "txt",
            genre = "essay",
            authorship = "direct",
            period = "test",
            rawSizeBytes = canonical.length(),
            filePath = missingLegacy.absolutePath,
            packageSha256 = "package",
            installedAt = 1L,
        )

        fixture.repository.recoverFileLifecycle()

        assertFalse(missingLegacy.exists())
        assertTrue(canonical.isFile)
        assertEquals(canonical.canonicalPath, fixture.dao.sources.values.single().filePath)
    }

    @Test
    fun recoveryUpdatesOnlyInstalledDescriptorsWhenSameHashAlsoHasCorpusOnlyDescriptor() = runTest {
        val fixture = v2InstallFixture()
        val sourceDirectory = fixture.repositoryRoot.resolve("files/agents/sources/$V2_SOURCE_HASH").apply {
            mkdirs()
        }
        val legacy = sourceDirectory.resolve("legacy-name.txt").apply { writeText("raw-source") }
        val installed = AgentSourceFileEntity(
            sourceId = "source-installed",
            sourceHash = V2_SOURCE_HASH,
            title = "已安装来源",
            fileName = "source.txt",
            storedName = "legacy-name.txt",
            format = "txt",
            genre = "essay",
            authorship = "direct",
            period = "test",
            rawSizeBytes = legacy.length(),
            filePath = legacy.absolutePath,
            packageSha256 = "package",
            installedAt = 1L,
        )
        val corpusOnly = installed.copy(sourceId = "source-corpus-only", filePath = "")
        fixture.dao.sources["${installed.sourceId}:${installed.sourceHash}"] = installed
        fixture.dao.sources["${corpusOnly.sourceId}:${corpusOnly.sourceHash}"] = corpusOnly

        fixture.repository.recoverFileLifecycle()

        val canonical = sourceDirectory.resolve("payload").canonicalPath
        assertEquals(canonical, fixture.dao.sources.getValue("${installed.sourceId}:${installed.sourceHash}").filePath)
        assertEquals("", fixture.dao.sources.getValue("${corpusOnly.sourceId}:${corpusOnly.sourceHash}").filePath)
        assertFalse(legacy.exists())
    }

    @Test
    fun removingOneOfTwoCorporaWithSamePayloadHashKeepsPayloadUntilLastReferenceIsRemoved() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        val sourceHash = "a97cc769a7c4eaff85eb7322caf37f0f0189a24580a80295154d05242027916c"
        val payload = fixture.repositoryRoot.resolve("files/agents/sources/$sourceHash/payload").apply {
            parentFile?.mkdirs()
            writeText("shared source")
        }
        fun source(sourceId: String) = AgentSourceFileEntity(
            sourceId = sourceId,
            sourceHash = sourceHash,
            title = sourceId,
            fileName = "$sourceId.txt",
            storedName = "$sourceId.txt",
            format = "txt",
            genre = "essay",
            authorship = "direct",
            period = "test",
            rawSizeBytes = payload.length(),
            filePath = payload.absolutePath,
            packageSha256 = "package-$sourceId",
            installedAt = 1L,
        )
        fixture.dao.sources["source-a:$sourceHash"] = source("source-a")
        fixture.dao.sources["source-b:$sourceHash"] = source("source-b")
        fixture.dao.corpusSourceRefs += AgentCorpusSourceCrossRef(
            "corpus-extra", "corpus-hash", "source-a", sourceHash,
        )
        fixture.dao.corpora["corpus-shared:shared-corpus-hash"] = AgentCorpusEntity(
            "corpus-shared", "shared-corpus-hash", "共享资料", 1L, 1L,
        )
        fixture.dao.versionCorpora = fixture.dao.versionCorpora + AgentVersionCorpusCrossRef(
            "agent-remove", 1, "corpus-shared", "shared-corpus-hash", false,
        )
        fixture.dao.corpusSourceRefs += AgentCorpusSourceCrossRef(
            "corpus-shared", "shared-corpus-hash", "source-b", sourceHash,
        )

        assertEquals(
            AgentCorpusRemovalOutcome.REMOVED,
            fixture.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra").outcome,
        )
        assertTrue(payload.isFile)

        assertEquals(
            AgentCorpusRemovalOutcome.REMOVED,
            fixture.repository.removeOptionalCorpus("agent-remove", 1, "corpus-shared").outcome,
        )
        assertFalse(payload.exists())
    }

    @Test
    fun removingVersionKeepsPayloadReferencedByAnotherAgentVersionWithDifferentSourceId() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        val sourceHash = "a97cc769a7c4eaff85eb7322caf37f0f0189a24580a80295154d05242027916c"
        val payload = fixture.repositoryRoot.resolve("files/agents/sources/$sourceHash/payload").apply {
            parentFile?.mkdirs()
            writeText("shared source")
        }
        fun source(sourceId: String) = AgentSourceFileEntity(
            sourceId, sourceHash, sourceId, "$sourceId.txt", "$sourceId.txt", "txt", "essay", "direct",
            "test", payload.length(), payload.absolutePath, "package-$sourceId", 1L,
        )
        fixture.dao.sources["source-a:$sourceHash"] = source("source-a")
        fixture.dao.sources["source-b:$sourceHash"] = source("source-b")
        fixture.dao.versionSources += AgentVersionSourceCrossRef("agent-remove", 1, "source-a", sourceHash)
        fixture.dao.versionSources += AgentVersionSourceCrossRef("agent-other", 2, "source-b", sourceHash)
        val repository = AgentRepository(
            filesDir = fixture.repositoryRoot.resolve("files"),
            cacheDir = fixture.repositoryRoot.resolve("cache"),
            dao = fixture.dao,
            conversationDao = RemovalConversationDao(referenceCount = 0),
            timeProvider = TimeProvider { 20L },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertEquals(AgentVersionRemovalOutcome.REMOVED, repository.removeVersion("agent-remove", 1).outcome)
        assertTrue(payload.isFile)
    }

    @Test
    fun installingNewV1OrV2ContentNeverImplicitlyEnablesDisabledAgent() = runTest {
        val v1Root = Files.createTempDirectory("agent-v1-disabled-test").toFile().apply { deleteOnExit() }
        val v1Source = v1Root.resolve("source.hbundle").apply { writeText("validated package") }
        val v1Dao = FakeAgentDao().apply {
            upsertAgent(
                AgentEntity(
                    "agent-v1-session", "V1 Session", "", 0, byteArrayOf(1), "publisher-fingerprint",
                    "LOCAL_FILE", AgentStatus.DISABLED.name, 0, 0, 1L, 1L,
                ),
            )
        }
        val v1Repository = AgentRepository(
            filesDir = v1Root.resolve("files"),
            cacheDir = v1Root.resolve("cache"),
            dao = v1Dao,
            reader = FakeAgentBundleAccess(parsedBundle = v1ParsedBundle(v1Source)),
            timeProvider = TimeProvider { 10L },
            ioDispatcher = Dispatchers.Unconfined,
        )

        v1Repository.install(v1Repository.prepareImport("source.hbundle") { v1Source.inputStream() })

        assertEquals(AgentStatus.DISABLED, v1Repository.agent("agent-v1-session")!!.status)
        assertEquals(AgentStatus.READY.name, v1Dao.version!!.state)

        listOf("agent", "bundle").forEach { marker ->
            val fixture = v2InstallFixture()
            fixture.dao.upsertAgent(
                AgentEntity(
                    "agent-v2", "V2 人格", "", 1, byteArrayOf(1), "publisher-v2",
                    "LOCAL_FILE", AgentStatus.DISABLED.name, 0, 0, 1L, 1L,
                ),
            )

            fixture.repository.installPackage(
                fixture.repository.preparePackageImport("$marker.package") {
                    marker.byteInputStream()
                },
            )

            assertEquals(AgentStatus.DISABLED, fixture.repository.agent("agent-v2")!!.status)
            val versionState = fixture.dao.version!!.state
            assertTrue(versionState != AgentStatus.DISABLED.name)
            fixture.repository.setAgentEnabled("agent-v2", enabled = true)
            assertEquals(AgentStatus.valueOf(versionState), fixture.repository.agent("agent-v2")!!.status)
        }
    }

    @Test
    fun existingSourceHashRejectsConflictingDescriptorBeforeReuse() = runTest {
        val fixture = v2InstallFixture()
        fixture.repository.installPackage(
            fixture.repository.preparePackageImport("agent.hagent") { "agent".byteInputStream() },
        )
        val canonical = fixture.repositoryRoot
            .resolve("files/agents/sources/$V2_SOURCE_HASH/payload")
            .apply {
                parentFile?.mkdirs()
                writeText("raw-source")
            }
        fixture.dao.sources["source-1:$V2_SOURCE_HASH"] = AgentSourceFileEntity(
            sourceId = "source-1",
            sourceHash = V2_SOURCE_HASH,
            title = "来源",
            fileName = "conflicting-name.txt",
            storedName = "legacy-name.txt",
            format = "txt",
            genre = V2SourceGenre.UNKNOWN.wireName,
            authorship = V2Authorship.UNKNOWN.wireName,
            period = "",
            rawSizeBytes = canonical.length(),
            filePath = canonical.absolutePath,
            packageSha256 = "old-package",
            installedAt = 1L,
        )

        val session = fixture.repository.preparePackageImport("source.hsource") {
            "source".byteInputStream()
        }
        val failure = runCatching { fixture.repository.installPackage(session) }.exceptionOrNull()

        assertTrue(failure is AgentBundleException)
        assertFalse(session.stagedFile.exists())
    }

    @Test
    fun optionalRemovalWaitsForConcurrentReferenceWriteInsideSharedLifecycleBoundary() = runTest {
        val coordinator = AgentLifecycleCoordinator()
        val fixture = optionalRemovalFixture(required = false, lifecycleCoordinator = coordinator)
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = launch {
            coordinator.serialized {
                writerEntered.complete(Unit)
                releaseWriter.await()
                fixture.dao.referencedChunkKeys += "source-extra:chunk-extra"
            }
        }
        writerEntered.await()
        val removal = async {
            fixture.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra")
        }

        assertFalse(removal.isCompleted)
        releaseWriter.complete(Unit)
        writer.join()

        assertEquals(AgentCorpusRemovalOutcome.REFERENCED, removal.await().outcome)
        assertEquals(1, fixture.dao.corpusChunkRefs.size)
    }

    @Test
    fun failedFinalMoveLeavesNoInstalledStateAndConsumesSession() = runTest {
        val fileOps = FailingAgentFileOps(failMoveDestinationName = "agent.hagent")
        val fixture = v2InstallFixture(fileOps = fileOps)
        val session = fixture.repository.preparePackageImport("agent.hagent") {
            "agent".byteInputStream()
        }

        val failure = runCatching { fixture.repository.installPackage(session) }.exceptionOrNull()

        assertTrue(failure is AgentBundleException)
        assertTrue(fixture.dao.findAgent("agent-v2") == null)
        assertFalse(session.stagedFile.exists())
        assertTrue(runCatching { fixture.repository.installPackage(session) }.isFailure)
    }

    @Test
    fun committedRemovalReportsPendingCleanupAndRecoversPersistentTombstone() = runTest {
        val fileOps = FailingAgentFileOps(failDelete = true)
        val fixture = optionalRemovalFixture(required = false, fileOps = fileOps)
        val packageFile = fixture.repositoryRoot.resolve("files/agents/packages/optional.hcorpus").apply {
            parentFile?.mkdirs()
            writeText("optional")
        }
        fixture.dao.versionPackages["agent-remove:1:corpus-extra"] = AgentVersionPackageEntity(
            agentId = "agent-remove",
            version = 1,
            packageId = "corpus-extra",
            type = V2PackageType.CORPUS.wireName,
            fileName = "optional.hcorpus",
            installClass = V2InstallClass.OPTIONAL.wireName,
            packageSha256 = "hash",
            packageSizeBytes = packageFile.length(),
            installed = true,
            filePath = packageFile.absolutePath,
            installedAt = 1L,
        )

        val result = fixture.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra")

        assertEquals(AgentCorpusRemovalOutcome.REMOVED_CLEANUP_PENDING, result.outcome)
        assertFalse(packageFile.exists())
        assertTrue(
            fixture.repositoryRoot.resolve("files/agents/.tombstones")
                .listFiles().orEmpty().any { it.name.endsWith(".data") },
        )

        fileOps.failDelete = false
        fixture.repository.recoverFileLifecycle()
        assertTrue(
            fixture.repositoryRoot.resolve("files/agents/.tombstones")
                .listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun standaloneCorpusRejectsUndeclaredPublisherHashSizeAndVersionMismatches() = runTest {
        val mismatches = listOf<(V2Corpus) -> V2Corpus>(
            { corpus -> corpus.copy(manifest = corpus.manifest.copy(id = "undeclared")) },
            { corpus -> corpus.copy(publisherFingerprint = "wrong-publisher") },
            { corpus -> corpus.copy(packageSha256 = "f".repeat(64)) },
            { corpus -> corpus.copy(compressedSizeBytes = corpus.compressedSizeBytes + 1) },
            { corpus -> corpus.copy(manifest = corpus.manifest.copy(version = 99)) },
        )
        mismatches.forEachIndexed { index, mutate ->
            val fixture = v2InstallFixture(
                standaloneCorpusTransform = mutate,
            )
            fixture.repository.installPackage(
                fixture.repository.preparePackageImport("agent-$index.hagent") { "agent".byteInputStream() },
            )

            val failure = runCatching {
                fixture.repository.installPackage(
                    fixture.repository.preparePackageImport("core.hcorpus") {
                        "corpus".byteInputStream()
                    },
                )
            }.exceptionOrNull()

            assertTrue("mismatch $index", failure is AgentBundleException)
            assertEquals(AgentStatus.WAITING_FOR_CORPUS, fixture.repository.agent("agent-v2")!!.status)
            assertTrue(fixture.dao.versionCorpora.isEmpty())
        }
    }

    @Test
    fun importCopyFailureLeavesNoStagingSessionOrFile() = runTest {
        val fixture = v2InstallFixture()

        val failure = runCatching {
            fixture.repository.preparePackageImport("broken.hbundle") {
                throw java.io.IOException("copy failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is AgentBundleException)
        assertTrue(
            fixture.repositoryRoot.resolve("cache/agent-staging")
                .listFiles().orEmpty().none(File::isFile),
        )
    }

    @Test
    fun largeImportStagingChecksCancellationBetweenBuffersAndCleansPartialFile() = runTest {
        val fixture = v2InstallFixture()
        val prepare = async {
            val job = currentCoroutineContext().job
            var reads = 0
            fixture.repository.preparePackageImport("cancelled.hbundle") {
                object : InputStream() {
                    override fun read(): Int = 0

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        reads += 1
                        if (reads == 2) job.cancel()
                        buffer.fill(1, offset, offset + length)
                        return length
                    }
                }
            }
        }

        assertTrue(runCatching { prepare.await() }.exceptionOrNull() is CancellationException)
        assertTrue(
            fixture.repositoryRoot.resolve("cache/agent-staging")
                .listFiles().orEmpty().none(File::isFile),
        )
    }

    @Test
    fun batchEvidenceReadyAndCancellationFailuresRollbackDatabaseAndFiles() = runTest {
        val cases = listOf(
            V2InstallFailure.BATCH_INSERT,
            V2InstallFailure.EVIDENCE_CHECK,
            V2InstallFailure.READY_TRANSITION,
            V2InstallFailure.CANCEL_DURING_CHUNKS,
        )
        cases.forEach { failurePoint ->
            val fixture = v2InstallFixture(chunkCount = 450, failure = failurePoint)
            val session = fixture.repository.preparePackageImport("$failurePoint.hbundle") {
                "bundle".byteInputStream()
            }

            val failure = runCatching { fixture.repository.installPackage(session) }.exceptionOrNull()

            if (failurePoint == V2InstallFailure.CANCEL_DURING_CHUNKS) {
                assertTrue(failure is CancellationException)
            } else {
                assertTrue("$failurePoint: $failure", failure is IllegalStateException)
            }
            assertTrue(fixture.dao.findAgent("agent-v2") == null)
            assertTrue(fixture.dao.chunks.isEmpty())
            assertTrue(fixture.dao.corpora.isEmpty())
            assertFalse(session.stagedFile.exists())
            assertTrue(
                fixture.repositoryRoot.resolve("files").walkTopDown().none(File::isFile),
            )
            assertTrue(runCatching { fixture.repository.installPackage(session) }.isFailure)
        }
    }

    @Test
    fun referencedVersionCannotBeDeletedAndLastUnreferencedVersionRemovesAgent() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        val conversationDao = RemovalConversationDao(referenceCount = 1)
        val repository = AgentRepository(
            filesDir = fixture.repositoryRoot.resolve("files"),
            cacheDir = fixture.repositoryRoot.resolve("cache"),
            dao = fixture.dao,
            conversationDao = conversationDao,
            timeProvider = TimeProvider { 20L },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertEquals(
            AgentVersionRemovalOutcome.REFERENCED,
            repository.removeVersion("agent-remove", 1).outcome,
        )
        conversationDao.referenceCount = 0
        assertEquals(
            AgentVersionRemovalOutcome.REMOVED,
            repository.removeVersion("agent-remove", 1).outcome,
        )
        assertTrue(fixture.dao.findAgent("agent-remove") == null)
    }

    @Test
    fun versionRemovalWaitsForConcurrentConversationPinInsideSharedLifecycleBoundary() = runTest {
        val coordinator = AgentLifecycleCoordinator()
        val fixture = optionalRemovalFixture(required = false, lifecycleCoordinator = coordinator)
        val conversationDao = RemovalConversationDao(referenceCount = 0)
        val repository = AgentRepository(
            filesDir = fixture.repositoryRoot.resolve("files"),
            cacheDir = fixture.repositoryRoot.resolve("cache"),
            dao = fixture.dao,
            conversationDao = conversationDao,
            lifecycleCoordinator = coordinator,
            timeProvider = TimeProvider { 20L },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val pinEntered = CompletableDeferred<Unit>()
        val releasePin = CompletableDeferred<Unit>()
        val pin = launch {
            coordinator.serialized {
                pinEntered.complete(Unit)
                releasePin.await()
                conversationDao.referenceCount = 1
            }
        }
        pinEntered.await()
        val removal = async { repository.removeVersion("agent-remove", 1) }

        assertFalse(removal.isCompleted)
        releasePin.complete(Unit)
        pin.join()

        assertEquals(AgentVersionRemovalOutcome.REFERENCED, removal.await().outcome)
        assertTrue(fixture.dao.findVersion("agent-remove", 1) != null)
    }

    @Test
    fun purgesOnlyExpiredImportStagingFiles() {
        val directory = Files.createTempDirectory("agent-staging-test").toFile().apply { deleteOnExit() }
        val expired = directory.resolve("expired.hbundle").apply {
            writeText("expired")
            setLastModified(1L)
        }
        val current = directory.resolve("current.hbundle").apply {
            writeText("current")
            setLastModified(86_400_001L)
        }

        purgeStaleImportFiles(directory, nowMillis = 86_400_002L)

        assertFalse(expired.exists())
        assertTrue(current.exists())
    }

    @Test
    fun installsValidatedBundleIntoExistingAppStore() = runTest {
        val dao = FakeAgentDao()
        val root = Files.createTempDirectory("agent-install-test").toFile().apply { deleteOnExit() }
        val staged = root.resolve("staged.hbundle").apply { writeText("validated package") }
        val corpus = AgentCorpusManifest(
            id = "corpus-1",
            title = "测试资料",
            sourceHash = "corpus-hash",
            sourcesPath = "corpora/corpus-1/sources.json",
            chunksPath = "corpora/corpus-1/chunks.jsonl",
            required = true,
        )
        val parsed = ParsedAgentBundle(
            file = staged,
            packageSha256 = "bundle-sha",
            publisherPublicKey = byteArrayOf(1, 2, 3),
            publisherFingerprint = "publisher-fingerprint",
            manifestJson = "{}",
            agent = AgentPackageManifest(
                id = "agent-1",
                name = "资料研究代理",
                version = 1,
                summary = "基于资料模拟",
                personaPath = "agent/persona.md",
                worldviewPath = "agent/worldview.jsonl",
                conceptsPath = "agent/concepts.json",
                examplesPath = "agent/examples.jsonl",
                evalPath = "agent/eval.jsonl",
                requiredCorpora = listOf("corpus-1"),
            ),
            corpora = listOf(corpus),
            persona = "我只根据资料回答。",
            worldviewJsonl = "",
            compressedSizeBytes = staged.length(),
            uncompressedSizeBytes = 100L,
        )
        val reader = FakeAgentBundleAccess(
            parsedBundle = parsed,
            chunks = listOf(
                AgentCorpusChunk(
                    id = "chunk-1",
                    sourceTitle = "测试资料",
                    sourceHash = "source-hash",
                    location = "第一章",
                    text = "研究问题必须从事实出发",
                    keywords = listOf("研究", "事实"),
                    ngrams = listOf("研究", "事实"),
                ),
            ),
        )
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            reader = reader,
            timeProvider = TimeProvider { 20L },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val session = repository.prepareImport("staged.hbundle") { staged.inputStream() }

        val result = repository.install(session)

        assertEquals(AgentInstallOutcome.INSTALLED, result.outcome)
        assertEquals(AgentStatus.READY, result.agent.status)
        assertEquals(1, dao.versionCorpora.size)
        assertEquals(1, dao.chunks.size)
        assertEquals(1, dao.searchRows.size)
        assertTrue(dao.version!!.bundlePath.endsWith("agents/agent-1/1/bundle.hbundle"))
        assertTrue(File(dao.version!!.bundlePath).isFile)
        assertFalse(session.stagedFile.exists())
    }

    @Test
    fun v1CompatibilityInstallRemainsIdempotent() = runTest {
        val fixture = twoCorpusInstallFixture(conflictingSecondChunk = false)
        val first = fixture.repository.install(fixture.session)
        val secondSession = fixture.repository.prepareImport("second.hbundle") {
            "validated package".byteInputStream()
        }

        val second = fixture.repository.install(secondSession)

        assertEquals(AgentInstallOutcome.INSTALLED, first.outcome)
        assertEquals(AgentInstallOutcome.ALREADY_INSTALLED, second.outcome)
        assertFalse(secondSession.stagedFile.exists())
        assertEquals(1, fixture.dao.versionCorpora.count { it.corpusId == "corpus-core" })
    }

    @Test
    fun reusesIdenticalPhysicalChunkAcrossCorpora() = runTest {
        val fixture = twoCorpusInstallFixture(conflictingSecondChunk = false)

        fixture.repository.install(fixture.session)

        assertEquals(1, fixture.dao.chunks.size)
        assertEquals(1, fixture.dao.searchRows.size)
        assertEquals(2, fixture.dao.corpusChunkRefs.size)
    }

    @Test
    fun rejectsConflictingPhysicalChunkBeforeAddingSecondCorpusReference() = runTest {
        val fixture = twoCorpusInstallFixture(conflictingSecondChunk = true)

        try {
            fixture.repository.install(fixture.session)
            throw AssertionError("Expected immutable physical evidence conflict")
        } catch (error: AgentBundleException) {
            assertTrue(error.message.orEmpty().contains("immutable evidence"))
        }

        assertEquals(1, fixture.dao.chunks.size)
        assertEquals(1, fixture.dao.corpusChunkRefs.size)
        assertEquals(1, fixture.dao.searchRows.size)
    }

    @Test
    fun buildsNaturalFirstPersonContextFromCurrentVersionEvidence() = runTest {
        val dao = FakeAgentDao().apply {
            version = AgentVersionEntity(
                agentId = "agent-1",
                version = 1,
                schemaVersion = 1,
                bundlePath = "/tmp/agent.hbundle",
                bundleSha256 = "sha",
                manifestJson = "{}",
                persona = "我重视从事实出发。",
                worldviewJsonl = """{"id":"view-investigation","statement":"调查先于结论","evidence":["chunk-secret-42"],"confidence":1.0}""",
                installedAt = 1L,
                state = "READY",
            )
            versionCorpora = listOf(
                AgentVersionCorpusCrossRef("agent-1", 1, "corpus-current", "hash-current", true),
            )
            chunks["hash-current:chunk-investigation"] = AgentChunkEntity(
                chunkKey = "hash-current:chunk-investigation",
                sourceId = "source-current",
                sourceHash = "hash-current",
                chunkId = "chunk-investigation",
                sourceTitle = "调查研究",
                location = "第一章 · 1",
                text = "没有调查，没有发言权。研究问题必须从事实出发。",
                keywordsText = "调查 事实 研究",
            )
            searchResult = listOf("hash-current:chunk-investigation")
        }
        val repository = repository(dao)

        val context = repository.runtimeContext("agent-1", 1, "为什么要先调查事实", 8)!!

        assertEquals(listOf("corpus-current:hash-current"), dao.lastCorpusKeys)
        assertEquals("chunk-investigation", context.evidence.single().chunkId)
        assertTrue(context.systemPrompt.contains("第一人称"))
        assertTrue(context.systemPrompt.contains("基于资料模拟"))
        assertTrue(context.systemPrompt.contains("历史事实、人物经历和核心立场必须由人物资料支持"))
        assertTrue(context.systemPrompt.contains("没有调查，没有发言权。研究问题必须从事实出发。"))
        assertTrue(context.systemPrompt.contains("调查先于结论"))
        assertFalse(context.systemPrompt.contains("[资料 1]"))
        assertFalse(context.systemPrompt.contains("调查研究 · 第一章 · 1"))
        assertFalse(context.systemPrompt.contains("chunk-secret-42"))
        assertFalse(context.systemPrompt.contains("view-investigation"))
    }

    @Test
    fun returnsNaturalConversationContractWhenFtsHasNoEvidence() = runTest {
        val dao = FakeAgentDao().apply {
            version = AgentVersionEntity(
                agentId = "agent-1",
                version = 1,
                schemaVersion = 1,
                bundlePath = "/tmp/agent.hbundle",
                bundleSha256 = "sha",
                manifestJson = "{}",
                persona = "我只依据资料。",
                worldviewJsonl = "",
                installedAt = 1L,
                state = "READY",
            )
            versionCorpora = listOf(AgentVersionCorpusCrossRef("agent-1", 1, "corpus-1", "hash", true))
        }

        val context = repository(dao).runtimeContext("agent-1", 1, "资料中没有的问题", 8)!!

        assertTrue(context.evidence.isEmpty())
        assertTrue(context.systemPrompt.contains("问候、承接前文和关系互动不要求原文证据"))
        assertFalse(context.systemPrompt.contains("当前资料不足"))
    }

    @Test
    fun chunkFtsRetrievalScoresEveryKeysetPageAndIsIndependentOfInsertionOrder() = runTest {
        fun fixture(order: List<String>): Pair<AgentRepository, FakeAgentDao> {
            val dao = FakeAgentDao().apply {
                versionCorpora = listOf(AgentVersionCorpusCrossRef("agent-1", 1, "corpus", "hash", true))
                val keys = (0 until 65).map { index -> "key-${index.toString().padStart(3, '0')}" } + "key-zzz"
                keys.forEach { key ->
                    chunks[key] = AgentChunkEntity(
                        chunkKey = key,
                        sourceId = "source-$key",
                        sourceHash = "hash-$key",
                        chunkId = key,
                        sourceTitle = key,
                        location = "location",
                        text = if (key == "key-zzz") "调查事实" else "调查",
                        keywordsText = if (key == "key-zzz") "调查 查事 事实" else "",
                    )
                }
                searchResult = order.ifEmpty { keys }
            }
            return repository(dao) to dao
        }
        val keys = (0 until 65).map { index -> "key-${index.toString().padStart(3, '0')}" } + "key-zzz"
        val (firstRepository, firstDao) = fixture(keys)
        val (secondRepository, secondDao) = fixture(keys.reversed())

        val first = firstRepository.searchChunks("agent-1", 1, "调查事实", 2, emptyList())
        val second = secondRepository.searchChunks("agent-1", 1, "调查事实", 2, emptyList())

        assertEquals(first.map(AgentRetrievalChunk::chunkKey), second.map(AgentRetrievalChunk::chunkKey))
        assertEquals("key-zzz", first.first().chunkKey)
        assertEquals(listOf(64, 64), firstDao.chunkSearchLimits)
        assertEquals(listOf(64, 64), secondDao.chunkSearchLimits)
        assertTrue(firstDao.chunkLoadBatchSizes.all { it <= 64 })
    }

    @Test
    fun hierarchyFtsRetrievalScoresEveryKeysetPageBeforeStableTruncation() = runTest {
        val dao = FakeAgentDao().apply {
            versionCorpora = listOf(AgentVersionCorpusCrossRef("agent-1", 1, "corpus", "hash", true))
            val keys = (0 until 65).map { index -> "node-${index.toString().padStart(3, '0')}" } + "node-zzz"
            keys.reversed().forEach { key ->
                hierarchyNodes[key] = AgentHierarchyNodeEntity(
                    nodeKey = key,
                    sourceId = "source-$key",
                    sourceHash = "hash-$key",
                    nodeId = key,
                    kind = "section",
                    title = if (key == "node-zzz") "调查事实" else "调查",
                    parentNodeKey = null,
                    path = key,
                    summary = "",
                )
                hierarchySearchRows += AgentHierarchyFtsEntity(key, "调查事实")
            }
        }

        val routes = repository(dao).searchHierarchy("agent-1", 1, "调查事实", 2)

        assertEquals("node-zzz", routes.first().nodeKey)
        assertEquals(listOf(64, 64), dao.hierarchySearchLimits)
        assertTrue(dao.hierarchyLoadBatchSizes.all { it <= 64 })
    }

    @Test
    fun pagedFtsRetrievalChecksCancellationBetweenBoundedPages() = runTest {
        val dao = FakeAgentDao().apply {
            versionCorpora = listOf(AgentVersionCorpusCrossRef("agent-1", 1, "corpus", "hash", true))
            searchResult = (0 until 130).map { index -> "key-${index.toString().padStart(3, '0')}" }
            searchResult.forEach { key ->
                chunks[key] = AgentChunkEntity(
                    key, key, "hash-$key", key, key, location = "location", text = "调查", keywordsText = "调查",
                )
            }
            cancelAfterChunkLoadCalls = 1
        }

        val retrieval = async {
            repository(dao).searchChunks("agent-1", 1, "调查", 2, emptyList())
        }
        val failure = runCatching { retrieval.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, dao.chunkSearchLimits.size)
        assertEquals(listOf(64), dao.chunkLoadBatchSizes)
    }

    @Test
    fun chineseQueryUsesQuotedTermsWithoutFtsOperatorsFromUserInput() {
        val query = buildAgentFtsQuery("调查 OR 事实 \"任意引号\"")

        assertTrue(query.contains("\"调查\""))
        assertTrue(query.contains("\"事实\""))
        assertTrue(query.contains(" OR "))
        assertTrue(!query.contains("\"任意引号\""))
    }

    private fun repository(dao: AgentDao): AgentRepository {
        val root = Files.createTempDirectory("agent-repository-test").toFile().apply { deleteOnExit() }
        return AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            timeProvider = TimeProvider { 10L },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private suspend fun twoCorpusInstallFixture(conflictingSecondChunk: Boolean): TwoCorpusInstallFixture {
        val root = Files.createTempDirectory("agent-physical-reuse-test").toFile().apply { deleteOnExit() }
        val staged = root.resolve("staged.hbundle").apply { writeText("validated package") }
        val corpora = listOf("corpus-core", "corpus-full").map { id ->
            AgentCorpusManifest(
                id = id,
                title = id,
                sourceHash = "corpus-$id",
                sourcesPath = "$id/sources.json",
                chunksPath = "$id/chunks.jsonl",
                required = id == "corpus-core",
            )
        }
        fun chunk(text: String) = AgentCorpusChunk(
            id = "chunk-1",
            sourceTitle = "同一来源",
            sourceHash = "a".repeat(64),
            location = "第一章",
            text = text,
            keywords = listOf("调查"),
            ngrams = listOf("调查"),
        )
        val parsed = ParsedAgentBundle(
            file = staged,
            packageSha256 = "bundle-sha",
            publisherPublicKey = byteArrayOf(1),
            publisherFingerprint = "publisher-fingerprint",
            manifestJson = "{}",
            agent = AgentPackageManifest(
                id = "agent-physical",
                name = "物理去重代理",
                version = 1,
                summary = "",
                personaPath = "agent/persona.md",
                worldviewPath = "agent/worldview.jsonl",
                conceptsPath = "agent/concepts.json",
                examplesPath = "agent/examples.jsonl",
                evalPath = "agent/eval.jsonl",
                requiredCorpora = listOf("corpus-core"),
            ),
            corpora = corpora,
            persona = "只依据证据",
            worldviewJsonl = "",
            compressedSizeBytes = staged.length(),
            uncompressedSizeBytes = staged.length(),
        )
        val reader = FakeAgentBundleAccess(
            parsedBundle = parsed,
            chunksByCorpus = mapOf(
                "corpus-core" to listOf(chunk("相同证据")),
                "corpus-full" to listOf(chunk(if (conflictingSecondChunk) "冲突证据" else "相同证据")),
            ),
        )
        val dao = FakeAgentDao()
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            reader = reader,
            timeProvider = TimeProvider { 20L },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val session = repository.prepareImport("staged.hbundle") { staged.inputStream() }
        return TwoCorpusInstallFixture(repository, session, dao)
    }

    private suspend fun optionalRemovalFixture(
        required: Boolean,
        lifecycleCoordinator: AgentLifecycleCoordinator = AgentLifecycleCoordinator(),
        fileOps: AgentFileOps = DefaultAgentFileOps(),
    ): V2InstallFixture {
        val root = Files.createTempDirectory("agent-removal-test").toFile().apply { deleteOnExit() }
        val dao = FakeAgentDao()
        dao.upsertAgent(
            AgentEntity(
                "agent-remove", "移除测试", "", 1, byteArrayOf(1), "publisher", "LOCAL_FILE",
                AgentStatus.READY.name, 0, 0, 1L, 1L,
            ),
        )
        val installedAgentPackage = root.resolve("files/agents/agent-remove/1/agent.hagent").apply {
            parentFile?.mkdirs()
            writeText("agent")
        }
        dao.version = AgentVersionEntity(
            "agent-remove", 1, 2, installedAgentPackage.absolutePath, "sha", "{}", "人格", "",
            1L, AgentStatus.READY.name,
        )
        dao.corpora["corpus-extra:corpus-hash"] = AgentCorpusEntity(
            "corpus-extra", "corpus-hash", "扩展资料", 1L, 10L,
        )
        dao.versionCorpora = listOf(
            AgentVersionCorpusCrossRef(
                "agent-remove", 1, "corpus-extra", "corpus-hash", required,
                if (required) "required" else "optional", "package-sha", 10L, 1L,
            ),
        )
        dao.chunks["source-extra:chunk-extra"] = AgentChunkEntity(
            "source-extra:chunk-extra", "source-extra", "source-extra", "chunk-extra", "扩展资料",
            location = "第一章", text = "扩展证据", keywordsText = "证据",
        )
        dao.corpusChunkRefs += AgentCorpusChunkCrossRef(
            "corpus-extra", "corpus-hash", "source-extra:chunk-extra",
        )
        dao.searchRows += AgentChunkFtsEntity("source-extra:chunk-extra", "证据")
        return V2InstallFixture(
            AgentRepository(
                filesDir = root.resolve("files"),
                cacheDir = root.resolve("cache"),
                dao = dao,
                lifecycleCoordinator = lifecycleCoordinator,
                fileOps = fileOps,
                timeProvider = TimeProvider { 20L },
                ioDispatcher = Dispatchers.Unconfined,
            ),
            dao,
            root,
        )
    }
}

private data class TwoCorpusInstallFixture(
    val repository: AgentRepository,
    val session: AgentImportSession,
    val dao: FakeAgentDao,
)

internal class FakeAgentDao : AgentDao {
    private val agents = MutableStateFlow<List<AgentEntity>>(emptyList())
    var version: AgentVersionEntity? = null
    var versionCorpora: List<AgentVersionCorpusCrossRef> = emptyList()
    val chunks = linkedMapOf<String, AgentChunkEntity>()
    val corpora = linkedMapOf<String, AgentCorpusEntity>()
    val corpusChunkRefs = mutableListOf<AgentCorpusChunkCrossRef>()
    val searchRows = mutableListOf<AgentChunkFtsEntity>()
    val versionPackages = linkedMapOf<String, AgentVersionPackageEntity>()
    val sources = linkedMapOf<String, AgentSourceFileEntity>()
    val versionSources = mutableListOf<AgentVersionSourceCrossRef>()
    val corpusSourceRefs = mutableListOf<AgentCorpusSourceCrossRef>()
    val corpusHierarchyRefs = mutableListOf<AgentCorpusHierarchyCrossRef>()
    val hierarchyNodes = linkedMapOf<String, AgentHierarchyNodeEntity>()
    val hierarchySearchRows = mutableListOf<AgentHierarchyFtsEntity>()
    var searchResult: List<String> = emptyList()
    var routedSearchResult: List<String> = emptyList()
    var lastCorpusKeys: List<String> = emptyList()
    val chunkSearchLimits = mutableListOf<Int>()
    val hierarchySearchLimits = mutableListOf<Int>()
    val chunkLoadBatchSizes = mutableListOf<Int>()
    val hierarchyLoadBatchSizes = mutableListOf<Int>()
    var cancelAfterChunkSearchCalls: Int? = null
    var cancelAfterChunkLoadCalls: Int? = null
    var maxChunkBatchSize: Int = 0
    val referencedChunkKeys = mutableSetOf<String>()
    val legacyAgentSourceVersions = mutableSetOf<String>()
    val persistableChunkKeys = mutableSetOf<String>()
    var onInstalledVersionChunkKeysRead: (suspend () -> Unit)? = null
    var failChunkInsertCall: Int? = null
    var failEvidenceCheck: Boolean = false
    var failReadyTransition: Boolean = false
    private var chunkInsertCalls: Int = 0

    override fun observeAgents(): Flow<List<AgentEntity>> = agents
    override suspend fun findAgent(id: String): AgentEntity? = agents.value.firstOrNull { it.id == id }
    override suspend fun listReadyAgents(): List<AgentEntity> =
        agents.value.filter { it.status == AgentStatus.READY.name }
    override suspend fun findVersion(agentId: String, version: Int): AgentVersionEntity? =
        this.version?.takeIf { it.agentId == agentId && it.version == version }
    override suspend fun listVersions(agentId: String) = listOfNotNull(version).filter { it.agentId == agentId }
    override suspend fun findVersionPackage(agentId: String, version: Int, packageId: String) =
        versionPackages["$agentId:$version:$packageId"]
    override suspend fun listVersionPackages(agentId: String, version: Int) = versionPackages.values
        .filter { it.agentId == agentId && it.version == version }

    override suspend fun findCorpus(corpusId: String, sourceHash: String): AgentCorpusEntity? =
        corpora["$corpusId:$sourceHash"]

    override suspend fun findCorpusById(corpusId: String): AgentCorpusEntity? =
        corpora.values.firstOrNull { it.corpusId == corpusId }
    override suspend fun listVersionCorpora(agentId: String, version: Int): List<AgentVersionCorpusCrossRef> =
        versionCorpora.filter { it.agentId == agentId && it.version == version }
    override suspend fun findVersionCorpus(agentId: String, version: Int, corpusId: String) =
        versionCorpora.firstOrNull { it.agentId == agentId && it.version == version && it.corpusId == corpusId }
    override suspend fun countVersionCorpusReferences(corpusId: String, sourceHash: String) =
        versionCorpora.count { it.corpusId == corpusId && it.sourceHash == sourceHash }
    override suspend fun listCorpusChunkKeys(corpusId: String, corpusHash: String) = corpusChunkRefs
        .filter { it.corpusId == corpusId && it.corpusHash == corpusHash }
        .map(AgentCorpusChunkCrossRef::chunkKey)
    override suspend fun countAgentSourcePartsReferencingChunkKey(chunkKey: String) =
        if (chunkKey in referencedChunkKeys) 1 else 0
    override suspend fun countLegacyAgentSourceParts(agentId: String, version: Int) =
        if ("$agentId:$version" in legacyAgentSourceVersions) 1 else 0
    override suspend fun listInstalledVersionChunkKeys(
        agentId: String,
        version: Int,
        chunkKeys: List<String>,
    ): List<String> {
        onInstalledVersionChunkKeysRead?.invoke()
        return chunkKeys.filter { it in persistableChunkKeys || it in chunks }
    }
    override suspend fun findSource(sourceId: String, sourceHash: String): AgentSourceFileEntity? =
        sources["$sourceId:$sourceHash"]
    override suspend fun listVersionSources(agentId: String, version: Int): List<AgentSourceFileEntity> =
        versionSources.filter { it.agentId == agentId && it.version == version }
            .mapNotNull { sources["${it.sourceId}:${it.sourceHash}"] }

    override suspend fun upsertAgent(entity: AgentEntity) {
        agents.value = agents.value.filterNot { it.id == entity.id } + entity
    }

    override suspend fun insertVersion(entity: AgentVersionEntity) {
        version = entity
    }
    override suspend fun upsertVersionPackages(entities: List<AgentVersionPackageEntity>) {
        entities.forEach { versionPackages["${it.agentId}:${it.version}:${it.packageId}"] = it }
    }
    override suspend fun markVersionPackageInstalled(
        agentId: String,
        version: Int,
        packageId: String,
        filePath: String,
        installedAt: Long,
    ): Int {
        val key = "$agentId:$version:$packageId"
        val row = versionPackages[key] ?: return 0
        versionPackages[key] = row.copy(installed = true, filePath = filePath, installedAt = installedAt)
        return 1
    }
    override suspend fun markVersionPackageRemoved(agentId: String, version: Int, packageId: String): Int {
        val key = "$agentId:$version:$packageId"
        val row = versionPackages[key] ?: return 0
        versionPackages[key] = row.copy(installed = false, filePath = "", installedAt = null)
        return 1
    }
    override suspend fun countInstalledPackagePathReferences(filePath: String) =
        versionPackages.values.count { it.installed && it.filePath == filePath }
    override suspend fun countVersionBundlePathReferences(filePath: String) =
        if (version?.bundlePath == filePath) 1 else 0
    override suspend fun countSourceFilePathReferences(filePath: String) =
        sources.values.count { it.filePath == filePath }
    override suspend fun countSourcePayloadReferences(sourceHash: String, filePath: String) =
        sources.values.count { it.sourceHash == sourceHash && it.filePath == filePath } +
            versionSources.count { it.sourceHash == sourceHash } +
            corpusSourceRefs.count { it.sourceHash == sourceHash }
    override suspend fun listInstalledPackagePaths() = versionPackages.values
        .filter(AgentVersionPackageEntity::installed)
        .map(AgentVersionPackageEntity::filePath)
        .filter(String::isNotBlank)
    override suspend fun countSourceReferences(sourceId: String, sourceHash: String) =
        versionSources.count { it.sourceId == sourceId && it.sourceHash == sourceHash } +
            corpusSourceRefs.count { it.sourceId == sourceId && it.sourceHash == sourceHash }
    override suspend fun listCorpusSources(corpusId: String, corpusHash: String) =
        corpusSourceRefs.filter { it.corpusId == corpusId && it.corpusHash == corpusHash }
            .mapNotNull { sources["${it.sourceId}:${it.sourceHash}"] }
    override suspend fun listSources() = sources.values.sortedWith(
        compareBy(AgentSourceFileEntity::sourceHash, AgentSourceFileEntity::sourceId),
    )
    override suspend fun updateSourcePathsByHashAndOldPaths(
        sourceHash: String,
        oldPaths: List<String>,
        filePath: String,
    ): Int {
        val matching = sources.filterValues { it.sourceHash == sourceHash && it.filePath in oldPaths }
        matching.forEach { (key, source) -> sources[key] = source.copy(filePath = filePath) }
        return matching.size
    }
    override suspend fun updateVersionState(agentId: String, version: Int, state: String, expandedAt: Long?): Int {
        if (failReadyTransition) throw IllegalStateException("ready transition failed")
        val row = this.version?.takeIf { it.agentId == agentId && it.version == version } ?: return 0
        this.version = row.copy(state = state, lastEvidenceExpandedAt = expandedAt)
        return 1
    }
    override suspend fun updateAgentInstallState(
        agentId: String,
        status: String,
        requiredCount: Int,
        installedCount: Int,
        updatedAt: Long,
    ): Int {
        val row = agents.value.firstOrNull { it.id == agentId } ?: return 0
        upsertAgent(
            row.copy(
                status = status,
                requiredCorpusCount = requiredCount,
                installedCorpusCount = installedCount,
                updatedAt = updatedAt,
            ),
        )
        return 1
    }
    override suspend fun updateAgentStatus(agentId: String, status: String, updatedAt: Long): Int {
        val row = agents.value.firstOrNull { it.id == agentId } ?: return 0
        upsertAgent(row.copy(status = status, updatedAt = updatedAt))
        return 1
    }
    override suspend fun deleteVersionCorpus(agentId: String, version: Int, corpusId: String): Int {
        val before = versionCorpora.size
        versionCorpora = versionCorpora.filterNot {
            it.agentId == agentId && it.version == version && it.corpusId == corpusId
        }
        return before - versionCorpora.size
    }
    override suspend fun deleteCorpus(corpusId: String, sourceHash: String): Int {
        val removed = corpora.remove("$corpusId:$sourceHash") ?: return 0
        corpusChunkRefs.removeIf { it.corpusId == corpusId && it.corpusHash == sourceHash }
        corpusSourceRefs.removeIf { it.corpusId == corpusId && it.corpusHash == sourceHash }
        corpusHierarchyRefs.removeIf { it.corpusId == corpusId && it.corpusHash == sourceHash }
        return if (removed.corpusId == corpusId) 1 else 0
    }

    override suspend fun insertCorpus(entity: AgentCorpusEntity): Long {
        corpora.putIfAbsent("${entity.corpusId}:${entity.sourceHash}", entity)
        return 1L
    }

    override suspend fun updateCorpusSize(corpusId: String, sourceHash: String, sizeBytes: Long) {
        val key = "$corpusId:$sourceHash"
        corpora[key] = corpora.getValue(key).copy(sizeBytes = sizeBytes)
    }

    override suspend fun insertVersionCorpus(entity: AgentVersionCorpusCrossRef): Long {
        versionCorpora = versionCorpora + entity
        return 1L
    }
    override suspend fun insertChunks(entities: List<AgentChunkEntity>): List<Long> {
        chunkInsertCalls += 1
        if (chunkInsertCalls == failChunkInsertCall) throw IllegalStateException("batch insert failed")
        maxChunkBatchSize = maxOf(maxChunkBatchSize, entities.size)
        return entities.map { entity ->
            if (chunks.containsKey(entity.chunkKey)) {
                -1L
            } else {
                chunks[entity.chunkKey] = entity
                1L
            }
        }
    }
    override suspend fun insertCorpusChunkRefs(entities: List<AgentCorpusChunkCrossRef>): List<Long> {
        corpusChunkRefs += entities
        return entities.map { 1L }
    }
    override suspend fun insertCorpusSourceRefs(entities: List<AgentCorpusSourceCrossRef>): List<Long> {
        corpusSourceRefs += entities.filterNot(corpusSourceRefs::contains)
        return entities.map { 1L }
    }

    override suspend fun insertChunkSearchRows(entities: List<AgentChunkFtsEntity>): List<Long> {
        searchRows += entities
        return entities.map { 1L }
    }

    override suspend fun searchChunkKeys(
        corpusKeys: List<String>,
        ftsQuery: String,
        afterChunkKey: String,
        limit: Int,
    ): List<String> {
        lastCorpusKeys = corpusKeys
        chunkSearchLimits += limit
        if (chunkSearchLimits.size == cancelAfterChunkSearchCalls) {
            currentCoroutineContext().cancel(CancellationException("cancel paged search"))
        }
        return searchResult.distinct().sorted().filter { it > afterChunkKey }.take(limit)
    }

    override suspend fun listChunks(chunkKeys: List<String>): List<AgentChunkEntity> {
        chunkLoadBatchSizes += chunkKeys.size
        if (chunkLoadBatchSizes.size == cancelAfterChunkLoadCalls) {
            currentCoroutineContext().cancel(CancellationException("cancel paged load"))
        }
        return chunkKeys.mapNotNull(chunks::get)
    }
    override suspend fun countRequiredEvidenceChunk(agentId: String, version: Int, chunkId: String): Int {
        if (failEvidenceCheck) throw IllegalStateException("evidence check failed")
        val requiredCorpora = versionCorpora.filter {
            it.agentId == agentId && it.version == version && it.required
        }.map { "${it.corpusId}:${it.sourceHash}" }.toSet()
        val keys = corpusChunkRefs.filter { "${it.corpusId}:${it.corpusHash}" in requiredCorpora }
            .map(AgentCorpusChunkCrossRef::chunkKey)
            .toSet()
        return chunks.values.count { it.chunkKey in keys && it.chunkId == chunkId }
    }
    override suspend fun insertHierarchyNodes(entities: List<AgentHierarchyNodeEntity>): List<Long> = entities.map {
        if (hierarchyNodes.putIfAbsent(it.nodeKey, it) == null) 1L else -1L
    }
    override suspend fun insertHierarchySearchRows(entities: List<AgentHierarchyFtsEntity>): List<Long> {
        hierarchySearchRows += entities
        return entities.map { 1L }
    }
    override suspend fun insertCorpusHierarchyRefs(entities: List<AgentCorpusHierarchyCrossRef>): List<Long> {
        corpusHierarchyRefs += entities.filterNot(corpusHierarchyRefs::contains)
        return entities.map { 1L }
    }
    override suspend fun insertSource(entity: AgentSourceFileEntity): Long {
        val key = "${entity.sourceId}:${entity.sourceHash}"
        if (key in sources) return -1L
        sources[key] = entity
        return 1L
    }
    override suspend fun upsertSource(entity: AgentSourceFileEntity) {
        sources["${entity.sourceId}:${entity.sourceHash}"] = entity
    }
    override suspend fun insertVersionSource(entity: AgentVersionSourceCrossRef): Long {
        if (entity !in versionSources) versionSources += entity
        return 1L
    }
    override suspend fun deleteVersion(agentId: String, version: Int): Int {
        val row = this.version?.takeIf { it.agentId == agentId && it.version == version } ?: return 0
        this.version = null
        versionCorpora = versionCorpora.filterNot { it.agentId == agentId && it.version == version }
        versionPackages.entries.removeIf { it.value.agentId == agentId && it.value.version == version }
        versionSources.removeIf { it.agentId == agentId && it.version == version }
        return if (row.agentId == agentId) 1 else 0
    }
    override suspend fun deleteAgent(agentId: String): Int {
        val before = agents.value.size
        agents.value = agents.value.filterNot { it.id == agentId }
        deleteVersion(agentId, version?.version ?: -1)
        return before - agents.value.size
    }
    override suspend fun searchHierarchyNodeKeys(ftsQuery: String, limit: Int): List<String> = emptyList()
    override suspend fun searchHierarchyNodeKeysForCorpora(
        corpusKeys: List<String>,
        ftsQuery: String,
        afterNodeKey: String,
        limit: Int,
    ): List<String> {
        hierarchySearchLimits += limit
        return hierarchySearchRows.map(AgentHierarchyFtsEntity::nodeKey).distinct().sorted()
            .filter { it > afterNodeKey }
            .take(limit)
    }
    override suspend fun listChunkKeysForHierarchyNodes(
        corpusKeys: List<String>,
        nodeKeys: List<String>,
        limit: Int,
    ): List<String> {
        if (routedSearchResult.isNotEmpty()) return routedSearchResult.take(limit)
        return nodeKeys.mapNotNull(hierarchyNodes::get).mapNotNull { node ->
            chunks.values.firstOrNull { chunk -> node.nodeId in chunk.parentPath.split('/') }?.chunkKey
        }.distinct().take(limit)
    }
    override suspend fun listHierarchyNodes(nodeKeys: List<String>): List<AgentHierarchyNodeEntity> {
        hierarchyLoadBatchSizes += nodeKeys.size
        return nodeKeys.mapNotNull(hierarchyNodes::get)
    }
    override suspend fun deleteOrphanChunkSearchRows(): Int {
        val before = searchRows.size
        searchRows.removeIf { it.chunkKey !in chunks }
        return before - searchRows.size
    }
    override suspend fun deleteOrphanChunks(): Int {
        val referenced = corpusChunkRefs.map(AgentCorpusChunkCrossRef::chunkKey).toSet()
        val before = chunks.size
        chunks.keys.removeIf { it !in referenced }
        return before - chunks.size
    }
    override suspend fun deleteOrphanHierarchySearchRows(): Int {
        val before = hierarchySearchRows.size
        hierarchySearchRows.removeIf { it.nodeKey !in hierarchyNodes }
        return before - hierarchySearchRows.size
    }
    override suspend fun deleteOrphanHierarchyNodes(): Int {
        val referenced = corpusHierarchyRefs.map(AgentCorpusHierarchyCrossRef::nodeKey).toSet()
        val before = hierarchyNodes.size
        hierarchyNodes.keys.removeIf { it !in referenced }
        return before - hierarchyNodes.size
    }
    override suspend fun deleteOrphanSources(): Int {
        val before = sources.size
        sources.entries.removeIf { (_, source) ->
            versionSources.none { it.sourceId == source.sourceId && it.sourceHash == source.sourceHash } &&
                corpusSourceRefs.none { it.sourceId == source.sourceId && it.sourceHash == source.sourceHash }
        }
        return before - sources.size
    }
    override suspend fun deleteOrphanCorpora(): Int = 0

    fun clearInstalledState() {
        agents.value = emptyList()
        version = null
        versionCorpora = emptyList()
        chunks.clear()
        corpora.clear()
        corpusChunkRefs.clear()
        searchRows.clear()
        versionPackages.clear()
        sources.clear()
        versionSources.clear()
        corpusSourceRefs.clear()
        corpusHierarchyRefs.clear()
        hierarchyNodes.clear()
        hierarchySearchRows.clear()
    }
}

private enum class V2InstallFailure {
    NONE,
    BATCH_INSERT,
    EVIDENCE_CHECK,
    READY_TRANSITION,
    CANCEL_DURING_CHUNKS,
}

private data class V2InstallFixture(
    val repository: AgentRepository,
    val dao: FakeAgentDao,
    val repositoryRoot: File,
)

private fun v2InstallFixture(
    chunkCount: Int = 1,
    requiredEvidenceId: String = "chunk-0",
    evaluationCorpusId: String = "corpus-core",
    standaloneCorpusTransform: (V2Corpus) -> V2Corpus = { it },
    existingCorpusTransform: (V2Corpus) -> V2Corpus = { it },
    failure: V2InstallFailure = V2InstallFailure.NONE,
    fileOps: AgentFileOps = DefaultAgentFileOps(),
    lifecycleCoordinator: AgentLifecycleCoordinator = AgentLifecycleCoordinator(),
    conversationDao: ConversationDao? = null,
    privateInstallAvailableBytes: () -> Long = { Long.MAX_VALUE },
): V2InstallFixture {
    val root = Files.createTempDirectory("agent-v2-install-test").toFile().apply { deleteOnExit() }
    val packageFile = root.resolve("template.zip").apply { writeText("template") }
    val hash = V2_SOURCE_HASH
    val installPackage = V2InstallPackage(
        id = "corpus-core",
        type = V2PackageType.CORPUS,
        fileName = "core.hcorpus",
        installClass = V2InstallClass.REQUIRED,
        dependencies = emptyList(),
        sizeBytes = 6L,
        sha256 = V2_CORPUS_PACKAGE_HASH,
    )
    val sourceInstallPackage = V2InstallPackage(
        id = "source-package",
        type = V2PackageType.SOURCE,
        fileName = "source.hsource",
        installClass = V2InstallClass.SOURCE,
        dependencies = emptyList(),
        sizeBytes = 6L,
        sha256 = "e".repeat(64),
    )
    val plan = V2InstallPlan(
        packages = listOf(installPackage, sourceInstallPackage),
        profiles = listOf(
            V2InstallProfile("lite", listOf("corpus-core"), false),
            V2InstallProfile("balanced", listOf("corpus-core", "source-package"), true),
            V2InstallProfile("complete", listOf("corpus-core", "source-package"), false),
            V2InstallProfile("source", listOf("corpus-core", "source-package"), false),
        ),
        recommendedProfileId = "balanced",
        requiredCorpusIds = listOf("corpus-core"),
    )
    val agent = V2Agent(
        file = packageFile,
        packageSha256 = "c".repeat(64),
        publisherPublicKey = byteArrayOf(1),
        publisherFingerprint = "publisher-v2",
        manifestJson = "{}",
        compressedSizeBytes = 5L,
        uncompressedSizeBytes = 5L,
        manifest = V2AgentManifest("agent-v2", "V2 人格", 2, listOf("corpus-core"), false),
        persona = "基于资料模拟。",
        identity = V2Identity(emptyList(), "", emptyList(), emptyList()),
        voice = V2Voice("", emptyList(), emptyList(), emptyList(), emptyList(), listOf(requiredEvidenceId)),
        worldview = emptyList(),
        episodes = emptyList(),
        concepts = emptyList(),
        examples = emptyList(),
        openers = V2Openers("", emptyList()),
        evaluations = listOf(
            V2Evaluation("eval-1", "grounding", "依据？", "", listOf(requiredEvidenceId), evaluationCorpusId),
        ),
        installPlanJson = "{}",
        installPlan = plan,
        isRunnable = false,
    )
    val source = V2SourceRecord(
        sourceId = "source-1",
        title = "测试来源",
        fileName = "source.txt",
        storedName = "source.txt",
        sourceHash = hash,
        format = "txt",
        genre = V2SourceGenre.ESSAY,
        authorship = V2Authorship.DIRECT,
        period = "test",
        rawSizeBytes = "raw-source".encodeToByteArray().size.toLong(),
        extractedChars = 0L,
    )
    val corpus = V2Corpus(
        file = packageFile,
        packageSha256 = installPackage.sha256,
        publisherPublicKey = byteArrayOf(1),
        publisherFingerprint = "publisher-v2",
        manifestJson = "{}",
        compressedSizeBytes = installPackage.sizeBytes,
        uncompressedSizeBytes = installPackage.sizeBytes,
        manifest = V2CorpusManifest(
            "corpus-core", "agent-v2", 2, V2InstallClass.REQUIRED, chunkCount,
            listOf(source.sourceId), listOf(source.sourceHash), listOf("test"),
            listOf(V2SourceGenre.ESSAY), listOf(V2Authorship.DIRECT), listOf("root"), listOf("identity:self"),
        ),
        sources = listOf(source),
        nodeCount = 1,
        chunkCount = chunkCount,
        duplicateCount = 0,
        validationDiagnostics = V2CorpusValidationDiagnostics("disk", chunkCount.toLong(), 1, 1L),
    )
    val sourcePackage = V2Source(
        file = packageFile,
        packageSha256 = sourceInstallPackage.sha256,
        publisherPublicKey = byteArrayOf(1),
        publisherFingerprint = "publisher-v2",
        manifestJson = "{}",
        compressedSizeBytes = sourceInstallPackage.sizeBytes,
        uncompressedSizeBytes = sourceInstallPackage.sizeBytes,
        manifest = V2SourceManifest(
            "source-package", "agent-v2", 2, source.sourceId, source.sourceHash,
            source.fileName, source.storedName, "raw-source".encodeToByteArray().size.toLong(),
        ),
    )
    val bundle = V2Bundle(
        file = packageFile,
        packageSha256 = "d".repeat(64),
        publisherPublicKey = byteArrayOf(1),
        publisherFingerprint = "publisher-v2",
        manifestJson = "{}",
        compressedSizeBytes = 6L,
        uncompressedSizeBytes = 6L,
        manifest = V2BundleManifest(
            V2BundleAgentDeclaration("agent-v2", 2, "agent.hagent", agent.packageSha256, agent.compressedSizeBytes),
            "balanced",
            listOf("corpus-core", "source-package"),
        ),
        profile = plan.profiles.first { it.id == "balanced" },
        agent = agent,
        corpora = listOf(corpus),
        sources = listOf(sourcePackage),
        selectedCorpusIds = listOf("corpus-core"),
    )
    val chunks = List(chunkCount) { index ->
        V2Chunk(
            id = "chunk-$index",
            sourceId = source.sourceId,
            sourceHash = source.sourceHash,
            sourceTitle = source.title,
            genre = source.genre,
            authorship = source.authorship,
            period = source.period,
            location = "第 ${index + 1} 段",
            parentIds = listOf("root"),
            context = "",
            text = "证据 $index",
            keywords = listOf("证据"),
            ngrams = emptyList(),
            conflictKey = "",
            duplicateGroup = "",
            sourceAliases = emptyList(),
            simHash = "0".repeat(16),
        )
    }
    val reader = FakeV2PackageAccess(
        packages = mapOf(
            "bundle" to bundle,
            "agent" to agent,
            "corpus" to standaloneCorpusTransform(corpus),
            "existing-corpus" to existingCorpusTransform(corpus),
            "source" to sourcePackage,
        ),
        chunks = chunks,
        nodes = listOf(V2HierarchyNode("root", "document", "测试来源", source.sourceId, null, listOf("测试来源"), "")),
        cancelDuringChunksAt = if (failure == V2InstallFailure.CANCEL_DURING_CHUNKS) 201 else null,
    )
    val dao = FakeAgentDao().apply {
        failChunkInsertCall = if (failure == V2InstallFailure.BATCH_INSERT) 2 else null
        failEvidenceCheck = failure == V2InstallFailure.EVIDENCE_CHECK
        failReadyTransition = failure == V2InstallFailure.READY_TRANSITION
    }
    return V2InstallFixture(
        repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            conversationDao = conversationDao,
            reader = reader,
            transactionRunner = AgentTransactionRunner { block ->
                try {
                    block()
                } catch (error: Throwable) {
                    dao.clearInstalledState()
                    throw error
                }
            },
            lifecycleCoordinator = lifecycleCoordinator,
            fileOps = fileOps,
            timeProvider = TimeProvider { 20L },
            ioDispatcher = Dispatchers.Unconfined,
            privateInstallAvailableBytes = privateInstallAvailableBytes,
        ),
        dao = dao,
        repositoryRoot = root,
    )
}

private class FakeV2PackageAccess(
    private val packages: Map<String, ParsedAgentPackage>,
    private val chunks: List<V2Chunk>,
    private val nodes: List<V2HierarchyNode>,
    private val cancelDuringChunksAt: Int? = null,
) : AgentBundleAccess {
    override fun inspect(file: File): AgentImportPreview = error("V2 preview uses readPackage")
    override fun read(file: File): ParsedAgentBundle = error("Not a V1 package")
    override fun readPackage(file: File): ParsedAgentPackage {
        val marker = file.readText()
        return requireNotNull(packages[marker]).withFile(file)
    }
    override suspend fun forEachChunkSuspending(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        block: suspend (AgentCorpusChunk) -> Unit,
    ) = error("Not a V1 package")
    override suspend fun forEachV2ChunkSuspending(
        file: File,
        corpusId: String?,
        block: suspend (V2Chunk) -> Unit,
    ) {
        chunks.forEachIndexed { index, chunk ->
            if (index == cancelDuringChunksAt) throw CancellationException("cancelled during chunks")
            block(chunk)
        }
    }
    override suspend fun forEachV2HierarchyNodeSuspending(
        file: File,
        corpusId: String?,
        block: suspend (V2HierarchyNode) -> Unit,
    ) {
        nodes.forEach { block(it) }
    }
    override suspend fun copyV2SourcePayload(
        file: File,
        packageId: String?,
        output: java.io.OutputStream,
    ) {
        output.write("raw-source".encodeToByteArray())
    }
}

private class RemovalConversationDao(var referenceCount: Int) : ConversationDao {
    override fun observeActive(): Flow<List<ConversationEntity>> = MutableStateFlow(emptyList())
    override suspend fun findById(id: String): ConversationEntity? = null
    override suspend fun findLatestActive(): ConversationEntity? = null
    override suspend fun findLatestActiveInProject(projectId: String): ConversationEntity? = null
    override suspend fun insert(entity: ConversationEntity) = Unit
    override suspend fun update(entity: ConversationEntity) = Unit
    override suspend fun updateIdentityIfNoUserMessages(id: String, agentId: String?, agentVersion: Int?, updatedAt: Long) = 0
    override suspend fun clearProject(projectId: String) = Unit
    override suspend fun archive(id: String, updatedAt: Long) = Unit
    override suspend fun countByAgentVersion(agentId: String, version: Int) = referenceCount
}

private fun ParsedAgentPackage.withFile(file: File): ParsedAgentPackage = when (this) {
    is V1Bundle -> V1Bundle(bundle.copy(file = file))
    is V2Agent -> copy(file = file)
    is V2Corpus -> copy(file = file)
    is V2Source -> copy(file = file)
    is V2Bundle -> copy(
        file = file,
        agent = agent.copy(file = file),
        corpora = corpora.map { it.copy(file = file) },
        sources = sources.map { it.copy(file = file) },
    )
}

private class FakeAgentBundleAccess(
    private val parsedBundle: ParsedAgentBundle? = null,
    private val chunks: List<AgentCorpusChunk> = emptyList(),
    private val chunksByCorpus: Map<String, List<AgentCorpusChunk>> = emptyMap(),
) : AgentBundleAccess {
    override fun read(file: File): ParsedAgentBundle =
        requireNotNull(parsedBundle).copy(file = file)

    override fun readPackage(file: File): ParsedAgentPackage = V1Bundle(read(file))

    override fun inspect(file: File): AgentImportPreview = AgentImportPreview(
        agentId = "agent-1",
        name = "资料研究代理",
        version = 1,
        summary = "基于资料模拟",
        publisherFingerprint = "publisher-fingerprint",
        corpora = listOf("测试资料"),
        compressedSizeBytes = file.length(),
        includesOriginalSources = false,
    )

    override suspend fun forEachChunkSuspending(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        block: suspend (AgentCorpusChunk) -> Unit,
    ) {
        check(bundle.file.isFile) { "bundle file must remain readable while indexing" }
        (chunksByCorpus[corpus.id] ?: chunks).forEach { block(it) }
    }
}

private const val V2_SOURCE_HASH = "5fec1a8b0ded0b8aa27e7afd9caddbf49b1d7dd530efc0515b85c073cdf5a0f0"
private const val V2_CORPUS_PACKAGE_HASH = "6b17b8fcc411a533c822a5117e33cf73fa6766739d7d36ac86dc4bea883af4fe"

private fun v2StoredChunk() = AgentChunkEntity(
    chunkKey = "$V2_SOURCE_HASH:chunk-0",
    sourceId = "source-1",
    sourceHash = V2_SOURCE_HASH,
    chunkId = "chunk-0",
    sourceTitle = "测试来源",
    period = "test",
    genre = V2SourceGenre.ESSAY.wireName,
    authorship = V2Authorship.DIRECT.wireName,
    location = "第 1 段",
    parentPath = "root",
    context = "",
    text = "证据 0",
    keywordsText = "证据",
    duplicateGroup = "",
    conflictKey = "",
    sourceAliasesJson = "[]",
    simHash = "0".repeat(16),
)

private fun v1ParsedBundle(file: File) = ParsedAgentBundle(
    file = file,
    packageSha256 = "bundle-sha",
    publisherPublicKey = byteArrayOf(1),
    publisherFingerprint = "publisher-fingerprint",
    manifestJson = "{}",
    agent = AgentPackageManifest(
        id = "agent-v1-session",
        name = "V1 Session",
        version = 1,
        summary = "",
        personaPath = "agent/persona.md",
        worldviewPath = "agent/worldview.jsonl",
        conceptsPath = "agent/concepts.json",
        examplesPath = "agent/examples.jsonl",
        evalPath = "agent/eval.jsonl",
        requiredCorpora = emptyList(),
    ),
    corpora = emptyList(),
    persona = "基于资料模拟",
    worldviewJsonl = "",
    compressedSizeBytes = file.length(),
    uncompressedSizeBytes = file.length(),
)

private class FailingAgentFileOps(
    private val delegate: AgentFileOps = DefaultAgentFileOps(),
    private val failMoveDestinationName: String? = null,
    var failDelete: Boolean = false,
) : AgentFileOps by delegate {
    override suspend fun moveAtomically(source: File, destination: File) {
        if (destination.name == failMoveDestinationName) {
            throw AgentBundleException("injected move failure")
        }
        delegate.moveAtomically(source, destination)
    }

    override fun delete(file: File): Boolean = if (failDelete && file.exists()) false else delegate.delete(file)
}

private class BlockingFinalMoveAgentFileOps(
    private val destinationName: String,
    private val delegate: AgentFileOps = DefaultAgentFileOps(),
) : AgentFileOps by delegate {
    val moveEntered = CompletableDeferred<Unit>()
    val releaseMove = CompletableDeferred<Unit>()

    override suspend fun moveAtomically(source: File, destination: File) {
        if (destination.name == destinationName) {
            moveEntered.complete(Unit)
            releaseMove.await()
        }
        delegate.moveAtomically(source, destination)
    }
}

private enum class StagedMutationMode {
    REPLACE_PATH,
    OVERWRITE_IN_PLACE,
}

private class StagedMutationAgentFileOps(
    private val mode: StagedMutationMode,
    private val delegate: AgentFileOps = DefaultAgentFileOps(),
) : AgentFileOps by delegate {
    lateinit var stagedFile: File
    private var mutated = false

    override suspend fun copyBounded(input: InputStream, target: File, maxBytes: Long): Long {
        if (!mutated && target.parentFile?.name == "agent-install-snapshots") {
            mutateOriginal()
        }
        return delegate.copyBounded(input, target, maxBytes)
    }

    override suspend fun moveAtomically(source: File, destination: File) {
        if (!mutated && destination.name == "agent.hagent" && source == stagedFile) {
            mutateOriginal()
        }
        delegate.moveAtomically(source, destination)
    }

    private fun mutateOriginal() {
        mutated = true
        when (mode) {
            StagedMutationMode.REPLACE_PATH -> {
                val moved = stagedFile.resolveSibling("${stagedFile.name}.original")
                check(stagedFile.renameTo(moved))
                stagedFile.writeText("evil")
            }
            StagedMutationMode.OVERWRITE_IN_PLACE -> stagedFile.writeText("evil")
        }
    }
}
