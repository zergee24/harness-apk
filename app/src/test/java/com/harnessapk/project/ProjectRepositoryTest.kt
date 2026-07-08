package com.harnessapk.project

import com.harnessapk.common.TimeProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createProjectCreatesMarkdownWorkspace() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 100L },
        )

        val project = repository.createProject("移动端 Harness")

        assertEquals("移动端 Harness", project.name)
        assertTrue(project.rootDirectory.resolve("README.md").isFile)
        assertTrue(project.rootDirectory.resolve("context.md").isFile)
        listOf("requirements", "solutions", "research", "todos", "reports", "retrospectives", "sessions")
            .forEach { directory ->
                assertTrue(project.rootDirectory.resolve(directory).isDirectory)
            }
        assertTrue(repository.readProjectContext(project.id).contains("移动端 Harness"))
    }

    @Test
    fun manageDeliverablesSearchAndSessionSummaries() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 200L },
        )
        val project = repository.createProject("移动端 Harness")

        val deliverable = repository.createDeliverable(
            projectId = project.id,
            template = DeliverableTemplate.REQUIREMENT,
            title = "项目模式 PRD",
        )

        assertEquals(DeliverableTemplate.REQUIREMENT, deliverable.template)
        assertTrue(deliverable.relativePath.startsWith("requirements/"))
        assertTrue(repository.readDeliverable(project.id, deliverable.id).contains("项目模式 PRD"))

        repository.writeDeliverable(
            projectId = project.id,
            deliverableId = deliverable.id,
            markdown = "# 项目模式 PRD\n\nMarkdown-first 项目空间。",
        )

        assertTrue(repository.readDeliverable(project.id, deliverable.id).contains("Markdown-first 项目空间"))
        assertEquals(listOf(deliverable.id), repository.searchDeliverables(project.id, "Markdown-first").map { it.id })
        assertTrue(repository.listDeliverables(project.id).any { it.id == deliverable.id })

        val session = repository.saveSessionSummary(
            projectId = project.id,
            summary = ProjectSessionSummary(
                id = "session-1",
                title = "会话写回",
                markdown = "# 会话写回\n\n建议补充导出。",
            ),
        )

        assertEquals(DeliverableTemplate.SESSION, session.template)
        assertTrue(session.relativePath.startsWith("sessions/"))
        assertTrue(session.relativePath.startsWith("sessions/session-1-"))
        assertTrue(repository.readDeliverable(project.id, session.id).contains("建议补充导出"))
    }

    @Test
    fun writeMarkdownFileCreatesAndUpdatesRelativeMarkdownInsideProject() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 300L },
        )
        val project = repository.createProject("移动端 Harness")

        val created = repository.writeMarkdownFile(
            projectId = project.id,
            relativePath = "sessions/review.md",
            markdown = "# 会话沉淀\n\n第一版。",
        )
        val updated = repository.writeMarkdownFile(
            projectId = project.id,
            relativePath = "sessions/review.md",
            markdown = "# 会话沉淀\n\n第二版。",
        )

        assertEquals("sessions/review.md", created.relativePath)
        assertEquals(created.id, updated.id)
        assertTrue(repository.readDeliverable(project.id, updated.id).contains("第二版"))
    }

    @Test
    fun writePdfExportCreatesPdfNextToMarkdownWithoutOverwritingExistingPdf() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 325L },
        )
        val project = repository.createProject("移动端 Harness")
        repository.writeMarkdownFile(
            projectId = project.id,
            relativePath = "sessions/review.md",
            markdown = "# Review",
        )

        val first = repository.writePdfExport(
            projectId = project.id,
            sourceDeliverableId = "sessions/review.md",
        ) { output ->
            output.write(byteArrayOf(1, 2, 3))
        }
        val second = repository.writePdfExport(
            projectId = project.id,
            sourceDeliverableId = "sessions/review.md",
        ) { output ->
            output.write(byteArrayOf(4, 5, 6))
        }

        assertEquals("sessions/review.pdf", first.relativePath)
        assertEquals(ProjectArtifactType.PDF, first.artifactType)
        assertEquals("sessions/review-2.pdf", second.relativePath)
        assertEquals(ProjectArtifactType.PDF, second.artifactType)
        assertTrue(
            repository.listDeliverables(project.id).map { it.relativePath }.containsAll(
                listOf("sessions/review.md", "sessions/review.pdf", "sessions/review-2.pdf"),
            ),
        )
    }

    @Test
    fun deleteProjectRemovesWorkspaceAndProjectListEntry() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 350L },
        )
        val project = repository.createProject("移动端 Harness")
        repository.writeMarkdownFile(
            projectId = project.id,
            relativePath = "sessions/review.md",
            markdown = "# 会话沉淀\n\n第一版。",
        )

        repository.deleteProject(project.id)

        assertFalse(project.rootDirectory.exists())
        assertEquals(emptyList<Project>(), repository.listProjects())
    }

    @Test
    fun createProjectFromPreparedDirectoryUsesLocalMetadataNameAndHidesHarnessFiles() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 375L },
        )

        val project = repository.createProjectFromPreparedDirectory("真实代码仓库") { directory ->
            directory.resolve("src/App.kt").writeProjectText("fun main() = Unit")
        }

        assertEquals("真实代码仓库", project.name)
        assertEquals(
            listOf("src/App.kt"),
            repository.listDeliverables(project.id).map { it.relativePath },
        )
    }

    @Test
    fun listDeliverablesDiscoversCommonArtifactTypes() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 400L },
        )
        val project = repository.createProject("移动端 Harness")

        project.rootDirectory.resolve("notes/brief.md").writeProjectText("# Brief\n\n项目背景")
        project.rootDirectory.resolve("docs/spec.docx").writeProjectBytes("word".toByteArray())
        project.rootDirectory.resolve("sheets/budget.xlsx").writeProjectBytes("excel".toByteArray())
        project.rootDirectory.resolve("slides/roadmap.pptx").writeProjectBytes("ppt".toByteArray())
        project.rootDirectory.resolve("exports/report.pdf").writeProjectBytes("%PDF".toByteArray())
        project.rootDirectory.resolve("src/App.kt").writeProjectText("fun main() = Unit")
        project.rootDirectory.resolve("images/wireframe.png").writeProjectBytes("png".toByteArray())

        val artifactsByPath = repository.listDeliverables(project.id).associateBy { it.relativePath }

        assertEquals(ProjectArtifactType.MARKDOWN, artifactsByPath.getValue("notes/brief.md").artifactType)
        assertEquals(ProjectArtifactType.DOCUMENT, artifactsByPath.getValue("docs/spec.docx").artifactType)
        assertEquals(ProjectArtifactType.SPREADSHEET, artifactsByPath.getValue("sheets/budget.xlsx").artifactType)
        assertEquals(ProjectArtifactType.PRESENTATION, artifactsByPath.getValue("slides/roadmap.pptx").artifactType)
        assertEquals(ProjectArtifactType.PDF, artifactsByPath.getValue("exports/report.pdf").artifactType)
        assertEquals(ProjectArtifactType.CODE, artifactsByPath.getValue("src/App.kt").artifactType)
        assertEquals(ProjectArtifactType.IMAGE, artifactsByPath.getValue("images/wireframe.png").artifactType)
    }

    @Test
    fun searchDeliverablesMatchesBinaryArtifactsByPathWithoutReadingThemAsText() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 500L },
        )
        val project = repository.createProject("移动端 Harness")
        project.rootDirectory.resolve("exports/report.pdf").writeProjectBytes(byteArrayOf(0, 1, 2, 3))
        project.rootDirectory.resolve("notes/brief.md").writeProjectText("# Brief\n\n验收标准")

        assertEquals(
            listOf("exports/report.pdf"),
            repository.searchDeliverables(project.id, "report").map { it.relativePath },
        )
        assertEquals(
            listOf("notes/brief.md"),
            repository.searchDeliverables(project.id, "验收标准").map { it.relativePath },
        )
    }

    @Test
    fun exportProjectZipIncludesWorkspaceFilesAndImportRestoresThem() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.newFolder("source-root"),
            timeProvider = TimeProvider { 600L },
        )
        val project = repository.createProject("移动端 Harness")
        repository.writeMarkdownFile(
            projectId = project.id,
            relativePath = "sessions/review.md",
            markdown = "# 会话沉淀\n\n第一版。",
        )
        project.rootDirectory.resolve("files/raw.bin").writeProjectBytes(byteArrayOf(1, 2, 3))

        val exportedZip = ByteArrayOutputStream()
        repository.exportProjectZip(project.id, exportedZip)

        val importedRepository = FileProjectRepository(
            rootDirectory = temporaryFolder.newFolder("imported-root"),
            timeProvider = TimeProvider { 700L },
        )
        val importedProject = importedRepository.importProjectZip(
            ByteArrayInputStream(exportedZip.toByteArray()),
        )

        assertEquals("移动端 Harness", importedProject.name)
        assertTrue(importedRepository.readProjectContext(importedProject.id).contains("移动端 Harness"))
        assertTrue(
            importedRepository.readDeliverable(importedProject.id, "sessions/review.md")
                .contains("第一版"),
        )
        assertEquals(
            byteArrayOf(1, 2, 3).toList(),
            importedProject.rootDirectory.resolve("files/raw.bin").readBytes().toList(),
        )
        assertTrue(importedRepository.listDeliverables(importedProject.id).any { it.relativePath == "files/raw.bin" })
    }

    @Test
    fun importProjectZipRejectsEntriesOutsideProject() = runTest {
        val repository = FileProjectRepository(
            rootDirectory = temporaryFolder.root,
            timeProvider = TimeProvider { 800L },
        )
        val zipBytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("../escape.md"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
        }

        try {
            repository.importProjectZip(ByteArrayInputStream(zipBytes.toByteArray()))
            throw AssertionError("Expected ProjectWorkspaceException")
        } catch (error: ProjectWorkspaceException) {
            assertTrue(error.message.orEmpty().contains("不安全"))
        }
        assertTrue(!temporaryFolder.root.resolve("escape.md").exists())
    }

    private fun java.io.File.writeProjectText(text: String) {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun java.io.File.writeProjectBytes(bytes: ByteArray) {
        parentFile?.mkdirs()
        writeBytes(bytes)
    }
}
