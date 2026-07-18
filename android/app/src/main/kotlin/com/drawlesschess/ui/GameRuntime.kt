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
import com.drawlesschess.core.engine.NamedBotLevel
import com.drawlesschess.engine.AndroidFairyEngineFactory
import com.drawlesschess.engine.AndroidUciTimeoutScheduler
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private val closed = AtomicBoolean(false)
    private val modelInvalidationListeners = CopyOnWriteArraySet<() -> Unit>()
    private val engineProvision = provisionEngine(uiContext)
    private val movePacingScheduler = AndroidUciTimeoutScheduler()
    private val engine = BotMovePacingEngine(
        delegate = engineProvision.engine,
        scheduler = movePacingScheduler,
        delayMillis = GamePacing.OPPONENT_MOVE_DELAY_MILLIS,
    )
    internal val opponentLevel: NamedBotLevel = BotDifficultyCatalog.displayLevel(
        explicitLevelId = config.opponentLevelId,
        strength = config.engineStrength,
    )
    private val timeSource = CoordinatorTimeSource {
        TimeReading(SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }
    private val idSource = CoordinatorIdSource { UUID.randomUUID().toString() }
    private val coordinator = try {
        if (checkpoint == null) {
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
            )
        } else {
            GameCoordinator.restore(
                checkpoint,
                engine,
                checkpointSink,
                timeSource,
                idSource,
                botMovePresentationDelayMillis = GamePacing.PIECE_MOVE_ANIMATION_MILLIS.toLong(),
            )
        }
    } catch (error: Throwable) {
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
    ) : this(
        selection.gameConfig(resolvedHumanSide),
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
                        val message = runCatching {
                            result.fold(
                                onSuccess = { response -> hintMessage(uiContext, effect.currentFen, response) },
                                onFailure = { uiContext.getString(R.string.hint_unavailable) },
                            )
                        }.getOrElse { error ->
                            Log.e(HINT_LOG_TAG, "Hint result could not be presented", error)
                            uiContext.getString(R.string.hint_unavailable)
                        }
                        createdController.showMessage(message)
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
            runCatching { coordinator.close() }
            runCatching { engineProvision.engine.close() }
            runCatching { movePacingScheduler.close() }
        }
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
    }
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

private fun SetupSelection.gameConfig(resolvedHumanSide: Side): GameConfig = GameConfig(
    gameId = UUID.randomUUID().toString(),
    initialFen = ChessPosition.START_FEN,
    rules = rules(),
    mode = mode,
    timeControl = timeControl,
    humanSide = resolvedHumanSide,
    engineStrength = EngineStrength.ApproximateElo(botLevel.approximateElo),
    engineLimits = EngineLimits(moveTimeMillis = 350),
    opponentLevelId = botLevel.id,
)
