package com.drawlesschess.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.coordinator.CoordinatorPhase
import com.drawlesschess.core.presentation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

@Suppress("DEPRECATION")
class CaptureHistoryUiInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun maximumCaptureInventoryFitsCompactClockAtDoubleFontScale() {
        if (StoreScreenshotHarness.runIfRequested(compose)) return

        val material = maximumCapturedMaterial(Side.WHITE)

        assertEquals(21, captureGroupSlotSizeDp(COMPACT_CLOCK_CONTENT_WIDTH_DP, 5))
        assertEquals(113, captureInventoryRequiredWidthDp(COMPACT_CLOCK_CONTENT_WIDTH_DP, 5))
        assertTrue(
            captureInventoryRequiredWidthDp(COMPACT_CLOCK_CONTENT_WIDTH_DP, 5) <=
                COMPACT_CLOCK_CONTENT_WIDTH_DP,
        )

        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 2f),
            ) {
                DrawlessTheme {
                    CapturedMaterialSide(
                        material,
                        Modifier.width(COMPACT_CLOCK_CONTENT_WIDTH_DP.dp),
                    )
                }
            }
        }

        val capturedNode = compose.onNodeWithTag("captured_by_white")
            .assertWidthIsEqualTo(COMPACT_CLOCK_CONTENT_WIDTH_DP.dp)
            .assertContentDescriptionEquals(
                "White captured: 1 queen, 2 rooks, 2 bishops, 2 knights, 8 pawns. " +
                    "Captured piece score 39.",
            )
            .fetchSemanticsNode()
        assertTrue(capturedNode.boundsInRoot.height > 0f)
    }

    @Test
    fun moveHistoryIconCellPublishesTheCompleteMoveDescription() {
        val description = "White move 1: queen from d1 to b7, Qb7."
        val history = listOf(
            MoveHistoryRow(
                moveNumber = 1,
                white = MoveHistoryEntry(
                    notation = "Qb7",
                    mover = Side.WHITE,
                    piece = PieceType.QUEEN,
                    promotedTo = null,
                    accessibility = moveAccessibility(
                        moveNumber = 1,
                        mover = Side.WHITE,
                        piece = PieceType.QUEEN,
                        from = "d1",
                        to = "b7",
                        notation = "Qb7",
                    ),
                ),
                black = null,
            ),
        )

        compose.setContent {
            DrawlessTheme {
                Column(Modifier.width(320.dp).height(180.dp)) {
                    MoveHistory(history, Modifier.fillMaxSize())
                }
            }
        }

        val moveNode = compose.onNodeWithTag("move_1_white")
            .assertContentDescriptionEquals(description)
            .fetchSemanticsNode()
        assertTrue(moveNode.boundsInRoot.width > 0f && moveNode.boundsInRoot.height > 0f)
    }

    @Test
    fun moveHistoryAutomaticallyFollowsNewestRowInCompactPane() {
        val history = completeHistory(12)
        compose.setContent {
            DrawlessTheme {
                MoveHistory(history, Modifier.width(320.dp).height(160.dp))
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("move_12_black").fetchSemanticsNodes().isNotEmpty()
        }
        assertVerticallyWithin("move_12_black", "move_history")
    }

    @Test
    fun shortLandscapeSidePanelScrollsToFiniteMoveHistory() {
        compose.setContent {
            DrawlessTheme {
                Box(Modifier.width(240.dp).height(272.dp)) {
                    GameSidePanelContainer(
                        panelWidthDp = 240,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        Spacer(Modifier.fillMaxWidth().height(300.dp))
                        MoveHistory(emptyList(), Modifier.fillMaxWidth().height(132.dp))
                    }
                }
            }
        }

        compose.onNodeWithTag("game_side_panel").assert(hasScrollAction())
        compose.onNodeWithTag("move_history").assertHeightIsEqualTo(132.dp)
        compose.onNodeWithTag("move_history").performScrollTo()
        compose.waitForIdle()
        assertVerticallyWithin("move_history", "game_side_panel")
    }

    @Test
    fun compactDoubleFontStackReachesCapturesResignAndNewestMove() {
        val history = completeHistory(12)
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 2f),
            ) {
                DrawlessTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        GameBody(
                            model = testGameModel(history),
                            opponent = OpponentProfiles.quickPlay,
                            modifier = Modifier.fillMaxSize(),
                            onBoardEvent = {},
                            onPause = {},
                            onUndo = {},
                            onHint = {},
                            onFlip = {},
                            onRetryBot = {},
                            onResign = {},
                            onDismissMessage = {},
                            showBoardCoordinates = true,
                            onMoveAnimationFinished = {},
                        )
                    }
                }
            }
        }

        saveCompactCaptureEvidenceIfRequested()
        compose.onNodeWithTag("game_stacked_content").assert(hasScrollAction())
        compose.onNodeWithTag("captured_by_white").performScrollTo()
        assertVerticallyWithin("captured_by_white", "game_stacked_content")
        compose.onNodeWithTag("captured_by_black").performScrollTo()
        assertVerticallyWithin("captured_by_black", "game_stacked_content")
        compose.onNodeWithTag("resign_button").performScrollTo()
        assertVerticallyWithin("resign_button", "game_stacked_content")
        compose.onNodeWithTag("move_history").performScrollTo()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("move_12_black").fetchSemanticsNodes().isNotEmpty()
        }
        assertVerticallyWithin("move_12_black", "move_history")
    }

    @Test
    fun chessBoardTapStillWorksInsideStackedScrollContainer() {
        val e2 = Square.parse("e2")
        var tapped: Square? = null
        compose.setContent {
            DrawlessTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    GameStackedContentContainer(
                        outerPadding = 16.dp,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        ChessBoard(
                            model = startingBoard(),
                            boardSizeDp = 288,
                            onEvent = { event ->
                                if (event is BoardEvent.TapSquare) tapped = event.square
                            },
                            showCoordinates = true,
                            onMoveAnimationFinished = {},
                        )
                        Spacer(Modifier.height(500.dp))
                    }
                }
            }
        }

        compose.onNodeWithTag("board_square_e2").performClick()
        compose.runOnIdle { assertEquals(e2, tapped) }
    }

    private fun assertVerticallyWithin(childTag: String, parentTag: String) {
        compose.waitForIdle()
        val child = compose.onNodeWithTag(childTag).fetchSemanticsNode().boundsInRoot
        val parent = compose.onNodeWithTag(parentTag).fetchSemanticsNode().boundsInRoot
        assertTrue("$childTag starts above $parentTag", child.top >= parent.top - 1f)
        assertTrue("$childTag ends below $parentTag", child.bottom <= parent.bottom + 1f)
        assertTrue("$childTag has no visible height", child.height > 0f)
    }

    private fun saveCompactCaptureEvidenceIfRequested() {
        val fileName = InstrumentationRegistry.getArguments()
            .getString("captureEvidenceFile")
            ?: return
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = requireNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir,
        )
        val output = File(directory, fileName)
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun maximumCapturedMaterial(side: Side): CapturedMaterialSideView {
        val pieces = listOf(
            PieceType.QUEEN,
            PieceType.ROOK,
            PieceType.ROOK,
            PieceType.BISHOP,
            PieceType.BISHOP,
            PieceType.KNIGHT,
            PieceType.KNIGHT,
        ) + List(8) { PieceType.PAWN }
        return CapturedMaterialSideView(
            capturedBy = side,
            pieces = pieces,
            totalValue = 39,
        )
    }

    private fun completeHistory(moveCount: Int): List<MoveHistoryRow> = (1..moveCount).map { moveNumber ->
        MoveHistoryRow(
            moveNumber = moveNumber,
            white = MoveHistoryEntry(
                notation = "Qb7",
                mover = Side.WHITE,
                piece = PieceType.QUEEN,
                promotedTo = null,
                accessibility = moveAccessibility(
                    moveNumber = moveNumber,
                    mover = Side.WHITE,
                    piece = PieceType.QUEEN,
                    from = "d1",
                    to = "b7",
                    notation = "Qb7",
                ),
            ),
            black = MoveHistoryEntry(
                notation = "Nf6",
                mover = Side.BLACK,
                piece = PieceType.KNIGHT,
                promotedTo = null,
                accessibility = moveAccessibility(
                    moveNumber = moveNumber,
                    mover = Side.BLACK,
                    piece = PieceType.KNIGHT,
                    from = "g8",
                    to = "f6",
                    notation = "Nf6",
                ),
            ),
        )
    }

    private fun startingBoard(): BoardScreenState {
        val position = ChessPosition.starting()
        val interaction = BoardInteractionState.initial(position, Side.WHITE)
        val pieceSet = PieceSets.MODERN_FLAT
        val cells = buildList {
            for (row in 0..7) for (column in 0..7) {
                val square = interaction.orientation.squareAt(row, column)
                val piece = position[square]
                add(
                    SquareView(
                        square = square,
                        displayRow = row,
                        displayColumn = column,
                        piece = piece?.let {
                            PieceView(it.side, it.type, pieceSet.assetKey(it))
                        },
                        selected = false,
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
                        ),
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

    private fun testGameModel(history: List<MoveHistoryRow>): GameScreenModel {
        val whiteCaptured = maximumCapturedMaterial(Side.WHITE)
        val blackCaptured = maximumCapturedMaterial(Side.BLACK)
        return GameScreenModel(
            board = startingBoard(),
            whiteClock = ClockView("10:00", active = true, lowTime = false),
            blackClock = ClockView("10:00", active = false, lowTime = false),
            history = history,
            capturedMaterial = CapturedMaterialView(
                white = whiteCaptured,
                black = blackCaptured,
                lead = CaptureScoreLead(side = null, points = 0),
            ),
            controls = GameControlsView(
                canPause = true,
                paused = false,
                canUndo = true,
                canHint = true,
                canResign = true,
            ),
            rulesPreset = RulesContractV1.Preset.DRAWLESS,
            mode = GameMode.CASUAL,
            result = null,
            transientNotice = null,
        )
    }

    private fun moveAccessibility(
        moveNumber: Int,
        mover: Side,
        piece: PieceType,
        from: String,
        to: String,
        notation: String,
    ) = MoveAccessibilityFacts(
        moveNumber = moveNumber,
        mover = mover,
        movingPiece = piece,
        from = Square.parse(from),
        to = Square.parse(to),
        capturedSide = null,
        capturedPiece = null,
        capturedSquare = null,
        enPassant = false,
        castleSide = null,
        promotedTo = null,
        notation = notation,
    )

    private companion object {
        const val COMPACT_CLOCK_CONTENT_WIDTH_DP = 115
    }
}
