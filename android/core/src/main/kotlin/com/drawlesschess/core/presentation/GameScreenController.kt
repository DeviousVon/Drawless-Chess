package com.drawlesschess.core.presentation

import com.drawlesschess.core.*
import com.drawlesschess.core.chess.*
import com.drawlesschess.core.coordinator.*

data class MoveHistoryRow(
    val moveNumber: Int,
    val white: String?,
    val black: String?,
)

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
    val reason: EndReason,
    val explanation: String,
)

data class GameScreenModel(
    val board: BoardScreenState,
    val whiteClock: ClockView,
    val blackClock: ClockView,
    val history: List<MoveHistoryRow>,
    val controls: GameControlsView,
    val rulesLabel: String,
    val modeLabel: String,
    val result: GameResultView?,
    val transientMessage: String?,
)

sealed interface GameUiEffect {
    data class RequestHintAnalysis(
        val positionId: String,
        val currentFen: String,
    ) : GameUiEffect
}

class GameScreenController(
    private val coordinator: GameCoordinator,
    private val config: GameConfig,
    private val onEffect: (GameUiEffect) -> Unit = {},
    initialTheme: BoardTheme = BoardThemes.OBSIDIAN_GLASS,
    initialPieceSet: PieceSet = PieceSets.MODERN_FLAT,
) {
    private var theme = initialTheme
    private var pieceSet = initialPieceSet
    private var interaction = BoardInteractionState.initial(
        ChessPosition.fromFen(coordinator.snapshot().currentFen),
        config.humanSide,
    )
    private var transientMessage: String? = null

    @Synchronized
    fun model(): GameScreenModel {
        val snapshot = coordinator.snapshot()
        val board = BoardPresenter.present(snapshot, config, interaction, theme, pieceSet)
        interaction = board.interaction
        return GameScreenModel(
            board = board,
            whiteClock = clockView(snapshot.clock.whiteRemainingMillis, snapshot.clock.runningSide == Side.WHITE),
            blackClock = clockView(snapshot.clock.blackRemainingMillis, snapshot.clock.runningSide == Side.BLACK),
            history = moveHistory(config.initialFen, snapshot.session.moves.map { it.move }),
            controls = controls(snapshot),
            rulesLabel = if (config.rules.preset == RulesContractV1.Preset.DRAWLESS) "Drawless" else "Escape",
            modeLabel = if (config.mode == GameMode.RATED) "Rated" else "Casual",
            result = snapshot.session.outcome?.let { outcome ->
                GameResultView(
                    playerWon = outcome.winner == config.humanSide,
                    playerSide = config.humanSide,
                    reason = outcome.reason,
                    explanation = outcome.explanation,
                )
            },
            transientMessage = transientMessage,
        )
    }

    @Synchronized
    fun boardEvent(event: BoardEvent): GameScreenModel {
        val snapshot = coordinator.snapshot()
        val position = ChessPosition.fromFen(snapshot.currentFen)
        val context = BoardInteractionContext(
            position,
            snapshot.phase == CoordinatorPhase.HUMAN_TURN && snapshot.session.sideToMove == config.humanSide,
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
        transientMessage = null
        return model()
    }

    @Synchronized
    fun showMessage(message: String): GameScreenModel {
        transientMessage = message
        return model()
    }

    private fun runUiAction(action: () -> Unit) {
        try {
            transientMessage = null
            action()
        } catch (error: IllegalArgumentException) {
            transientMessage = error.message ?: "Action unavailable"
        } catch (error: IllegalStateException) {
            transientMessage = error.message ?: "Action unavailable"
        }
    }

    private fun controls(snapshot: CoordinatorSnapshot): GameControlsView {
        val complete = snapshot.phase == CoordinatorPhase.COMPLETED
        val casual = config.mode == GameMode.CASUAL
        return GameControlsView(
            canPause = casual && !complete,
            paused = snapshot.phase == CoordinatorPhase.PAUSED,
            canUndo = casual && snapshot.session.moves.any { it.mover == config.humanSide },
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

        fun moveHistory(initialFen: String, moves: List<UciMove>): List<MoveHistoryRow> {
            var position = ChessPosition.fromFen(initialFen)
            val san = moves.map { move ->
                val notation = SanNotation.format(position, move)
                position = ChessRules.apply(position, move)
                notation
            }
            return san.chunked(2).mapIndexed { index, pair ->
                MoveHistoryRow(index + 1, pair.getOrNull(0), pair.getOrNull(1))
            }
        }
    }
}
