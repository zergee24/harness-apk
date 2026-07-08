package com.harnessapk.project

import com.harnessapk.common.TimeProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileProjectRepository(
    rootDirectory: File,
    private val timeProvider: TimeProvider,
) {
    private val projectsRoot = rootDirectory.resolve("projects")

    suspend fun createProject(name: String): Project {
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "项目名称不能为空" }

        val projectId = uniqueProjectId(trimmedName)
        val root = projectsRoot.resolve(projectId)
        root.mkdirs()
        markdownDirectories.forEach { root.resolve(it).mkdirs() }
        root.resolve("files").mkdirs()

        writeIfAbsent(root.resolve("README.md"), "# $trimmedName\n\n")
        writeIfAbsent(
            root.resolve("context.md"),
            """
            # $trimmedName 上下文

            ## 项目目标

            ## 关键决策

            ## 当前状态

            ## 待跟进

            """.trimIndent() + "\n",
        )

        return Project(
            id = projectId,
            name = trimmedName,
            rootDirectory = root,
            updatedAt = timeProvider.nowMillis(),
        )
    }

    suspend fun createProjectFromPreparedDirectory(
        name: String,
        prepareDirectory: (File) -> Unit,
    ): Project {
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "项目名称不能为空" }

        val projectId = uniqueProjectId(trimmedName)
        val root = projectsRoot.resolve(projectId)
        root.mkdirs()
        try {
            prepareDirectory(root)
            writeLocalProjectName(root, trimmedName)
            excludeLocalHarnessMetadata(root)
            root.setLastModified(timeProvider.nowMillis())
            return projectFromDirectory(root)
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    suspend fun listProjects(): List<Project> {
        return projectsRoot
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { projectFromDirectory(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    suspend fun deleteProject(projectId: String) {
        val project = projectDirectory(projectId)
        if (!project.deleteRecursively()) {
            throw ProjectWorkspaceException("删除项目失败：$projectId")
        }
    }

    suspend fun readProjectContext(projectId: String): String {
        val project = projectDirectory(projectId)
        val trackedContext = project.resolve("context.md")
        if (trackedContext.isFile) return trackedContext.readText()
        val localContext = localHarnessDirectory(project).resolve("context.md")
        return localContext.takeIf { it.isFile }?.readText().orEmpty()
    }

    suspend fun updateProjectContext(projectId: String, markdown: String) {
        val project = projectDirectory(projectId)
        val contextFile = project.resolve("context.md").takeIf { it.isFile }
            ?: localHarnessDirectory(project).resolve("context.md")
        contextFile.parentFile?.mkdirs()
        contextFile.writeText(markdown)
        excludeLocalHarnessMetadata(project)
    }

    suspend fun createDeliverable(
        projectId: String,
        template: DeliverableTemplate,
        title: String,
        markdown: String? = null,
    ): ProjectDeliverable {
        val project = projectDirectory(projectId)
        val trimmedTitle = title.trim()
        require(trimmedTitle.isNotBlank()) { "文档标题不能为空" }

        val relativePath = when (template) {
            DeliverableTemplate.CONTEXT -> "context.md"
            else -> "${template.directoryName}/${safeFileName(trimmedTitle)}.md"
        }
        val file = checkedProjectFile(project, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(markdown ?: defaultMarkdown(template, trimmedTitle))
        return deliverableFromFile(project, file, template)
    }

    suspend fun listDeliverables(projectId: String): List<ProjectDeliverable> {
        val project = projectDirectory(projectId)
        val files = project
            .walkTopDown()
            .filter { it.isFile && shouldListProjectFile(project, it) }
            .toList()

        return files
            .map { deliverableFromFile(project, it, templateFor(project, it)) }
            .sortedWith(compareBy<ProjectDeliverable> { it.artifactType.ordinal }.thenBy { it.relativePath })
    }

    suspend fun readDeliverable(projectId: String, deliverableId: String): String {
        val project = projectDirectory(projectId)
        return checkedProjectFile(project, deliverableId).readText()
    }

    fun resolveDeliverableFile(projectId: String, deliverableId: String): File {
        val project = projectDirectory(projectId)
        return checkedProjectFile(project, deliverableId)
    }

    fun resolveProjectDirectory(projectId: String): File = projectDirectory(projectId)

    suspend fun writeDeliverable(projectId: String, deliverableId: String, markdown: String) {
        val project = projectDirectory(projectId)
        val file = checkedProjectFile(project, deliverableId)
        file.parentFile?.mkdirs()
        file.writeText(markdown)
    }

    suspend fun writeMarkdownFile(
        projectId: String,
        relativePath: String,
        markdown: String,
    ): ProjectDeliverable {
        val project = projectDirectory(projectId)
        val normalizedPath = relativePath.trim().replace('\\', '/').trim('/')
        require(normalizedPath.isNotBlank()) { "Markdown 路径不能为空" }
        require(normalizedPath.endsWith(".md", ignoreCase = true)) { "只能写入 Markdown 文件" }
        val file = checkedProjectFile(project, normalizedPath)
        file.parentFile?.mkdirs()
        file.writeText(markdown)
        return deliverableFromFile(project, file, templateFor(project, file))
    }

    suspend fun writePdfExport(
        projectId: String,
        sourceDeliverableId: String,
        writePdf: (OutputStream) -> Unit,
    ): ProjectDeliverable {
        val project = projectDirectory(projectId)
        val normalizedPath = sourceDeliverableId.trim().replace('\\', '/').trim('/')
        require(normalizedPath.isNotBlank()) { "Markdown 路径不能为空" }
        require(normalizedPath.endsWith(".md", ignoreCase = true)) { "只能从 Markdown 导出 PDF" }
        val sourceFile = checkedProjectFile(project, normalizedPath)
        require(sourceFile.isFile) { "未找到 Markdown 文件：$normalizedPath" }

        val basePath = normalizedPath.substringBeforeLast('.', normalizedPath)
        val pdfPath = uniquePdfExportPath(project, basePath)
        val pdfFile = checkedProjectFile(project, pdfPath)
        pdfFile.parentFile?.mkdirs()
        pdfFile.outputStream().use(writePdf)
        return deliverableFromFile(project, pdfFile, templateFor(project, pdfFile))
    }

    suspend fun searchDeliverables(projectId: String, query: String): List<ProjectDeliverable> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()
        val project = projectDirectory(projectId)
        return listDeliverables(projectId).filter { deliverable ->
            val file = checkedProjectFile(project, deliverable.id)
            deliverable.title.contains(normalizedQuery, ignoreCase = true) ||
                deliverable.relativePath.contains(normalizedQuery, ignoreCase = true) ||
                (
                    deliverable.artifactType.isTextPreviewable &&
                        file.readText().contains(normalizedQuery, ignoreCase = true)
                    )
        }
    }

    suspend fun saveSessionSummary(
        projectId: String,
        summary: ProjectSessionSummary,
    ): ProjectDeliverable {
        val project = projectDirectory(projectId)
        val title = summary.title.trim().ifBlank { "会话摘要" }
        val fileName = safeFileName("${summary.id}-$title")
        val relativePath = "sessions/$fileName.md"
        val file = checkedProjectFile(project, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(summary.markdown)
        return deliverableFromFile(project, file, DeliverableTemplate.SESSION)
    }

    suspend fun exportProjectZip(projectId: String, outputStream: OutputStream) {
        val project = projectDirectory(projectId)
        val root = project.canonicalFile
        val files = project
            .walkTopDown()
            .filter { it.isFile && shouldExportProjectFile(root, it) }
            .sortedBy { relativeProjectPath(root, it) }
            .toList()

        ZipOutputStream(outputStream.buffered()).use { zip ->
            files.forEach { file ->
                val relativePath = relativeProjectPath(root, file)
                val entry = ZipEntry(relativePath).apply {
                    time = file.lastModified()
                }
                zip.putNextEntry(entry)
                file.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
    }

    suspend fun importProjectZip(inputStream: InputStream): Project {
        projectsRoot.mkdirs()
        val tempRoot = projectsRoot.resolve(".import-${UUID.randomUUID()}")
        tempRoot.mkdirs()

        try {
            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val relativePath = normalizedZipEntryPath(entry.name)
                    if (relativePath.isNotBlank() && !hasHiddenPathSegment(relativePath)) {
                        val target = checkedImportFile(tempRoot, relativePath)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { output ->
                                zip.copyTo(output)
                            }
                            if (entry.time > 0) target.setLastModified(entry.time)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val contentRoot = normalizedImportedProjectRoot(tempRoot)
            val projectName = importedProjectName(contentRoot, tempRoot)
            val projectId = uniqueProjectId(projectName)
            val finalRoot = projectsRoot.resolve(projectId)
            moveImportedProject(contentRoot, tempRoot, finalRoot)
            ensureProjectScaffold(finalRoot, projectName)
            finalRoot.setLastModified(timeProvider.nowMillis())
            return projectFromDirectory(finalRoot)
        } catch (error: Throwable) {
            tempRoot.deleteRecursively()
            throw error
        }
    }

    private fun projectDirectory(projectId: String): File {
        val project = projectsRoot.resolve(projectId)
        if (!project.isDirectory) {
            throw ProjectWorkspaceException("未找到项目：$projectId")
        }
        return project
    }

    private fun projectFromDirectory(directory: File): Project {
        val name = projectNameFromDirectory(directory) ?: directory.name
        return Project(
            id = directory.name,
            name = name,
            rootDirectory = directory,
            updatedAt = directory.lastModified(),
        )
    }

    private fun projectNameFromDirectory(directory: File): String? =
        localHarnessDirectory(directory)
            .resolve("name")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: directory
            .resolve("README.md")
            .takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun checkedProjectFile(project: File, relativePath: String): File {
        val root = project.canonicalFile
        val file = project.resolve(relativePath).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw ProjectWorkspaceException("文件不在项目目录内：$relativePath")
        }
        return file
    }

    private fun checkedImportFile(project: File, relativePath: String): File {
        val root = project.canonicalFile
        val file = project.resolve(relativePath).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw ProjectWorkspaceException("不安全的项目包路径：$relativePath")
        }
        return file
    }

    private fun deliverableFromFile(
        project: File,
        file: File,
        template: DeliverableTemplate,
    ): ProjectDeliverable {
        val relativePath = file.canonicalFile.relativeTo(project.canonicalFile).invariantSeparatorsPath
        val artifactType = projectArtifactTypeForPath(relativePath)
        return ProjectDeliverable(
            id = relativePath,
            title = titleFromFile(file, artifactType),
            relativePath = relativePath,
            template = template,
            updatedAt = file.lastModified(),
            artifactType = artifactType,
        )
    }

    private fun titleFromFile(file: File, artifactType: ProjectArtifactType): String =
        if (artifactType.rendersAsMarkdown) {
            titleFromMarkdown(file) ?: file.nameWithoutExtension
        } else {
            file.nameWithoutExtension
        }

    private fun titleFromMarkdown(file: File): String? =
        file.readLines()
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun templateFor(project: File, file: File): DeliverableTemplate {
        val relativePath = file.canonicalFile.relativeTo(project.canonicalFile).invariantSeparatorsPath
        return when {
            relativePath == "context.md" -> DeliverableTemplate.CONTEXT
            relativePath.startsWith("requirements/") -> DeliverableTemplate.REQUIREMENT
            relativePath.startsWith("solutions/") -> DeliverableTemplate.SOLUTION
            relativePath.startsWith("research/") -> DeliverableTemplate.RESEARCH
            relativePath.startsWith("todos/") -> DeliverableTemplate.TODO
            relativePath.startsWith("reports/") -> DeliverableTemplate.REPORT
            relativePath.startsWith("retrospectives/") -> DeliverableTemplate.RETROSPECTIVE
            relativePath.startsWith("sessions/") -> DeliverableTemplate.SESSION
            else -> DeliverableTemplate.RESEARCH
        }
    }

    private fun shouldListProjectFile(project: File, file: File): Boolean {
        val relativePath = file.canonicalFile.relativeTo(project.canonicalFile).invariantSeparatorsPath
        if (relativePath == "README.md") return false
        return relativePath.split('/').none { it.startsWith(".") }
    }

    private fun shouldExportProjectFile(projectRoot: File, file: File): Boolean {
        val relativePath = runCatching { relativeProjectPath(projectRoot, file) }.getOrNull() ?: return false
        return relativePath.isNotBlank() && !hasHiddenPathSegment(relativePath)
    }

    private fun relativeProjectPath(projectRoot: File, file: File): String {
        val root = projectRoot.canonicalFile
        val canonicalFile = file.canonicalFile
        if (canonicalFile != root && !canonicalFile.path.startsWith(root.path + File.separator)) {
            throw ProjectWorkspaceException("文件不在项目目录内：${file.path}")
        }
        return canonicalFile.relativeTo(root).invariantSeparatorsPath
    }

    private fun uniquePdfExportPath(project: File, basePath: String): String {
        var candidate = "$basePath.pdf"
        var counter = 2
        while (checkedProjectFile(project, candidate).exists()) {
            candidate = "$basePath-$counter.pdf"
            counter += 1
        }
        return candidate
    }

    private fun normalizedZipEntryPath(name: String): String {
        val slashPath = name.replace('\\', '/')
        if (slashPath.startsWith("/") || slashPath.contains(":")) {
            throw ProjectWorkspaceException("不安全的项目包路径：$name")
        }
        val normalizedPath = slashPath.trim('/')
        if (normalizedPath.isBlank()) return ""

        val pathParts = normalizedPath.split('/')
        if (pathParts.any { it.isBlank() || it == "." || it == ".." }) {
            throw ProjectWorkspaceException("不安全的项目包路径：$name")
        }
        return pathParts.joinToString("/")
    }

    private fun hasHiddenPathSegment(relativePath: String): Boolean =
        relativePath.split('/').any { it.startsWith(".") }

    private fun normalizedImportedProjectRoot(tempRoot: File): File {
        if (tempRoot.resolve("README.md").isFile || tempRoot.resolve("context.md").isFile) return tempRoot
        val visibleChildren = tempRoot.listFiles()?.filterNot { it.name.startsWith(".") }.orEmpty()
        return visibleChildren.singleOrNull()?.takeIf { it.isDirectory } ?: tempRoot
    }

    private fun importedProjectName(contentRoot: File, tempRoot: File): String =
        projectNameFromDirectory(contentRoot)
            ?: contentRoot.takeIf { it != tempRoot }?.name
            ?: "导入项目"

    private fun moveImportedProject(contentRoot: File, tempRoot: File, finalRoot: File) {
        finalRoot.parentFile?.mkdirs()
        if (contentRoot == tempRoot && tempRoot.renameTo(finalRoot)) return

        contentRoot.copyRecursively(finalRoot, overwrite = false)
        tempRoot.deleteRecursively()
    }

    private fun ensureProjectScaffold(project: File, projectName: String) {
        markdownDirectories.forEach { project.resolve(it).mkdirs() }
        project.resolve("files").mkdirs()
        writeLocalProjectName(project, projectName)
        writeIfAbsent(project.resolve("README.md"), "# $projectName\n\n")
        writeIfAbsent(
            project.resolve("context.md"),
            """
            # $projectName 上下文

            ## 项目目标

            ## 关键决策

            ## 当前状态

            ## 待跟进

            """.trimIndent() + "\n",
        )
    }

    private fun writeLocalProjectName(project: File, projectName: String) {
        val nameFile = localHarnessDirectory(project).resolve("name")
        nameFile.parentFile?.mkdirs()
        nameFile.writeText(projectName.trim())
    }

    private fun excludeLocalHarnessMetadata(project: File) {
        val excludeFile = project.resolve(".git/info/exclude")
        if (!excludeFile.isFile) return
        val current = excludeFile.readText()
        if (current.lineSequence().map { it.trim() }.any { it == ".harness/" }) return
        excludeFile.appendText("\n.harness/\n")
    }

    private fun localHarnessDirectory(project: File): File = project.resolve(".harness")

    private fun defaultMarkdown(template: DeliverableTemplate, title: String): String = when (template) {
        DeliverableTemplate.REQUIREMENT -> "# $title\n\n## 背景\n\n## 目标\n\n## 范围\n\n## 验收标准\n\n"
        DeliverableTemplate.SOLUTION -> "# $title\n\n## 目标\n\n## 方案\n\n## 风险\n\n## 下一步\n\n"
        DeliverableTemplate.RESEARCH -> "# $title\n\n## 问题\n\n## 发现\n\n## 结论\n\n"
        DeliverableTemplate.TODO -> "# $title\n\n- [ ] 待补充\n\n"
        DeliverableTemplate.REPORT -> "# $title\n\n## 结论\n\n## 证据\n\n## 后续动作\n\n"
        DeliverableTemplate.RETROSPECTIVE -> "# $title\n\n## 做得好的\n\n## 问题\n\n## 改进\n\n"
        DeliverableTemplate.CONTEXT -> "# $title\n\n## 项目目标\n\n## 关键决策\n\n## 当前状态\n\n## 待跟进\n\n"
        DeliverableTemplate.SESSION -> "# $title\n\n"
    }

    private fun uniqueProjectId(name: String): String {
        val base = safeFileName(name).ifBlank { UUID.randomUUID().toString() }
        var candidate = base
        var counter = 2
        while (projectsRoot.resolve(candidate).exists()) {
            candidate = "$base-$counter"
            counter += 1
        }
        return candidate
    }

    private fun safeFileName(value: String): String {
        val normalized = value
            .lowercase(Locale.ROOT)
            .map { char ->
                when {
                    char.isLetterOrDigit() -> char
                    char == '-' || char == '_' -> char
                    else -> '-'
                }
            }
            .joinToString("")
            .trim('-')
        return normalized.ifBlank { UUID.randomUUID().toString() }
    }

    private fun writeIfAbsent(file: File, content: String) {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText(content)
    }

    private companion object {
        val markdownDirectories = listOf(
            "requirements",
            "solutions",
            "research",
            "todos",
            "reports",
            "retrospectives",
            "sessions",
        )
    }
}
