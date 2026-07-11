package com.drawlesschess.rules;

import java.util.Objects;
import java.util.Optional;

public final class GameOutcome {
    private static final GameOutcome ONGOING = new GameOutcome(EndReason.NONE, null, null, "Game continues");

    private final EndReason reason;
    private final Side winner;
    private final Side loser;
    private final String explanation;

    private GameOutcome(EndReason reason, Side winner, Side loser, String explanation) {
        this.reason = Objects.requireNonNull(reason, "reason");
        this.winner = winner;
        this.loser = loser;
        this.explanation = Objects.requireNonNull(explanation, "explanation");
    }

    public static GameOutcome ongoing() { return ONGOING; }

    public static GameOutcome win(EndReason reason, Side winner, String explanation) {
        if (reason == EndReason.NONE) throw new IllegalArgumentException("A win needs an end reason");
        return new GameOutcome(reason, winner, winner.opposite(), explanation);
    }

    public boolean isTerminal() { return reason != EndReason.NONE; }
    public EndReason reason() { return reason; }
    public Optional<Side> winner() { return Optional.ofNullable(winner); }
    public Optional<Side> loser() { return Optional.ofNullable(loser); }
    public String explanation() { return explanation; }
}
