package com.drawlesschess.rules;

import java.util.Objects;

public final class Ruleset {
    private final String id;
    private final StalematePolicy stalematePolicy;
    private final DeadPositionPolicy deadPositionPolicy;
    private final FiftyMovePolicy fiftyMovePolicy;

    public Ruleset(
            String id,
            StalematePolicy stalematePolicy,
            DeadPositionPolicy deadPositionPolicy,
            FiftyMovePolicy fiftyMovePolicy) {
        this.id = Objects.requireNonNull(id, "id");
        this.stalematePolicy = Objects.requireNonNull(stalematePolicy, "stalematePolicy");
        this.deadPositionPolicy = Objects.requireNonNull(deadPositionPolicy, "deadPositionPolicy");
        this.fiftyMovePolicy = Objects.requireNonNull(fiftyMovePolicy, "fiftyMovePolicy");
    }

    public static Ruleset drawless(DeadPositionPolicy dead, FiftyMovePolicy fifty) {
        return new Ruleset("drawless", StalematePolicy.TRAPPED_PLAYER_LOSES, dead, fifty);
    }

    public static Ruleset escape(DeadPositionPolicy dead, FiftyMovePolicy fifty) {
        return new Ruleset("escape", StalematePolicy.TRAPPED_PLAYER_WINS, dead, fifty);
    }

    public String id() { return id; }
    public StalematePolicy stalematePolicy() { return stalematePolicy; }
    public DeadPositionPolicy deadPositionPolicy() { return deadPositionPolicy; }
    public FiftyMovePolicy fiftyMovePolicy() { return fiftyMovePolicy; }
}
