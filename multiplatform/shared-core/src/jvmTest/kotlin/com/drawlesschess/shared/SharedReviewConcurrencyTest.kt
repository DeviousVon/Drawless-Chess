package com.drawlesschess.shared

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedReviewConcurrencyTest {
    @Test
    fun concurrentEnsureReviewStartedCallsOwnExactlyOneGeneration() {
        repeat(12) { attempt ->
            val runtime = SharedGameRuntime()
            try {
                runtime.resign()
                val callerCount = 12
                val callersReady = CountDownLatch(callerCount)
                val releaseCallers = CountDownLatch(1)
                val callersDone = CountDownLatch(callerCount)
                val failures = ConcurrentLinkedQueue<Throwable>()

                repeat(callerCount) {
                    thread(name = "review-ensure-$attempt-$it") {
                        callersReady.countDown()
                        try {
                            releaseCallers.await()
                            runtime.ensureReviewStarted()
                        } catch (error: Throwable) {
                            failures.add(error)
                        } finally {
                            callersDone.countDown()
                        }
                    }
                }

                assertTrue(callersReady.await(5, TimeUnit.SECONDS), "Review callers did not become ready")
                releaseCallers.countDown()
                assertTrue(callersDone.await(10, TimeUnit.SECONDS), "Review callers did not finish")
                failures.peek()?.let { throw AssertionError("Concurrent Review call failed", it) }

                assertEquals(1L, runtime.reviewStatus().generation)
                val handoffCount = runtime.drainReviewAuditEvents()
                    .lineSequence()
                    .count { it.contains("\"event\":\"terminal_handoff_frozen\"") }
                assertEquals(1, handoffCount, "Review handoff was claimed more than once")
            } finally {
                runtime.close()
            }
        }
    }
}
