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
import com.drawlesschess.core.coordinator.CoordinatorPhase
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.presentation.*
import com.drawlesschess.persistence.PlayerStatistics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
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
                        Text("Drawless Chess")
                        Text(
                            "${model.rulesLabel} · ${model.modeLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = exitGame) { Text("Save & exit") } },
                actions = {
                    TextButton(
                        onClick = onShowThemes,
                        modifier = Modifier.testTag("game_theme_selector"),
                    ) {
                        Text("Theme")
                    }
                },
            )
        },
        bottomBar = {
            postGameResult?.let { result ->
                PostGameBar(
                    result = result,
                    opponentName = opponentProfile.name,
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
            title = { Text("Resign this game?") },
            text = { Text("${opponentProfile.name} will be awarded the win.") },
            confirmButton = {
                Button(onClick = {
                    confirmResign = false
                    model = controller.resign()
                }) { Text("Resign game") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResign = false }) { Text("Keep playing") }
            },
        )
    }
}

@Composable
internal fun PostGameBar(
    result: GameResultView,
    opponentName: String = "Your opponent",
    careerAverageGameScore: Double? = null,
    onHome: () -> Unit,
    onRematch: () -> Unit,
) {
    val headline = if (result.playerWon) "Victory" else "Defeat"
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
                            "$headline, score ${result.score.points} of ${result.score.maximumPoints}"
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
                        if (opponentName == "Your opponent") {
                            "You won this game."
                        } else {
                            "You defeated $opponentName."
                        }
                    } else {
                        "$opponentName won this game."
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
                Text(result.explanation, color = onContainer.copy(alpha = 0.82f))
                Text(
                    text = "Score: ${result.score.points} / ${result.score.maximumPoints}",
                    modifier = Modifier.testTag("post_game_score"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onContainer,
                )
                careerAverageGameScore?.let { average ->
                    Text(
                        "Career average game score: ${String.format(Locale.US, "%.1f", average)}",
                        modifier = Modifier.testTag("career_average_score"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.88f),
                    )
                }
                if (result.score.hintPenalty > 0) {
                    ScorePenaltyLine(
                        label = "Hints",
                        points = result.score.hintPenalty,
                        tag = "hint_score_penalty",
                        color = onContainer.copy(alpha = 0.82f),
                    )
                }
                if (result.score.undoPenalty > 0) {
                    ScorePenaltyLine(
                        label = "Undos",
                        points = result.score.undoPenalty,
                        tag = "undo_score_penalty",
                        color = onContainer.copy(alpha = 0.82f),
                    )
                }
                if (result.score.timedPausePenalty > 0) {
                    ScorePenaltyLine(
                        label = "Timed pauses",
                        points = result.score.timedPausePenalty,
                        tag = "pause_score_penalty",
                        color = onContainer.copy(alpha = 0.82f),
                    )
                }
                if (result.score.threatIndicationPenalty > 0) {
                    ScorePenaltyLine(
                        label = "Threat indication",
                        points = result.score.threatIndicationPenalty,
                        tag = "threat_score_penalty",
                        color = onContainer.copy(alpha = 0.82f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onHome, modifier = Modifier.weight(1f)) { Text("Home") }
                    Button(onClick = onRematch, modifier = Modifier.weight(1f)) { Text("Rematch") }
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
        "$label: −$points points",
        modifier = Modifier.testTag(tag),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

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
                contentDescription = cell.accessibilityLabel
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
                    ClockCard("White", model.whiteClock, model.capturedMaterial.white, Modifier.fillMaxWidth())
                    ClockCard("Black", model.blackClock, model.capturedMaterial.black, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClockCard("White", model.whiteClock, model.capturedMaterial.white, Modifier.weight(1f))
                    ClockCard("Black", model.blackClock, model.capturedMaterial.black, Modifier.weight(1f))
                }
            }
            Text(
                text = model.capturedMaterial.lead.side?.let { side ->
                    "${side.displayName()} leads the captured piece score by ${model.capturedMaterial.lead.points}"
                } ?: "Captured piece score is even",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clearAndSetSemantics {
                        contentDescription = model.capturedMaterial.leadAccessibilityLabel
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
    val statusText = when (model.board.phase) {
        CoordinatorPhase.BOT_THINKING -> "${opponent.name} is thinking"
        CoordinatorPhase.BOT_ERROR -> if (model.board.statusText == "Opponent unavailable") {
            "${opponent.name} is unavailable"
        } else {
            model.board.statusText
        }
        else -> model.board.statusText
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
                    opponent.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${opponent.level.displayName} · ${opponent.epithet}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(statusText, fontWeight = FontWeight.Medium)
                model.transientMessage?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                    }
                }
                if (model.board.phase == CoordinatorPhase.BOT_ERROR) {
                    FilledTonalButton(onClick = onRetryBot) { Text("Retry ${opponent.name}") }
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
            Text(if (controls.paused) "Resume" else "Pause")
        }
        FilledTonalButton(onClick = onUndo, enabled = controls.canUndo) { Text("Undo") }
        FilledTonalButton(onClick = onHint, enabled = controls.canHint) { Text("Hint") }
        FilledTonalButton(onClick = onFlip, enabled = controls.canFlip) { Text("Flip") }
        TextButton(
            onClick = onResign,
            enabled = controls.canResign,
            modifier = Modifier.testTag("resign_button"),
        ) { Text("Resign") }
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
            Text("Moves", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (history.isEmpty()) {
                Text("No moves yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(34.dp))
                    Text(
                        "White",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Black",
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
                            Text("${row.moveNumber}.", Modifier.width(34.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(
        modifier = modifier
            .testTag("captured_by_${material.capturedBy.name.lowercase()}")
            .clearAndSetSemantics { contentDescription = material.accessibilityLabel },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                "Captured",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Text(
                "Score ${material.totalValue}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        if (material.pieces.isEmpty()) {
            Text(
                "None",
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
    Row(
        modifier = modifier
            .testTag("move_${moveNumber}_$column")
            .clearAndSetSemantics { contentDescription = entry.accessibilityLabel }
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
        title = { Text("Promote pawn") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                choices.forEach { piece ->
                    FilledTonalButton(
                        onClick = { onChoose(piece) },
                        modifier = Modifier.semantics { contentDescription = piece.name.lowercase() },
                    ) {
                        ChessPiece(side, piece, Modifier.size(38.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun List<MoveHistoryRow>.plyCount(): Int = sumOf { row ->
    (if (row.white == null) 0 else 1) + (if (row.black == null) 0 else 1)
}

private fun List<MoveHistoryRow>.lastPlayedSan(): String? =
    lastOrNull()?.let { row -> row.black ?: row.white }?.notation

private fun Side.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

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
