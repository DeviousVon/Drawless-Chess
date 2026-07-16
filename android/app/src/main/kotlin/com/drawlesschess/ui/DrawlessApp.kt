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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
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
import com.drawlesschess.R
import java.math.RoundingMode
import java.text.NumberFormat

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
                stringResource(R.string.app_name),
                color = home.title,
                fontSize = 38.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.brand_tagline),
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
                        Text(stringResource(R.string.home_resume_game), fontSize = 17.sp)
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
                        Text(stringResource(R.string.home_checking_saved_game), fontSize = 17.sp)
                    }
                }
                ResumeState.Empty -> Unit
                is ResumeState.Failed -> {
                    Text(
                        resumeState.message.resolve(),
                        color = visualTheme.darkColors.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onDiscard, colors = textButtonColors) {
                        Text(stringResource(R.string.home_discard_saved_game))
                    }
                }
            }
            if (resumeState != ResumeState.Loading) {
                Button(
                    onClick = onQuickPlay,
                    enabled = resumeState !is ResumeState.Failed,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("home_quick_play"),
                    shape = RoundedCornerShape(18.dp),
                    colors = primaryButtonColors,
                ) {
                    Text(stringResource(R.string.home_quick_play), fontSize = 17.sp)
                }
                Text(
                    stringResource(
                        R.string.home_quick_play_summary,
                        opponentName(quickPlayOpponent),
                        botLevelName(quickPlayOpponent.level),
                    ),
                    color = home.muted,
                    fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = onCustomGame,
                    enabled = resumeState !is ResumeState.Failed,
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("home_custom_game"),
                    shape = RoundedCornerShape(18.dp),
                    colors = outlinedButtonColors,
                    border = BorderStroke(1.dp, home.accent.copy(alpha = 0.72f)),
                ) {
                    Text(stringResource(R.string.home_custom_game), fontSize = 16.sp)
                }
                OutlinedButton(
                    onClick = onShowThemes,
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("home_theme"),
                    shape = RoundedCornerShape(18.dp),
                    colors = outlinedButtonColors,
                    border = BorderStroke(1.dp, home.accent.copy(alpha = 0.72f)),
                ) {
                    Text(stringResource(R.string.home_theme, themeName(selectedTheme.id)), fontSize = 15.sp)
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
                        Text(stringResource(R.string.home_player_stats), fontSize = 15.sp)
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
                    Text(stringResource(R.string.home_options), fontSize = 15.sp)
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onShowRules, colors = textButtonColors) {
                    Text(stringResource(R.string.home_how_drawless_works))
                }
                TextButton(onClick = { showLicense = true }, colors = textButtonColors) {
                    Text(stringResource(R.string.home_open_source_license))
                }
                TextButton(onClick = { showPrivacy = true }, colors = textButtonColors) {
                    Text(stringResource(R.string.home_privacy))
                }
            }
            Text(
                stringResource(R.string.brand_features),
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
        title = { Text(stringResource(R.string.forfeit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.forfeit_body),
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
                        Text(stringResource(R.string.forfeit_recording))
                    }
                }
                state.errorMessage?.let { message ->
                    Text(message.resolve(), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.recordingLoss,
                modifier = Modifier.testTag("confirm_forfeit_game"),
            ) {
                Text(stringResource(R.string.forfeit_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !state.recordingLoss,
                modifier = Modifier.testTag("cancel_forfeit_game"),
            ) {
                Text(stringResource(R.string.forfeit_cancel))
            }
        },
    )
}

@Composable
private fun homeStatsSummary(state: PlayerStatsState): String = when (state) {
    PlayerStatsState.Loading -> stringResource(R.string.home_stats_loading)
    is PlayerStatsState.Failed -> stringResource(R.string.home_stats_unavailable)
    is PlayerStatsState.Ready -> {
        val stats = state.statistics
        if (stats.completedGames == 0) {
            stringResource(R.string.home_stats_empty)
        } else {
            val winPercentage = stats.winPercentage
                ?.let { oneDecimal(it) }
                ?: "—"
            val averageScore = stats.averageScore
                ?.let { oneDecimal(it) }
                ?: "—"
            stringResource(R.string.home_stats_summary, stats.wins, stats.losses, winPercentage, averageScore)
        }
    }
}

@Composable
private fun RulesGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_guide_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.rules_checkmate))
                Text(stringResource(R.string.rules_stalemate))
                Text(stringResource(R.string.rules_repetition))
                Text(stringResource(R.string.rules_dead_position))
                Text(stringResource(R.string.rules_fifty_move))
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.testTag("rules_guide_dismiss")) {
                Text(stringResource(R.string.action_got_it))
            }
        },
    )
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.license_title)) },
        text = {
            Text(stringResource(R.string.license_body))
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            TextButton(
                onClick = {
                    uriHandler.openUri(
                        "https://github.com/DeviousVon/Drawless-Chess/releases/tag/v0.2.0",
                    )
                },
            ) {
                Text(stringResource(R.string.license_view_source))
            }
        },
    )
}

@Composable
private fun PrivacyDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacy_title)) },
        text = {
            Text(stringResource(R.string.privacy_body))
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            TextButton(
                onClick = {
                    uriHandler.openUri(
                        "https://github.com/DeviousVon/Drawless-Chess/blob/main/PRIVACY.md",
                    )
                },
            ) {
                Text(stringResource(R.string.privacy_view_policy))
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
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("setup_back")) {
                        Text(stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp) {
                Button(
                    onClick = { onStart(selection) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp).testTag("setup_start_game"),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(stringResource(R.string.setup_start_game)) }
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
            SetupSection(stringResource(R.string.setup_clock)) {
                ChoiceRow {
                    ClockChoices.forEach { choice ->
                        ChoiceChip(clockLabel(choice.control), selection.timeControl == choice.control) {
                            onSelectionChanged(selection.copy(timeControl = choice.control))
                        }
                    }
                }
            }

            SetupSection(stringResource(R.string.setup_play_as)) {
                ChoiceRow {
                    ChoiceChip(
                        stringResource(R.string.label_random),
                        selection.startingColor == StartingColor.RANDOM,
                        Modifier.testTag("play_as_random"),
                    ) {
                        onSelectionChanged(selection.copy(startingColor = StartingColor.RANDOM))
                    }
                    ChoiceChip(
                        stringResource(R.string.label_white),
                        selection.startingColor == StartingColor.WHITE,
                        Modifier.testTag("play_as_white"),
                    ) {
                        onSelectionChanged(selection.copy(startingColor = StartingColor.WHITE))
                    }
                    ChoiceChip(
                        stringResource(R.string.label_black),
                        selection.startingColor == StartingColor.BLACK,
                        Modifier.testTag("play_as_black"),
                    ) {
                        onSelectionChanged(selection.copy(startingColor = StartingColor.BLACK))
                    }
                }
                Text(
                    if (selection.startingColor == StartingColor.RANDOM) {
                        stringResource(R.string.setup_random_side_explanation)
                    } else {
                        stringResource(
                            R.string.setup_side_explanation,
                            stringResource(
                                if (selection.startingColor == StartingColor.WHITE) R.string.label_white
                                else R.string.label_black,
                            ),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SetupSection(stringResource(R.string.setup_opponent)) {
                OpponentPicker(
                    selectedLevel = selection.botLevel,
                    onSelected = { level -> onSelectionChanged(selection.copy(botLevel = level)) },
                )
            }

            SetupSection(stringResource(R.string.setup_advanced_rules)) {
                Text(
                    stringResource(R.string.setup_advanced_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Text(stringResource(if (advancedExpanded) R.string.setup_hide_options else R.string.setup_show_options))
                }
                if (advancedExpanded) {
                    Text(stringResource(R.string.setup_stalemate), fontWeight = FontWeight.SemiBold)
                    ChoiceRow {
                        ChoiceChip(stringResource(R.string.setup_drawless), selection.preset == RulesContractV1.Preset.DRAWLESS) {
                            onSelectionChanged(selection.copy(preset = RulesContractV1.Preset.DRAWLESS))
                        }
                        ChoiceChip(stringResource(R.string.setup_escape), selection.preset == RulesContractV1.Preset.ESCAPE) {
                            onSelectionChanged(selection.copy(preset = RulesContractV1.Preset.ESCAPE))
                        }
                    }
                    Text(
                        if (selection.preset == RulesContractV1.Preset.DRAWLESS) {
                            stringResource(R.string.setup_drawless_description)
                        } else {
                            stringResource(R.string.setup_escape_description)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.setup_impossible_checkmate), fontWeight = FontWeight.SemiBold)
                    ChoiceRow {
                        ChoiceChip(stringResource(R.string.setup_material_wins), selection.deadPosition == DeadPositionPolicy.MATERIAL_VICTORY) {
                            onSelectionChanged(selection.copy(deadPosition = DeadPositionPolicy.MATERIAL_VICTORY))
                        }
                        ChoiceChip(stringResource(R.string.setup_final_capture), selection.deadPosition == DeadPositionPolicy.FINAL_CAPTURE_VICTORY) {
                            onSelectionChanged(selection.copy(deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY))
                        }
                    }
                    Text(
                        if (selection.deadPosition == DeadPositionPolicy.MATERIAL_VICTORY) {
                            stringResource(R.string.setup_material_wins_description)
                        } else {
                            stringResource(R.string.setup_final_capture_description)
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
    val selectionDescription = stringResource(
        if (selected) R.string.label_selected else R.string.label_not_selected,
    )
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
                stateDescription = selectionDescription
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
                opponentName(profile),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                botLevelName(profile.level),
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
                    opponentName(profile),
                    modifier = Modifier.testTag("selected_opponent_name"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.setup_opponent_level, botLevelName(profile.level)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(profile.epithetRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(stringResource(profile.personalityRes), style = MaterialTheme.typography.bodyMedium)
                Text(
                    botLevelDescription(profile.level),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class ClockChoice(val control: TimeControl)

private val ClockChoices = listOf(
    ClockChoice(TimeControl.Untimed),
    ClockChoice(TimeControl.Clock(3 * 60_000L)),
    ClockChoice(TimeControl.Clock(5 * 60_000L)),
    ClockChoice(TimeControl.Clock(10 * 60_000L)),
    ClockChoice(TimeControl.Clock(15 * 60_000L, 10_000L)),
)

@Composable
private fun clockLabel(control: TimeControl): String = when (control) {
    TimeControl.Untimed -> stringResource(R.string.clock_untimed)
    is TimeControl.Clock -> if (control.incrementMillis == 10_000L) {
        stringResource(R.string.clock_fifteen_ten)
    } else {
        stringResource(R.string.clock_minutes, (control.initialMillis / 60_000L).toInt())
    }
}

@Composable
internal fun oneDecimal(value: Double): String {
    val locale = LocalConfiguration.current.locales[0]
    return NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        roundingMode = RoundingMode.HALF_UP
    }.format(value)
}

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
