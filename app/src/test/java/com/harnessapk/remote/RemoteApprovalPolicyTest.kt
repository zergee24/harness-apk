package com.harnessapk.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteApprovalPolicyTest {
    @Test
    fun tokenInCommandAndUrlIsRedactedBeforePersistence() {
        val raw = Json.parseToJsonElement(
            """{"command":"curl -H 'Authorization: Bearer top-secret' 'https://api.example.com/run?access_token=url-secret'","apiKey":"json-secret"}""",
        )

        val sanitized = sanitizeRemoteApprovalJson(raw).toString()

        listOf("top-secret", "url-secret", "json-secret").forEach { secret ->
            assertFalse(sanitized.contains(secret))
        }
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun highRiskNotificationHasNoAllowAction() {
        assertEquals(RemoteApprovalRisk.HIGH, classifyRemoteApprovalRisk("sudo rm -rf build", "COMMAND_EXECUTION"))
        assertFalse(remoteApprovalPolicy(RemoteApprovalRisk.HIGH, deviceLocked = false).allowFromNotification)
        assertTrue(remoteApprovalPolicy(RemoteApprovalRisk.HIGH, deviceLocked = false).requiresDetailConfirmation)
    }

    @Test
    fun lockedDeviceRequiresUnlockBeforeHighRiskApproval() {
        assertFalse(remoteApprovalPolicy(RemoteApprovalRisk.HIGH, deviceLocked = true).canApproveNow)
        assertTrue(remoteApprovalPolicy(RemoteApprovalRisk.LOW, deviceLocked = true).canApproveNow)
    }
}
