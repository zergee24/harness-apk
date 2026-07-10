package com.harnessapk.ui.project

import com.harnessapk.chat.Conversation
import com.harnessapk.git.GitStatusSummary
import com.harnessapk.project.ProjectArtifactType
import com.harnessapk.project.DeliverableTemplate
import com.harnessapk.project.ProjectDeliverable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectSessionLaunchUiStateTest {
    @Test
    fun workbenchOverviewUsesLoadedGitStateWithoutRequestingRefresh() {
        val overview = projectWorkbenchOverview(2, 3, gitStatus("test", false, 1))

        assertEquals("2 个会话", overview.conversationLabel)
        assertEquals("3 个文件", overview.deliverableLabel)
        assertEquals("test · 1 项变更", overview.gitLabel)
    }

    @Test
    fun workbenchTabGuidanceExplainsEachExistingTab() {
        assertEquals("在当前项目内开始或继续工作", projectWorkbenchTabGuidance(ProjectWorkbenchTab.CONVERSATIONS))
        assertEquals("查看会话沉淀和已写入文件", projectWorkbenchTabGuidance(ProjectWorkbenchTab.FOLDER))
        assertEquals("查看当前分支和工作区变更", projectWorkbenchTabGuidance(ProjectWorkbenchTab.GIT))
    }

    @Test
    fun projectHeaderKeepsFrequentActionsDirectAndMovesLowFrequencyActionsToOverflow() {
        val layout = projectHeaderActionLayout(hasProject = true)

        assertTrue(layout.showCreateProjectDirectly)
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

    @Test
    fun projectHeaderWithoutSelectionOnlyOffersAvailableOverflowActions() {
        val layout = projectHeaderActionLayout(hasProject = false)

        assertFalse(layout.showCreateProjectDirectly)
        assertEquals(emptyList<ProjectHeaderAction>(), layout.directActions)
        assertEquals(
            listOf(ProjectHeaderAction.CLONE, ProjectHeaderAction.IMPORT),
            layout.overflowActions,
        )
    }

    @Test
    fun projectSessionTitleIncludesProjectAndDeliverable() {
        assertEquals(
            "移动端 Harness · 项目模式 PRD",
            projectSessionTitle(
                projectName = "移动端 Harness",
                deliverableTitle = "项目模式 PRD",
            ),
        )
    }

    @Test
    fun projectSessionTitleFallsBackToProjectOnly() {
        assertEquals(
            "移动端 Harness · 项目会话",
            projectSessionTitle(
                projectName = "移动端 Harness",
                deliverableTitle = null,
            ),
        )
    }

    @Test
    fun projectDeliverableSupportingTextHidesTemplateClassification() {
        val deliverable = ProjectDeliverable(
            id = "sessions/conversation-1.md",
            title = "会话写回",
            relativePath = "sessions/conversation-1.md",
            template = DeliverableTemplate.REQUIREMENT,
            updatedAt = 0L,
        )

        assertEquals("sessions/conversation-1.md", projectDeliverableSupportingText(deliverable))
    }

    @Test
    fun projectWorkbenchDefaultsToConversationsTab() {
        assertEquals(ProjectWorkbenchTab.CONVERSATIONS, defaultProjectWorkbenchTab())
        assertEquals("会话", ProjectWorkbenchTab.CONVERSATIONS.label)
        assertEquals("文件夹", ProjectWorkbenchTab.FOLDER.label)
    }

    @Test
    fun workbenchTargetsMapToFolderAndGitTabs() {
        assertEquals(ProjectWorkbenchTab.FOLDER, projectWorkbenchTab(ProjectWorkbenchDestination.FILES))
        assertEquals(ProjectWorkbenchTab.GIT, projectWorkbenchTab(ProjectWorkbenchDestination.GIT))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun filesRefreshCannotPublishAfterNewerGitTargetForSameProject() = runTest {
        val refreshController = ProjectDeliverableRefreshController()
        val repository = DelayedDeliverableRepository()
        var publishedDeliverables = emptyList<ProjectDeliverable>()

        val filesRefresh = refreshController.acceptWorkbenchTarget(
            target = target("project-p", ProjectWorkbenchDestination.FILES, "docs/first.md", 1),
            selectedProjectId = "project-p",
        )!!
        val filesJob = launch {
            val deliverables = repository.listDeliverables("project-p")
            if (refreshController.canPublish(filesRefresh)) publishedDeliverables = deliverables
        }
        runCurrent()

        refreshController.acceptWorkbenchTarget(
            target = target("project-p", ProjectWorkbenchDestination.GIT, null, 2),
            selectedProjectId = "project-p",
        )
        repository.complete("project-p", listOf(deliverable("docs/first.md", ProjectArtifactType.MARKDOWN)))
        filesJob.join()

        assertEquals(emptyList<ProjectDeliverable>(), publishedDeliverables)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun crossProjectFilesTargetKeepsPreferredPathOverSelectedProjectOrdinaryRefresh() = runTest {
        val refreshController = ProjectDeliverableRefreshController()
        val repository = DelayedDeliverableRepository()
        var selectedDeliverableId: String? = null

        val targetedRefresh = refreshController.acceptWorkbenchTarget(
            target = target("project-q", ProjectWorkbenchDestination.FILES, "docs/requested.md", 3),
            selectedProjectId = "project-p",
        )!!
        val targetedJob = async {
            val deliverables = repository.listDeliverables("project-q")
            if (refreshController.canPublish(targetedRefresh)) {
                selectedDeliverableId = selectedDeliverableIdForRefresh(
                    preferredPath = targetedRefresh.preferredPath,
                    currentSelectedDeliverableId = selectedDeliverableId,
                    filteredDeliverables = deliverables,
                )
            }
        }
        runCurrent()

        assertEquals(
            null,
            refreshController.beginOrdinaryFilesRefresh(
                projectId = "project-q",
                query = "",
                filter = ProjectArtifactFilter.ALL,
            ),
        )
        repository.complete(
            "project-q",
            listOf(
                deliverable("README.md", ProjectArtifactType.MARKDOWN),
                deliverable("docs/requested.md", ProjectArtifactType.MARKDOWN),
            ),
        )
        targetedJob.await()

        assertEquals("docs/requested.md", selectedDeliverableId)
    }

    @Test
    fun sameProjectFilesTargetSuppressesTargetResetRefreshAndKeepsLaterSearchRefresh() {
        val refreshController = ProjectDeliverableRefreshController()

        refreshController.acceptWorkbenchTarget(
            target = target("project-p", ProjectWorkbenchDestination.FILES, "docs/requested.md", 4),
            selectedProjectId = "project-p",
            currentSearchQuery = "requested",
        )

        assertEquals(
            null,
            refreshController.beginOrdinaryFilesRefresh(
                projectId = "project-p",
                query = "",
                filter = ProjectArtifactFilter.ALL,
            ),
        )

        val searchRefresh = refreshController.beginOrdinaryFilesRefresh(
            projectId = "project-p",
            query = "requested",
            filter = ProjectArtifactFilter.ALL,
        )

        assertEquals("requested", searchRefresh?.query)

        val filterRefresh = refreshController.beginOrdinaryFilesRefresh(
            projectId = "project-p",
            query = "requested",
            filter = ProjectArtifactFilter.CODE,
        )

        assertEquals(ProjectArtifactFilter.CODE, filterRefresh?.filter)
    }

    @Test
    fun sameProjectFilesTargetWithEmptySearchKeepsFirstNormalSearchRefresh() {
        val refreshController = ProjectDeliverableRefreshController()

        refreshController.acceptWorkbenchTarget(
            target = target("project-p", ProjectWorkbenchDestination.FILES, "docs/requested.md", 5),
            selectedProjectId = "project-p",
            currentSearchQuery = "",
        )

        val searchRefresh = refreshController.beginOrdinaryFilesRefresh(
            projectId = "project-p",
            query = "requested",
            filter = ProjectArtifactFilter.ALL,
        )

        assertEquals("requested", searchRefresh?.query)
    }

    @Test
    fun gitRefreshIsOnlyTriggeredBySelectingGitTab() {
        assertTrue(shouldRefreshGitOnTabSelection(ProjectWorkbenchTab.GIT))
        assertFalse(shouldRefreshGitOnTabSelection(ProjectWorkbenchTab.FOLDER))
        assertFalse(shouldRefreshGitOnTabSelection(ProjectWorkbenchTab.CONVERSATIONS))
    }

    @Test
    fun gitRefreshPublishesOnlyCurrentProjectGenerationAcrossRapidTargets() {
        val refreshController = ProjectGitRefreshController()

        val projectP = refreshController.begin("project-p")!!
        val firstProjectQ = refreshController.begin("project-q")!!
        val latestProjectQ = refreshController.begin("project-q")!!

        assertFalse(refreshController.canPublish(projectP, selectedProjectId = "project-p"))
        assertFalse(refreshController.canPublish(firstProjectQ, selectedProjectId = "project-q"))
        assertFalse(refreshController.canPublish(latestProjectQ, selectedProjectId = "project-p"))
        assertTrue(refreshController.canPublish(latestProjectQ, selectedProjectId = "project-q"))
    }

    @Test
    fun clearingGitTargetInvalidatesPendingRefresh() {
        val refreshController = ProjectGitRefreshController()
        val pending = refreshController.begin("project-p")!!

        assertEquals(null, refreshController.begin(null))

        assertFalse(refreshController.canPublish(pending, selectedProjectId = "project-p"))
    }

    @Test
    fun projectSelectionRefreshesGitOnlyWhileGitTabIsActive() {
        assertTrue(shouldRefreshGitForProjectSelection(ProjectWorkbenchTab.GIT, "project-q"))
        assertFalse(shouldRefreshGitForProjectSelection(ProjectWorkbenchTab.FOLDER, "project-q"))
        assertFalse(shouldRefreshGitForProjectSelection(ProjectWorkbenchTab.GIT, null))
    }

    @Test
    fun projectFolderDefaultsToAllArtifactTypes() {
        assertEquals(ProjectArtifactFilter.ALL, defaultProjectArtifactFilter())
        assertEquals(
            listOf("全部", "Markdown", "Office", "PDF", "代码", "图片", "其他"),
            ProjectArtifactFilter.entries.map { it.label },
        )
    }

    @Test
    fun projectArtifactFilterGroupsOfficeAndSpecificTypes() {
        assertTrue(ProjectArtifactFilter.ALL.matches(ProjectArtifactType.DOCUMENT))
        assertTrue(ProjectArtifactFilter.OFFICE.matches(ProjectArtifactType.DOCUMENT))
        assertTrue(ProjectArtifactFilter.OFFICE.matches(ProjectArtifactType.SPREADSHEET))
        assertTrue(ProjectArtifactFilter.OFFICE.matches(ProjectArtifactType.PRESENTATION))
        assertFalse(ProjectArtifactFilter.OFFICE.matches(ProjectArtifactType.PDF))
        assertTrue(ProjectArtifactFilter.CODE.matches(ProjectArtifactType.CODE))
        assertTrue(ProjectArtifactFilter.IMAGES.matches(ProjectArtifactType.IMAGE))
    }

    @Test
    fun buildProjectArtifactTreeGroupsDeliverablesByRelativePath() {
        val tree = buildProjectArtifactTree(
            listOf(
                deliverable("README.md", ProjectArtifactType.MARKDOWN),
                deliverable("docs/spec.docx", ProjectArtifactType.DOCUMENT),
                deliverable("src/main/App.kt", ProjectArtifactType.CODE),
                deliverable("src/test/AppTest.kt", ProjectArtifactType.CODE),
            ),
        )

        assertEquals(listOf("docs", "src", "README.md"), tree.map { it.name })
        assertTrue(tree[0].isDirectory)
        assertEquals(listOf("spec.docx"), tree[0].children.map { it.name })
        assertTrue(tree[1].isDirectory)
        assertEquals(listOf("main", "test"), tree[1].children.map { it.name })
        assertEquals(listOf("App.kt"), tree[1].children[0].children.map { it.name })
        assertFalse(tree[2].isDirectory)
        assertEquals("README.md", tree[2].deliverable?.relativePath)
    }

    @Test
    fun flattenProjectArtifactTreeRespectsCollapsedDirectories() {
        val tree = buildProjectArtifactTree(
            listOf(
                deliverable("docs/spec.docx", ProjectArtifactType.DOCUMENT),
                deliverable("src/main/App.kt", ProjectArtifactType.CODE),
            ),
        )

        assertEquals(
            listOf("docs", "docs/spec.docx", "src"),
            flattenProjectArtifactTree(tree, collapsedDirectoryPaths = setOf("src")).map { it.path },
        )
    }

    @Test
    fun markdownPreviewScrollContainerIsHeightBoundedInsideProjectList() {
        val source = File("src/main/java/com/harnessapk/ui/project/ProjectScreen.kt").readText()

        assertTrue(source.contains("heightIn(min = 280.dp, max = 520.dp)"))
        assertTrue(source.contains(".verticalScroll(rememberScrollState())"))
    }

    @Test
    fun projectMarkdownToolbarExposesPdfExportAction() {
        val source = File("src/main/java/com/harnessapk/ui/project/ProjectScreen.kt").readText()

        assertTrue(source.contains("AndroidMarkdownPdfWriter"))
        assertTrue(source.contains("writePdfExport"))
        assertTrue(source.contains("contentDescription = \"导出 PDF\""))
    }

    @Test
    fun projectConversationsOnlyIncludesCurrentProjectSortedByUpdatedAt() {
        val conversations = listOf(
            conversation("plain", "普通会话", 30L, null),
            conversation("p1-old", "旧方案", 10L, "project-1"),
            conversation("p2", "其他项目", 40L, "project-2"),
            conversation("p1-new", "新方案", 50L, "project-1"),
        )

        assertEquals(
            listOf("p1-new", "p1-old"),
            projectConversations(conversations, "project-1").map { it.id },
        )
    }

    @Test
    fun projectConversationSupportingTextIncludesUpdatedAtAndPromptState() {
        assertEquals(
            "1970-01-01 08:00 · 已设提示词",
            projectConversationSupportingText(
                conversation("p1", "项目会话", 0L, "project-1", promptFinal = "你是项目助手"),
            ),
        )
    }

    private fun conversation(
        id: String,
        title: String,
        updatedAt: Long,
        projectId: String?,
        promptFinal: String = "",
    ): Conversation = Conversation(
        id = id,
        title = title,
        updatedAt = updatedAt,
        projectId = projectId,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = promptFinal,
    )

    private fun deliverable(
        relativePath: String,
        artifactType: ProjectArtifactType,
    ): ProjectDeliverable = ProjectDeliverable(
        id = relativePath,
        title = relativePath.substringAfterLast('/').substringBeforeLast('.'),
        relativePath = relativePath,
        template = DeliverableTemplate.RESEARCH,
        updatedAt = 0L,
        artifactType = artifactType,
    )

    private fun target(
        projectId: String,
        destination: ProjectWorkbenchDestination,
        selectedPath: String?,
        requestKey: Int,
    ) = ProjectWorkbenchTarget(
        projectId = projectId,
        destination = destination,
        selectedPath = selectedPath,
        requestKey = requestKey,
    )

    private fun gitStatus(
        currentBranch: String,
        isClean: Boolean,
        changeCount: Int,
    ) = GitStatusSummary(
        currentBranch = currentBranch,
        isClean = isClean,
        stagedCount = 0,
        unstagedCount = changeCount,
        untrackedCount = 0,
        aheadCount = 0,
        behindCount = 0,
        files = List(changeCount) { index ->
            com.harnessapk.git.GitFileChange(
                path = "change-$index",
                type = com.harnessapk.git.GitChangeType.MODIFIED,
            )
        },
    )

    private class DelayedDeliverableRepository {
        private val responses = mutableMapOf<String, CompletableDeferred<List<ProjectDeliverable>>>()

        suspend fun listDeliverables(projectId: String): List<ProjectDeliverable> =
            responses.getOrPut(projectId) { CompletableDeferred() }.await()

        fun complete(projectId: String, deliverables: List<ProjectDeliverable>) {
            responses.getOrPut(projectId) { CompletableDeferred() }.complete(deliverables)
        }
    }
}
