package com.harnessapk.ui.chat

import com.harnessapk.session.CreatedDeliverable
import com.harnessapk.session.MarkdownBatchApplyResult
import com.harnessapk.session.MarkdownFileApplyResult
import com.harnessapk.session.MarkdownFileApplyStatus
import com.harnessapk.session.MarkdownUpdateOperation
import com.harnessapk.session.MarkdownUpdateProposal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownWriteBackLinksTest {
    @Test
    fun linksOnlySuccessfulNewMarkdownFilesOnce() = runTest {
        val linked = mutableListOf<String>()

        persistMarkdownWriteBackLinks(
            result = MarkdownBatchApplyResult(
                results = listOf(
                    result(MarkdownUpdateOperation.CREATE, "notes/created.md"),
                    result(MarkdownUpdateOperation.UPDATE, "notes/existing.md"),
                    result(MarkdownUpdateOperation.CREATE, "notes/created.md"),
                    failedResult(MarkdownUpdateOperation.CREATE, "notes/failed.md"),
                ),
            ),
            linkMarkdown = linked::add,
        )

        assertEquals(listOf("notes/created.md"), linked)
    }

    private fun result(operation: MarkdownUpdateOperation, path: String): MarkdownFileApplyResult =
        MarkdownFileApplyResult(
            proposal = proposal(operation, path),
            status = MarkdownFileApplyStatus.SUCCEEDED,
            writtenDeliverable = CreatedDeliverable(id = path, title = path, path = path),
        )

    private fun failedResult(operation: MarkdownUpdateOperation, path: String): MarkdownFileApplyResult =
        MarkdownFileApplyResult(
            proposal = proposal(operation, path),
            status = MarkdownFileApplyStatus.FAILED,
            errorMessage = "写入失败",
        )

    private fun proposal(operation: MarkdownUpdateOperation, path: String): MarkdownUpdateProposal =
        MarkdownUpdateProposal(
            operation = operation,
            path = path,
            title = path,
            reason = "测试",
            markdown = "# 测试",
        )
}
