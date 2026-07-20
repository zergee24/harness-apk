package com.harnessapk.agent

import android.content.Context
import android.os.StatFs
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.harnessapk.chat.ChatRepository
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentV2InstallFlowTest {
    @Test
    fun realBuilderBundleInstallsReadyAssemblesStableGroundedInputAndExpandsOptionalCoverage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = context.cacheDir.resolve("agent-v2-acceptance-${UUID.randomUUID()}").apply { mkdirs() }
        val filesDir = root.resolve("files").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = AgentRepository(
            filesDir = filesDir,
            cacheDir = root.resolve("cache"),
            dao = database.agentDao(),
            conversationDao = database.conversationDao(),
            reader = AgentBundleReader(temporaryDirectory = root.resolve("reader")),
            transactionRunner = AgentTransactionRunner { block -> database.withTransaction { block() } },
            timeProvider = TimeProvider { 1_000L },
            ioDispatcher = Dispatchers.IO,
            privateInstallAvailableBytes = { StatFs(filesDir.absolutePath).availableBytes },
        )
        try {
            val bundleName = "fixture.researcher-v2-balanced.hbundle"
            val session = repository.preparePackageImport(bundleName) { testAssets.open(bundleName) }
            val parsed = session.parsedPackage as V2Bundle
            val optional = parsed.agent.installPlan.packages.single { it.installClass == V2InstallClass.OPTIONAL }

            val installed = repository.installPackage(session, profileId = "balanced")

            assertEquals(AgentStatus.READY, installed.agent.status)
            val installedDetail = requireNotNull(repository.packageDetail("fixture.researcher", 2))
            val balancedProfile = parsed.agent.installPlan.profiles.single { it.id == "balanced" }
            val declarations = parsed.agent.installPlan.packages.associateBy(V2InstallPackage::id)
            val balancedBytes = balancedProfile.packageIds.fold(parsed.manifest.agent.sizeBytes) { total, packageId ->
                Math.addExact(total, declarations.getValue(packageId).sizeBytes)
            }
            assertEquals("balanced", installedDetail.selectedProfileId)
            assertEquals(balancedBytes, installedDetail.exactInstalledBytes)
            val beforeCoverage = requireNotNull(repository.loadPackage("fixture.researcher", 2))
            assertTrue(optional.id in beforeCoverage.missingOptionalCoverage)

            val chatRepository = ChatRepository(
                conversationDao = database.conversationDao(),
                messageDao = database.messageDao(),
                messagePartDao = database.messagePartDao(),
                attachmentDao = database.messageAttachmentDao(),
                memoryDao = database.conversationMemoryDao(),
                timeProvider = TimeProvider { 2_000L },
            )
            val conversationId = chatRepository.createConversation(
                title = "固定版本验收",
                agentId = "fixture.researcher",
                agentVersion = 2,
            )
            val question = "研究者应如何收集事实并形成结论？"
            chatRepository.insertUserMessage(conversationId, question, emptyList())
            val assembler = AgentContextAssembler(repository)
            val firstContext = requireNotNull(
                assembler.assemble(AgentContextRequest("fixture.researcher", 2, question)),
            )
            assertTrue(firstContext.evidence.isNotEmpty())
            firstContext.evidence.forEach { evidence ->
                assertTrue(firstContext.systemPrompt.contains(evidence.sourceTitle))
                assertTrue(firstContext.systemPrompt.contains(evidence.location))
                assertTrue(firstContext.systemPrompt.contains(evidence.text))
            }
            val firstMetadata = firstContext.evidence.map { listOf(it.chunkKey, it.sourceTitle, it.location) }

            val corpusSession = repository.preparePackageImport(optional.fileName) {
                testAssets.open(optional.fileName)
            }
            val expansion = repository.installPackage(corpusSession)

            assertEquals(AgentStatus.READY, expansion.agent.status)
            val afterCoverage = requireNotNull(repository.loadPackage("fixture.researcher", 2))
            assertFalse(optional.id in afterCoverage.missingOptionalCoverage)
            assertEquals(2, requireNotNull(chatRepository.conversation(conversationId)).agentVersion)
            val reopenedRepository = AgentRepository(
                filesDir = filesDir,
                cacheDir = root.resolve("cache-reopened"),
                dao = database.agentDao(),
                conversationDao = database.conversationDao(),
                reader = AgentBundleReader(temporaryDirectory = root.resolve("reader-reopened")),
                transactionRunner = AgentTransactionRunner { block -> database.withTransaction { block() } },
                timeProvider = TimeProvider { 3_000L },
                ioDispatcher = Dispatchers.IO,
                privateInstallAvailableBytes = { StatFs(filesDir.absolutePath).availableBytes },
            )
            val expandedDetail = requireNotNull(reopenedRepository.packageDetail("fixture.researcher", 2))
            assertEquals("complete", expandedDetail.selectedProfileId)
            assertEquals(Math.addExact(balancedBytes, optional.sizeBytes), expandedDetail.exactInstalledBytes)
            assertEquals(installedDetail.optional.installed + 1, expandedDetail.optional.installed)
            assertEquals(1_000L, expandedDetail.lastEvidenceExpandedAt)
            val secondContext = requireNotNull(
                assembler.assemble(AgentContextRequest("fixture.researcher", 2, question)),
            )
            assertEquals(firstMetadata, secondContext.evidence.map { listOf(it.chunkKey, it.sourceTitle, it.location) })
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun canonicalAgentSupportsBalancedThenSourceBundleWithoutVersionOrHashConflict() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = context.cacheDir.resolve("agent-v2-source-upgrade-${UUID.randomUUID()}").apply { mkdirs() }
        val filesDir = root.resolve("files").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = AgentRepository(
            filesDir = filesDir,
            cacheDir = root.resolve("cache"),
            dao = database.agentDao(),
            conversationDao = database.conversationDao(),
            reader = AgentBundleReader(temporaryDirectory = root.resolve("reader")),
            transactionRunner = AgentTransactionRunner { block -> database.withTransaction { block() } },
            timeProvider = TimeProvider { 1_000L },
            ioDispatcher = Dispatchers.IO,
            privateInstallAvailableBytes = { StatFs(filesDir.absolutePath).availableBytes },
        )
        try {
            val balancedName = "fixture.researcher-v2-balanced.hbundle"
            val balancedSession = repository.preparePackageImport(balancedName) { testAssets.open(balancedName) }
            val balanced = balancedSession.parsedPackage as V2Bundle
            repository.installPackage(balancedSession, "balanced")
            val storedVersion = requireNotNull(database.agentDao().findVersion("fixture.researcher", 2))
            val chatRepository = ChatRepository(
                conversationDao = database.conversationDao(),
                messageDao = database.messageDao(),
                messagePartDao = database.messagePartDao(),
                attachmentDao = database.messageAttachmentDao(),
                memoryDao = database.conversationMemoryDao(),
                timeProvider = TimeProvider { 1_500L },
            )
            val conversationId = chatRepository.createConversation(
                title = "原文补装版本固定",
                agentId = "fixture.researcher",
                agentVersion = 2,
            )

            val sourceName = "fixture.researcher-v2-source.hbundle"
            val sourceSession = repository.preparePackageImport(sourceName) { testAssets.open(sourceName) }
            val source = sourceSession.parsedPackage as V2Bundle

            assertEquals(balanced.agent.packageSha256, source.agent.packageSha256)
            assertEquals(storedVersion.bundleSha256, source.agent.packageSha256)
            val result = repository.installPackage(sourceSession, "source")

            assertEquals(AgentStatus.READY, result.agent.status)
            assertEquals(2, result.agent.activeVersion)
            val detail = requireNotNull(repository.packageDetail("fixture.researcher", 2))
            assertEquals("source", detail.selectedProfileId)
            assertEquals(detail.source.planned, detail.source.installed)
            assertEquals(2, chatRepository.conversation(conversationId)?.agentVersion)
            assertEquals(storedVersion.bundleSha256, database.agentDao().findVersion("fixture.researcher", 2)?.bundleSha256)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun completeBundleCarriesCanonicalSourcePlanButDoesNotCarrySourcePayloads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = context.cacheDir.resolve("agent-v2-complete-contract-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = database.agentDao(),
            reader = AgentBundleReader(temporaryDirectory = root.resolve("reader")),
            ioDispatcher = Dispatchers.IO,
        )
        try {
            val name = "fixture.researcher-v2-complete.hbundle"
            val session = repository.preparePackageImport(name) { assets.open(name) }
            val bundle = session.parsedPackage as V2Bundle
            val sourceIds = bundle.agent.installPlan.packages
                .filter { it.type == V2PackageType.SOURCE }
                .mapTo(linkedSetOf(), V2InstallPackage::id)

            assertTrue(sourceIds.isNotEmpty())
            assertTrue(sourceIds.none(bundle.manifest.selectedPackageIds::contains))
            val failure = runCatching { repository.installPackage(session, "source") }.exceptionOrNull()
            assertTrue(failure is AgentBundleException)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun canonicalAgentSupportsCompleteThenAllStandaloneSources() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = context.cacheDir.resolve("agent-v2-standalone-source-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = database.agentDao(),
            reader = AgentBundleReader(temporaryDirectory = root.resolve("reader")),
            transactionRunner = AgentTransactionRunner { block -> database.withTransaction { block() } },
            timeProvider = TimeProvider { 2_000L },
            ioDispatcher = Dispatchers.IO,
        )
        try {
            val completeName = "fixture.researcher-v2-complete.hbundle"
            val completeSession = repository.preparePackageImport(completeName) { assets.open(completeName) }
            val complete = completeSession.parsedPackage as V2Bundle
            repository.installPackage(completeSession, "complete")
            val sourceDeclarations = complete.agent.installPlan.packages
                .filter { it.type == V2PackageType.SOURCE }
            assertTrue(sourceDeclarations.isNotEmpty())

            sourceDeclarations.forEach { declaration ->
                val sourceSession = repository.preparePackageImport(declaration.fileName) {
                    assets.open(declaration.fileName)
                }
                repository.installPackage(sourceSession)
            }

            val detail = requireNotNull(repository.packageDetail("fixture.researcher", 2))
            assertEquals("source", detail.selectedProfileId)
            assertEquals(detail.source.planned, detail.source.installed)
            assertEquals(2, repository.agent("fixture.researcher")?.activeVersion)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}
