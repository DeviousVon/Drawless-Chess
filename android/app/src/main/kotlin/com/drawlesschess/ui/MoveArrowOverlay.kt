package com.drawlesschess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.drawlesschess.core.presentation.BoardMoveArrow
import com.drawlesschess.core.presentation.BoardScreenState
import kotlin.math.sqrt

@Composable
internal fun MoveArrowOverlay(
    board: BoardScreenState,
    arrow: BoardMoveArrow,
    testTag: String,
    description: String,
) {
    val (fromCell, toCell) = moveArrowDisplayCells(board, arrow)
    val arrowColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
    Canvas(
        Modifier
            .fillMaxSize()
            .testTag(testTag)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val squarePixels = size.width / 8f
        fun center(row: Int, column: Int): Offset = Offset(
            (column + 0.5f) * squarePixels,
            (row + 0.5f) * squarePixels,
        )

        val from = center(fromCell.displayRow, fromCell.displayColumn)
        val destination = center(toCell.displayRow, toCell.displayColumn)
        val dx = destination.x - from.x
        val dy = destination.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0f) return@Canvas

        val unitX = dx / length
        val unitY = dy / length
        val start = Offset(
            from.x + unitX * squarePixels * 0.13f,
            from.y + unitY * squarePixels * 0.13f,
        )
        val end = Offset(
            destination.x - unitX * squarePixels * 0.24f,
            destination.y - unitY * squarePixels * 0.24f,
        )
        val headLength = squarePixels * 0.24f
        val headHalfWidth = squarePixels * 0.16f
        val headBase = Offset(
            end.x - unitX * headLength,
            end.y - unitY * headLength,
        )
        val headOne = Offset(
            headBase.x - unitY * headHalfWidth,
            headBase.y + unitX * headHalfWidth,
        )
        val headTwo = Offset(
            headBase.x + unitY * headHalfWidth,
            headBase.y - unitX * headHalfWidth,
        )
        val outlineWidth = squarePixels * 0.12f
        val arrowWidth = squarePixels * 0.075f

        drawLine(outlineColor, start, end, outlineWidth, cap = StrokeCap.Round)
        drawLine(outlineColor, end, headOne, outlineWidth, cap = StrokeCap.Round)
        drawLine(outlineColor, end, headTwo, outlineWidth, cap = StrokeCap.Round)
        drawLine(arrowColor.copy(alpha = 0.72f), start, end, arrowWidth, cap = StrokeCap.Round)
        drawLine(arrowColor.copy(alpha = 0.72f), end, headOne, arrowWidth, cap = StrokeCap.Round)
        drawLine(arrowColor.copy(alpha = 0.72f), end, headTwo, arrowWidth, cap = StrokeCap.Round)
        drawCircle(
            color = arrowColor.copy(alpha = 0.72f),
            radius = squarePixels * 0.10f,
            center = from,
            style = Stroke(width = arrowWidth * 0.65f),
        )
    }
}

internal fun moveArrowDisplayCells(
    board: BoardScreenState,
    arrow: BoardMoveArrow,
) = board.cells.single { it.square == arrow.from } to
    board.cells.single { it.square == arrow.to }
