package com.harnessapk.projectsearch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRetrievalGoldenTest {
    @Test
    fun `forty query golden set has zero leaks and zero no match injection`() {
        val fixture = loadFixture()
        assertEquals(40, fixture.queries.size)
        assertEquals(
            mapOf("decision" to 10, "status" to 10, "path_title" to 8, "history_message" to 6, "no_match" to 6),
            fixture.queries.groupingBy(GoldenQuery::category).eachCount(),
        )

        // Deliberately return the whole corpus. The repository still has to fail closed
        // if a storage implementation ever violates its SQL project-scope contract.
        val repository = ProjectRetrievalRepository(
            ProjectSearchCandidateSource { _, _, limit -> fixture.documents.asReversed().take(limit) },
        )
        var expectedCount = 0
        var recalledCount = 0
        var leakCount = 0
        var noMatchInjectionCount = 0
        var unexpectedInjectionCount = 0

        fixture.queries.forEach { query ->
            val first = repository.retrieve(query.projectId, query.query)
            val second = repository.retrieve(query.projectId, query.query)
            assertEquals("${query.id} must be repeatable", first, second)
            val retrievedKeys = first.evidence.map(ProjectSearchDocument::documentKey).toSet()
            expectedCount += query.expectedKeys.size
            recalledCount += query.expectedKeys.count(retrievedKeys::contains)
            val unexpected = retrievedKeys - query.expectedKeys.toSet()
            if (unexpected.isNotEmpty()) {
                println("M3_RETRIEVAL_UNEXPECTED id=${query.id} keys=${unexpected.sorted()}")
            }
            unexpectedInjectionCount += unexpected.size
            leakCount += first.evidence.count { it.projectId != query.projectId }
            if (query.noMatch) {
                noMatchInjectionCount += first.evidence.size
                assertEquals("${query.id} must be NO_MATCH", ProjectRetrievalStatus.NO_MATCH, first.status)
            }
        }

        val recallAt6 = recalledCount.toDouble() / expectedCount
        println(
            "M3_RETRIEVAL_GOLDEN queries=${fixture.queries.size} " +
                "recallAt6=${"%.3f".format(recallAt6)} leaks=$leakCount " +
                "noMatchInjection=$noMatchInjectionCount unexpectedInjection=$unexpectedInjectionCount",
        )
        assertEquals("cross-project leak count", 0, leakCount)
        assertEquals("No Match injection count", 0, noMatchInjectionCount)
        assertEquals("retrieval must stay inside each query's allowed source set", 0, unexpectedInjectionCount)
        assertTrue("Recall@6 must cover every allowed golden source: $recallAt6", recallAt6 == 1.0)
    }

    private fun loadFixture(): GoldenFixture {
        val raw = requireNotNull(javaClass.getResourceAsStream("/projectsearch/m3-retrieval-golden.json"))
            .bufferedReader()
            .use { it.readText() }
        val root = Json.parseToJsonElement(raw).jsonObject
        val documents = root.getValue("documents").jsonArray.mapIndexed { index, element ->
            val value = element.jsonObject
            val key = value.getValue("key").jsonPrimitive.content
            val projectId = value.getValue("projectId").jsonPrimitive.content
            val type = ProjectSourceType.valueOf(value.getValue("type").jsonPrimitive.content)
            val authority = ProjectSourceAuthority.valueOf(value.getValue("authority").jsonPrimitive.content)
            val path = value["path"]?.jsonPrimitive?.contentOrNull
            val title = value.getValue("title").jsonPrimitive.content
            val text = value.getValue("text").jsonPrimitive.content
            ProjectSearchDocument(
                documentKey = key,
                projectId = projectId,
                sourceType = type,
                authority = authority,
                sourceKey = key,
                conversationId = null,
                messageId = null,
                relativePath = path,
                title = title,
                headingPath = title,
                ordinal = 0,
                text = text,
                searchableText = listOfNotNull(path, title, text).joinToString(" "),
                sourceSha256 = key.padEnd(64, '0'),
                gitBlobId = null,
                sourceUpdatedAt = index.toLong(),
                indexedAt = index.toLong(),
                score = value["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        }
        val queries = root.getValue("queries").jsonArray.map { element ->
            val value = element.jsonObject
            GoldenQuery(
                id = value.getValue("id").jsonPrimitive.content,
                category = value.getValue("category").jsonPrimitive.content,
                projectId = value.getValue("projectId").jsonPrimitive.content,
                query = value.getValue("query").jsonPrimitive.content,
                expectedKeys = value.getValue("expectedKeys").jsonArray.map { it.jsonPrimitive.content },
                noMatch = value.getValue("noMatch").jsonPrimitive.boolean,
            )
        }
        return GoldenFixture(documents, queries)
    }

    private data class GoldenFixture(
        val documents: List<ProjectSearchDocument>,
        val queries: List<GoldenQuery>,
    )

    private data class GoldenQuery(
        val id: String,
        val category: String,
        val projectId: String,
        val query: String,
        val expectedKeys: List<String>,
        val noMatch: Boolean,
    )
}
