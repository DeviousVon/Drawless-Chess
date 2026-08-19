@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package com.drawlesschess.shared

import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EnginePurpose
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.engine.UciTimeoutScheduler
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.native.Platform
import platform.darwin.dispatch_async_f
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync_f
import platform.posix.getenv
import platform.posix.usleep

/**
 * Keeps every call into the process-global Apple engine on one worker queue.
 *
 * Analyze and cancellation calls deliberately return before the native/controller lock is entered.
 * Queue order therefore preserves REVIEW analyze -> REVIEW cancel -> BOT analyze without making the
 * Swift main actor wait for an in-flight speculative search. Close is the exception: it drains the
 * queue synchronously so a replacement runtime cannot create a second native session too early.
 */
internal class AppleFifoRuntimeEngine(
    private val delegate: RuntimeChessEngine,
    private val scheduler: UciTimeoutScheduler,
    private val botMoveDelayMillis: Long = appleBotMoveDelayMillis(),
    private val reviewQueueHoldMillis: Long = appleReviewQueueHoldMillis(),
    private val reviewQueueHoldMinimumPly: Int = appleReviewQueueHoldMinimumPly(),
    private val activityEnabled: Boolean = appleEngineActivityEnabled(),
    commandQueueLabel: String = "com.drawlesschess.apple-engine.commands",
    callbackQueueLabel: String = "com.drawlesschess.apple-engine.callbacks",
) : RuntimeChessEngine {
    init {
        require(botMoveDelayMillis >= 0L) { "Bot move delay must not be negative" }
        require(reviewQueueHoldMillis >= 0L) { "Review queue hold must not be negative" }
        require(reviewQueueHoldMinimumPly >= 0) { "Review queue hold minimum ply must not be negative" }
    }

    override val reviewEvidenceBuildId: String = delegate.reviewEvidenceBuildId
    override val reviewEvidencePatchVersion: Int = delegate.reviewEvidencePatchVersion

    private val commandQueue = AppleSerialQueue(commandQueueLabel)
    private val callbackQueue = AppleSerialQueue(callbackQueueLabel)
    private val closing = AtomicBoolean(false)
    private val activity = AtomicReference("IDLE")

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        val ticket = AppleQueuedAnalysis(
            request = request,
            scheduler = scheduler,
            botMoveDelayMillis = botMoveDelayMillis,
            enqueueCancellation = commandQueue::async,
            enqueueCallback = callbackQueue::async,
            onResult = onResult,
            updateActivity = { state -> updateActivity(request, state) },
        )
        updateActivity(request, "QUEUED")
        commandQueue.async(
            action = analyzeCommand@{
                if (closing.load()) {
                    ticket.accept(Result.failure(IllegalStateException("Apple engine is closing")))
                    return@analyzeCommand
                }
                if (ticket.isCancelled()) {
                    updateActivity(request, "SKIPPED")
                    return@analyzeCommand
                }

                if (request.purpose == EnginePurpose.REVIEW &&
                    request.moves.size >= reviewQueueHoldMinimumPly &&
                    reviewQueueHoldMillis > 0L) {
                    updateActivity(request, "HOLDING")
                    holdReviewQueue(ticket, reviewQueueHoldMillis)
                    if (ticket.isCancelled()) {
                        updateActivity(request, "SKIPPED")
                        return@analyzeCommand
                    }
                }

                updateActivity(request, "STARTING")
                val upstream = try {
                    delegate.analyze(request, ticket::accept)
                } catch (error: Throwable) {
                    ticket.accept(Result.failure(error))
                    null
                }
                if (upstream != null) ticket.attach(upstream)
                if (!ticket.hasAcceptedResult() && !ticket.isCancelled()) {
                    updateActivity(request, "SEARCHING")
                }
            },
            onFailure = ticket::acceptFailure,
        )
        return ticket
    }

    /**
     * Intentionally synchronous. The native bridge permits one live session per process, so this
     * method must not return until all earlier commands have drained and [delegate] is fully closed.
     */
    override fun close() {
        if (!closing.compareAndSet(expectedValue = false, newValue = true)) return
        setActivity("CLOSING")
        commandQueue.sync {
            delegate.close()
            setActivity("CLOSED")
        }
    }

    override fun testingActivity(): String = if (activityEnabled) activity.load() else "DISABLED"

    private fun updateActivity(request: EngineRequest, state: String) {
        if (activityEnabled) activity.store("${state}_${request.purpose.name}:${request.requestId}")
    }

    private fun setActivity(value: String) {
        if (activityEnabled) activity.store(value)
    }
}

private fun holdReviewQueue(ticket: AppleQueuedAnalysis, requestedMillis: Long) {
    var remainingMillis = requestedMillis.coerceAtMost(MAX_TEST_HOLD_MILLIS)
    while (remainingMillis > 0L && !ticket.isCancelled()) {
        val sliceMillis = remainingMillis.coerceAtMost(TEST_HOLD_SLICE_MILLIS)
        usleep((sliceMillis * 1_000L).toUInt())
        remainingMillis -= sliceMillis
    }
}

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
private class AppleQueuedAnalysis(
    private val request: EngineRequest,
    private val scheduler: UciTimeoutScheduler,
    private val botMoveDelayMillis: Long,
    private val enqueueCancellation: (() -> Unit) -> Unit,
    private val enqueueCallback: (() -> Unit) -> Unit,
    private val onResult: (Result<EngineResponse>) -> Unit,
    private val updateActivity: (String) -> Unit,
) : EngineCancellation {
    private val cancelled = AtomicBoolean(false)
    private val acceptedResult = AtomicBoolean(false)
    private val upstream = AtomicReference<EngineCancellation?>(null)
    private val delayedDelivery = AtomicReference<EngineCancellation?>(null)

    fun isCancelled(): Boolean = cancelled.load()

    fun hasAcceptedResult(): Boolean = acceptedResult.load()

    fun attach(cancellation: EngineCancellation) {
        upstream.store(cancellation)
    }

    fun acceptFailure(error: Throwable) {
        accept(Result.failure(error))
    }

    fun accept(result: Result<EngineResponse>) {
        if (cancelled.load() || !acceptedResult.compareAndSet(expectedValue = false, newValue = true)) return

        if (request.purpose == EnginePurpose.BOT_MOVE && result.isSuccess && botMoveDelayMillis > 0L) {
            updateActivity("PACING")
            val scheduled = try {
                scheduler.schedule(botMoveDelayMillis) {
                    delayedDelivery.exchange(null)
                    deliver(result)
                }
            } catch (_: Throwable) {
                deliver(result)
                return
            }
            delayedDelivery.store(scheduled)
            if (cancelled.load()) delayedDelivery.exchange(null)?.cancel()
            return
        }

        deliver(result)
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(expectedValue = false, newValue = true)) return
        updateActivity("CANCEL_QUEUED")
        enqueueCancellation {
            delayedDelivery.exchange(null)?.cancel()
            upstream.exchange(null)?.cancel()
            updateActivity("CANCELLED")
        }
    }

    private fun deliver(result: Result<EngineResponse>) {
        if (cancelled.load()) return
        enqueueCallback {
            if (!cancelled.load()) {
                updateActivity(if (result.isSuccess) "DELIVERED" else "FAILED")
                onResult(result)
            }
        }
    }
}

/** A tiny libdispatch adapter that never lets Kotlin exceptions cross a C callback boundary. */
internal class AppleSerialQueue(label: String) {
    private val queue = dispatch_queue_create(label, null)

    fun async(action: () -> Unit) = async(action) { _ -> }

    fun async(action: () -> Unit, onFailure: (Throwable) -> Unit) {
        val reference = StableRef.create(AppleQueuedAction(action, onFailure))
        dispatch_async_f(queue, reference.asCPointer(), staticCFunction(::runAppleQueuedAction))
    }

    fun sync(action: () -> Unit) {
        val result = AtomicReference<Result<Unit>?>(null)
        val reference = StableRef.create(
            AppleQueuedAction(
                action = { result.store(runCatching(action)) },
                onFailure = { error -> result.store(Result.failure(error)) },
            ),
        )
        dispatch_sync_f(queue, reference.asCPointer(), staticCFunction(::runAppleQueuedAction))
        requireNotNull(result.load()) { "Apple engine queue did not run its synchronous command" }.getOrThrow()
    }
}

private class AppleQueuedAction(
    private val action: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    fun run() {
        try {
            action()
        } catch (error: Throwable) {
            runCatching { onFailure(error) }
        }
    }
}

private fun runAppleQueuedAction(context: COpaquePointer?) {
    val reference = requireNotNull(context).asStableRef<AppleQueuedAction>()
    try {
        reference.get().run()
    } finally {
        reference.dispose()
    }
}

private fun appleBotMoveDelayMillis(): Long {
    if (!Platform.isDebugBinary) return APPLE_BOT_MOVE_DELAY_MILLIS
    return getenv(TEST_BOT_MOVE_DELAY_ENV)
        ?.toKString()
        ?.toLongOrNull()
        ?.coerceIn(APPLE_BOT_MOVE_DELAY_MILLIS, MAX_TEST_BOT_MOVE_DELAY_MILLIS)
        ?: APPLE_BOT_MOVE_DELAY_MILLIS
}

private fun appleReviewQueueHoldMillis(): Long {
    if (!Platform.isDebugBinary) return 0L
    return getenv(TEST_REVIEW_HOLD_ENV)
        ?.toKString()
        ?.toLongOrNull()
        ?.coerceIn(0L, MAX_TEST_HOLD_MILLIS)
        ?: 0L
}

private fun appleReviewQueueHoldMinimumPly(): Int {
    if (!Platform.isDebugBinary) return 0
    return getenv(TEST_REVIEW_HOLD_MINIMUM_PLY_ENV)
        ?.toKString()
        ?.toIntOrNull()
        ?.coerceAtLeast(0)
        ?: 0
}

private fun appleEngineActivityEnabled(): Boolean =
    Platform.isDebugBinary &&
        (getenv(TEST_ACTIVITY_ENV)?.toKString() == "1" || appleReviewQueueHoldMillis() > 0L)

private const val APPLE_BOT_MOVE_DELAY_MILLIS = SHARED_BOT_MOVE_PRESENTATION_MILLIS
private const val MAX_TEST_BOT_MOVE_DELAY_MILLIS = 10_000L
private const val MAX_TEST_HOLD_MILLIS = 10_000L
private const val TEST_HOLD_SLICE_MILLIS = 10L
private const val TEST_BOT_MOVE_DELAY_ENV = "DRAWLESS_XCTEST_BOT_MOVE_DELAY_MILLIS"
private const val TEST_REVIEW_HOLD_ENV = "DRAWLESS_XCTEST_REVIEW_QUEUE_HOLD_MILLIS"
private const val TEST_REVIEW_HOLD_MINIMUM_PLY_ENV = "DRAWLESS_XCTEST_REVIEW_QUEUE_HOLD_MINIMUM_PLY"
private const val TEST_ACTIVITY_ENV = "DRAWLESS_XCTEST_LATENCY"
