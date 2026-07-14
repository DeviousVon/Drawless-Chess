package com.drawlesschess.ui

import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.Side
import com.drawlesschess.core.TimeControl
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.coordinator.GameConfig
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
}
