package com.harnessapk.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolContractTest {
    @Test
    fun approvalWireUsesCurrentAppServerDecisionNames() {
        assertEquals("accept", approvalDecisionForWire(ApprovalDecision.ALLOW_ONCE))
        assertEquals("decline", approvalDecisionForWire(ApprovalDecision.DENY))
    }

    @Test
    fun steerRequiresExpectedTurnId() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteM2Command.Steer(
                commandId = "command-1",
                runId = "run-1",
                expectedTurnId = "",
                text = "继续",
            )
        }
    }

    @Test
    fun steerUsesCommandIdAsLegacyRequestId() {
        val payload = RemoteM2Command.Steer(
            commandId = "command-1",
            runId = "run-1",
            expectedTurnId = "turn-1",
            text = "继续",
        ).toJson()

        assertEquals("run.steer", payload.string("type"))
        assertEquals("command-1", payload.string("commandId"))
        assertEquals("command-1", payload.string("requestId"))
    }

    @Test
    fun interruptUsesRunCommandContractRatherThanApprovalCancellation() {
        val payload = RemoteM2Command.Interrupt(
            commandId = "interrupt-1",
            runId = "run-1",
            expectedTurnId = "turn-1",
        ).toJson()

        assertEquals("run.interrupt", payload.string("type"))
        assertEquals("interrupt-1", payload.string("requestId"))
        assertEquals("turn-1", payload.string("expectedTurnId"))
        assertFalse(payload.containsKey("decision"))
    }

    @Test
    fun requestUserInputIsNotDecodedAsApproval() {
        assertEquals(
            RemoteServerInteractionKind.USER_INPUT,
            remoteServerInteractionKind("item/tool/requestUserInput"),
        )
        assertFalse(isRemoteApprovalMethod("item/tool/requestUserInput"))
        assertTrue(isRemoteApprovalMethod("item/commandExecution/requestApproval"))
    }

    @Test
    fun unsupportedBridgeCapabilitiesDisableRunStartButKeepLegacyHistory() {
        val availability = remoteFeatureAvailability(
            setOf("workspace.candidates.v1", "run.lifecycle.v1"),
        )

        assertFalse(availability.canStartM2Run)
        assertTrue(availability.canOpenLegacyHistory)
        assertFalse(availability.canUseM3CompletionEvidence)
        assertFalse(availability.canLoadLatestUserMessage)
    }

    @Test
    fun completeBridgeCapabilitiesEnableRunStart() {
        val availability = remoteFeatureAvailability(
            setOf(
                "workspace.candidates.v1",
                "run.lifecycle.v1",
                "logical-replay.v1",
                "completion-evidence.v2",
                "thread-latest-user-message.v1",
            ),
        )

        assertTrue(availability.canStartM2Run)
        assertTrue(availability.canUseM3CompletionEvidence)
        assertTrue(availability.canLoadLatestUserMessage)
    }

    @Test
    fun hostStatusParsesCapabilitiesAndFailsClosedForUnknownSchema() {
        val capabilities = parseRemoteHostCapabilities(
            RemoteEvent(
                type = "host.status",
                payload = kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"schemaVersion":1,"capabilities":["run.lifecycle.v1","completion-evidence.v2","run.lifecycle.v1"]}""",
                ),
            ),
        )

        assertEquals(setOf("run.lifecycle.v1", "completion-evidence.v2"), capabilities)
        assertTrue(
            parseRemoteHostCapabilities(
                RemoteEvent(
                    type = "host.status",
                    payload = kotlinx.serialization.json.Json.parseToJsonElement(
                        """{"schemaVersion":2,"capabilities":["completion-evidence.v2"]}""",
                    ),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun logicalEventIgnoresUnknownFieldsButRequiresStableIdentity() {
        val event = parseRemoteLogicalEvent(
            """
            {
              "schemaVersion": 1,
              "eventId": "event-1",
              "hostId": "host-1",
              "deviceId": "device-1",
              "runId": "run-1",
              "sequence": 7,
              "type": "run.item.upserted",
              "payload": {"itemId":"item-1"},
              "createdAt": 1234,
              "futureField": {"ignored":true}
            }
            """.trimIndent(),
        )

        assertEquals("event-1", event.eventId)
        assertEquals(7L, event.sequence)
        assertThrows(IllegalArgumentException::class.java) {
            parseRemoteLogicalEvent(
                """{"schemaVersion":1,"hostId":"host-1","deviceId":"device-1","runId":"run-1","sequence":7,"type":"run.started","createdAt":1234}""",
            )
        }
    }
}
