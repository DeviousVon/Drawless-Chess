import type { Board, FairyStockfish } from "ffish-es6";
import {
  adjudicateDrawless,
  type GameOutcome,
  type MaterialScore,
  type PositionFacts,
  type Side,
} from "./drawless-rules";

export const START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
const WASM_URL = "/game/ffish-0.7.9.wasm";

export type PieceCode = "P" | "N" | "B" | "R" | "Q" | "K" | "p" | "n" | "b" | "r" | "q" | "k";

export interface PieceOnSquare {
  square: string;
  piece: PieceCode;
}

export interface MoveEntry {
  uci: string;
  san: string;
  mover: Side;
}

export interface GameSnapshot {
  gameId: string;
  revision: number;
  fen: string;
  sideToMove: Side;
  pieces: PieceOnSquare[];
  legalMoves: string[];
  inCheck: boolean;
  lastMove: string | null;
  moves: MoveEntry[];
  outcome: GameOutcome | null;
}

export interface OpponentCandidate {
  move: string;
  score: number;
  terminal: "WIN" | "LOSS" | null;
}

let modulePromise: Promise<FairyStockfish> | null = null;

export function loadChessModule(): Promise<FairyStockfish> {
  modulePromise ??= import("ffish-es6").then(async ({ default: Module }) => Module({
    locateFile: (file) => file.endsWith(".wasm") ? WASM_URL : file,
    print: () => undefined,
    printErr: (text) => console.warn(`[ffish] ${text}`),
  }));
  return modulePromise;
}

function sideFromTurn(whiteToMove: boolean): Side {
  return whiteToMove ? "WHITE" : "BLACK";
}

function splitMoves(value: string): string[] {
  const trimmed = value.trim();
  return trimmed ? trimmed.split(/\s+/) : [];
}

export function piecesFromFen(fen: string): PieceOnSquare[] {
  const placement = fen.trim().split(/\s+/)[0];
  const pieces: PieceOnSquare[] = [];
  placement.split("/").forEach((rankText, rankIndex) => {
    let file = 0;
    for (const char of rankText) {
      if (/\d/.test(char)) {
        file += Number(char);
      } else {
        const rank = 8 - rankIndex;
        pieces.push({ square: `${String.fromCharCode(97 + file)}${rank}`, piece: char as PieceCode });
        file += 1;
      }
    }
  });
  return pieces;
}

export function materialFromFen(fen: string): MaterialScore {
  const values: Record<string, number> = { p: 1, n: 3, b: 3, r: 5, q: 9, k: 0 };
  return piecesFromFen(fen).reduce<MaterialScore>((score, { piece }) => {
    const value = values[piece.toLowerCase()];
    if (piece === piece.toUpperCase()) score.white += value;
    else score.black += value;
    return score;
  }, { white: 0, black: 0 });
}

export function isKnownDeadFen(fen: string): boolean {
  const nonKings = piecesFromFen(fen).filter(({ piece }) => piece.toLowerCase() !== "k");
  if (nonKings.length === 0) return true;
  if (nonKings.some(({ piece }) => "prq".includes(piece.toLowerCase()))) return false;
  if (nonKings.length === 1) return true;
  if (nonKings.every(({ piece }) => piece.toLowerCase() === "b")) {
    const colors = nonKings.map(({ square }) => {
      const file = square.charCodeAt(0) - 97;
      const rank = Number(square[1]) - 1;
      return (file + rank) % 2;
    });
    return new Set(colors).size === 1;
  }
  return false;
}

function repetitionKey(board: Board): string {
  const [placement, side, castling, rawEnPassant] = board.fen().split(/\s+/);
  let enPassant = "-";
  if (rawEnPassant !== "-") {
    const targetFile = rawEnPassant[0];
    const hasLegalCapture = splitMoves(board.legalMoves()).some((move) =>
      move.slice(2, 4) === rawEnPassant && move[0] !== targetFile,
    );
    if (hasLegalCapture) enPassant = rawEnPassant;
  }
  return `${placement} ${side} ${castling} ${enPassant}`;
}

function cloneBoard(module: FairyStockfish, fen: string): Board {
  return new module.Board("chess", fen);
}

function evaluateMaterialFor(side: Side, material: MaterialScore): number {
  const difference = material.white - material.black;
  return side === "WHITE" ? difference : -difference;
}

export class BrowserGame {
  private readonly module: FairyStockfish;
  private board: Board;
  private readonly history = new Map<string, number>();
  private lastCaptureBy: Side | null = null;
  private moveEntries: MoveEntry[] = [];
  private currentOutcome: GameOutcome | null = null;
  private currentLastMove: string | null = null;
  private currentGameId = "";

  constructor(module: FairyStockfish) {
    this.module = module;
    this.board = cloneBoard(module, START_FEN);
    this.reset();
  }

  reset(): GameSnapshot {
    this.board.delete();
    this.board = cloneBoard(this.module, START_FEN);
    this.history.clear();
    this.history.set(repetitionKey(this.board), 1);
    this.lastCaptureBy = null;
    this.moveEntries = [];
    this.currentOutcome = null;
    this.currentLastMove = null;
    this.currentGameId = globalThis.crypto?.randomUUID?.() ?? `web-${Date.now()}-${Math.random()}`;
    return this.snapshot();
  }

  dispose(): void {
    this.board.delete();
  }

  snapshot(): GameSnapshot {
    const fen = this.board.fen();
    return {
      gameId: this.currentGameId,
      revision: this.moveEntries.length,
      fen,
      sideToMove: sideFromTurn(this.board.turn()),
      pieces: piecesFromFen(fen),
      legalMoves: this.currentOutcome ? [] : splitMoves(this.board.legalMoves()),
      inCheck: this.board.isCheck(),
      lastMove: this.currentLastMove,
      moves: [...this.moveEntries],
      outcome: this.currentOutcome,
    };
  }

  resign(side: Side): GameSnapshot {
    if (!this.currentOutcome) {
      this.currentOutcome = { winner: side === "WHITE" ? "BLACK" : "WHITE", loser: side, reason: "RESIGNATION" };
    }
    return this.snapshot();
  }

  apply(move: string): GameSnapshot {
    if (this.currentOutcome) throw new Error("The game is already over.");
    const legalBefore = splitMoves(this.board.legalMoves());
    if (!legalBefore.includes(move)) throw new Error(`Illegal move: ${move}`);

    const mover = sideFromTurn(this.board.turn());
    const san = this.board.sanMove(move);
    const wasCapture = this.board.isCapture(move);
    const alternatives = legalBefore.map((candidate) => {
      if (!this.board.push(candidate)) throw new Error(`Could not preview legal move: ${candidate}`);
      const result = {
        key: repetitionKey(this.board),
        halfmove: this.board.halfmoveClock(),
      };
      this.board.pop();
      return result;
    });

    if (!this.board.push(move)) throw new Error(`Could not apply legal move: ${move}`);
    const afterKey = repetitionKey(this.board);
    const occurrenceAfter = (this.history.get(afterKey) ?? 0) + 1;
    const lastCaptureAfter = wasCapture ? mover : this.lastCaptureBy;
    const facts: PositionFacts = {
      mover,
      legalMovesAfter: this.board.numberLegalMoves(),
      sideToMoveInCheck: this.board.isCheck(),
      positionOccurrenceCount: occurrenceAfter,
      repetitionAvoidingAlternativesBeforeMove: alternatives.filter(({ key }) =>
        (this.history.get(key) ?? 0) + 1 < 3,
      ).length,
      halfmoveClockAfter: this.board.halfmoveClock(),
      fiftyMoveAvoidingAlternativesBeforeMove: alternatives.filter(({ halfmove }) => halfmove < 100).length,
      deadPositionAfter: isKnownDeadFen(this.board.fen()),
      materialAfter: materialFromFen(this.board.fen()),
      lastCaptureBy: lastCaptureAfter,
    };

    this.history.set(afterKey, occurrenceAfter);
    this.lastCaptureBy = lastCaptureAfter;
    this.moveEntries.push({ uci: move, san, mover });
    this.currentLastMove = move;
    this.currentOutcome = adjudicateDrawless(facts);
    return this.snapshot();
  }

  opponentCandidates(): OpponentCandidate[] {
    if (this.currentOutcome) return [];
    const mover = sideFromTurn(this.board.turn());
    const currentMaterial = materialFromFen(this.board.fen());
    return splitMoves(this.board.legalMoves()).map((move) => {
      const preview = new BrowserGamePreview(this.module, this.board, this.history, this.lastCaptureBy);
      const result = preview.evaluate(move);
      preview.dispose();
      const materialSwing = evaluateMaterialFor(mover, result.material) - evaluateMaterialFor(mover, currentMaterial);
      const terminal = result.outcome?.winner === mover ? "WIN" : result.outcome ? "LOSS" : null;
      return {
        move,
        terminal,
        score: terminal === "WIN" ? 10000 : terminal === "LOSS" ? -10000 : materialSwing * 100 + result.mobility,
      };
    });
  }
}

class BrowserGamePreview {
  private readonly board: Board;

  constructor(
    module: FairyStockfish,
    source: Board,
    private readonly history: ReadonlyMap<string, number>,
    private readonly lastCaptureBy: Side | null,
  ) {
    this.board = cloneBoard(module, source.fen());
  }

  evaluate(move: string): { outcome: GameOutcome | null; material: MaterialScore; mobility: number } {
    const mover = sideFromTurn(this.board.turn());
    const legalBefore = splitMoves(this.board.legalMoves());
    const wasCapture = this.board.isCapture(move);
    const alternatives = legalBefore.map((candidate) => {
      this.board.push(candidate);
      const value = { key: repetitionKey(this.board), halfmove: this.board.halfmoveClock() };
      this.board.pop();
      return value;
    });
    this.board.push(move);
    const key = repetitionKey(this.board);
    const material = materialFromFen(this.board.fen());
    const facts: PositionFacts = {
      mover,
      legalMovesAfter: this.board.numberLegalMoves(),
      sideToMoveInCheck: this.board.isCheck(),
      positionOccurrenceCount: (this.history.get(key) ?? 0) + 1,
      repetitionAvoidingAlternativesBeforeMove: alternatives.filter((alternative) =>
        (this.history.get(alternative.key) ?? 0) + 1 < 3,
      ).length,
      halfmoveClockAfter: this.board.halfmoveClock(),
      fiftyMoveAvoidingAlternativesBeforeMove: alternatives.filter(({ halfmove }) => halfmove < 100).length,
      deadPositionAfter: isKnownDeadFen(this.board.fen()),
      materialAfter: material,
      lastCaptureBy: wasCapture ? mover : this.lastCaptureBy,
    };
    return { outcome: adjudicateDrawless(facts), material, mobility: this.board.numberLegalMoves() };
  }

  dispose(): void {
    this.board.delete();
  }
}
