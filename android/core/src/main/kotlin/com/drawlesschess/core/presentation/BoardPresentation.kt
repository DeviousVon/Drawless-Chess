package com.drawlesschess.core.presentation

import com.drawlesschess.core.*
import com.drawlesschess.core.chess.*
import com.drawlesschess.core.coordinator.CoordinatorPhase
import com.drawlesschess.core.coordinator.CoordinatorSnapshot
import com.drawlesschess.core.coordinator.GameConfig

enum class BoardOrientation {
    WHITE_AT_BOTTOM,
    BLACK_AT_BOTTOM;

    fun flipped(): BoardOrientation = if (this == WHITE_AT_BOTTOM) BLACK_AT_BOTTOM else WHITE_AT_BOTTOM

    fun squareAt(displayRow: Int, displayColumn: Int): Square {
        require(displayRow in 0..7 && displayColumn in 0..7)
        return when (this) {
            WHITE_AT_BOTTOM -> Square.at(displayColumn, 7 - displayRow)!!
            BLACK_AT_BOTTOM -> Square.at(7 - displayColumn, displayRow)!!
        }
    }

    fun displayCoordinates(square: Square): Pair<Int, Int> = when (this) {
        WHITE_AT_BOTTOM -> (7 - square.rank) to square.file
        BLACK_AT_BOTTOM -> square.rank to (7 - square.file)
    }

    companion object {
        fun forSide(side: Side): BoardOrientation =
            if (side == Side.WHITE) WHITE_AT_BOTTOM else BLACK_AT_BOTTOM
    }
}

enum class TargetKind { QUIET, CAPTURE }

data class PromotionPrompt(
    val from: Square,
    val to: Square,
    val choices: List<PieceType>,
)

data class BoardInteractionState(
    val positionMarker: String,
    val orientation: BoardOrientation,
    val selected: Square? = null,
    val draggingFrom: Square? = null,
    val promotionPrompt: PromotionPrompt? = null,
) {
    companion object {
        fun initial(position: ChessPosition, humanSide: Side) = BoardInteractionState(
            positionMarker = position.fen(),
            orientation = BoardOrientation.forSide(humanSide),
        )
    }
}

sealed interface BoardEvent {
    data class TapSquare(val square: Square) : BoardEvent
    data class DragStarted(val square: Square) : BoardEvent
    data class Dropped(val square: Square) : BoardEvent
    data object DragCancelled : BoardEvent
    data class PromotionChosen(val piece: PieceType) : BoardEvent
    data object PromotionCancelled : BoardEvent
    data object FlipBoard : BoardEvent
}

sealed interface BoardAction {
    data class SubmitMove(val move: UciMove) : BoardAction
}

data class BoardReduction(
    val state: BoardInteractionState,
    val action: BoardAction? = null,
)

data class BoardInteractionContext(
    val position: ChessPosition,
    val interactive: Boolean,
)

object BoardInteractionReducer {
    fun reconcile(
        context: BoardInteractionContext,
        state: BoardInteractionState,
    ): BoardInteractionState = if (state.positionMarker == context.position.fen()) state else state.copy(
        positionMarker = context.position.fen(),
        selected = null,
        draggingFrom = null,
        promotionPrompt = null,
    )

    fun reduce(
        context: BoardInteractionContext,
        current: BoardInteractionState,
        event: BoardEvent,
    ): BoardReduction {
        val state = reconcile(context, current)
        if (event == BoardEvent.FlipBoard) {
            return BoardReduction(state.copy(orientation = state.orientation.flipped()))
        }
        if (!context.interactive) return BoardReduction(state)

        val prompt = state.promotionPrompt
        if (prompt != null) {
            return when (event) {
                is BoardEvent.PromotionChosen -> {
                    if (event.piece !in prompt.choices) BoardReduction(state)
                    else BoardReduction(
                        state.copy(selected = null, draggingFrom = null, promotionPrompt = null),
                        BoardAction.SubmitMove(ChessMove(prompt.from, prompt.to, event.piece).toUci()),
                    )
                }
                BoardEvent.PromotionCancelled -> BoardReduction(state.copy(promotionPrompt = null))
                else -> BoardReduction(state)
            }
        }

        return when (event) {
            is BoardEvent.TapSquare -> tap(context, state, event.square)
            is BoardEvent.DragStarted -> dragStart(context, state, event.square)
            is BoardEvent.Dropped -> drop(context, state, event.square)
            BoardEvent.DragCancelled -> BoardReduction(state.copy(draggingFrom = null))
            is BoardEvent.PromotionChosen, BoardEvent.PromotionCancelled, BoardEvent.FlipBoard -> BoardReduction(state)
        }
    }

    private fun tap(
        context: BoardInteractionContext,
        state: BoardInteractionState,
        square: Square,
    ): BoardReduction {
        val selected = state.selected
        val piece = context.position[square]
        if (selected == null) {
            return if (piece?.side == context.position.sideToMove) {
                BoardReduction(state.copy(selected = square))
            } else BoardReduction(state)
        }
        if (square == selected) {
            return BoardReduction(state.copy(selected = null, draggingFrom = null))
        }
        if (piece?.side == context.position.sideToMove) {
            return BoardReduction(state.copy(selected = square, draggingFrom = null))
        }
        return attempt(context, state, selected, square)
    }

    private fun dragStart(
        context: BoardInteractionContext,
        state: BoardInteractionState,
        square: Square,
    ): BoardReduction {
        val piece = context.position[square]
        return if (piece?.side == context.position.sideToMove) {
            BoardReduction(state.copy(selected = square, draggingFrom = square))
        } else BoardReduction(state)
    }

    private fun drop(
        context: BoardInteractionContext,
        state: BoardInteractionState,
        square: Square,
    ): BoardReduction {
        val from = state.draggingFrom ?: return BoardReduction(state)
        val attempted = attempt(context, state, from, square)
        return attempted.copy(state = attempted.state.copy(draggingFrom = null))
    }

    private fun attempt(
        context: BoardInteractionContext,
        state: BoardInteractionState,
        from: Square,
        to: Square,
    ): BoardReduction {
        val matches = ChessRules.legalMoves(context.position).filter { it.from == from && it.to == to }
        if (matches.isEmpty()) return BoardReduction(state)
        val promotions = matches.mapNotNull { it.promotion }
        if (promotions.isNotEmpty()) {
            val ordered = listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
                .filter { it in promotions }
            return BoardReduction(state.copy(promotionPrompt = PromotionPrompt(from, to, ordered)))
        }
        return BoardReduction(
            state.copy(selected = null, draggingFrom = null),
            BoardAction.SubmitMove(matches.single().toUci()),
        )
    }
}

data class PieceView(
    val side: Side,
    val type: PieceType,
    val assetKey: String,
)

data class PieceMotion(
    val from: Square,
    val to: Square,
    val piece: PieceView,
)

data class BoardMoveMotion(
    val ply: Int,
    val mover: Side,
    val pieces: List<PieceMotion>,
) {
    init {
        require(ply > 0)
        require(pieces.isNotEmpty())
    }
}

data class SquareView(
    val square: Square,
    val displayRow: Int,
    val displayColumn: Int,
    val piece: PieceView?,
    val selected: Boolean,
    val target: TargetKind?,
    val lastMove: Boolean,
    val inCheck: Boolean,
    val threatened: Boolean,
    val accessibility: SquareAccessibilityFacts,
)

/** Locale-neutral facts from which the Android UI builds an accessibility description. */
data class SquareAccessibilityFacts(
    val square: Square,
    val piece: Piece?,
    val target: TargetKind?,
    val inCheck: Boolean,
    val threatened: Boolean,
)

enum class BoardStatus {
    HUMAN_TURN,
    HINT_THINKING,
    BOT_THINKING,
    BOT_ERROR,
    PAUSED,
    COMPLETED,
}

data class BoardScreenState(
    val positionMarker: String,
    val plyCount: Int,
    val humanSide: Side,
    val sideToMove: Side,
    val cells: List<SquareView>,
    val interaction: BoardInteractionState,
    val interactive: Boolean,
    val phase: CoordinatorPhase,
    val status: BoardStatus,
    val theme: BoardTheme,
    val pieceSet: PieceSet,
    val promotionPrompt: PromotionPrompt?,
    val moveMotion: BoardMoveMotion?,
)

object BoardPresenter {
    fun present(
        snapshot: CoordinatorSnapshot,
        config: GameConfig,
        interactionState: BoardInteractionState,
        theme: BoardTheme = BoardThemes.DEFAULT,
        pieceSet: PieceSet = PieceSets.MODERN_FLAT,
        threatIndicationEnabled: Boolean = false,
    ): BoardScreenState {
        val position = ChessPosition.fromFen(snapshot.currentFen)
        val interactive = snapshot.phase == CoordinatorPhase.HUMAN_TURN &&
            snapshot.session.sideToMove == config.humanSide
        val context = BoardInteractionContext(position, interactive)
        val interaction = BoardInteractionReducer.reconcile(context, interactionState)
        val legalByTarget = if (interactive && interaction.selected != null) {
            ChessRules.legalMoves(position)
                .filter { it.from == interaction.selected }
                .groupBy { it.to }
        } else emptyMap()
        val lastRecord = snapshot.session.moves.lastOrNull()
        val lastMove = lastRecord?.move?.let(ChessMove::fromUci)
        val moveMotion = lastRecord?.let { motionFor(it, position, pieceSet) }
        val threatenedPieces = if (threatIndicationEnabled) {
            ThreatIndicators.threatenedPieces(position, config.humanSide)
        } else {
            emptySet()
        }
        val checkedKing = if (ChessRules.isInCheck(position)) {
            position.pieces().single { (_, piece) ->
                piece.side == position.sideToMove && piece.type == PieceType.KING
            }.first
        } else null

        val cells = buildList {
            for (row in 0..7) for (column in 0..7) {
                val square = interaction.orientation.squareAt(row, column)
                val piece = position[square]
                val moves = legalByTarget[square]
                val target = when {
                    moves == null -> null
                    isCapture(position, moves.first()) -> TargetKind.CAPTURE
                    else -> TargetKind.QUIET
                }
                add(SquareView(
                    square = square,
                    displayRow = row,
                    displayColumn = column,
                    piece = piece?.let { PieceView(it.side, it.type, pieceSet.assetKey(it)) },
                    selected = interaction.selected == square,
                    target = target,
                    lastMove = lastMove?.let { square == it.from || square == it.to } == true,
                    inCheck = checkedKing == square,
                    threatened = square in threatenedPieces,
                    accessibility = SquareAccessibilityFacts(
                        square = square,
                        piece = piece,
                        target = target,
                        inCheck = checkedKing == square,
                        threatened = square in threatenedPieces,
                    ),
                ))
            }
        }
        return BoardScreenState(
            positionMarker = position.fen(),
            plyCount = snapshot.session.moves.size,
            humanSide = config.humanSide,
            sideToMove = position.sideToMove,
            cells = cells,
            interaction = interaction,
            interactive = interactive,
            phase = snapshot.phase,
            status = status(snapshot),
            theme = theme,
            pieceSet = pieceSet,
            promotionPrompt = interaction.promotionPrompt,
            moveMotion = moveMotion,
        )
    }

    private fun motionFor(
        record: MoveRecord,
        position: ChessPosition,
        pieceSet: PieceSet,
    ): BoardMoveMotion {
        val move = ChessMove.fromUci(record.move)
        val primaryType = if (move.promotion != null) {
            PieceType.PAWN
        } else {
            requireNotNull(position[move.to]) { "Moved piece is absent from ${move.to.algebraic}" }.type
        }
        val pieces = mutableListOf(
            PieceMotion(
                from = move.from,
                to = move.to,
                piece = PieceView(
                    side = record.mover,
                    type = primaryType,
                    assetKey = pieceSet.assetKey(Piece(record.mover, primaryType)),
                ),
            ),
        )
        if (primaryType == PieceType.KING && kotlin.math.abs(move.to.file - move.from.file) == 2) {
            val rank = move.from.rank
            val kingSide = move.to.file == 6
            val rookFrom = Square.at(if (kingSide) 7 else 0, rank)!!
            val rookTo = Square.at(if (kingSide) 5 else 3, rank)!!
            pieces += PieceMotion(
                from = rookFrom,
                to = rookTo,
                piece = PieceView(
                    side = record.mover,
                    type = PieceType.ROOK,
                    assetKey = pieceSet.assetKey(Piece(record.mover, PieceType.ROOK)),
                ),
            )
        }
        return BoardMoveMotion(record.ply, record.mover, pieces)
    }

    private fun isCapture(position: ChessPosition, move: ChessMove): Boolean {
        if (position[move.to] != null) return true
        return position[move.from]?.type == PieceType.PAWN && move.from.file != move.to.file
    }

    private fun status(snapshot: CoordinatorSnapshot): BoardStatus = when (snapshot.phase) {
        CoordinatorPhase.HUMAN_TURN -> BoardStatus.HUMAN_TURN
        CoordinatorPhase.HINT_THINKING -> BoardStatus.HINT_THINKING
        CoordinatorPhase.BOT_THINKING -> BoardStatus.BOT_THINKING
        CoordinatorPhase.BOT_ERROR -> BoardStatus.BOT_ERROR
        CoordinatorPhase.PAUSED -> BoardStatus.PAUSED
        CoordinatorPhase.COMPLETED -> BoardStatus.COMPLETED
    }
}
