package com.drawlesschess.core.presentation

import com.drawlesschess.core.*
import com.drawlesschess.core.chess.*
import com.drawlesschess.core.coordinator.*

data class ClockView(
    val text: String,
    val active: Boolean,
    val lowTime: Boolean,
)

data class GameControlsView(
    val canPause: Boolean,
    val paused: Boolean,
    val canUndo: Boolean,
    val canHint: Boolean,
    val canResign: Boolean,
    val canFlip: Boolean = true,
)

data class GameResultView(
    val playerWon: Boolean,
    val playerSide: Side,
    val winner: Side,
    val reason: EndReason,
    val score: GameScore,
    val rules: RulesContractV1 = RulesContractV1.drawless(),
    val adjudicationFacts: PositionFacts? = null,
)

sealed interface GameNotice {
    data object ActionUnavailable : GameNotice
    /** Already-localized presentation supplied by the Android UI. */
    data class External(val message: String) : GameNotice
}

data class GameScreenModel(
    val board: BoardScreenState,
    val whiteClock: ClockView,
    val blackClock: ClockView,
    val history: List<MoveHistoryRow>,
    val capturedMaterial: CapturedMaterialView,
    val controls: GameControlsView,
    val rulesPreset: RulesContractV1.Preset,
    val mode: GameMode,
    val result: GameResultView?,
    val transientNotice: GameNotice?,
)

sealed interface GameUiEffect {
    data class RequestHintAnalysis(
        val positionId: String,
        val currentFen: String,
    ) : GameUiEffect
}

private data class TimelineCache(
    val moves: List<UciMove>,
    val timeline: GameTimelineView,
)

class GameScreenController(
    private val coordinator: GameCoordinator,
    private val config: GameConfig,
    private val onEffect: (GameUiEffect) -> Unit = {},
    initialTheme: BoardTheme = BoardThemes.DEFAULT,
    initialPieceSet: PieceSet = PieceSets.MODERN_FLAT,
    private val threatIndicationEnabled: Boolean = false,
) {
    private var theme = initialTheme
    private var pieceSet = initialPieceSet
    private var interaction = BoardInteractionState.initial(
        ChessPosition.fromFen(coordinator.snapshot().currentFen),
        config.humanSide,
    )
    private var transientNotice: GameNotice? = null
    private var timelineCache: TimelineCache? = null

    @Synchronized
    fun model(): GameScreenModel {
        val snapshot = coordinator.snapshot()
        val board = BoardPresenter.present(
            snapshot = snapshot,
            config = config,
            interactionState = interaction,
            theme = theme,
            pieceSet = pieceSet,
            threatIndicationEnabled = threatIndicationEnabled,
        )
        val timeline = timeline(snapshot.session.moves)
        interaction = board.interaction
        return GameScreenModel(
            board = board,
            whiteClock = clockView(snapshot.clock.whiteRemainingMillis, snapshot.clock.runningSide == Side.WHITE),
            blackClock = clockView(snapshot.clock.blackRemainingMillis, snapshot.clock.runningSide == Side.BLACK),
            history = timeline.history,
            capturedMaterial = timeline.capturedMaterial,
            controls = controls(snapshot),
            rulesPreset = config.rules.preset,
            mode = config.mode,
            result = snapshot.session.outcome?.let { outcome ->
                GameResultView(
                    playerWon = outcome.winner == config.humanSide,
                    playerSide = config.humanSide,
                    winner = outcome.winner,
                    reason = outcome.reason,
                    score = GameScoring.forResult(
                        playerWon = outcome.winner == config.humanSide,
                        assistance = snapshot.assistance,
                        timeControl = config.timeControl,
                    ),
                    rules = config.rules,
                    adjudicationFacts = snapshot.session.adjudicationFacts,
                )
            },
            transientNotice = transientNotice,
        )
    }

    @Synchronized
    fun boardEvent(event: BoardEvent): GameScreenModel {
        val snapshot = coordinator.snapshot()
        val position = ChessPosition.fromFen(snapshot.currentFen)
        val context = BoardInteractionContext(
            position = position,
            interactive = snapshot.phase == CoordinatorPhase.HUMAN_TURN &&
                snapshot.session.sideToMove == config.humanSide,
            selectionSide = config.humanSide,
            preselectionEnabled = snapshot.phase == CoordinatorPhase.BOT_THINKING,
        )
        val reduction = BoardInteractionReducer.reduce(context, interaction, event)
        interaction = reduction.state
        val action = reduction.action
        if (action is BoardAction.SubmitMove) runUiAction { coordinator.playHuman(action.move) }
        return model()
    }

    @Synchronized
    fun tick(): GameScreenModel {
        coordinator.tick()
        return model()
    }

    @Synchronized
    fun pauseOrResume(): GameScreenModel {
        runUiAction {
            if (coordinator.snapshot().phase == CoordinatorPhase.PAUSED) coordinator.resume() else coordinator.pause()
        }
        return model()
    }

    @Synchronized
    fun undo(): GameScreenModel {
        runUiAction { coordinator.undoLastHumanTurn() }
        return model()
    }

    @Synchronized
    fun hint(): GameScreenModel {
        runUiAction {
            val snapshot = coordinator.snapshot()
            onEffect(GameUiEffect.RequestHintAnalysis(snapshot.session.positionId, snapshot.currentFen))
        }
        return model()
    }

    @Synchronized
    fun resign(): GameScreenModel {
        runUiAction { coordinator.resignHuman() }
        return model()
    }

    @Synchronized
    fun retryBot(): GameScreenModel {
        runUiAction { coordinator.retryBot() }
        return model()
    }

    @Synchronized
    fun selectTheme(value: BoardTheme): GameScreenModel {
        theme = value
        return model()
    }

    @Synchronized
    fun selectPieceSet(value: PieceSet): GameScreenModel {
        pieceSet = value
        return model()
    }

    @Synchronized
    fun dismissMessage(): GameScreenModel {
        transientNotice = null
        return model()
    }

    @Synchronized
    fun showMessage(message: String): GameScreenModel {
        transientNotice = GameNotice.External(message)
        return model()
    }

    private fun runUiAction(action: () -> Unit) {
        try {
            transientNotice = null
            action()
        } catch (error: IllegalArgumentException) {
            transientNotice = GameNotice.ActionUnavailable
        } catch (error: IllegalStateException) {
            transientNotice = GameNotice.ActionUnavailable
        }
    }

    private fun timeline(records: List<MoveRecord>): GameTimelineView {
        val moves = records.map { it.move }
        timelineCache?.takeIf { it.moves == moves }?.let { return it.timeline }
        return GameHistoryPresenter.present(config.initialFen, moves).also { timeline ->
            timelineCache = TimelineCache(moves.toList(), timeline)
        }
    }

    private fun controls(snapshot: CoordinatorSnapshot): GameControlsView {
        val complete = snapshot.phase == CoordinatorPhase.COMPLETED
        val casual = config.mode == GameMode.CASUAL
        return GameControlsView(
            canPause = casual && !complete,
            paused = snapshot.phase == CoordinatorPhase.PAUSED,
            canUndo = casual && !complete && snapshot.session.moves.any { it.mover == config.humanSide },
            canHint = casual && snapshot.phase == CoordinatorPhase.HUMAN_TURN,
            canResign = !complete,
        )
    }

    companion object {
        fun clockView(remainingMillis: Long?, active: Boolean): ClockView {
            if (remainingMillis == null) return ClockView("∞", active = false, lowTime = false)
            val text = if (remainingMillis < 10_000) {
                val tenths = (remainingMillis.coerceAtLeast(0) / 100) % 10
                "${remainingMillis.coerceAtLeast(0) / 1_000}.$tenths"
            } else {
                val totalSeconds = (remainingMillis + 999) / 1_000
                val hours = totalSeconds / 3_600
                val minutes = (totalSeconds % 3_600) / 60
                val seconds = totalSeconds % 60
                if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
                else "%d:%02d".format(minutes, seconds)
            }
            return ClockView(text, active, remainingMillis < 10_000)
        }

        fun moveHistory(initialFen: String, moves: List<UciMove>): List<MoveHistoryRow> =
            GameHistoryPresenter.present(initialFen, moves).history

        fun capturedMaterial(initialFen: String, moves: List<UciMove>): CapturedMaterialView =
            GameHistoryPresenter.present(initialFen, moves).capturedMaterial
    }
}
