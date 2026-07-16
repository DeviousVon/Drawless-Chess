package com.drawlesschess.core.presentation

import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.Piece
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.chess.SanNotation
import com.drawlesschess.core.chess.Square
import kotlin.math.abs

enum class CastleSide { KING_SIDE, QUEEN_SIDE }

/** Locale-neutral move facts from which the Android UI builds an accessibility description. */
data class MoveAccessibilityFacts(
    val moveNumber: Int,
    val mover: Side,
    val movingPiece: PieceType,
    val from: Square,
    val to: Square,
    val capturedSide: Side?,
    val capturedPiece: PieceType?,
    val capturedSquare: Square?,
    val enPassant: Boolean,
    val castleSide: CastleSide?,
    val promotedTo: PieceType?,
    val notation: String,
)

data class MoveHistoryEntry(
    val notation: String,
    val mover: Side,
    val piece: PieceType,
    val promotedTo: PieceType?,
    val accessibility: MoveAccessibilityFacts,
)

data class MoveHistoryRow(
    val moveNumber: Int,
    val white: MoveHistoryEntry?,
    val black: MoveHistoryEntry?,
)

/** Pieces captured by [capturedBy], expressed in conventional chess point values. */
data class CapturedMaterialSideView(
    val capturedBy: Side,
    val pieces: List<PieceType>,
    val totalValue: Int,
) {
    init {
        require(PieceType.KING !in pieces) { "Kings cannot appear in captured material" }
        require(totalValue >= 0) { "Captured material cannot have a negative value" }
    }
}

data class CaptureScoreLead(
    val side: Side?,
    val points: Int,
) {
    init {
        require(points >= 0) { "Capture-score lead cannot be negative" }
        require((side == null) == (points == 0)) { "An even capture score must have no leading side" }
    }
}

data class CapturedMaterialView(
    val white: CapturedMaterialSideView,
    val black: CapturedMaterialSideView,
    val lead: CaptureScoreLead,
)

data class GameTimelineView(
    val history: List<MoveHistoryRow>,
    val capturedMaterial: CapturedMaterialView,
)

/** Replays the persisted move list so history and captures remain correct after resume or undo. */
object GameHistoryPresenter {
    fun present(initialFen: String, moves: List<UciMove>): GameTimelineView {
        var position = ChessPosition.fromFen(initialFen)
        val rows = linkedMapOf<Int, MutableHistoryRow>()
        val captures = mutableMapOf<Side, MutableList<PieceType>>(
            Side.WHITE to mutableListOf<PieceType>(),
            Side.BLACK to mutableListOf(),
        )

        moves.forEach { encodedMove ->
            val move = ChessMove.fromUci(encodedMove)
            val mover = position.sideToMove
            val movingPiece = requireNotNull(position[move.from]) {
                "Move ${encodedMove.value} has no piece on ${move.from.algebraic}"
            }
            val capturedPiece = capturedPiece(position, move, movingPiece)
            val notation = SanNotation.format(position, encodedMove)
            val moveNumber = position.fullmoveNumber
            val entry = MoveHistoryEntry(
                notation = notation,
                mover = mover,
                piece = movingPiece.type,
                promotedTo = move.promotion,
                accessibility = moveAccessibilityFacts(
                    moveNumber = moveNumber,
                    mover = mover,
                    movingPiece = movingPiece,
                    move = move,
                    capturedPiece = capturedPiece,
                    notation = notation,
                ),
            )
            val row = rows.getOrPut(moveNumber) { MutableHistoryRow(moveNumber) }
            if (mover == Side.WHITE) {
                check(row.white == null) { "Move $moveNumber already contains a White move" }
                row.white = entry
            } else {
                check(row.black == null) { "Move $moveNumber already contains a Black move" }
                row.black = entry
            }
            capturedPiece?.let { captures.getValue(mover) += it.piece.type }
            position = ChessRules.apply(position, encodedMove)
        }

        val white = capturedSide(Side.WHITE, captures.getValue(Side.WHITE))
        val black = capturedSide(Side.BLACK, captures.getValue(Side.BLACK))
        val difference = white.totalValue - black.totalValue
        val lead = when {
            difference > 0 -> CaptureScoreLead(Side.WHITE, difference)
            difference < 0 -> CaptureScoreLead(Side.BLACK, -difference)
            else -> CaptureScoreLead(null, 0)
        }
        return GameTimelineView(
            history = rows.values.map { row -> MoveHistoryRow(row.moveNumber, row.white, row.black) },
            capturedMaterial = CapturedMaterialView(white, black, lead),
        )
    }

    private fun capturedPiece(
        position: ChessPosition,
        move: ChessMove,
        moving: Piece,
    ): CapturedPiece? {
        position[move.to]?.let { return CapturedPiece(it, move.to) }
        if (moving.type != PieceType.PAWN || move.from.file == move.to.file) return null
        val capturedSquare = requireNotNull(
            com.drawlesschess.core.chess.Square.at(move.to.file, move.from.rank),
        )
        return position[capturedSquare]?.let { CapturedPiece(it, capturedSquare) }
    }

    private fun capturedSide(side: Side, pieces: List<PieceType>): CapturedMaterialSideView {
        val sorted = pieces.sortedBy(CAPTURE_ORDER::indexOf)
        val total = sorted.sumOf(::pieceValue)
        return CapturedMaterialSideView(
            capturedBy = side,
            pieces = sorted,
            totalValue = total,
        )
    }

    private fun moveAccessibilityFacts(
        moveNumber: Int,
        mover: Side,
        movingPiece: Piece,
        move: ChessMove,
        capturedPiece: CapturedPiece?,
        notation: String,
    ): MoveAccessibilityFacts = MoveAccessibilityFacts(
        moveNumber = moveNumber,
        mover = mover,
        movingPiece = movingPiece.type,
        from = move.from,
        to = move.to,
        capturedSide = capturedPiece?.piece?.side,
        capturedPiece = capturedPiece?.piece?.type,
        capturedSquare = capturedPiece?.square,
        enPassant = capturedPiece != null && capturedPiece.square != move.to,
        castleSide = if (movingPiece.type == PieceType.KING && abs(move.to.file - move.from.file) == 2) {
            if (move.to.file > move.from.file) CastleSide.KING_SIDE else CastleSide.QUEEN_SIDE
        } else {
            null
        },
        promotedTo = move.promotion,
        notation = notation,
    )

    private fun pieceValue(type: PieceType): Int = when (type) {
        PieceType.PAWN -> 1
        PieceType.KNIGHT, PieceType.BISHOP -> 3
        PieceType.ROOK -> 5
        PieceType.QUEEN -> 9
        PieceType.KING -> 0
    }

    private data class MutableHistoryRow(
        val moveNumber: Int,
        var white: MoveHistoryEntry? = null,
        var black: MoveHistoryEntry? = null,
    )

    private data class CapturedPiece(
        val piece: Piece,
        val square: com.drawlesschess.core.chess.Square,
    )

    private val CAPTURE_ORDER = listOf(
        PieceType.QUEEN,
        PieceType.ROOK,
        PieceType.BISHOP,
        PieceType.KNIGHT,
        PieceType.PAWN,
    )
}
