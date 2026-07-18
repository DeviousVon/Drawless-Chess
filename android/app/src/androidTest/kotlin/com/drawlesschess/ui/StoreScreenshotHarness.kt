@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.drawlesschess.ui

import android.content.res.Configuration
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.text.TextUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.R
import com.drawlesschess.core.AssistanceCounts
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.GameScore
import com.drawlesschess.core.GameSession
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.TimeControl
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import com.drawlesschess.core.coordinator.ClockSnapshot
import com.drawlesschess.core.coordinator.CoordinatorPhase
import com.drawlesschess.core.coordinator.CoordinatorSnapshot
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.presentation.BoardInteractionState
import com.drawlesschess.core.presentation.BoardPresenter
import com.drawlesschess.core.presentation.BoardTheme
import com.drawlesschess.core.presentation.BoardThemes
import com.drawlesschess.core.presentation.ClockView
import com.drawlesschess.core.presentation.ControlPlacement
import com.drawlesschess.core.presentation.GameControlsView
import com.drawlesschess.core.presentation.GameHistoryPresenter
import com.drawlesschess.core.presentation.GameResultView
import com.drawlesschess.core.presentation.GameScreenModel
import com.drawlesschess.core.presentation.PieceSets
import com.drawlesschess.core.presentation.ResponsiveBoardLayout
import com.drawlesschess.persistence.DrawlessDatabase
import com.drawlesschess.persistence.RoomCheckpointStore
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Opt-in Google Play screenshot exporter. It deliberately reuses an existing test method so the
 * production verification inventory does not grow merely to host marketing capture tooling.
 *
 * Run the host test with `-e storeScreenshotSet phone`, `tablet-portrait`, or
 * `tablet-landscape`. With no argument this class is inert and the host test runs normally.
 */
internal object StoreScreenshotHarness {
    private const val ARGUMENT = "storeScreenshotSet"
    private const val LOCALE_ARGUMENT = "storeScreenshotLocale"
    private const val VICTORY_CAPTURE_MILLIS = 1_210L
    private const val DEFEAT_CAPTURE_MILLIS = 890L
    private var outputLocaleSuffix = ""

    fun runIfRequested(compose: ComposeContentTestRule): Boolean {
        val requested = InstrumentationRegistry.getArguments().getString(ARGUMENT) ?: return false
        require(requested in setOf("phone", "tablet-portrait", "tablet-landscape")) {
            "Unsupported $ARGUMENT '$requested'"
        }
        val requestedLocale = InstrumentationRegistry.getArguments().getString(LOCALE_ARGUMENT)
        val locale = requestedLocale?.let(Locale::forLanguageTag) ?: Locale.forLanguageTag("en-US")
        require(locale.language.isNotBlank()) { "Unsupported $LOCALE_ARGUMENT '$requestedLocale'" }
        outputLocaleSuffix = requestedLocale
            ?.let { "-${it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9-]"), "-")}" }
            .orEmpty()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val isolatedContext = SelfApplicationContext(testContext)
        val database = Room.inMemoryDatabaseBuilder(testContext, DrawlessDatabase::class.java)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        val checkpointStore = RoomCheckpointStore(database, executor)
        val viewModel = DrawlessAppViewModel(isolatedContext, checkpointStore) { true }
        viewModel.dismissRulesGuide()
        val soundPlayer = GameSoundPlayer(targetContext).also { it.setEnabled(false) }
        val scene = mutableStateOf(MarketingScene.HOME)

        try {
            compose.setContent {
                ForcedLocale(locale) {
                    ForcedLightMode {
                        DrawlessTheme(theme = themeFor(scene.value)) {
                            when (scene.value) {
                                MarketingScene.HOME -> DrawlessApp(viewModel, soundPlayer)
                                MarketingScene.GLACIER_SLATE -> MarketingGame(
                                    model = gameplayModel(BoardThemes.GLACIER_SLATE),
                                    opponent = OpponentProfiles.quickPlay,
                                )
                                MarketingScene.DESERT_SANDSTONE -> MarketingGame(
                                    model = gameplayModel(BoardThemes.DESERT_SANDSTONE),
                                    opponent = OpponentProfiles.quickPlay,
                                )
                                MarketingScene.IMPERIAL_MARBLE -> MarketingGame(
                                    model = gameplayModel(BoardThemes.IMPERIAL_MARBLE),
                                    opponent = OpponentProfiles.quickPlay,
                                )
                                MarketingScene.VERDIGRIS_COPPER -> MarketingGame(
                                    model = gameplayModel(BoardThemes.VERDIGRIS_COPPER),
                                    opponent = OpponentProfiles.quickPlay,
                                )
                                MarketingScene.AMETHYST_GEODE -> MarketingGame(
                                    model = gameplayModel(BoardThemes.AMETHYST_GEODE),
                                    opponent = OpponentProfiles.quickPlay,
                                )
                                MarketingScene.VICTORY -> MarketingGame(
                                    model = victoryModel(),
                                    opponent = OpponentProfiles.quickPlay,
                                    completion = victoryResult(),
                                )
                                MarketingScene.DEFEAT -> MarketingGame(
                                    model = defeatModel(),
                                    opponent = OpponentProfiles.quickPlay,
                                    completion = defeatResult(),
                                )
                            }
                        }
                    }
                }
            }

            when (requested) {
                "phone" -> exportPhone(compose, scene)
                "tablet-portrait" -> exportTabletPortrait(compose, scene)
                "tablet-landscape" -> exportTabletLandscape(compose, scene)
            }
        } finally {
            soundPlayer.close()
            database.close()
            executor.shutdownNow()
        }
        return true
    }

    private fun exportPhone(
        compose: ComposeContentTestRule,
        scene: MutableState<MarketingScene>,
    ) {
        awaitHome(compose)
        save(compose, "phone-home-current.png")
        compose.onNodeWithTag("home_theme").performClick()
        compose.onNodeWithTag("theme_picker").fetchSemanticsNode()
        save(compose, "phone-themes-current.png")
        setScene(compose, scene, MarketingScene.IMPERIAL_MARBLE)
        save(compose, "phone-gameplay-current.png")
        setTimedScene(compose, scene, MarketingScene.VICTORY, VICTORY_CAPTURE_MILLIS)
        save(compose, "phone-victory-current.png", waitForIdle = false)
        setTimedScene(compose, scene, MarketingScene.DEFEAT, DEFEAT_CAPTURE_MILLIS)
        save(compose, "phone-defeat-current.png", waitForIdle = false)
    }

    private fun exportTabletPortrait(
        compose: ComposeContentTestRule,
        scene: MutableState<MarketingScene>,
    ) {
        awaitHome(compose)
        save(compose, "tablet-home-current.png")
        compose.onNodeWithTag("home_theme").performClick()
        compose.onNodeWithTag("theme_picker").fetchSemanticsNode()
        save(compose, "tablet-themes-current.png")
        setScene(compose, scene, MarketingScene.IMPERIAL_MARBLE)
        save(compose, "tablet-gameplay-current.png")
        setTimedScene(compose, scene, MarketingScene.VICTORY, VICTORY_CAPTURE_MILLIS)
        save(compose, "tablet-victory-current.png", waitForIdle = false)
    }

    private fun exportTabletLandscape(
        compose: ComposeContentTestRule,
        scene: MutableState<MarketingScene>,
    ) {
        setTimedScene(compose, scene, MarketingScene.DEFEAT, DEFEAT_CAPTURE_MILLIS)
        save(compose, "tablet-defeat-landscape-current.png", waitForIdle = false)
    }

    private fun awaitHome(compose: ComposeContentTestRule) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("home_options").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun setScene(
        compose: ComposeContentTestRule,
        scene: MutableState<MarketingScene>,
        value: MarketingScene,
    ) {
        compose.runOnIdle { scene.value = value }
        compose.waitForIdle()
    }

    private fun setTimedScene(
        compose: ComposeContentTestRule,
        scene: MutableState<MarketingScene>,
        value: MarketingScene,
        captureMillis: Long,
    ) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { scene.value = value }
        // Recompose the scene, launch the effect, then advance to one documented instant.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(captureMillis)
        compose.mainClock.advanceTimeByFrame()
    }

    private fun save(
        compose: ComposeContentTestRule,
        fileName: String,
        waitForIdle: Boolean = true,
    ) {
        if (waitForIdle) compose.waitForIdle()
        // Compose can report idle one frame before the Surface compositor on old devices.
        SystemClock.sleep(300)
        val image = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
        ) { "Android did not return a display screenshot" }
        check(image.width > 0 && image.height > 0 && image.config != Bitmap.Config.ALPHA_8)
        val directory = requireNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir,
        )
        val output = File(directory, fileName.replace("-current.png", "$outputLocaleSuffix-current.png"))
        FileOutputStream(output).use { stream ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        check(output.length() > 10_000L) { "Screenshot output is unexpectedly small: $output" }
    }

    @Composable
    private fun ForcedLocale(locale: Locale, content: @Composable () -> Unit) {
        val currentContext = LocalContext.current
        val currentConfiguration = LocalConfiguration.current
        val localizedConfiguration = remember(currentConfiguration, locale) {
            Configuration(currentConfiguration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
        }
        val localizedContext = remember(currentContext, localizedConfiguration) {
            currentContext.createConfigurationContext(localizedConfiguration)
        }
        val layoutDirection = if (TextUtils.getLayoutDirectionFromLocale(locale) == 1) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
        CompositionLocalProvider(
            LocalContext provides localizedContext,
            LocalConfiguration provides localizedConfiguration,
            LocalLayoutDirection provides layoutDirection,
            content = content,
        )
    }

    @Composable
    private fun ForcedLightMode(content: @Composable () -> Unit) {
        val current = LocalConfiguration.current
        val forced = remember(current) {
            Configuration(current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    Configuration.UI_MODE_NIGHT_NO
            }
        }
        CompositionLocalProvider(LocalConfiguration provides forced, content = content)
    }

    @Composable
    private fun MarketingGame(
        model: GameScreenModel,
        opponent: OpponentProfile,
        completion: GameResultView? = null,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fullWindowLayout = ResponsiveBoardLayout.calculate(
                maxWidth.value.roundToInt(),
                maxHeight.value.roundToInt(),
            )
            val headerInSidePanel = fullWindowLayout.controlPlacement ==
                ControlPlacement.BESIDE_BOARD

            Scaffold(
                topBar = {
                    if (!headerInSidePanel) {
                        GameTopBar(model, {}, {}, {})
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    GameBody(
                        model = model,
                        opponent = opponent,
                        modifier = Modifier.fillMaxSize(),
                        sideHeader = {
                            if (headerInSidePanel) GameSideHeader(model, {}, {}, {})
                        },
                        onBoardEvent = {},
                        onPause = {},
                        onUndo = {},
                        onHint = {},
                        onFlip = {},
                        onRetryBot = {},
                        onResign = {},
                        onDismissMessage = {},
                        showBoardCoordinates = true,
                        onMoveAnimationFinished = {},
                    )
                    completion?.let { result ->
                        CompletionEffectOverlay(
                            result = result,
                            opponent = opponent,
                            onCue = {},
                            onFinished = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    private fun gameplayModel(
        theme: BoardTheme = BoardThemes.GLACIER_SLATE,
    ): GameScreenModel = modelFromMoves(
        moves = listOf(
            "e2e4", "d7d5", "e4d5", "d8d5", "b1c3", "d5d8",
            "d2d4", "g8f6", "g1f3", "c7c6", "f1d3", "c8g4",
        ),
        humanSide = Side.WHITE,
        theme = theme,
    )

    private fun victoryModel(): GameScreenModel = modelFromMoves(
        moves = listOf("e2e4", "e7e5", "d1h5", "b8c6", "f1c4", "g8f6", "h5f7"),
        humanSide = Side.WHITE,
        theme = BoardThemes.VERDIGRIS_COPPER,
        result = victoryResult(),
    )

    private fun defeatModel(): GameScreenModel = modelFromMoves(
        moves = listOf("f2f3", "e7e5", "g2g4", "d8h4"),
        humanSide = Side.WHITE,
        theme = BoardThemes.VERDIGRIS_COPPER,
        result = defeatResult(),
    )

    private fun modelFromMoves(
        moves: List<String>,
        humanSide: Side,
        theme: BoardTheme,
        result: GameResultView? = null,
    ): GameScreenModel {
        val rules = RulesContractV1.drawless()
        val encodedMoves = moves.map(::UciMove)
        var position = ChessPosition.starting()
        var session = GameSession.newGame(
            gameId = "store-screenshot",
            rules = rules,
            initialPositionKey = RepetitionKey.of(position),
        )
        encodedMoves.forEach { move ->
            val transition = ChessAdapter.transition(position, move)
            session = session.apply(transition)
            position = ChessRules.apply(position, move)
        }
        val phase = if (result == null) CoordinatorPhase.HUMAN_TURN else CoordinatorPhase.COMPLETED
        val config = GameConfig(
            gameId = session.gameId,
            initialFen = ChessPosition.START_FEN,
            rules = rules,
            mode = GameMode.CASUAL,
            timeControl = TimeControl.Untimed,
            humanSide = humanSide,
            engineStrength = EngineStrength.ApproximateElo(700),
            engineLimits = EngineLimits(moveTimeMillis = 120),
            opponentLevelId = OpponentProfiles.quickPlay.level.id,
        )
        val snapshot = CoordinatorSnapshot(
            revision = encodedMoves.size.toLong(),
            session = session,
            currentFen = position.fen(),
            phase = phase,
            clock = ClockSnapshot(null, null, null, paused = false),
            assistance = AssistanceCounts(),
            engineError = null,
        )
        val timeline = GameHistoryPresenter.present(ChessPosition.START_FEN, encodedMoves)
        return GameScreenModel(
            board = BoardPresenter.present(
                snapshot = snapshot,
                config = config,
                interactionState = BoardInteractionState.initial(position, humanSide),
                theme = theme,
                pieceSet = PieceSets.MODERN_FLAT,
            ),
            whiteClock = ClockView("∞", active = phase != CoordinatorPhase.COMPLETED, lowTime = false),
            blackClock = ClockView("∞", active = false, lowTime = false),
            history = timeline.history,
            capturedMaterial = timeline.capturedMaterial,
            controls = GameControlsView(
                canPause = result == null,
                paused = false,
                canUndo = result == null,
                canHint = result == null,
                canResign = result == null,
            ),
            rulesPreset = rules.preset,
            mode = GameMode.CASUAL,
            result = result,
            transientNotice = null,
        )
    }

    private fun victoryResult() = GameResultView(
        playerWon = true,
        playerSide = Side.WHITE,
        winner = Side.WHITE,
        reason = EndReason.CHECKMATE,
        score = GameScore(points = 100, maximumPoints = 100, threatIndicationPenalty = 0),
    )

    private fun defeatResult() = GameResultView(
        playerWon = false,
        playerSide = Side.WHITE,
        winner = Side.BLACK,
        reason = EndReason.CHECKMATE,
        score = GameScore(points = 0, maximumPoints = 100, threatIndicationPenalty = 0),
    )

    private fun themeFor(scene: MarketingScene): BoardTheme = when (scene) {
        MarketingScene.HOME -> BoardThemes.IMPERIAL_MARBLE
        MarketingScene.GLACIER_SLATE -> BoardThemes.GLACIER_SLATE
        MarketingScene.DESERT_SANDSTONE -> BoardThemes.DESERT_SANDSTONE
        MarketingScene.IMPERIAL_MARBLE -> BoardThemes.IMPERIAL_MARBLE
        MarketingScene.VERDIGRIS_COPPER -> BoardThemes.VERDIGRIS_COPPER
        MarketingScene.AMETHYST_GEODE -> BoardThemes.AMETHYST_GEODE
        MarketingScene.VICTORY, MarketingScene.DEFEAT -> BoardThemes.VERDIGRIS_COPPER
    }

    private enum class MarketingScene {
        HOME,
        GLACIER_SLATE,
        DESERT_SANDSTONE,
        IMPERIAL_MARBLE,
        VERDIGRIS_COPPER,
        AMETHYST_GEODE,
        VICTORY,
        DEFEAT,
    }

    /** Instrumentation's package context has no Application object on some older Android builds. */
    private class SelfApplicationContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }
}
