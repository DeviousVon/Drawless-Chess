import test from "node:test";
import assert from "node:assert/strict";
import {
  adjudicate, DeadPositionPolicy as Dead, EndReason, FiftyMovePolicy as Fifty,
  positionAfterMove as pos, ruleset, Side, StalematePolicy as Stalemate,
} from "../../main/js/rules.js";

const drawless = ruleset();
const outcome = (overrides, rules = drawless) => adjudicate(rules, pos(overrides));

test("ordinary position continues", () => assert.equal(outcome({}).terminal, false));
test("drawless defaults to no 50-move limit", () => {
  assert.equal(drawless.fiftyMove, Fifty.DISABLED);
  assert.equal(outcome({ halfmoveClock: 100 }).terminal, false);
});
test("checkmate awards mover", () => assert.equal(outcome({ mover: Side.BLACK, legalMoveCount: 0, sideToMoveInCheck: true }).winner, Side.BLACK));
test("drawless stalemate defeats trapped player", () => assert.equal(outcome({ mover: Side.WHITE, legalMoveCount: 0 }).winner, Side.WHITE));
test("escape stalemate rewards trapped player", () => assert.equal(outcome(
  { mover: Side.WHITE, legalMoveCount: 0 },
  ruleset({ id: "escape", stalemate: Stalemate.TRAPPED_PLAYER_WINS })).winner, Side.BLACK));
test("voluntary third repetition defeats mover", () => assert.equal(outcome(
  { mover: Side.WHITE, positionOccurrenceCount: 3, repetitionAvoidingAlternativesBeforeMove: 2 }).winner, Side.BLACK));
test("forced third repetition defeats forcing opponent", () => assert.equal(outcome(
  { mover: Side.WHITE, positionOccurrenceCount: 3, repetitionAvoidingAlternativesBeforeMove: 0 }).winner, Side.WHITE));
test("second occurrence continues", () => assert.equal(outcome({ positionOccurrenceCount: 2 }).terminal, false));
test("material victory chooses greater material", () => assert.equal(outcome(
  { mover: Side.BLACK, deadPosition: true, moveWasCapture: true, whiteMaterial: 3, blackMaterial: 0 }).winner, Side.WHITE));
test("equal dead material rewards causing mover", () => assert.equal(outcome(
  { mover: Side.BLACK, deadPosition: true, moveWasCapture: true }).winner, Side.BLACK));
test("final-capture rule rewards capturer", () => assert.equal(outcome(
  { mover: Side.WHITE, deadPosition: true, moveWasCapture: true, blackMaterial: 9 },
  ruleset({ deadPosition: Dead.FINAL_CAPTURE_VICTORY })).winner, Side.WHITE));
test("final-capture rule rejects a non-capture transition", () => assert.throws(() => outcome(
  { deadPosition: true }, ruleset({ deadPosition: Dead.FINAL_CAPTURE_VICTORY }))));
test("50-move completion defeats mover", () => assert.equal(outcome(
  { mover: Side.WHITE, halfmoveClock: 100 },
  ruleset({ fiftyMove: Fifty.COMPLETING_PLAYER_LOSES })).winner, Side.BLACK));
test("disabled 50-move policy continues", () => assert.equal(outcome(
  { halfmoveClock: 100 }, ruleset({ fiftyMove: Fifty.DISABLED })).terminal, false));
test("forced 50-move exception defeats forcing opponent", () => assert.equal(outcome(
  { mover: Side.WHITE, halfmoveClock: 100, fiftyMoveAvoidingAlternativesBeforeMove: 0 },
  ruleset({ fiftyMove: Fifty.FORCED_MOVE_EXCEPTION })).winner, Side.WHITE));
test("checkmate outranks repetition", () => assert.equal(outcome(
  { legalMoveCount: 0, sideToMoveInCheck: true, positionOccurrenceCount: 3 }).reason, EndReason.CHECKMATE));
test("repetition outranks dead-position adjudication", () => assert.equal(outcome(
  { positionOccurrenceCount: 3, deadPosition: true, moveWasCapture: true }).reason, EndReason.REPETITION));
test("invalid negative facts are rejected", () => assert.throws(() => pos({ legalMoveCount: -1 }), RangeError));
