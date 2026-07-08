package com.harnessapk.common

fun interface TimeProvider {
    fun nowMillis(): Long
}

object SystemTimeProvider : TimeProvider {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
