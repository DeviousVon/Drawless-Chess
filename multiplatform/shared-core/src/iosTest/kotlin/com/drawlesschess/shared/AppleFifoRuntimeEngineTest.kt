@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package com.drawlesschess.shared

import com.drawlesschess.core.ConcurrentLock
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EnginePurpose
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.PrincipalVariation
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.engine.UciTimeoutScheduler
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import platform.posix.usleep

class AppleFifoRuntimeEngineTest {
    @Test
    fun blockedReviewLaunchDoesNotBlockCallerAndCancelPrecedesBotAnalyze() {
        val events = EventLog()
        val reviewEntered = AtomicBoolean(false)
        val releaseReview = AtomicBoolean(false)
        val botEntered = AtomicBoolean(false)
        val nativeSessionLive = AtomicBoolean(true)
        val delegate = runtimeEngine(
            analyzeBlock = { request, _ ->
                events.add("analyze:${request.purpose.name}")
                if (request.purpose == EnginePurpose.REVIEW) {
                    reviewEntered.store(true)
                    await("review release") { releaseReview.load() }
                } else if (request.purpose == EnginePurpose.BOT_MOVE) {
                    botEntered.store(true)
                }
                EngineCancellation { events.add("cancel:${request.purpose.name}") }
            },
            closeBlock = {
                usleep(75_000u)
                nativeSessionLive.store(false)
                events.add("close")
            },
        )
        val engine = testEngine(delegate)

        val reviewStart = TimeSource.Monotonic.markNow()
        val reviewCancellation = engine.analyze(request("review", EnginePurpose.REVIEW)) {}
        assertTrue(
            reviewStart.elapsedNow().inWholeMilliseconds < CALLER_BUDGET_MILLIS,
            "analyze waited for the worker-side review launch",
        )
        await("review analyze entry") { reviewEntered.load() }

        val cancelStart = TimeSource.Monotonic.markNow()
        reviewCancellation.cancel()
        assertTrue(
            cancelStart.elapsedNow().inWholeMilliseconds < CALLER_BUDGET_MILLIS,
            "cancel waited for the blocked review launch",
        )

        val botStart = TimeSource.Monotonic.markNow()
        engine.analyze(request("bot", EnginePurpose.BOT_MOVE)) {}
        assertTrue(
            botStart.elapsedNow().inWholeMilliseconds < CALLER_BUDGET_MILLIS,
            "bot analyze waited for the blocked review launch",
        )
        assertFalse(botEntered.load(), "bot analyze overtook the queued review cancellation")

        releaseReview.store(true)
        await("bot analyze entry") { botEntered.load() }
        assertEquals(
            listOf("analyze:REVIEW", "cancel:REVIEW", "analyze:BOT_MOVE"),
            events.snapshot().take(3),
        )

        val closeStart = TimeSource.Monotonic.markNow()
        engine.close()
        assertTrue(
            closeStart.elapsedNow().inWholeMilliseconds >= 50L,
            "close returned before the delegate's native-session close completed",
        )
        assertEquals("close", events.snapshot().last())
        assertFalse(nativeSessionLive.load())

        assertTrue(
            nativeSessionLive.compareAndSet(expectedValue = false, newValue = true),
            "a replacement native session was created before queued close completed",
        )
        val replacement = testEngine(
            runtimeEngine(
                analyzeBlock = { _, _ -> EngineCancellation {} },
                closeBlock = { nativeSessionLive.store(false) },
            ),
        )
        replacement.close()
        assertFalse(nativeSessionLive.load())
    }

    @Test
    fun testOnlyReviewHoldObservesCancellationWithinOneSlice() {
        val reviewDelegateEntered = AtomicBoolean(false)
        val botDelegateEntered = AtomicBoolean(false)
        val delegate = runtimeEngine(
            analyzeBlock = { request, _ ->
                if (request.purpose == EnginePurpose.REVIEW) {
                    reviewDelegateEntered.store(true)
                } else if (request.purpose == EnginePurpose.BOT_MOVE) {
                    botDelegateEntered.store(true)
                }
                EngineCancellation {}
            },
        )
        val engine = testEngine(
            delegate = delegate,
            reviewQueueHoldMillis = 10_000L,
            reviewQueueHoldMinimumPly = 0,
        )
        val cancellation = engine.analyze(request("held-review", EnginePurpose.REVIEW)) {}
        await("test review hold") { engine.testingActivity().startsWith("HOLDING_REVIEW") }

        val cancellationStarted = TimeSource.Monotonic.markNow()
        cancellation.cancel()
        engine.analyze(request("bot-after-hold", EnginePurpose.BOT_MOVE)) {}
        await("bot after held review cancellation") { botDelegateEntered.load() }

        assertTrue(
            cancellationStarted.elapsedNow().inWholeMilliseconds < 100L,
            "the test hold did not observe cancellation within its ten-millisecond polling slice",
        )
        assertFalse(reviewDelegateEntered.load(), "cancelled test hold still entered the REVIEW delegate")
        engine.close()
    }

    @Test
    fun successfulBotDeliveryIsPacedAndCancellationStopsUpstreamAndTimer() {
        val timers = ManualScheduler()
        val upstreamCancelled = AtomicBoolean(false)
        val delegate = runtimeEngine(
            analyzeBlock = { request, callback ->
                callback(Result.success(response(request)))
                EngineCancellation { upstreamCancelled.store(true) }
            },
        )
        val engine = testEngine(delegate, scheduler = timers)
        val delivered = AtomicBoolean(false)

        engine.analyze(request("paced-bot", EnginePurpose.BOT_MOVE)) {
            delivered.store(it.isSuccess)
        }
        await("paced timer") { timers.count() == 1 }
        assertFalse(delivered.load())
        assertEquals(500L, timers.delayAt(0))
        timers.fire(0)
        await("paced bot delivery") { delivered.load() }

        val cancelledDelivery = AtomicBoolean(false)
        val cancellation = engine.analyze(request("cancelled-bot", EnginePurpose.BOT_MOVE)) {
            cancelledDelivery.store(true)
        }
        await("second paced timer") { timers.count() == 2 }
        cancellation.cancel()
        await("upstream cancellation") { upstreamCancelled.load() && timers.isCancelled(1) }
        timers.fire(1)
        usleep(20_000u)
        assertFalse(cancelledDelivery.load())
        engine.close()
    }

    @Test
    fun reviewAndFailuresBypassBotPacingAndThrownAnalyzeBecomesFailure() {
        val timers = ManualScheduler()
        val thrown = IllegalStateException("controller rejected request")
        val delegate = runtimeEngine(
            analyzeBlock = { request, callback ->
                when (request.requestId) {
                    "review" -> callback(Result.success(response(request)))
                    "failed-bot" -> callback(Result.failure(thrown))
                    else -> throw thrown
                }
                EngineCancellation {}
            },
        )
        val engine = testEngine(delegate, scheduler = timers)
        val reviewDelivered = AtomicBoolean(false)
        var failedBot: Throwable? = null
        var thrownAnalyze: Throwable? = null
        val resultLock = ConcurrentLock()

        engine.analyze(request("review", EnginePurpose.REVIEW)) {
            reviewDelivered.store(it.isSuccess)
        }
        await("review delivery") { reviewDelivered.load() }
        engine.analyze(request("failed-bot", EnginePurpose.BOT_MOVE)) {
            resultLock.withLock { failedBot = it.exceptionOrNull() }
        }
        await("bot failure delivery") { resultLock.withLock { failedBot != null } }
        engine.analyze(request("thrown", EnginePurpose.HINT)) {
            resultLock.withLock { thrownAnalyze = it.exceptionOrNull() }
        }
        await("thrown analyze delivery") { resultLock.withLock { thrownAnalyze != null } }

        assertSame(thrown, resultLock.withLock { failedBot })
        assertSame(thrown, resultLock.withLock { thrownAnalyze })
        assertEquals(0, timers.count())
        engine.close()
    }

    private fun testEngine(
        delegate: RuntimeChessEngine,
        scheduler: UciTimeoutScheduler = ManualScheduler(),
        reviewQueueHoldMillis: Long = 0L,
        reviewQueueHoldMinimumPly: Int = 0,
    ) = AppleFifoRuntimeEngine(
        delegate = delegate,
        scheduler = scheduler,
        botMoveDelayMillis = 500L,
        reviewQueueHoldMillis = reviewQueueHoldMillis,
        reviewQueueHoldMinimumPly = reviewQueueHoldMinimumPly,
        activityEnabled = true,
        commandQueueLabel = "com.drawlesschess.tests.commands.${nextQueueId()}",
        callbackQueueLabel = "com.drawlesschess.tests.callbacks.${nextQueueId()}",
    )
}

private fun runtimeEngine(
    analyzeBlock: (EngineRequest, (Result<EngineResponse>) -> Unit) -> EngineCancellation,
    closeBlock: () -> Unit = {},
): RuntimeChessEngine = object : RuntimeChessEngine {
    override val reviewEvidenceBuildId: String = "fifo-test"
    override val reviewEvidencePatchVersion: Int = 2

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation = analyzeBlock(request, onResult)

    override fun close() = closeBlock()
}

private class ManualScheduler : UciTimeoutScheduler {
    private data class Task(
        val delayMillis: Long,
        val action: () -> Unit,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private val lock = ConcurrentLock()
    private val tasks = mutableListOf<Task>()

    override fun schedule(delayMillis: Long, action: () -> Unit): EngineCancellation {
        val task = Task(delayMillis, action)
        lock.withLock { tasks += task }
        return EngineCancellation { task.cancelled.store(true) }
    }

    fun count(): Int = lock.withLock { tasks.size }

    fun delayAt(index: Int): Long = lock.withLock { tasks[index].delayMillis }

    fun isCancelled(index: Int): Boolean = lock.withLock { tasks[index].cancelled.load() }

    fun fire(index: Int) {
        val task = lock.withLock { tasks[index] }
        if (!task.cancelled.load()) task.action()
    }
}

private class EventLog {
    private val lock = ConcurrentLock()
    private val events = mutableListOf<String>()

    fun add(event: String) = lock.withLock { events += event }

    fun snapshot(): List<String> = lock.withLock { events.toList() }
}

private fun request(id: String, purpose: EnginePurpose) = EngineRequest(
    requestId = id,
    gameId = "fifo-game",
    positionId = "fifo-game:0:start",
    initialFen = ChessPosition.START_FEN,
    moves = emptyList(),
    rules = RulesContractV1.drawless(),
    strength = EngineStrength.ApproximateElo(800),
    limits = EngineLimits(moveTimeMillis = 100L, multiPv = if (purpose == EnginePurpose.REVIEW) 3 else 1),
    purpose = purpose,
)

private fun response(request: EngineRequest) = EngineResponse(
    requestId = request.requestId,
    gameId = request.gameId,
    positionId = request.positionId,
    bestMove = UciMove("e2e4"),
    ponderMove = null,
    depth = 1,
    nodes = 1,
    variations = listOf(
        PrincipalVariation(
            scoreCentipawns = 0,
            mateIn = null,
            moves = listOf(UciMove("e2e4")),
        ),
    ),
    engine = EngineIdentity("fifo-test", "1", 2),
)

private fun await(description: String, predicate: () -> Boolean) {
    val started = TimeSource.Monotonic.markNow()
    while (!predicate() && started.elapsedNow().inWholeMilliseconds < TEST_TIMEOUT_MILLIS) {
        usleep(1_000u)
    }
    assertTrue(predicate(), "Timed out waiting for $description")
}

private val queueIdLock = ConcurrentLock()
private var queueId = 0

private fun nextQueueId(): Int = queueIdLock.withLock { ++queueId }

private const val CALLER_BUDGET_MILLIS = 100L
private const val TEST_TIMEOUT_MILLIS = 5_000L
