package com.harnessapk.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFailureHintsTest {

    /** 荣耀真机实录：Base URL 手误成 happycode.com（过期证书的无关站点）。 */
    private val honorLog = """
        LLM 请求失败：Chain validation failed
        --- 诊断日志 ---
        Time: 1789401591140
        Provider: OpenAI
        Base URL: https://happycode.com/v1
        Model: gpt-6-astra
        Exception: javax.net.ssl.SSLHandshakeException
        Message: Chain validation failed
        Elapsed Ms: 1138
    """.trimIndent()

    @Test
    fun `tls failure headline names the host and points at Base URL`() {
        val firstLine = honorLog.lineSequence().first()
        val headline = NetworkFailureHints.chatHeadline(firstLine, honorLog)

        assertTrue(headline.contains("加密连接校验失败"))
        assertTrue(headline.contains("happycode.com"))
        assertTrue(headline.contains("Base URL"))
        assertFalse(headline.contains("Chain validation failed"))
    }

    @Test
    fun `dns failure headline names the host`() {
        val log = """
            LLM 请求失败：Unable to resolve host "hapycodai.com": No address associated with hostname
            Base URL: https://hapycodai.com/v1
            Exception: java.net.UnknownHostException
        """.trimIndent()

        val headline = NetworkFailureHints.chatHeadline(log.lineSequence().first(), log)

        assertTrue(headline.contains("域名解析失败"))
        assertTrue(headline.contains("hapycodai.com"))
    }

    @Test
    fun `non network errors pass through unchanged`() {
        val error = "LLM 请求失败：HTTP 401，invalid api key"
        assertTrue(NetworkFailureHints.chatHeadline(error, error) == error)
    }

    @Test
    fun `connection test status classifies tls failure with host`() {
        val failure = javax.net.ssl.SSLHandshakeException("Chain validation failed")
        val status = NetworkFailureHints.connectionTestStatus(failure, "https://happycode.com/v1")

        assertTrue(status.contains("加密连接校验失败"))
        assertTrue(status.contains("happycode.com"))
    }

    @Test
    fun `connection test status keeps provider http errors as is`() {
        val failure = com.harnessapk.network.ChatHttpException("请求失败：HTTP 401，invalid key")
        val status = NetworkFailureHints.connectionTestStatus(failure, "https://happycodeai.com/v1")

        assertTrue(status.contains("HTTP 401"))
    }

    @Test
    fun `classification helpers`() {
        assertTrue(NetworkFailureHints.isTlsFailure("Exception: javax.net.ssl.SSLHandshakeException"))
        assertTrue(NetworkFailureHints.isTlsFailure("Chain validation failed"))
        assertTrue(NetworkFailureHints.isDnsFailure("Unable to resolve host \"x\""))
        assertFalse(NetworkFailureHints.isTlsFailure("HTTP 502 Bad Gateway"))
        assertFalse(NetworkFailureHints.isDnsFailure("Chain validation failed"))
    }

    @Test
    fun `host parsing tolerates trailing paths and bad urls`() {
        assertTrue(NetworkFailureHints.hostOf("https://HappyCode.com/v1/") == "happycode.com")
        assertTrue(NetworkFailureHints.hostOf("not a url") == null)
        assertTrue(NetworkFailureHints.hostOf(null) == null)
    }
}
