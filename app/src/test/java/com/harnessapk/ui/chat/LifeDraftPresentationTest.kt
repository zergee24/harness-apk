package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatSendAttachmentKind
import com.harnessapk.chat.ChatSendAttachmentSnapshot
import com.harnessapk.chat.ChatSendIntent
import com.harnessapk.chat.ChatSendRequestPhase
import com.harnessapk.chat.ChatSendRequestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeDraftPresentationTest {
    private val file = ChatSendAttachmentSnapshot(
        id = "document-1", kind = ChatSendAttachmentKind.DOCUMENT, uri = "private-copy",
        mimeType = "text/plain", displayName = "问题.txt", sha256 = "version-1",
        extractedText = "文件原文", sizeBytes = 12L, originalCharCount = 4,
    )
    private fun state(phase: ChatSendRequestPhase) = ChatSendRequestState(
        requestId = "one-request", submittedText = "用户原话\n【附件：问题.txt】文件原文【附件结束】",
        submittedAttachments = emptyList(), isFirstUserMessage = true, currentDraftText = "用户原话",
        currentDraftAttachmentSnapshots = listOf(file), phase = phase,
        intent = ChatSendIntent("conversation", "one-request", "用户原话", originalAttachments = listOf(file)),
    )

    @Test fun confirmedLandingClearsTheOriginalQuestionAndOnlyItsDocuments() {
        val draft = draftFromSendState(state(ChatSendRequestPhase.LANDED), settle = true)
        assertEquals("", draft.text)
        assertTrue(draft.documents.isEmpty())
    }

    @Test fun definiteAbsenceRestoresRawQuestionAndTypedDocumentBody() {
        val draft = draftFromSendState(state(ChatSendRequestPhase.NOT_LANDED), settle = true)
        assertEquals("用户原话", draft.text)
        assertEquals("问题.txt", draft.documents.single().displayName)
        assertEquals("文件原文", draft.documents.single().body)
    }

    @Test fun unknownNeverHidesTheSubmittedDocumentOrShowsTheProviderWrapperInTheEditor() {
        val draft = draftFromSendState(state(ChatSendRequestPhase.UNKNOWN))
        assertEquals("用户原话", draft.text)
        assertEquals("document-1", draft.documents.single().id)
    }

    @Test fun settlementKeepsAnIndependentlyChangedDraftAndNewContentVersion() {
        val draft = draftFromSendState(state(ChatSendRequestPhase.LANDED).copy(
            currentDraftText = "下一条问题",
            currentDraftAttachmentSnapshots = listOf(file.copy(sha256 = "version-2", extractedText = "修订的正文")),
        ), settle = true)
        assertEquals("下一条问题", draft.text)
        assertEquals("修订的正文", draft.documents.single().body)
    }
}
