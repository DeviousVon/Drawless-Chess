@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.drawlesschess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drawlesschess.core.*
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.presentation.BoardTheme
import java.util.Locale

@Composable
internal fun DrawlessApp(viewModel: DrawlessAppViewModel, soundPlayer: GameSoundPlayer) {
    var showThemePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(soundPlayer, viewModel.gamePreferences.soundEnabled) {
        soundPlayer.setEnabled(viewModel.gamePreferences.soundEnabled)
    }

    when (viewModel.route) {
        AppRoute.HOME -> HomeScreen(
            resumeState = viewModel.resumeState,
            playerStatsState = viewModel.playerStatsState,
            selectedTheme = viewModel.selectedTheme,
            onResume = viewModel::resumeGame,
            onQuickPlay = viewModel::startQuickPlay,
            onCustomGame = viewModel::showNewGameSetup,
            onShowOptions = viewModel::showOptions,
            onShowStats = viewModel::showStats,
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
        AppRoute.OPTIONS -> {
            BackHandler(onBack = viewModel::leaveOptions)
            OptionsScreen(
                preferences = viewModel.gamePreferences,
                onPreferencesChanged = viewModel::updateGamePreferences,
                onBack = viewModel::leaveOptions,
            )
        }
        AppRoute.STATS -> {
            BackHandler(onBack = viewModel::leaveStats)
            PlayerStatsScreen(
                state = viewModel.playerStatsState,
                onBack = viewModel::leaveStats,
                onRetry = viewModel::completedGameRecorded,
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
                    preferences = viewModel.gamePreferences,
                    playerStatistics = (viewModel.playerStatsState as? PlayerStatsState.Ready)
                        ?.statistics,
                    selectedTheme = viewModel.selectedTheme,
                    onShowThemes = { showThemePicker = true },
                    onExit = viewModel::exitGame,
                    onRematch = viewModel::rematchGame,
                    onGameCompleted = viewModel::completedGameRecorded,
                )
            }
        }
    }

    if (viewModel.route == AppRoute.HOME && viewModel.showRulesGuide) {
        RulesGuideDialog(onDismiss = viewModel::dismissRulesGuide)
    }

    viewModel.forfeitConfirmation?.let { state ->
        ForfeitCurrentGameDialog(
            state = state,
            onConfirm = viewModel::confirmForfeitAndStartNewGame,
            onCancel = viewModel::cancelForfeitAndKeepGame,
        )
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
    playerStatsState: PlayerStatsState,
    selectedTheme: BoardTheme,
    onResume: () -> Unit,
    onQuickPlay: () -> Unit,
    onCustomGame: () -> Unit,
    onShowOptions: () -> Unit,
    onShowStats: () -> Unit,
    onShowThemes: () -> Unit,
    onShowRules: () -> Unit,
    onDiscard: () -> Unit,
) {
    var showLicense by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    val quickPlayOpponent = OpponentProfiles.quickPlay
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
                    enabled = resumeState !is ResumeState.Failed,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = primaryButtonColors,
                ) {
                    Text("Quick Play", fontSize = 17.sp)
                }
                Text(
                    "Drawless · ${quickPlayOpponent.name}, ${quickPlayOpponent.level.displayName} · Random side · Untimed",
                    color = home.muted,
                    fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = onCustomGame,
                    enabled = resumeState !is ResumeState.Failed,
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
                OutlinedButton(
                    onClick = onShowStats,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp)
                        .testTag("home_stats"),
                    shape = RoundedCornerShape(18.dp),
                    colors = outlinedButtonColors,
                    border = BorderStroke(1.dp, home.accent.copy(alpha = 0.72f)),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Player stats", fontSize = 15.sp)
                        Text(
                            homeStatsSummary(playerStatsState),
                            fontSize = 12.sp,
                            color = home.muted,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onShowOptions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("home_options"),
                    shape = RoundedCornerShape(18.dp),
                    colors = outlinedButtonColors,
                    border = BorderStroke(1.dp, home.accent.copy(alpha = 0.72f)),
                ) {
                    Text("Options", fontSize = 15.sp)
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
                TextButton(onClick = { showPrivacy = true }, colors = textButtonColors) {
                    Text("Privacy")
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
    if (showPrivacy) {
        PrivacyDialog(onDismiss = { showPrivacy = false })
    }
}

@Composable
internal fun ForfeitCurrentGameDialog(
    state: ForfeitConfirmationState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.recordingLoss) onCancel() },
        title = { Text("Forfeit current game?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Are you sure you want to forfeit your current game? " +
                        "It will count as a loss in your stats.",
                )
                if (state.recordingLoss) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Recording loss…")
                    }
                }
                state.errorMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.recordingLoss,
                modifier = Modifier.testTag("confirm_forfeit_game"),
            ) {
                Text("Forfeit & start new game")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !state.recordingLoss,
                modifier = Modifier.testTag("cancel_forfeit_game"),
            ) {
                Text("Keep current game")
            }
        },
    )
}

private fun homeStatsSummary(state: PlayerStatsState): String = when (state) {
    PlayerStatsState.Loading -> "Loading…"
    is PlayerStatsState.Failed -> "Unavailable"
    is PlayerStatsState.Ready -> {
        val stats = state.statistics
        if (stats.completedGames == 0) {
            "No completed games yet"
        } else {
            val winPercentage = stats.winPercentage
                ?.let { String.format(Locale.US, "%.1f", it) }
                ?: "—"
            val averageScore = stats.averageScore
                ?.let { String.format(Locale.US, "%.1f", it) }
                ?: "—"
            "${stats.wins}–${stats.losses} · $winPercentage% wins · Avg $averageScore"
        }
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
        title = { Text("Open-source software") },
        text = {
            Text(
                "Drawless Chess is licensed under GNU GPL version 3 or later. " +
                    "Sampled audio includes CC0 chess recordings by JJTaynos and mh2o, " +
                    "CC0 fireworks by Rudmer_Rotteveel, and MIT-licensed ion.sound " +
                    "recordings by Denis Ineshin. " +
                    "Exact corresponding source and third-party notices accompany " +
                    "the official v0.1.0 release.",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(
                onClick = {
                    uriHandler.openUri(
                        "https://github.com/DeviousVon/Drawless-Chess/releases/tag/v0.1.0",
                    )
                },
            ) {
                Text("View source")
            }
        },
    )
}

@Composable
private fun PrivacyDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy") },
        text = {
            Text(
                "Drawless Chess works entirely offline. BB_Games does not collect, " +
                "transmit, share, or sell personal data. Saved games, completed-game " +
                    "history, local statistics, and settings are stored on your device and " +
                    "may be included in Android backups if " +
                    "you enable them; BB_Games cannot access those backups. " +
                    "Privacy questions: realitymaster@protonmail.ch",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(
                onClick = {
                    uriHandler.openUri(
                        "https://github.com/DeviousVon/Drawless-Chess/blob/main/PRIVACY.md",
                    )
                },
            ) {
                Text("View policy")
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
                    ChoiceChip(
                        "Random",
                        selection.startingColor == StartingColor.RANDOM,
                        Modifier.testTag("play_as_random"),
                    ) {
                        onSelectionChanged(selection.copy(startingColor = StartingColor.RANDOM))
                    }
                    ChoiceChip(
                        "White",
                        selection.startingColor == StartingColor.WHITE,
                        Modifier.testTag("play_as_white"),
                    ) {
                        onSelectionChanged(selection.copy(startingColor = StartingColor.WHITE))
                    }
                    ChoiceChip(
                        "Black",
                        selection.startingColor == StartingColor.BLACK,
                        Modifier.testTag("play_as_black"),
                    ) {
                        onSelectionChanged(selection.copy(startingColor = StartingColor.BLACK))
                    }
                }
                Text(
                    if (selection.startingColor == StartingColor.RANDOM) {
                        "White or Black will be chosen when the game starts."
                    } else {
                        "You'll play ${selection.startingColor.name.lowercase().replaceFirstChar(Char::uppercase)}."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SetupSection("Opponent") {
                OpponentPicker(
                    selectedLevel = selection.botLevel,
                    onSelected = { level -> onSelectionChanged(selection.copy(botLevel = level)) },
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

@Composable
private fun OpponentPicker(
    selectedLevel: com.drawlesschess.core.engine.NamedBotLevel,
    onSelected: (com.drawlesschess.core.engine.NamedBotLevel) -> Unit,
) {
    val selectedProfile = OpponentProfiles.forLevel(selectedLevel)
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("opponent_picker"),
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(OpponentProfiles.all, key = { it.level.id }) { profile ->
            OpponentChoiceCard(
                profile = profile,
                selected = profile.level.id == selectedLevel.id,
                onClick = { onSelected(profile.level) },
            )
        }
    }
    OpponentDetailCard(selectedProfile)
}

@Composable
private fun OpponentChoiceCard(
    profile: OpponentProfile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

    Surface(
        modifier = Modifier
            .width(116.dp)
            .testTag("opponent_option_${profile.level.id}")
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                stateDescription = if (selected) "Selected" else "Not selected"
            },
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        tonalElevation = if (selected) 5.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            OpponentPortrait(
                profile = profile,
                size = 74.dp,
                emphasized = selected,
                modifier = Modifier.testTag("opponent_avatar_${profile.level.id}"),
            )
            Text(
                profile.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                profile.level.displayName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OpponentDetailCard(profile: OpponentProfile) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("selected_opponent_${profile.level.id}"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OpponentPortrait(profile = profile, size = 92.dp, emphasized = true)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    profile.name,
                    modifier = Modifier.testTag("selected_opponent_name"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${profile.level.displayName} opponent",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    profile.epithet,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(profile.personality, style = MaterialTheme.typography.bodyMedium)
                Text(
                    profile.level.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
    )
}
