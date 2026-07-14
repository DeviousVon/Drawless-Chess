import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const sourcePath = process.argv[2];
assert.ok(sourcePath, "usage: node verify-elo-rounding.mjs /path/to/search.cpp");

const source = readFileSync(sourcePath, "utf8");
assert.match(source, /int lowerLevel = int\(std::floor\(floatLevel\)\);/);
assert.match(source, /\(floatLevel - lowerLevel\) \* 1024/);
assert.doesNotMatch(source, /floatLevel - int\(floatLevel\)/);

function fractionalSkillForElo(elo) {
  const shiftedElo = elo - 1346.6;
  const raw = shiftedElo > 0
    ? Math.pow(shiftedElo / 143.4, 1 / 0.806)
    : shiftedElo / 143.4 + Math.pow(shiftedElo / 500, 5);
  return Math.max(-20, Math.min(20, raw));
}

function roundedLevels(floatLevel) {
  const lower = Math.floor(floatLevel);
  return Array.from(
    { length: 1024 },
    (_, residue) => lower + ((floatLevel - lower) * 1024 > residue ? 1 : 0),
  );
}

function assertDistribution(floatLevel, expectedLevels) {
  const levels = roundedLevels(floatLevel);
  assert.deepEqual([...new Set(levels)].sort((a, b) => a - b), expectedLevels);
  const mean = levels.reduce((total, value) => total + value, 0) / levels.length;
  assert.ok(Math.abs(mean - floatLevel) <= 1 / 1024);
}

for (const exact of [-20, -3, 0, 20]) {
  assert.deepEqual([...new Set(roundedLevels(exact))], [exact]);
}

const elo900Skill = fractionalSkillForElo(900);
assert.ok(Math.abs(elo900Skill - (-3.68288256)) < 1e-8);
assertDistribution(elo900Skill, [-4, -3]);
assertDistribution(3.25, [3, 4]);

console.log("ok - negative and positive fractional skill levels round without bias");
