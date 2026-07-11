package com.drawlesschess.rules;

public enum EndReason {
    NONE,
    CHECKMATE,
    STALEMATE,
    REPETITION,
    DEAD_POSITION_MATERIAL,
    DEAD_POSITION_FINAL_CAPTURE,
    FIFTY_MOVE_LIMIT
}
