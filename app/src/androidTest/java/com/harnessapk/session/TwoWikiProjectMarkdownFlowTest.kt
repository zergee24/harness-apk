package com.harnessapk.session

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.harnessapk.chat.ChatRepository
import com.harnessapk.chat.MessageStatus
import com.harnessapk.chat.StreamingMessageSnapshot
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import com.harnessapk.chat.WikiSourcePartWriter
import com.harnessapk.chat.legacyVisibleText
import com.harnessapk.common.TimeProvider
import com.harnessapk.project.FileProjectRepository
import com.harnessapk.project.ProjectWorkspaceGatewayAdapter
import com.harnessapk.storage.AppDatabase
import com.harnessapk.ui.chat.persistMarkdownWriteBackLinks
import com.harnessapk.wiki.ConfirmedWikiImport
import com.harnessapk.wiki.ConversationWikiRepository
import com.harnessapk.wiki.ConversationWikiTransactionRunner
import com.harnessapk.wiki.InstalledWikiContentStore
import com.harnessapk.wiki.WikiContentStore
import com.harnessapk.wiki.WikiContextAssembler
import com.harnessapk.wiki.WikiEvidence
import com.harnessapk.wiki.WikiPackageReader
import com.harnessapk.wiki.WikiQueryAuthorization
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiRetrievalResult
import com.harnessapk.wiki.WikiRetrievalStatus
import com.harnessapk.wiki.WikiRouteDecision
import com.harnessapk.wiki.WikiRouteReason
import com.harnessapk.wiki.WikiRuntimeContext
import com.harnessapk.wiki.WikiSourceDescriptor
import com.harnessapk.wiki.WikiTurnAlias
import com.harnessapk.wiki.WikiTurnIntentMode
import com.harnessapk.wiki.WikiUsage
import com.harnessapk.wiki.WikiVersionHealthReporter
import com.harnessapk.wiki.WikiRepository
import com.harnessapk.wiki.WikiTransactionRunner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TwoWikiProjectMarkdownFlowTest {
    @Test
    fun installedComparisonEvidenceBecomesPortableReviewedProjectMarkdown() = runBlocking {
        withEnvironment { environment ->
            val project = environment.projectRepository.createProject("双 Wiki 史料项目")
            val conversationId = environment.chatRepository.createConversation(
                title = "双 Wiki 比较",
                projectId = project.id,
            )
            environment.conversationWikiRepository.copyDefaultsToConversation(conversationId)
            val scope = environment.conversationWikiRepository.snapshotEnabled(conversationId)
            assertEquals(environment.titles.keys.sortedBy(WikiRef::wikiId), scope)

            val userMessageId = environment.chatRepository.insertUserMessage(
                conversationId = conversationId,
                content = "比较史料测试库和纪事测试库中的司马光礼制，并写入项目笔记。",
                attachments = emptyList(),
            )
            val assistantMessageId = environment.chatRepository.insertAssistantPending(
                conversationId = conversationId,
                providerId = "fixture-provider",
                model = "fixture-model",
            )
            val runtimeContext = comparisonRuntimeContext(environment, scope)
            val preparedAnswer = WikiSourcePartWriter(
                conversationWikiRepository = environment.conversationWikiRepository,
                chatRepository = environment.chatRepository,
                contentStore = environment.contentStore,
                timeProvider = FIXTURE_TIME,
            ).persist(
                messageId = assistantMessageId,
                snapshot = fakeProviderAnswer(),
                context = runtimeContext,
            )

            assertEquals(1, preparedAnswer.parts.count { it.type == UiMessagePartType.WIKI_SOURCES })
            val sourceContext = WikiMarkdownContextRepository(
                environment.database.conversationWikiDao(),
            ).forAssistantMessage(assistantMessageId)
            assertEquals(2, sourceContext.citations.citations.size)
            assertFalse(sourceContext.coverage.hasMissingComparisonEvidence)

            val portableAnswer = WikiMarkdownCitationFormatter.toPortableMarkdown(
                preparedAnswer.legacyVisibleText(),
                sourceContext.citations,
            )
            val plan = parseAndValidateMarkdownUpdatePlanResponse(
                response = fakeMarkdownProviderResponse(portableAnswer),
                wikiCitations = sourceContext.citations,
                wikiCoverage = sourceContext.coverage,
            )
            val controller = MarkdownFileChangeController(timeProvider = { 1L })
            val ready = controller.markReady(
                state = controller.createPlanningDraft(conversationId, project.id, userMessageId),
                plan = plan,
                snapshots = emptyList(),
            )
            val proposal = plan.proposals.single()
            val target = project.rootDirectory.resolve(proposal.path)

            assertEquals(MarkdownFileChangeStatus.READY, ready.draft.status)
            assertFalse(target.exists())

            val applyResult = environment.projectGateway.applyMarkdownUpdates(
                projectId = project.id,
                updates = controller.retainedProposals(ready),
            )
            assertTrue(applyResult.isFullyApplied)
            assertTrue(target.isFile)
            assertEquals(MarkdownFileChangeStatus.APPLIED, controller.markApplyResult(ready, applyResult).draft.status)

            persistMarkdownWriteBackLinks(applyResult) { relativePath ->
                environment.notebookRepository.linkMarkdown(conversationId, project.id, relativePath)
            }
            assertEquals(
                listOf(proposal.path),
                environment.notebookRepository.observeLinks(conversationId).first().map { it.relativePath },
            )

            environment.root.resolve("files/wikis").deleteRecursively()
            val reopened = environment.projectGateway.readDeliverable(project.id, proposal.path)
            val persistedCitations = environment.database.conversationWikiDao().listCitationsForMessage(assistantMessageId)

            assertEquals(proposal.markdown, reopened)
            assertTrue(reopened.contains("史料测试库 v1"))
            assertTrue(reopened.contains("纪事测试库 v1"))
            assertEquals(2, reopened.lines().count { it.startsWith("[^hwiki-") })
            assertFalse(reopened.contains("harness-wiki://"))
            assertFalse(reopened.contains(environment.root.absolutePath))
            assertFalse(reopened.trimStart().startsWith("---"))
            persistedCitations.forEach { citation ->
                assertFalse(reopened.contains(citation.id))
                assertFalse(reopened.contains(citation.chunkId))
                assertFalse(reopened.contains(citation.originalTextSha256))
            }
        }
    }

    private suspend fun comparisonRuntimeContext(
        environment: TestEnvironment,
        scope: List<WikiRef>,
    ): WikiRuntimeContext {
        val evidence = scope.mapIndexed { index, ref -> environment.evidenceFor(ref, index + 1) }
        return WikiContextAssembler(
            route = { request ->
                assertEquals(WikiTurnIntentMode.COMPARE_NAMED, request.intent.mode)
                assertEquals(scope.mapTo(sortedSetOf(), WikiRef::wikiId), request.intent.namedWikiIds)
                WikiRouteDecision(
                    routerVersion = "fixture-comparison-router",
                    reason = WikiRouteReason.EXPLICIT_COMPARISON,
                    candidates = emptyList(),
                    selectedRefs = scope,
                )
            },
            retrieve = { request ->
                assertEquals(scope, request.routeDecision.selectedRefs)
                WikiRetrievalResult(
                    retrieverVersion = "fixture-comparison-retriever",
                    status = WikiRetrievalStatus.HIT,
                    routeDecision = request.routeDecision,
                    usages = scope.mapIndexed { index, ref ->
                        WikiUsage(
                            ref = ref,
                            scoutRank = index + 1,
                            deepHitCount = 1,
                            selectedEvidenceCount = 1,
                            enteredContext = true,
                        )
                    },
                    evidence = evidence,
                    missingComparisonWikiIds = emptySet(),
                )
            },
            aliasesProvider = {
                environment.titles.map { (ref, title) -> WikiTurnAlias(ref.wikiId, title) }
            },
            titleProvider = { ref -> environment.titles[ref] },
        ).assemble(
            query = "比较史料测试库和纪事测试库中的司马光礼制",
            scope = scope,
        )
    }

    private suspend fun TestEnvironment.evidenceFor(ref: WikiRef, ordinal: Int): WikiEvidence {
        val chunk = contentStore.searchSources(ref, "司马光", limit = 20)
            .first { hit -> hit.originalText.contains("司馬光") }
            .chunk
        val section = checkNotNull(contentStore.findSection(ref, chunk.sectionId))
        val document = checkNotNull(contentStore.findDocument(ref, section.documentId))
        return WikiEvidence(
            token = "⟦W$ordinal⟧",
            ref = ref,
            wikiTitle = titles.getValue(ref),
            documentId = document.id,
            sectionId = section.id,
            chunkId = chunk.id,
            sourceTitle = document.title,
            sectionPath = section.path,
            locatorLabel = chunk.locator.label,
            originalText = chunk.originalText,
            originalTextSha256 = chunk.originalText.sha256(),
        )
    }

    private suspend fun withEnvironment(block: suspend (TestEnvironment) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("two-wiki-project-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val wikiRepository = WikiRepository(
                filesDir = root.resolve("files"),
                dao = database.wikiDao(),
                transactionRunner = WikiTransactionRunner { operation -> database.withTransaction { operation() } },
                timeProvider = FIXTURE_TIME,
            )
            val first = installFixture(root, wikiRepository, FIRST_FIXTURE)
            val second = installFixture(root, wikiRepository, SECOND_FIXTURE)
            val contentStore = InstalledWikiContentStore(
                filesDir = root.resolve("files"),
                wikiDao = database.wikiDao(),
                healthReporter = WikiVersionHealthReporter(wikiRepository::markInvalid),
            )
            val chatRepository = ChatRepository(
                conversationDao = database.conversationDao(),
                messageDao = database.messageDao(),
                messagePartDao = database.messagePartDao(),
                attachmentDao = database.messageAttachmentDao(),
                memoryDao = database.conversationMemoryDao(),
                timeProvider = FIXTURE_TIME,
            )
            val conversationWikiRepository = ConversationWikiRepository(
                dao = database.conversationWikiDao(),
                transactionRunner = ConversationWikiTransactionRunner { operation ->
                    database.withTransaction { operation() }
                },
                timeProvider = FIXTURE_TIME,
            )
            val projectRepository = FileProjectRepository(root.resolve("projects"), FIXTURE_TIME)
            val notebookRepository = MarkdownNotebookRepository(
                chatRepository = chatRepository,
                linkDao = database.conversationMarkdownLinkDao(),
                draftDao = database.markdownChangeDraftDao(),
                timeProvider = FIXTURE_TIME,
            )
            block(
                TestEnvironment(
                    root = root,
                    database = database,
                    contentStore = contentStore,
                    chatRepository = chatRepository,
                    conversationWikiRepository = conversationWikiRepository,
                    projectRepository = projectRepository,
                    projectGateway = ProjectWorkspaceGatewayAdapter(projectRepository),
                    notebookRepository = notebookRepository,
                    titles = mapOf(
                        first.ref to first.title,
                        second.ref to second.title,
                    ),
                ),
            )
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private suspend fun installFixture(
        root: File,
        repository: WikiRepository,
        fixtureName: String,
    ): InstalledFixture {
        val archive = root.resolve(fixtureName)
        InstrumentationRegistry.getInstrumentation().context.assets.open(fixtureName).use { input ->
            archive.outputStream().use(input::copyTo)
        }
        val inspection = WikiPackageReader(root.resolve("inspect-$fixtureName").toPath()).inspect(archive.toPath())
        repository.install(
            ConfirmedWikiImport(
                inspection = inspection,
                packageHash = archive.readBytes().sha256(),
                enabledForNewConversations = true,
            ),
        )
        return InstalledFixture(inspection.manifest.ref, inspection.manifest.title)
    }

    private fun fakeProviderAnswer(): StreamingMessageSnapshot = StreamingMessageSnapshot(
        status = MessageStatus.SUCCEEDED,
        parts = listOf(
            UiMessagePartDraft(
                index = 0,
                type = UiMessagePartType.TEXT,
                content = "史料测试库保留了司馬光论礼制的可核对原文。⟦W1⟧\n\n" +
                    "纪事测试库也保留了同一主题的可核对原文。⟦W2⟧",
                stable = true,
            ),
        ),
    )

    private fun fakeMarkdownProviderResponse(portableAnswer: String): String =
        """
        {"updates":[{"operation":"create","path":"notes/history-comparison.md","title":"史料对比","reason":"沉淀本轮双 Wiki 对比","markdown":${JsonPrimitive("# 史料对比\n\n$portableAnswer")}}]}
        """.trimIndent()

    private data class InstalledFixture(
        val ref: WikiRef,
        val title: String,
    )

    private data class TestEnvironment(
        val root: File,
        val database: AppDatabase,
        val contentStore: WikiContentStore,
        val chatRepository: ChatRepository,
        val conversationWikiRepository: ConversationWikiRepository,
        val projectRepository: FileProjectRepository,
        val projectGateway: ProjectWorkspaceGatewayAdapter,
        val notebookRepository: MarkdownNotebookRepository,
        val titles: Map<WikiRef, String>,
    )

    private companion object {
        val FIXTURE_TIME = TimeProvider { 1L }
        const val FIRST_FIXTURE = "fixture.history-v1.hwiki"
        const val SECOND_FIXTURE = "fixture.annals-v1.hwiki"
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { "%02x".format(it) }

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }
