package com.drawlesschess.engine

import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.engine.UciTimeoutScheduler
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Monotonic JVM/Android scheduler owned by one Android engine session. */
class AndroidUciTimeoutScheduler : UciTimeoutScheduler, AutoCloseable {
    private val executor: ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1, EngineTimeoutThreadFactory).apply {
            removeOnCancelPolicy = true
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        }

    override fun schedule(delayMillis: Long, action: () -> Unit): EngineCancellation {
        require(delayMillis >= 0) { "Timeout delay must not be negative" }
        val cancelled = AtomicBoolean(false)
        val future = executor.schedule(
            {
                if (!cancelled.get()) action()
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
        return EngineCancellation {
            if (cancelled.compareAndSet(false, true)) future.cancel(false)
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private object EngineTimeoutThreadFactory : ThreadFactory {
        override fun newThread(task: Runnable): Thread = Thread(
            task,
            "drawless-fairy-timeouts",
        ).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }
}
