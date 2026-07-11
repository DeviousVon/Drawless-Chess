@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.drawlesschess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun GameRoute(
    runtime: GameRuntime,
    soundPlayer: GameSoundPlayer,
    onExit: () -> Unit,
    onRematch: () -> Unit,
) {
    val controller = runtime.controller
    var model by remember(controller) { mutableStateOf(controller.model()) }
    val uiScope = rememberCoroutineScope()
    var soundedPlyCount by remember(controller) { mutableIntStateOf(model.history.plyCount()) }
    var confirmResign by remember { mutableStateOf(false) }
    var previousPhase by remember(controller) { mutableStateOf(model.board.phase) }
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

    val visiblePlyCount = model.history.plyCount()
    LaunchedEffect(soundPlayer, visiblePlyCount, lifecycleStarted) {
        if (lifecycleStarted && visiblePlyCount > soundedPlyCount) {
            val latestSan = model.history.lastPlayedSan().orEmpty()
            soundPlayer.playMove(capture = 'x' in latestSan)
        }
        soundedPlyCount = visiblePlyCount
    }

    LaunchedEffect(soundPlayer, lifecycleStarted) {
        if (!lifecycleStarted) soundPlayer.stopAll()
    }

    LaunchedEffect(model.board.phase, model.result, lifecycleStarted) {
        if (!lifecycleStarted) return@LaunchedEffect
        if (previousPhase != CoordinatorPhase.COMPLETED &&
            model.board.phase == CoordinatorPhase.COMPLETED
        ) {
            completionEffect = model.result
        }
        previousPhase = model.board.phase
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
        completionEffect = null
        soundPlayer.stopAll()
        onExit()
    }
    val rematchGame = {
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
                navigationIcon = { TextButton(onClick = exitGame) { Text("Exit") } },
            )
        },
        bottomBar = {
            model.result?.let { result ->
                PostGameBar(
                    result = result,
                    onHome = exitGame,
                    onRematch = rematchGame,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            GameBody(
                model = model,
                modifier = Modifier.fillMaxSize(),
                onBoardEvent = { model = controller.boardEvent(it) },
                onPause = { model = controller.pauseOrResume() },
                onUndo = { model = controller.undo() },
                onHint = { model = controller.hint() },
                onFlip = { model = controller.boardEvent(BoardEvent.FlipBoard) },
                onRetryBot = { model = controller.retryBot() },
                onResign = { confirmResign = true },
                onDismissMessage = { model = controller.dismissMessage() },
            )
            completionEffect?.let { result ->
                CompletionEffectOverlay(
                    result = result,
                    modifier = Modifier.fillMaxSize(),
                    onCue = soundPlayer::playCompletionCue,
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
            text = { Text("Your opponent will be awarded the win.") },
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
    Surface(color = container, tonalElevation = 10.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("post_game_feedback")
                .semantics {
                    stateDescription = headline
                    liveRegion = LiveRegionMode.Polite
                }
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
                if (result.playerWon) "You won this game." else "Your opponent won this game.",
                fontWeight = FontWeight.SemiBold,
                color = onContainer,
            )
            Text(result.explanation, color = onContainer.copy(alpha = 0.82f))
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

@Composable
private fun GameBody(
    model: GameScreenModel,
    modifier: Modifier,
    onBoardEvent: (BoardEvent) -> Unit,
    onPause: () -> Unit,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onFlip: () -> Unit,
    onRetryBot: () -> Unit,
    onResign: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = ResponsiveBoardLayout.calculate(maxWidth.value.roundToInt(), maxHeight.value.roundToInt())
        if (layout.controlPlacement == ControlPlacement.BELOW_BOARD) {
            Column(
                modifier = Modifier.fillMaxSize().padding(layout.outerPaddingDp.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClockRow(model)
                ChessBoard(model.board, layout.boardSizeDp, onBoardEvent)
                StatusCard(model, onRetryBot, onDismissMessage)
                GameControls(model.controls, onPause, onUndo, onHint, onFlip, onResign)
                MoveHistory(model.history, Modifier.fillMaxWidth().weight(1f))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(layout.outerPaddingDp.dp),
                horizontalArrangement = Arrangement.spacedBy(layout.outerPaddingDp.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ChessBoard(model.board, layout.boardSizeDp, onBoardEvent)
                    ClockRow(model, Modifier.width(layout.boardSizeDp.dp))
                }
                Column(
                    modifier = Modifier.width(layout.panelWidthDp.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusCard(model, onRetryBot, onDismissMessage)
                    GameControls(model.controls, onPause, onUndo, onHint, onFlip, onResign)
                    MoveHistory(model.history, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ChessBoard(
    model: BoardScreenState,
    boardSizeDp: Int,
    onEvent: (BoardEvent) -> Unit,
) {
    var boardPixels by remember { mutableIntStateOf(1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .size(boardSizeDp.dp)
            .onSizeChanged { boardPixels = it.width.coerceAtLeast(1) }
            .pointerInput(model.positionMarker, model.interaction.orientation) {
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
            .border(1.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            for (row in 0..7) {
                Row(Modifier.weight(1f)) {
                    model.cells.filter { it.displayRow == row }.forEach { cell ->
                        SquareCell(
                            cell,
                            model,
                            onClick = { onEvent(BoardEvent.TapSquare(cell.square)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
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
private fun SquareCell(
    cell: SquareView,
    board: BoardScreenState,
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
            .semantics(mergeDescendants = true) {
                contentDescription = cell.accessibilityLabel
                role = Role.Button
            }
            .clickable(enabled = board.interactive, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (cell.target == TargetKind.QUIET) {
            Box(Modifier.size(14.dp).background(board.theme.legalMove.color(), CircleShape))
        }
        if (cell.target == TargetKind.CAPTURE) {
            Box(Modifier.fillMaxSize().padding(4.dp).border(4.dp, board.theme.legalCapture.color(), CircleShape))
        }
        cell.piece?.let { piece ->
            if (board.interaction.draggingFrom != cell.square) {
                ChessPiece(
                    piece.side,
                    piece.type,
                    Modifier.fillMaxSize().padding(5.dp),
                )
            }
        }
        if (cell.displayColumn == 0) {
            Text(
                cell.square.algebraic.last().toString(),
                fontSize = 9.sp,
                color = coordinateColor(cell.square.isLight),
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
            )
        }
        if (cell.displayRow == 7) {
            Text(
                cell.square.algebraic.first().toString(),
                fontSize = 9.sp,
                color = coordinateColor(cell.square.isLight),
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
            )
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
private fun ClockRow(model: GameScreenModel, modifier: Modifier = Modifier.fillMaxWidth()) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ClockCard("White", model.whiteClock, Modifier.weight(1f))
        ClockCard("Black", model.blackClock, Modifier.weight(1f))
    }
}

@Composable
private fun ClockCard(label: String, clock: ClockView, modifier: Modifier) {
    val container = when {
        clock.lowTime -> MaterialTheme.colorScheme.errorContainer
        clock.active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(modifier, color = container, shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(clock.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusCard(
    model: GameScreenModel,
    onRetryBot: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.board.statusText, fontWeight = FontWeight.Medium)
            model.transientMessage?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                    TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                }
            }
            if (model.board.phase == com.drawlesschess.core.coordinator.CoordinatorPhase.BOT_ERROR) {
                FilledTonalButton(onClick = onRetryBot) { Text("Retry opponent") }
            }
        }
    }
}

@Composable
private fun GameControls(
    controls: GameControlsView,
    onPause: () -> Unit,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onFlip: () -> Unit,
    onResign: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = onPause, enabled = controls.canPause) {
            Text(if (controls.paused) "Resume" else "Pause")
        }
        FilledTonalButton(onClick = onUndo, enabled = controls.canUndo) { Text("Undo") }
        FilledTonalButton(onClick = onHint, enabled = controls.canHint) { Text("Hint") }
        FilledTonalButton(onClick = onFlip, enabled = controls.canFlip) { Text("Flip") }
        TextButton(onClick = onResign, enabled = controls.canResign) { Text("Resign") }
    }
}

@Composable
private fun MoveHistory(history: List<MoveHistoryRow>, modifier: Modifier) {
    ElevatedCard(modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("Moves", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (history.isEmpty()) {
                Text("No moves yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(history) { row ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("${row.moveNumber}.", Modifier.width(34.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(row.white.orEmpty(), Modifier.weight(1f))
                            Text(row.black.orEmpty(), Modifier.weight(1f))
                        }
                    }
                }
            }
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
    lastOrNull()?.let { row -> row.black ?: row.white }

private fun ArgbColor.color(): Color = Color(value)

private fun coordinateColor(lightSquare: Boolean): Color =
    if (lightSquare) Color.Black.copy(alpha = 0.58f) else Color.White.copy(alpha = 0.72f)
