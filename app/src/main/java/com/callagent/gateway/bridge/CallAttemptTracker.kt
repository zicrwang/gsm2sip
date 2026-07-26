package com.callagent.gateway.bridge

import java.util.concurrent.atomic.AtomicLong

/** Invalidates delayed work as soon as a call ends or a new call begins. */
internal class CallAttemptTracker {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()

    fun invalidate(): Long = generation.incrementAndGet()

    fun current(): Long = generation.get()

    fun isCurrent(token: Long): Boolean = generation.get() == token
}
