#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { resolve } from "node:path";

const [binaryArg, variantsArg, mode = "patched"] = process.argv.slice(2);

if (!binaryArg || !variantsArg || !["patched", "unpatched"].includes(mode)) {
  console.error("usage: node verify-engine.mjs <binary> <variants.ini> [patched|unpatched]");
  process.exit(2);
}

class UciProcess {
  constructor(binary) {
    this.lines = [];
    this.waiters = new Set();
    this.process = spawn(resolve(binary), [], { stdio: ["pipe", "pipe", "pipe"] });
    this.stderr = "";
    this.process.stderr.setEncoding("utf8");
    this.process.stderr.on("data", (chunk) => { this.stderr += chunk; });

    const lines = createInterface({ input: this.process.stdout });
    lines.on("line", (line) => {
      this.lines.push(line.trim());
      for (const waiter of [...this.waiters]) waiter();
    });
  }

  send(command) {
    assert.equal(this.process.exitCode, null, `engine exited early: ${this.stderr}`);
    this.process.stdin.write(`${command}\n`);
  }

  waitFor(predicate, from = 0, timeoutMs = 10_000) {
    return new Promise((resolvePromise, reject) => {
      const scan = () => {
        const relative = this.lines.slice(from);
        const index = relative.findIndex(predicate);
        if (index < 0) return;
        cleanup();
        resolvePromise({ line: relative[index], index: from + index });
      };
      const timeout = setTimeout(() => {
        cleanup();
        reject(new Error(`UCI timeout. Recent output:\n${this.lines.slice(-30).join("\n")}\n${this.stderr}`));
      }, timeoutMs);
      const cleanup = () => {
        clearTimeout(timeout);
        this.waiters.delete(scan);
      };
      this.waiters.add(scan);
      scan();
    });
  }

  async ready() {
    const from = this.lines.length;
    this.send("isready");
    await this.waitFor((line) => line === "readyok", from);
  }

  async search(position, depth, { newGame = true, clearHash = true, hash = null } = {}) {
    if (newGame) this.send("ucinewgame");
    if (hash !== null) this.send(`setoption name Hash value ${hash}`);
    if (clearHash) this.send("setoption name Clear Hash");
    await this.ready();

    const from = this.lines.length;
    this.send(position);
    this.send(`go depth ${depth}`);
    const best = await this.waitFor((line) => line.startsWith("bestmove "), from, 20_000);
    const output = this.lines.slice(from, best.index + 1);
    const scoreLines = output.filter((line) => line.startsWith("info depth ") && line.includes(" score "));
    return {
      bestMove: best.line.split(/\s+/)[1],
      output,
      finalScore: scoreLines.at(-1) ?? "",
    };
  }

  async stopSearchAfterInfo(position) {
    this.send("ucinewgame");
    await this.ready();
    const from = this.lines.length;
    this.send(position);
    this.send("go infinite");
    await this.waitFor((line) => /^info depth [2-9]/.test(line), from);
    this.send("stop");
    await this.waitFor((line) => line.startsWith("bestmove "), from);
  }

  async close() {
    if (this.process.exitCode !== null) return;
    this.send("quit");
    await new Promise((resolvePromise, reject) => {
      const timeout = setTimeout(() => {
        this.process.kill("SIGKILL");
        reject(new Error("engine did not exit after quit"));
      }, 3_000);
      this.process.once("exit", (code) => {
        clearTimeout(timeout);
        code === 0 ? resolvePromise() : reject(new Error(`engine exited ${code}: ${this.stderr}`));
      });
    });
  }
}

const forcedBlack = "position fen 6k1/7p/5Q2/8/8/8/8/6K1 w - - 0 1 moves f6f7 g8h8 f7f6 h8g8 f6f7 g8h8 f7f6";
const sameBoardShortHistory = "position fen 6k1/7p/5Q2/8/8/8/8/6K1 w - - 0 1 moves f6f7 g8h8 f7f6";
const forcedWhite = "position fen 1k6/8/8/8/8/2q5/P7/1K6 b - - 0 1 moves c3c2 b1a1 c2c3 a1b1 c3c2 b1a1 c2c3";
const avoidableBlack = "position startpos moves g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 f3g1";
const avoidableWhite = "position fen rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1 moves g8f6 f3g1 f6g8 g1f3 g8f6 f3g1 f6g8";

const engine = new UciProcess(binaryArg);
try {
  let from = engine.lines.length;
  engine.send("uci");
  await engine.waitFor((line) => line === "uciok", from);

  const patchIdentity = engine.lines.filter((line) => line.includes("Drawless Patch Version"));
  if (mode === "patched") {
    assert.deepEqual(patchIdentity, ["option name Drawless Patch Version type spin default 1 min 1 max 1"]);
  } else {
    assert.deepEqual(patchIdentity, []);
  }

  engine.send(`setoption name VariantPath value ${resolve(variantsArg)}`);
  engine.send("setoption name UCI_Variant value drawless");
  engine.send("setoption name Threads value 1");
  engine.send("setoption name Skill Level value 20");
  await engine.ready();

  const forced = await engine.search(forcedBlack, 4, { hash: 1 });
  assert.equal(forced.bestMove, "h8g8");

  if (mode === "unpatched") {
    assert.match(forced.finalScore, / score mate -1(?: |$)/, forced.output.join("\n"));
    console.log("ok - canonical baseline scores the forced fixture as mate -1");
  } else {
    assert.match(forced.finalScore, / score mate 1(?: |$)/, forced.output.join("\n"));
    console.log("ok - forced Black completer wins with mate +1 (Hash 1)");

    const evasionBlack = await engine.search(avoidableBlack, 6);
    assert.notEqual(evasionBlack.bestMove, "f6g8", evasionBlack.output.join("\n"));
    console.log("ok - Black avoids an optional losing third occurrence");

    const evasionWhite = await engine.search(avoidableWhite, 6);
    assert.notEqual(evasionWhite.bestMove, "g1f3", evasionWhite.output.join("\n"));
    console.log("ok - White avoids an optional losing third occurrence");

    const mirrored = await engine.search(forcedWhite, 4, { hash: 64 });
    assert.equal(mirrored.bestMove, "a1b1");
    assert.match(mirrored.finalScore, / score mate 1(?: |$)/, mirrored.output.join("\n"));
    console.log("ok - forced White completer wins with mate +1 (Hash 64)");

    const primed = await engine.search(forcedBlack, 4, { newGame: false, clearHash: false });
    assert.match(primed.finalScore, / score mate 1(?: |$)/, primed.output.join("\n"));
    const shortHistory = await engine.search(sameBoardShortHistory, 4, { newGame: false, clearHash: false });
    assert.doesNotMatch(shortHistory.finalScore, / score mate 1(?: |$)/, shortHistory.output.join("\n"));
    const forcedAgain = await engine.search(forcedBlack, 4, { newGame: false, clearHash: false });
    assert.match(forcedAgain.finalScore, / score mate 1(?: |$)/, forcedAgain.output.join("\n"));
    console.log("ok - identical boards with different histories do not leak TT scores");

    await engine.stopSearchAfterInfo("position startpos");
    const afterStop = await engine.search(forcedBlack, 4, { newGame: false, clearHash: false });
    assert.match(afterStop.finalScore, / score mate 1(?: |$)/, afterStop.output.join("\n"));
    console.log("ok - stopped search cannot contaminate the following request");
  }
} finally {
  await engine.close();
}
