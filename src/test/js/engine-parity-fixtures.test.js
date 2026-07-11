import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

const fixtures = JSON.parse(fs.readFileSync("engine/parity-fixtures-v1.json", "utf8"));
const uciMove = /^[a-h][1-8][a-h][1-8][qrbn]?$/;

test("engine parity fixture contract is versioned and uniquely identified", () => {
  assert.equal(fixtures.schemaVersion, 1);
  assert.ok(Array.isArray(fixtures.fixtures) && fixtures.fixtures.length >= 7);
  const ids = fixtures.fixtures.map((fixture) => fixture.id);
  assert.equal(new Set(ids).size, ids.length);
});

test("engine parity fixtures use supported variants and valid UCI histories", () => {
  for (const fixture of fixtures.fixtures) {
    assert.match(fixture.id, /^[a-z][a-z0-9-]*$/);
    assert.ok(["drawless", "escape"].includes(fixture.variant));
    assert.ok(fixture.initialFen.split(/\s+/).length === 6);
    assert.ok(fixture.moves.every((move) => uciMove.test(move)));
    assert.ok(fixture.expected && typeof fixture.expected === "object");
  }
});

test("parity suite covers repetition loss for both completing colors", () => {
  const black = fixtures.fixtures.find((fixture) => fixture.id === "avoidable-third-repetition");
  const white = fixtures.fixtures.find((fixture) => fixture.id === "avoidable-third-repetition-white");
  assert.equal(black.expected.loserIfPlayed, "BLACK");
  assert.equal(white.expected.loserIfPlayed, "WHITE");
});

test("parity suite reserves the forced-repetition fixture for patched search", () => {
  const forced = fixtures.fixtures.find((fixture) => fixture.id === "forced-third-repetition-exception");
  const mirrored = fixtures.fixtures.find((fixture) => fixture.id === "forced-third-repetition-exception-white");
  assert.equal(forced.gate, "drawless-patch-v1");
  assert.equal(forced.expected.onlyLegalMove, "h8g8");
  assert.equal(forced.expected.winner, "BLACK");
  assert.equal(mirrored.gate, "drawless-patch-v1");
  assert.equal(mirrored.expected.onlyLegalMove, "a1b1");
  assert.equal(mirrored.expected.winner, "WHITE");
});
