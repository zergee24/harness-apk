package com.harnessapk.ui.remote

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import java.net.URLDecoder

/**
 * 浏览器套壳的下载对接：WebView 默认不处理下载，点下载链接是静默无效的。
 * 走系统 DownloadManager 落公共 Download 目录（任意文件管理器可取，便于
 * 中转到 Mac）；带登录态 Cookie 与 UA 头，chatgpt 登录态产物可下。
 */
fun WebView.installWebShellDownload(context: Context) {
    setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
        val name = webShellDownloadFileName(url, contentDisposition, mimetype)
        runCatching {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription("浏览器套壳下载")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType(mimetype)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            request.addRequestHeader("User-Agent", userAgent)
            manager.enqueue(request)
            Toast.makeText(context, "已开始下载 $name（完成后在 Download 目录）", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(context, "下载失败：${error.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

/** 下载文件名：content-disposition 的 filename 优先，退回 URL 末段，再退时间戳。 */
internal fun webShellDownloadFileName(
    url: String,
    contentDisposition: String?,
    mimetype: String?,
): String {
    if (!contentDisposition.isNullOrBlank()) {
        // RFC 5987 扩展格式优先（UTF-8 文件名）。
        Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(contentDisposition)
            ?.groupValues?.get(1)?.let { encoded ->
                URLDecoder.decode(encoded, "UTF-8").takeIf { it.isNotBlank() }?.let { return it }
            }
        Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(contentDisposition)
            ?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
    }
    url.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() }?.let { return it }
    return "download_${System.currentTimeMillis()}"
}
