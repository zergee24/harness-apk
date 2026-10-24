package com.harnessapk.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZcodeWebRemoteTest {
    @Test
    fun pastedExplanationIsNotPartOfTheOpenedUrl() {
        val url = "https://zcode.z.ai/remote/v4?sid=abc&hash=def"
        assertEquals(url, extractZcodeRemoteUrl("说明文字\n$url\n"))
        assertNull(extractZcodeRemoteUrl("https:///remote/no-host"))
        assertNull(extractZcodeRemoteUrl("http://zcode.z.ai/remote/v4"))
    }


    private val validUrl =
        "https://zcode.z.ai/remote/v4?sid=d_ABC123&hash=AbCd%2F0%3D&t=1789404212550&mid=m1&name=mac&app_version=3.11.2"

    @Test
    fun `解析 bridge webremote 回执完整载荷`() {
        val payload = Json.parseToJsonElement(
            """{"ok":true,"action":"refresh","stage":"","url":"$validUrl"}""",
        ).jsonObject
        val result = parseZcodeWebRemoteResult(payload)
        assertTrue(result.ok)
        assertEquals("refresh", result.action)
        assertEquals(validUrl, result.url)
        assertNull(zcodeWebRemoteStageLabel(result.stage))
    }

    @Test
    fun `解析失败回执并映射引导文案`() {
        val payload = Json.parseToJsonElement(
            """{"ok":false,"action":"refresh","stage":"ax-denied","message":"denied"}""",
        ).jsonObject
        val result = parseZcodeWebRemoteResult(payload)
        assertFalse(result.ok)
        assertEquals("ax-denied", result.stage)
        assertTrue(zcodeWebRemoteStageLabel(result.stage)!!.contains("辅助功能"))
    }

    @Test
    fun `空载荷回退为失败结果`() {
        val result = parseZcodeWebRemoteResult(null)
        assertFalse(result.ok)
        assertEquals("", result.action)
    }

    @Test
    fun `未知 stage 走兜底文案`() {
        assertEquals("Mac 端自动化失败，可稍后重试", zcodeWebRemoteStageLabel("weird"))
        assertNull(zcodeWebRemoteStageLabel(null))
        assertNull(zcodeWebRemoteStageLabel("disconnected"))
    }

    @Test
    fun `配对链接底线校验`() {
        // 新版 ZCode 链接参数代际变化大，底线 = https 链接 + 路径含 remote 或主机含 zcode。
        assertTrue(looksLikeZcodeRemoteUrl(validUrl))
        assertTrue(looksLikeZcodeRemoteUrl("说明文字\n$validUrl\n"))
        assertTrue(looksLikeZcodeRemoteUrl("https://zcode.z.ai/other?sid=a&hash=b"))
        assertTrue(looksLikeZcodeRemoteUrl("https://zcode.z.ai/remote/v4?sid=a"))
        assertTrue(looksLikeZcodeRemoteUrl("https://example.com/remote/x?token=abc"))
        assertFalse(looksLikeZcodeRemoteUrl("http://zcode.z.ai/remote/v4?sid=a&hash=b"))
        assertFalse(looksLikeZcodeRemoteUrl("随便一段话"))
        assertFalse(looksLikeZcodeRemoteUrl(""))
    }
}
