package com.drawlesschess.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.persistence.RoomCheckpointStore

internal enum class AppRoute {
    HOME,
    SETUP,
    GAME,
}

internal sealed interface ResumeState {
    data object Loading : ResumeState
    data object Empty : ResumeState
    data class Ready(val checkpoint: CoordinatorCheckpoint) : ResumeState
    data class Failed(val message: String) : ResumeState
}

internal class DrawlessAppViewModel(
    applicationContext: Context,
    private val checkpointStore: RoomCheckpointStore,
) : ViewModel() {
    private val applicationContext = applicationContext.applicationContext
    private val preferences = this.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private var activeSelection: SetupSelection? = null

    var route: AppRoute by mutableStateOf(AppRoute.HOME)
        private set

    var resumeState: ResumeState by mutableStateOf(ResumeState.Loading)
        private set

    var runtime: GameRuntime? by mutableStateOf(null)
        private set

    var setupSelection: SetupSelection by mutableStateOf(SetupSelection())
        private set

    var showRulesGuide: Boolean by mutableStateOf(
        !preferences.getBoolean(RULES_GUIDE_SEEN, false),
    )
        private set

    init {
        refreshResume()
    }

    fun showNewGameSetup() {
        if (resumeState != ResumeState.Loading) {
            setupSelection = SetupSelection()
            route = AppRoute.SETUP
        }
    }

    fun startQuickPlay() {
        startNewGame(SetupSelection())
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
    }

    fun leaveSetup() {
        route = AppRoute.HOME
    }

    fun startNewGame(selection: SetupSelection) {
        val casualSelection = selection.copy(mode = GameMode.CASUAL)
        replaceRuntime {
            GameRuntime(casualSelection, applicationContext, checkpointStore.activateNewGame())
                .also { activeSelection = casualSelection }
        }
    }

    fun rematchGame() {
        startNewGame(activeSelection ?: SetupSelection())
    }

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
                            ).also { activeSelection = resumedSelection }
                        }
                    }
                },
                onFailure = { showResumeFailure(it) },
            )
        }
    }

    fun exitGame() {
        val previous = runtime
        runtime = null
        previous?.close()
        route = AppRoute.HOME
        refreshResume()
    }

    fun discardSavedGame() {
        resumeState = ResumeState.Loading
        checkpointStore.discard { result ->
            result.fold(
                onSuccess = { resumeState = ResumeState.Empty },
                onFailure = { showResumeFailure(it) },
            )
        }
    }

    override fun onCleared() {
        runtime?.close()
        runtime = null
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

    private fun replaceRuntime(create: () -> GameRuntime) {
        val previous = runtime
        runtime = null
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
        val detail = error.message?.takeIf(String::isNotBlank)
            ?: error::class.simpleName
            ?: "unknown error"
        resumeState = ResumeState.Failed("Saved game can't be resumed: $detail")
    }

    companion object {
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

private fun GameConfig.toSetupSelection(): SetupSelection {
    val botLevel = when (val strength = engineStrength) {
        is EngineStrength.ApproximateElo -> BotDifficultyCatalog.nearest(strength.elo)
        is EngineStrength.SkillLevel -> BotDifficultyCatalog.named("casual")
    }
    return SetupSelection(
        preset = rules.preset,
        deadPosition = rules.deadPosition,
        fiftyMove = rules.fiftyMove,
        mode = GameMode.CASUAL,
        timeControl = timeControl,
        humanSide = humanSide,
        botLevel = botLevel,
    )
}
