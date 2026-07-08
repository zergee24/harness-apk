package com.harnessapk.updater

import java.io.File

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val apkUrl: String?,
    val apkChunks: List<String> = emptyList(),
    val sha256: String,
    val releaseNotes: List<String>,
    val publishedAt: String,
)

data class UpdateCheckResult(
    val manifest: UpdateManifest?,
    val updateAvailable: Boolean,
    val forceUpdate: Boolean,
)

data class ApkDownloadResult(
    val file: File,
    val sha256: String,
)
