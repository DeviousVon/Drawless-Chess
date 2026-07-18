package com.drawlesschess.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.drawlesschess.MainActivity
import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.presentation.BoardInteractionState
import com.drawlesschess.core.presentation.BoardScreenState
import com.drawlesschess.core.presentation.BoardStatus
import com.drawlesschess.core.presentation.BoardThemes
import com.drawlesschess.core.presentation.PieceSets
import com.drawlesschess.core.presentation.PieceView
import com.drawlesschess.core.presentation.SelfCheckDiagnostics
import com.drawlesschess.core.presentation.SquareAccessibilityFacts
import com.drawlesschess.core.presentation.SquareView
import com.drawlesschess.core.coordinator.CoordinatorPhase
import com.drawlesschess.core.chess.Square
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SelfCheckWarningInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun unsafeCastleConnectsKingTransitSquareAndAttackerWithAnExplanation() {
        val position = ChessPosition.fromFen("k4r2/8/8/8/8/8/8/4K2R w K - 0 1")
        val warning = requireNotNull(
            SelfCheckDiagnostics.forAttempt(position, Square.parse("e1"), Square.parse("g1")),
        )
        val board = warningBoard(position, warning)

        compose.activity.setContent {
            MaterialTheme {
                Column {
                    ChessBoard(
                        model = board,
                        boardSizeDp = 320,
                        onEvent = {},
                        showCoordinates = true,
                        onMoveAnimationFinished = {},
                    )
                    SelfCheckWarningBanner(warning.reason)
                }
            }
        }

        compose.onNodeWithTag("self_check_king_e1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("self_check_unsafe_f1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("self_check_attacker_f8", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("self_check_attack_line", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("self_check_warning").assertExists()
        assertEquals("f1", warning.unsafeKingSquare.algebraic)

        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(compose.activity.cacheDir, "self-check-warning.png")).use { output ->
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun warningBoard(
        position: ChessPosition,
        warning: com.drawlesschess.core.presentation.SelfCheckWarning,
    ): BoardScreenState {
        val interaction = BoardInteractionState.initial(position, Side.WHITE).copy(
            selected = Square.parse("e1"),
            selfCheckWarning = warning,
        )
        val pieceSet = PieceSets.MODERN_FLAT
        val cells = buildList {
            for (row in 0..7) for (column in 0..7) {
                val square = interaction.orientation.squareAt(row, column)
                val piece = position[square]
                val king = square == warning.kingSquare
                val attacker = square in warning.attackerSquares
                val unsafe = square == warning.unsafeKingSquare
                add(
                    SquareView(
                        square = square,
                        displayRow = row,
                        displayColumn = column,
                        piece = piece?.let { PieceView(it.side, it.type, pieceSet.assetKey(it)) },
                        selected = square == interaction.selected,
                        target = null,
                        lastMove = false,
                        inCheck = false,
                        threatened = false,
                        accessibility = SquareAccessibilityFacts(
                            square = square,
                            piece = piece,
                            target = null,
                            inCheck = false,
                            threatened = false,
                            selfCheckKing = king,
                            selfCheckAttacker = attacker,
                            selfCheckUnsafe = unsafe,
                        ),
                        selfCheckKing = king,
                        selfCheckAttacker = attacker,
                        selfCheckUnsafe = unsafe,
                    ),
                )
            }
        }
        return BoardScreenState(
            positionMarker = position.fen(),
            plyCount = 0,
            humanSide = Side.WHITE,
            sideToMove = Side.WHITE,
            cells = cells,
            interaction = interaction,
            interactive = true,
            phase = CoordinatorPhase.HUMAN_TURN,
            status = BoardStatus.HUMAN_TURN,
            theme = BoardThemes.DEFAULT,
            pieceSet = pieceSet,
            promotionPrompt = null,
            moveMotion = null,
        )
    }
}
