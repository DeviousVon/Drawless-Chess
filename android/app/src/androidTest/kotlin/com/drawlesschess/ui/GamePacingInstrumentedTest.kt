package com.drawlesschess.ui

import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.Side
import com.drawlesschess.core.TimeControl
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.engine.BotDifficultyCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class GamePacingInstrumentedTest {
    @Test
    fun randomIsTheDefaultAndResolvesToEitherConcreteSide() {
        assertEquals(StartingColor.RANDOM, SetupSelection().startingColor)
        assertEquals(Side.WHITE, StartingColor.RANDOM.resolve { true })
        assertEquals(Side.BLACK, StartingColor.RANDOM.resolve { false })
        assertEquals(Side.WHITE, StartingColor.WHITE.resolve { false })
        assertEquals(Side.BLACK, StartingColor.BLACK.resolve { true })
    }

    @Test
    fun requestedPacingIsExactlyHalfASecondForBothStages() {
        assertEquals(500L, GamePacing.OPPONENT_MOVE_DELAY_MILLIS)
        assertEquals(500, GamePacing.PIECE_MOVE_ANIMATION_MILLIS)
    }

    @Test
    fun randomResolvesOnceAndRematchKeepsTheResolvedSide() {
        var randomCalls = 0
        val resolved = SetupSelection().resolveForNewGame {
            randomCalls++
            false
        }

        assertEquals(Side.BLACK, resolved.humanSide)
        assertEquals(StartingColor.BLACK, resolved.rematchSelection.startingColor)
        assertEquals(1, randomCalls)

        val rematch = resolved.rematchSelection.resolveForNewGame {
            randomCalls++
            true
        }
        assertEquals(Side.BLACK, rematch.humanSide)
        assertEquals(1, randomCalls)
    }

    @Test
    fun quickPlayDrawsAFreshSideAndCanResolveToEitherAssignment() {
        val opponent = BotDifficultyCatalog.named("challenger")
        val selection = quickPlaySetup(opponent)

        assertEquals(StartingColor.RANDOM, selection.startingColor)
        assertEquals(opponent, selection.botLevel)
        assertEquals(Side.WHITE, selection.resolveForNewGame { true }.humanSide)
        assertEquals(Side.BLACK, selection.resolveForNewGame { false }.humanSide)
    }

    @Test
    fun resumedConcreteSideDoesNotRerollOnRematch() {
        val checkpointConfig = GameConfig(
            gameId = "resume-black",
            initialFen = ChessPosition.START_FEN,
            rules = SetupSelection().rules(),
            mode = GameMode.CASUAL,
            timeControl = TimeControl.Untimed,
            humanSide = Side.BLACK,
            engineStrength = EngineStrength.ApproximateElo(875),
            engineLimits = EngineLimits(moveTimeMillis = 350),
        )
        var randomCalls = 0

        val resumed = checkpointConfig.toSetupSelection().resolveForNewGame {
            randomCalls++
            true
        }

        assertEquals(Side.BLACK, resumed.humanSide)
        assertEquals(StartingColor.BLACK, resumed.rematchSelection.startingColor)
        assertEquals(0, randomCalls)
    }

    @Test
    fun resumedAdaptiveGameKeepsItsIdentityAndFrozenStrength() {
        val checkpointConfig = GameConfig(
            gameId = "adaptive-resume",
            initialFen = ChessPosition.START_FEN,
            rules = SetupSelection().rules(),
            mode = GameMode.CASUAL,
            timeControl = TimeControl.Untimed,
            humanSide = Side.WHITE,
            engineStrength = EngineStrength.ApproximateElo(973),
            engineLimits = EngineLimits(moveTimeMillis = 350),
            opponentLevelId = BotDifficultyCatalog.ADAPTIVE_LEVEL_ID,
        )

        val resumed = checkpointConfig.toSetupSelection()

        assertEquals(BotDifficultyCatalog.ADAPTIVE_LEVEL_ID, resumed.botLevel.id)
        assertEquals(973, resumed.botLevel.approximateElo)
    }
}
