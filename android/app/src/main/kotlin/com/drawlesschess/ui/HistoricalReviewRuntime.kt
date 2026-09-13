package com.drawlesschess.ui

import android.content.Context
import android.util.Log
import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.engine.GameReviewResult
import com.drawlesschess.core.engine.GameReviewRunner
import com.drawlesschess.persistence.CompletedGameHistory
import com.drawlesschess.review.IsolatedReviewEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns analysis for a completed history row without reconstructing a playable game runtime. */
internal class HistoricalReviewRuntime(
    applicationContext: Context,
    val game: CompletedGameHistory,
    cachedReview: GameReviewResult?,
    private val saveCompletedReview: (
        GameReviewResult,
        (Result<Unit>) -> Unit,
    ) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val engine = IsolatedReviewEngine(applicationContext.applicationContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val runner = GameReviewRunner(object : ChessEngine {
        override fun analyze(
            request: EngineRequest,
            onResult: (Result<EngineResponse>) -> Unit,
        ): EngineCancellation = engine.analyze(request, onResult)
    })
    private val state = MutableStateFlow<RuntimeGameReviewState?>(
        cachedReview?.let(RuntimeGameReviewState::Complete),
    )
    private var generation = 0L
    private var cancellation: EngineCancellation? = null

    fun gameReviewState(): StateFlow<RuntimeGameReviewState?> {
        var startGeneration: Long? = null
        synchronized(lock) {
            check(!closed.get()) { "Historical Review runtime is closed" }
            if (state.value == null) {
                state.value = RuntimeGameReviewState.Analyzing()
                startGeneration = ++generation
            }
        }
        startGeneration?.let(::schedule)
        return state.asStateFlow()
    }

    fun cancelGameReview() {
        val toCancel = synchronized(lock) {
            val current = state.value as? RuntimeGameReviewState.Analyzing ?: return
            generation++
            cancellation.also {
                cancellation = null
                state.value = RuntimeGameReviewState.Cancelled(
                    progress = current.progress,
                    partialMoves = current.partialMoves,
                )
            }
        }
        runCatching { toCancel?.cancel() }
    }

    fun restartGameReview() {
        val previous: EngineCancellation?
        val nextGeneration: Long
        synchronized(lock) {
            check(!closed.get()) { "Historical Review runtime is closed" }
            previous = cancellation
            cancellation = null
            nextGeneration = ++generation
            state.value = RuntimeGameReviewState.Analyzing()
        }
        runCatching { previous?.cancel() }
        schedule(nextGeneration)
    }

    private fun schedule(attempt: Long) {
        try {
            executor.execute { start(attempt) }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            failIfCurrent(attempt, error)
        }
    }

    private fun start(attempt: Long) {
        if (!isCurrent(attempt)) return
        val completedSynchronously = AtomicBoolean(false)
        val submitted = try {
            runner.reviewPlayerMoves(
                gameId = game.gameId,
                initialFen = game.initialFen,
                moves = game.moves,
                rules = game.rules,
                outcome = game.outcome,
                playerSide = game.playerSide,
                onMoveReviewed = { completedMove ->
                    synchronized(lock) {
                        val current = state.value as? RuntimeGameReviewState.Analyzing
                        if (isCurrentLocked(attempt) && current != null) {
                            state.value = current.copy(
                                partialMoves = current.partialMoves +
                                    (completedMove.move.ply to completedMove.move),
                            )
                        }
                    }
                },
                onProgress = { progress ->
                    synchronized(lock) {
                        val current = state.value as? RuntimeGameReviewState.Analyzing
                        if (isCurrentLocked(attempt) && current != null) {
                            state.value = current.copy(progress = progress)
                        }
                    }
                },
                onResult = { result ->
                    completedSynchronously.set(true)
                    val review = result.getOrNull()
                    if (review == null) {
                        failIfCurrent(
                            attempt,
                            result.exceptionOrNull()
                                ?: IllegalStateException("Historical Review failed without an error"),
                        )
                        return@reviewPlayerMoves
                    }
                    val shouldSave = synchronized(lock) {
                        if (isCurrentLocked(attempt)) {
                            cancellation = null
                            true
                        } else false
                    }
                    if (shouldSave) {
                        saveCompletedReview(review) { saveResult ->
                            saveResult.fold(
                                onSuccess = {
                                    synchronized(lock) {
                                        if (isCurrentLocked(attempt)) {
                                            state.value = RuntimeGameReviewState.Complete(review)
                                        }
                                    }
                                },
                                onFailure = { error -> failIfCurrent(attempt, error, review) },
                            )
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            completedSynchronously.set(true)
            failIfCurrent(attempt, error)
            return
        }
        val cancelNow = synchronized(lock) {
            if (!isCurrentLocked(attempt) || completedSynchronously.get() ||
                state.value !is RuntimeGameReviewState.Analyzing
            ) {
                true
            } else {
                cancellation = submitted
                false
            }
        }
        if (cancelNow) submitted.cancel()
    }

    private fun isCurrent(attempt: Long): Boolean = synchronized(lock) {
        isCurrentLocked(attempt) && state.value is RuntimeGameReviewState.Analyzing
    }

    private fun isCurrentLocked(attempt: Long): Boolean =
        !closed.get() && generation == attempt

    private fun failIfCurrent(
        attempt: Long,
        error: Throwable,
        completed: GameReviewResult? = null,
    ) {
        synchronized(lock) {
            if (!isCurrentLocked(attempt)) return
            cancellation = null
            Log.e(LOG_TAG, "Historical Review failed", error)
            state.value = RuntimeGameReviewState.Failed(
                error = error,
                partialMoves = completed?.moves?.associateBy { it.ply }
                    ?: (state.value as? RuntimeGameReviewState.Analyzing)?.partialMoves.orEmpty(),
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = synchronized(lock) {
            generation++
            cancellation.also { cancellation = null }
        }
        runCatching { active?.cancel() }
        executor.shutdownNow()
        runCatching { engine.close() }
    }

    private companion object {
        const val LOG_TAG = "DrawlessHistoryReview"
    }
}
