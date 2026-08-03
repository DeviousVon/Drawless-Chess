package com.drawlesschess.core

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Small multiplatform mutual-exclusion primitive for the stateful coordinator.
 *
 * Coordinator critical sections are deliberately short and never suspend or invoke the engine.
 * Keeping the lock in the standard-library atomic API lets the exact same coordinator source run
 * on Android/JVM and Kotlin/Native without weakening its serialized state transitions.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ConcurrentLock {
    @PublishedApi
    internal val held = AtomicBoolean(false)

    @PublishedApi
    internal fun acquire() {
        while (!held.compareAndSet(expectedValue = false, newValue = true)) {
            // Critical sections are bounded state transitions; retry until their owner releases.
        }
    }

    internal fun tryAcquire(): Boolean =
        held.compareAndSet(expectedValue = false, newValue = true)

    @PublishedApi
    internal fun release() {
        check(held.exchange(false)) { "Attempted to release an unlocked coordinator lock" }
    }

    @OptIn(ExperimentalContracts::class)
    internal inline fun <T> withLock(block: () -> T): T {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }
}
