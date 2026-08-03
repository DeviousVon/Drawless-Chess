package com.drawlesschess.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.drawlesschess.BuildConfig
import com.drawlesschess.R
import com.drawlesschess.core.*
import com.drawlesschess.core.chess.*
import com.drawlesschess.core.coordinator.*
import com.drawlesschess.core.presentation.*
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.BotMovePacingEngine
import com.drawlesschess.core.engine.GameReviewProgress
import com.drawlesschess.core.engine.GameReviewPlanner
import com.drawlesschess.core.engine.GameReviewResult
import com.drawlesschess.core.engine.GameReviewRunner
import com.drawlesschess.core.engine.NamedBotLevel
import com.drawlesschess.core.engine.ReviewedMove
import com.drawlesschess.engine.AndroidFairyEngineFactory
import com.drawlesschess.engine.AndroidUciTimeoutScheduler
import com.drawlesschess.review.IsolatedReviewEngine
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SetupSelection(
    val preset: RulesContractV1.Preset = RulesContractV1.Preset.DRAWLESS,
    val deadPosition: DeadPositionPolicy = DeadPositionPolicy.MATERIAL_VICTORY,
    val fiftyMove: FiftyMovePolicy = FiftyMovePolicy.MATERIAL_VICTORY,
    val mode: GameMode = GameMode.CASUAL,
    val timeControl: TimeControl = TimeControl.Untimed,
    val startingColor: StartingColor = StartingColor.RANDOM,
    val botLevel: NamedBotLevel = BotDifficultyCatalog.named("casual"),
) {
    fun rules(): RulesContractV1 = when (preset) {
        RulesContractV1.Preset.DRAWLESS -> RulesContractV1.drawless(deadPosition, fiftyMove)
        RulesContractV1.Preset.ESCAPE -> RulesContractV1.escape(deadPosition, fiftyMove)
    }
}

/**
 * Runtime-owned review state. Keeping the engine job here lets the UI detach during an
 * activity recreation without throwing away analysis that has already completed.
 */
internal sealed interface RuntimeGameReviewState {
    data class Analyzing(
        val progress: GameReviewProgress? = null,
        val partialMoves: Map<Int, ReviewedMove> = emptyMap(),
    ) : RuntimeGameReviewState
    data class Complete(val result: GameReviewResult) : RuntimeGameReviewState
    data class Cancelled(
        val progress: GameReviewProgress? = null,
        val partialMoves: Map<Int, ReviewedMove> = emptyMap(),
    ) : RuntimeGameReviewState
    data class Failed(
        val error: Throwable,
        val partialMoves: Map<Int, ReviewedMove> = emptyMap(),
    ) : RuntimeGameReviewState
}

internal data class RuntimeReviewPrefetchDiagnostics(
    val rootCandidateMovesByPly: Map<Int, Set<UciMove>>,
    val adjacentFallbackPlies: Set<Int>,
)

class GameRuntime private constructor(
    private val config: GameConfig,
    checkpoint: CoordinatorCheckpoint?,
    applicationContext: Context,
    checkpointSink: CheckpointSink,
    initialTheme: BoardTheme,
    threatIndicationEnabled: Boolean,
) : AutoCloseable {
    internal val gameId: String get() = config.gameId

    private val uiContext = applicationContext.applicationContext
    private val restorableCheckpoint = checkpoint?.withCompatibleReviewPrefetchEvidence()
    private val closed = AtomicBoolean(false)
    private val modelInvalidationListeners = CopyOnWriteArraySet<() -> Unit>()
    private val engineProvision = provisionEngine(uiContext)
    private val movePacingScheduler = AndroidUciTimeoutScheduler()
    private val engine = BotMovePacingEngine(
        delegate = engineProvision.engine,
        scheduler = movePacingScheduler,
        delayMillis = GamePacing.OPPONENT_MOVE_DELAY_MILLIS,
    )
    private val reviewEngine = IsolatedReviewEngine(uiContext)
    private val reviewLock = Any()
    private val reviewPreparationExecutor = Executors.newSingleThreadExecutor()
    private val reviewPrefetchEngineSubmissions = AtomicInteger(0)
    private val reviewEngineSubmissions = AtomicInteger(0)
    private val coordinatorReviewEngine = object : ChessEngine {
        override fun analyze(
            request: EngineRequest,
            onResult: (Result<EngineResponse>) -> Unit,
        ): EngineCancellation {
            reviewPrefetchEngineSubmissions.incrementAndGet()
            return reviewEngine.analyze(request, onResult)
        }
    }
    private val reviewRunner = GameReviewRunner(
        object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                reviewEngineSubmissions.incrementAndGet()
                return reviewEngine.analyze(request, onResult)
            }
        },
    )
    private var reviewGeneration = 0L
    private var activeReviewCancellation: EngineCancellation? = null
    private val reviewState = MutableStateFlow<RuntimeGameReviewState?>(null)
    internal val opponentLevel: NamedBotLevel = if (
        config.opponentLevelId == BotDifficultyCatalog.ADAPTIVE_LEVEL_ID
    ) {
        BotDifficultyCatalog.adaptiveLevel(
            (config.engineStrength as? EngineStrength.ApproximateElo)?.elo
                ?: BotDifficultyCatalog.ADAPTIVE_STARTING_ELO,
        )
    } else {
        BotDifficultyCatalog.displayLevel(
            explicitLevelId = config.opponentLevelId,
            strength = config.engineStrength,
        )
    }
    private val timeSource = CoordinatorTimeSource {
        TimeReading(SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }
    private val idSource = CoordinatorIdSource { UUID.randomUUID().toString() }
    private val coordinator = try {
        if (restorableCheckpoint == null) {
            GameCoordinator.newGame(
                config,
                engine,
                checkpointSink,
                timeSource,
                idSource,
                botMovePresentationDelayMillis = GamePacing.PIECE_MOVE_ANIMATION_MILLIS.toLong(),
                initialAssistance = AssistanceCounts(
                    threatIndication = threatIndicationEnabled,
                ),
                reviewEngine = coordinatorReviewEngine,
            )
        } else {
            GameCoordinator.restore(
                restorableCheckpoint,
                engine,
                checkpointSink,
                timeSource,
                idSource,
                botMovePresentationDelayMillis = GamePacing.PIECE_MOVE_ANIMATION_MILLIS.toLong(),
                reviewEngine = coordinatorReviewEngine,
            )
        }
    } catch (error: Throwable) {
        runCatching { reviewPreparationExecutor.shutdownNow() }
        runCatching { reviewEngine.close() }
        runCatching { engineProvision.engine.close() }
        runCatching { movePacingScheduler.close() }
        throw error
    }

    constructor(
        selection: SetupSelection,
        applicationContext: Context,
        checkpointSink: CheckpointSink,
        initialTheme: BoardTheme = BoardThemes.DEFAULT,
        resolvedHumanSide: Side = selection.startingColor.resolve(),
        threatIndicationEnabled: Boolean = false,
        adaptiveElo: Int = BotDifficultyCatalog.ADAPTIVE_STARTING_ELO,
    ) : this(
        selection.gameConfig(resolvedHumanSide, adaptiveElo),
        null,
        applicationContext,
        checkpointSink,
        initialTheme,
        threatIndicationEnabled,
    )

    constructor(
        checkpoint: CoordinatorCheckpoint,
        applicationContext: Context,
        checkpointSink: CheckpointSink,
        initialTheme: BoardTheme = BoardThemes.DEFAULT,
    ) : this(
        checkpoint.config,
        checkpoint,
        applicationContext,
        checkpointSink,
        initialTheme,
        checkpoint.assistance.threatIndication,
    )

    val controller: GameScreenController

    init {
        lateinit var createdController: GameScreenController
        createdController = GameScreenController(
            coordinator = coordinator,
            config = config,
            initialTheme = initialTheme,
            threatIndicationEnabled = threatIndicationEnabled,
            onEffect = { effect: GameUiEffect ->
                if (effect is GameUiEffect.RequestHintAnalysis) {
                    Log.d(HINT_LOG_TAG, "Submitting coordinator-owned hint analysis")
                    coordinator.requestHint(effect.positionId) { result ->
                        if (closed.get()) return@requestHint
                        Log.d(HINT_LOG_TAG, "Hint analysis completed success=${result.isSuccess}")
                        runCatching {
                            result.fold(
                                onSuccess = { response ->
                                    createdController.showHint(
                                        message = hintMessage(uiContext, effect.currentFen, response),
                                        move = response.bestMove,
                                    )
                                },
                                onFailure = {
                                    createdController.showMessage(uiContext.getString(R.string.hint_unavailable))
                                },
                            )
                        }.onFailure { error ->
                            Log.e(HINT_LOG_TAG, "Hint result could not be presented", error)
                            createdController.showMessage(uiContext.getString(R.string.hint_unavailable))
                        }
                        publishModelInvalidation()
                        Log.d(HINT_LOG_TAG, "Hint message published")
                    }
                    Log.d(HINT_LOG_TAG, "Coordinator accepted hint analysis")
                }
            },
        )
        controller = createdController
        coordinator.start()
        engineProvision.startupMessage?.let(createdController::showMessage)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            modelInvalidationListeners.clear()
            val reviewCancellation = synchronized(reviewLock) {
                reviewGeneration++
                activeReviewCancellation.also { activeReviewCancellation = null }
            }
            runCatching { reviewCancellation?.cancel() }
            reviewPreparationExecutor.shutdownNow()
            runCatching { coordinator.close() }
            runCatching { reviewEngine.close() }
            runCatching { engineProvision.engine.close() }
            runCatching { movePacingScheduler.close() }
        }
    }

    internal fun reviewCheckpoint(): CoordinatorCheckpoint = coordinator.checkpoint().also {
        require(it.outcome != null) { "Game review is available only after a completed game" }
    }

    /** Keeps isolated speculative review work aligned with the visible game lifecycle. */
    internal fun setGameForeground(foreground: Boolean) {
        if (closed.get()) return
        coordinator.setReviewPrefetchEnabled(foreground)
    }

    internal fun gameReviewState(): StateFlow<RuntimeGameReviewState?> {
        check(!closed.get()) { "Game runtime is closed" }
        val checkpoint = reviewCheckpoint()
        var generationToStart: Long? = null
        synchronized(reviewLock) {
            check(!closed.get()) { "Game runtime is closed" }
            if (reviewState.value == null) {
                reviewState.value = RuntimeGameReviewState.Analyzing()
                generationToStart = ++reviewGeneration
            }
        }
        generationToStart?.let { generation -> scheduleGameReview(checkpoint, generation) }
        return reviewState.asStateFlow()
    }

    /** Starts final review work behind the result presentation, before the user opens Review. */
    internal fun prepareGameReview() {
        gameReviewState()
    }

    internal fun currentGameReviewState(): RuntimeGameReviewState? = reviewState.value

    /** Counts only non-seeded engine work submitted by the final-review runner. */
    internal fun reviewEngineSubmissionCount(): Int = reviewEngineSubmissions.get()

    /** Counts isolated-engine work submitted by in-game speculative review prefetch. */
    internal fun reviewPrefetchEngineSubmissionCount(): Int = reviewPrefetchEngineSubmissions.get()

    internal fun reviewPrefetchDiagnostics(): RuntimeReviewPrefetchDiagnostics =
        RuntimeReviewPrefetchDiagnostics(
            rootCandidateMovesByPly = coordinator.completedReviewPrefetchRoots().associate { seed ->
                seed.key.ply to seed.response.variations.mapNotNullTo(linkedSetOf()) { variation ->
                    variation.moves.firstOrNull()
                }
            },
            adjacentFallbackPlies = coordinator.completedReviewPrefetchAdjacentRoots()
                .mapTo(linkedSetOf()) { seed -> seed.key.rootKey.ply },
        )

    internal fun cancelGameReview() {
        val cancellation: EngineCancellation?
        synchronized(reviewLock) {
            val current = reviewState.value as? RuntimeGameReviewState.Analyzing ?: return
            reviewGeneration++
            cancellation = activeReviewCancellation
            activeReviewCancellation = null
            reviewState.value = RuntimeGameReviewState.Cancelled(
                progress = current.progress,
                partialMoves = current.partialMoves,
            )
        }
        runCatching { cancellation?.cancel() }
    }

    internal fun restartGameReview() {
        check(!closed.get()) { "Game runtime is closed" }
        val checkpoint = reviewCheckpoint()
        val previous: EngineCancellation?
        val generation: Long
        synchronized(reviewLock) {
            check(!closed.get()) { "Game runtime is closed" }
            generation = ++reviewGeneration
            previous = activeReviewCancellation
            activeReviewCancellation = null
            reviewState.value = RuntimeGameReviewState.Analyzing()
        }
        runCatching { previous?.cancel() }
        scheduleGameReview(checkpoint, generation)
    }

    private fun scheduleGameReview(checkpoint: CoordinatorCheckpoint, generation: Long) {
        try {
            reviewPreparationExecutor.execute { startGameReview(checkpoint, generation) }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            synchronized(reviewLock) {
                if (!closed.get() && generation == reviewGeneration &&
                    reviewState.value is RuntimeGameReviewState.Analyzing
                ) {
                    reviewState.value = RuntimeGameReviewState.Failed(error)
                }
            }
        }
    }

    private fun startGameReview(checkpoint: CoordinatorCheckpoint, generation: Long) {
        fun isCurrentAttempt() = synchronized(reviewLock) {
            !closed.get() && generation == reviewGeneration &&
                reviewState.value is RuntimeGameReviewState.Analyzing
        }
        if (!isCurrentAttempt()) return

        val completedSynchronously = AtomicBoolean(false)
        val cancellation = try {
            val plan = GameReviewPlanner.playerPlan(
                gameId = checkpoint.config.gameId,
                initialFen = checkpoint.config.initialFen,
                moves = checkpoint.moves,
                rules = checkpoint.config.rules,
                playerSide = checkpoint.config.humanSide,
            )
            val prefetchedByKey = coordinator.completedReviewPrefetchRoots().associateBy { it.key }
            val compatibleSeeds = plan.roots.mapNotNull { root -> prefetchedByKey[root.key] }
            val prefetchedAdjacentByRootAndMove = coordinator.completedReviewPrefetchAdjacentRoots()
                .associateBy { seed -> seed.key.rootKey to seed.key.playedMove }
            val compatibleAdjacentSeeds = plan.roots.mapNotNull { root ->
                val playedMove = plan.gameMoves[root.ply - 1]
                prefetchedAdjacentByRootAndMove[root.key to playedMove]
            }
            Log.d(
                REVIEW_LOG_TAG,
                "Starting player review roots=${plan.roots.size} " +
                    "seededRoots=${compatibleSeeds.size} " +
                    "seededAdjacent=${compatibleAdjacentSeeds.size}",
            )
            if (!isCurrentAttempt()) return
            reviewRunner.reviewPlayerMoves(
                gameId = checkpoint.config.gameId,
                initialFen = checkpoint.config.initialFen,
                moves = checkpoint.moves,
                rules = checkpoint.config.rules,
                outcome = requireNotNull(checkpoint.outcome),
                playerSide = checkpoint.config.humanSide,
                preparedPlan = plan,
                seededRoots = compatibleSeeds,
                seededAdjacentRoots = compatibleAdjacentSeeds,
                onMoveReviewed = { completedMove ->
                    synchronized(reviewLock) {
                        val current = reviewState.value as? RuntimeGameReviewState.Analyzing
                        if (!closed.get() && generation == reviewGeneration && current != null) {
                            reviewState.value = current.copy(
                                partialMoves = current.partialMoves +
                                    (completedMove.move.ply to completedMove.move),
                            )
                        }
                    }
                },
                onProgress = { progress ->
                    synchronized(reviewLock) {
                        val current = reviewState.value as? RuntimeGameReviewState.Analyzing
                        if (!closed.get() && generation == reviewGeneration && current != null) {
                            reviewState.value = current.copy(progress = progress)
                        }
                    }
                },
                onResult = { result ->
                    completedSynchronously.set(true)
                    synchronized(reviewLock) {
                        if (!closed.get() && generation == reviewGeneration) {
                            activeReviewCancellation = null
                            reviewState.value = result.fold(
                                onSuccess = { RuntimeGameReviewState.Complete(it) },
                                onFailure = { error ->
                                    Log.e(REVIEW_LOG_TAG, "Game review failed", error)
                                    RuntimeGameReviewState.Failed(
                                        error = error,
                                        partialMoves = (
                                            reviewState.value as? RuntimeGameReviewState.Analyzing
                                        )?.partialMoves.orEmpty(),
                                    )
                                },
                            )
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            completedSynchronously.set(true)
            synchronized(reviewLock) {
                if (!closed.get() && generation == reviewGeneration) {
                    activeReviewCancellation = null
                    Log.e(REVIEW_LOG_TAG, "Game review could not start", error)
                    reviewState.value = RuntimeGameReviewState.Failed(
                        error = error,
                        partialMoves = (
                            reviewState.value as? RuntimeGameReviewState.Analyzing
                        )?.partialMoves.orEmpty(),
                    )
                }
            }
            return
        }
        val cancelImmediately = synchronized(reviewLock) {
            if (closed.get() || generation != reviewGeneration || completedSynchronously.get() ||
                reviewState.value !is RuntimeGameReviewState.Analyzing
            ) {
                true
            } else {
                activeReviewCancellation = cancellation
                false
            }
        }
        if (cancelImmediately) cancellation.cancel()
    }

    /**
     * Async engine callbacks do not themselves mutate Compose state. GameRoute registers here
     * so a completed hint refreshes the screen even when the coordinator phase changed between
     * two polling frames.
     */
    internal fun addModelInvalidationListener(listener: () -> Unit): AutoCloseable {
        if (closed.get()) return AutoCloseable {}
        modelInvalidationListeners += listener
        if (closed.get() && modelInvalidationListeners.remove(listener)) return AutoCloseable {}
        return AutoCloseable { modelInvalidationListeners.remove(listener) }
    }

    private fun publishModelInvalidation() {
        modelInvalidationListeners.forEach { listener -> runCatching(listener) }
    }

    private fun provisionEngine(context: Context): EngineProvision {
        if (BuildConfig.USE_DEVELOPMENT_ENGINE) {
            Log.w(ENGINE_LOG_TAG, "Explicit development chess engine is enabled")
            return EngineProvision(
                DevelopmentChessEngine(),
                context.getString(R.string.engine_development_enabled),
            )
        }

        return try {
            val session = AndroidFairyEngineFactory(
                context = context,
                diagnosticSink = { line -> Log.d(ENGINE_LOG_TAG, line) },
            ).create()
            EngineProvision(ManagedChessEngine(session, session::close))
        } catch (error: Exception) {
            failedEngineProvision(error)
        } catch (error: LinkageError) {
            failedEngineProvision(error)
        }
    }

    private fun failedEngineProvision(error: Throwable): EngineProvision {
        Log.e(ENGINE_LOG_TAG, "Native chess engine startup failed", error)
        return EngineProvision(
            FailedChessEngine(error),
            uiContext.getString(R.string.engine_start_failed),
        )
    }

    private companion object {
        const val ENGINE_LOG_TAG = "DrawlessChessEngine"
        const val HINT_LOG_TAG = "DrawlessChessHint"
        const val REVIEW_LOG_TAG = "DrawlessChessReview"
    }
}

private fun CoordinatorCheckpoint.withCompatibleReviewPrefetchEvidence(): CoordinatorCheckpoint {
    val roots = reviewPrefetchRoots.filter { seed ->
        AndroidFairyEngineFactory.acceptsPersistedReviewEvidence(seed.response.engine)
    }
    val adjacentRoots = reviewPrefetchAdjacentRoots.filter { seed ->
        AndroidFairyEngineFactory.acceptsPersistedReviewEvidence(seed.response.engine)
    }
    if (roots.size == reviewPrefetchRoots.size &&
        adjacentRoots.size == reviewPrefetchAdjacentRoots.size
    ) {
        return this
    }
    Log.i(
        "DrawlessChessReview",
        "Discarding review prefetch evidence from an incompatible embedded engine build",
    )
    return copy(
        reviewPrefetchRoots = roots,
        reviewPrefetchAdjacentRoots = adjacentRoots,
    )
}

private fun hintMessage(context: Context, fen: String, response: EngineResponse): String {
    val position = ChessPosition.fromFen(fen)
    val best = SanNotation.format(position, response.bestMove)
    val alternatives = response.variations
        .sortedBy { it.rank }
        .mapNotNull { variation -> variation.moves.firstOrNull() }
        .filter { it != response.bestMove }
        .distinct()
        .mapNotNull { move -> runCatching { SanNotation.format(position, move) }.getOrNull() }
        .take(2)
    return if (alternatives.isEmpty()) {
        context.getString(R.string.hint_suggestion, best)
    } else {
        context.getString(R.string.hint_suggestion_alternatives, best, alternatives.joinToString(", "))
    }
}

private data class EngineProvision(
    val engine: ManagedChessEngineDelegate,
    val startupMessage: String? = null,
)

private class ManagedChessEngine(
    private val delegate: ChessEngine,
    private val closeDelegate: () -> Unit,
) : ManagedChessEngineDelegate {
    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation = delegate.analyze(request, onResult)

    override fun close() = closeDelegate()
}

private class FailedChessEngine(
    private val startupFailure: Throwable,
) : ManagedChessEngineDelegate {
    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        onResult(Result.failure(startupFailure))
        return EngineCancellation {}
    }

    override fun close() = Unit
}

private interface ManagedChessEngineDelegate : ChessEngine, AutoCloseable

/** Explicit debug-only fallback selected with -Pdrawless.useDevelopmentEngine=true. */
private class DevelopmentChessEngine : ManagedChessEngineDelegate {
    private val executor = Executors.newSingleThreadExecutor()

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        val cancelled = AtomicBoolean(false)
        executor.execute {
            runCatching {
                val position = ChessAdapter.replay(request.initialFen, request.moves)
                val legal = ChessRules.legalUciMoves(position)
                check(legal.isNotEmpty()) { "No legal engine move" }
                val index = Math.floorMod(request.requestId.hashCode(), legal.size)
                val move = legal[index]
                EngineResponse(
                    request.requestId,
                    request.gameId,
                    request.positionId,
                    move,
                    null,
                    depth = 1,
                    nodes = legal.size.toLong(),
                    variations = listOf(PrincipalVariation(0, null, listOf(move))),
                    engine = EngineIdentity("development-engine", "1", 0),
                )
            }.also { result ->
                if (!cancelled.get()) onResult(result)
            }
        }
        return EngineCancellation { cancelled.set(true) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

private fun SetupSelection.gameConfig(resolvedHumanSide: Side, adaptiveElo: Int): GameConfig = GameConfig(
    gameId = UUID.randomUUID().toString(),
    initialFen = ChessPosition.START_FEN,
    rules = rules(),
    mode = mode,
    timeControl = timeControl,
    humanSide = resolvedHumanSide,
    engineStrength = EngineStrength.ApproximateElo(
        if (botLevel.id == BotDifficultyCatalog.ADAPTIVE_LEVEL_ID) {
            BotDifficultyCatalog.clampElo(adaptiveElo)
        } else {
            botLevel.approximateElo
        },
    ),
    engineLimits = EngineLimits(moveTimeMillis = 350),
    opponentLevelId = botLevel.id,
)
