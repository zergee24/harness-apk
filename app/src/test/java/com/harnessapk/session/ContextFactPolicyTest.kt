package com.harnessapk.session

import com.harnessapk.projectsearch.ProjectSourceAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextFactPolicyTest {
    private val policy = ContextFactPolicy()

    @Test
    fun `rejects context fact without evidence`() {
        val result = policy.evaluate(candidate(evidenceIds = emptyList()))
        assertFalse(result.accepted)
        assertEquals("缺少项目证据", result.reason)
    }

    @Test
    fun `assistant proposal cannot prove decision or completed status`() {
        val decision = policy.evaluate(
            candidate(authorities = setOf(ProjectSourceAuthority.ASSISTANT_PROPOSAL)),
        )
        assertFalse(decision.accepted)
        assertTrue(decision.reason.contains("助手提案"))
    }

    @Test
    fun `reviewed fact is accepted once then dismissed semantic key suppresses repeat`() {
        val fact = candidate(authorities = setOf(ProjectSourceAuthority.REVIEWED_ARTIFACT))
        assertTrue(policy.evaluate(fact).accepted)
        assertFalse(policy.evaluate(fact, suppressedSemanticKeys = setOf(fact.semanticKey)).accepted)
    }

    @Test
    fun `accept enriches candidate from allowed evidence with deterministic keys`() {
        val candidate = rawCandidate(evidenceIds = listOf("evidence-2", "evidence-1"))
        val evidence = listOf(
            ContextFactEvidence("evidence-1", ProjectSourceAuthority.USER_STATED, "a".repeat(64)),
            ContextFactEvidence("evidence-2", ProjectSourceAuthority.REVIEWED_ARTIFACT, "b".repeat(64)),
        )

        val first = policy.accept(listOf(candidate), evidence).single()
        val second = policy.accept(
            listOf(candidate.copy(evidenceIds = candidate.evidenceIds.reversed())),
            evidence.reversed(),
        ).single()

        assertEquals(setOf(ProjectSourceAuthority.USER_STATED, ProjectSourceAuthority.REVIEWED_ARTIFACT), first.evidenceAuthorities)
        assertEquals(first.evidenceHash, second.evidenceHash)
        assertEquals(first.semanticKey, second.semanticKey)
        assertTrue(first.evidenceHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first.semanticKey.startsWith("key_decisions:"))
    }

    @Test
    fun `dedupe key stays stable when a later run snapshots the same source under a new evidence id`() {
        val first = policy.accept(
            listOf(rawCandidate(listOf("run-1-evidence"))),
            listOf(ContextFactEvidence("run-1-evidence", ProjectSourceAuthority.USER_STATED, "a".repeat(64))),
        ).single()
        val laterRun = policy.accept(
            listOf(rawCandidate(listOf("run-2-evidence"))),
            listOf(ContextFactEvidence("run-2-evidence", ProjectSourceAuthority.USER_STATED, "a".repeat(64))),
        ).single()

        assertEquals(first.evidenceHash, laterRun.evidenceHash)
        assertEquals(first.semanticKey, laterRun.semanticKey)
    }

    @Test
    fun `accept rejects candidate when any referenced evidence is unavailable`() {
        val accepted = policy.accept(
            candidates = listOf(rawCandidate(evidenceIds = listOf("evidence-1", "missing"))),
            allowedEvidence = listOf(
                ContextFactEvidence("evidence-1", ProjectSourceAuthority.USER_STATED, "a".repeat(64)),
            ),
        )

        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `accept rejects assistant only decision and a previously suppressed key`() {
        val evidence = listOf(
            ContextFactEvidence("assistant", ProjectSourceAuthority.ASSISTANT_PROPOSAL, "c".repeat(64)),
        )
        assertTrue(policy.accept(listOf(rawCandidate(listOf("assistant"))), evidence).isEmpty())

        val reviewedEvidence = listOf(
            ContextFactEvidence("reviewed", ProjectSourceAuthority.REVIEWED_ARTIFACT, "d".repeat(64)),
        )
        val first = policy.accept(listOf(rawCandidate(listOf("reviewed"))), reviewedEvidence).single()
        assertTrue(
            policy.accept(
                listOf(rawCandidate(listOf("reviewed"))),
                reviewedEvidence,
                suppressedSemanticKeys = setOf(first.semanticKey),
            ).isEmpty(),
        )
    }

    private fun candidate(
        evidenceIds: List<String> = listOf("evidence-1"),
        authorities: Set<ProjectSourceAuthority> = setOf(ProjectSourceAuthority.USER_STATED),
    ) = ContextFactCandidate(
        section = ContextSection.KEY_DECISIONS,
        statement = "项目继续使用本地 FTS",
        evidenceIds = evidenceIds,
        evidenceAuthorities = authorities,
        operation = FactOperation.UPSERT,
        semanticKey = "key-decisions:项目继续使用本地fts",
        evidenceHash = "abc",
    )

    private fun rawCandidate(evidenceIds: List<String>) = ContextFactCandidate(
        section = ContextSection.KEY_DECISIONS,
        statement = " 继续使用本地   FTS ",
        evidenceIds = evidenceIds,
        evidenceAuthorities = emptySet(),
        operation = FactOperation.UPSERT,
        semanticKey = "",
        evidenceHash = "",
    )
}
