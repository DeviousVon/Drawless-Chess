package com.drawlesschess.core.engine

import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EnginePurpose
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Delays successful bot moves without changing engine search strength or delaying other work. */
class BotMovePacingEngine(
    private val delegate: ChessEngine,
    private val scheduler: UciTimeoutScheduler,
    private val delayMillis: Long,
) : ChessEngine {
    init {
        require(delayMillis >= 0) { "Bot move delay must not be negative" }
    }

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        val cancelled = AtomicBoolean(false)
        val acceptedResult = AtomicBoolean(false)
        val delayedDelivery = AtomicReference<EngineCancellation?>(null)

        val upstream = try {
            delegate.analyze(request) resultHandler@{ result ->
                if (cancelled.get() || !acceptedResult.compareAndSet(false, true)) return@resultHandler

                if (request.purpose != EnginePurpose.BOT_MOVE || result.isFailure || delayMillis == 0L) {
                    if (!cancelled.get()) onResult(result)
                    return@resultHandler
                }

                val scheduled = try {
                    scheduler.schedule(delayMillis) {
                        if (!cancelled.get()) onResult(result)
                    }
                } catch (_: Throwable) {
                    // Pacing is presentation-only. Keep the valid engine move if the timer is unavailable.
                    if (!cancelled.get()) onResult(result)
                    return@resultHandler
                }
                delayedDelivery.set(scheduled)
                if (cancelled.get()) delayedDelivery.getAndSet(null)?.cancel()
            }
        } catch (error: Throwable) {
            delayedDelivery.getAndSet(null)?.cancel()
            throw error
        }

        return EngineCancellation {
            if (cancelled.compareAndSet(false, true)) {
                upstream.cancel()
                delayedDelivery.getAndSet(null)?.cancel()
            }
        }
    }
}
