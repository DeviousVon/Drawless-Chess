package com.drawlesschess.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.Side
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.NamedBotLevel
import com.drawlesschess.core.presentation.BoardTheme
import com.drawlesschess.core.presentation.BoardThemes
import com.drawlesschess.persistence.PlayerStatistics
import com.drawlesschess.persistence.RoomCheckpointStore
import com.drawlesschess.R

internal enum class AppRoute {
    HOME,
    SETUP,
    OPTIONS,
    STATS,
    GAME,
    REVIEW,
}

internal sealed interface ResumeState {
    data object Loading : ResumeState
    data object Empty : ResumeState
    data class Ready(val checkpoint: CoordinatorCheckpoint) : ResumeState
    data class Failed(val message: UiText) : ResumeState
}

internal sealed interface PlayerStatsState {
    data object Loading : PlayerStatsState
    data class Ready(val statistics: PlayerStatistics) : PlayerStatsState
    data class Failed(val message: UiText) : PlayerStatsState
}

internal data class ResolvedGameSetup(
    val humanSide: Side,
    val rematchSelection: SetupSelection,
)

internal data class ForfeitConfirmationState(
    val selection: SetupSelection,
    val expectedGameId: String,
    val recordingLoss: Boolean = false,
    val errorMessage: UiText? = null,
)

internal fun SetupSelection.resolveForNewGame(randomBoolean: () -> Boolean): ResolvedGameSetup {
    val resolvedHumanSide = startingColor.resolve(randomBoolean)
    return ResolvedGameSetup(
        humanSide = resolvedHumanSide,
        rematchSelection = copy(startingColor = StartingColor.fromResolvedSide(resolvedHumanSide)),
    )
}

internal fun quickPlaySetup(botLevel: NamedBotLevel): SetupSelection = SetupSelection(
    startingColor = StartingColor.RANDOM,
    botLevel = botLevel,
)

internal class DrawlessAppViewModel(
    applicationContext: Context,
    private val checkpointStore: RoomCheckpointStore,
    private val randomBoolean: () -> Boolean = { kotlin.random.Random.nextBoolean() },
) : ViewModel() {
    private val applicationContext = applicationContext.applicationContext
    private val preferences = this.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val themePreferences = ThemePreferenceStore(this.applicationContext)
    private val gamePreferenceStore = GamePreferenceStore(this.applicationContext)
    private val quickPlayPreferences = QuickPlayPreferenceStore(this.applicationContext)
    private var activeSelection: SetupSelection? = null
    private var postGameReviewHandledGameId: String? by mutableStateOf(null)

    var route: AppRoute by mutableStateOf(AppRoute.HOME)
        private set

    var resumeState: ResumeState by mutableStateOf(ResumeState.Loading)
        private set

    var playerStatsState: PlayerStatsState by mutableStateOf(PlayerStatsState.Loading)
        private set
    private var lastAdaptiveRating: Int = BotDifficultyCatalog.ADAPTIVE_STARTING_ELO
    private var adaptiveLaunchPending = false
    private var launchRequestGeneration = 0L

    var runtime: GameRuntime? by mutableStateOf(null)
        private set

    var quickPlayOpponentLevel: NamedBotLevel by mutableStateOf(quickPlayPreferences.load())
        private set

    var setupSelection: SetupSelection by mutableStateOf(
        SetupSelection(botLevel = quickPlayOpponentLevel),
    )
        private set

    var forfeitConfirmation: ForfeitConfirmationState? by mutableStateOf(null)
        private set

    var selectedTheme: BoardTheme by mutableStateOf(themePreferences.load())
        private set

    var gamePreferences: GamePreferences by mutableStateOf(gamePreferenceStore.load())
        private set

    var showRulesGuide: Boolean by mutableStateOf(
        !preferences.getBoolean(RULES_GUIDE_SEEN, false),
    )
        private set

    init {
        refreshResume()
        refreshPlayerStats()
    }

    fun showNewGameSetup() {
        if (resumeState is ResumeState.Empty || resumeState is ResumeState.Ready) {
            setupSelection = SetupSelection(botLevel = quickPlayOpponentLevel)
            route = AppRoute.SETUP
        }
    }

    fun startQuickPlay() {
        startNewGame(quickPlaySelection())
    }

    fun showOptions() {
        if (resumeState != ResumeState.Loading) route = AppRoute.OPTIONS
    }

    fun leaveOptions() {
        route = AppRoute.HOME
    }

    fun showStats() {
        route = AppRoute.STATS
    }

    fun leaveStats() {
        route = AppRoute.HOME
    }

    fun completedGameRecorded() {
        playerStatsState = PlayerStatsState.Loading
        refreshPlayerStats()
    }

    fun updateGamePreferences(value: GamePreferences) {
        if (gamePreferences == value) return
        gamePreferences = value
        gamePreferenceStore.save(value)
    }

    fun showRulesGuide() {
        showRulesGuide = true
    }

    fun dismissRulesGuide() {
        showRulesGuide = false
        preferences.edit().putBoolean(RULES_GUIDE_SEEN, true).apply()
    }

    fun updateSetupSelection(selection: SetupSelection) {
        setupSelection = selection
        rememberQuickPlayOpponent(selection.botLevel)
    }

    fun selectQuickPlayOpponent(level: NamedBotLevel) {
        rememberQuickPlayOpponent(level)
        setupSelection = setupSelection.copy(botLevel = quickPlayOpponentLevel)
    }

    fun selectTheme(theme: BoardTheme) {
        val supported = BoardThemes.fromId(theme.id)
        if (selectedTheme == supported) return
        selectedTheme = supported
        themePreferences.save(supported)
    }

    fun leaveSetup() {
        cancelPendingAdaptiveLaunch()
        route = AppRoute.HOME
    }

    fun startNewGame(selection: SetupSelection) {
        rememberQuickPlayOpponent(selection.botLevel)
        when (val state = resumeState) {
            is ResumeState.Ready -> {
                forfeitConfirmation = ForfeitConfirmationState(
                    selection = selection,
                    expectedGameId = state.checkpoint.config.gameId,
                )
                return
            }
            ResumeState.Empty -> launchNewGame(selection)
            ResumeState.Loading,
            is ResumeState.Failed -> Unit
        }
    }

    fun cancelForfeitAndKeepGame() {
        if (forfeitConfirmation?.recordingLoss == true) return
        forfeitConfirmation = null
    }

    fun confirmForfeitAndStartNewGame() {
        val pending = forfeitConfirmation ?: return
        if (pending.recordingLoss) return
        forfeitConfirmation = pending.copy(recordingLoss = true, errorMessage = null)
        checkpointStore.forfeitResumable(pending.expectedGameId) { result ->
            result.fold(
                onSuccess = { lossRecorded ->
                    if (!lossRecorded) {
                        // Fail closed even if a future storage implementation accidentally
                        // returns an unrecorded success result.
                        forfeitConfirmation = pending.copy(
                            errorMessage = uiText(R.string.error_loss_not_recorded),
                        )
                    } else {
                        // The storage callback runs only after the terminal checkpoint and history
                        // row have committed. A replacement runtime can now safely claim authority.
                        forfeitConfirmation = null
                        resumeState = ResumeState.Empty
                        playerStatsState = PlayerStatsState.Loading
                        refreshPlayerStats(
                            afterSuccess = { launchNewGameResolved(pending.selection) },
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(LOG_TAG, "Could not record forfeited game", error)
                    forfeitConfirmation = pending.copy(
                        errorMessage = uiText(R.string.error_loss_not_recorded),
                    )
                },
            )
        }
    }

    private fun launchNewGame(selection: SetupSelection) {
        if (selection.botLevel.id == BotDifficultyCatalog.ADAPTIVE_LEVEL_ID) {
            if (adaptiveLaunchPending) return
            adaptiveLaunchPending = true
            val requestGeneration = ++launchRequestGeneration
            playerStatsState = PlayerStatsState.Loading
            // The FIFO store reads after any already-enqueued terminal write, so even an
            // immediate rematch freezes Vesper at the newly committed rating.
            refreshPlayerStats(
                afterSuccess = {
                    if (requestGeneration != launchRequestGeneration) return@refreshPlayerStats
                    adaptiveLaunchPending = false
                    launchNewGameResolved(selection)
                },
                afterFailure = {
                    if (requestGeneration == launchRequestGeneration) adaptiveLaunchPending = false
                },
            )
            return
        }
        launchRequestGeneration++
        adaptiveLaunchPending = false
        launchNewGameResolved(selection)
    }

    private fun launchNewGameResolved(selection: SetupSelection) {
        val casualSelection = selection.copy(mode = GameMode.CASUAL)
        val resolvedSetup = casualSelection.resolveForNewGame(randomBoolean)
        replaceRuntime {
            GameRuntime(
                casualSelection,
                applicationContext,
                checkpointStore.activateNewGame(),
                initialTheme = selectedTheme,
                resolvedHumanSide = resolvedSetup.humanSide,
                threatIndicationEnabled = gamePreferences.threatIndicationEnabled,
                adaptiveElo = currentAdaptiveRating(),
            )
                .also { activeSelection = resolvedSetup.rematchSelection }
        }
    }

    private fun rememberQuickPlayOpponent(level: NamedBotLevel) {
        val supported = when (level.id) {
            BotDifficultyCatalog.ADAPTIVE_LEVEL_ID -> BotDifficultyCatalog.adaptiveLevel()
            else -> BotDifficultyCatalog.namedOrNull(level.id) ?: return
        }
        if (quickPlayOpponentLevel == supported) return
        quickPlayOpponentLevel = supported
        quickPlayPreferences.save(supported)
    }

    private fun currentAdaptiveRating(): Int =
        lastAdaptiveRating

    fun rematchGame() {
        // Rematch is offered only after the active runtime has already persisted a terminal
        // result, so it does not pass through the unfinished-game replacement flow.
        launchNewGame(activeSelection ?: SetupSelection())
    }

    fun postGameQuickPlay() {
        // Like rematch, this action is offered only for a completed game and launches directly.
        // Starting from a fresh RANDOM selection deliberately draws the player's side again.
        launchNewGame(quickPlaySelection())
    }

    fun isPostGameReviewPending(gameId: String): Boolean =
        runtime?.gameId == gameId && postGameReviewHandledGameId != gameId

    fun enterPostGameReview(expectedGameId: String) {
        val activeRuntime = runtime ?: return
        if (activeRuntime.gameId != expectedGameId ||
            activeRuntime.controller.model().result == null ||
            postGameReviewHandledGameId == activeRuntime.gameId
        ) {
            return
        }
        postGameReviewHandledGameId = activeRuntime.gameId
        route = AppRoute.REVIEW
    }

    private fun quickPlaySelection(): SetupSelection = quickPlaySetup(quickPlayOpponentLevel)

    fun resumeGame() {
        if (resumeState !is ResumeState.Ready) return
        resumeState = ResumeState.Loading
        checkpointStore.loadResumable { result ->
            result.fold(
                onSuccess = { checkpoint ->
                    if (checkpoint == null) {
                        resumeState = ResumeState.Empty
                    } else {
                        val resumedSelection = checkpoint.config.toSetupSelection()
                        replaceRuntime {
                            GameRuntime(
                                checkpoint,
                                applicationContext,
                                checkpointStore.activateResume(),
                                initialTheme = selectedTheme,
                            ).also { activeSelection = resumedSelection }
                        }
                    }
                },
                onFailure = { showResumeFailure(it) },
            )
        }
    }

    fun exitGame() {
        cancelPendingAdaptiveLaunch()
        val previous = runtime
        runtime = null
        postGameReviewHandledGameId = null
        previous?.close()
        route = AppRoute.HOME
        playerStatsState = PlayerStatsState.Loading
        refreshResume()
        refreshPlayerStats()
    }

    fun discardSavedGame() {
        if (resumeState !is ResumeState.Failed) return
        resumeState = ResumeState.Loading
        checkpointStore.discard { result ->
            result.fold(
                onSuccess = { resumeState = ResumeState.Empty },
                onFailure = { showResumeFailure(it) },
            )
        }
    }

    override fun onCleared() {
        cancelPendingAdaptiveLaunch()
        runtime?.close()
        runtime = null
    }

    private fun cancelPendingAdaptiveLaunch() {
        launchRequestGeneration++
        adaptiveLaunchPending = false
    }

    private fun refreshResume() {
        resumeState = ResumeState.Loading
        checkpointStore.loadResumable { result ->
            result.fold(
                onSuccess = { checkpoint ->
                    resumeState = checkpoint?.let(ResumeState::Ready) ?: ResumeState.Empty
                },
                onFailure = { showResumeFailure(it) },
            )
        }
    }

    private fun refreshPlayerStats(
        afterSuccess: () -> Unit = {},
        afterFailure: () -> Unit = {},
    ) {
        checkpointStore.loadPlayerStats { result ->
            result.fold(
                onSuccess = { statistics ->
                    lastAdaptiveRating = statistics.adaptiveRating
                    playerStatsState = PlayerStatsState.Ready(statistics)
                    afterSuccess()
                },
                onFailure = { error ->
                    Log.e(LOG_TAG, "Could not load player statistics", error)
                    playerStatsState = PlayerStatsState.Failed(uiText(R.string.error_stats_not_loaded))
                    afterFailure()
                },
            )
        }
    }

    private fun replaceRuntime(create: () -> GameRuntime) {
        val previous = runtime
        runtime = null
        postGameReviewHandledGameId = null
        previous?.close()
        try {
            runtime = create()
            route = AppRoute.GAME
        } catch (error: Throwable) {
            route = AppRoute.HOME
            showResumeFailure(error)
        }
    }

    private fun showResumeFailure(error: Throwable) {
        Log.e(LOG_TAG, "Could not resume saved game", error)
        resumeState = ResumeState.Failed(uiText(R.string.error_saved_game_not_resumed))
    }

    companion object {
        private const val LOG_TAG = "DrawlessChessApp"
        private const val PREFERENCES_NAME = "drawless-onboarding"
        private const val RULES_GUIDE_SEEN = "rules-guide-seen-v1"

        fun factory(
            applicationContext: Context,
            checkpointStore: RoomCheckpointStore,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(DrawlessAppViewModel::class.java))
                return DrawlessAppViewModel(applicationContext, checkpointStore) as T
            }
        }
    }
}

internal fun GameConfig.toSetupSelection(): SetupSelection {
    val botLevel = if (opponentLevelId == BotDifficultyCatalog.ADAPTIVE_LEVEL_ID) {
        BotDifficultyCatalog.adaptiveLevel(
            (engineStrength as? EngineStrength.ApproximateElo)?.elo
                ?: BotDifficultyCatalog.ADAPTIVE_STARTING_ELO,
        )
    } else {
        BotDifficultyCatalog.displayLevel(opponentLevelId, engineStrength)
    }
    return SetupSelection(
        preset = rules.preset,
        deadPosition = rules.deadPosition,
        fiftyMove = rules.fiftyMove,
        mode = GameMode.CASUAL,
        timeControl = timeControl,
        startingColor = StartingColor.fromResolvedSide(humanSide),
        botLevel = botLevel,
    )
}
