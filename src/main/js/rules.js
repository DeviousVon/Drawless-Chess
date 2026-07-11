export const Side = Object.freeze({ WHITE: "WHITE", BLACK: "BLACK" });
export const StalematePolicy = Object.freeze({
  TRAPPED_PLAYER_LOSES: "TRAPPED_PLAYER_LOSES",
  TRAPPED_PLAYER_WINS: "TRAPPED_PLAYER_WINS",
});
export const DeadPositionPolicy = Object.freeze({
  MATERIAL_VICTORY: "MATERIAL_VICTORY",
  FINAL_CAPTURE_VICTORY: "FINAL_CAPTURE_VICTORY",
});
export const FiftyMovePolicy = Object.freeze({
  DISABLED: "DISABLED",
  COMPLETING_PLAYER_LOSES: "COMPLETING_PLAYER_LOSES",
  FORCED_MOVE_EXCEPTION: "FORCED_MOVE_EXCEPTION",
});
export const EndReason = Object.freeze({
  NONE: "NONE",
  CHECKMATE: "CHECKMATE",
  STALEMATE: "STALEMATE",
  REPETITION: "REPETITION",
  DEAD_POSITION_MATERIAL: "DEAD_POSITION_MATERIAL",
  DEAD_POSITION_FINAL_CAPTURE: "DEAD_POSITION_FINAL_CAPTURE",
  FIFTY_MOVE_LIMIT: "FIFTY_MOVE_LIMIT",
});

export const opposite = (side) => side === Side.WHITE ? Side.BLACK : Side.WHITE;

export function ruleset({
  id = "drawless",
  stalemate = StalematePolicy.TRAPPED_PLAYER_LOSES,
  deadPosition = DeadPositionPolicy.MATERIAL_VICTORY,
  fiftyMove = FiftyMovePolicy.DISABLED,
} = {}) {
  return Object.freeze({ id, stalemate, deadPosition, fiftyMove });
}

export function positionAfterMove(overrides = {}) {
  const p = {
    mover: Side.WHITE,
    legalMoveCount: 1,
    sideToMoveInCheck: false,
    positionOccurrenceCount: 1,
    repetitionAvoidingAlternativesBeforeMove: 1,
    halfmoveClock: 0,
    fiftyMoveAvoidingAlternativesBeforeMove: 1,
    deadPosition: false,
    moveWasCapture: false,
    whiteMaterial: 0,
    blackMaterial: 0,
    ...overrides,
  };
  for (const key of ["legalMoveCount", "repetitionAvoidingAlternativesBeforeMove",
    "halfmoveClock", "fiftyMoveAvoidingAlternativesBeforeMove", "whiteMaterial", "blackMaterial"]) {
    if (!Number.isInteger(p[key]) || p[key] < 0) throw new RangeError(`${key} must be a non-negative integer`);
  }
  if (!Number.isInteger(p.positionOccurrenceCount) || p.positionOccurrenceCount < 1) {
    throw new RangeError("positionOccurrenceCount must be a positive integer");
  }
  if (![Side.WHITE, Side.BLACK].includes(p.mover)) throw new TypeError("mover must be WHITE or BLACK");
  return Object.freeze(p);
}

const ongoing = () => Object.freeze({ terminal: false, reason: EndReason.NONE });
const win = (reason, winner, explanation) => Object.freeze({
  terminal: true, reason, winner, loser: opposite(winner), explanation,
});

export function adjudicate(rules, p) {
  const sideToMove = opposite(p.mover);

  if (p.legalMoveCount === 0) {
    if (p.sideToMoveInCheck) {
      return win(EndReason.CHECKMATE, p.mover, `${p.mover} wins by checkmate`);
    }
    const winner = rules.stalemate === StalematePolicy.TRAPPED_PLAYER_LOSES
      ? p.mover : sideToMove;
    return win(EndReason.STALEMATE, winner, `${winner} wins under the ${rules.id} stalemate rule`);
  }

  if (p.positionOccurrenceCount >= 3) {
    const loser = p.repetitionAvoidingAlternativesBeforeMove === 0 ? sideToMove : p.mover;
    return win(EndReason.REPETITION, opposite(loser), `${loser} loses by causing a third repetition`);
  }

  if (p.deadPosition) {
    if (rules.deadPosition === DeadPositionPolicy.FINAL_CAPTURE_VICTORY) {
      if (!p.moveWasCapture) throw new Error("Final-capture adjudication requires a capture transition");
      return win(EndReason.DEAD_POSITION_FINAL_CAPTURE, p.mover,
        `${p.mover} wins by making the final meaningful capture`);
    }
    const winner = p.whiteMaterial > p.blackMaterial ? Side.WHITE
      : p.blackMaterial > p.whiteMaterial ? Side.BLACK : p.mover;
    return win(EndReason.DEAD_POSITION_MATERIAL, winner,
      `${winner} wins the dead position by material adjudication`);
  }

  if (p.halfmoveClock >= 100 && rules.fiftyMove !== FiftyMovePolicy.DISABLED) {
    const forced = rules.fiftyMove === FiftyMovePolicy.FORCED_MOVE_EXCEPTION
      && p.fiftyMoveAvoidingAlternativesBeforeMove === 0;
    const loser = forced ? sideToMove : p.mover;
    return win(EndReason.FIFTY_MOVE_LIMIT, opposite(loser),
      `${loser} loses by reaching the configured 50-move limit`);
  }

  return ongoing();
}
