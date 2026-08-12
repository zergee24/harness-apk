package com.harnessapk.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCandidateTest {
    private val matching = WorkspaceCandidate(
        workspaceId = "workspace-match",
        displayName = "harness-apk",
        cwd = "/Users/tony/Documents/harness-apk",
        repositoryLabel = "github.com/zergee24/harness-apk",
        branch = "test",
        repositoryFingerprint = "fingerprint-match",
        lastUsedAt = 10L,
    )

    @Test
    fun projectNameMatchSortsBeforeNewerUnrelatedCandidate() {
        val newer = matching.copy(
            workspaceId = "workspace-newer",
            displayName = "unrelated",
            cwd = "/tmp/unrelated",
            repositoryLabel = "github.com/acme/unrelated",
            lastUsedAt = 100L,
        )

        assertEquals(matching, rankWorkspaceCandidates("Harness APK", listOf(newer, matching)).first())
    }

    @Test
    fun candidatesWithSameMatchScoreSortNewestFirst() {
        val newer = matching.copy(workspaceId = "newer", lastUsedAt = 30L)
        val older = matching.copy(workspaceId = "older", lastUsedAt = 20L)

        assertEquals(listOf("newer", "older"), rankWorkspaceCandidates("other", listOf(older, newer)).map { it.workspaceId })
    }

    @Test
    fun emptyCandidateListDoesNotCreateManualPathFallback() {
        assertTrue(rankWorkspaceCandidates("Harness", emptyList()).isEmpty())
    }

    @Test
    fun rebindingRequiresExplicitConfirmationWhenFingerprintChanges() {
        val same = evaluateBindingChange("fingerprint-match", matching, confirmed = false)
        val mismatch = evaluateBindingChange(
            existingFingerprint = "old-fingerprint",
            candidate = matching,
            confirmed = false,
        )
        val confirmed = evaluateBindingChange(
            existingFingerprint = "old-fingerprint",
            candidate = matching,
            confirmed = true,
        )

        assertTrue(same.allowed)
        assertFalse(mismatch.allowed)
        assertTrue(mismatch.requiresConfirmation)
        assertTrue(confirmed.allowed)
    }
}
