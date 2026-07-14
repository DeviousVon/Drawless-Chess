import test from "node:test";
import assert from "node:assert/strict";
import { validateRulesV1, validateSavedGameV1 } from "../../main/js/contracts.js";

const rules = () => ({
  schemaVersion: 1,
  preset: "drawless",
  stalemate: "trapped_player_loses",
  repetition: { threshold: 3, completingPlayerLoses: true, forcedMoveException: true },
  deadPosition: "material_victory",
  fiftyMove: "disabled",
  materialValues: { pawn: 1, knight: 3, bishop: 3, rook: 5, queen: 9 },
});

const savedGame = () => ({
  schemaVersion: 1,
  gameId: "game-001",
  createdAt: "2026-07-09T20:30:00Z",
  mode: "rated",
  initialFen: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
  rules: rules(),
  timeControl: { kind: "clock", initialMs: 300000, incrementMs: 0 },
  moves: [{ uci: "g1f3", whiteRemainingMs: 298000, blackRemainingMs: 300000 }],
  engine: { id: "fairy-stockfish", build: "5589ea54", drawlessPatch: 0 },
  assistance: { hints: 0, undos: 0, pauses: 0 },
});

test("accepts the immutable rules v1 contract", () => assert.equal(validateRulesV1(rules()), true));
test("rejects a changed repetition threshold in v1", () => {
  const value = rules(); value.repetition.threshold = 4;
  assert.equal(validateRulesV1(value), false);
});
test("rejects unknown rules fields to prevent silent drift", () => {
  const value = rules(); value.drawOffers = true;
  assert.equal(validateRulesV1(value), false);
});
test("accepts a replayable rated game", () => assert.equal(validateSavedGameV1(savedGame()), true));
test("rejects hints in rated games", () => {
  const value = savedGame(); value.assistance.hints = 1;
  assert.equal(validateSavedGameV1(value), false);
});
test("accepts legacy assistance without threat indication", () => {
  const value = savedGame();
  assert.equal(Object.hasOwn(value.assistance, "threatIndication"), false);
  assert.equal(validateSavedGameV1(value), true);
});
test("accepts boolean threat indication in casual games", () => {
  for (const threatIndication of [true, false]) {
    const value = savedGame();
    value.mode = "casual";
    value.assistance.threatIndication = threatIndication;
    assert.equal(validateSavedGameV1(value), true);
  }
});
test("rejects threat indication in rated games", () => {
  const value = savedGame(); value.assistance.threatIndication = true;
  assert.equal(validateSavedGameV1(value), false);
});
test("rejects malformed threat indication types", () => {
  for (const threatIndication of ["true", 1, null]) {
    const value = savedGame(); value.mode = "casual";
    value.assistance.threatIndication = threatIndication;
    assert.equal(validateSavedGameV1(value), false);
  }
});
test("rejects unknown assistance fields", () => {
  const value = savedGame(); value.mode = "casual";
  value.assistance.futureAssistance = false;
  assert.equal(validateSavedGameV1(value), false);
});
test("accepts assistance in casual games", () => {
  const value = savedGame(); value.mode = "casual"; value.assistance = { hints: 2, undos: 1, pauses: 3 };
  assert.equal(validateSavedGameV1(value), true);
});
test("rejects invalid UCI replay data", () => {
  const value = savedGame(); value.moves[0].uci = "knight-f3";
  assert.equal(validateSavedGameV1(value), false);
});
test("rejects timed fields on an untimed game", () => {
  const value = savedGame(); value.timeControl = { kind: "untimed", initialMs: 1 };
  assert.equal(validateSavedGameV1(value), false);
});
test("accepts a completed decisive game", () => {
  const value = savedGame(); value.result = { winner: "WHITE", reason: "REPETITION", atPly: 1 };
  assert.equal(validateSavedGameV1(value), true);
});
test("rejects a result beyond saved replay history", () => {
  const value = savedGame(); value.result = { winner: "WHITE", reason: "CHECKMATE", atPly: 2 };
  assert.equal(validateSavedGameV1(value), false);
});
