package com.harnessapk.ui.wiki

import com.harnessapk.wiki.WikiContentUnavailableException
import com.harnessapk.wiki.WikiDocument
import com.harnessapk.wiki.WikiSection
import com.harnessapk.wiki.WikiSourceHit

data class WikiBreadcrumb(
    val id: String,
    val title: String,
)

data class WikiSearchGroup(
    val documentId: String,
    val documentTitle: String,
    val sectionId: String,
    val sectionPath: String,
    val hits: List<WikiSourceHit>,
)

internal fun wikiBreadcrumbs(
    document: WikiDocument?,
    sectionTrail: List<WikiSection>,
): List<WikiBreadcrumb> = buildList {
    document?.let { add(WikiBreadcrumb(it.id, it.title)) }
    sectionTrail.forEach { section -> add(WikiBreadcrumb(section.id, section.title)) }
}

internal fun groupWikiSearchHits(
    hits: List<WikiSourceHit>,
    documentsById: Map<String, WikiDocument>,
    sectionsById: Map<String, WikiSection>,
): List<WikiSearchGroup> {
    data class GroupKey(
        val documentId: String,
        val documentTitle: String,
        val sectionId: String,
        val sectionPath: String,
    )

    val grouped = linkedMapOf<GroupKey, MutableList<WikiSourceHit>>()
    hits.forEach { hit ->
        val section = sectionsById[hit.chunk.sectionId]
        val document = section?.documentId?.let(documentsById::get)
        val key = GroupKey(
            documentId = document?.id ?: "unknown-document",
            documentTitle = document?.title ?: "未分类资料",
            sectionId = section?.id ?: hit.chunk.sectionId,
            sectionPath = section?.path?.ifBlank { section.title } ?: "未标注章节",
        )
        grouped.getOrPut(key, ::mutableListOf) += hit
    }
    return grouped.map { (key, groupedHits) ->
        WikiSearchGroup(
            documentId = key.documentId,
            documentTitle = key.documentTitle,
            sectionId = key.sectionId,
            sectionPath = key.sectionPath,
            hits = groupedHits,
        )
    }
}

internal fun wikiBrowserErrorMessage(error: Throwable): String = when (error) {
    is WikiContentUnavailableException -> "该 Wiki 版本已不可用，请返回知识库重新选择。"
    else -> "无法读取该 Wiki 内容，请返回知识库后重试。"
}
