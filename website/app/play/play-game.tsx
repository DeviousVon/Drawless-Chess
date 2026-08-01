"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  BrowserGame,
  loadChessModule,
  type GameSnapshot,
  type PieceCode,
} from "./chess-adapter";
import { DrawlessPiece } from "./drawless-piece";
import { outcomeText, type EndReason, type Side } from "./drawless-rules";
import { marbleSquareBackground } from "./marble-texture";
import styles from "./result-celebration.module.css";

type RuntimeStatus = "loading" | "ready" | "thinking" | "error";
type SideChoice = Side | "RANDOM";

interface PendingRequest {
  requestId: string;
  gameId: string;
  revision: number;
}

const PIECE_NAMES: Record<string, string> = {
  k: "king", q: "queen", r: "rook", b: "bishop", n: "knight", p: "pawn",
};

const FILES = ["a", "b", "c", "d", "e", "f", "g", "h"];
const RANKS = ["1", "2", "3", "4", "5", "6", "7", "8"];

function sideForPiece(piece: PieceCode): Side {
  return piece === piece.toUpperCase() ? "WHITE" : "BLACK";
}

function squareLabel(square: string, piece?: PieceCode): string {
  if (!piece) return `${square}, empty`;
  const side = sideForPiece(piece) === "WHITE" ? "White" : "Black";
  return `${square}, ${side} ${PIECE_NAMES[piece.toLowerCase()]}`;
}

function resultReasonLabel(reason: EndReason): string {
  switch (reason) {
    case "CHECKMATE": return "Checkmate";
    case "STALEMATE": return "Drawless stalemate";
    case "REPETITION": return "Repetition decided";
    case "BARE_KING": return "Bare king";
    case "DEAD_POSITION_MATERIAL": return "Material victory";
    case "FIFTY_MOVE_LIMIT": return "50-move decision";
    case "RESIGNATION": return "Resignation";
  }
}

export function PlayGame() {
  const gameRef = useRef<BrowserGame | null>(null);
  const workerRef = useRef<Worker | null>(null);
  const pendingRef = useRef<PendingRequest | null>(null);
  const resultDialogRef = useRef<HTMLDivElement | null>(null);
  const [snapshot, setSnapshot] = useState<GameSnapshot | null>(null);
  const [status, setStatus] = useState<RuntimeStatus>("loading");
  const [error, setError] = useState<string | null>(null);
  const [humanSide, setHumanSide] = useState<Side>("WHITE");
  const [nextHumanSide, setNextHumanSide] = useState<SideChoice>("WHITE");
  const [selected, setSelected] = useState<string | null>(null);
  const [promotionMoves, setPromotionMoves] = useState<string[]>([]);
  const [resultDismissed, setResultDismissed] = useState(false);

  const cancelPending = useCallback(() => {
    const pending = pendingRef.current;
    if (pending && workerRef.current) {
      workerRef.current.postMessage({ type: "cancel", requestId: pending.requestId });
    }
    pendingRef.current = null;
  }, []);

  const scheduleOpponent = useCallback((game: BrowserGame, current: GameSnapshot, side: Side) => {
    const worker = workerRef.current;
    if (!worker || current.outcome || current.sideToMove === side) {
      setStatus("ready");
      return;
    }
    const requestId = `${current.gameId}:${current.revision}:${Date.now()}`;
    pendingRef.current = { requestId, gameId: current.gameId, revision: current.revision };
    setStatus("thinking");
    worker.postMessage({
      type: "choose",
      requestId,
      candidates: game.opponentCandidates(),
      seed: current.revision * 7919 + current.gameId.length * 101,
    });
  }, []);

  useEffect(() => {
    const worker = new Worker(new URL("./opponent.worker.ts", import.meta.url), { type: "module" });
    workerRef.current = worker;
    let disposed = false;

    worker.onmessage = (event: MessageEvent<{ type: "move"; requestId: string; move: string | null }>) => {
      const pending = pendingRef.current;
      const game = gameRef.current;
      if (!pending || !game || event.data.type !== "move" || event.data.requestId !== pending.requestId) return;
      const current = game.snapshot();
      if (current.gameId !== pending.gameId || current.revision !== pending.revision) return;
      pendingRef.current = null;
      if (!event.data.move) {
        setError("The casual opponent could not choose a move. Start a new game to try again.");
        setStatus("error");
        return;
      }
      try {
        setSnapshot(game.apply(event.data.move));
        setStatus("ready");
      } catch (caught) {
        setError(caught instanceof Error ? caught.message : "The opponent returned an invalid move.");
        setStatus("error");
      }
    };

    worker.onerror = () => {
      if (disposed) return;
      pendingRef.current = null;
      setError("The casual opponent stopped unexpectedly. Start a new game to recover.");
      setStatus("error");
    };

    loadChessModule()
      .then((module) => {
        if (disposed) return;
        const game = new BrowserGame(module);
        gameRef.current = game;
        setSnapshot(game.snapshot());
        setStatus("ready");
      })
      .catch((caught) => {
        if (disposed) return;
        setError(caught instanceof Error ? caught.message : "The chess module could not start.");
        setStatus("error");
      });

    return () => {
      disposed = true;
      cancelPending();
      worker.terminate();
      workerRef.current = null;
      gameRef.current?.dispose();
      gameRef.current = null;
    };
  }, [cancelPending]);

  const pieces = useMemo(() => new Map(snapshot?.pieces.map(({ square, piece }) => [square, piece])), [snapshot]);
  const legalDestinations = useMemo(() => new Set(
    selected && snapshot
      ? snapshot.legalMoves.filter((move) => move.startsWith(selected)).map((move) => move.slice(2, 4))
      : [],
  ), [selected, snapshot]);
  const lastMoveSquares = useMemo(() => new Set(snapshot?.lastMove
    ? [snapshot.lastMove.slice(0, 2), snapshot.lastMove.slice(2, 4)]
    : []), [snapshot]);

  const displaySquares = useMemo(() => {
    const ranks = humanSide === "WHITE" ? [...RANKS].reverse() : [...RANKS];
    const files = humanSide === "WHITE" ? FILES : [...FILES].reverse();
    return ranks.flatMap((rank) => files.map((file) => `${file}${rank}`));
  }, [humanSide]);

  const commitMove = useCallback((move: string) => {
    const game = gameRef.current;
    if (!game) return;
    try {
      const current = game.apply(move);
      setSnapshot(current);
      setSelected(null);
      setPromotionMoves([]);
      setError(null);
      scheduleOpponent(game, current, humanSide);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "That move could not be played.");
    }
  }, [humanSide, scheduleOpponent]);

  const chooseSquare = useCallback((square: string) => {
    if (!snapshot || status !== "ready" || snapshot.outcome || snapshot.sideToMove !== humanSide) return;
    const piece = pieces.get(square);
    if (selected) {
      const matches = snapshot.legalMoves.filter((move) => move.startsWith(selected + square));
      if (matches.length === 1) {
        commitMove(matches[0]);
        return;
      }
      if (matches.length > 1) {
        setPromotionMoves(matches);
        return;
      }
    }
    setSelected(piece && sideForPiece(piece) === humanSide ? square : null);
  }, [commitMove, humanSide, pieces, selected, snapshot, status]);

  const newGame = useCallback(() => {
    cancelPending();
    setResultDismissed(false);
    setSelected(null);
    setPromotionMoves([]);
    setError(null);
    const game = gameRef.current;
    if (game) {
      const chosenSide: Side = nextHumanSide === "RANDOM"
        ? crypto.getRandomValues(new Uint8Array(1))[0] % 2 === 0 ? "WHITE" : "BLACK"
        : nextHumanSide;
      setHumanSide(chosenSide);
      const current = game.reset();
      setSnapshot(current);
      scheduleOpponent(game, current, chosenSide);
    }
  }, [cancelPending, nextHumanSide, scheduleOpponent]);

  const resign = useCallback(() => {
    const game = gameRef.current;
    if (!game || !snapshot || snapshot.outcome) return;
    cancelPending();
    setResultDismissed(false);
    setSnapshot(game.resign(humanSide));
    setSelected(null);
    setStatus("ready");
  }, [cancelPending, humanSide, snapshot]);

  const turnText = snapshot?.outcome
    ? outcomeText(snapshot.outcome)
    : status === "thinking"
      ? "The casual opponent is thinking…"
      : snapshot?.inCheck
        ? `${snapshot.sideToMove === "WHITE" ? "White" : "Black"} is in check.`
        : snapshot
          ? `${snapshot.sideToMove === "WHITE" ? "White" : "Black"} to move.`
          : "Loading the board…";
  const outcome = snapshot?.outcome ?? null;
  const showResult = Boolean(outcome && !resultDismissed);
  const humanWon = outcome?.winner === humanSide;
  const winnerName = outcome?.winner === "WHITE" ? "White" : "Black";

  useEffect(() => {
    if (showResult) resultDialogRef.current?.focus();
  }, [showResult]);

  return (
    <section className="web-game" aria-labelledby="play-game-title">
      <div className="web-game-heading">
        <div>
          <p className="eyebrow">Casual web preview</p>
          <h1 id="play-game-title">Play Drawless Chess</h1>
          <p>Familiar chess, but every game has a winner. Your game stays in this browser.</p>
          <p className="web-theme-note"><span>Imperial Marble</span> Original Drawless pieces</p>
        </div>
        <div className="web-game-options" aria-label="New game options">
          <label>
            Your side
            <select value={nextHumanSide} onChange={(event) => setNextHumanSide(event.target.value as SideChoice)}>
              <option value="WHITE">White</option>
              <option value="BLACK">Black</option>
              <option value="RANDOM">Random</option>
            </select>
          </label>
          <div className="web-opponent-label">
            <span>Opponent</span>
            <strong>Web Casual</strong>
          </div>
          <button className="button button-primary" type="button" onClick={newGame} disabled={!snapshot || status === "loading"}>
            New game
          </button>
        </div>
      </div>

      <div className="web-game-layout">
        <div className="web-board-wrap">
          <div className="web-board" role="grid" aria-label={`Chessboard, viewed from ${humanSide === "WHITE" ? "White" : "Black"}'s side`}>
            {displaySquares.map((square) => {
              const piece = pieces.get(square);
              const isLight = (square.charCodeAt(0) - 97 + Number(square[1]) - 1) % 2 !== 0;
              const isSelected = selected === square;
              const isLegal = legalDestinations.has(square);
              const classes = [
                "web-square",
                isLight ? "web-square-light" : "web-square-dark",
                isSelected ? "is-selected" : "",
                isLegal ? "is-legal" : "",
                lastMoveSquares.has(square) ? "is-last" : "",
              ].filter(Boolean).join(" ");
              return (
                <button
                  className={classes}
                  type="button"
                  role="gridcell"
                  aria-label={squareLabel(square, piece)}
                  aria-selected={isSelected}
                  data-square={square}
                  key={square}
                  style={{ backgroundImage: marbleSquareBackground(square, isLight) }}
                  onClick={() => chooseSquare(square)}
                  disabled={!snapshot || Boolean(snapshot.outcome) || status !== "ready" || snapshot.sideToMove !== humanSide}
                >
                  {piece ? <DrawlessPiece piece={piece} /> : null}
                  {isLegal ? <i aria-hidden="true" /> : null}
                </button>
              );
            })}
          </div>
          <p className="web-game-status" aria-live="polite">{turnText}</p>
          {error ? <p className="web-game-error" role="alert">{error}</p> : null}
        </div>

        <aside className="web-game-panel" aria-label="Game information">
          <div className="web-rule-card">
            <p className="eyebrow">What changes?</p>
            <h2>No draw offer. No split result.</h2>
            <p>Stalemate defeats the trapped player. Repeating a position, reaching a dead position, or hitting the 50-move limit also produces a winner under Drawless rules.</p>
          </div>
          <div className="web-game-actions">
            <button className="button button-secondary" type="button" onClick={resign} disabled={!snapshot || Boolean(snapshot.outcome)}>
              Resign
            </button>
            <a className="button button-quiet" href="/#rules">Read the rules</a>
          </div>
          <div className="web-move-list">
            <h2>Moves</h2>
            {snapshot?.moves.length ? (
              <ol>
                {snapshot.moves.map((move, index) => <li key={`${index}-${move.uci}`}>{move.san}</li>)}
              </ol>
            ) : <p>Your moves will appear here.</p>}
          </div>
        </aside>
      </div>

      {outcome && showResult ? (
        <div className={`${styles.backdrop} ${humanWon ? styles.victory : styles.defeat}`}>
          <div
            ref={resultDialogRef}
            className={styles.card}
            role="dialog"
            aria-modal="true"
            aria-labelledby="web-result-heading"
            aria-describedby="web-result-description"
            tabIndex={-1}
            onKeyDown={(event) => {
              if (event.key === "Escape") setResultDismissed(true);
            }}
          >
            <div className={styles.halo} aria-hidden="true" />
            <div className={styles.confetti} aria-hidden="true">
              {Array.from({ length: 16 }, (_, index) => <i key={index} />)}
            </div>
            <DrawlessPiece
              piece={outcome.winner === "WHITE" ? "K" : "k"}
              className={styles.winnerPiece}
            />
            <p className={styles.reason}>{resultReasonLabel(outcome.reason)}</p>
            <h2 id="web-result-heading" className={styles.headline}>
              {humanWon ? "Victory" : "Defeat"}
            </h2>
            <p className={styles.winner}>{winnerName} wins</p>
            <p id="web-result-description" className={styles.summary}>{outcomeText(outcome)}</p>
            <div className={styles.actions}>
              <button className="button button-primary" type="button" onClick={newGame}>
                Play again
              </button>
              <button className="button button-secondary" type="button" onClick={() => setResultDismissed(true)}>
                View final board
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {promotionMoves.length > 0 ? (
        <div className="promotion-backdrop" role="presentation">
          <div className="promotion-dialog" role="dialog" aria-modal="true" aria-labelledby="promotion-title">
            <h2 id="promotion-title">Choose a promotion</h2>
            <div>
              {promotionMoves.map((move) => {
                const promotion = move[4] as "q" | "r" | "b" | "n";
                return (
                  <button type="button" key={move} onClick={() => commitMove(move)} aria-label={`Promote to ${PIECE_NAMES[promotion]}`}>
                    <DrawlessPiece piece={humanSide === "WHITE" ? promotion.toUpperCase() as PieceCode : promotion} />
                    {PIECE_NAMES[promotion]}
                  </button>
                );
              })}
            </div>
            <button className="button button-quiet" type="button" onClick={() => setPromotionMoves([])}>Cancel</button>
          </div>
        </div>
      ) : null}
    </section>
  );
}
