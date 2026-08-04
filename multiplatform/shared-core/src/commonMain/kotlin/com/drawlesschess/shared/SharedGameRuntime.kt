package com.drawlesschess.shared

import com.drawlesschess.core.AssistanceCounts
import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.ConcurrentLock
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.GameScoring
import com.drawlesschess.core.PrincipalVariation
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.TimeControl
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.Piece
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.coordinator.CheckpointSink
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.CoordinatorIdSource
import com.drawlesschess.core.coordinator.CoordinatorPhase
import com.drawlesschess.core.coordinator.CoordinatorTimeSource
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.coordinator.GameCoordinator
import com.drawlesschess.core.coordinator.TimeReading
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.GameReviewProgress
import com.drawlesschess.core.engine.GameReviewPlanner
import com.drawlesschess.core.engine.GameReviewResult
import com.drawlesschess.core.engine.GameReviewRunner
import com.drawlesschess.core.engine.ReviewEvaluation
import com.drawlesschess.core.engine.ReviewMoveQuality
import com.drawlesschess.core.engine.ReviewedMove
import com.drawlesschess.core.presentation.BoardAction
import com.drawlesschess.core.presentation.BoardEvent
import com.drawlesschess.core.presentation.BoardInteractionContext
import com.drawlesschess.core.presentation.BoardInteractionReducer
import com.drawlesschess.core.presentation.BoardInteractionState
import com.drawlesschess.core.presentation.BoardMoveArrow
import com.drawlesschess.core.presentation.BoardPresenter
import com.drawlesschess.core.presentation.BoardThemes
import com.drawlesschess.core.presentation.GameHistoryPresenter
import com.drawlesschess.core.presentation.PieceSets
import com.drawlesschess.core.presentation.TargetKind
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.random.Random

/** Swift-friendly projection of one board square in display order. */
data class SharedBoardCell(
    val displayIndex: Int,
    val square: String,
    val pieceSymbol: String,
    val pieceCode: String,
    val darkSquare: Boolean,
    val selected: Boolean,
    val legalTarget: Boolean,
    val captureTarget: Boolean,
    val lastMove: Boolean,
    val inCheck: Boolean,
    val threatened: Boolean,
    val accessibilityLabel: String,
)

/** Player-only RC1 review evidence projected into stable Swift-friendly primitives. */
data class SharedReviewMove(
    val ply: Int,
    val mover: String,
    val playedMove: String,
    val bestMove: String,
    val quality: String?,
    val expectedPointLoss: Double?,
    val bestEvaluationKind: String?,
    val bestEvaluationValue: Int?,
    val bestEvaluationText: String?,
    val playedEvaluationKind: String?,
    val playedEvaluationValue: Int?,
    val playedEvaluationText: String?,
    val suggestedLine: List<String>,
    val fenBefore: String,
    val fenAfter: String,
)

/** Stable, platform-neutral state consumed by the native iOS view model. */
data class SharedGameView(
    val gameId: String,
    val presetId: String,
    val opponentLevelId: String,
    val opponentElo: Int,
    val initialMillis: Long,
    val incrementMillis: Long,
    val boardThemeId: String,
    val lightSquareArgb: Long,
    val darkSquareArgb: Long,
    val selectedArgb: Long,
    val legalMoveArgb: Long,
    val legalCaptureArgb: Long,
    val lastMoveArgb: Long,
    val checkArgb: Long,
    val cells: List<SharedBoardCell>,
    val phase: String,
    val statusText: String,
    val humanSide: String,
    val sideToMove: String,
    val plyCount: Int,
    val moveHistory: String,
    val lastMoveNotation: String?,
    val lastMoveEnPassant: Boolean,
    val whiteRemainingMillis: Long,
    val blackRemainingMillis: Long,
    val canPause: Boolean,
    val canResume: Boolean,
    val canUndo: Boolean,
    val canHint: Boolean,
    val canResign: Boolean,
    val promotionChoices: List<String>,
    val winner: String?,
    val endReason: String?,
    val score: Int,
    val scoreMaximumPoints: Int,
    val hintPenalty: Int,
    val undoPenalty: Int,
    val pausePenalty: Int,
    val threatPenalty: Int,
    val hintCount: Int,
    val undoCount: Int,
    val pauseCount: Int,
    val threatIndicationEnabled: Boolean,
    val hintMove: String?,
    val hintFromSquare: String?,
    val hintToSquare: String?,
    val engineError: String?,
    val reviewAvailable: Boolean,
    val reviewInProgress: Boolean,
    val reviewProgress: Int,
    val reviewTotal: Int,
    val reviewSummary: String?,
    val reviewDetails: String,
    val reviewError: String?,
    val reviewMoves: List<SharedReviewMove>,
    val reviewBestCount: Int,
    val reviewGoodCount: Int,
    val reviewInaccuracyCount: Int,
    val reviewMistakeCount: Int,
    val reviewBlunderCount: Int,
)

/**
 * Runnable production-rules game session for Apple hosts.
 *
 * The coordinator, clocks, board reducer, Drawless adjudication, SAN history, controls, and
 * scoring are the exact Android production sources. Apple targets use the pinned native Fairy
 * transport; non-Apple host tests use a deterministic in-process opponent.
 */
class SharedGameRuntime(
    presetId: String = "drawless",
    humanSideId: String = "white",
    botLevelId: String = "casual",
    initialMillis: Long = 0,
    incrementMillis: Long = 0,
    threatIndicationEnabled: Boolean = false,
    checkpointJson: String? = null,
    boardThemeId: String = BoardThemes.DEFAULT.id,
    adaptiveElo: Int = BotDifficultyCatalog.ADAPTIVE_STARTING_ELO,
) {
    private val restoredCheckpoint = checkpointJson?.let(SharedCheckpointCodec::decode)
    private val requestedHumanSide = if (humanSideId.lowercase() == "black") Side.BLACK else Side.WHITE
    private val requestedBotLevel = if (botLevelId == BotDifficultyCatalog.ADAPTIVE_LEVEL_ID) {
        BotDifficultyCatalog.adaptiveLevel(adaptiveElo)
    } else {
        BotDifficultyCatalog.named(botLevelId)
    }
    private val config = restoredCheckpoint?.config ?: GameConfig(
        gameId = newGameId(),
        initialFen = ChessPosition.START_FEN,
        rules = when (presetId.lowercase()) {
            "escape" -> RulesContractV1.escape()
            else -> RulesContractV1.drawless()
        },
        mode = GameMode.CASUAL,
        timeControl = if (initialMillis > 0) {
            TimeControl.Clock(initialMillis, incrementMillis)
        } else {
            TimeControl.Untimed
        },
        humanSide = requestedHumanSide,
        engineStrength = EngineStrength.ApproximateElo(requestedBotLevel.approximateElo),
        engineLimits = EngineLimits(moveTimeMillis = 350),
        opponentLevelId = requestedBotLevel.id,
    )
    private val humanSide = config.humanSide
    private val botLevel = if (config.opponentLevelId == BotDifficultyCatalog.ADAPTIVE_LEVEL_ID) {
        BotDifficultyCatalog.adaptiveLevel(
            (config.engineStrength as? EngineStrength.ApproximateElo)?.elo
                ?: BotDifficultyCatalog.ADAPTIVE_STARTING_ELO,
        )
    } else {
        BotDifficultyCatalog.displayLevel(config.opponentLevelId, config.engineStrength)
    }
    private val boardTheme = BoardThemes.fromId(boardThemeId)
    private val timeSource = RuntimeTimeSource()
    private val checkpointStore = MemoryCheckpointSink()
    private val engine = createRuntimeEngine()
    private var nextId = 0
    private val coordinator = restoredCheckpoint?.let { checkpoint ->
        GameCoordinator.restore(
            checkpoint = checkpoint,
            engine = engine,
            checkpointSink = checkpointStore,
            timeSource = timeSource,
            idSource = CoordinatorIdSource { "ios-request-${++nextId}" },
        )
    } ?: GameCoordinator.newGame(
        config = config,
        engine = engine,
        checkpointSink = checkpointStore,
        timeSource = timeSource,
        idSource = CoordinatorIdSource { "ios-request-${++nextId}" },
        initialAssistance = AssistanceCounts(threatIndication = threatIndicationEnabled),
    )
    private var interaction = BoardInteractionState.initial(
        ChessPosition.fromFen(restoredCheckpoint?.currentFen ?: config.initialFen),
        humanSide,
    )
    private val resultLock = ConcurrentLock()
    private var latestHintMove: String? = null
    private var latestHintError: String? = null
    private val reviewRunner = GameReviewRunner(engine)
    private var reviewInProgress = false
    private var reviewProgress: GameReviewProgress? = null
    private var reviewTotalMoves = 0
    private var reviewPartialMoves: Map<Int, ReviewedMove> = emptyMap()
    private var reviewResult: GameReviewResult? = null
    private var reviewError: String? = null
    private var reviewCancellation: EngineCancellation? = null
    private var closed = false

    init {
        coordinator.start()
    }

    fun view(): SharedGameView {
        check(!closed) { "Game runtime is closed" }
        coordinator.tick()
        val snapshot = coordinator.snapshot()
        val asyncState = resultLock.withLock {
            RuntimeAsyncState(
                hintMove = latestHintMove,
                hintError = latestHintError,
                reviewInProgress = reviewInProgress,
                reviewProgress = reviewProgress,
                reviewTotalMoves = reviewTotalMoves,
                reviewPartialMoves = reviewPartialMoves,
                reviewResult = reviewResult,
                reviewError = reviewError,
            )
        }
        val hintArrow = asyncState.hintMove?.let { move ->
            BoardMoveArrow(
                from = Square.parse(move.substring(0, 2)),
                to = Square.parse(move.substring(2, 4)),
            )
        }
        val board = BoardPresenter.present(
            snapshot = snapshot,
            config = config,
            interactionState = interaction,
            theme = boardTheme,
            pieceSet = PieceSets.MODERN_FLAT,
            threatIndicationEnabled = snapshot.assistance.threatIndication,
            hintMove = hintArrow,
        )
        interaction = board.interaction
        val timeline = GameHistoryPresenter.present(
            config.initialFen,
            snapshot.session.moves.map { it.move },
        )
        val outcome = snapshot.session.outcome
        val lastMoveEntry = timeline.history.lastOrNull()?.let { row -> row.black ?: row.white }
        val playerWon = outcome?.winner == humanSide
        val score = outcome?.let {
            GameScoring.forResult(playerWon, snapshot.assistance, config.timeControl)
        }
        val reviewedMoves = (
            asyncState.reviewResult?.moves
                ?: asyncState.reviewPartialMoves.values.sortedBy { it.ply }
            )
        val sharedReviewMoves = reviewedMoves.map(ReviewedMove::sharedProjection)
        return SharedGameView(
            gameId = config.gameId,
            presetId = config.rules.preset.name,
            opponentLevelId = config.opponentLevelId ?: botLevel.id,
            opponentElo = (config.engineStrength as? EngineStrength.ApproximateElo)?.elo
                ?: botLevel.approximateElo,
            initialMillis = (config.timeControl as? TimeControl.Clock)?.initialMillis ?: 0,
            incrementMillis = (config.timeControl as? TimeControl.Clock)?.incrementMillis ?: 0,
            boardThemeId = boardTheme.id,
            lightSquareArgb = boardTheme.lightSquare.value,
            darkSquareArgb = boardTheme.darkSquare.value,
            selectedArgb = boardTheme.selected.value,
            legalMoveArgb = boardTheme.legalMove.value,
            legalCaptureArgb = boardTheme.legalCapture.value,
            lastMoveArgb = boardTheme.lastMove.value,
            checkArgb = boardTheme.check.value,
            cells = board.cells.mapIndexed { index, cell ->
                val piece = cell.piece?.let { Piece(it.side, it.type) }
                SharedBoardCell(
                    displayIndex = index,
                    square = cell.square.algebraic,
                    pieceSymbol = piece?.symbol().orEmpty(),
                    pieceCode = piece?.code().orEmpty(),
                    darkSquare = (cell.square.file + cell.square.rank).isEven,
                    selected = cell.selected,
                    legalTarget = cell.target != null,
                    captureTarget = cell.target == TargetKind.CAPTURE,
                    lastMove = cell.lastMove,
                    inCheck = cell.inCheck,
                    threatened = cell.threatened,
                    accessibilityLabel = cell.accessibility.label(),
                )
            },
            phase = snapshot.phase.name,
            statusText = statusText(snapshot.phase, outcome?.winner),
            humanSide = humanSide.name,
            sideToMove = snapshot.session.sideToMove.name,
            plyCount = snapshot.session.moves.size,
            moveHistory = timeline.history.joinToString("  ") { row ->
                buildString {
                    append(row.moveNumber)
                    append(". ")
                    append(row.white?.notation ?: "…")
                    row.black?.notation?.let { append(" ").append(it) }
                }
            },
            lastMoveNotation = lastMoveEntry?.notation,
            lastMoveEnPassant = lastMoveEntry?.accessibility?.enPassant == true,
            whiteRemainingMillis = snapshot.clock.whiteRemainingMillis ?: -1,
            blackRemainingMillis = snapshot.clock.blackRemainingMillis ?: -1,
            canPause = snapshot.phase != CoordinatorPhase.PAUSED && outcome == null,
            canResume = snapshot.phase == CoordinatorPhase.PAUSED,
            canUndo = outcome == null && snapshot.session.moves.any { it.mover == humanSide },
            canHint = snapshot.phase == CoordinatorPhase.HUMAN_TURN && outcome == null,
            canResign = outcome == null,
            promotionChoices = interaction.promotionPrompt?.choices?.map { it.name }.orEmpty(),
            winner = outcome?.winner?.name,
            endReason = outcome?.reason?.name,
            score = score?.points ?: 0,
            scoreMaximumPoints = score?.maximumPoints ?: 100,
            hintPenalty = score?.hintPenalty ?: 0,
            undoPenalty = score?.undoPenalty ?: 0,
            pausePenalty = score?.timedPausePenalty ?: 0,
            threatPenalty = score?.threatIndicationPenalty ?: 0,
            hintCount = snapshot.assistance.hints,
            undoCount = snapshot.assistance.undos,
            pauseCount = snapshot.assistance.pauses,
            threatIndicationEnabled = snapshot.assistance.threatIndication,
            hintMove = asyncState.hintMove,
            hintFromSquare = board.hintMove?.from?.algebraic,
            hintToSquare = board.hintMove?.to?.algebraic,
            engineError = snapshot.engineError ?: asyncState.hintError,
            reviewAvailable = outcome != null && snapshot.session.moves.isNotEmpty(),
            reviewInProgress = asyncState.reviewInProgress,
            reviewProgress = asyncState.reviewProgress?.completedMoves ?: reviewedMoves.size,
            reviewTotal = asyncState.reviewProgress?.totalMoves ?: asyncState.reviewTotalMoves,
            reviewSummary = asyncState.reviewResult?.let {
                "Reviewed ${it.moves.size} player moves with full Drawless patch-v2 evidence"
            },
            reviewDetails = reviewedMoves.joinToString("\n") { move ->
                "${move.ply}. ${move.playedMove.value} — ${move.quality?.name ?: "unreviewed"}" +
                    if (move.bestMove != move.playedMove) " · best ${move.bestMove.value}" else ""
            },
            reviewError = asyncState.reviewError,
            reviewMoves = sharedReviewMoves,
            reviewBestCount = reviewedMoves.count { it.quality == ReviewMoveQuality.BEST },
            reviewGoodCount = reviewedMoves.count { it.quality == ReviewMoveQuality.GOOD },
            reviewInaccuracyCount = reviewedMoves.count { it.quality == ReviewMoveQuality.INACCURACY },
            reviewMistakeCount = reviewedMoves.count { it.quality == ReviewMoveQuality.MISTAKE },
            reviewBlunderCount = reviewedMoves.count { it.quality == ReviewMoveQuality.BLUNDER },
        )
    }

    fun tap(displayIndex: Int): SharedGameView {
        check(displayIndex in 0..63) { "Board index must be between 0 and 63" }
        clearHintResult()
        val snapshot = coordinator.snapshot()
        val position = ChessPosition.fromFen(snapshot.currentFen)
        val context = BoardInteractionContext(
            position = position,
            interactive = snapshot.phase == CoordinatorPhase.HUMAN_TURN &&
                snapshot.session.sideToMove == humanSide,
            selectionSide = humanSide,
            preselectionEnabled = snapshot.phase == CoordinatorPhase.BOT_THINKING,
        )
        val row = displayIndex / 8
        val column = displayIndex % 8
        val square = interaction.orientation.squareAt(row, column)
        val reduction = BoardInteractionReducer.reduce(context, interaction, BoardEvent.TapSquare(square))
        interaction = reduction.state
        (reduction.action as? BoardAction.SubmitMove)?.let { coordinator.playHuman(it.move) }
        return view()
    }

    fun choosePromotion(pieceType: String): SharedGameView {
        clearHintResult()
        val choice = runCatching { PieceType.valueOf(pieceType.uppercase()) }.getOrNull()
            ?: return view()
        val snapshot = coordinator.snapshot()
        val context = BoardInteractionContext(
            position = ChessPosition.fromFen(snapshot.currentFen),
            interactive = snapshot.phase == CoordinatorPhase.HUMAN_TURN,
        )
        val reduction = BoardInteractionReducer.reduce(
            context,
            interaction,
            BoardEvent.PromotionChosen(choice),
        )
        interaction = reduction.state
        (reduction.action as? BoardAction.SubmitMove)?.let { coordinator.playHuman(it.move) }
        return view()
    }

    fun flipBoard(): SharedGameView {
        val snapshot = coordinator.snapshot()
        val context = BoardInteractionContext(
            position = ChessPosition.fromFen(snapshot.currentFen),
            interactive = snapshot.phase == CoordinatorPhase.HUMAN_TURN,
        )
        interaction = BoardInteractionReducer.reduce(context, interaction, BoardEvent.FlipBoard).state
        return view()
    }

    fun requestHint(): SharedGameView {
        clearHintResult()
        val positionId = coordinator.snapshot().session.positionId
        coordinator.requestHint(positionId) { result ->
            result.fold(
                onSuccess = { response ->
                    resultLock.withLock {
                        latestHintMove = response.bestMove.value
                        latestHintError = null
                    }
                },
                onFailure = { error ->
                    resultLock.withLock {
                        latestHintMove = null
                        latestHintError = error.message ?: "Hint analysis failed"
                    }
                },
            )
        }
        return view()
    }

    fun pause(): SharedGameView {
        coordinator.pause()
        return view()
    }

    fun resume(): SharedGameView {
        coordinator.resume()
        return view()
    }

    fun undo(): SharedGameView {
        clearHintResult()
        coordinator.undoLastHumanTurn()
        return view()
    }

    fun resign(): SharedGameView {
        clearHintResult()
        coordinator.resignHuman()
        return view()
    }

    fun startReview(): SharedGameView {
        val snapshot = coordinator.snapshot()
        val outcome = requireNotNull(snapshot.session.outcome) {
            "Review is available after the game finishes"
        }
        val moves = snapshot.session.moves.map { it.move }
        require(moves.isNotEmpty()) { "Review requires at least one played move" }
        val plan = GameReviewPlanner.playerPlan(
            gameId = config.gameId,
            initialFen = config.initialFen,
            moves = moves,
            rules = config.rules,
            playerSide = humanSide,
        )
        val previousReview = resultLock.withLock {
            val previous = reviewCancellation
            reviewInProgress = true
            reviewProgress = GameReviewProgress(
                completedWorkUnits = 0,
                totalWorkUnits = plan.roots.size,
                completedMoves = 0,
                totalMoves = plan.roots.size,
            )
            reviewTotalMoves = plan.roots.size
            reviewPartialMoves = emptyMap()
            reviewResult = null
            reviewError = null
            reviewCancellation = null
            previous
        }
        previousReview?.cancel()
        val cancellation = try {
            reviewRunner.reviewPlayerMoves(
                gameId = config.gameId,
                initialFen = config.initialFen,
                moves = moves,
                rules = config.rules,
                outcome = outcome,
                playerSide = humanSide,
                preparedPlan = plan,
                onMoveReviewed = { completed ->
                    resultLock.withLock {
                        if (reviewInProgress) {
                            reviewPartialMoves = reviewPartialMoves +
                                (completed.move.ply to completed.move)
                        }
                    }
                },
                onProgress = { progress ->
                    resultLock.withLock {
                        if (reviewInProgress) reviewProgress = progress
                    }
                },
                onResult = { result ->
                    resultLock.withLock {
                        reviewInProgress = false
                        reviewCancellation = null
                        result.fold(
                            onSuccess = { completed ->
                                reviewResult = completed
                                reviewPartialMoves = completed.moves.associateBy { it.ply }
                                reviewProgress = GameReviewProgress(
                                    completedWorkUnits = completed.moves.size,
                                    totalWorkUnits = completed.moves.size,
                                    completedMoves = completed.moves.size,
                                    totalMoves = completed.moves.size,
                                )
                                reviewError = null
                            },
                            onFailure = { error ->
                                reviewResult = null
                                reviewError = error.message ?: "The engine couldn't finish this review"
                            },
                        )
                    }
                },
            )
        } catch (error: Throwable) {
            resultLock.withLock {
                reviewInProgress = false
                reviewResult = null
                reviewError = error.message ?: "The engine couldn't start this review"
            }
            return view()
        }
        val cancelImmediately = resultLock.withLock {
            if (reviewInProgress) {
                reviewCancellation = cancellation
                false
            } else {
                true
            }
        }
        if (cancelImmediately) cancellation.cancel()
        return view()
    }

    fun close() {
        if (closed) return
        closed = true
        val activeReview = resultLock.withLock {
            val active = reviewCancellation
            reviewCancellation = null
            active
        }
        activeReview?.cancel()
        coordinator.close()
        engine.close()
    }

    fun checkpointRevision(): Long = checkpointStore.latest?.revision ?: -1

    /** Android-compatible format-1 payload for atomic platform storage. */
    fun checkpointJson(): String = SharedCheckpointCodec.encode(
        checkpointStore.latest ?: coordinator.checkpoint(),
    )

    private fun clearHintResult() {
        resultLock.withLock {
            latestHintMove = null
            latestHintError = null
        }
    }

    private fun statusText(phase: CoordinatorPhase, winner: Side?): String = when (phase) {
        CoordinatorPhase.HUMAN_TURN -> "Your move"
        CoordinatorPhase.HINT_THINKING -> "Finding a hint"
        CoordinatorPhase.BOT_THINKING -> "${botLevel.id.replaceFirstChar { it.uppercase() }} is thinking"
        CoordinatorPhase.BOT_ERROR -> "Opponent engine needs attention"
        CoordinatorPhase.PAUSED -> "Game paused"
        CoordinatorPhase.COMPLETED -> when (winner) {
            humanSide -> "You won"
            humanSide.opposite() -> "You lost"
            else -> "Game complete"
        }
    }

    private fun newGameId(): String = buildString {
        append("ios-")
        append(Clock.System.now().toEpochMilliseconds())
        append('-')
        append(Random.nextInt().toUInt().toString(16))
    }
}

private data class RuntimeAsyncState(
    val hintMove: String?,
    val hintError: String?,
    val reviewInProgress: Boolean,
    val reviewProgress: GameReviewProgress?,
    val reviewTotalMoves: Int,
    val reviewPartialMoves: Map<Int, ReviewedMove>,
    val reviewResult: GameReviewResult?,
    val reviewError: String?,
)

private fun ReviewedMove.sharedProjection(): SharedReviewMove = SharedReviewMove(
    ply = ply,
    mover = mover.name,
    playedMove = playedMove.value,
    bestMove = bestMove.value,
    quality = quality?.name,
    expectedPointLoss = expectedPointLoss,
    bestEvaluationKind = bestEvaluation?.sharedKind(),
    bestEvaluationValue = bestEvaluation?.sharedValue(),
    bestEvaluationText = bestEvaluation?.sharedText(),
    playedEvaluationKind = playedEvaluation?.sharedKind(),
    playedEvaluationValue = playedEvaluation?.sharedValue(),
    playedEvaluationText = playedEvaluation?.sharedText(),
    suggestedLine = suggestedLine.map { it.value },
    fenBefore = fenBefore,
    fenAfter = fenAfter,
)

private fun ReviewEvaluation.sharedKind(): String = when (this) {
    is ReviewEvaluation.Centipawns -> "CENTIPAWNS"
    is ReviewEvaluation.Mate -> "MATE"
    is ReviewEvaluation.Terminal -> "TERMINAL"
}

private fun ReviewEvaluation.sharedValue(): Int = when (this) {
    is ReviewEvaluation.Centipawns -> value
    is ReviewEvaluation.Mate -> mateIn
    is ReviewEvaluation.Terminal -> if (winner == Side.WHITE) 1 else -1
}

private fun ReviewEvaluation.sharedText(): String = when (this) {
    is ReviewEvaluation.Centipawns -> buildString {
        if (value >= 0) append('+') else append('-')
        val magnitude = kotlin.math.abs(value)
        append(magnitude / 100)
        append('.')
        append((magnitude % 100).toString().padStart(2, '0'))
    }
    is ReviewEvaluation.Mate -> "M$mateIn"
    is ReviewEvaluation.Terminal -> "${winner.name.lowercase().replaceFirstChar { it.uppercase() }} wins"
}

private class RuntimeTimeSource : CoordinatorTimeSource {
    private val origin = TimeSource.Monotonic.markNow()

    override fun now(): TimeReading = TimeReading(
        monotonicMillis = origin.elapsedNow().inWholeMilliseconds,
        epochMillis = Clock.System.now().toEpochMilliseconds(),
    )
}

private class MemoryCheckpointSink : CheckpointSink {
    var latest: CoordinatorCheckpoint? = null
        private set

    override fun persist(checkpoint: CoordinatorCheckpoint) {
        latest = checkpoint
    }
}

/** Deterministic shared-rules engine used by non-Apple host tests. */
internal class DeterministicOfflineEngine : RuntimeChessEngine {
    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        val result = runCatching {
            val position = ChessAdapter.replay(request.initialFen, request.moves)
            val move = chooseMove(position)
            val encoded = move.toUci()
            EngineResponse(
                requestId = request.requestId,
                gameId = request.gameId,
                positionId = request.positionId,
                bestMove = encoded,
                ponderMove = null,
                depth = 1,
                nodes = ChessRules.legalMoves(position).size.toLong(),
                variations = listOf(PrincipalVariation(0, null, listOf(encoded))),
                engine = EngineIdentity("shared-rules-test-engine", "2", 2),
            )
        }
        onResult(result)
        return EngineCancellation {}
    }

    override fun close() = Unit

    private fun chooseMove(position: ChessPosition): ChessMove = ChessRules.legalMoves(position)
        .maxWithOrNull(
            compareBy<ChessMove> { move -> moveScore(position, move) }
                .thenByDescending { it.toUci().value },
        )
        ?: error("No legal engine move")

    private fun moveScore(position: ChessPosition, move: ChessMove): Int {
        val captured = position[move.to]?.type?.value() ?: 0
        val after = ChessRules.apply(position, move)
        return captured * 100 +
            (if (move.promotion == PieceType.QUEEN) 900 else 0) +
            (if (ChessRules.isCheckmate(after)) 100_000 else if (ChessRules.isInCheck(after)) 50 else 0)
    }
}

private fun Piece.symbol(): String = when (side to type) {
    Side.WHITE to PieceType.KING -> "♔"
    Side.WHITE to PieceType.QUEEN -> "♕"
    Side.WHITE to PieceType.ROOK -> "♖"
    Side.WHITE to PieceType.BISHOP -> "♗"
    Side.WHITE to PieceType.KNIGHT -> "♘"
    Side.WHITE to PieceType.PAWN -> "♙"
    Side.BLACK to PieceType.KING -> "♚"
    Side.BLACK to PieceType.QUEEN -> "♛"
    Side.BLACK to PieceType.ROOK -> "♜"
    Side.BLACK to PieceType.BISHOP -> "♝"
    Side.BLACK to PieceType.KNIGHT -> "♞"
    Side.BLACK to PieceType.PAWN -> "♟"
    else -> error("Unsupported piece")
}

private fun Piece.code(): String = buildString(2) {
    append(if (side == Side.WHITE) 'w' else 'b')
    append(
        when (type) {
            PieceType.PAWN -> 'P'
            PieceType.KNIGHT -> 'N'
            PieceType.BISHOP -> 'B'
            PieceType.ROOK -> 'R'
            PieceType.QUEEN -> 'Q'
            PieceType.KING -> 'K'
        },
    )
}

private fun PieceType.value(): Int = when (this) {
    PieceType.PAWN -> 1
    PieceType.KNIGHT, PieceType.BISHOP -> 3
    PieceType.ROOK -> 5
    PieceType.QUEEN -> 9
    PieceType.KING -> 0
}

private val Int.isEven: Boolean get() = this % 2 == 0

private fun com.drawlesschess.core.presentation.SquareAccessibilityFacts.label(): String = buildString {
    val pieceName = piece?.let { "${it.side.name.lowercase()} ${it.type.name.lowercase()}" }
    append(pieceName ?: "Empty")
    append(", ")
    append(square.algebraic)
    when {
        target == TargetKind.CAPTURE -> append(", capture target")
        target == TargetKind.QUIET -> append(", legal target")
    }
    if (inCheck) append(", in check")
    if (threatened) append(", threatened")
}
