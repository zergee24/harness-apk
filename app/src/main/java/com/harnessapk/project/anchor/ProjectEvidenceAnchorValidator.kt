package com.harnessapk.project.anchor

import java.util.Locale

object ProjectEvidenceAnchorValidator {

    const val EXCERPT_MAX_LENGTH = 500
    private val forbiddenSubstrings = listOf("http://", "https://", "/Users/")

    fun validate(anchor: ProjectEvidenceAnchor): List<String> {
        val errors = mutableListOf<String>()

        if (anchor.projectId.isBlank()) errors += "projectId 不能为空"
        validateContentHash(anchor.contentHash, errors)
        validateForbiddenContent(anchor, errors)
        validateExcerpt(anchor.excerpt, errors)
        validateTypeRequirements(anchor, errors)
        return errors
    }

    fun requireValid(anchor: ProjectEvidenceAnchor) {
        val errors = validate(anchor)
        require(errors.isEmpty()) { "锚点校验失败：${errors.joinToString("；")}" }
    }

    private fun validateContentHash(contentHash: String, errors: MutableList<String>) {
        if (!contentHash.isSha256Hex()) {
            errors += "contentHash 必须是 64 位十六进制 SHA-256"
        }
    }

    private fun validateForbiddenContent(anchor: ProjectEvidenceAnchor, errors: MutableList<String>) {
        val texts = listOfNotNull(anchor.relativePath, anchor.gitRef, anchor.excerpt)
        texts.forEach { text ->
            forbiddenSubstrings.firstOrNull { text.contains(it, ignoreCase = true) }?.let { forbidden ->
                errors += "禁止保存包含 $forbidden 的内容（凭证 URL / Mac 用户目录前缀）"
            }
        }
    }

    private fun validateExcerpt(excerpt: String?, errors: MutableList<String>) {
        if (excerpt != null && excerpt.length > EXCERPT_MAX_LENGTH) {
            errors += "excerpt 不得超过 $EXCERPT_MAX_LENGTH 字符"
        }
    }

    private fun validateTypeRequirements(anchor: ProjectEvidenceAnchor, errors: MutableList<String>) {
        if (anchor.relativePath.isNullOrBlank() && anchor.type in setOf(
                AnchorType.PROJECT_FILE,
                AnchorType.FILE_RANGE,
                AnchorType.DIFF,
                AnchorType.TEST_EVIDENCE,
            )
        ) {
            errors += "${anchor.type} 需要项目相对路径"
        }
        if (anchor.gitRef.isNullOrBlank() && anchor.type in setOf(AnchorType.COMMIT, AnchorType.DIFF)) {
            errors += "${anchor.type} 需要 gitRef（commit/blob）"
        }
        if (anchor.runSnapshotId.isNullOrBlank() && anchor.type in setOf(
                AnchorType.RUN_EVENT,
                AnchorType.RUN_COMPLETION,
            )
        ) {
            errors += "${anchor.type} 需要 runSnapshotId"
        }
        val start = anchor.startLine
        val end = anchor.endLine
        if (anchor.type == AnchorType.FILE_RANGE) {
            if (start == null || end == null || start < 1 || end < start) {
                errors += "FILE_RANGE 需要 1 <= startLine <= endLine"
            }
        } else if (start != null || end != null) {
            errors += "仅 FILE_RANGE 允许行区间"
        }
    }
}

internal fun String.isSha256Hex(): Boolean =
    length == 64 && lowercase(Locale.ROOT).all { it in '0'..'9' || it in 'a'..'f' }
