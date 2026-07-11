package com.drawlesschess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.PieceType

/** Original, code-native chess pieces so rendering is consistent and redistributable. */
@Composable
internal fun ChessPiece(
    side: Side,
    type: PieceType,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val fill = if (side == Side.WHITE) Color(0xFFF8F1DD) else Color(0xFF172126)
        val outline = if (side == Side.WHITE) Color(0xFF283238) else Color(0xFFE8E1CF)
        val detail = if (side == Side.WHITE) Color(0xFF665D4E) else Color(0xFFB9B19E)
        val kingAccent = if (side == Side.WHITE) Color(0xFFB3261E) else Color(0xFFFFC857)
        val scaleX = size.width / 100f
        val scaleY = size.height / 100f
        withTransform({ scale(scaleX, scaleY, pivot = Offset.Zero) }) {
            drawPiece(type, fill, outline, detail, kingAccent)
        }
    }
}

private fun DrawScope.drawPiece(
    type: PieceType,
    fill: Color,
    outline: Color,
    detail: Color,
    kingAccent: Color,
) {
    val shape = when (type) {
        PieceType.PAWN -> pawnPath()
        PieceType.KNIGHT -> knightPath()
        PieceType.BISHOP -> bishopPath()
        PieceType.ROOK -> rookPath()
        PieceType.QUEEN -> queenPath()
        PieceType.KING -> kingPath()
    }
    drawPath(shape, outline, style = Stroke(width = 7f))
    drawPath(shape, fill, style = Fill)
    drawPath(shape, outline, style = Stroke(width = 2.4f))

    when (type) {
        PieceType.KING -> {
            // The colored, outlined cross keeps the king distinct from the bishop at a glance.
            drawLine(outline, Offset(50f, 5f), Offset(50f, 29f), strokeWidth = 8f)
            drawLine(outline, Offset(40f, 14f), Offset(60f, 14f), strokeWidth = 8f)
            drawLine(kingAccent, Offset(50f, 5f), Offset(50f, 29f), strokeWidth = 4.5f)
            drawLine(kingAccent, Offset(40f, 14f), Offset(60f, 14f), strokeWidth = 4.5f)
        }
        PieceType.QUEEN -> {
            listOf(27f, 42f, 58f, 73f).forEachIndexed { index, x ->
                drawCircle(
                    color = detail,
                    radius = 3.4f,
                    center = Offset(x, if (index == 1 || index == 2) 13f else 18f),
                )
            }
        }
        PieceType.BISHOP -> drawLine(detail, Offset(57f, 17f), Offset(45f, 42f), strokeWidth = 4f)
        PieceType.KNIGHT -> {
            drawCircle(detail, radius = 2.6f, center = Offset(57f, 31f))
            drawLine(detail, Offset(48f, 48f), Offset(63f, 55f), strokeWidth = 3f)
        }
        PieceType.ROOK -> drawLine(detail, Offset(31f, 42f), Offset(69f, 42f), strokeWidth = 3f)
        PieceType.PAWN -> Unit
    }

    // A shared weighted base gives every piece a coherent, readable silhouette.
    val base = Path().apply {
        moveTo(25f, 70f)
        lineTo(75f, 70f)
        lineTo(82f, 88f)
        quadraticTo(83f, 93f, 77f, 93f)
        lineTo(23f, 93f)
        quadraticTo(17f, 93f, 18f, 88f)
        close()
    }
    drawPath(base, outline, style = Stroke(width = 7f))
    drawPath(base, fill)
    drawPath(base, outline, style = Stroke(width = 2.4f))
    drawLine(detail, Offset(23f, 83f), Offset(77f, 83f), strokeWidth = 2.6f)
}

private fun pawnPath() = Path().apply {
    moveTo(50f, 14f)
    cubicTo(39f, 14f, 33f, 22f, 33f, 32f)
    cubicTo(33f, 41f, 38f, 47f, 44f, 50f)
    cubicTo(37f, 56f, 32f, 64f, 31f, 74f)
    lineTo(69f, 74f)
    cubicTo(68f, 64f, 63f, 56f, 56f, 50f)
    cubicTo(62f, 47f, 67f, 41f, 67f, 32f)
    cubicTo(67f, 22f, 61f, 14f, 50f, 14f)
    close()
}

private fun rookPath() = Path().apply {
    moveTo(26f, 15f)
    lineTo(38f, 15f)
    lineTo(38f, 25f)
    lineTo(46f, 25f)
    lineTo(46f, 15f)
    lineTo(56f, 15f)
    lineTo(56f, 25f)
    lineTo(64f, 25f)
    lineTo(64f, 15f)
    lineTo(76f, 15f)
    lineTo(73f, 39f)
    lineTo(67f, 45f)
    lineTo(70f, 74f)
    lineTo(30f, 74f)
    lineTo(33f, 45f)
    lineTo(29f, 39f)
    close()
}

private fun knightPath() = Path().apply {
    moveTo(29f, 74f)
    cubicTo(30f, 60f, 35f, 49f, 43f, 42f)
    lineTo(36f, 29f)
    lineTo(52f, 34f)
    lineTo(47f, 18f)
    cubicTo(67f, 22f, 76f, 35f, 73f, 50f)
    cubicTo(71f, 62f, 62f, 65f, 64f, 74f)
    close()
}

private fun bishopPath() = Path().apply {
    moveTo(50f, 11f)
    cubicTo(38f, 20f, 33f, 30f, 38f, 41f)
    cubicTo(40f, 46f, 45f, 49f, 42f, 54f)
    cubicTo(36f, 61f, 33f, 67f, 32f, 74f)
    lineTo(68f, 74f)
    cubicTo(67f, 67f, 64f, 61f, 58f, 54f)
    cubicTo(55f, 49f, 60f, 46f, 62f, 41f)
    cubicTo(67f, 30f, 62f, 20f, 50f, 11f)
    close()
}

private fun queenPath() = Path().apply {
    moveTo(24f, 23f)
    lineTo(35f, 38f)
    lineTo(42f, 18f)
    lineTo(50f, 38f)
    lineTo(58f, 18f)
    lineTo(65f, 38f)
    lineTo(76f, 23f)
    lineTo(68f, 54f)
    cubicTo(66f, 62f, 68f, 67f, 70f, 74f)
    lineTo(30f, 74f)
    cubicTo(32f, 67f, 34f, 62f, 32f, 54f)
    close()
}

private fun kingPath() = Path().apply {
    moveTo(50f, 25f)
    cubicTo(36f, 25f, 29f, 35f, 34f, 47f)
    cubicTo(37f, 54f, 40f, 58f, 36f, 64f)
    lineTo(31f, 74f)
    lineTo(69f, 74f)
    lineTo(64f, 64f)
    cubicTo(60f, 58f, 63f, 54f, 66f, 47f)
    cubicTo(71f, 35f, 64f, 25f, 50f, 25f)
    close()
}
