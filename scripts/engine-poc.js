import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
import fs from "node:fs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function startEngine() {
  const child = spawn(process.execPath, [path.join(root, "scripts/fairy-uci.cjs")], {
    cwd: root,
    env: { ...process.env, DRAWLESS_VARIANTS: path.join(root, "engine/variants.ini") },
    stdio: ["pipe", "pipe", "pipe"],
  });
  let output = "";
  child.stdout.on("data", (chunk) => { output += chunk; });
  child.stderr.on("data", (chunk) => { output += chunk; });
  const send = (...commands) => child.stdin.write(`${commands.join("\n")}\n`);
  const waitFor = (pattern, timeout = 10000) => new Promise((resolve, reject) => {
    const started = Date.now();
    const poll = () => {
      if (pattern.test(output)) return resolve(output);
      if (child.exitCode !== null) return reject(new Error(`Engine exited ${child.exitCode}:\n${output}`));
      if (Date.now() - started > timeout) return reject(new Error(`Timed out waiting for ${pattern}:\n${output}`));
      setTimeout(poll, 20);
    };
    poll();
  });
  const waitForAfter = (offset, pattern, timeout = 10000) => new Promise((resolve, reject) => {
    const started = Date.now();
    const poll = () => {
      const fresh = output.slice(offset);
      if (pattern.test(fresh)) return resolve(fresh);
      if (child.exitCode !== null) return reject(new Error(`Engine exited ${child.exitCode}:\n${fresh}`));
      if (Date.now() - started > timeout) return reject(new Error(`Timed out waiting for ${pattern}:\n${fresh}`));
      setTimeout(poll, 20);
    };
    poll();
  });
  return { child, send, waitFor, waitForAfter, output: () => output };
}

async function runSearch(engine, positionCommand, variant) {
  const readyOffset = engine.output().length;
  engine.send(
    `setoption name UCI_Variant value ${variant}`,
    "setoption name Use NNUE value false",
    "ucinewgame",
    "isready",
  );
  await engine.waitForAfter(readyOffset, /readyok/);
  const positionOffset = engine.output().length;
  engine.send(positionCommand, "isready");
  await engine.waitForAfter(positionOffset, /readyok/);
  const before = engine.output().length;
  engine.send("go depth 2");
  const fresh = await engine.waitForAfter(before, /bestmove/);
  return { output: fresh };
}

async function main() {
  const e = startEngine();
  try {
    e.send("uci");
    const uci = await e.waitFor(/uciok/);
    assert.match(uci, /var drawless/);
    assert.match(uci, /var escape/);

    const parity = JSON.parse(fs.readFileSync(path.join(root, "engine/parity-fixtures-v1.json"), "utf8"));
    const current = parity.fixtures.filter((fixture) => fixture.gate === "current-poc");
    for (const fixture of current) {
      const rootPosition = fixture.initialFen === "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        ? "position startpos"
        : `position fen ${fixture.initialFen}`;
      const position = fixture.moves.length === 0
        ? rootPosition
        : `${rootPosition} moves ${fixture.moves.join(" ")}`;
      const result = await runSearch(e, position, fixture.variant);
      if (Object.hasOwn(fixture.expected, "bestMove") && fixture.expected.bestMove === null) {
        assert.match(result.output, /bestmove (?:\(none\)|0000)/, fixture.id);
      }
      for (const forbidden of fixture.expected.forbiddenBestMoves ?? []) {
        assert.doesNotMatch(result.output, new RegExp(`bestmove ${forbidden}(?:\\s|$)`), fixture.id);
      }
      console.log(`PASSED engine parity fixture: ${fixture.id}`);
    }
    console.log("SKIPPED forced-repetition fixtures in this unpatched WASM lane (covered by the native patch-v1 verifier)");
  } finally {
    e.send("quit");
    e.child.stdin.end();
  }
}

main().catch((error) => {
  console.error(error.stack || error);
  process.exitCode = 1;
});
