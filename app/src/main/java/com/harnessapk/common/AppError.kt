package com.harnessapk.common

sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ProviderMissing : AppError("请先在模型配置中保存供应商")
    class ApiKeyMissing : AppError("请重新输入供应商 API Key")
    class VisionUnsupported : AppError("当前供应商未开启图片输入，请切换支持图片的模型或移除图片。")
    class ImageTooLarge : AppError("图片超过 8 MB，请选择更小的截图或图片")
    class Network(message: String) : AppError(message)
    class Update(message: String) : AppError(message)
}

fun Throwable.toUserMessage(): String = when (this) {
    is AppError -> message ?: "操作失败"
    else -> message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
}
