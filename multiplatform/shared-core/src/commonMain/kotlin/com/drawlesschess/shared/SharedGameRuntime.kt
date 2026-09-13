package com.drawlesschess.shared

import com.drawlesschess.core.AssistanceCounts
import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.ConcurrentLock
import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.GameOutcome
import com.drawlesschess.core.GameScoring
import com.drawlesschess.core.PrincipalVariation
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.TimeControl
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.Piece
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.chess.SanNotation
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.coordinator.CheckpointSink
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.CoordinatorIdSource
import com.drawlesschess.core.coordinator.CoordinatorPhase
import com.drawlesschess.core.coordinator.CoordinatorTimeSource
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.coordinator.GameCoordinator
import com.drawlesschess.core.coordinator.ReviewPrefetchAuditEvent
import com.drawlesschess.core.coordinator.ReviewPrefetchCoverageSnapshot
import com.drawlesschess.core.coordinator.TimeReading
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.GameReviewMoveResult
import com.drawlesschess.core.engine.GameReviewSearchSubmission
import com.drawlesschess.core.engine.GameReviewProgress
import com.drawlesschess.core.engine.GameReviewPlanner
import com.drawlesschess.core.engine.GameReviewResult
import com.drawlesschess.core.engine.GameReviewRunner
import com.drawlesschess.core.engine.PlayerGameReviewSeedCoverage
import com.drawlesschess.core.engine.ReviewEvaluation
import com.drawlesschess.core.engine.ReviewMoveQuality
import com.drawlesschess.core.engine.ReviewedMove
import com.drawlesschess.core.engine.SeededGameReviewAdjacentRoot
import com.drawlesschess.core.engine.SeededGameReviewRoot
import com.drawlesschess.core.presentation.BoardAction
import com.drawlesschess.core.presentation.BoardEvent
import com.drawlesschess.core.presentation.BoardInteractionContext
import com.drawlesschess.core.presentation.BoardInteractionReducer
import com.drawlesschess.core.presentation.BoardInteractionState
import com.drawlesschess.core.presentation.BoardMoveArrow
import com.drawlesschess.core.presentation.BoardOrientation
import com.drawlesschess.core.presentation.BoardPresenter
import com.drawlesschess.core.presentation.BoardThemes
import com.drawlesschess.core.presentation.GameHistoryPresenter
import com.drawlesschess.core.presentation.GameTimelineView
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

/** Complete game timeline with player-only review evidence projected into Swift-friendly primitives. */
data class SharedReviewMove(
    val ply: Int,
    val moveNumber: Int,
    val mover: String,
    val playerDecision: Boolean,
    val playedMove: String,
    val playedSan: String,
    val bestMove: String,
    val bestMoveSan: String?,
    val quality: String?,
    val expectedPointLoss: Double?,
    val bestEvaluationKind: String?,
    val bestEvaluationValue: Int?,
    val bestEvaluationText: String?,
    val playedEvaluationKind: String?,
    val playedEvaluationValue: Int?,
    val playedEvaluationText: String?,
    val suggestedLine: List<String>,
    val suggestedLineSan: List<String>,
    val fenBefore: String,
    val fenAfter: String,
    val cells: List<SharedBoardCell>,
    val betterMoveFromSquare: String?,
    val betterMoveToSquare: String?,
)

/** One piece participating in the most recently committed move. */
data class SharedPieceMotion(
    val fromSquare: String,
    val toSquare: String,
    val pieceCode: String,
)

/** Exact board-presentation motion metadata consumed by the native iOS board. */
data class SharedMoveMotion(
    val ply: Int,
    val mover: String,
    val pieces: List<SharedPieceMotion>,
)

/** Stable, platform-neutral state consumed by the native iOS view model. */
data class SharedGameView(
    val gameId: String,
    val presetId: String,
    val deadPositionId: String,
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
    val positionMarker: String,
    val moveMotion: SharedMoveMotion?,
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

/** Cheap review lifecycle state for result-screen polling without projecting the full timeline. */
data class SharedReviewStatus(
    val generation: Long,
    val inProgress: Boolean,
    val ready: Boolean,
    val progress: Int,
    val total: Int,
    val error: String?,
    val stage: String,
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
    deadPositionId: String = "material",
    humanSideId: String = "white",
    botLevelId: String = "casual",
    initialMillis: Long = 0,
    incrementMillis: Long = 0,
    threatIndicationEnabled: Boolean = false,
    checkpointJson: String? = null,
    boardThemeId: String = BoardThemes.DEFAULT.id,
    adaptiveElo: Int = BotDifficultyCatalog.ADAPTIVE_STARTING_ELO,
) {
    private val decodedCheckpoint = checkpointJson?.let(SharedCheckpointCodec::decode)
    private val engine = CountingRuntimeChessEngine(createRuntimeEngine())
    private val restoredCheckpoint = decodedCheckpoint?.withCompatibleReviewPrefetchEvidence(
        engineBuildId = engine.reviewEvidenceBuildId,
        enginePatchVersion = engine.reviewEvidencePatchVersion,
    )
    private val requestedHumanSide = if (humanSideId.lowercase() == "black") Side.BLACK else Side.WHITE
    private val requestedDeadPosition = when (deadPositionId.lowercase()) {
        "final", "final_capture", "final_capture_victory" -> DeadPositionPolicy.FINAL_CAPTURE_VICTORY
        else -> DeadPositionPolicy.MATERIAL_VICTORY
    }
    private val requestedBotLevel = if (botLevelId == BotDifficultyCatalog.ADAPTIVE_LEVEL_ID) {
        BotDifficultyCatalog.adaptiveLevel(adaptiveElo)
    } else {
        BotDifficultyCatalog.named(botLevelId)
    }
    private val config = restoredCheckpoint?.config ?: GameConfig(
        gameId = newGameId(),
        initialFen = ChessPosition.START_FEN,
        rules = when (presetId.lowercase()) {
            "escape" -> RulesContractV1.escape(deadPosition = requestedDeadPosition)
            else -> RulesContractV1.drawless(deadPosition = requestedDeadPosition)
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
    private var nextId = 0
    private val coordinator = restoredCheckpoint?.let { checkpoint ->
        GameCoordinator.restore(
            checkpoint = checkpoint,
            engine = engine,
            checkpointSink = checkpointStore,
            timeSource = timeSource,
            idSource = CoordinatorIdSource { "ios-request-${++nextId}" },
            botMovePresentationDelayMillis = SHARED_BOT_MOVE_PRESENTATION_MILLIS,
            drainReviewPrefetchBacklog = true,
        )
    } ?: GameCoordinator.newGame(
        config = config,
        engine = engine,
        checkpointSink = checkpointStore,
        timeSource = timeSource,
        idSource = CoordinatorIdSource { "ios-request-${++nextId}" },
        botMovePresentationDelayMillis = SHARED_BOT_MOVE_PRESENTATION_MILLIS,
        initialAssistance = AssistanceCounts(threatIndication = threatIndicationEnabled),
        drainReviewPrefetchBacklog = true,
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
    private var reviewGeneration = 0L
    private var reviewProjectedMoves: List<SharedReviewMove> = emptyList()
    private var reviewPlanRootCount = 0
    private var reviewSeededRootCount = 0
    private var reviewSeededAdjacentRootCount = 0
    private var reviewPreparedMoveCount = 0
    private var reviewRequestBaseline = 0
    private var reviewStage = REVIEW_STAGE_IDLE
    private var reviewPreparationStartedAtMillis: Long? = null
    private var reviewPreparationMillis: Long? = null
    private var reviewSeedCoverage: PlayerGameReviewSeedCoverage? = null
    private var reviewPostGameSubmissions: List<GameReviewSearchSubmission> = emptyList()
    private var reviewAuditLines: List<String> = emptyList()
    private var foregroundPreparedMoves: Map<Int, GameReviewMoveResult> = emptyMap()
    private var foregroundMaterializationScheduled = false
    private var foregroundMaterializationRerunRequested = false
    private var foregroundMaterializationRequestedRevision = Long.MIN_VALUE
    private var foregroundMaterializationCompletedRevision = Long.MIN_VALUE
    private var foregroundMaterializationError: String? = null
    private var foregroundMaterializationMillis: Long? = null
    private var asyncPresentationRevision = 0L
    private var interactionPresentationRevision = 0L
    private var observedPresentationInputs: RuntimePresentationInputs? = null
    private var presentationRevision = 0L
    private var timelineMoveKey: List<String>? = null
    private var cachedTimeline: GameTimelineView? = null
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
                reviewProjectedMoves = reviewProjectedMoves,
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
        updateInteraction(board.interaction)
        val timelineMoves = snapshot.session.moves.map { it.move }
        val timelineKey = timelineMoves.map { it.value }
        val timeline = if (timelineMoveKey == timelineKey) {
            requireNotNull(cachedTimeline)
        } else {
            GameHistoryPresenter.present(config.initialFen, timelineMoves).also {
                timelineMoveKey = timelineKey
                cachedTimeline = it
            }
        }
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
        return SharedGameView(
            gameId = config.gameId,
            presetId = config.rules.preset.name,
            deadPositionId = when (config.rules.deadPosition) {
                DeadPositionPolicy.MATERIAL_VICTORY -> "material"
                DeadPositionPolicy.FINAL_CAPTURE_VICTORY -> "final_capture"
            },
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
            positionMarker = board.positionMarker,
            moveMotion = board.moveMotion?.let { motion ->
                SharedMoveMotion(
                    ply = motion.ply,
                    mover = motion.mover.name,
                    pieces = motion.pieces.map { piece ->
                        SharedPieceMotion(
                            fromSquare = piece.from.algebraic,
                            toSquare = piece.to.algebraic,
                            pieceCode = Piece(piece.piece.side, piece.piece.type).code(),
                        )
                    },
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
            // Android routes every completed game through Review, including an immediate
            // resignation with no played moves. The empty review completes without engine work
            // and presents the dedicated no-player-moves state.
            reviewAvailable = outcome != null,
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
            reviewMoves = asyncState.reviewProjectedMoves,
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
        updateInteraction(reduction.state)
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
        updateInteraction(reduction.state)
        (reduction.action as? BoardAction.SubmitMove)?.let { coordinator.playHuman(it.move) }
        return view()
    }

    fun flipBoard(): SharedGameView {
        val snapshot = coordinator.snapshot()
        val context = BoardInteractionContext(
            position = ChessPosition.fromFen(snapshot.currentFen),
            interactive = snapshot.phase == CoordinatorPhase.HUMAN_TURN,
        )
        updateInteraction(
            BoardInteractionReducer.reduce(context, interaction, BoardEvent.FlipBoard).state,
        )
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
                        asyncPresentationRevision++
                    }
                },
                onFailure = { error ->
                    resultLock.withLock {
                        latestHintMove = null
                        latestHintError = error.message ?: "Hint analysis failed"
                        asyncPresentationRevision++
                    }
                },
            )
        }
        // Hint startup changes the visible coordinator phase before its asynchronous result can
        // advance the callback revision below.
        resultLock.withLock { asyncPresentationRevision++ }
        return view()
    }

    /** Selects a human piece without allowing a move to commit during opponent presentation. */
    fun preselect(displayIndex: Int): SharedGameView {
        check(displayIndex in 0..63) { "Board index must be between 0 and 63" }
        val snapshot = coordinator.snapshot()
        val context = BoardInteractionContext(
            position = ChessPosition.fromFen(snapshot.currentFen),
            interactive = snapshot.phase == CoordinatorPhase.HUMAN_TURN &&
                snapshot.session.sideToMove == humanSide,
            selectionSide = humanSide,
            preselectionEnabled = true,
        )
        val row = displayIndex / 8
        val column = displayIndex % 8
        val square = interaction.orientation.squareAt(row, column)
        updateInteraction(
            BoardInteractionReducer.reduce(
                context,
                interaction,
                BoardEvent.PreselectSquare(square),
            ).state,
        )
        return view()
    }

    fun cancelPromotion(): SharedGameView {
        clearHintResult()
        val snapshot = coordinator.snapshot()
        val context = BoardInteractionContext(
            position = ChessPosition.fromFen(snapshot.currentFen),
            interactive = snapshot.phase == CoordinatorPhase.HUMAN_TURN,
        )
        updateInteraction(
            BoardInteractionReducer.reduce(
                context,
                interaction,
                BoardEvent.PromotionCancelled,
            ).state,
        )
        return view()
    }

    /**
     * Warms exact player-decision review evidence only while the game is visible and active.
     * Apple uses the coordinator's shared-engine launch gate: a move, hint, pause, or bot request
     * cancels and drains speculative review before the same native Fairy session is reconfigured.
     */
    fun setGameForeground(foreground: Boolean) {
        if (closed) return
        coordinator.setReviewPrefetchEnabled(foreground)
        if (foreground) requestForegroundReviewMaterialization()
    }

    /**
     * Converts accepted foreground engine evidence into immutable reviewed moves while the player
     * is still thinking. The Apple implementation and the JVM verifier both run this work on the
     * same serial preparation worker later used by the terminal handoff, so final review can never
     * race or duplicate an in-flight gameplay materialization.
     */
    private fun requestForegroundReviewMaterialization(
        checkpointRevision: Long? = null,
        force: Boolean = false,
    ) {
        if (closed) return
        val requestedRevision = checkpointRevision
            ?: checkpointStore.latest?.revision
            ?: coordinator.checkpoint().revision
        val shouldSchedule = resultLock.withLock {
            if (closed) return@withLock false
            val revisionAdvanced = requestedRevision > foregroundMaterializationRequestedRevision
            if (revisionAdvanced) {
                foregroundMaterializationRequestedRevision = requestedRevision
            }
            if (foregroundMaterializationScheduled) {
                // Swift polls presentationRevision frequently. Re-running for the same revision
                // would keep this serial worker busy forever and could starve terminal handoff.
                if (revisionAdvanced || force) foregroundMaterializationRerunRequested = true
                false
            } else if (!force && foregroundMaterializationCompletedRevision >= requestedRevision) {
                false
            } else {
                foregroundMaterializationScheduled = true
                true
            }
        }
        if (shouldSchedule) {
            scheduleRuntimeReviewPreparation(::drainForegroundReviewMaterialization)
        }
    }

    private fun drainForegroundReviewMaterialization() {
        while (true) {
            if (closed) {
                resultLock.withLock {
                    foregroundMaterializationScheduled = false
                    foregroundMaterializationRerunRequested = false
                }
                return
            }
            val materializationStartedAt = timeSource.now().monotonicMillis
            val checkpoint = coordinator.checkpoint()
            val plan = GameReviewPlanner.playerPlan(
                gameId = checkpoint.config.gameId,
                initialFen = checkpoint.config.initialFen,
                moves = checkpoint.moves,
                rules = checkpoint.config.rules,
                playerSide = checkpoint.config.humanSide,
            )
            val plannedByPly = plan.roots.associateBy { root -> root.ply }
            val plannedKeys = plan.roots.mapTo(linkedSetOf()) { root -> root.key }
            val compatibleRoots = checkpoint.reviewPrefetchRoots.filter { seed ->
                seed.key in plannedKeys
            }
            val compatibleAdjacentRoots = checkpoint.reviewPrefetchAdjacentRoots.filter { seed ->
                val planned = plannedByPly[seed.key.rootKey.ply]
                planned?.key == seed.key.rootKey &&
                    checkpoint.moves.getOrNull(seed.key.rootKey.ply - 1) == seed.key.playedMove
            }
            val alreadyPrepared = resultLock.withLock {
                foregroundPreparedMoves.values.filter { prepared ->
                    plannedByPly[prepared.move.ply]?.key == prepared.rootKey &&
                        checkpoint.moves.getOrNull(prepared.move.ply - 1) == prepared.move.playedMove
                }
            }
            val newlyPrepared = runCatching {
                reviewRunner.materializeReadyPlayerMoves(
                    gameId = checkpoint.config.gameId,
                    initialFen = checkpoint.config.initialFen,
                    moves = checkpoint.moves,
                    rules = checkpoint.config.rules,
                    playerSide = checkpoint.config.humanSide,
                    seededRoots = compatibleRoots,
                    seededAdjacentRoots = compatibleAdjacentRoots,
                    alreadyPrepared = alreadyPrepared,
                    outcome = checkpoint.outcome,
                    preparedPlan = plan,
                )
            }

            val materializationElapsedMillis =
                (timeSource.now().monotonicMillis - materializationStartedAt).coerceAtLeast(0L)
            resultLock.withLock {
                if (!closed) {
                    val retained = foregroundPreparedMoves.filter { (ply, prepared) ->
                        plannedByPly[ply]?.key == prepared.rootKey &&
                            checkpoint.moves.getOrNull(ply - 1) == prepared.move.playedMove
                    }
                    newlyPrepared.fold(
                        onSuccess = { additions ->
                            foregroundPreparedMoves = retained + additions.associateBy { it.move.ply }
                            foregroundMaterializationError = null
                            if (additions.isNotEmpty()) {
                                reviewAuditLines = reviewAuditLines + additions.map { prepared ->
                                    runtimeRootAuditLine(
                                        event = "foreground_move_materialized",
                                        ply = prepared.move.ply,
                                        kind = "reviewed_move",
                                        positionId = prepared.rootKey.positionId,
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            foregroundPreparedMoves = retained
                            foregroundMaterializationError = error.message
                                ?: "Foreground review materialization failed"
                            reviewAuditLines = reviewAuditLines + reviewAuditJson(
                                event = "foreground_materialization_failed",
                                fields = mapOf(
                                    "source" to "runtime",
                                    "revision" to checkpoint.revision.toString(),
                                    "error" to foregroundMaterializationError,
                                ),
                            )
                        },
                    )
                    when {
                        checkpoint.revision > foregroundMaterializationCompletedRevision -> {
                            foregroundMaterializationCompletedRevision = checkpoint.revision
                            foregroundMaterializationMillis = materializationElapsedMillis
                        }
                        checkpoint.revision == foregroundMaterializationCompletedRevision -> {
                            foregroundMaterializationMillis = maxOf(
                                foregroundMaterializationMillis ?: 0L,
                                materializationElapsedMillis,
                            )
                        }
                    }
                }
            }

            val latestRevision = if (closed) checkpoint.revision else coordinator.checkpoint().revision
            val shouldRunAgain = resultLock.withLock {
                val rerun = !closed && (
                    foregroundMaterializationRerunRequested ||
                        foregroundMaterializationRequestedRevision > checkpoint.revision ||
                        latestRevision > checkpoint.revision
                    )
                foregroundMaterializationRerunRequested = false
                if (!rerun) foregroundMaterializationScheduled = false
                rerun
            }
            if (!shouldRunAgain) return
        }
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

    /**
     * Starts the runtime-owned final review only when no attempt or completed result exists.
     * Opening the Review screen can call this safely even when its Swift snapshot is stale.
     */
    fun ensureReviewStarted(): SharedGameView = requireNotNull(
        startReview(allowFailedRetry = false, projectImmediateView = true),
    )

    /**
     * Accepts and schedules the final-review handoff without rebuilding the gameplay view.
     * The terminal screen already owns its committed [SharedGameView]; automatic preparation only
     * needs control-plane state until the user opens Review.
     */
    fun ensureReviewPreparationStarted() {
        startReview(allowFailedRetry = false, projectImmediateView = false)
    }

    /** Explicit retries may replace a failed attempt, but never an active or completed review. */
    fun startReview(): SharedGameView = requireNotNull(
        startReview(allowFailedRetry = true, projectImmediateView = true),
    )

    private fun startReview(
        allowFailedRetry: Boolean,
        projectImmediateView: Boolean,
    ): SharedGameView? {
        val preparationStartedAtMillis = timeSource.now().monotonicMillis
        val snapshot = coordinator.snapshot()
        val outcome = requireNotNull(snapshot.session.outcome) {
            "Review is available after the game finishes"
        }
        val moves = snapshot.session.moves.map { it.move }
        val initialSide = ChessPosition.fromFen(config.initialFen).sideToMove
        val playerMoveCount = moves.indices.count { index ->
            val mover = if (index % 2 == 0) initialSide else initialSide.opposite()
            mover == humanSide
        }
        // Freeze the exact terminal handoff before asynchronous preparation starts. Accepted
        // foreground evidence cannot subsequently disappear or be replaced under the final run.
        val handoffRoots = coordinator.completedReviewPrefetchRoots()
        val handoffAdjacentRoots = coordinator.completedReviewPrefetchAdjacentRoots()
        val handoffCoverage = coordinator.terminalReviewPrefetchCoverageSnapshot()
            ?: coordinator.reviewPrefetchCoverageSnapshot()
        val requestBaseline = engine.reviewRequestCountForTesting()
        val startState = resultLock.withLock {
            val alreadyOwned = closed ||
                reviewInProgress ||
                reviewResult != null ||
                (!allowFailedRetry && reviewError != null)
            if (alreadyOwned) return@withLock null
            val previous = reviewCancellation
            reviewGeneration++
            reviewInProgress = true
            reviewProgress = GameReviewProgress(
                completedWorkUnits = 0,
                totalWorkUnits = playerMoveCount,
                completedMoves = 0,
                totalMoves = playerMoveCount,
            )
            reviewTotalMoves = reviewProgress?.totalMoves ?: 0
            reviewPartialMoves = emptyMap()
            reviewResult = null
            reviewError = null
            reviewCancellation = null
            reviewProjectedMoves = emptyList()
            reviewPlanRootCount = 0
            reviewSeededRootCount = 0
            reviewSeededAdjacentRootCount = 0
            reviewPreparedMoveCount = 0
            reviewRequestBaseline = requestBaseline
            reviewStage = REVIEW_STAGE_PREPARING
            reviewPreparationStartedAtMillis = preparationStartedAtMillis
            reviewPreparationMillis = null
            reviewSeedCoverage = null
            reviewPostGameSubmissions = emptyList()
            reviewAuditLines = reviewAuditLines + runtimeCoverageAuditLine(
                event = "terminal_handoff_frozen",
                coverage = handoffCoverage,
                extra = mapOf(
                    "handoffExact" to handoffRoots.size.toString(),
                    "handoffAdjacent" to handoffAdjacentRoots.size.toString(),
                    "preparedAtHandoff" to foregroundPreparedMoves.size.toString(),
                ),
            )
            asyncPresentationRevision++
            reviewGeneration to previous
        } ?: return if (projectImmediateView) view() else null
        val (generation, previousReview) = startState
        previousReview?.cancel()

        // Capture the cheap presentation before the worker can publish prepared review frames.
        // This guarantees that the terminal SwiftUI callback never races a very fast seeded run
        // and accidentally projects the whole review timeline on the main actor.
        val immediateView = if (projectImmediateView) view() else null

        // Queue one final incremental pass ahead of terminal preparation. In the normal path this
        // materializes only the just-played terminal decision; all earlier decisions were already
        // converted during human think time.
        // A previous deterministic foreground failure is not retried on every Swift poll, but the
        // one terminal handoff gets a final explicit attempt before final review preparation.
        requestForegroundReviewMaterialization(force = true)
        scheduleRuntimeReviewPreparation {
            prepareAndStartReview(
                generation = generation,
                moves = moves,
                outcome = outcome,
                handoffRoots = handoffRoots,
                handoffAdjacentRoots = handoffAdjacentRoots,
            )
        }
        return immediateView
    }

    private fun prepareAndStartReview(
        generation: Long,
        moves: List<UciMove>,
        outcome: GameOutcome,
        handoffRoots: List<SeededGameReviewRoot>,
        handoffAdjacentRoots: List<SeededGameReviewAdjacentRoot>,
    ) {
        if (!isCurrentReview(generation)) return
        resultLock.withLock {
            if (reviewInProgress && reviewGeneration == generation && !closed) {
                reviewAuditLines = reviewAuditLines + reviewAuditJson(
                    event = "terminal_preparation_worker_started",
                    fields = mapOf(
                        "source" to "runtime",
                        "generation" to generation.toString(),
                        "moves" to moves.size.toString(),
                        "foregroundPrepared" to foregroundPreparedMoves.size.toString(),
                    ),
                )
            }
        }
        val cancellation = try {
            val preparedFrames = buildSharedReviewFrames(
                initialFen = config.initialFen,
                moves = moves,
                humanSide = humanSide,
                shouldContinue = { isCurrentReview(generation) },
            ) ?: return
            resultLock.withLock {
                if (reviewInProgress && reviewGeneration == generation && !closed) {
                    reviewAuditLines = reviewAuditLines + reviewAuditJson(
                        event = "review_frames_built",
                        fields = mapOf(
                            "source" to "runtime",
                            "generation" to generation.toString(),
                            "frames" to preparedFrames.size.toString(),
                        ),
                    )
                }
            }
            val initialProjectedMoves = preparedFrames.map { frame ->
                frame.sharedProjection(reviewed = null)
            }
            resultLock.withLock {
                if (reviewInProgress && reviewGeneration == generation && !closed) {
                    reviewProjectedMoves = initialProjectedMoves
                    asyncPresentationRevision++
                }
            }
            if (!isCurrentReview(generation)) return
            val plan = GameReviewPlanner.playerPlan(
                gameId = config.gameId,
                initialFen = config.initialFen,
                moves = moves,
                rules = config.rules,
                playerSide = humanSide,
            )
            val prefetchedByKey = handoffRoots.associateBy { it.key }
            val compatibleSeeds = plan.roots.mapNotNull { root -> prefetchedByKey[root.key] }
            val prefetchedAdjacentByRootAndMove = handoffAdjacentRoots
                .associateBy { seed -> seed.key.rootKey to seed.key.playedMove }
            val compatibleAdjacentSeeds = plan.roots.mapNotNull { root ->
                val playedMove = plan.gameMoves[root.ply - 1]
                prefetchedAdjacentByRootAndMove[root.key to playedMove]
            }
            val plannedByPly = plan.roots.associateBy { root -> root.ply }
            val compatiblePreparedMoves = resultLock.withLock {
                foregroundPreparedMoves.values.filter { prepared ->
                    plannedByPly[prepared.move.ply]?.key == prepared.rootKey &&
                        plan.gameMoves.getOrNull(prepared.move.ply - 1) == prepared.move.playedMove
                }
            }
            resultLock.withLock {
                if (reviewInProgress && reviewGeneration == generation && !closed) {
                    reviewAuditLines = reviewAuditLines + reviewAuditJson(
                        event = "review_plan_built",
                        fields = mapOf(
                            "source" to "runtime",
                            "generation" to generation.toString(),
                            "roots" to plan.roots.size.toString(),
                            "foregroundPrepared" to compatiblePreparedMoves.size.toString(),
                        ),
                    )
                }
            }
            resultLock.withLock {
                if (reviewInProgress && reviewGeneration == generation && !closed) {
                    reviewPlanRootCount = plan.roots.size
                    reviewSeededRootCount = compatibleSeeds.size
                    reviewSeededAdjacentRootCount = compatibleAdjacentSeeds.size
                    reviewPreparedMoveCount = compatiblePreparedMoves.size
                    val compatibleRootKeys = compatibleSeeds.map { it.key }.toSet()
                    val compatibleAdjacentKeys = compatibleAdjacentSeeds.map { it.key }.toSet()
                    reviewAuditLines = reviewAuditLines + plan.roots.map { root ->
                        runtimeRootAuditLine(
                            event = if (root.key in compatibleRootKeys) "handoff_root_matched" else "handoff_root_missing",
                            ply = root.ply,
                            kind = "root",
                            positionId = root.key.positionId,
                        )
                    } + compatibleAdjacentSeeds.map { seed ->
                        runtimeRootAuditLine(
                            event = if (seed.key in compatibleAdjacentKeys) {
                                "handoff_adjacent_matched"
                            } else {
                                "handoff_adjacent_missing"
                            },
                            ply = seed.key.rootKey.ply,
                            kind = "adjacent",
                            positionId = seed.key.positionId,
                        )
                    }
                    reviewAuditLines = reviewAuditLines + reviewAuditJson(
                        event = "foreground_materialization_handoff",
                        fields = mapOf(
                            "source" to "runtime",
                            "preparedMoves" to compatiblePreparedMoves.size.toString(),
                            "expectedMoves" to plan.roots.size.toString(),
                            "missingPreparedPlies" to plan.roots
                                .filterNot { root -> compatiblePreparedMoves.any { it.rootKey == root.key } }
                                .joinToString(",") { root -> root.ply.toString() },
                        ),
                    )
                }
            }
            if (!isCurrentReview(generation)) return

            reviewRunner.reviewPlayerMoves(
                gameId = config.gameId,
                initialFen = config.initialFen,
                moves = moves,
                rules = config.rules,
                outcome = outcome,
                playerSide = humanSide,
                preparedPlan = plan,
                seededRoots = compatibleSeeds,
                seededAdjacentRoots = compatibleAdjacentSeeds,
                preparedMoves = compatiblePreparedMoves,
                materializeSeededMovesUpFront = true,
                onSeedCoverage = { coverage ->
                    resultLock.withLock {
                        if (reviewInProgress && reviewGeneration == generation && !closed) {
                            reviewSeedCoverage = coverage
                            reviewProgress = GameReviewProgress(
                                completedWorkUnits = coverage.materializablePlies.size,
                                totalWorkUnits = coverage.expectedPlies.size,
                                completedMoves = coverage.materializablePlies.size,
                                totalMoves = coverage.expectedPlies.size,
                            )
                            reviewStage = if (
                                coverage.missingExactPlies.isEmpty() &&
                                coverage.missingAdjacentPlies.isEmpty()
                            ) {
                                REVIEW_STAGE_FINALIZING
                            } else {
                                REVIEW_STAGE_ANALYZING
                            }
                            reviewAuditLines = reviewAuditLines + runtimeSeedCoverageAuditLine(coverage)
                            asyncPresentationRevision++
                        }
                    }
                },
                onSearchSubmitted = { submission ->
                    resultLock.withLock {
                        check(reviewInProgress && reviewGeneration == generation && !closed) {
                            "Stale final-review generation attempted to submit engine work"
                        }
                        val coverage = reviewSeedCoverage
                        val duplicateSeedSearch = when (submission.kind.name) {
                            "EXACT_ROOT" -> submission.ply in coverage?.exactSeededPlies.orEmpty()
                            else -> submission.ply in coverage?.adjacentSeededPlies.orEmpty()
                        }
                        check(!duplicateSeedSearch) {
                            "Final review attempted to re-search accepted foreground evidence at ply ${submission.ply}"
                        }
                        reviewPostGameSubmissions = reviewPostGameSubmissions + submission
                        reviewAuditLines = reviewAuditLines + runtimeSearchAuditLine(submission)
                        reviewStage = REVIEW_STAGE_ANALYZING
                        asyncPresentationRevision++
                    }
                },
                onMoveReviewed = { completed ->
                    val frameIndex = completed.move.ply - 1
                    val projectedMove = preparedFrames.getOrNull(frameIndex)?.sharedProjection(completed.move)
                    resultLock.withLock {
                        if (reviewInProgress && reviewGeneration == generation && !closed) {
                            reviewPartialMoves = reviewPartialMoves +
                                (completed.move.ply to completed.move)
                            if (projectedMove != null && frameIndex in reviewProjectedMoves.indices) {
                                reviewProjectedMoves = reviewProjectedMoves.toMutableList().also { projected ->
                                    projected[frameIndex] = projectedMove
                                }
                            }
                            asyncPresentationRevision++
                        }
                    }
                },
                onProgress = { progress ->
                    resultLock.withLock {
                        if (reviewInProgress && reviewGeneration == generation && !closed) {
                            reviewProgress = progress
                            if (progress.completedMoves < progress.totalMoves) {
                                reviewStage = REVIEW_STAGE_ANALYZING
                            }
                            asyncPresentationRevision++
                        }
                    }
                },
                onResult = { result ->
                    val completedProjection = result.getOrNull()?.let { completed ->
                        val reviewedByPly = completed.moves.associateBy { move -> move.ply }
                        preparedFrames.map { frame -> frame.sharedProjection(reviewedByPly[frame.ply]) }
                    }
                    resultLock.withLock {
                        if (reviewGeneration != generation || closed) return@withLock
                        reviewInProgress = false
                        reviewCancellation = null
                        asyncPresentationRevision++
                        result.fold(
                            onSuccess = { completed ->
                                reviewResult = completed
                                reviewPartialMoves = completed.moves.associateBy { it.ply }
                                reviewProjectedMoves = requireNotNull(completedProjection)
                                reviewProgress = GameReviewProgress(
                                    completedWorkUnits = completed.moves.size,
                                    totalWorkUnits = completed.moves.size,
                                    completedMoves = completed.moves.size,
                                    totalMoves = completed.moves.size,
                                )
                                reviewError = null
                                reviewStage = REVIEW_STAGE_READY
                                reviewAuditLines = reviewAuditLines + runtimeReviewResultAuditLine(
                                    ready = true,
                                    reviewedMoves = completed.moves.size,
                                )
                            },
                            onFailure = { error ->
                                reviewResult = null
                                reviewError = error.message ?: "The engine couldn't finish this review"
                                reviewStage = REVIEW_STAGE_FAILED
                                reviewAuditLines = reviewAuditLines + runtimeReviewResultAuditLine(
                                    ready = false,
                                    reviewedMoves = reviewPartialMoves.size,
                                    error = reviewError,
                                )
                            },
                        )
                        reviewPreparationMillis = reviewPreparationStartedAtMillis?.let { startedAt ->
                            (timeSource.now().monotonicMillis - startedAt).coerceAtLeast(0L)
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            resultLock.withLock {
                if (reviewGeneration != generation || closed) return@withLock
                reviewInProgress = false
                reviewResult = null
                reviewError = error.message ?: "The engine couldn't start this review"
                reviewStage = REVIEW_STAGE_FAILED
                reviewAuditLines = reviewAuditLines + runtimeReviewResultAuditLine(
                    ready = false,
                    reviewedMoves = reviewPartialMoves.size,
                    error = reviewError,
                )
                asyncPresentationRevision++
                reviewPreparationMillis = reviewPreparationStartedAtMillis?.let { startedAt ->
                    (timeSource.now().monotonicMillis - startedAt).coerceAtLeast(0L)
                }
            }
            return
        }
        val cancelImmediately = resultLock.withLock {
            if (reviewInProgress && reviewGeneration == generation && !closed) {
                reviewCancellation = cancellation
                false
            } else {
                true
            }
        }
        if (cancelImmediately) cancellation.cancel()
    }

    private fun isCurrentReview(generation: Long): Boolean = resultLock.withLock {
        reviewInProgress && reviewGeneration == generation && !closed
    }

    fun close() {
        val closeState = resultLock.withLock {
            if (closed) return@withLock false to null
            closed = true
            reviewGeneration++
            reviewInProgress = false
            val active = reviewCancellation
            reviewCancellation = null
            true to active
        }
        if (!closeState.first) return
        closeState.second?.cancel()
        coordinator.close()
        engine.close()
    }

    fun checkpointRevision(): Long = checkpointStore.latest?.revision ?: -1

    /**
     * Captures the latest immutable coordinator checkpoint without serializing it.
     *
     * Apple uses this small handoff on its main actor, then performs JSON construction and
     * platform storage on a private serial queue. The checkpoint and all of its nested values are
     * immutable snapshots; encoding this object never reads mutable runtime, coordinator, or
     * engine state.
     */
    fun checkpointSnapshot(): SharedCheckpointSnapshot = SharedCheckpointSnapshot(
        checkpointStore.latest ?: coordinator.checkpoint(),
    )

    /** Result-screen polling path which never reconstructs or exports review board frames. */
    fun reviewStatus(): SharedReviewStatus = resultLock.withLock {
        SharedReviewStatus(
            generation = reviewGeneration,
            inProgress = reviewInProgress,
            ready = reviewResult != null,
            progress = reviewProgress?.completedMoves ?: reviewPartialMoves.size,
            total = reviewProgress?.totalMoves ?: reviewTotalMoves,
            error = reviewError,
            stage = reviewStage,
        )
    }

    /** Current final-review phase, kept separate from numeric engine-analysis progress. */
    fun reviewPreparationState(): String = resultLock.withLock { reviewStage }

    /**
     * Destructively drains append-only, per-key review evidence for a platform-owned JSONL file.
     * This is intentionally available in normal Debug app launches, not only under XCTest.
     */
    fun drainReviewAuditEvents(): String {
        val coordinatorLines = coordinator.drainReviewPrefetchAuditEvents().map(::coordinatorAuditLine)
        val runtimeLines = resultLock.withLock {
            reviewAuditLines.also { reviewAuditLines = emptyList() }
        }
        return (coordinatorLines + runtimeLines).joinToString("\n")
    }

    /** Test-only diagnostics; production engines return DISABLED/UNAVAILABLE without exposing UI. */
    fun engineActivityForTesting(): String = engine.testingActivity()

    /** Test-only proof that final review replay/planning is not executing on the Swift main actor. */
    fun reviewPreparationActivityForTesting(): String = runtimeReviewPreparationActivityForTesting()

    /** Test-only proof of exact seed coverage and post-terminal engine submissions. */
    fun reviewReuseDiagnosticsForTesting(): String {
        val prefetchLifecycle = coordinator.reviewPrefetchLifecycleDiagnosticsForTesting()
        val prefetchRoots = coordinator.completedReviewPrefetchRoots().size
        val prefetchAdjacentRoots = coordinator.completedReviewPrefetchAdjacentRoots().size
        val prefetchPending = coordinator.pendingReviewPrefetchWorkForTesting()
        val requestCount = engine.reviewRequestCountForTesting()
        return resultLock.withLock {
            listOf(
                prefetchLifecycle,
                "prefetchRoots=$prefetchRoots",
                "prefetchAdjacent=$prefetchAdjacentRoots",
                "prefetchPending=$prefetchPending",
                "foregroundMaterialized=${foregroundPreparedMoves.size}",
                "foregroundMaterializationRevision=$foregroundMaterializationCompletedRevision",
                "foregroundMaterializationMs=${foregroundMaterializationMillis ?: "pending"}",
                "foregroundMaterializationFailed=${foregroundMaterializationError != null}",
                "reviewPreparationMs=${reviewPreparationMillis ?: "pending"}",
                "reviewGeneration=$reviewGeneration",
                "reviewPlanRoots=$reviewPlanRootCount",
                "reviewSeededRoots=$reviewSeededRootCount",
                "reviewSeededAdjacent=$reviewSeededAdjacentRootCount",
                "reviewPreparedMoves=$reviewPreparedMoveCount",
                "reviewPostGameSearches=${
                    if (reviewStage == REVIEW_STAGE_IDLE) {
                        0
                    } else {
                        (requestCount - reviewRequestBaseline).coerceAtLeast(0)
                    }
                }",
                "reviewStage=$reviewStage",
                "reviewMaterializable=${reviewSeedCoverage?.materializablePlies?.size ?: 0}",
                "reviewMissingExact=${reviewSeedCoverage?.missingExactPlies?.joinToString(",").orEmpty()}",
                "reviewMissingAdjacent=${reviewSeedCoverage?.missingAdjacentPlies?.joinToString(",").orEmpty()}",
                "reviewPostGameKeys=${reviewPostGameSubmissions.joinToString(",") { "${it.kind.name}:${it.ply}" }}",
                "reviewReady=${reviewResult != null}",
                "reviewInProgress=$reviewInProgress",
            ).joinToString(";")
        }
    }

    /**
     * Cheap UI polling token. Exact review roots advance the durable checkpoint revision so they
     * can be saved immediately, but do not alter anything projected on screen. Normalizing those
     * evidence-only increments keeps foreground prefetch from rebuilding the board. Coordinator
     * gameplay state, asynchronous hint/review state, and board interaction state remain distinct
     * inputs and advance this token when their visible projection changes.
     */
    fun presentationRevision(): Long {
        if (closed) return Long.MIN_VALUE
        coordinator.tick()
        val checkpoint = checkpointStore.latest ?: coordinator.checkpoint()
        requestForegroundReviewMaterialization(checkpoint.revision)
        return resultLock.withLock {
            val inputs = RuntimePresentationInputs(
                coordinatorRevision = checkpoint.visibleCoordinatorRevision(),
                asyncRevision = asyncPresentationRevision,
                interactionRevision = interactionPresentationRevision,
            )
            if (inputs != observedPresentationInputs) {
                if (observedPresentationInputs != null) presentationRevision++
                observedPresentationInputs = inputs
            }
            presentationRevision
        }
    }

    /** Android-compatible format-1 payload for atomic platform storage. */
    fun checkpointJson(): String = checkpointSnapshot().encodeJson()

    private fun clearHintResult() {
        resultLock.withLock {
            if (latestHintMove != null || latestHintError != null) asyncPresentationRevision++
            latestHintMove = null
            latestHintError = null
        }
    }

    private fun updateInteraction(next: BoardInteractionState) {
        if (interaction == next) return
        interaction = next
        resultLock.withLock { interactionPresentationRevision++ }
    }

    private fun coordinatorAuditLine(event: ReviewPrefetchAuditEvent): String = reviewAuditJson(
        event = event.stage.name.lowercase(),
        fields = mapOf(
            "source" to "coordinator",
            "sourceSequence" to event.sequence.toString(),
            "coordinatorRevision" to event.coordinatorRevision.toString(),
            "ply" to event.key.ply.toString(),
            "kind" to event.key.kind.name.lowercase(),
            "key" to event.key.diagnosticId,
            "positionId" to event.key.rootKey.positionId,
            "playedMove" to event.key.playedMove?.value,
            "requestId" to event.requestId,
            "reason" to event.reason,
        ),
    )

    private fun runtimeCoverageAuditLine(
        event: String,
        coverage: ReviewPrefetchCoverageSnapshot,
        extra: Map<String, String> = emptyMap(),
    ): String = reviewAuditJson(
        event = event,
        fields = mapOf(
            "source" to "runtime",
            "coordinatorRevision" to coverage.coordinatorRevision.toString(),
            "terminal" to coverage.terminal.toString(),
            "expectedPlies" to coverage.expectedPlayedRoots.joinToString(",") { it.ply.toString() },
            "acceptedPlies" to coverage.acceptedPlayedRoots.joinToString(",") { it.ply.toString() },
            "missingPlies" to coverage.missingPlayedRoots.joinToString(",") { it.ply.toString() },
            "requiredAdjacentPlies" to coverage.requiredAdjacent.joinToString(",") { it.ply.toString() },
            "acceptedAdjacentPlies" to coverage.acceptedAdjacent.joinToString(",") { it.ply.toString() },
            "missingAdjacentPlies" to coverage.missingAdjacent.joinToString(",") { it.ply.toString() },
            "fullyCovered" to coverage.fullyCoveredPlayedMoves.toString(),
            "pending" to coverage.pendingWorkCount.toString(),
        ) + extra,
    )

    private fun runtimeRootAuditLine(
        event: String,
        ply: Int,
        kind: String,
        positionId: String,
    ): String = reviewAuditJson(
        event = event,
        fields = mapOf(
            "source" to "runtime",
            "ply" to ply.toString(),
            "kind" to kind,
            "positionId" to positionId,
        ),
    )

    private fun runtimeSeedCoverageAuditLine(coverage: PlayerGameReviewSeedCoverage): String = reviewAuditJson(
        event = "seed_coverage_materialized",
        fields = mapOf(
            "source" to "runtime",
            "expectedPlies" to coverage.expectedPlies.joinToString(","),
            "exactSeededPlies" to coverage.exactSeededPlies.joinToString(","),
            "adjacentRequiredPlies" to coverage.adjacentRequiredPlies.joinToString(","),
            "adjacentSeededPlies" to coverage.adjacentSeededPlies.joinToString(","),
            "materializablePlies" to coverage.materializablePlies.joinToString(","),
            "missingExactPlies" to coverage.missingExactPlies.joinToString(","),
            "missingAdjacentPlies" to coverage.missingAdjacentPlies.joinToString(","),
        ),
    )

    private fun runtimeSearchAuditLine(submission: GameReviewSearchSubmission): String = reviewAuditJson(
        event = "postgame_search_submitted",
        fields = mapOf(
            "source" to "runtime",
            "ply" to submission.ply.toString(),
            "kind" to submission.kind.name.lowercase(),
            "rootPositionId" to submission.rootPositionId,
            "playedMove" to submission.playedMove?.value,
            "positionId" to submission.positionId,
            "requestId" to submission.requestId,
        ),
    )

    private fun runtimeReviewResultAuditLine(
        ready: Boolean,
        reviewedMoves: Int,
        error: String? = null,
    ): String = reviewAuditJson(
        event = if (ready) "review_ready" else "review_failed",
        fields = mapOf(
            "source" to "runtime",
            "reviewedMoves" to reviewedMoves.toString(),
            "postGameSearches" to reviewPostGameSubmissions.size.toString(),
            "error" to error,
        ),
    )

    private fun reviewAuditJson(event: String, fields: Map<String, String?>): String {
        val values = linkedMapOf<String, String?>()
        values["timestampEpochMillis"] = Clock.System.now().toEpochMilliseconds().toString()
        values["event"] = event
        values["gameId"] = config.gameId
        values.putAll(fields)
        return values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val encodedValue = value?.let { "\"${reviewAuditJsonEscape(it)}\"" } ?: "null"
            "\"${reviewAuditJsonEscape(key)}\":$encodedValue"
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

/** Thread-independent, immutable input for platform checkpoint persistence. */
class SharedCheckpointSnapshot internal constructor(
    private val checkpoint: CoordinatorCheckpoint,
) {
    val revision: Long get() = checkpoint.revision
    val gameId: String get() = checkpoint.config.gameId

    fun encodeJson(): String = SharedCheckpointCodec.encode(checkpoint)
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
    val reviewProjectedMoves: List<SharedReviewMove>,
)

private data class RuntimePresentationInputs(
    val coordinatorRevision: Long,
    val asyncRevision: Long,
    val interactionRevision: Long,
)

private data class SharedReviewFrame(
    val ply: Int,
    val moveNumber: Int,
    val mover: Side,
    val playerDecision: Boolean,
    val playedMove: UciMove,
    val playedSan: String,
    val fenBefore: String,
    val fenAfter: String,
    val cells: List<SharedBoardCell>,
)

private fun buildSharedReviewFrames(
    initialFen: String,
    moves: List<UciMove>,
    humanSide: Side,
    shouldContinue: () -> Boolean,
): List<SharedReviewFrame>? {
    val orientation = BoardOrientation.forSide(humanSide)
    var position = ChessPosition.fromFen(initialFen)
    val frames = ArrayList<SharedReviewFrame>(moves.size)
    for ((index, playedMove) in moves.withIndex()) {
        if (!shouldContinue()) return null
        val before = position
        val chessMove = ChessMove.fromUci(playedMove)
        val playedSan = SanNotation.format(before, playedMove)
        position = ChessRules.apply(before, chessMove)
        frames += SharedReviewFrame(
            ply = index + 1,
            moveNumber = before.fullmoveNumber,
            mover = before.sideToMove,
            playerDecision = before.sideToMove == humanSide,
            playedMove = playedMove,
            playedSan = playedSan,
            fenBefore = before.fen(),
            fenAfter = position.fen(),
            cells = sharedReviewBoardCells(position, chessMove, orientation),
        )
    }
    return frames
}

private fun sharedReviewBoardCells(
    position: ChessPosition,
    lastMove: ChessMove,
    orientation: BoardOrientation,
): List<SharedBoardCell> {
    val checkedKing = if (ChessRules.isInCheck(position)) {
        position.pieces().single { (_, piece) ->
            piece.side == position.sideToMove && piece.type == PieceType.KING
        }.first
    } else {
        null
    }
    return buildList(64) {
        for (row in 0..7) for (column in 0..7) {
            val displayIndex = row * 8 + column
            val square = orientation.squareAt(row, column)
            val piece = position[square]
            val inCheck = checkedKing == square
            add(
                SharedBoardCell(
                    displayIndex = displayIndex,
                    square = square.algebraic,
                    pieceSymbol = piece?.symbol().orEmpty(),
                    pieceCode = piece?.code().orEmpty(),
                    darkSquare = (square.file + square.rank).isEven,
                    selected = false,
                    legalTarget = false,
                    captureTarget = false,
                    lastMove = square == lastMove.from || square == lastMove.to,
                    inCheck = inCheck,
                    threatened = false,
                    accessibilityLabel = com.drawlesschess.core.presentation.SquareAccessibilityFacts(
                        square = square,
                        piece = piece,
                        target = null,
                        inCheck = inCheck,
                        threatened = false,
                    ).label(),
                ),
            )
        }
    }
}

private fun SharedReviewFrame.sharedProjection(reviewed: ReviewedMove?): SharedReviewMove {
    reviewed?.let {
        require(it.ply == ply && it.mover == mover && it.playedMove == playedMove)
        require(it.fenBefore == fenBefore && it.fenAfter == fenAfter)
    }
    val bestMove = reviewed?.bestMove ?: playedMove
    val bestMoveSan = reviewed?.let {
        val positionBefore = ChessPosition.fromFen(fenBefore)
        runCatching { SanNotation.format(positionBefore, it.bestMove) }.getOrNull()
    }
    val arrow = reviewed
        ?.takeIf {
            playerDecision &&
                it.quality != null &&
                it.quality != ReviewMoveQuality.BEST &&
                it.bestMove != it.playedMove &&
                bestMoveSan != null
        }
        ?.let { ChessMove.fromUci(it.bestMove) }
    return SharedReviewMove(
        ply = ply,
        moveNumber = moveNumber,
        mover = mover.name,
        playerDecision = playerDecision,
        playedMove = playedMove.value,
        playedSan = playedSan,
        bestMove = bestMove.value,
        bestMoveSan = bestMoveSan,
        quality = reviewed?.quality?.name,
        expectedPointLoss = reviewed?.expectedPointLoss,
        bestEvaluationKind = reviewed?.bestEvaluation?.sharedKind(),
        bestEvaluationValue = reviewed?.bestEvaluation?.sharedValue(),
        bestEvaluationText = reviewed?.bestEvaluation?.sharedText(),
        playedEvaluationKind = reviewed?.playedEvaluation?.sharedKind(),
        playedEvaluationValue = reviewed?.playedEvaluation?.sharedValue(),
        playedEvaluationText = reviewed?.playedEvaluation?.sharedText(),
        suggestedLine = reviewed?.suggestedLine?.map { it.value }.orEmpty(),
        suggestedLineSan = reviewed?.let {
            reviewSuggestedLineSan(fenBefore, it.suggestedLine)
        }.orEmpty(),
        fenBefore = fenBefore,
        fenAfter = fenAfter,
        cells = cells,
        betterMoveFromSquare = arrow?.from?.algebraic,
        betterMoveToSquare = arrow?.to?.algebraic,
    )
}

private fun reviewSuggestedLineSan(initialFen: String, moves: List<UciMove>): List<String> {
    var position = ChessPosition.fromFen(initialFen)
    val notation = mutableListOf<String>()
    for (move in moves.take(4)) {
        if (move !in ChessRules.legalUciMoves(position)) break
        notation += SanNotation.format(position, move)
        position = ChessRules.apply(position, ChessMove.fromUci(move))
    }
    return notation
}

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
    private val lock = ConcurrentLock()
    private var value: CoordinatorCheckpoint? = null

    val latest: CoordinatorCheckpoint?
        get() = lock.withLock { value }

    override fun persist(checkpoint: CoordinatorCheckpoint) {
        lock.withLock { value = checkpoint }
    }
}

/** Deterministic shared-rules engine used by non-Apple host tests. */
internal class DeterministicOfflineEngine : RuntimeChessEngine {
    override val reviewEvidenceBuildId: String = "2"
    override val reviewEvidencePatchVersion: Int = 2

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        val result = runCatching {
            val position = ChessAdapter.replay(request.initialFen, request.moves)
            val ranked = rankedMoves(position)
            val requestedRoots = request.searchMoves.toSet()
            val candidates = if (requestedRoots.isEmpty()) {
                ranked.take(request.limits.multiPv)
            } else {
                ranked.filter { move -> move.toUci() in requestedRoots }
                    .also { constrained ->
                        require(constrained.size == requestedRoots.size) {
                            "Constrained Review request contains an illegal root move"
                        }
                    }
                    .take(request.limits.multiPv)
            }
            val move = candidates.first()
            val encoded = move.toUci()
            EngineResponse(
                requestId = request.requestId,
                gameId = request.gameId,
                positionId = request.positionId,
                bestMove = encoded,
                ponderMove = null,
                depth = 1,
                nodes = ChessRules.legalMoves(position).size.toLong(),
                variations = candidates.mapIndexed { index, candidate ->
                    PrincipalVariation(
                        scoreCentipawns = -index,
                        mateIn = null,
                        moves = listOf(candidate.toUci()),
                        rank = index + 1,
                    )
                },
                engine = EngineIdentity("shared-rules-test-engine", "2", 2),
            )
        }
        onResult(result)
        return EngineCancellation {}
    }

    override fun close() = Unit

    private fun rankedMoves(position: ChessPosition): List<ChessMove> = ChessRules.legalMoves(position)
        .sortedWith(
            compareByDescending<ChessMove> { move -> moveScore(position, move) }
                .thenBy { it.toUci().value },
        )
        .ifEmpty { error("No legal engine move") }

    private fun moveScore(position: ChessPosition, move: ChessMove): Int {
        val captured = position[move.to]?.type?.value() ?: 0
        val after = ChessRules.apply(position, move)
        return captured * 100 +
            (if (move.promotion == PieceType.QUEEN) 900 else 0) +
            (if (ChessRules.isCheckmate(after)) 100_000 else if (ChessRules.isInCheck(after)) 50 else 0)
    }
}

/**
 * Each accepted exact or adjacent prefetch root advances [revision] once when it is persisted.
 * Subtracting the retained evidence count therefore removes only those invisible increments.
 * A restored checkpoint can start at any offset; the runtime compares subsequent values, so the
 * offset is irrelevant while later evidence and visible mutations remain distinguishable.
 */
private fun CoordinatorCheckpoint.visibleCoordinatorRevision(): Long =
    revision - reviewPrefetchRoots.size.toLong() - reviewPrefetchAdjacentRoots.size.toLong()

private fun CoordinatorCheckpoint.withCompatibleReviewPrefetchEvidence(
    engineBuildId: String,
    enginePatchVersion: Int,
): CoordinatorCheckpoint {
    fun EngineResponse.matchesCurrentEngine(): Boolean =
        engine.build == engineBuildId && engine.drawlessPatch == enginePatchVersion

    val roots = reviewPrefetchRoots.filter { seed -> seed.response.matchesCurrentEngine() }
    val adjacentRoots = reviewPrefetchAdjacentRoots.filter { seed ->
        seed.response.matchesCurrentEngine() && roots.any { it.key == seed.key.rootKey }
    }
    return if (roots.size == reviewPrefetchRoots.size &&
        adjacentRoots.size == reviewPrefetchAdjacentRoots.size
    ) {
        this
    } else {
        copy(reviewPrefetchRoots = roots, reviewPrefetchAdjacentRoots = adjacentRoots)
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

internal const val SHARED_BOT_MOVE_PRESENTATION_MILLIS = 500L

private const val REVIEW_STAGE_IDLE = "IDLE"
private const val REVIEW_STAGE_PREPARING = "PREPARING"
private const val REVIEW_STAGE_FINALIZING = "FINALIZING"
private const val REVIEW_STAGE_ANALYZING = "ANALYZING"
private const val REVIEW_STAGE_READY = "READY"
private const val REVIEW_STAGE_FAILED = "FAILED"

private fun reviewAuditJsonEscape(value: String): String = buildString(value.length + 8) {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}

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
