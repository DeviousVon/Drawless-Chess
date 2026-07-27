@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.drawlesschess.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drawlesschess.R
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.SanNotation
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.engine.GameReviewProgress
import com.drawlesschess.core.engine.GameReviewResult
import com.drawlesschess.core.engine.GameReviewSummary
import com.drawlesschess.core.engine.ReviewEvaluation
import com.drawlesschess.core.engine.ReviewMoveQuality
import com.drawlesschess.core.engine.ReviewSideSummary
import com.drawlesschess.core.engine.ReviewedMove
import com.drawlesschess.core.presentation.BoardOrientation
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

internal data class ReviewMoveUi(
    /** One-based half-move index. This remains unambiguous for nonstandard starting FENs. */
    val ply: Int,
    val moveNumber: Int,
    val mover: Side,
    val san: String,
    val grade: ReviewGradeUi? = null,
    val bestMoveSan: String? = null,
    val suggestedLine: List<String> = emptyList(),
    val evaluation: ReviewEvaluation? = null,
    val evaluationSide: Side = Side.WHITE,
)

internal data class GameReviewUiModel(
    val board: BoardScreenState,
    val moves: List<ReviewMoveUi>,
    val selectedPly: Int,
    val completedMoves: Int,
    val status: ReviewAnalysisUiStatus,
    val playerSide: Side,
    val summary: GameReviewSummary? = null,
    val errorMessage: String? = null,
) {
    val selectedMove: ReviewMoveUi?
        get() = moves.firstOrNull { it.ply == selectedPly }
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
    onBack: () -> Unit,
) {
    RuntimeGameReviewRoute(runtime, preferences, selectedTheme, onBack)
}

@Composable
private fun RuntimeGameReviewRoute(
    runtime: GameRuntime,
    preferences: GamePreferences,
    selectedTheme: BoardTheme,
    onBack: () -> Unit,
) {
    val checkpoint = remember(runtime) { runtime.reviewCheckpoint() }
    val finalGameModel = remember(runtime) { runtime.controller.model() }
    val placeholderMoves = remember(checkpoint) { reviewMovePlaceholders(checkpoint) }
    var orientationOrdinal by rememberSaveable(runtime.gameId) {
        mutableIntStateOf(BoardOrientation.forSide(checkpoint.config.humanSide).ordinal)
    }
    val orientation = BoardOrientation.entries[orientationOrdinal]
    var selectedPly by rememberSaveable(runtime.gameId) { mutableIntStateOf(checkpoint.moves.size) }
    var completedMoves by remember(runtime) { mutableIntStateOf(0) }
    var status by remember(runtime) { mutableStateOf(ReviewAnalysisUiStatus.ANALYZING) }
    var reviewResult by remember(runtime) { mutableStateOf<GameReviewResult?>(null) }
    var errorMessage by remember(runtime) { mutableStateOf<String?>(null) }

    LaunchedEffect(runtime) {
        runtime.gameReviewState().collect { state ->
            when (state) {
                null -> Unit
                is RuntimeGameReviewState.Analyzing -> {
                    reviewResult = null
                    completedMoves = state.progress?.let { progress ->
                        reviewCompletedMoveCount(progress, placeholderMoves.size)
                    } ?: 0
                    errorMessage = null
                    status = ReviewAnalysisUiStatus.ANALYZING
                }
                is RuntimeGameReviewState.Complete -> {
                    reviewResult = state.result
                    completedMoves = state.result.moves.size
                    errorMessage = null
                    status = ReviewAnalysisUiStatus.COMPLETE
                }
                is RuntimeGameReviewState.Cancelled -> {
                    completedMoves = state.progress?.let { progress ->
                        reviewCompletedMoveCount(progress, placeholderMoves.size)
                    } ?: 0
                    errorMessage = null
                    status = ReviewAnalysisUiStatus.CANCELLED
                }
                is RuntimeGameReviewState.Failed -> {
                    errorMessage = null
                    status = ReviewAnalysisUiStatus.FAILED
                }
            }
        }
    }

    val reviewedMoves = remember(reviewResult, placeholderMoves) {
        reviewResult?.moves?.map(::reviewMoveUi) ?: placeholderMoves
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
            completedMoves = completedMoves,
            status = status,
            playerSide = checkpoint.config.humanSide,
            summary = reviewResult?.summary,
            errorMessage = errorMessage,
        ),
        showBoardCoordinates = preferences.boardCoordinatesEnabled,
        onBack = onBack,
        onFlip = { orientationOrdinal = orientation.flipped().ordinal },
        onCancel = runtime::cancelGameReview,
        onRetry = runtime::restartGameReview,
        onSelectPly = { ply -> selectedPly = ply.coerceIn(0, reviewedMoves.size) },
    )
}

internal fun reviewCompletedMoveCount(progress: GameReviewProgress, totalMoves: Int): Int {
    require(totalMoves >= 0)
    // A played move generally needs the following position's evaluation before it can be
    // graded. The final onResult callback promotes the count to all moves at completion.
    return (progress.completedPositions - 1).coerceIn(0, totalMoves)
}

@Composable
internal fun GameReviewScreen(
    model: GameReviewUiModel,
    showBoardCoordinates: Boolean,
    onBack: () -> Unit,
    onFlip: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSelectPly: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
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
                },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("review_back"),
                    ) { Text(stringResource(R.string.action_back)) }
                },
                actions = {
                    TextButton(
                        onClick = onFlip,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("review_flip"),
                    ) { Text(stringResource(R.string.game_flip)) }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("game_review"),
        ) {
            val layout = ResponsiveBoardLayout.calculate(
                maxWidth.value.roundToInt().coerceAtLeast(1),
                maxHeight.value.roundToInt().coerceAtLeast(1),
            )
            val largeFont = LocalDensity.current.fontScale >= 1.5f
            val moveListHeight = if (largeFont) 300.dp else {
                layout.panelMoveHistoryHeightDp.coerceAtLeast(200).dp
            }

            if (layout.controlPlacement == ControlPlacement.BELOW_BOARD) {
                GameStackedContentContainer(
                    outerPadding = layout.outerPaddingDp.dp,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ChessBoard(
                        model = model.board,
                        boardSizeDp = layout.boardSizeDp,
                        onEvent = {},
                        showCoordinates = showBoardCoordinates,
                        onMoveAnimationFinished = {},
                    )
                    ReviewPanel(
                        model = model,
                        moveListHeight = moveListHeight,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onSelectPly = onSelectPly,
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
                        layout.outerPaddingDp.dp,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    ChessBoard(
                        model = model.board,
                        boardSizeDp = layout.boardSizeDp,
                        onEvent = {},
                        showCoordinates = showBoardCoordinates,
                        onMoveAnimationFinished = {},
                    )
                    GameSidePanelContainer(
                        panelWidthDp = layout.panelWidthDp,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        ReviewPanel(
                            model = model,
                            moveListHeight = moveListHeight,
                            onCancel = onCancel,
                            onRetry = onRetry,
                            onSelectPly = onSelectPly,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewPanel(
    model: GameReviewUiModel,
    moveListHeight: androidx.compose.ui.unit.Dp,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSelectPly: (Int) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.testTag("review_panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReviewAnalysisCard(model, onCancel, onRetry)
        if (model.status == ReviewAnalysisUiStatus.COMPLETE) {
            model.summary?.let { summary ->
                ReviewSummaryCard(
                    summary = summary,
                    playerSide = model.playerSide,
                    moves = model.moves,
                    onSelectPly = onSelectPly,
                )
            }
        }
        ReviewMoveFeedback(model.selectedMove, model.status)
        ReviewNavigator(model, onSelectPly)
        ReviewMoveList(
            moves = model.moves,
            selectedPly = model.selectedPly,
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
) {
    val total = model.moves.size.coerceAtLeast(1)
    val completed = model.completedMoves.coerceIn(0, total)
    val milestonePercent = when (model.status) {
        ReviewAnalysisUiStatus.COMPLETE -> 100
        ReviewAnalysisUiStatus.ANALYZING -> ((completed * 4 / total) * 25).coerceIn(0, 100)
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
                        progress = { completed.toFloat() / total.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("review_progress"),
                    )
                    Text(
                        stringResource(R.string.review_progress, completed, model.moves.size),
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
                            model.completedMoves,
                            model.summary?.let { it.white.gradedMoves + it.black.gradedMoves } ?: 0,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    summary: GameReviewSummary,
    playerSide: Side,
    moves: List<ReviewMoveUi>,
    onSelectPly: (Int) -> Unit,
) {
    val playerSummary = if (playerSide == Side.WHITE) summary.white else summary.black
    val opponentSummary = if (playerSide == Side.WHITE) summary.black else summary.white
    val firstPlayerIssue = moves.firstOrNull { move ->
        move.mover == playerSide && move.grade?.isReviewIssue() == true
    }

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
                summary = playerSummary,
                player = true,
                roleTag = "player",
            )
            HorizontalDivider()
            ReviewSummarySide(
                summary = opponentSummary,
                player = false,
                roleTag = "opponent",
            )
            firstPlayerIssue?.let { issue ->
                FilledTonalButton(
                    onClick = { onSelectPly(issue.ply) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("review_my_mistakes"),
                ) {
                    Text(stringResource(R.string.review_my_mistakes))
                }
            }
        }
    }
}

@Composable
private fun ReviewSummarySide(
    summary: ReviewSideSummary,
    player: Boolean,
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
                    if (player) R.string.review_summary_player_side
                    else R.string.review_summary_opponent_side,
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
    onSelectPly: (Int) -> Unit,
) {
    val selectedIndex = model.moves.indexOfFirst { it.ply == model.selectedPly }
    val previousPly = when {
        model.selectedPly == 0 -> null
        selectedIndex <= 0 -> 0
        else -> model.moves[selectedIndex - 1].ply
    }
    val next = when {
        model.selectedPly == 0 || selectedIndex < 0 -> model.moves.firstOrNull()
        else -> model.moves.getOrNull(selectedIndex + 1)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_navigator"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { previousPly?.let(onSelectPly) },
            enabled = previousPly != null,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("review_previous"),
        ) { Text(stringResource(R.string.review_previous)) }
        Text(
            text = if (selectedIndex < 0) {
                stringResource(R.string.review_starting_position)
            } else {
                stringResource(R.string.review_move_position, selectedIndex + 1, model.moves.size)
            },
            modifier = Modifier
                .weight(1f)
                .testTag("review_move_position"),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
        )
        OutlinedButton(
            onClick = { next?.let { onSelectPly(it.ply) } },
            enabled = next != null,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("review_next"),
        ) { Text(stringResource(R.string.review_next)) }
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
    onSelectPly: (Int) -> Unit,
    modifier: Modifier,
) {
    val rows = moves
        .groupBy { it.moveNumber }
        .map { (moveNumber, entries) ->
            ReviewMoveRow(
                moveNumber = moveNumber,
                white = entries.firstOrNull { it.mover == Side.WHITE },
                black = entries.firstOrNull { it.mover == Side.BLACK },
            )
        }
    val listState = rememberLazyListState()
    val selectedRow = rows.indexOfFirst { row ->
        row.white?.ply == selectedPly || row.black?.ply == selectedPly
    }
    LaunchedEffect(selectedRow) {
        if (selectedRow >= 0) listState.animateScrollToItem(selectedRow)
    }

    ElevatedCard(
        modifier = modifier.testTag("review_move_list"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                stringResource(R.string.review_moves),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.move_number_format, row.moveNumber),
                            modifier = Modifier.width(34.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ReviewMoveCell(
                            move = row.white,
                            selected = row.white?.ply == selectedPly,
                            onSelectPly = onSelectPly,
                            modifier = Modifier.weight(1f),
                        )
                        ReviewMoveCell(
                            move = row.black,
                            selected = row.black?.ply == selectedPly,
                            onSelectPly = onSelectPly,
                            modifier = Modifier.weight(1f),
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
    onSelectPly: (Int) -> Unit,
    modifier: Modifier,
) {
    if (move == null) {
        Spacer(modifier)
        return
    }
    val gradeName = move.grade?.let { reviewGradeName(it) }
        ?: stringResource(R.string.review_grade_unreviewed)
    val description = stringResource(
        R.string.review_move_accessibility,
        reviewMoveTitle(move),
        gradeName,
        if (selected) stringResource(R.string.label_selected)
        else stringResource(R.string.label_not_selected),
    )
    val palette = move.grade?.let { reviewGradePalette(it) }

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
            if (move.grade != null && palette != null) {
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
                move.san,
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
            san = SanNotation.format(position, move),
        )
        position = ChessRules.apply(position, move)
        placeholder
    }
}

private fun reviewMoveUi(move: ReviewedMove): ReviewMoveUi {
    val position = ChessPosition.fromFen(move.fenBefore)
    return ReviewMoveUi(
        ply = move.ply,
        moveNumber = position.fullmoveNumber,
        mover = move.mover,
        san = SanNotation.format(position, move.playedMove),
        grade = move.quality?.toUiGrade(),
        bestMoveSan = runCatching { SanNotation.format(position, move.bestMove) }.getOrNull(),
        suggestedLine = suggestedLineSan(move.fenBefore, move.suggestedLine),
        evaluation = move.playedEvaluation,
        evaluationSide = move.mover,
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
