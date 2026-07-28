package com.harnessapk.remote

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteEnrollmentClientTest {
    @Test
    fun pushTargetUpdateUsesAuthenticatedDeviceEndpoint() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        try {
            val profile = RemoteProfile(
                relayUrl = server.url("/").toString().removeSuffix("/"),
                hostId = "mac",
                hostName = "Mac",
                deviceId = "phone",
                deviceToken = "device-token",
                pairingTicket = "ticket",
                pairingSecret = "secret",
            )

            RemoteEnrollmentClient(OkHttpClient()).updatePushTarget(profile, "push-ready")

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/v1/devices/phone", request.path)
            assertEquals("Bearer device-token", request.getHeader("Authorization"))
            assertEquals("""{"PushTarget":"push-ready"}""", request.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }
}
