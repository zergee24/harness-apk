package com.harnessapk.remote

import com.harnessapk.storage.RemoteRunEventEntity
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteCompletionEvidenceTest {
    @Test
    fun unknownItemShowsGenericStatusAndKeepsDiagnosticPayload() {
        val event = event(
            id = "event-1",
            itemId = "item-unknown",
            presentationKind = "FUTURE_TOOL",
            payload = """{"futureField":"diagnostic-value"}""",
        )

        val item = presentRemoteTimeline(event)

        assertEquals("正在处理", item.title)
        assertTrue(item.diagnosticPayload.contains("diagnostic-value"))
    }

    @Test
    fun repeatedAgentDeltaUpsertsOneTimelineItem() {
        val collapsed = collapseRemoteTimeline(
            listOf(
                event("event-1", "agent-1", "AGENT_DELTA", """{"delta":"正在"}"""),
                event("event-2", "agent-1", "AGENT_DELTA", """{"delta":"分析"}"""),
            ),
        )

        assertEquals(1, collapsed.size)
        assertEquals("正在分析", collapsed.single().detail)
    }

    @Test
    fun missingTestEventRendersUnverified() {
        val evidence = parseRemoteCompletionEvidence(
            """{"summary":"完成修复","changedFiles":[],"tests":[],"unresolved":[],"completedAt":10}""",
        )

        assertEquals("测试未验证", evidence.testSummary)
        assertEquals("文件未验证", evidence.fileSummary)
        assertEquals("Git 未验证", evidence.gitSummary)
        assertEquals(RemoteCompletionVerification.LEGACY_UNVERIFIED, evidence.verification)
    }

    @Test
    fun completionV2KeepsStableEvidenceIdsAndWorkspaceLocator() {
        // Hashes are copied from the compact JSON emitted by Go json.Marshal for the Bridge structs.
        val fileHash = sha256("""{"path":"docs/result.md","source":"git"}""")
        val testHash = sha256("""{"command":"go test ./...","status":"PASSED","exitCode":0}""")
        val evidence = parseRemoteCompletionEvidence(
            """
            {
              "schemaVersion":2,
              "completionId":"completion-123",
              "summary":"完成 M3",
              "changedFiles":[{"evidenceId":"file-1","evidenceSha256":"$fileHash","path":"docs/result.md","source":"git"}],
              "tests":[{"evidenceId":"test-1","evidenceSha256":"$testHash","command":"go test ./...","status":"PASSED","exitCode":0}],
              "unresolved":[],
              "completedAt":20,
              "workspace":{"workspaceId":"workspace-1","repositoryFingerprint":"fingerprint-1","cwd":"/mac/harness-apk"}
            }
            """.trimIndent(),
        )

        assertEquals(2, evidence.schemaVersion)
        assertEquals("completion-123", evidence.completionId)
        assertEquals(RemoteCompletionVerification.VERIFIED_V2, evidence.verification)
        assertEquals("file-1", evidence.files.single().evidenceId)
        assertEquals("test-1", evidence.tests.single().evidenceId)
        assertEquals("workspace-1", evidence.workspace?.workspaceId)
        assertEquals("/mac/harness-apk", evidence.workspace?.cwd)
    }

    @Test
    fun completionV2WithoutFileOrTestEvidenceIsUnverified() {
        val evidence = parseRemoteCompletionEvidence(
            """
            {
              "schemaVersion":2,
              "completionId":"completion-empty",
              "summary":"仅有完成声明",
              "changedFiles":[],
              "tests":[],
              "unresolved":[],
              "completedAt":20
            }
            """.trimIndent(),
        )

        assertEquals(RemoteCompletionVerification.UNVERIFIED_V2, evidence.verification)
    }

    @Test
    fun completionV2RequiresAHexContentBoundEvidenceHash() {
        val wrongContentHash = parseRemoteCompletionEvidence(
            """
            {
              "schemaVersion":2,
              "completionId":"completion-wrong-hash",
              "summary":"错误哈希",
              "changedFiles":[{"evidenceId":"file-1","evidenceSha256":"${"a".repeat(64)}","path":"docs/result.md","source":"git"}],
              "tests":[],
              "unresolved":[],
              "completedAt":20
            }
            """.trimIndent(),
        )
        val nonHexHash = parseRemoteCompletionEvidence(
            """
            {
              "schemaVersion":2,
              "completionId":"completion-non-hex",
              "summary":"非十六进制哈希",
              "changedFiles":[],
              "tests":[{"evidenceId":"test-1","evidenceSha256":"${"z".repeat(64)}","command":"go test ./...","status":"PASSED","exitCode":0}],
              "unresolved":[],
              "completedAt":20
            }
            """.trimIndent(),
        )

        assertEquals(RemoteCompletionVerification.UNVERIFIED_V2, wrongContentHash.verification)
        assertEquals(RemoteCompletionVerification.UNVERIFIED_V2, nonHexHash.verification)
    }

    @Test
    fun completionV2TestHashOmitsNullExitCodeLikeBridge() {
        val testHash = sha256("""{"command":"pytest","status":"UNVERIFIED"}""")
        val evidence = parseRemoteCompletionEvidence(
            """
            {
              "schemaVersion":2,
              "completionId":"completion-no-exit-code",
              "summary":"测试没有退出码",
              "changedFiles":[],
              "tests":[{"evidenceId":"test-1","evidenceSha256":"$testHash","command":"pytest","status":"UNVERIFIED"}],
              "unresolved":[],
              "completedAt":20
            }
            """.trimIndent(),
        )

        assertEquals(RemoteCompletionVerification.VERIFIED_V2, evidence.verification)
    }

    @Test
    fun completionV2RejectsAnEvidenceArrayWhenAnyElementIsMalformed() {
        val validHash = sha256("""{"path":"docs/valid.md","source":"git"}""")
        val evidence = parseRemoteCompletionEvidence(
            """
            {
              "schemaVersion":2,
              "completionId":"completion-partial-parse",
              "summary":"包含无法解析的 evidence",
              "changedFiles":[
                {"evidenceId":"file-valid","evidenceSha256":"$validHash","path":"docs/valid.md","source":"git"},
                {"evidenceId":"file-missing-path","evidenceSha256":"${"a".repeat(64)}","source":"git"}
              ],
              "tests":[],
              "unresolved":[],
              "completedAt":20
            }
            """.trimIndent(),
        )

        assertEquals(RemoteCompletionVerification.UNVERIFIED_V2, evidence.verification)
    }

    @Test
    fun completionV2MatchesBridgeJsonEscapingForEvidenceHash() {
        val bridgeHash = sha256(
            """{"command":"go test ./... \u0026\u0026 echo \"done\"","status":"PASSED","exitCode":0}""",
        )
        val evidence = parseRemoteCompletionEvidence(
            """
            {
              "schemaVersion":2,
              "completionId":"completion-go-escape",
              "summary":"Bridge JSON 转义",
              "changedFiles":[],
              "tests":[{"evidenceId":"test-escaped","evidenceSha256":"$bridgeHash","command":"go test ./... &amp;&amp; echo \"done\"","status":"PASSED","exitCode":0}],
              "unresolved":[],
              "completedAt":20
            }
            """.trimIndent().replace("&amp;", "&"),
        )

        assertEquals(RemoteCompletionVerification.VERIFIED_V2, evidence.verification)
    }

    @Test
    fun completionV2RequiresBridgeEvidenceContentFields() {
        val fileWithoutSource = parseRemoteCompletionEvidence(
            """
            {
              "schemaVersion":2,
              "completionId":"completion-missing-source",
              "summary":"缺 source",
              "changedFiles":[{"evidenceId":"file-1","evidenceSha256":"${sha256("""{"path":"docs/result.md"}""")}","path":"docs/result.md"}],
              "tests":[],
              "unresolved":[],
              "completedAt":20
            }
            """.trimIndent(),
        )
        val testWithoutStatus = parseRemoteCompletionEvidence(
            """
            {
              "schemaVersion":2,
              "completionId":"completion-missing-status",
              "summary":"缺 status",
              "changedFiles":[],
              "tests":[{"evidenceId":"test-1","evidenceSha256":"${sha256("""{"command":"pytest","status":"UNVERIFIED"}""")}","command":"pytest"}],
              "unresolved":[],
              "completedAt":20
            }
            """.trimIndent(),
        )

        assertEquals(RemoteCompletionVerification.UNVERIFIED_V2, fileWithoutSource.verification)
        assertEquals(RemoteCompletionVerification.UNVERIFIED_V2, testWithoutStatus.verification)
    }

    @Test
    fun unknownCompletionSchemaFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRemoteCompletionEvidence(
                """{"schemaVersion":99,"summary":"future","changedFiles":[],"tests":[],"unresolved":[],"completedAt":20}""",
            )
        }
    }

    private fun event(
        id: String,
        itemId: String?,
        presentationKind: String,
        payload: String,
    ) = RemoteRunEventEntity(
        logicalEventId = id,
        runId = "run-1",
        hostId = "host-1",
        deviceId = "device-1",
        sequence = id.substringAfterLast('-').toLong(),
        type = "run.timeline",
        itemId = itemId,
        presentationKind = presentationKind,
        payloadJson = payload,
        createdAt = 1L,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
