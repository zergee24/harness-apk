package com.harnessapk.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteRunRepositoryTest {
    @Test
    fun terminalRunRejectsLateRunningEvent() {
        assertEquals(
            RemoteRunStatus.COMPLETED,
            RemoteRunRepository.reduceStatus(
                current = RemoteRunStatus.COMPLETED,
                incoming = RemoteRunStatus.RUNNING,
            ),
        )
        assertEquals(
            RemoteRunStatus.FAILED,
            RemoteRunRepository.reduceStatus(
                current = RemoteRunStatus.FAILED,
                incoming = RemoteRunStatus.RUNNING,
            ),
        )
        assertEquals(
            RemoteRunStatus.CANCELLED,
            RemoteRunRepository.reduceStatus(
                current = RemoteRunStatus.CANCELLED,
                incoming = RemoteRunStatus.RUNNING,
            ),
        )
    }
}
