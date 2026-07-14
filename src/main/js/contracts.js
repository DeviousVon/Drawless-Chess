const exactKeys = (object, required, optional = []) => {
  if (!object || typeof object !== "object" || Array.isArray(object)) return false;
  const allowed = new Set([...required, ...optional]);
  return required.every((key) => Object.hasOwn(object, key))
    && Object.keys(object).every((key) => allowed.has(key));
};

const oneOf = (value, choices) => choices.includes(value);
const nonNegativeInteger = (value) => Number.isInteger(value) && value >= 0;

export function validateRulesV1(rules) {
  if (!exactKeys(rules,
    ["schemaVersion", "preset", "stalemate", "repetition", "deadPosition", "fiftyMove", "materialValues"])) return false;
  if (rules.schemaVersion !== 1 || !oneOf(rules.preset, ["drawless", "escape"])) return false;
  if (!oneOf(rules.stalemate, ["trapped_player_loses", "trapped_player_wins"])) return false;
  if (!exactKeys(rules.repetition, ["threshold", "completingPlayerLoses", "forcedMoveException"])) return false;
  if (rules.repetition.threshold !== 3 || rules.repetition.completingPlayerLoses !== true
    || rules.repetition.forcedMoveException !== true) return false;
  if (!oneOf(rules.deadPosition, ["material_victory", "final_capture_victory"])) return false;
  if (!oneOf(rules.fiftyMove, ["disabled", "completing_player_loses", "forced_move_exception"])) return false;
  if (!exactKeys(rules.materialValues, ["pawn", "knight", "bishop", "rook", "queen"])) return false;
  const v = rules.materialValues;
  return v.pawn === 1 && v.knight === 3 && v.bishop === 3 && v.rook === 5 && v.queen === 9;
}

export function validateSavedGameV1(game) {
  if (!exactKeys(game,
    ["schemaVersion", "gameId", "createdAt", "mode", "initialFen", "rules", "timeControl", "moves", "engine"],
    ["assistance", "result"])) return false;
  if (game.schemaVersion !== 1 || typeof game.gameId !== "string" || game.gameId.length === 0) return false;
  if (typeof game.createdAt !== "string" || Number.isNaN(Date.parse(game.createdAt))) return false;
  if (!oneOf(game.mode, ["casual", "rated"]) || typeof game.initialFen !== "string" || game.initialFen.length === 0) return false;
  if (!validateRulesV1(game.rules)) return false;
  if (!exactKeys(game.timeControl, ["kind"], ["initialMs", "incrementMs"])) return false;
  if (!oneOf(game.timeControl.kind, ["untimed", "clock"])) return false;
  if (game.timeControl.kind === "clock" && (!Number.isInteger(game.timeControl.initialMs) || game.timeControl.initialMs < 1)) return false;
  if (game.timeControl.kind === "untimed" && (Object.hasOwn(game.timeControl, "initialMs") || Object.hasOwn(game.timeControl, "incrementMs"))) return false;
  if (Object.hasOwn(game.timeControl, "incrementMs") && !nonNegativeInteger(game.timeControl.incrementMs)) return false;
  if (!Array.isArray(game.moves) || !game.moves.every(validateMove)) return false;
  if (!exactKeys(game.engine, ["id", "build", "drawlessPatch"])) return false;
  if (![game.engine.id, game.engine.build].every((v) => typeof v === "string" && v.length > 0)
    || !nonNegativeInteger(game.engine.drawlessPatch)) return false;
  if (game.assistance && !validateAssistance(game.assistance)) return false;
  if (game.mode === "rated" && game.assistance
    && (game.assistance.hints !== 0 || game.assistance.undos !== 0 || game.assistance.pauses !== 0
      || game.assistance.threatIndication === true)) return false;
  return !game.result || validateResult(game.result, game.moves.length);
}

function validateMove(move) {
  if (!exactKeys(move, ["uci"], ["whiteRemainingMs", "blackRemainingMs"])) return false;
  if (typeof move.uci !== "string" || !/^[a-h][1-8][a-h][1-8][qrbn]?$/.test(move.uci)) return false;
  return ["whiteRemainingMs", "blackRemainingMs"].every((key) =>
    !Object.hasOwn(move, key) || nonNegativeInteger(move[key]));
}

function validateAssistance(a) {
  return exactKeys(a, ["hints", "undos", "pauses"], ["threatIndication"])
    && [a.hints, a.undos, a.pauses].every(nonNegativeInteger)
    && (!Object.hasOwn(a, "threatIndication") || typeof a.threatIndication === "boolean");
}

function validateResult(result, plyCount) {
  const reasons = ["CHECKMATE", "STALEMATE", "REPETITION", "DEAD_POSITION_MATERIAL",
    "DEAD_POSITION_FINAL_CAPTURE", "FIFTY_MOVE_LIMIT", "RESIGNATION", "TIMEOUT"];
  return exactKeys(result, ["winner", "reason", "atPly"])
    && oneOf(result.winner, ["WHITE", "BLACK"])
    && oneOf(result.reason, reasons)
    && nonNegativeInteger(result.atPly)
    && result.atPly <= plyCount;
}
