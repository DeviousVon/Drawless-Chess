import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  adjudicateDrawless,
  outcomeText,
  resignationOutcome,
  type PositionFacts,
} from "../app/play/drawless-rules.ts";

const baseFacts: PositionFacts = {
  mover: "WHITE",
  legalMovesAfter: 12,
  sideToMoveInCheck: false,
  positionOccurrenceCount: 1,
  repetitionAvoidingAlternativesBeforeMove: 8,
  halfmoveClockAfter: 0,
  fiftyMoveAvoidingAlternativesBeforeMove: 8,
  deadPositionAfter: false,
  materialAfter: { white: 39, black: 39 },
  lastCaptureBy: null,
};

const facts = (overrides: Partial<PositionFacts>): PositionFacts => ({ ...baseFacts, ...overrides });

test("normal play remains unfinished", () => {
  assert.equal(adjudicateDrawless(baseFacts), null);
});

test("checkmate and Drawless stalemate both award the mover", () => {
  assert.deepEqual(
    adjudicateDrawless(facts({ legalMovesAfter: 0, sideToMoveInCheck: true })),
    { winner: "WHITE", loser: "BLACK", reason: "CHECKMATE" },
  );
  assert.deepEqual(
    adjudicateDrawless(facts({ legalMovesAfter: 0 })),
    { winner: "WHITE", loser: "BLACK", reason: "STALEMATE" },
  );
});

test("avoidable repetition defeats the mover and forced repetition awards the mover", () => {
  assert.deepEqual(
    adjudicateDrawless(facts({ mover: "BLACK", positionOccurrenceCount: 3 })),
    { winner: "WHITE", loser: "BLACK", reason: "REPETITION" },
  );
  assert.deepEqual(
    adjudicateDrawless(facts({
      mover: "BLACK",
      positionOccurrenceCount: 3,
      repetitionAvoidingAlternativesBeforeMove: 0,
    })),
    { winner: "BLACK", loser: "WHITE", reason: "REPETITION" },
  );
});

test("bare king and known-dead material use the app's fixed material policy", () => {
  assert.deepEqual(
    adjudicateDrawless(facts({ materialAfter: { white: 0, black: 3 } })),
    { winner: "BLACK", loser: "WHITE", reason: "BARE_KING" },
  );
  assert.deepEqual(
    adjudicateDrawless(facts({ deadPositionAfter: true, materialAfter: { white: 3, black: 1 } })),
    { winner: "WHITE", loser: "BLACK", reason: "DEAD_POSITION_MATERIAL" },
  );
  assert.deepEqual(
    adjudicateDrawless(facts({ mover: "BLACK", deadPositionAfter: true, materialAfter: { white: 3, black: 3 } })),
    { winner: "BLACK", loser: "WHITE", reason: "DEAD_POSITION_MATERIAL" },
  );
});

test("the 50-move material and tie-break policies match RulesContractV1", () => {
  assert.deepEqual(
    adjudicateDrawless(facts({ halfmoveClockAfter: 100, materialAfter: { white: 8, black: 7 } })),
    { winner: "WHITE", loser: "BLACK", reason: "FIFTY_MOVE_LIMIT" },
  );
  assert.deepEqual(
    adjudicateDrawless(facts({ mover: "BLACK", halfmoveClockAfter: 100, lastCaptureBy: "WHITE" })),
    { winner: "WHITE", loser: "BLACK", reason: "FIFTY_MOVE_LIMIT" },
  );
  assert.deepEqual(
    adjudicateDrawless(facts({
      mover: "BLACK",
      halfmoveClockAfter: 100,
      fiftyMoveAvoidingAlternativesBeforeMove: 0,
    })),
    { winner: "BLACK", loser: "WHITE", reason: "FIFTY_MOVE_LIMIT" },
  );
  assert.deepEqual(
    adjudicateDrawless(facts({ mover: "BLACK", halfmoveClockAfter: 100 })),
    { winner: "WHITE", loser: "BLACK", reason: "FIFTY_MOVE_LIMIT" },
  );
});

test("terminal precedence matches the Android adjudicator", () => {
  assert.equal(
    adjudicateDrawless(facts({
      legalMovesAfter: 0,
      positionOccurrenceCount: 3,
      materialAfter: { white: 0, black: 9 },
      deadPositionAfter: true,
      halfmoveClockAfter: 100,
    }))?.reason,
    "STALEMATE",
  );
  assert.equal(
    adjudicateDrawless(facts({
      positionOccurrenceCount: 3,
      materialAfter: { white: 0, black: 9 },
      deadPositionAfter: true,
      halfmoveClockAfter: 100,
    }))?.reason,
    "REPETITION",
  );
});

test("resignation and result copy identify the winner", () => {
  const outcome = resignationOutcome("WHITE");
  assert.deepEqual(outcome, { winner: "BLACK", loser: "WHITE", reason: "RESIGNATION" });
  assert.equal(outcomeText(outcome), "Black wins by resignation.");
});

test("checkmate opens a prominent accessible result takeover", async () => {
  const component = await readFile(new URL("../app/play/play-game.tsx", import.meta.url), "utf8");
  const styles = await readFile(
    new URL("../app/play/result-celebration.module.css", import.meta.url),
    "utf8",
  );

  assert.match(component, /role="dialog"/);
  assert.match(component, /aria-modal="true"/);
  assert.match(component, /aria-labelledby="web-result-heading"/);
  assert.match(component, /humanWon \? "Victory" : "Defeat"/);
  assert.match(component, /resultReasonLabel\(outcome\.reason\)/);
  assert.match(component, /Play again/);
  assert.match(component, /View final board/);
  assert.match(styles, /\.backdrop\s*\{[\s\S]*?position:\s*fixed/);
  assert.match(styles, /\.headline\s*\{[\s\S]*?font-size:\s*clamp\(3\.7rem, 14vw, 8\.6rem\)/);
  assert.match(styles, /@media \(prefers-reduced-motion: reduce\)/);
});
