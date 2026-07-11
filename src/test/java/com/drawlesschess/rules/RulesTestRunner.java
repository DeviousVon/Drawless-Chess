package com.drawlesschess.rules;

import java.util.ArrayList;
import java.util.List;

public final class RulesTestRunner {
    private static final DrawlessAdjudicator ADJUDICATOR = new DrawlessAdjudicator();
    private static final Ruleset DRAWLESS = Ruleset.drawless(
            DeadPositionPolicy.MATERIAL_VICTORY, FiftyMovePolicy.COMPLETING_PLAYER_LOSES);

    private static final List<String> failures = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) {
        test("ordinary position continues", () -> outcome(DRAWLESS, pos(Side.WHITE)).isTerminal() == false);
        test("checkmate awards mover", () -> winner(DRAWLESS,
                pos(Side.WHITE).legalMoves(0).inCheck(true)) == Side.WHITE);
        test("drawless stalemate defeats trapped player", () -> winner(DRAWLESS,
                pos(Side.BLACK).legalMoves(0).inCheck(false)) == Side.BLACK);
        test("escape stalemate rewards trapped player", () -> winner(
                Ruleset.escape(DeadPositionPolicy.MATERIAL_VICTORY, FiftyMovePolicy.DISABLED),
                pos(Side.BLACK).legalMoves(0).inCheck(false)) == Side.WHITE);
        test("voluntary third repetition defeats mover", () -> winner(DRAWLESS,
                pos(Side.WHITE).positionOccurrences(3).repetitionAvoidingAlternatives(2)) == Side.BLACK);
        test("forced third repetition defeats forcing opponent", () -> winner(DRAWLESS,
                pos(Side.WHITE).positionOccurrences(3).repetitionAvoidingAlternatives(0)) == Side.WHITE);
        test("second occurrence is not terminal", () -> !outcome(DRAWLESS,
                pos(Side.WHITE).positionOccurrences(2)).isTerminal());
        test("material victory chooses white", () -> winner(DRAWLESS,
                pos(Side.BLACK).deadPosition(true).moveWasCapture(true).material(3, 0)) == Side.WHITE);
        test("material victory chooses black", () -> winner(DRAWLESS,
                pos(Side.WHITE).deadPosition(true).moveWasCapture(true).material(0, 3)) == Side.BLACK);
        test("equal dead material rewards causing mover", () -> winner(DRAWLESS,
                pos(Side.BLACK).deadPosition(true).moveWasCapture(true).material(0, 0)) == Side.BLACK);
        test("final capture policy rewards capturing mover", () -> winner(
                Ruleset.drawless(DeadPositionPolicy.FINAL_CAPTURE_VICTORY, FiftyMovePolicy.DISABLED),
                pos(Side.WHITE).deadPosition(true).moveWasCapture(true).material(0, 9)) == Side.WHITE);
        test("final capture policy rejects a non-capture transition", () -> throwsState(() -> outcome(
                Ruleset.drawless(DeadPositionPolicy.FINAL_CAPTURE_VICTORY, FiftyMovePolicy.DISABLED),
                pos(Side.WHITE).deadPosition(true).moveWasCapture(false))));
        test("50-move completion defeats mover", () -> winner(DRAWLESS,
                pos(Side.WHITE).halfmoveClock(100)) == Side.BLACK);
        test("disabled 50-move policy continues", () -> !outcome(
                Ruleset.drawless(DeadPositionPolicy.MATERIAL_VICTORY, FiftyMovePolicy.DISABLED),
                pos(Side.WHITE).halfmoveClock(100)).isTerminal());
        test("forced 50-move exception defeats forcing opponent", () -> winner(
                Ruleset.drawless(DeadPositionPolicy.MATERIAL_VICTORY, FiftyMovePolicy.FORCED_MOVE_EXCEPTION),
                pos(Side.WHITE).halfmoveClock(100).fiftyMoveAvoidingAlternatives(0)) == Side.WHITE);
        test("checkmate outranks repetition", () -> outcome(DRAWLESS,
                pos(Side.WHITE).legalMoves(0).inCheck(true).positionOccurrences(3)).reason()
                == EndReason.CHECKMATE);
        test("repetition outranks dead-position adjudication", () -> outcome(DRAWLESS,
                pos(Side.WHITE).positionOccurrences(3).deadPosition(true).moveWasCapture(true)).reason()
                == EndReason.REPETITION);
        test("invalid negative facts are rejected", () -> throwsArgument(() ->
                pos(Side.WHITE).legalMoves(-1).build()));

        if (!failures.isEmpty()) {
            System.err.println("FAILED " + failures.size() + " of " + (passed + failures.size()) + " tests");
            failures.forEach(f -> System.err.println(" - " + f));
            System.exit(1);
        }
        System.out.println("PASSED " + passed + " rules tests");
    }

    private static PositionAfterMove.Builder pos(Side mover) {
        return PositionAfterMove.afterMoveBy(mover);
    }

    private static GameOutcome outcome(Ruleset rules, PositionAfterMove.Builder p) {
        return ADJUDICATOR.adjudicate(rules, p.build());
    }

    private static Side winner(Ruleset rules, PositionAfterMove.Builder p) {
        return outcome(rules, p).winner().orElseThrow(AssertionError::new);
    }

    private static boolean throwsState(Runnable r) {
        try { r.run(); return false; } catch (IllegalStateException expected) { return true; }
    }

    private static boolean throwsArgument(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException expected) { return true; }
    }

    private static void test(String name, Check check) {
        try {
            if (!check.run()) failures.add(name + ": assertion returned false");
            else passed++;
        } catch (Throwable t) {
            failures.add(name + ": " + t);
        }
    }

    private interface Check { boolean run(); }
}
