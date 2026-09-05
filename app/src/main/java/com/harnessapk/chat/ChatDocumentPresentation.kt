package com.harnessapk.chat

/** Display metadata lives with the durable execution; provider content remains unchanged. */
data class ChatDocumentPresentation(
    val id: String,
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val extractedText: String,
    val truncated: Boolean,
)

/** Only explicit metadata can replace protocol text. Never parse a user's handwritten delimiters. */
fun displayedUserQuestion(content: String, context: ChatExecutionRequestContext?): String =
    context?.userInputText ?: content
