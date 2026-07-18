package com.drawlesschess.ui

import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drawlesschess.core.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameHapticsInstrumentedTest {
    @Test
    fun moveCuesAreRestrainedAndUseOnePriorityOrderedSignal() {
        assertNull(
            GameHapticClassifier.forMove(
                mover = Side.WHITE,
                humanSide = Side.WHITE,
                capture = true,
                check = true,
                terminal = true,
            ),
        )
        assertEquals(
            GameHapticCue.CHECK_GIVEN,
            GameHapticClassifier.forMove(Side.WHITE, Side.WHITE, capture = true, check = true, terminal = false),
        )
        assertEquals(
            GameHapticCue.CHECK_RECEIVED,
            GameHapticClassifier.forMove(Side.BLACK, Side.WHITE, capture = true, check = true, terminal = false),
        )
        assertEquals(
            GameHapticCue.CAPTURE,
            GameHapticClassifier.forMove(Side.BLACK, Side.WHITE, capture = true, check = false, terminal = false),
        )
        assertEquals(
            GameHapticCue.MOVE,
            GameHapticClassifier.forMove(Side.WHITE, Side.WHITE, capture = false, check = false, terminal = false),
        )
        assertNull(
            GameHapticClassifier.forMove(Side.BLACK, Side.WHITE, capture = false, check = false, terminal = false),
        )
    }

    @Test
    fun completionAndCompatibilityMappingsStayExplicit() {
        assertEquals(GameHapticCue.WIN, GameHapticClassifier.forCompletion(playerWon = true))
        assertEquals(GameHapticCue.LOSS, GameHapticClassifier.forCompletion(playerWon = false))
        assertEquals(HapticFeedbackConstantsCompat.CONFIRM, GameHapticCue.WIN.feedbackConstant())
        assertEquals(HapticFeedbackConstantsCompat.REJECT, GameHapticCue.LOSS.feedbackConstant())
        assertEquals(
            HapticFeedbackConstantsCompat.CONTEXT_CLICK,
            GameHapticCue.CAPTURE.feedbackConstant(),
        )
    }
}
