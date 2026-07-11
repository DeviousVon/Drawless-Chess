package com.drawlesschess.rules;

import java.util.Objects;

public final class DrawlessAdjudicator {
    public GameOutcome adjudicate(Ruleset rules, PositionAfterMove p) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(p, "position");

        // No-legal-move outcomes outrank draw-like counters reached on the same move.
        if (p.legalMoveCount() == 0) {
            if (p.sideToMoveInCheck()) {
                return GameOutcome.win(EndReason.CHECKMATE, p.mover(),
                        p.mover() + " wins by checkmate");
            }
            Side winner = rules.stalematePolicy() == StalematePolicy.TRAPPED_PLAYER_LOSES
                    ? p.mover()
                    : p.sideToMove();
            return GameOutcome.win(EndReason.STALEMATE, winner,
                    winner + " wins under the " + rules.id() + " stalemate rule");
        }

        if (p.positionOccurrenceCount() >= 3) {
            Side loser = p.repetitionAvoidingAlternativesBeforeMove() == 0
                    ? p.sideToMove() // opponent forced the mover into repetition
                    : p.mover();
            return GameOutcome.win(EndReason.REPETITION, loser.opposite(),
                    loser + " loses by causing a third repetition");
        }

        if (p.deadPosition()) {
            if (rules.deadPositionPolicy() == DeadPositionPolicy.FINAL_CAPTURE_VICTORY) {
                if (!p.moveWasCapture()) {
                    throw new IllegalStateException(
                            "Final-capture adjudication requires the transition move to be a capture");
                }
                return GameOutcome.win(EndReason.DEAD_POSITION_FINAL_CAPTURE, p.mover(),
                        p.mover() + " wins by making the final meaningful capture");
            }

            Side winner;
            if (p.whiteMaterial() > p.blackMaterial()) winner = Side.WHITE;
            else if (p.blackMaterial() > p.whiteMaterial()) winner = Side.BLACK;
            else winner = p.mover();
            return GameOutcome.win(EndReason.DEAD_POSITION_MATERIAL, winner,
                    winner + " wins the dead position by material adjudication");
        }

        if (p.halfmoveClock() >= 100 && rules.fiftyMovePolicy() != FiftyMovePolicy.DISABLED) {
            Side loser = p.mover();
            if (rules.fiftyMovePolicy() == FiftyMovePolicy.FORCED_MOVE_EXCEPTION
                    && p.fiftyMoveAvoidingAlternativesBeforeMove() == 0) {
                loser = p.sideToMove();
            }
            return GameOutcome.win(EndReason.FIFTY_MOVE_LIMIT, loser.opposite(),
                    loser + " loses by reaching the configured 50-move limit");
        }

        return GameOutcome.ongoing();
    }
}
