package com.harnessapk.network

import java.net.URI

/**
 * 把网络层异常翻译成可操作的中文提示：聊天错误气泡首行与供应商「测试连通」状态共用。
 * 只转译展示层，复制出的详细日志仍保留原始异常、Base URL 与 Trace ID，便于回报问题。
 *
 * 典型案例：Base URL 手误写成 happycode.com（过期证书的无关站点），用户只看到
 * 「Chain validation failed」，既不知道该查配置也不知道该查网络，排查成本极高。
 */
object NetworkFailureHints {

    fun isTlsFailure(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val upper = text.uppercase()
        return "SSLHANDSHAKEEXCEPTION" in upper ||
            "CHAIN VALIDATION FAILED" in upper ||
            "SSLCPEXCEPTION" in upper ||
            "CERTIFICATEEXCEPTION" in upper
    }

    fun isDnsFailure(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val upper = text.uppercase()
        return "UNABLE TO RESOLVE HOST" in upper || "UNKNOWNHOSTEXCEPTION" in upper
    }

    fun hostOf(baseUrl: String?): String? = baseUrl?.trim()?.let {
        runCatching { URI(it).host?.lowercase() }.getOrNull()
    }?.takeIf { it.isNotBlank() }

    private fun hostFromLog(logText: String): String? = logText.lineSequence()
        .firstOrNull { it.trimStart().startsWith("Base URL:") }
        ?.substringAfter("Base URL:")
        ?.let(::hostOf)

    /** 聊天错误气泡首行：TLS/DNS 类异常替换为点名 Base URL 主机的可操作提示，其余原样返回。 */
    fun chatHeadline(firstLine: String, fullErrorText: String): String {
        val host = hostFromLog(fullErrorText)
        return when {
            isTlsFailure(fullErrorText) -> "加密连接校验失败：连接 $host 时服务器证书异常。" +
                "常见原因是 Base URL 域名拼写有误（连到了无关站点），或当前网络劫持了连接；请核对 Base URL，或换个网络重试"
            isDnsFailure(fullErrorText) -> "域名解析失败：无法解析 $host。" +
                "多为 Base URL 拼写错误或设备断网，请核对后重试"
            else -> firstLine
        }
    }

    /** 供应商「测试连通」失败状态行：同样点名主机并给出排查方向。 */
    fun connectionTestStatus(failure: Throwable, baseUrl: String): String {
        val host = hostOf(baseUrl) ?: "服务器"
        val raw = failure.message ?: failure.toString()
        return when {
            isTlsFailure(raw) || isTlsFailure(failure.toString()) ->
                "加密连接校验失败：连接 $host 时证书异常。请核对 Base URL 域名是否拼写正确，或更换网络重试"
            isDnsFailure(raw) || isDnsFailure(failure.toString()) ->
                "域名解析失败：无法解析 $host。请检查 Base URL 拼写与设备网络"
            else -> raw
        }
    }
}
