package com.harnessapk.websearch

fun shouldUseWebSearch(text: String): Boolean {
    val normalized = text.trim().lowercase()
    if (normalized.isBlank()) return false
    return listOf(
        "联网",
        "搜索",
        "最新",
        "查一下",
        "查下",
        "引用来源",
        "来源",
        "打开",
        "http://",
        "https://",
    ).any { normalized.contains(it.lowercase()) }
}
