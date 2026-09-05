package com.harnessapk.workbrief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefStateMachineTest {

    @Test
    fun briefHappyPathIsAllowed() {
        assertTrue(BriefStateMachine.canTransitionBrief(WorkBriefStatus.DRAFT, WorkBriefStatus.CAPTURING))
        assertTrue(BriefStateMachine.canTransitionBrief(WorkBriefStatus.CAPTURING, WorkBriefStatus.PROCESSING))
        assertTrue(BriefStateMachine.canTransitionBrief(WorkBriefStatus.PROCESSING, WorkBriefStatus.READY))
        // READY -> PROCESSING：生成新修订
        assertTrue(BriefStateMachine.canTransitionBrief(WorkBriefStatus.READY, WorkBriefStatus.PROCESSING))
    }

    @Test
    fun briefRecoverablePathAllowed() {
        assertTrue(BriefStateMachine.canTransitionBrief(WorkBriefStatus.CAPTURING, WorkBriefStatus.RECOVERABLE))
        assertTrue(BriefStateMachine.canTransitionBrief(WorkBriefStatus.RECOVERABLE, WorkBriefStatus.PROCESSING))
        assertTrue(BriefStateMachine.canTransitionBrief(WorkBriefStatus.RECOVERABLE, WorkBriefStatus.READY))
    }

    @Test
    fun briefIllegalJumpsRejected() {
        // DRAFT 不能直接 READY（必须先经过 CAPTURING/PROCESSING）
        assertFalse(BriefStateMachine.canTransitionBrief(WorkBriefStatus.DRAFT, WorkBriefStatus.READY))
        // 终态不可再动
        assertFalse(BriefStateMachine.canTransitionBrief(WorkBriefStatus.DELETED, WorkBriefStatus.CAPTURING))
        assertFalse(BriefStateMachine.canTransitionBrief(WorkBriefStatus.CORRUPTED, WorkBriefStatus.READY))
        // DELETED 只能从 DELETING 来
        assertTrue(BriefStateMachine.canTransitionBrief(WorkBriefStatus.DELETING, WorkBriefStatus.DELETED))
    }

    @Test
    fun sessionHappyPathWithPauseResumeIsAllowed() {
        assertTrue(BriefStateMachine.canTransitionSession(CaptureSessionStatus.PREPARING, CaptureSessionStatus.ACTIVE))
        assertTrue(BriefStateMachine.canTransitionSession(CaptureSessionStatus.ACTIVE, CaptureSessionStatus.PAUSED))
        assertTrue(BriefStateMachine.canTransitionSession(CaptureSessionStatus.PAUSED, CaptureSessionStatus.ACTIVE))
        assertTrue(BriefStateMachine.canTransitionSession(CaptureSessionStatus.ACTIVE, CaptureSessionStatus.STOPPING))
        assertTrue(BriefStateMachine.canTransitionSession(CaptureSessionStatus.STOPPING, CaptureSessionStatus.SEALED))
    }

    @Test
    fun sessionSealedIsTerminal() {
        assertFalse(BriefStateMachine.canTransitionSession(CaptureSessionStatus.SEALED, CaptureSessionStatus.ACTIVE))
        assertFalse(BriefStateMachine.canTransitionSession(CaptureSessionStatus.SEALED, CaptureSessionStatus.RECOVERABLE))
    }

    @Test
    fun requireFunctionsThrowWithReadableMessage() {
        val error = runCatching {
            BriefStateMachine.requireBriefTransition(WorkBriefStatus.DRAFT, WorkBriefStatus.READY)
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("DRAFT -> READY"))
    }
}
