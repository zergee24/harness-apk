package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDocumentPresentationTest {
    @Test fun documentPresentationSurvivesExecutionPersistenceWithoutChangingQuestion() {
        val context = ChatExecutionRequestContext(
            userInputText = "请解释【附件：这是我手写的文字】",
            documents = listOf(ChatDocumentPresentation(
                id = "attachment-1", uri = "file:///private/draft.txt", fileName = "报告.txt",
                mimeType = "text/plain", sizeBytes = 40L, sha256 = "verified-content-hash",
                extractedText = "实际文件内容", truncated = false,
            )),
        )
        val restored = decodeExecutionRequestContext(encodeExecutionRequestContext(context))
        assertEquals(context, restored)
        assertEquals(context.userInputText, displayedUserQuestion("provider payload", restored))
    }

    @Test fun legacyMessagesAndHandwrittenDelimitersAreNeverStripped() {
        val original = "【附件：手写示例】\n请解释此格式\n【附件结束】"
        assertEquals(original, displayedUserQuestion(original, decodeExecutionRequestContext("{}")))
        assertEquals(original, displayedUserQuestion(original, null))
    }
}
