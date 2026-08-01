export type Side = "WHITE" | "BLACK";

export type EndReason =
  | "CHECKMATE"
  | "STALEMATE"
  | "REPETITION"
  | "DEAD_POSITION_MATERIAL"
  | "BARE_KING"
  | "FIFTY_MOVE_LIMIT"
  | "RESIGNATION";

export interface MaterialScore {
  white: number;
  black: number;
}

export interface PositionFacts {
  mover: Side;
  legalMovesAfter: number;
  sideToMoveInCheck: boolean;
  positionOccurrenceCount: number;
  repetitionAvoidingAlternativesBeforeMove: number;
  halfmoveClockAfter: number;
  fiftyMoveAvoidingAlternativesBeforeMove: number;
  deadPositionAfter: boolean;
  materialAfter: MaterialScore;
  lastCaptureBy: Side | null;
}

export interface GameOutcome {
  winner: Side;
  loser: Side;
  reason: EndReason;
}

export const opposite = (side: Side): Side => side === "WHITE" ? "BLACK" : "WHITE";

const win = (winner: Side, reason: EndReason): GameOutcome => ({
  winner,
  loser: opposite(winner),
  reason,
});

/**
 * The web preview intentionally exposes one fixed RulesContractV1 profile:
 * Drawless stalemate, material victory for known-dead positions and at 100
 * halfmoves, completing-player repetition loss with the forced exception,
 * and immediate bare-king loss.
 */
export function adjudicateDrawless(facts: PositionFacts): GameOutcome | null {
  const sideToMove = opposite(facts.mover);

  if (facts.legalMovesAfter === 0) {
    return win(facts.mover, facts.sideToMoveInCheck ? "CHECKMATE" : "STALEMATE");
  }

  if (facts.positionOccurrenceCount >= 3) {
    const loser = facts.repetitionAvoidingAlternativesBeforeMove === 0
      ? sideToMove
      : facts.mover;
    return win(opposite(loser), "REPETITION");
  }

  if (facts.materialAfter.white === 0 && facts.materialAfter.black > 0) {
    return win("BLACK", "BARE_KING");
  }
  if (facts.materialAfter.black === 0 && facts.materialAfter.white > 0) {
    return win("WHITE", "BARE_KING");
  }

  if (facts.deadPositionAfter) {
    const winner = facts.materialAfter.white > facts.materialAfter.black
      ? "WHITE"
      : facts.materialAfter.black > facts.materialAfter.white
        ? "BLACK"
        : facts.mover;
    return win(winner, "DEAD_POSITION_MATERIAL");
  }

  if (facts.halfmoveClockAfter >= 100) {
    const winner = facts.materialAfter.white > facts.materialAfter.black
      ? "WHITE"
      : facts.materialAfter.black > facts.materialAfter.white
        ? "BLACK"
        : facts.lastCaptureBy ?? (
          facts.fiftyMoveAvoidingAlternativesBeforeMove === 0 ? facts.mover : sideToMove
        );
    return win(winner, "FIFTY_MOVE_LIMIT");
  }

  return null;
}

export function resignationOutcome(resigningSide: Side): GameOutcome {
  return win(opposite(resigningSide), "RESIGNATION");
}

export function outcomeText(outcome: GameOutcome): string {
  const winner = outcome.winner === "WHITE" ? "White" : "Black";
  switch (outcome.reason) {
    case "CHECKMATE": return `${winner} wins by checkmate.`;
    case "STALEMATE": return `${winner} wins — in Drawless Chess, the trapped player loses.`;
    case "REPETITION": return `${winner} wins under the Drawless repetition rule.`;
    case "BARE_KING": return `${winner} wins — the opponent has only a king remaining.`;
    case "DEAD_POSITION_MATERIAL": return `${winner} wins the dead position by material.`;
    case "FIFTY_MOVE_LIMIT": return `${winner} wins at the 50-move limit.`;
    case "RESIGNATION": return `${winner} wins by resignation.`;
  }
}
