@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.drawlesschess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drawlesschess.core.*
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.presentation.BoardTheme

@Composable
internal fun DrawlessApp(viewModel: DrawlessAppViewModel, soundPlayer: GameSoundPlayer) {
    var showThemePicker by rememberSaveable { mutableStateOf(false) }

    when (viewModel.route) {
        AppRoute.HOME -> HomeScreen(
            resumeState = viewModel.resumeState,
            selectedTheme = viewModel.selectedTheme,
            onResume = viewModel::resumeGame,
            onQuickPlay = viewModel::startQuickPlay,
            onCustomGame = viewModel::showNewGameSetup,
            onShowThemes = { showThemePicker = true },
            onShowRules = viewModel::showRulesGuide,
            onDiscard = viewModel::discardSavedGame,
        )
        AppRoute.SETUP -> {
            BackHandler(onBack = viewModel::leaveSetup)
            SetupScreen(
                selection = viewModel.setupSelection,
                onSelectionChanged = viewModel::updateSetupSelection,
                onBack = viewModel::leaveSetup,
                onStart = viewModel::startNewGame,
            )
        }
        AppRoute.GAME -> {
            val runtime = viewModel.runtime
            if (runtime == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                BackHandler(onBack = viewModel::exitGame)
                GameRoute(
                    runtime = runtime,
                    soundPlayer = soundPlayer,
                    selectedTheme = viewModel.selectedTheme,
                    onShowThemes = { showThemePicker = true },
                    onExit = viewModel::exitGame,
                    onRematch = viewModel::rematchGame,
                )
            }
        }
    }

    if (viewModel.route == AppRoute.HOME && viewModel.showRulesGuide) {
        RulesGuideDialog(onDismiss = viewModel::dismissRulesGuide)
    }

    if (showThemePicker) {
        ThemePickerDialog(
            selectedTheme = viewModel.selectedTheme,
            onSelect = viewModel::selectTheme,
            onDismiss = { showThemePicker = false },
        )
    }
}

@Composable
private fun HomeScreen(
    resumeState: ResumeState,
    selectedTheme: BoardTheme,
    onResume: () -> Unit,
    onQuickPlay: () -> Unit,
    onCustomGame: () -> Unit,
    onShowThemes: () -> Unit,
    onShowRules: () -> Unit,
    onDiscard: () -> Unit,
) {
    var showLicense by rememberSaveable { mutableStateOf(false) }
    val visualTheme = LocalDrawlessVisualTheme.current
    val home = visualTheme.home
    val primaryButtonColors = ButtonDefaults.buttonColors(
        containerColor = home.accent,
        contentColor = home.onAccent,
        disabledContainerColor = home.accent.copy(alpha = 0.34f),
        disabledContentColor = home.title.copy(alpha = 0.55f),
    )
    val outlinedButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = home.accent)
    val textButtonColors = ButtonDefaults.textButtonColors(contentColor = home.accent)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(home.gradient),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Surface(
                color = home.accent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(30.dp),
                tonalElevation = 10.dp,
            ) {
                ChessPiece(
                    side = Side.BLACK,
                    type = PieceType.KING,
                    modifier = Modifier.size(118.dp).padding(12.dp),
                )
            }
            Text(
                "Drawless Chess",
                color = home.title,
                fontSize = 38.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Every game has a winner.",
                color = home.subtitle,
                fontSize = 18.sp,
            )
            when (resumeState) {
                is ResumeState.Ready -> {
                    Button(
                        onClick = onResume,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = primaryButtonColors,
                    ) {
                        Text("Resume game", fontSize = 17.sp)
                    }
                }
                ResumeState.Loading -> {
                    Button(
                        onClick = onQuickPlay,
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = primaryButtonColors,
                    ) {
                        Text("Checking saved game…", fontSize = 17.sp)
                    }
                }
                ResumeState.Empty -> Unit
                is ResumeState.Failed -> {
                    Text(
                        resumeState.message,
                        color = visualTheme.darkColors.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onDiscard, colors = textButtonColors) {
                        Text("Discard saved game")
                    }
                }
            }
            if (resumeState != ResumeState.Loading) {
                Button(
                    onClick = onQuickPlay,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = primaryButtonColors,
                ) {
                    Text("Quick Play", fontSize = 17.sp)
                }
                Text(
                    "Drawless rules · Casual opponent · No clock",
                    color = home.muted,
                    fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = onCustomGame,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = outlinedButtonColors,
                    border = BorderStroke(1.dp, home.accent.copy(alpha = 0.72f)),
                ) {
                    Text("Custom game", fontSize = 16.sp)
                }
                OutlinedButton(
                    onClick = onShowThemes,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = outlinedButtonColors,
                    border = BorderStroke(1.dp, home.accent.copy(alpha = 0.72f)),
                ) {
                    Text("Theme · ${selectedTheme.name}", fontSize = 15.sp)
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onShowRules, colors = textButtonColors) {
                    Text("How Drawless works")
                }
                TextButton(onClick = { showLicense = true }, colors = textButtonColors) {
                    Text("Open-source license")
                }
            }
            Text(
                "Offline • Decisive rules • Modern play",
                color = home.faint,
                fontSize = 13.sp,
            )
        }
    }

    if (showLicense) {
        LicenseDialog(onDismiss = { showLicense = false })
    }
}

@Composable
private fun RulesGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drawless in one minute") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("• Checkmate still wins.")
                Text("• In default Drawless, a player with no legal move loses. The optional Escape variant makes stalemate a win instead.")
                Text("• Causing the same position a third time loses, unless every legal move repeats.")
                Text("• If checkmate becomes impossible, the selected dead-position rule awards the game.")
                Text("• There is no automatic 50-move ending.")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Got it") }
        },
    )
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Free and open-source") },
        text = {
            Text(
                "Drawless Chess is licensed under GNU GPL version 3 or later. " +
                    "Public source is hosted at github.com/DeviousVon/Drawless-Chess. " +
                    "Exact corresponding source and third-party notices accompany each official release.",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(
                onClick = {
                    uriHandler.openUri("https://github.com/DeviousVon/Drawless-Chess")
                },
            ) {
                Text("View source")
            }
        },
    )
}

@Composable
private fun SetupScreen(
    selection: SetupSelection,
    onSelectionChanged: (SetupSelection) -> Unit,
    onBack: () -> Unit,
    onStart: (SetupSelection) -> Unit,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom game") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp) {
                Button(
                    onClick = { onStart(selection) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Start game") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SetupSection("Clock") {
                ChoiceRow {
                    ClockChoices.forEach { choice ->
                        ChoiceChip(choice.label, selection.timeControl == choice.control) {
                            onSelectionChanged(selection.copy(timeControl = choice.control))
                        }
                    }
                }
            }

            SetupSection("Play as") {
                ChoiceRow {
                    ChoiceChip("White", selection.humanSide == Side.WHITE) {
                        onSelectionChanged(selection.copy(humanSide = Side.WHITE))
                    }
                    ChoiceChip("Black", selection.humanSide == Side.BLACK) {
                        onSelectionChanged(selection.copy(humanSide = Side.BLACK))
                    }
                }
            }

            SetupSection("Opponent") {
                ChoiceRow {
                    BotLevels.forEach { level ->
                        ChoiceChip(
                            level.displayName,
                            selection.botLevel == level,
                        ) { onSelectionChanged(selection.copy(botLevel = level)) }
                    }
                }
                Text(
                    selection.botLevel.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SetupSection("Advanced rules") {
                Text(
                    "Quick Play uses the recommended Drawless rules. Change these only if you want a variant.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Text(if (advancedExpanded) "Hide options" else "Show options")
                }
                if (advancedExpanded) {
                    Text("Stalemate", fontWeight = FontWeight.SemiBold)
                    ChoiceRow {
                        ChoiceChip("Drawless", selection.preset == RulesContractV1.Preset.DRAWLESS) {
                            onSelectionChanged(selection.copy(preset = RulesContractV1.Preset.DRAWLESS))
                        }
                        ChoiceChip("Escape", selection.preset == RulesContractV1.Preset.ESCAPE) {
                            onSelectionChanged(selection.copy(preset = RulesContractV1.Preset.ESCAPE))
                        }
                    }
                    Text(
                        if (selection.preset == RulesContractV1.Preset.DRAWLESS) {
                            "Default: a player with no legal move loses."
                        } else {
                            "Escape variant: a stalemated player wins instead."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Text("Impossible checkmate", fontWeight = FontWeight.SemiBold)
                    ChoiceRow {
                        ChoiceChip("Material wins", selection.deadPosition == DeadPositionPolicy.MATERIAL_VICTORY) {
                            onSelectionChanged(selection.copy(deadPosition = DeadPositionPolicy.MATERIAL_VICTORY))
                        }
                        ChoiceChip("Final capture", selection.deadPosition == DeadPositionPolicy.FINAL_CAPTURE_VICTORY) {
                            onSelectionChanged(selection.copy(deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY))
                        }
                    }
                    Text(
                        if (selection.deadPosition == DeadPositionPolicy.MATERIAL_VICTORY) {
                            "When checkmate is impossible, the side with more material wins; equal material favors the last mover."
                        } else {
                            "When a capture makes checkmate impossible, the capturing side wins."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

private data class ClockChoice(val label: String, val control: TimeControl)

private val ClockChoices = listOf(
    ClockChoice("Untimed", TimeControl.Untimed),
    ClockChoice("3 min", TimeControl.Clock(3 * 60_000L)),
    ClockChoice("5 min", TimeControl.Clock(5 * 60_000L)),
    ClockChoice("10 min", TimeControl.Clock(10 * 60_000L)),
    ClockChoice("15+10", TimeControl.Clock(15 * 60_000L, 10_000L)),
)

@Composable
private fun SetupSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ChoiceRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}
