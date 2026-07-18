package com.drawlesschess.ui

import android.view.View
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.presentation.BoardEvent
import com.drawlesschess.core.presentation.BoardScreenState

internal enum class GameHapticCue {
    SELECTION,
    DRAG_START,
    MOVE,
    CAPTURE,
    CHECK_GIVEN,
    CHECK_RECEIVED,
    REJECTED,
    WIN,
    LOSS,
}

/** Pure event classification keeps the one-cue-per-action policy independently testable. */
internal object GameHapticClassifier {
    fun forMove(
        mover: Side,
        humanSide: Side,
        capture: Boolean,
        check: Boolean,
        terminal: Boolean,
    ): GameHapticCue? = when {
        terminal -> null
        check && mover == humanSide -> GameHapticCue.CHECK_GIVEN
        check -> GameHapticCue.CHECK_RECEIVED
        capture -> GameHapticCue.CAPTURE
        mover == humanSide -> GameHapticCue.MOVE
        else -> null
    }

    fun forCompletion(playerWon: Boolean): GameHapticCue =
        if (playerWon) GameHapticCue.WIN else GameHapticCue.LOSS

    fun forInteraction(
        before: BoardScreenState,
        after: BoardScreenState,
        event: BoardEvent,
    ): GameHapticCue? {
        if ((!before.interactive && !before.preselectionEnabled) ||
            before.plyCount != after.plyCount
        ) return null

        val promotionOpened = before.promotionPrompt == null && after.promotionPrompt != null
        return when (event) {
            is BoardEvent.DragStarted -> {
                if (after.interaction.draggingFrom == event.square) GameHapticCue.DRAG_START else null
            }
            is BoardEvent.TapSquare -> forSelection(before, after, event.square, promotionOpened)
            is BoardEvent.PreselectSquare -> forSelection(before, after, event.square, promotionOpened)
            is BoardEvent.Dropped -> when {
                promotionOpened -> null
                before.interaction.draggingFrom != null -> GameHapticCue.REJECTED
                else -> null
            }
            BoardEvent.DragCancelled,
            is BoardEvent.PromotionChosen,
            BoardEvent.PromotionCancelled,
            BoardEvent.FlipBoard,
            -> null
        }
    }

    private fun forSelection(
        before: BoardScreenState,
        after: BoardScreenState,
        square: Square,
        promotionOpened: Boolean,
    ): GameHapticCue? = when {
        after.interaction.selected != null &&
            after.interaction.selected != before.interaction.selected -> GameHapticCue.SELECTION
        promotionOpened || before.interaction.selected == null ||
            square == before.interaction.selected -> null
        after.interaction.selected == before.interaction.selected -> GameHapticCue.REJECTED
        else -> null
    }
}

internal class GameHaptics(private val view: View) {
    fun perform(cue: GameHapticCue) {
        ViewCompat.performHapticFeedback(view, cue.feedbackConstant())
    }
}

internal fun GameHapticCue.feedbackConstant(): Int = when (this) {
    GameHapticCue.SELECTION -> HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK
    GameHapticCue.DRAG_START -> HapticFeedbackConstantsCompat.DRAG_START
    GameHapticCue.MOVE,
    GameHapticCue.CHECK_GIVEN,
    GameHapticCue.WIN,
    -> HapticFeedbackConstantsCompat.CONFIRM
    GameHapticCue.CAPTURE -> HapticFeedbackConstantsCompat.CONTEXT_CLICK
    GameHapticCue.CHECK_RECEIVED,
    GameHapticCue.REJECTED,
    GameHapticCue.LOSS,
    -> HapticFeedbackConstantsCompat.REJECT
}
