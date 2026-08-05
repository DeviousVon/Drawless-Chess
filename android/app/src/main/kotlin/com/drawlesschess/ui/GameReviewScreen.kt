@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.drawlesschess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drawlesschess.R
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.SanNotation
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.engine.GameReviewProgress
import com.drawlesschess.core.engine.GameReviewResult
import com.drawlesschess.core.engine.ReviewEvaluation
import com.drawlesschess.core.engine.ReviewMoveQuality
import com.drawlesschess.core.engine.ReviewSideSummary
import com.drawlesschess.core.engine.ReviewedMove
import com.drawlesschess.core.presentation.BoardOrientation
import com.drawlesschess.core.presentation.BoardMoveArrow
import com.drawlesschess.core.presentation.BoardPresenter
import com.drawlesschess.core.presentation.BoardScreenState
import com.drawlesschess.core.presentation.BoardTheme
import com.drawlesschess.core.presentation.ControlPlacement
import com.drawlesschess.core.presentation.ResponsiveBoardLayout
import java.text.NumberFormat
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class ReviewAnalysisUiStatus {
    ANALYZING,
    COMPLETE,
    CANCELLED,
    FAILED,
}

internal enum class ReviewGradeUi {
    BEST,
    GOOD,
    INACCURACY,
    MISTAKE,
    BLUNDER,
}

internal enum class ReviewMoveRole {
    PLAYER_DECISION,
    OPPONENT_CONTEXT,
}

internal typealias ReviewBetterMoveArrow = BoardMoveArrow

internal data class ReviewMoveUi(
    /** One-based half-move index. This remains unambiguous for nonstandard starting FENs. */
    val ply: Int,
    val moveNumber: Int,
    val mover: Side,
    val role: ReviewMoveRole,
    val san: String,
    val grade: ReviewGradeUi? = null,
    val bestMoveSan: String? = null,
    val suggestedLine: List<String> = emptyList(),
    val evaluation: ReviewEvaluation? = null,
    val evaluationSide: Side = Side.WHITE,
    val betterMoveArrow: ReviewBetterMoveArrow? = null,
)

internal data class GameReviewUiModel(
    val board: BoardScreenState,
    val moves: List<ReviewMoveUi>,
    val selectedPly: Int,
    val completedPlayerMoves: Int,
    val status: ReviewAnalysisUiStatus,
    val playerSide: Side,
    val playerSummary: ReviewSideSummary? = null,
    val errorMessage: String? = null,
) {
    init {
        require(moves.all { move ->
            (move.role == ReviewMoveRole.PLAYER_DECISION) == (move.mover == playerSide)
        }) { "Review move roles must match the player side" }
        require(completedPlayerMoves in 0..moves.count {
            it.role == ReviewMoveRole.PLAYER_DECISION
        }) { "Completed player moves must fit the player timeline" }
        require(playerSummary == null || playerSummary.side == playerSide) {
            "The review summary must describe the player side"
        }
    }

    val selectedMove: ReviewMoveUi?
        get() = moves.firstOrNull { it.ply == selectedPly }

    val totalPlayerMoves: Int
        get() = moves.count { it.role == ReviewMoveRole.PLAYER_DECISION }
}

internal fun initialPlayerReviewPly(moves: List<ReviewMoveUi>): Int =
    moves.firstOrNull { move -> move.role == ReviewMoveRole.PLAYER_DECISION }?.ply ?: 0

internal fun visibleReviewMoves(
    moves: List<ReviewMoveUi>,
    showOpponentMoves: Boolean,
): List<ReviewMoveUi> = if (showOpponentMoves) {
    moves
} else {
    moves.filter { move -> move.role == ReviewMoveRole.PLAYER_DECISION }
}

internal fun reviewSelectionAfterHidingOpponentMoves(
    moves: List<ReviewMoveUi>,
    selectedPly: Int,
): Int {
    if (selectedPly == 0) return 0
    val selectedMove = moves.firstOrNull { move -> move.ply == selectedPly }
        ?: return selectedPly
    if (selectedMove.role == ReviewMoveRole.PLAYER_DECISION) return selectedPly
    return moves.firstOrNull { move ->
        move.ply > selectedPly && move.role == ReviewMoveRole.PLAYER_DECISION
    }?.ply ?: moves.lastOrNull { move ->
        move.ply < selectedPly && move.role == ReviewMoveRole.PLAYER_DECISION
    }?.ply ?: 0
}

internal data class ReviewContentLayout(
    val controlPlacement: ControlPlacement,
    val boardSizeDp: Int,
    val outerPaddingDp: Int,
    val panelWidthDp: Int,
    val panelGapDp: Int,
    val panelMoveHistoryHeightDp: Int,
)

internal fun calculateReviewContentLayout(widthDp: Int, heightDp: Int): ReviewContentLayout {
    val base = ResponsiveBoardLayout.calculate(widthDp, heightDp)
    val shortPhoneLandscape =
        base.controlPlacement == ControlPlacement.BESIDE_BOARD &&
            widthDp > heightDp &&
            heightDp < 480
    if (!shortPhoneLandscape) {
        return ReviewContentLayout(
            controlPlacement = base.controlPlacement,
            boardSizeDp = base.boardSizeDp,
            outerPaddingDp = base.outerPaddingDp,
            panelWidthDp = base.panelWidthDp,
            panelGapDp = base.outerPaddingDp,
            panelMoveHistoryHeightDp = base.panelMoveHistoryHeightDp,
        )
    }

    // Review moves its app-bar actions into the side panel, so the board can use the
    // full short edge without changing the shared in-game responsive layout.
    val panelGapDp = 8
    return ReviewContentLayout(
        controlPlacement = base.controlPlacement,
        boardSizeDp = minOf(
            heightDp,
            (widthDp - base.panelWidthDp - panelGapDp).coerceAtLeast(1),
        ),
        outerPaddingDp = 0,
        panelWidthDp = base.panelWidthDp,
        panelGapDp = panelGapDp,
        panelMoveHistoryHeightDp = base.panelMoveHistoryHeightDp,
    )
}

/**
 * Route adapter. The runtime-facing implementation is completed alongside the review runner;
 * keeping the presentation below parameter-only makes progress, failure, and large-font states
 * deterministic in instrumentation tests.
 */
@Composable
internal fun GameReviewRoute(
    runtime: GameRuntime,
    preferences: GamePreferences,
    selectedTheme: BoardTheme,
    onSaveAndExit: () -> Unit,
    onRematch: () -> Unit,
) {
    RuntimeGameReviewRoute(
        runtime = runtime,
        preferences = preferences,
        selectedTheme = selectedTheme,
        onSaveAndExit = onSaveAndExit,
        onRematch = onRematch,
    )
}

@Composable
private fun RuntimeGameReviewRoute(
    runtime: GameRuntime,
    preferences: GamePreferences,
    selectedTheme: BoardTheme,
    onSaveAndExit: () -> Unit,
    onRematch: () -> Unit,
) {
    val checkpoint = remember(runtime) { runtime.reviewCheckpoint() }
    val finalGameModel = remember(runtime) { runtime.controller.model() }
    val placeholderMoves = remember(checkpoint) { reviewMovePlaceholders(checkpoint) }
    val defaultSelectedPly = remember(placeholderMoves) {
        initialPlayerReviewPly(placeholderMoves)
    }
    var orientationOrdinal by rememberSaveable(runtime.gameId) {
        mutableIntStateOf(BoardOrientation.forSide(checkpoint.config.humanSide).ordinal)
    }
    val orientation = BoardOrientation.entries[orientationOrdinal]
    var selectedPly by rememberSaveable(runtime.gameId) { mutableIntStateOf(defaultSelectedPly) }
    var showOpponentMoves by rememberSaveable(runtime.gameId) { mutableStateOf(false) }
    var completedPlayerMoves by remember(runtime) { mutableIntStateOf(0) }
    var status by remember(runtime) { mutableStateOf(ReviewAnalysisUiStatus.ANALYZING) }
    var reviewResult by remember(runtime) { mutableStateOf<GameReviewResult?>(null) }
    var partialReviewedMoves by remember(runtime) {
        mutableStateOf<Map<Int, ReviewedMove>>(emptyMap())
    }
    var errorMessage by remember(runtime) { mutableStateOf<String?>(null) }

    LaunchedEffect(runtime) {
        runtime.gameReviewState().collect { state ->
            when (state) {
                null -> Unit
                is RuntimeGameReviewState.Analyzing -> {
                    reviewResult = null
                    partialReviewedMoves = state.partialMoves
                    val completedByProgress = state.progress?.let { progress ->
                        reviewCompletedPlayerMoveCount(progress, placeholderMoves)
                    } ?: 0
                    completedPlayerMoves = maxOf(
                        completedByProgress,
                        state.partialMoves.values.count { it.mover == checkpoint.config.humanSide },
                    )
                    errorMessage = null
                    status = ReviewAnalysisUiStatus.ANALYZING
                }
                is RuntimeGameReviewState.Complete -> {
                    reviewResult = state.result
                    partialReviewedMoves = state.result.moves.associateBy { it.ply }
                    completedPlayerMoves = placeholderMoves.count {
                        it.role == ReviewMoveRole.PLAYER_DECISION
                    }
                    errorMessage = null
                    status = ReviewAnalysisUiStatus.COMPLETE
                }
                is RuntimeGameReviewState.Cancelled -> {
                    reviewResult = null
                    partialReviewedMoves = state.partialMoves
                    val completedByProgress = state.progress?.let { progress ->
                        reviewCompletedPlayerMoveCount(progress, placeholderMoves)
                    } ?: 0
                    completedPlayerMoves = maxOf(
                        completedByProgress,
                        state.partialMoves.values.count { it.mover == checkpoint.config.humanSide },
                    )
                    errorMessage = null
                    status = ReviewAnalysisUiStatus.CANCELLED
                }
                is RuntimeGameReviewState.Failed -> {
                    reviewResult = null
                    partialReviewedMoves = state.partialMoves
                    completedPlayerMoves = state.partialMoves.values.count {
                        it.mover == checkpoint.config.humanSide
                    }
                    errorMessage = null
                    status = ReviewAnalysisUiStatus.FAILED
                }
            }
        }
    }

    val reviewedMoves = remember(
        reviewResult,
        partialReviewedMoves,
        placeholderMoves,
        checkpoint.config.humanSide,
    ) {
        val reviewedByPly = reviewResult?.moves?.associateBy { move -> move.ply }
            ?: partialReviewedMoves
        reviewMovesWithPartials(
            placeholders = placeholderMoves,
            partialMoves = reviewedByPly,
            playerSide = checkpoint.config.humanSide,
        )
    }
    val board = remember(
        checkpoint,
        selectedPly,
        orientation,
        selectedTheme.id,
        finalGameModel.board.pieceSet.id,
    ) {
        BoardPresenter.presentReview(
            initialFen = checkpoint.config.initialFen,
            moves = checkpoint.moves.take(selectedPly),
            humanSide = checkpoint.config.humanSide,
            orientation = orientation,
            theme = selectedTheme,
            pieceSet = finalGameModel.board.pieceSet,
        )
    }

    GameReviewScreen(
        model = GameReviewUiModel(
            board = board,
            moves = reviewedMoves,
            selectedPly = selectedPly,
            completedPlayerMoves = completedPlayerMoves,
            status = status,
            playerSide = checkpoint.config.humanSide,
            playerSummary = reviewResult?.summary?.let { summary ->
                if (checkpoint.config.humanSide == Side.WHITE) summary.white else summary.black
            },
            errorMessage = errorMessage,
        ),
        showBoardCoordinates = preferences.boardCoordinatesEnabled,
        onSaveAndExit = onSaveAndExit,
        onRematch = onRematch,
        onFlip = { orientationOrdinal = orientation.flipped().ordinal },
        onCancel = runtime::cancelGameReview,
        onRetry = runtime::restartGameReview,
        onSelectPly = { ply -> selectedPly = ply.coerceIn(0, reviewedMoves.size) },
        showOpponentMoves = showOpponentMoves,
        onShowOpponentMovesChange = { show ->
            if (!show) {
                selectedPly = reviewSelectionAfterHidingOpponentMoves(reviewedMoves, selectedPly)
            }
            showOpponentMoves = show
        },
    )
}

internal fun reviewCompletedPlayerMoveCount(
    progress: GameReviewProgress,
    moves: List<ReviewMoveUi>,
): Int {
    val totalPlayerMoves = moves.count { move -> move.role == ReviewMoveRole.PLAYER_DECISION }
    return progress.completedMoves.coerceIn(0, totalPlayerMoves)
}

@Composable
internal fun GameReviewScreen(
    model: GameReviewUiModel,
    showBoardCoordinates: Boolean,
    onSaveAndExit: () -> Unit,
    onRematch: () -> Unit = {},
    onFlip: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSelectPly: (Int) -> Unit,
    showOpponentMoves: Boolean = false,
    onShowOpponentMovesChange: (Boolean) -> Unit = {},
) {
    val stackedScrollState = rememberScrollState()
    val sideScrollState = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = calculateReviewContentLayout(
            maxWidth.value.roundToInt().coerceAtLeast(1),
            maxHeight.value.roundToInt().coerceAtLeast(1),
        )
        val headerInSidePanel = layout.controlPlacement == ControlPlacement.BESIDE_BOARD
        val largeFont = LocalDensity.current.fontScale >= 1.5f
        val moveListHeight = if (largeFont) 300.dp else {
            layout.panelMoveHistoryHeightDp.coerceAtLeast(200).dp
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (!headerInSidePanel) {
                    ReviewTopBar(onSaveAndExit = onSaveAndExit, onFlip = onFlip)
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("game_review"),
            ) {
                if (layout.controlPlacement == ControlPlacement.BELOW_BOARD) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("game_stacked_content")
                            .verticalScroll(stackedScrollState)
                            .padding(layout.outerPaddingDp.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ReviewChessBoard(
                            model = model.board,
                            boardSizeDp = layout.boardSizeDp,
                            showCoordinates = showBoardCoordinates,
                            arrow = model.selectedMove.reviewArrowOrNull(),
                        )
                        ReviewPanel(
                            model = model,
                            moveListHeight = moveListHeight,
                            onCancel = onCancel,
                            onRetry = onRetry,
                            onSelectPly = onSelectPly,
                            onRematch = onRematch,
                            showOpponentMoves = showOpponentMoves,
                            onShowOpponentMovesChange = onShowOpponentMovesChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(layout.outerPaddingDp.dp),
                        horizontalArrangement = Arrangement.spacedBy(
                            layout.panelGapDp.dp,
                            Alignment.CenterHorizontally,
                        ),
                    ) {
                        ReviewChessBoard(
                            model = model.board,
                            boardSizeDp = layout.boardSizeDp,
                            showCoordinates = showBoardCoordinates,
                            arrow = model.selectedMove.reviewArrowOrNull(),
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(layout.panelWidthDp.dp)
                                .testTag("game_side_panel")
                                .verticalScroll(sideScrollState),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ReviewSideHeader(
                                onSaveAndExit = onSaveAndExit,
                                onFlip = onFlip,
                            )
                            ReviewPanel(
                                model = model,
                                moveListHeight = moveListHeight,
                                onCancel = onCancel,
                                onRetry = onRetry,
                                onSelectPly = onSelectPly,
                                onRematch = onRematch,
                                showOpponentMoves = showOpponentMoves,
                                onShowOpponentMovesChange = onShowOpponentMovesChange,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewTopBar(
    onSaveAndExit: () -> Unit,
    onFlip: () -> Unit,
) {
    TopAppBar(
        title = { ReviewHeaderTitle() },
        navigationIcon = {
            TextButton(
                onClick = onSaveAndExit,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("review_save_exit"),
            ) {
                Text(
                    stringResource(R.string.game_save_exit),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            TextButton(
                onClick = onFlip,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("review_flip"),
            ) { Text(stringResource(R.string.game_flip)) }
        },
        modifier = Modifier.testTag("review_top_bar"),
    )
}

@Composable
private fun ReviewSideHeader(
    onSaveAndExit: () -> Unit,
    onFlip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_side_header"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onSaveAndExit,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("review_save_exit"),
            ) {
                Text(
                    stringResource(R.string.game_save_exit),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onFlip,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("review_flip"),
            ) { Text(stringResource(R.string.game_flip)) }
        }
        ReviewHeaderTitle(Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun ReviewHeaderTitle(modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            stringResource(R.string.review_title),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.review_beta),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ReviewMoveUi?.reviewArrowOrNull(): ReviewBetterMoveArrow? = this
    ?.takeIf { move ->
        move.role == ReviewMoveRole.PLAYER_DECISION &&
            move.grade != null &&
            move.grade != ReviewGradeUi.BEST &&
            move.bestMoveSan != null
    }
    ?.betterMoveArrow

@Composable
private fun ReviewChessBoard(
    model: BoardScreenState,
    boardSizeDp: Int,
    showCoordinates: Boolean,
    arrow: ReviewBetterMoveArrow?,
) {
    Box(Modifier.size(boardSizeDp.dp)) {
        ChessBoard(
            model = model,
            boardSizeDp = boardSizeDp,
            onEvent = {},
            showCoordinates = showCoordinates,
            onMoveAnimationFinished = {},
        )
        arrow?.let {
            MoveArrowOverlay(
                board = model,
                arrow = it,
                testTag = "review_better_move_arrow",
                description = stringResource(
                    R.string.review_better_move_arrow_accessibility,
                    it.from.algebraic,
                    it.to.algebraic,
                ),
            )
        }
    }
}

internal fun reviewArrowDisplayCells(
    board: BoardScreenState,
    arrow: ReviewBetterMoveArrow,
) = moveArrowDisplayCells(board, arrow)

@Composable
private fun ReviewPanel(
    model: GameReviewUiModel,
    moveListHeight: androidx.compose.ui.unit.Dp,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSelectPly: (Int) -> Unit,
    onRematch: () -> Unit,
    showOpponentMoves: Boolean,
    onShowOpponentMovesChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val visibleMoves = remember(model.moves, showOpponentMoves) {
        visibleReviewMoves(model.moves, showOpponentMoves)
    }
    Column(
        modifier = modifier.testTag("review_panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (model.status == ReviewAnalysisUiStatus.COMPLETE) {
            ReviewMoveFeedback(model.selectedMove, model.status)
            ReviewNavigator(model, visibleMoves, showOpponentMoves, onSelectPly)
        }
        ReviewAnalysisCard(model, onCancel, onRetry, onRematch)
        if (model.status == ReviewAnalysisUiStatus.COMPLETE) {
            model.playerSummary?.let { summary ->
                ReviewSummaryCard(
                    summary = summary,
                )
            }
        } else {
            ReviewMoveFeedback(model.selectedMove, model.status)
            ReviewNavigator(model, visibleMoves, showOpponentMoves, onSelectPly)
        }
        ReviewMoveList(
            moves = visibleMoves,
            selectedPly = model.selectedPly,
            analysisStatus = model.status,
            showOpponentMoves = showOpponentMoves,
            onShowOpponentMovesChange = onShowOpponentMovesChange,
            onSelectPly = onSelectPly,
            modifier = Modifier
                .fillMaxWidth()
                .height(moveListHeight),
        )
    }
}

@Composable
private fun ReviewAnalysisCard(
    model: GameReviewUiModel,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRematch: () -> Unit,
) {
    val totalPlayerMoves = model.totalPlayerMoves
    val progressDenominator = totalPlayerMoves.coerceAtLeast(1)
    val completed = model.completedPlayerMoves.coerceIn(0, totalPlayerMoves)
    val milestonePercent = when (model.status) {
        ReviewAnalysisUiStatus.COMPLETE -> 100
        ReviewAnalysisUiStatus.ANALYZING -> if (totalPlayerMoves == 0) {
            100
        } else {
            ((completed * 4 / progressDenominator) * 25).coerceIn(0, 100)
        }
        ReviewAnalysisUiStatus.CANCELLED,
        ReviewAnalysisUiStatus.FAILED -> null
    }
    val accessibilityState = milestonePercent?.let { percent ->
        stringResource(R.string.review_progress_accessibility, percent)
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_analysis_status")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                accessibilityState?.let { stateDescription = it }
            },
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (model.status) {
                ReviewAnalysisUiStatus.ANALYZING -> {
                    Text(
                        stringResource(R.string.review_analyzing),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (totalPlayerMoves == 0) 1f
                            else completed.toFloat() / totalPlayerMoves.toFloat()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("review_progress"),
                    )
                    Text(
                        stringResource(R.string.review_progress, completed, totalPlayerMoves),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .align(Alignment.End)
                            .testTag("review_cancel"),
                    ) { Text(stringResource(R.string.review_cancel_analysis)) }
                }

                ReviewAnalysisUiStatus.COMPLETE -> {
                    Text(
                        stringResource(R.string.review_complete),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(
                            R.string.review_complete_body,
                            totalPlayerMoves,
                            model.playerSummary?.gradedMoves ?: 0,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onRematch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("review_rematch"),
                    ) {
                        Text(stringResource(R.string.action_rematch))
                    }
                }

                ReviewAnalysisUiStatus.CANCELLED -> {
                    Text(
                        stringResource(R.string.review_cancelled),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.review_cancelled_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("review_retry"),
                    ) { Text(stringResource(R.string.review_retry)) }
                }

                ReviewAnalysisUiStatus.FAILED -> {
                    Text(
                        stringResource(R.string.review_failed),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        model.errorMessage ?: stringResource(R.string.review_failed_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewSummaryCard(
    summary: ReviewSideSummary,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_summary"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.review_summary_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ReviewSummarySide(
                summary = summary,
                roleTag = "player",
            )
        }
    }
}

@Composable
private fun ReviewSummarySide(
    summary: ReviewSideSummary,
    roleTag: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_summary_$roleTag"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    R.string.review_summary_player_side,
                    sideNameForReview(summary.side),
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.review_summary_graded, summary.gradedMoves),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ReviewMoveQuality.entries.chunked(2).forEach { rowQualities ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowQualities.forEach { quality ->
                        ReviewSummaryGradeCount(
                            quality = quality,
                            count = summary.qualityCounts[quality] ?: 0,
                            roleTag = roleTag,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowQualities.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ReviewSummaryGradeCount(
    quality: ReviewMoveQuality,
    count: Int,
    roleTag: String,
    modifier: Modifier,
) {
    val grade = quality.toUiGrade()
    val palette = reviewGradePalette(grade)
    val description = stringResource(
        R.string.review_summary_grade_count,
        reviewGradeName(grade),
        count,
    )
    Surface(
        modifier = modifier
            .testTag("review_summary_${roleTag}_${quality.name.lowercase()}")
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = palette.container,
        contentColor = palette.content,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    R.string.review_summary_grade_badge,
                    reviewGradeSymbol(grade),
                    count,
                ),
                modifier = Modifier.testTag(
                    "review_summary_${roleTag}_${quality.name.lowercase()}_label",
                ),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun ReviewGradeUi.isReviewIssue(): Boolean = when (this) {
    ReviewGradeUi.INACCURACY,
    ReviewGradeUi.MISTAKE,
    ReviewGradeUi.BLUNDER -> true
    ReviewGradeUi.BEST,
    ReviewGradeUi.GOOD -> false
}

@Composable
private fun ReviewMoveFeedback(
    move: ReviewMoveUi?,
    analysisStatus: ReviewAnalysisUiStatus,
) {
    val feedbackStateDescription = if (move == null) {
        stringResource(R.string.review_starting_position)
    } else if (move.role == ReviewMoveRole.OPPONENT_CONTEXT) {
        stringResource(
            R.string.review_move_accessibility,
            reviewMoveTitle(move),
            stringResource(R.string.review_opponent_context_label),
            stringResource(R.string.label_selected),
        )
    } else {
        val gradeDescription = move.grade?.let { reviewGradeName(it) }
            ?: stringResource(
                if (analysisStatus != ReviewAnalysisUiStatus.ANALYZING) {
                    R.string.review_grade_unreviewed
                } else {
                    R.string.review_waiting
                },
            )
        stringResource(
            R.string.review_move_accessibility,
            reviewMoveTitle(move),
            gradeDescription,
            stringResource(R.string.label_selected),
        )
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_move_feedback")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = feedbackStateDescription
            },
        shape = RoundedCornerShape(16.dp),
    ) {
        if (move == null) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.review_starting_position),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.review_choose_move),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@ElevatedCard
        }

        if (move.role == ReviewMoveRole.OPPONENT_CONTEXT) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("review_opponent_context"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    reviewMoveTitle(move),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.review_opponent_context_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.review_opponent_context_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@ElevatedCard
        }

        val grade = move.grade
        val palette = grade?.let { reviewGradePalette(it) }
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                reviewMoveTitle(move),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (grade == null || palette == null) {
                Text(
                    stringResource(
                        if (analysisStatus != ReviewAnalysisUiStatus.ANALYZING) {
                            R.string.review_grade_unreviewed
                        } else {
                            R.string.review_waiting
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Surface(
                    color = palette.container,
                    contentColor = palette.content,
                    shape = RoundedCornerShape(100.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(reviewGradeSymbol(grade), fontWeight = FontWeight.Black)
                        Text(reviewGradeName(grade), fontWeight = FontWeight.Bold)
                    }
                }
                Text(reviewGradeExplanation(grade))
                move.bestMoveSan
                    ?.takeIf { grade != ReviewGradeUi.BEST && it != move.san }
                    ?.let { best ->
                        Text(
                            stringResource(R.string.review_better_move, best),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                if (move.suggestedLine.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.review_suggested_line,
                            move.suggestedLine.joinToString(" "),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                move.evaluation?.let { evaluation ->
                    Text(
                        stringResource(
                            R.string.review_evaluation,
                            sideNameForReview(move.evaluationSide),
                            reviewEvaluationText(evaluation, move.evaluationSide),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewNavigator(
    model: GameReviewUiModel,
    visibleMoves: List<ReviewMoveUi>,
    showOpponentMoves: Boolean,
    onSelectPly: (Int) -> Unit,
) {
    val selectedIndex = visibleMoves.indexOfFirst { it.ply == model.selectedPly }
    val previousPly = when {
        model.selectedPly == 0 -> null
        selectedIndex <= 0 -> 0
        else -> visibleMoves[selectedIndex - 1].ply
    }
    val next = when {
        model.selectedPly == 0 || selectedIndex < 0 -> visibleMoves.firstOrNull()
        else -> visibleMoves.getOrNull(selectedIndex + 1)
    }
    val playerIssues = visibleMoves.filter { move ->
        move.role == ReviewMoveRole.PLAYER_DECISION && move.grade?.isReviewIssue() == true
    }
    val previousIssue = playerIssues
        .filter { move -> move.ply < model.selectedPly }
        .maxByOrNull { move -> move.ply }
    val nextIssue = playerIssues
        .filter { move -> move.ply > model.selectedPly }
        .minByOrNull { move -> move.ply }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_navigator"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (selectedIndex < 0) {
                stringResource(R.string.review_starting_position)
            } else {
                stringResource(
                    if (showOpponentMoves) R.string.review_move_position
                    else R.string.review_your_move_position,
                    selectedIndex + 1,
                    visibleMoves.size,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review_move_position"),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReviewNavigationButton(
                symbol = "|<<",
                contentDescription = stringResource(R.string.review_previous_issue),
                enabled = previousIssue != null,
                testTag = "review_previous_issue",
                onClick = { previousIssue?.let { onSelectPly(it.ply) } },
            )
            ReviewNavigationButton(
                symbol = "<<",
                contentDescription = stringResource(R.string.review_previous),
                enabled = previousPly != null,
                testTag = "review_previous",
                onClick = { previousPly?.let(onSelectPly) },
            )
            ReviewNavigationButton(
                symbol = ">>",
                contentDescription = stringResource(R.string.review_next),
                enabled = next != null,
                testTag = "review_next",
                onClick = { next?.let { onSelectPly(it.ply) } },
            )
            ReviewNavigationButton(
                symbol = ">>|",
                contentDescription = stringResource(R.string.review_next_issue),
                enabled = nextIssue != null,
                testTag = "review_next_issue",
                onClick = { nextIssue?.let { onSelectPly(it.ply) } },
            )
        }
    }
}

@Composable
private fun ReviewNavigationButton(
    symbol: String,
    contentDescription: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .testTag(testTag)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = symbol,
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
    }
}

private data class ReviewMoveRow(
    val moveNumber: Int,
    val white: ReviewMoveUi?,
    val black: ReviewMoveUi?,
)

@Composable
private fun ReviewMoveList(
    moves: List<ReviewMoveUi>,
    selectedPly: Int,
    analysisStatus: ReviewAnalysisUiStatus,
    showOpponentMoves: Boolean,
    onShowOpponentMovesChange: (Boolean) -> Unit,
    onSelectPly: (Int) -> Unit,
    modifier: Modifier,
) {
    val rows = if (showOpponentMoves) {
        moves
            .groupBy { it.moveNumber }
            .map { (moveNumber, entries) ->
                ReviewMoveRow(
                    moveNumber = moveNumber,
                    white = entries.firstOrNull { it.mover == Side.WHITE },
                    black = entries.firstOrNull { it.mover == Side.BLACK },
                )
            }
    } else {
        emptyList()
    }
    val listState = rememberLazyListState()
    val selectedRow = if (showOpponentMoves) {
        rows.indexOfFirst { row ->
            row.white?.ply == selectedPly || row.black?.ply == selectedPly
        }
    } else {
        moves.indexOfFirst { move -> move.ply == selectedPly }
    }
    LaunchedEffect(selectedRow, showOpponentMoves) {
        if (selectedRow >= 0) listState.animateScrollToItem(selectedRow)
    }

    ElevatedCard(
        modifier = modifier.testTag("review_move_list"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                stringResource(
                    if (showOpponentMoves) R.string.review_moves else R.string.review_your_moves,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("review_show_opponent_moves")
                    .toggleable(
                        value = showOpponentMoves,
                        role = Role.Switch,
                        onValueChange = onShowOpponentMovesChange,
                    )
                    .semantics(mergeDescendants = true) {}
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.review_show_opponent_moves),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = showOpponentMoves,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (moves.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("review_no_player_moves"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.review_no_player_moves),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (showOpponentMoves) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(34.dp))
                    Text(
                        stringResource(R.string.label_white),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.label_black),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("review_move_list_scroll"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(rows, key = { it.moveNumber }) { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.move_number_format, row.moveNumber),
                                modifier = Modifier.width(34.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ReviewMoveCell(
                                move = row.white,
                                selected = row.white?.ply == selectedPly,
                                analysisStatus = analysisStatus,
                                onSelectPly = onSelectPly,
                                modifier = Modifier.weight(1f),
                            )
                            ReviewMoveCell(
                                move = row.black,
                                selected = row.black?.ply == selectedPly,
                                analysisStatus = analysisStatus,
                                onSelectPly = onSelectPly,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("review_move_list_scroll"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(moves, key = { it.ply }) { move ->
                        ReviewMoveCell(
                            move = move,
                            selected = move.ply == selectedPly,
                            analysisStatus = analysisStatus,
                            onSelectPly = onSelectPly,
                            modifier = Modifier.fillMaxWidth(),
                            includeMoveNumber = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewMoveCell(
    move: ReviewMoveUi?,
    selected: Boolean,
    analysisStatus: ReviewAnalysisUiStatus,
    onSelectPly: (Int) -> Unit,
    modifier: Modifier,
    includeMoveNumber: Boolean = false,
) {
    if (move == null) {
        Spacer(modifier)
        return
    }
    val gradeName = if (move.role == ReviewMoveRole.OPPONENT_CONTEXT) {
        stringResource(R.string.review_opponent_context_label)
    } else {
        move.grade?.let { reviewGradeName(it) }
            ?: stringResource(
                if (analysisStatus == ReviewAnalysisUiStatus.ANALYZING) {
                    R.string.review_waiting
                } else {
                    R.string.review_grade_unreviewed
                },
            )
    }
    val description = stringResource(
        R.string.review_move_accessibility,
        reviewMoveTitle(move),
        gradeName,
        if (selected) stringResource(R.string.label_selected)
        else stringResource(R.string.label_not_selected),
    )
    val palette = move.grade
        ?.takeIf { move.role == ReviewMoveRole.PLAYER_DECISION }
        ?.let { reviewGradePalette(it) }

    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(end = 4.dp)
            .testTag("review_move_${move.ply}")
            .selectable(
                selected = selected,
                onClick = { onSelectPly(move.ply) },
                role = Role.Button,
            )
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (move.role == ReviewMoveRole.PLAYER_DECISION && move.grade != null && palette != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(palette.container, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        reviewGradeSymbol(move.grade),
                        color = palette.content,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Text(
                if (includeMoveNumber) reviewMoveTitle(move) else move.san,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

private data class ReviewGradePalette(val container: Color, val content: Color)

private fun reviewMovePlaceholders(checkpoint: CoordinatorCheckpoint): List<ReviewMoveUi> {
    var position = ChessPosition.fromFen(checkpoint.config.initialFen)
    return checkpoint.moves.mapIndexed { index, move ->
        val placeholder = ReviewMoveUi(
            ply = index + 1,
            moveNumber = position.fullmoveNumber,
            mover = position.sideToMove,
            role = if (position.sideToMove == checkpoint.config.humanSide) {
                ReviewMoveRole.PLAYER_DECISION
            } else {
                ReviewMoveRole.OPPONENT_CONTEXT
            },
            san = SanNotation.format(position, move),
        )
        position = ChessRules.apply(position, move)
        placeholder
    }
}

internal fun reviewMovesWithPartials(
    placeholders: List<ReviewMoveUi>,
    partialMoves: Map<Int, ReviewedMove>,
    playerSide: Side,
): List<ReviewMoveUi> {
    if (partialMoves.isEmpty()) return placeholders
    val playerPartials = partialMoves.values
        .asSequence()
        .filter { move -> move.mover == playerSide }
        .associate { move -> move.ply to reviewMoveUi(move, playerSide) }
    return placeholders.map { placeholder -> playerPartials[placeholder.ply] ?: placeholder }
}

private fun reviewMoveUi(move: ReviewedMove, playerSide: Side): ReviewMoveUi {
    val position = ChessPosition.fromFen(move.fenBefore)
    val role = if (move.mover == playerSide) {
        ReviewMoveRole.PLAYER_DECISION
    } else {
        ReviewMoveRole.OPPONENT_CONTEXT
    }
    val best = ChessMove.fromUci(move.bestMove)
    val bestMoveSan = runCatching { SanNotation.format(position, move.bestMove) }.getOrNull()
    val playerGrade = move.quality?.toUiGrade().takeIf {
        role == ReviewMoveRole.PLAYER_DECISION
    }
    return ReviewMoveUi(
        ply = move.ply,
        moveNumber = position.fullmoveNumber,
        mover = move.mover,
        role = role,
        san = SanNotation.format(position, move.playedMove),
        grade = playerGrade,
        bestMoveSan = bestMoveSan.takeIf { role == ReviewMoveRole.PLAYER_DECISION },
        suggestedLine = if (role == ReviewMoveRole.PLAYER_DECISION) {
            suggestedLineSan(move.fenBefore, move.suggestedLine)
        } else {
            emptyList()
        },
        evaluation = move.playedEvaluation.takeIf { role == ReviewMoveRole.PLAYER_DECISION },
        evaluationSide = playerSide,
        betterMoveArrow = if (
            role == ReviewMoveRole.PLAYER_DECISION &&
            playerGrade != null &&
            playerGrade != ReviewGradeUi.BEST &&
            move.bestMove != move.playedMove &&
            bestMoveSan != null
        ) {
            ReviewBetterMoveArrow(best.from, best.to)
        } else {
            null
        },
    )
}

private fun suggestedLineSan(initialFen: String, moves: List<UciMove>): List<String> {
    var position = ChessPosition.fromFen(initialFen)
    val notation = mutableListOf<String>()
    for (move in moves.take(4)) {
        if (move !in ChessRules.legalUciMoves(position)) break
        notation += SanNotation.format(position, move)
        position = ChessRules.apply(position, move)
    }
    return notation
}

private fun ReviewMoveQuality.toUiGrade(): ReviewGradeUi = when (this) {
    ReviewMoveQuality.BEST -> ReviewGradeUi.BEST
    ReviewMoveQuality.GOOD -> ReviewGradeUi.GOOD
    ReviewMoveQuality.INACCURACY -> ReviewGradeUi.INACCURACY
    ReviewMoveQuality.MISTAKE -> ReviewGradeUi.MISTAKE
    ReviewMoveQuality.BLUNDER -> ReviewGradeUi.BLUNDER
}

@Composable
private fun reviewEvaluationText(evaluation: ReviewEvaluation, perspective: Side): String = when (evaluation) {
    is ReviewEvaluation.Centipawns -> {
        val locale = LocalConfiguration.current.locales[0]
        val formatter = remember(locale) {
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
        }
        val score = formatter.format(evaluation.value / 100.0)
        if (evaluation.value > 0) "+$score" else score
    }
    is ReviewEvaluation.Mate -> if (evaluation.mateIn > 0) {
        "M${evaluation.mateIn}"
    } else {
        "−M${abs(evaluation.mateIn)}"
    }
    is ReviewEvaluation.Terminal -> if (evaluation.winner == perspective) "+∞" else "−∞"
}

@Composable
private fun reviewGradePalette(grade: ReviewGradeUi): ReviewGradePalette = when (grade) {
    ReviewGradeUi.BEST -> ReviewGradePalette(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer,
    )
    ReviewGradeUi.GOOD -> ReviewGradePalette(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
    )
    ReviewGradeUi.INACCURACY -> ReviewGradePalette(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
    ReviewGradeUi.MISTAKE -> ReviewGradePalette(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
    ReviewGradeUi.BLUNDER -> ReviewGradePalette(
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.onError,
    )
}

private fun reviewGradeSymbol(grade: ReviewGradeUi): String = when (grade) {
    ReviewGradeUi.BEST -> "★"
    ReviewGradeUi.GOOD -> "✓"
    ReviewGradeUi.INACCURACY -> "?!"
    ReviewGradeUi.MISTAKE -> "?"
    ReviewGradeUi.BLUNDER -> "??"
}

@Composable
private fun reviewGradeName(grade: ReviewGradeUi): String = stringResource(
    when (grade) {
        ReviewGradeUi.BEST -> R.string.review_grade_best
        ReviewGradeUi.GOOD -> R.string.review_grade_good
        ReviewGradeUi.INACCURACY -> R.string.review_grade_inaccuracy
        ReviewGradeUi.MISTAKE -> R.string.review_grade_mistake
        ReviewGradeUi.BLUNDER -> R.string.review_grade_blunder
    },
)

@Composable
private fun reviewGradeExplanation(grade: ReviewGradeUi): String = stringResource(
    when (grade) {
        ReviewGradeUi.BEST -> R.string.review_grade_best_explanation
        ReviewGradeUi.GOOD -> R.string.review_grade_good_explanation
        ReviewGradeUi.INACCURACY -> R.string.review_grade_inaccuracy_explanation
        ReviewGradeUi.MISTAKE -> R.string.review_grade_mistake_explanation
        ReviewGradeUi.BLUNDER -> R.string.review_grade_blunder_explanation
    },
)

@Composable
private fun reviewMoveTitle(move: ReviewMoveUi): String = stringResource(
    if (move.mover == Side.WHITE) R.string.review_move_title_white
    else R.string.review_move_title_black,
    move.moveNumber,
    move.san,
)

@Composable
private fun sideNameForReview(side: Side): String = stringResource(
    if (side == Side.WHITE) R.string.label_white else R.string.label_black,
)
