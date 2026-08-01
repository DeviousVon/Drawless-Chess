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

  async search(position, depth, { newGame = true, clearHash = true, hash = null, searchMoves = [] } = {}) {
    if (newGame) this.send("ucinewgame");
    if (hash !== null) this.send(`setoption name Hash value ${hash}`);
    if (clearHash) this.send("setoption name Clear Hash");
    await this.ready();

    const from = this.lines.length;
    this.send(position);
    const constrained = searchMoves.length ? ` searchmoves ${searchMoves.join(" ")}` : "";
    this.send(`go depth ${depth}${constrained}`);
    const best = await this.waitFor((line) => line.startsWith("bestmove "), from, 20_000);
    const output = this.lines.slice(from, best.index + 1);
    const scoreLines = output.filter((line) => line.startsWith("info depth ") && line.includes(" score "));
    return {
      bestMove: best.line.split(/\s+/)[1],
      output,
      finalScore: scoreLines.at(-1) ?? "",
    };
  }

  async setPolicies({
    variant = "drawless",
    deadPosition = "material-victory",
    fiftyMove = "material-victory",
    bareKing = "bare-king-loses",
  } = {}) {
    this.send(`setoption name UCI_Variant value ${variant}`);
    this.send(`setoption name Drawless Dead Position value ${deadPosition}`);
    this.send(`setoption name Drawless Fifty Move value ${fiftyMove}`);
    this.send(`setoption name Drawless Bare King value ${bareKing}`);
    await this.ready();
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
const repetitionBeatsFiftyBlack = forcedBlack.replace(" 0 1 moves", " 92 1 moves");
const repetitionBeatsFiftyWhite = forcedWhite.replace(" 0 1 moves", " 92 1 moves");
const pinnedEpRepetition = "position fen k3r1n1/3p4/8/4P3/8/8/8/4K1N1 b - - 0 1 moves d7d5 g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 f3g1 f6g8";
const legalEpDistinctHistory = "position fen k5n1/3p4/8/4P3/8/8/8/4K1N1 b - - 0 1 moves d7d5 g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 f3g1 f6g8";
const mixedTerminalAllLoss = "position fen 7k/8/8/7b/8/8/pKb5/8 b - - 0 1 moves c2d3 b2a1 d3c2 a1b2 c2d3 b2a1";
const quietStalemateBeyondMaterialGateBlack = "position fen 1k6/8/1PK2N2/8/8/8/4PP2/8 b - - 0 1";
const quietStalemateBeyondMaterialGateWhite = "position fen 8/2pp4/8/8/8/2n2kp1/8/6K1 w - - 0 1";

const lastCaptureTieMoves = `a2a3 g7a7 a1a2 a7a4 a2a1 a4a5 a1a2 a5a4 a2a1 a4a5 a1a2 a5a6 a2a1 a6a4 a1a2 a4a5 a2a1 a5a4 a1a2 a4a5 a2a1 a5a6 a1a2 a6a7 a2a1 a7a6 a1a2 a6a7 a2a1 a7a8 a1a2 a8a6 a2a1 a6a7 a1a2 a7a8 a2a1 a8b8 a1a2 b8a8 a2a1 a8b8 a1a2 b8b3 a2a1 b3b4 a1a2 b4b3 a2a1 b3b4 a1a2 b4b5 a2a1 b5b3 a1a2 b3b4 a2a1 b4b3 a1a2 b3b4 a2a1 b4b5 a1a2 b5b6 a2a1 b6b5 a1a2 b5b6 a2a1 b6b7 a1a2 b7b5 a2a1 b5b6 a1a2 b6b7 a2a1 b7b6 a1a2 b6b7 a2a1 b7c7 a1a2 c7c1 a2b3 c1a1 a3a2 a1b1 a2a1 b1c1 a1a2 c1a1 a2a3 a1a2 a3a4 a2a1 a4a2 a1b1 a2a1 b1c1 a1a2`.split(" ");
const mirrorSquare = (square) => `${String.fromCharCode(104 - (square.charCodeAt(0) - 97))}${9 - Number(square[1])}`;
const mirrorMove = (move) => `${mirrorSquare(move.slice(0, 2))}${mirrorSquare(move.slice(2, 4))}${move.slice(4)}`;
const lastCaptureTieParent = `position fen 7k/6rr/8/8/8/b7/RR6/K7 w - - 0 1 moves ${lastCaptureTieMoves.slice(0, -1).join(" ")}`;
const lastCaptureTieParentMirrored = `position fen 7k/6rr/7B/8/8/8/RR6/K7 b - - 0 1 moves ${lastCaptureTieMoves.slice(0, -1).map(mirrorMove).join(" ")}`;

const mateScore = (search, score) => {
  assert.match(search.finalScore, new RegExp(` score mate ${score}(?: |$)`), search.output.join("\n"));
};

const mandatoryRootEnd = (search) => {
  // UCI's mate-distance encoding collapses both winner polarities at ply zero
  // to `mate 0`. The observable mandatory-root contract is therefore the
  // terminal score plus the absence of any playable best move.
  assert.equal(search.bestMove, "(none)", search.output.join("\n"));
  mateScore(search, 0);
};

const notImmediateMate = (search) => {
  assert.doesNotMatch(search.finalScore, / score mate -?1(?: |$)/, search.output.join("\n"));
};

const playableRoot = (search) => {
  assert.notEqual(search.bestMove, "(none)", search.output.join("\n"));
};

const engine = new UciProcess(binaryArg);
try {
  let from = engine.lines.length;
  engine.send("uci");
  await engine.waitFor((line) => line === "uciok", from);

  const patchIdentity = engine.lines.filter((line) => line.includes("Drawless Patch Version"));
  if (mode === "patched") {
    assert.deepEqual(patchIdentity, ["option name Drawless Patch Version type spin default 2 min 2 max 2"]);
    assert.ok(engine.lines.includes("option name Drawless Dead Position type combo default material-victory var material-victory var final-capture-victory"));
    assert.ok(engine.lines.includes("option name Drawless Fifty Move type combo default material-victory var disabled var completing-player-loses var forced-move-exception var material-victory"));
    assert.ok(engine.lines.includes("option name Drawless Bare King type combo default bare-king-loses var continue var bare-king-loses"));
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

    for (const hash of [1, 64]) {
      mateScore(await engine.search(forcedBlack, 4, { hash }), 1);
      const avoidableWithReusedHash = await engine.search(avoidableBlack, 6, { newGame: false, clearHash: false });
      assert.notEqual(avoidableWithReusedHash.bestMove, "f6g8", avoidableWithReusedHash.output.join("\n"));
      mateScore(await engine.search(forcedBlack, 4, { newGame: false, clearHash: false }), 1);
    }
    console.log("ok - forced/avoidable repetition ordering is stable with reused Hash 1 and 64");

    await engine.setPolicies({ bareKing: "continue", fiftyMove: "disabled" });
    mandatoryRootEnd(await engine.search(pinnedEpRepetition, 2));
    playableRoot(await engine.search(legalEpDistinctHistory, 2));
    console.log("ok - repetition keys discard only illegal en-passant targets");

    await engine.stopSearchAfterInfo("position startpos");
    const afterStop = await engine.search(forcedBlack, 4, { newGame: false, clearHash: false });
    assert.match(afterStop.finalScore, / score mate 1(?: |$)/, afterStop.output.join("\n"));
    console.log("ok - stopped search cannot contaminate the following request");

    await engine.setPolicies({ bareKing: "bare-king-loses", fiftyMove: "disabled" });
    mateScore(await engine.search("position fen 7k/8/8/8/8/8/Rr6/K7 w - - 0 1", 2, { searchMoves: ["a2b2"] }), 1);
    mateScore(await engine.search("position fen 7k/6Rr/8/8/8/8/8/K7 b - - 0 1", 2, { searchMoves: ["h7g7"] }), 1);
    mandatoryRootEnd(await engine.search("position fen 7k/8/8/8/8/8/R7/K7 b - - 0 1", 2));
    console.log("ok - a mandatory Drawless root terminal returns no playable bestmove");
    await engine.setPolicies({ bareKing: "continue", fiftyMove: "disabled" });
    notImmediateMate(await engine.search("position fen 7k/8/8/8/8/8/Rr6/K7 w - - 0 1", 1, { searchMoves: ["a2b2"] }));
    notImmediateMate(await engine.search("position fen 7k/6Rr/8/8/8/8/8/K7 b - - 0 1", 1, { searchMoves: ["h7g7"] }));
    console.log("ok - bare-king policy is searched for both colors");

    await engine.setPolicies({ bareKing: "bare-king-loses", fiftyMove: "disabled" });
    for (const depth of [1, 3]) {
      mateScore(await engine.search("position fen 7k/8/8/8/8/8/Rr6/K7 b - - 0 1", depth, { searchMoves: ["h8g8"] }), -1);
      mateScore(await engine.search("position fen 7k/6Rr/8/8/8/8/8/K7 w - - 0 1", depth, { searchMoves: ["a1b1"] }), -1);
    }
    console.log("ok - non-root bare captures survive main and quiescence pruning for both colors");

    for (const depth of [1, 3]) {
      mateScore(await engine.search("position fen 7k/6p1/8/8/8/8/6Q1/K7 b - - 0 1", depth, { searchMoves: ["h8g8"] }), -1);
      mateScore(await engine.search("position fen 7k/1q6/8/8/8/8/1P6/K7 w - - 0 1", depth, { searchMoves: ["a1b1"] }), -1);
    }
    console.log("ok - negative-SEE last-piece captures remain searchable for both colors");

    await engine.setPolicies({ bareKing: "continue", deadPosition: "final-capture-victory", fiftyMove: "disabled" });
    mateScore(await engine.search("position fen 7k/8/8/6b1/5R2/4B3/8/K1B5 b - - 0 1", 2, { searchMoves: ["g5f4"] }), 1);
    mateScore(await engine.search("position fen 5b1k/8/3b4/2r5/1B6/8/8/K7 w - - 0 1", 2, { searchMoves: ["b4c5"] }), 1);
    await engine.setPolicies({ bareKing: "continue", deadPosition: "material-victory", fiftyMove: "disabled" });
    mateScore(await engine.search("position fen 7k/8/8/6b1/5R2/4B3/8/K1B5 b - - 0 1", 2, { searchMoves: ["g5f4"] }), -1);
    mateScore(await engine.search("position fen 5b1k/8/3b4/2r5/1B6/8/8/K7 w - - 0 1", 2, { searchMoves: ["b4c5"] }), -1);
    console.log("ok - both known-dead policies use the exact mover/material result for both colors");

    await engine.setPolicies({ bareKing: "continue", deadPosition: "final-capture-victory", fiftyMove: "disabled" });
    for (const depth of [1, 3]) {
      mateScore(await engine.search("position fen 7k/8/8/6b1/5R2/4B3/8/K1B5 w - - 0 1", depth, { searchMoves: ["a1a2"] }), -1);
      mateScore(await engine.search("position fen 5b1k/8/3b4/2r5/1B6/8/8/K7 b - - 0 1", depth, { searchMoves: ["h8h7"] }), -1);
    }
    console.log("ok - non-root dead-position captures survive main and quiescence pruning for both colors");

    await engine.setPolicies({ bareKing: "continue", deadPosition: "material-victory", fiftyMove: "disabled" });
    mateScore(await engine.search(mixedTerminalAllLoss, 1, { searchMoves: ["d3c2"] }), 1);
    console.log("ok - qsearch treats mixed repetition/dead terminal replies as an authoritative all-loss set");

    await engine.setPolicies({ bareKing: "continue", deadPosition: "final-capture-victory", fiftyMove: "disabled" });
    mateScore(await engine.search("position fen 7k/1P6/2K5/8/8/b7/8/8 w - - 0 1", 2, { searchMoves: ["b7b8b"] }), 1);
    mateScore(await engine.search("position fen 8/8/7B/8/8/5k2/6p1/K7 b - - 0 1", 2, { searchMoves: ["g2g1b"] }), 1);
    mateScore(await engine.search("position fen 7k/1P6/2K5/8/8/8/8/8 w - - 0 1", 2, { searchMoves: ["b7b8n"] }), 1);
    mateScore(await engine.search("position fen 8/8/8/8/8/5k2/6p1/K7 b - - 0 1", 2, { searchMoves: ["g2g1n"] }), 1);
    console.log("ok - quiet bishop and knight underpromotions create both known-dead topologies for both colors");

    mateScore(await engine.search("position fen 7k/1P6/2K5/8/8/b7/8/8 b - - 0 1", 1, { searchMoves: ["h8g8"] }), -1);
    mateScore(await engine.search("position fen 8/8/7B/8/8/5k2/6p1/K7 w - - 0 1", 1, { searchMoves: ["a1b1"] }), -1);
    mateScore(await engine.search("position fen 7k/1P6/2K5/8/8/b7/8/8 b - - 0 1", 3, { searchMoves: ["h8g8"] }), -1);
    mateScore(await engine.search("position fen 8/8/7B/8/8/5k2/6p1/K7 w - - 0 1", 3, { searchMoves: ["a1b1"] }), -1);
    for (const depth of [1, 3]) {
      mateScore(await engine.search("position fen 7k/1P6/2K5/8/8/8/8/8 b - - 0 1", depth, { searchMoves: ["h8g8"] }), -1);
      mateScore(await engine.search("position fen 8/8/8/8/8/5k2/6p1/K7 w - - 0 1", depth, { searchMoves: ["a1b1"] }), -1);
    }
    console.log("ok - non-root bishop and knight underpromotions survive main search and qsearch omission for both colors");

    await engine.setPolicies({ bareKing: "bare-king-loses", deadPosition: "final-capture-victory", fiftyMove: "disabled" });
    mandatoryRootEnd(await engine.search("position fen 7k/8/8/8/8/8/2B5/K7 b - - 0 1", 2));
    mandatoryRootEnd(await engine.search("position fen 7k/5b2/8/8/8/8/8/K7 w - - 0 1", 2));
    console.log("ok - coincident bare-king and known-dead roots are mandatory for both colors");

    await engine.setPolicies({ bareKing: "bare-king-loses", fiftyMove: "forced-move-exception" });
    mandatoryRootEnd(await engine.search("position fen 7k/8/8/8/8/8/R7/K7 b - - 99 1", 2));
    mandatoryRootEnd(await engine.search("position fen 7k/7r/8/8/8/8/8/K7 w - - 99 1", 2));
    console.log("ok - bare-king roots stop before a 50-move transition for both colors");

    await engine.setPolicies({ bareKing: "continue", deadPosition: "final-capture-victory", fiftyMove: "completing-player-loses" });
    mandatoryRootEnd(await engine.search("position fen 5b1k/8/8/8/8/8/8/K1B5 w - - 99 1", 2));
    mandatoryRootEnd(await engine.search("position fen 5b1k/8/8/8/8/8/8/K1B5 b - - 99 1", 2));
    console.log("ok - known-dead roots stop before a 50-move transition for both colors");

    await engine.setPolicies({ bareKing: "continue", fiftyMove: "completing-player-loses" });
    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/R7/K7 w - - 99 1", 2, { searchMoves: ["a2b2"] }), -1);
    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/R7/K7 b - - 99 1", 2, { searchMoves: ["h7g7"] }), -1);
    await engine.setPolicies({ bareKing: "continue", fiftyMove: "forced-move-exception" });
    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/R7/K7 w - - 99 1", 2, { searchMoves: ["a2b2"] }), 1);
    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/R7/K7 b - - 99 1", 2, { searchMoves: ["h7g7"] }), 1);
    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/P6R/K7 w - - 99 1", 2, { searchMoves: ["h2h3"] }), -1);
    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/R6p/K7 b - - 99 1", 2, { searchMoves: ["h7g7"] }), -1);
    console.log("ok - 50-move completing loss and full-legal-set forced exception cover both colors");

    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/R7/K7 w - - 98 1", 1, { searchMoves: ["a2b2"] }), -1);
    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/R7/K7 b - - 98 1", 1, { searchMoves: ["h7g7"] }), -1);
    console.log("ok - quiescence searches quiet 50-move boundary wins for both colors");

    for (const { hash, at99, at98, move } of [
      { hash: 1, at99: "position fen 7k/7r/8/8/8/8/R7/K7 w - - 99 1", at98: "position fen 7k/7r/8/8/8/8/R7/K7 w - - 98 1", move: "a2b2" },
      { hash: 64, at99: "position fen 7k/7r/8/8/8/8/R7/K7 b - - 99 1", at98: "position fen 7k/7r/8/8/8/8/R7/K7 b - - 98 1", move: "h7g7" },
    ]) {
      mateScore(await engine.search(at99, 2, { hash, searchMoves: [move] }), 1);
      mateScore(await engine.search(at98, 1, { newGame: false, clearHash: false, searchMoves: [move] }), -1);
      mateScore(await engine.search(at99, 2, { newGame: false, clearHash: false, searchMoves: [move] }), 1);
    }
    console.log("ok - rule-98/rule-99 history cannot leak through reused Hash 1 or 64");

    await engine.setPolicies({ bareKing: "continue", fiftyMove: "material-victory" });
    mateScore(await engine.search("position fen 7k/7b/8/8/8/8/R7/K7 w - - 99 1", 2, { searchMoves: ["a2b2"] }), 1);
    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/B7/K7 b - - 99 1", 2, { searchMoves: ["h7g7"] }), 1);
    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/R7/K7 w - - 99 1", 2, { searchMoves: ["a2b2"] }), 1);
    mateScore(await engine.search("position fen 7k/7r/8/8/8/8/R7/K7 b - - 99 1", 2, { searchMoves: ["h7g7"] }), 1);
    mateScore(await engine.search(lastCaptureTieParent, 2, { searchMoves: [lastCaptureTieMoves.at(-1)] }), 1);
    mateScore(await engine.search(lastCaptureTieParentMirrored, 2, { searchMoves: [mirrorMove(lastCaptureTieMoves.at(-1))] }), 1);
    console.log("ok - 50-move material advantage, forced tie, and last-capturer tie cover both colors");

    await engine.setPolicies({ variant: "drawless", bareKing: "continue", fiftyMove: "disabled" });
    mateScore(await engine.search("position fen k7/2Q5/2K5/8/8/8/8/8 w - - 0 1", 1, { searchMoves: ["c7b6"] }), 1);
    mateScore(await engine.search("position fen 8/8/8/8/8/5k2/5q2/7K b - - 0 1", 1, { searchMoves: ["f2g3"] }), 1);
    await engine.setPolicies({ variant: "escape", bareKing: "continue", fiftyMove: "disabled" });
    mateScore(await engine.search("position fen k7/2Q5/2K5/8/8/8/8/8 w - - 0 1", 1, { searchMoves: ["c7b6"] }), -1);
    mateScore(await engine.search("position fen 8/8/8/8/8/5k2/5q2/7K b - - 0 1", 1, { searchMoves: ["f2g3"] }), -1);
    console.log("ok - ordinary quiescence recognizes stalemate for both presets and colors");

    await engine.setPolicies({ variant: "drawless", bareKing: "continue", fiftyMove: "disabled" });
    for (const depth of [1, 3]) {
      mateScore(await engine.search(quietStalemateBeyondMaterialGateBlack, depth, { searchMoves: ["b8a8"] }), -1);
      mateScore(await engine.search(quietStalemateBeyondMaterialGateWhite, depth, { searchMoves: ["g1h1"] }), -1);
    }
    await engine.setPolicies({ variant: "escape", bareKing: "continue", fiftyMove: "disabled" });
    for (const depth of [1, 3]) {
      notImmediateMate(await engine.search(quietStalemateBeyondMaterialGateBlack, depth, { searchMoves: ["b8a8"] }));
      notImmediateMate(await engine.search(quietStalemateBeyondMaterialGateWhite, depth, { searchMoves: ["g1h1"] }));
    }
    for (const { variant, expected } of [{ variant: "drawless", expected: 1 }, { variant: "escape", expected: -1 }]) {
      await engine.setPolicies({ variant, bareKing: "continue", fiftyMove: "disabled" });
      mateScore(await engine.search(`${quietStalemateBeyondMaterialGateBlack} moves b8a8`, 1, { searchMoves: ["f6d7"] }), expected);
      mateScore(await engine.search(`${quietStalemateBeyondMaterialGateWhite} moves g1h1`, 1, { searchMoves: ["c3e2"] }), expected);
    }
    console.log("ok - quiet stalemates beyond the material frontier survive Drawless pruning and Escape avoids them for both colors");

    await engine.setPolicies({ variant: "drawless", bareKing: "continue", fiftyMove: "material-victory" });
    mateScore(await engine.search("position fen k7/2Q5/2K5/8/8/8/8/8 w - - 99 1", 2, { searchMoves: ["c7b6"] }), 1);
    mateScore(await engine.search("position fen 8/8/8/8/8/5k2/5q2/7K b - - 99 1", 2, { searchMoves: ["f2g3"] }), 1);
    await engine.setPolicies({ variant: "escape", bareKing: "continue", fiftyMove: "material-victory" });
    mateScore(await engine.search("position fen k7/2Q5/2K5/8/8/8/8/8 w - - 99 1", 2, { searchMoves: ["c7b6"] }), -1);
    mateScore(await engine.search("position fen 8/8/8/8/8/5k2/5q2/7K b - - 99 1", 2, { searchMoves: ["f2g3"] }), -1);
    console.log("ok - stalemate precedes the conflicting 50-move result for both presets and colors");

    await engine.setPolicies({ variant: "drawless", bareKing: "continue", fiftyMove: "completing-player-loses" });
    mateScore(await engine.search(repetitionBeatsFiftyBlack, 4), 1);
    mateScore(await engine.search(repetitionBeatsFiftyWhite, 4), 1);
    console.log("ok - forced repetition precedes the conflicting 50-move loss for both colors");

    await engine.setPolicies({ bareKing: "continue", fiftyMove: "disabled" });
    notImmediateMate(await engine.search("position fen 7k/7r/8/8/8/8/R7/K7 w - - 99 1", 1, { searchMoves: ["a2b2"] }));
    notImmediateMate(await engine.search("position fen 7k/7r/8/8/8/8/R7/K7 b - - 99 1", 1, { searchMoves: ["h7g7"] }));
    console.log("ok - disabled 50-move policy remains non-terminal for both colors");
  }
} finally {
  await engine.close();
}
