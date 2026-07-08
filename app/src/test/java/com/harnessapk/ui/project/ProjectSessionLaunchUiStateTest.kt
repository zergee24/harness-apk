package com.harnessapk.ui.project

import com.harnessapk.chat.Conversation
import com.harnessapk.project.ProjectArtifactType
import com.harnessapk.project.DeliverableTemplate
import com.harnessapk.project.ProjectDeliverable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectSessionLaunchUiStateTest {
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
}
