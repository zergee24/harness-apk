package com.harnessapk.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteModelsTest {
    @Test
    fun pairingPayloadRequiresHttpsAndParsesCurrentProtocol() {
        val payload = parsePairingPayload(
            """{"version":1,"relayUrl":"https://relay.example.com","hostId":"mac","hostName":"Studio","pairingTicket":"ticket","pairingSecret":"secret","expiresAt":2000}""",
            now = 1000,
        )

        assertEquals("mac", payload.hostId)
        assertEquals("Studio", payload.hostName)
    }

    @Test
    fun pairingPayloadRejectsExpiredOrCleartextRelay() {
        assertThrows(IllegalArgumentException::class.java) {
            parsePairingPayload("""{"version":1,"relayUrl":"http://relay.example.com","hostId":"mac","pairingTicket":"t","pairingSecret":"s","expiresAt":2000}""", 1000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parsePairingPayload("""{"version":1,"relayUrl":"https://relay.example.com","hostId":"mac","pairingTicket":"t","pairingSecret":"s","expiresAt":999}""", 1000)
        }
    }
}
