package com.harnessapk.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class AgentLifecycleCoordinator {
    private val mutex = Mutex()

    suspend fun <T> serialized(block: suspend () -> T): T {
        val owner = currentCoroutineContext()[LifecycleOwner]
        if (owner?.coordinator === this) return block()
        return mutex.withLock {
            withContext(LifecycleOwner(this)) { block() }
        }
    }

    private class LifecycleOwner(
        val coordinator: AgentLifecycleCoordinator,
    ) : AbstractCoroutineContextElement(LifecycleOwner) {
        companion object : CoroutineContext.Key<LifecycleOwner>
    }
}
