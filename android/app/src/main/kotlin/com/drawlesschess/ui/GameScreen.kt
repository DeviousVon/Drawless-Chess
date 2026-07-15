@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.drawlesschess.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.drawlesschess.core.Side
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.coordinator.CoordinatorPhase
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.presentation.*
import com.drawlesschess.persistence.PlayerStatistics
import com.drawlesschess.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class PendingCompletion(
    val result: GameResultView,
    val waitForAnimationPly: Int?,
)

@Composable
internal fun GameRoute(
    runtime: GameRuntime,
    soundPlayer: GameSoundPlayer,
    preferences: GamePreferences,
    playerStatistics: PlayerStatistics?,
    selectedTheme: BoardTheme,
    onShowThemes: () -> Unit,
    onExit: () -> Unit,
    onRematch: () -> Unit,
    onGameCompleted: () -> Unit,
) {
    val controller = runtime.controller
    val opponentProfile = remember(runtime, runtime.opponentLevel.id) {
        OpponentProfiles.forLevel(runtime.opponentLevel)
    }
    val opponentDisplayName = opponentName(opponentProfile)
    var model by remember(controller) { mutableStateOf(controller.model()) }
    val uiScope = rememberCoroutineScope()
    var soundedPlyCount by remember(controller) { mutableIntStateOf(model.history.plyCount()) }
    var confirmResign by remember { mutableStateOf(false) }
    var previousPhase by remember(controller) { mutableStateOf(model.board.phase) }
    var completedMoveAnimationPly by remember(controller) { mutableIntStateOf(model.history.plyCount()) }
    var pendingCompletion by remember(controller) { mutableStateOf<PendingCompletion?>(null) }
    // A completed runtime survives Activity recreation in the ViewModel. Restore only the
    // persistent result bar here; pendingCompletion/completionEffect deliberately remain empty
    // so configuration changes never replay the one-shot animation or its matching audio cue.
    var postGameResult by remember(controller) {
        mutableStateOf(model.result.takeIf { model.board.phase == CoordinatorPhase.COMPLETED })
    }
    var completionEffect by remember(controller) { mutableStateOf<GameResultView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(soundPlayer) {
        onDispose(soundPlayer::stopAll)
    }
    DisposableEffect(runtime, controller, uiScope) {
        val registration = runtime.addModelInvalidationListener {
            uiScope.launch { model = controller.model() }
        }
        onDispose(registration::close)
    }

    LaunchedEffect(controller, selectedTheme.id) {
        if (model.board.theme.id != selectedTheme.id) {
            model = controller.selectTheme(selectedTheme)
        }
    }

    val visiblePlyCount = model.history.plyCount()
    LaunchedEffect(soundPlayer, preferences.soundEnabled, visiblePlyCount, lifecycleStarted) {
        if (preferences.soundEnabled && lifecycleStarted && visiblePlyCount > soundedPlyCount) {
            val latestSan = model.history.lastPlayedSan().orEmpty()
            soundPlayer.playMove(latestSan)
        }
        soundedPlyCount = visiblePlyCount
    }

    LaunchedEffect(soundPlayer, preferences.soundEnabled, lifecycleStarted) {
        if (!preferences.soundEnabled || !lifecycleStarted) soundPlayer.stopAll()
    }

    LaunchedEffect(model.board.phase, model.result, visiblePlyCount, lifecycleStarted) {
        if (!lifecycleStarted) return@LaunchedEffect
        if (previousPhase != CoordinatorPhase.COMPLETED &&
            model.board.phase == CoordinatorPhase.COMPLETED
        ) {
            model.result?.let { result ->
                val finalOpponentMovePly = model.board.moveMotion?.takeIf { motion ->
                    motion.ply == visiblePlyCount && motion.mover != model.board.humanSide
                }?.ply
                pendingCompletion = PendingCompletion(result, finalOpponentMovePly)
            }
        }
        previousPhase = model.board.phase
    }

    LaunchedEffect(controller, model.board.phase, model.result) {
        if (model.board.phase == CoordinatorPhase.COMPLETED && model.result != null) {
            onGameCompleted()
        }
    }

    LaunchedEffect(pendingCompletion, completedMoveAnimationPly, lifecycleStarted) {
        if (!lifecycleStarted) return@LaunchedEffect
        pendingCompletion?.let { pending ->
            if (pending.waitForAnimationPly == null ||
                completedMoveAnimationPly >= pending.waitForAnimationPly
            ) {
                postGameResult = pending.result
                completionEffect = pending.result.takeIf {
                    preferences.celebrationEffectsEnabled
                }
                pendingCompletion = null
            }
        }
    }

    val shouldTick = model.board.phase == CoordinatorPhase.BOT_THINKING ||
        model.board.phase == CoordinatorPhase.HINT_THINKING ||
        model.whiteClock.active || model.blackClock.active
    val tickIntervalMillis = if (
        model.board.phase == CoordinatorPhase.BOT_THINKING ||
        model.board.phase == CoordinatorPhase.HINT_THINKING ||
        (model.whiteClock.active && model.whiteClock.lowTime) ||
        (model.blackClock.active && model.blackClock.lowTime)
    ) 100L else 1_000L
    LaunchedEffect(controller, lifecycleStarted, shouldTick, tickIntervalMillis) {
        while (lifecycleStarted && shouldTick) {
            delay(tickIntervalMillis)
            model = controller.tick()
        }
    }

    val exitGame = {
        pendingCompletion = null
        postGameResult = null
        completionEffect = null
        soundPlayer.stopAll()
        onExit()
    }
    val rematchGame = {
        pendingCompletion = null
        postGameResult = null
        completionEffect = null
        soundPlayer.stopAll()
        onRematch()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text(
                            stringResource(
                                R.string.game_title_summary,
                                stringResource(
                                    if (model.rulesPreset == RulesContractV1.Preset.DRAWLESS) {
                                        R.string.rules_label_drawless
                                    } else {
                                        R.string.rules_label_escape
                                    },
                                ),
                                stringResource(
                                    if (model.mode == GameMode.CASUAL) R.string.mode_casual
                                    else R.string.mode_rated,
                                ),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = exitGame, modifier = Modifier.testTag("game_save_exit")) {
                        Text(stringResource(R.string.game_save_exit))
                    }
                },
                actions = {
                    TextButton(
                        onClick = onShowThemes,
                        modifier = Modifier.testTag("game_theme_selector"),
                    ) {
                        Text(stringResource(R.string.action_theme))
                    }
                },
            )
        },
        bottomBar = {
            postGameResult?.let { result ->
                PostGameBar(
                    result = result,
                    opponentName = opponentDisplayName,
                    careerAverageGameScore = playerStatistics
                        ?.takeIf { it.latestGameId == runtime.gameId }
                        ?.averageScore,
                    onHome = exitGame,
                    onRematch = rematchGame,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            GameBody(
                model = model,
                opponent = opponentProfile,
                modifier = Modifier.fillMaxSize(),
                onBoardEvent = { model = controller.boardEvent(it) },
                onPause = { model = controller.pauseOrResume() },
                onUndo = { model = controller.undo() },
                onHint = { model = controller.hint() },
                onFlip = { model = controller.boardEvent(BoardEvent.FlipBoard) },
                onRetryBot = { model = controller.retryBot() },
                onResign = { confirmResign = true },
                onDismissMessage = { model = controller.dismissMessage() },
                showBoardCoordinates = preferences.boardCoordinatesEnabled,
                onMoveAnimationFinished = { ply ->
                    completedMoveAnimationPly = maxOf(completedMoveAnimationPly, ply)
                },
            )
            completionEffect?.let { result ->
                CompletionEffectOverlay(
                    result = result,
                    opponent = opponentProfile,
                    modifier = Modifier.fillMaxSize(),
                    onCue = { won ->
                        if (preferences.soundEnabled) soundPlayer.playCompletionCue(won)
                    },
                    onFinished = { completionEffect = null },
                )
            }
        }
    }

    model.board.promotionPrompt?.let { prompt ->
        PromotionDialog(
            side = model.board.sideToMove,
            choices = prompt.choices,
            onChoose = { model = controller.boardEvent(BoardEvent.PromotionChosen(it)) },
            onDismiss = { model = controller.boardEvent(BoardEvent.PromotionCancelled) },
        )
    }

    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            title = { Text(stringResource(R.string.game_resign_title)) },
            text = { Text(stringResource(R.string.game_resign_body, opponentDisplayName)) },
            confirmButton = {
                Button(onClick = {
                    confirmResign = false
                    model = controller.resign()
                }, modifier = Modifier.testTag("confirm_resign_game")) {
                    Text(stringResource(R.string.game_resign_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmResign = false },
                    modifier = Modifier.testTag("cancel_resign_game"),
                ) { Text(stringResource(R.string.action_keep_playing)) }
            },
        )
    }
}

@Composable
internal fun PostGameBar(
    result: GameResultView,
    opponentName: String? = null,
    careerAverageGameScore: Double? = null,
    onHome: () -> Unit,
    onRematch: () -> Unit,
) {
    val headline = stringResource(if (result.playerWon) R.string.game_victory else R.string.game_defeat)
    val resolvedOpponentName = opponentName ?: stringResource(R.string.opponent_default)
    val resultStateDescription = stringResource(
        R.string.game_result_accessibility,
        headline,
        result.score.points,
        result.score.maximumPoints,
    )
    val container = if (result.playerWon) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = if (result.playerWon) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availableHeight = if (constraints.hasBoundedHeight) maxHeight else 420.dp
        val maximumBarHeight = minOf(420.dp, availableHeight * 0.62f)
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = maximumBarHeight),
            color = container,
            tonalElevation = 10.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("post_game_feedback")
                    .semantics {
                        stateDescription =
                            resultStateDescription
                        liveRegion = LiveRegionMode.Polite
                    }
                    .verticalScroll(rememberScrollState())
                .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    headline,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onContainer,
                )
                Text(
                    if (result.playerWon) {
                        if (opponentName == null) {
                            stringResource(R.string.game_you_won)
                        } else {
                            stringResource(R.string.game_you_defeated, resolvedOpponentName)
                        }
                    } else {
                        stringResource(R.string.game_opponent_won, resolvedOpponentName)
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
                Text(resultReasonText(result.reason), color = onContainer.copy(alpha = 0.82f))
                Text(
                    text = stringResource(R.string.game_score, result.score.points, result.score.maximumPoints),
                    modifier = Modifier.testTag("post_game_score"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onContainer,
                )
                careerAverageGameScore?.let { average ->
                    Text(
                        stringResource(R.string.game_career_average, oneDecimal(average)),
                        modifier = Modifier.testTag("career_average_score"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.88f),
                    )
                }
                if (result.score.hintPenalty > 0) {
                    ScorePenaltyLine(
                        label = stringResource(R.string.game_hints),
                        points = result.score.hintPenalty,
                        tag = "hint_score_penalty",
                        color = onContainer.copy(alpha = 0.82f),
                    )
                }
                if (result.score.undoPenalty > 0) {
                    ScorePenaltyLine(
                        label = stringResource(R.string.game_undos),
                        points = result.score.undoPenalty,
                        tag = "undo_score_penalty",
                        color = onContainer.copy(alpha = 0.82f),
                    )
                }
                if (result.score.timedPausePenalty > 0) {
                    ScorePenaltyLine(
                        label = stringResource(R.string.game_timed_pauses),
                        points = result.score.timedPausePenalty,
                        tag = "pause_score_penalty",
                        color = onContainer.copy(alpha = 0.82f),
                    )
                }
                if (result.score.threatIndicationPenalty > 0) {
                    ScorePenaltyLine(
                        label = stringResource(R.string.game_threat_indication),
                        points = result.score.threatIndicationPenalty,
                        tag = "threat_score_penalty",
                        color = onContainer.copy(alpha = 0.82f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onHome,
                        modifier = Modifier.weight(1f).testTag("post_game_home"),
                    ) { Text(stringResource(R.string.action_home)) }
                    Button(
                        onClick = onRematch,
                        modifier = Modifier.weight(1f).testTag("post_game_rematch"),
                    ) { Text(stringResource(R.string.action_rematch)) }
                }
            }
        }
    }
}

@Composable
private fun ScorePenaltyLine(
    label: String,
    points: Int,
    tag: String,
    color: Color,
) {
    Text(
        stringResource(R.string.game_penalty, label, points),
        modifier = Modifier.testTag(tag),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun resultReasonText(reason: EndReason): String = stringResource(
    when (reason) {
        EndReason.CHECKMATE -> R.string.result_checkmate
        EndReason.STALEMATE -> R.string.result_stalemate
        EndReason.REPETITION -> R.string.result_threefold_repetition
        EndReason.DEAD_POSITION_MATERIAL -> R.string.result_dead_position_material
        EndReason.DEAD_POSITION_FINAL_CAPTURE -> R.string.result_dead_position_final_capture
        EndReason.FIFTY_MOVE_LIMIT -> R.string.result_fifty_move
        EndReason.RESIGNATION -> R.string.result_resignation
        EndReason.TIMEOUT -> R.string.result_timeout
    },
)

@Composable
internal fun GameBody(
    model: GameScreenModel,
    opponent: OpponentProfile,
    modifier: Modifier,
    onBoardEvent: (BoardEvent) -> Unit,
    onPause: () -> Unit,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onFlip: () -> Unit,
    onRetryBot: () -> Unit,
    onResign: () -> Unit,
    onDismissMessage: () -> Unit,
    showBoardCoordinates: Boolean,
    onMoveAnimationFinished: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = ResponsiveBoardLayout.calculate(maxWidth.value.roundToInt(), maxHeight.value.roundToInt())
        if (layout.controlPlacement == ControlPlacement.BELOW_BOARD) {
            GameStackedContentContainer(
                outerPadding = layout.outerPaddingDp.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                ClockRow(model)
                ChessBoard(
                    model.board,
                    layout.boardSizeDp,
                    onBoardEvent,
                    showBoardCoordinates,
                    onMoveAnimationFinished,
                )
                OpponentStatusCard(
                    model = model,
                    opponent = opponent,
                    portraitSize = 54.dp,
                    onRetryBot = onRetryBot,
                    onDismissMessage = onDismissMessage,
                )
                GameControls(model.controls, onPause, onUndo, onHint, onFlip, onResign)
                MoveHistory(
                    model.history,
                    Modifier.fillMaxWidth().height(layout.panelMoveHistoryHeightDp.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(layout.outerPaddingDp.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    layout.outerPaddingDp.dp,
                    Alignment.CenterHorizontally,
                ),
            ) {
                ChessBoard(
                    model.board,
                    layout.boardSizeDp,
                    onBoardEvent,
                    showBoardCoordinates,
                    onMoveAnimationFinished,
                )
                GameSidePanelContainer(
                    panelWidthDp = layout.panelWidthDp,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    ClockRow(model, forceStackCards = layout.panelWidthDp < 320)
                    OpponentStatusCard(
                        model = model,
                        opponent = opponent,
                        portraitSize = 68.dp,
                        onRetryBot = onRetryBot,
                        onDismissMessage = onDismissMessage,
                    )
                    GameControls(model.controls, onPause, onUndo, onHint, onFlip, onResign)
                    MoveHistory(
                        model.history,
                        Modifier.fillMaxWidth().height(layout.panelMoveHistoryHeightDp.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun GameStackedContentContainer(
    outerPadding: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .testTag("game_stacked_content")
            .verticalScroll(rememberScrollState())
            .padding(outerPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun GameSidePanelContainer(
    panelWidthDp: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .width(panelWidthDp.dp)
            .testTag("game_side_panel")
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun ChessBoard(
    model: BoardScreenState,
    boardSizeDp: Int,
    onEvent: (BoardEvent) -> Unit,
    showCoordinates: Boolean,
    onMoveAnimationFinished: (Int) -> Unit,
) {
    var boardPixels by remember { mutableIntStateOf(1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var presentedPositionMarker by remember { mutableStateOf(model.positionMarker) }
    var presentedPlyCount by remember { mutableIntStateOf(model.plyCount) }
    val moveToAnimate = model.moveMotion?.takeIf { motion ->
        model.positionMarker != presentedPositionMarker &&
            model.plyCount == presentedPlyCount + 1 &&
            motion.ply == model.plyCount &&
            motion.mover != model.humanSide
    }
    val moveProgress = remember(model.positionMarker, model.plyCount) {
        Animatable(if (moveToAnimate == null) 1f else 0f)
    }

    LaunchedEffect(model.positionMarker, model.plyCount) {
        val animatedPly = moveToAnimate?.ply
        if (moveToAnimate != null) {
            moveProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = GamePacing.PIECE_MOVE_ANIMATION_MILLIS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
        presentedPositionMarker = model.positionMarker
        presentedPlyCount = model.plyCount
        if (animatedPly != null) onMoveAnimationFinished(animatedPly)
    }

    val animationRunning = moveToAnimate != null && moveProgress.value < 1f
    val hiddenDestinations = if (animationRunning) {
        moveToAnimate.pieces.mapTo(mutableSetOf()) { it.to }
    } else {
        emptySet()
    }

    Box(
        modifier = Modifier
            .size(boardSizeDp.dp)
            .testTag("chess_board_${model.theme.id}")
            .onSizeChanged { boardPixels = it.width.coerceAtLeast(1) }
            .pointerInput(model.positionMarker, model.interaction.orientation, animationRunning) {
                if (!animationRunning) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragOffset = offset
                            squareAtOffset(model, offset, boardPixels)?.let { onEvent(BoardEvent.DragStarted(it)) }
                        },
                        onDrag = { change, amount ->
                            if (change.positionChange() != Offset.Zero) change.consume()
                            dragOffset += amount
                        },
                        onDragEnd = {
                            squareAtOffset(model, dragOffset, boardPixels)?.let { onEvent(BoardEvent.Dropped(it)) }
                                ?: onEvent(BoardEvent.DragCancelled)
                        },
                        onDragCancel = { onEvent(BoardEvent.DragCancelled) },
                    )
                }
            }
            .border(1.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            for (row in 0..7) {
                Row(Modifier.weight(1f)) {
                    model.cells.filter { it.displayRow == row }.forEach { cell ->
                        SquareCell(
                            cell,
                            model,
                            hidePiece = cell.square in hiddenDestinations,
                            inputEnabled = model.interactive && !animationRunning,
                            showCoordinates = showCoordinates,
                            onClick = { onEvent(BoardEvent.TapSquare(cell.square)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }

        if (animationRunning) {
            val squarePixels = boardPixels / 8f
            moveToAnimate.pieces.forEach { motion ->
                val from = model.cells.single { it.square == motion.from }
                val to = model.cells.single { it.square == motion.to }
                val startX = from.displayColumn * squarePixels
                val startY = from.displayRow * squarePixels
                val endX = to.displayColumn * squarePixels
                val endY = to.displayRow * squarePixels
                val progress = moveProgress.value
                ChessPiece(
                    side = motion.piece.side,
                    type = motion.piece.type,
                    modifier = Modifier
                        .testTag("moving_piece_${motion.from.algebraic}_${motion.to.algebraic}")
                        .offset {
                            IntOffset(
                                (startX + (endX - startX) * progress).roundToInt(),
                                (startY + (endY - startY) * progress).roundToInt(),
                            )
                        }
                        .size((boardSizeDp / 8).dp)
                        .padding(5.dp),
                )
            }
        }

        val dragged = model.interaction.draggingFrom?.let { square ->
            model.cells.firstOrNull { it.square == square }?.piece
        }
        if (dragged != null) {
            ChessPiece(
                dragged.side,
                dragged.type,
                modifier = Modifier
                    .offset {
                    val half = boardPixels / 16f
                    IntOffset((dragOffset.x - half).roundToInt(), (dragOffset.y - half).roundToInt())
                    }
                    .size((boardSizeDp / 8).dp)
                    .padding(5.dp),
            )
        }
    }
}

@Composable
internal fun SquareCell(
    cell: SquareView,
    board: BoardScreenState,
    hidePiece: Boolean,
    inputEnabled: Boolean,
    showCoordinates: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val squareDescription = squareAccessibilityText(cell.accessibility)
    val base = if (cell.square.isLight) board.theme.lightSquare.color() else board.theme.darkSquare.color()
    val overlay = when {
        cell.inCheck -> board.theme.check.color()
        cell.selected -> board.theme.selected.color()
        cell.lastMove -> board.theme.lastMove.color()
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .background(base)
            .background(overlay)
            .testTag("board_square_${cell.square.algebraic}")
            .semantics(mergeDescendants = true) {
                contentDescription = squareDescription
                role = Role.Button
            }
            .clickable(enabled = inputEnabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (cell.target == TargetKind.QUIET) {
            Box(Modifier.size(14.dp).background(board.theme.legalMove.color(), CircleShape))
        }
        if (cell.target == TargetKind.CAPTURE) {
            Box(Modifier.fillMaxSize().padding(4.dp).border(4.dp, board.theme.legalCapture.color(), CircleShape))
        }
        if (cell.threatened) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .border(2.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(5.dp)),
            )
        }
        cell.piece?.takeUnless { hidePiece }?.let { piece ->
            if (board.interaction.draggingFrom != cell.square) {
                ChessPiece(
                    piece.side,
                    piece.type,
                    Modifier.fillMaxSize().padding(5.dp),
                )
            }
        }
        if (showCoordinates && cell.displayColumn == 0) {
            Text(
                cell.square.algebraic.last().toString(),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = coordinateColor(cell.square.isLight),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .testTag("board_rank_${cell.square.algebraic}"),
            )
        }
        if (showCoordinates && cell.displayRow == 7) {
            Text(
                cell.square.algebraic.first().toString(),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = coordinateColor(cell.square.isLight),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .testTag("board_file_${cell.square.algebraic}"),
            )
        }
        if (cell.threatened) {
            val warningMarkColor = MaterialTheme.colorScheme.onTertiary
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 1.dp, vertical = 0.5.dp)
                    .size(12.dp)
                    .testTag("threat_badge_${cell.square.algebraic}")
                    .clearAndSetSemantics {},
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ) {
                Canvas(Modifier.fillMaxSize().padding(2.5.dp)) {
                    val centerX = size.width / 2f
                    val barWidth = maxOf(2f, size.minDimension * 0.24f)
                    val barHeight = size.height * 0.58f
                    drawRoundRect(
                        color = warningMarkColor,
                        topLeft = Offset(centerX - barWidth / 2f, 0f),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                    )
                    drawCircle(
                        color = warningMarkColor,
                        radius = maxOf(1.25f, size.minDimension * 0.14f),
                        center = Offset(centerX, size.height * 0.86f),
                    )
                }
            }
        }
    }
}

@Composable
private fun squareAccessibilityText(facts: SquareAccessibilityFacts): String {
    val base = facts.piece?.let { piece ->
        stringResource(
            R.string.board_piece_square,
            stringResource(
                R.string.side_piece,
                sideName(piece.side),
                pieceName(piece.type),
            ),
            facts.square.algebraic,
        )
    } ?: stringResource(R.string.board_empty_square, facts.square.algebraic)
    val details = buildList {
        when (facts.target) {
            TargetKind.QUIET -> add(stringResource(R.string.board_legal_move))
            TargetKind.CAPTURE -> add(stringResource(R.string.board_legal_capture))
            null -> Unit
        }
        if (facts.inCheck) add(stringResource(R.string.board_in_check))
        if (facts.threatened) add(stringResource(R.string.board_under_threat))
    }
    return details.fold(base) { text, detail ->
        stringResource(R.string.board_accessibility_join, text, detail)
    }
}

private fun squareAtOffset(model: BoardScreenState, offset: Offset, boardPixels: Int): Square? {
    if (offset.x < 0 || offset.y < 0 || offset.x >= boardPixels || offset.y >= boardPixels) return null
    val cell = boardPixels / 8f
    val row = (offset.y / cell).toInt().coerceIn(0, 7)
    val column = (offset.x / cell).toInt().coerceIn(0, 7)
    return model.cells.firstOrNull { it.displayRow == row && it.displayColumn == column }?.square
}

@Composable
private fun ClockRow(
    model: GameScreenModel,
    modifier: Modifier = Modifier,
    forceStackCards: Boolean = false,
) {
    val whiteLabel = stringResource(R.string.label_white)
    val blackLabel = stringResource(R.string.label_black)
    val captureLeadText = model.capturedMaterial.lead.side?.let { side ->
        stringResource(
            R.string.game_captured_lead,
            sideName(side),
            model.capturedMaterial.lead.points,
        )
    } ?: stringResource(R.string.game_captured_even)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stackCards = forceStackCards || maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
        Column(
            modifier = Modifier.fillMaxWidth().testTag("captured_material"),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (stackCards) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ClockCard(whiteLabel, model.whiteClock, model.capturedMaterial.white, Modifier.fillMaxWidth())
                    ClockCard(blackLabel, model.blackClock, model.capturedMaterial.black, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClockCard(whiteLabel, model.whiteClock, model.capturedMaterial.white, Modifier.weight(1f))
                    ClockCard(blackLabel, model.blackClock, model.capturedMaterial.black, Modifier.weight(1f))
                }
            }
            Text(
                text = captureLeadText,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clearAndSetSemantics {
                        contentDescription = captureLeadText
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClockCard(
    label: String,
    clock: ClockView,
    captured: CapturedMaterialSideView,
    modifier: Modifier,
) {
    val container = when {
        clock.lowTime -> MaterialTheme.colorScheme.errorContainer
        clock.active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(modifier, color = container, shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(clock.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            CapturedMaterialSide(captured, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun OpponentStatusCard(
    model: GameScreenModel,
    opponent: OpponentProfile,
    portraitSize: Dp,
    onRetryBot: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val thinking = model.board.phase == CoordinatorPhase.BOT_THINKING
    val opponentDisplayName = opponentName(opponent)
    val statusText = when (model.board.status) {
        BoardStatus.HUMAN_TURN -> stringResource(R.string.status_your_turn)
        BoardStatus.HINT_THINKING -> stringResource(R.string.status_hint_thinking)
        BoardStatus.BOT_THINKING -> stringResource(R.string.game_opponent_thinking, opponentDisplayName)
        BoardStatus.BOT_ERROR -> stringResource(R.string.game_opponent_unavailable, opponentDisplayName)
        BoardStatus.PAUSED -> stringResource(R.string.status_paused)
        BoardStatus.COMPLETED -> stringResource(R.string.status_completed)
    }
    val transientMessage = when (val notice = model.transientNotice) {
        GameNotice.ActionUnavailable -> stringResource(R.string.notice_action_unavailable)
        is GameNotice.External -> notice.message
        null -> null
    }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("opponent_status_card"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OpponentPortrait(
                profile = opponent,
                size = portraitSize,
                emphasized = true,
                thinking = thinking,
                modifier = Modifier.testTag("game_opponent_avatar_${opponent.level.id}"),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    opponentDisplayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(
                        R.string.game_title_summary,
                        botLevelName(opponent.level),
                        stringResource(opponent.epithetRes),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusText,
                    modifier = Modifier.testTag("game_status"),
                    fontWeight = FontWeight.Medium,
                )
                transientMessage?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.action_dismiss)) }
                    }
                }
                if (model.board.phase == CoordinatorPhase.BOT_ERROR) {
                    FilledTonalButton(onClick = onRetryBot) {
                        Text(stringResource(R.string.game_retry_opponent, opponentDisplayName))
                    }
                }
            }
        }
    }
}

@Composable
internal fun GameControls(
    controls: GameControlsView,
    onPause: () -> Unit,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onFlip: () -> Unit,
    onResign: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag("game_controls"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = onPause, enabled = controls.canPause) {
            Text(stringResource(if (controls.paused) R.string.game_resume else R.string.game_pause))
        }
        FilledTonalButton(onClick = onUndo, enabled = controls.canUndo) { Text(stringResource(R.string.game_undo)) }
        FilledTonalButton(onClick = onHint, enabled = controls.canHint) { Text(stringResource(R.string.game_hint)) }
        FilledTonalButton(onClick = onFlip, enabled = controls.canFlip) { Text(stringResource(R.string.game_flip)) }
        TextButton(
            onClick = onResign,
            enabled = controls.canResign,
            modifier = Modifier.testTag("resign_button"),
        ) { Text(stringResource(R.string.game_resign)) }
    }
}

@Composable
internal fun MoveHistory(history: List<MoveHistoryRow>, modifier: Modifier) {
    val listState = rememberLazyListState()
    var followNewest by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followNewest = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    followNewest = !listState.canScrollForward
                }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) followNewest = !listState.canScrollForward
        }
    }
    LaunchedEffect(history.size) {
        if (history.isEmpty()) {
            followNewest = true
        } else if (followNewest) {
            listState.scrollToItem(history.lastIndex)
        }
    }

    ElevatedCard(modifier.testTag("move_history"), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text(stringResource(R.string.game_moves), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (history.isEmpty()) {
                Text(stringResource(R.string.game_no_moves), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
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
                    modifier = Modifier.weight(1f).testTag("move_history_list"),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(history, key = { it.moveNumber }) { row ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.move_number_format, row.moveNumber),
                                Modifier.width(34.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MoveHistoryCell(row.white, row.moveNumber, "white", Modifier.weight(1f))
                            MoveHistoryCell(row.black, row.moveNumber, "black", Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CapturedMaterialSide(material: CapturedMaterialSideView, modifier: Modifier) {
    val materialDescription = capturedMaterialAccessibilityText(material)
    Column(
        modifier = modifier
            .testTag("captured_by_${material.capturedBy.name.lowercase()}")
            .clearAndSetSemantics { contentDescription = materialDescription },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                stringResource(R.string.game_captured),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Text(
                stringResource(R.string.game_material_score, material.totalValue),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        if (material.pieces.isEmpty()) {
            Text(
                stringResource(R.string.game_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val visibleTypes = CAPTURE_DISPLAY_ORDER.filter { type -> type in material.pieces }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val availableWidthDp = maxWidth.value.roundToInt().coerceAtLeast(1)
                val slotDp = captureGroupSlotSizeDp(availableWidthDp, visibleTypes.size)
                val iconDp = (slotDp - 4).coerceAtLeast(14)
                val density = LocalDensity.current
                val badgeFontSize = with(density) { 8.dp.toSp() }
                val badgeLineHeight = with(density) { 10.dp.toSp() }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CAPTURE_GROUP_GAP_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(CAPTURE_GROUP_GAP_DP.dp),
                ) {
                    visibleTypes.forEach { type ->
                        val count = material.pieces.count { it == type }
                        Box(Modifier.size(slotDp.dp)) {
                            ChessPiece(
                                side = material.capturedBy.opposite(),
                                type = type,
                                modifier = Modifier.align(Alignment.CenterStart).size(iconDp.dp),
                            )
                            if (count > 1) {
                                Text(
                                    count.toString(),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(11.dp)
                                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = badgeFontSize,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = badgeLineHeight,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun capturedMaterialAccessibilityText(material: CapturedMaterialSideView): String {
    val side = sideName(material.capturedBy)
    if (material.pieces.isEmpty()) {
        return stringResource(R.string.captured_none_accessibility, side, material.totalValue)
    }
    val groups = CAPTURE_DISPLAY_ORDER.mapNotNull { type ->
        val count = material.pieces.count { it == type }
        if (count == 0) return@mapNotNull null
        val resource = when (type) {
            PieceType.QUEEN -> R.plurals.captured_queens
            PieceType.ROOK -> R.plurals.captured_rooks
            PieceType.BISHOP -> R.plurals.captured_bishops
            PieceType.KNIGHT -> R.plurals.captured_knights
            PieceType.PAWN -> R.plurals.captured_pawns
            PieceType.KING -> return@mapNotNull null
        }
        pluralStringResource(resource, count, count)
    }
    return stringResource(
        R.string.captured_accessibility,
        side,
        groups.joinToString(", "),
        material.totalValue,
    )
}

@Composable
private fun moveAccessibilityText(facts: MoveAccessibilityFacts): String {
    val side = sideName(facts.mover)
    var text = when (facts.castleSide) {
        CastleSide.KING_SIDE -> stringResource(
            R.string.move_accessibility_castle_king,
            side,
            facts.moveNumber,
            facts.notation,
        )
        CastleSide.QUEEN_SIDE -> stringResource(
            R.string.move_accessibility_castle_queen,
            side,
            facts.moveNumber,
            facts.notation,
        )
        null -> if (facts.capturedPiece != null && facts.capturedSide != null) {
            val capturedPiece = requireNotNull(facts.capturedPiece)
            val capturedSide = requireNotNull(facts.capturedSide)
            stringResource(
                R.string.move_accessibility_capture,
                side,
                facts.moveNumber,
                pieceName(facts.movingPiece),
                facts.from.algebraic,
                stringResource(
                    R.string.side_piece,
                    sideName(capturedSide),
                    pieceName(capturedPiece),
                ),
                (facts.capturedSquare ?: facts.to).algebraic,
                facts.notation,
            )
        } else {
            stringResource(
                R.string.move_accessibility,
                side,
                facts.moveNumber,
                pieceName(facts.movingPiece),
                facts.from.algebraic,
                facts.to.algebraic,
                facts.notation,
            )
        }
    }
    if (facts.enPassant) text = stringResource(R.string.move_accessibility_en_passant, text)
    facts.promotedTo?.let { promoted ->
        text = stringResource(R.string.move_accessibility_promotion, text, pieceName(promoted))
    }
    return text
}

@Composable
private fun MoveHistoryCell(
    entry: MoveHistoryEntry?,
    moveNumber: Int,
    column: String,
    modifier: Modifier,
) {
    if (entry == null) {
        Spacer(modifier)
        return
    }
    val moveDescription = moveAccessibilityText(entry.accessibility)
    Row(
        modifier = modifier
            .testTag("move_${moveNumber}_$column")
            .clearAndSetSemantics { contentDescription = moveDescription }
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChessPiece(entry.mover, entry.piece, Modifier.size(24.dp))
        Text(entry.notation, maxLines = 1, overflow = TextOverflow.Ellipsis)
        entry.promotedTo?.let { promotedTo ->
            Text("→", style = MaterialTheme.typography.labelSmall)
            ChessPiece(entry.mover, promotedTo, Modifier.size(14.dp))
        }
    }
}

@Composable
private fun PromotionDialog(
    side: Side,
    choices: List<PieceType>,
    onChoose: (PieceType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_promote_pawn)) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                choices.forEach { piece ->
                    val pieceDescription = pieceName(piece)
                    FilledTonalButton(
                        onClick = { onChoose(piece) },
                        modifier = Modifier.semantics { contentDescription = pieceDescription },
                    ) {
                        ChessPiece(side, piece, Modifier.size(38.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun List<MoveHistoryRow>.plyCount(): Int = sumOf { row ->
    (if (row.white == null) 0 else 1) + (if (row.black == null) 0 else 1)
}

private fun List<MoveHistoryRow>.lastPlayedSan(): String? =
    lastOrNull()?.let { row -> row.black ?: row.white }?.notation

@Composable
private fun sideName(side: Side): String = stringResource(
    if (side == Side.WHITE) R.string.label_white else R.string.label_black,
)

@Composable
private fun pieceName(piece: PieceType): String = stringResource(
    when (piece) {
        PieceType.PAWN -> R.string.piece_pawn
        PieceType.KNIGHT -> R.string.piece_knight
        PieceType.BISHOP -> R.string.piece_bishop
        PieceType.ROOK -> R.string.piece_rook
        PieceType.QUEEN -> R.string.piece_queen
        PieceType.KING -> R.string.piece_king
    },
)

private val CAPTURE_DISPLAY_ORDER = listOf(
    PieceType.QUEEN,
    PieceType.ROOK,
    PieceType.BISHOP,
    PieceType.KNIGHT,
    PieceType.PAWN,
)

internal const val CAPTURE_GROUP_MAX_SLOT_DP = 22
internal const val CAPTURE_GROUP_MIN_SLOT_DP = 16
internal const val CAPTURE_GROUP_GAP_DP = 2

internal fun captureGroupSlotSizeDp(availableWidthDp: Int, distinctPieceTypes: Int): Int {
    require(availableWidthDp > 0)
    require(distinctPieceTypes in 0..CAPTURE_DISPLAY_ORDER.size)
    if (distinctPieceTypes == 0) return 0
    val gaps = (distinctPieceTypes - 1) * CAPTURE_GROUP_GAP_DP
    return ((availableWidthDp - gaps) / distinctPieceTypes)
        .coerceIn(CAPTURE_GROUP_MIN_SLOT_DP, CAPTURE_GROUP_MAX_SLOT_DP)
}

internal fun captureInventoryRequiredWidthDp(
    availableWidthDp: Int,
    distinctPieceTypes: Int,
): Int {
    if (distinctPieceTypes == 0) return 0
    val slotDp = captureGroupSlotSizeDp(availableWidthDp, distinctPieceTypes)
    return distinctPieceTypes * slotDp +
        (distinctPieceTypes - 1) * CAPTURE_GROUP_GAP_DP
}

private fun ArgbColor.color(): Color = Color(value)

private fun coordinateColor(lightSquare: Boolean): Color =
    if (lightSquare) Color.Black.copy(alpha = 0.58f) else Color.White.copy(alpha = 0.72f)
