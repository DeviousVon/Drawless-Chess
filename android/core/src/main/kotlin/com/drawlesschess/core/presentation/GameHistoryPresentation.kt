package com.drawlesschess.core.presentation

import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.Piece
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.chess.SanNotation
import kotlin.math.abs

data class MoveHistoryEntry(
    val notation: String,
    val mover: Side,
    val piece: PieceType,
    val promotedTo: PieceType?,
    val accessibilityLabel: String,
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
    val accessibilityLabel: String,
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
    val leadAccessibilityLabel: String,
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
                accessibilityLabel = moveAccessibilityLabel(
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
        val leadLabel = lead.side?.let { side ->
            "${side.displayName()} leads the captured piece score by ${lead.points}."
        } ?: "The captured piece score is even."

        return GameTimelineView(
            history = rows.values.map { row -> MoveHistoryRow(row.moveNumber, row.white, row.black) },
            capturedMaterial = CapturedMaterialView(white, black, lead, leadLabel),
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
        val inventory = if (sorted.isEmpty()) {
            "none"
        } else {
            CAPTURE_ORDER.mapNotNull { type ->
                val count = sorted.count { it == type }
                if (count == 0) null else "$count ${type.displayName()}${if (count == 1) "" else "s"}"
            }.joinToString(", ")
        }
        return CapturedMaterialSideView(
            capturedBy = side,
            pieces = sorted,
            totalValue = total,
            accessibilityLabel = "${side.displayName()} captured: $inventory. Captured piece score $total.",
        )
    }

    private fun moveAccessibilityLabel(
        moveNumber: Int,
        mover: Side,
        movingPiece: Piece,
        move: ChessMove,
        capturedPiece: CapturedPiece?,
        notation: String,
    ): String {
        val action = if (movingPiece.type == PieceType.KING && abs(move.to.file - move.from.file) == 2) {
            "king castles ${if (move.to.file > move.from.file) "kingside" else "queenside"}"
        } else {
            buildString {
                append(movingPiece.type.displayName())
                append(" from ${move.from.algebraic}")
                if (capturedPiece == null) {
                    append(" to ${move.to.algebraic}")
                } else {
                    append(" captures ${capturedPiece.piece.side.displayName().lowercase()} ")
                    append(capturedPiece.piece.type.displayName())
                    append(" on ${capturedPiece.square.algebraic}")
                    if (capturedPiece.square != move.to) {
                        append(" en passant and moves to ${move.to.algebraic}")
                    }
                }
                move.promotion?.let { append(" and promotes to ${it.displayName()}") }
            }
        }
        return "${mover.displayName()} move $moveNumber: $action, $notation."
    }

    private fun pieceValue(type: PieceType): Int = when (type) {
        PieceType.PAWN -> 1
        PieceType.KNIGHT, PieceType.BISHOP -> 3
        PieceType.ROOK -> 5
        PieceType.QUEEN -> 9
        PieceType.KING -> 0
    }

    private fun Side.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)
    private fun PieceType.displayName(): String = name.lowercase()

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
