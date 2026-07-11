package com.drawlesschess.rules;

import java.util.Objects;

/**
 * Engine-neutral facts about the position after a move.
 *
 * The chess adapter owns move legality, check detection, repetition-key generation,
 * dead-position detection, and counting alternatives before the move. Keeping those
 * concerns outside this class makes the adjudicator deterministic and easy to test.
 */
public final class PositionAfterMove {
    private final Side mover;
    private final int legalMoveCount;
    private final boolean sideToMoveInCheck;
    private final int positionOccurrenceCount;
    private final int repetitionAvoidingAlternativesBeforeMove;
    private final int halfmoveClock;
    private final int fiftyMoveAvoidingAlternativesBeforeMove;
    private final boolean deadPosition;
    private final boolean moveWasCapture;
    private final int whiteMaterial;
    private final int blackMaterial;

    private PositionAfterMove(Builder b) {
        mover = Objects.requireNonNull(b.mover, "mover");
        legalMoveCount = nonNegative(b.legalMoveCount, "legalMoveCount");
        sideToMoveInCheck = b.sideToMoveInCheck;
        positionOccurrenceCount = positive(b.positionOccurrenceCount, "positionOccurrenceCount");
        repetitionAvoidingAlternativesBeforeMove = nonNegative(
                b.repetitionAvoidingAlternativesBeforeMove,
                "repetitionAvoidingAlternativesBeforeMove");
        halfmoveClock = nonNegative(b.halfmoveClock, "halfmoveClock");
        fiftyMoveAvoidingAlternativesBeforeMove = nonNegative(
                b.fiftyMoveAvoidingAlternativesBeforeMove,
                "fiftyMoveAvoidingAlternativesBeforeMove");
        deadPosition = b.deadPosition;
        moveWasCapture = b.moveWasCapture;
        whiteMaterial = nonNegative(b.whiteMaterial, "whiteMaterial");
        blackMaterial = nonNegative(b.blackMaterial, "blackMaterial");
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
        return value;
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    public static Builder afterMoveBy(Side mover) { return new Builder(mover); }
    public Side mover() { return mover; }
    public Side sideToMove() { return mover.opposite(); }
    public int legalMoveCount() { return legalMoveCount; }
    public boolean sideToMoveInCheck() { return sideToMoveInCheck; }
    public int positionOccurrenceCount() { return positionOccurrenceCount; }
    public int repetitionAvoidingAlternativesBeforeMove() { return repetitionAvoidingAlternativesBeforeMove; }
    public int halfmoveClock() { return halfmoveClock; }
    public int fiftyMoveAvoidingAlternativesBeforeMove() { return fiftyMoveAvoidingAlternativesBeforeMove; }
    public boolean deadPosition() { return deadPosition; }
    public boolean moveWasCapture() { return moveWasCapture; }
    public int whiteMaterial() { return whiteMaterial; }
    public int blackMaterial() { return blackMaterial; }

    public static final class Builder {
        private final Side mover;
        private int legalMoveCount = 1;
        private boolean sideToMoveInCheck;
        private int positionOccurrenceCount = 1;
        private int repetitionAvoidingAlternativesBeforeMove = 1;
        private int halfmoveClock;
        private int fiftyMoveAvoidingAlternativesBeforeMove = 1;
        private boolean deadPosition;
        private boolean moveWasCapture;
        private int whiteMaterial;
        private int blackMaterial;

        private Builder(Side mover) { this.mover = mover; }
        public Builder legalMoves(int value) { legalMoveCount = value; return this; }
        public Builder inCheck(boolean value) { sideToMoveInCheck = value; return this; }
        public Builder positionOccurrences(int value) { positionOccurrenceCount = value; return this; }
        public Builder repetitionAvoidingAlternatives(int value) {
            repetitionAvoidingAlternativesBeforeMove = value; return this;
        }
        public Builder halfmoveClock(int value) { halfmoveClock = value; return this; }
        public Builder fiftyMoveAvoidingAlternatives(int value) {
            fiftyMoveAvoidingAlternativesBeforeMove = value; return this;
        }
        public Builder deadPosition(boolean value) { deadPosition = value; return this; }
        public Builder moveWasCapture(boolean value) { moveWasCapture = value; return this; }
        public Builder material(int white, int black) {
            whiteMaterial = white; blackMaterial = black; return this;
        }
        public PositionAfterMove build() { return new PositionAfterMove(this); }
    }
}
