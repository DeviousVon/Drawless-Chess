# ADR-002: Versioned rules and replayable saved games

Status: accepted

## Decision

Every game stores an immutable rules snapshot, not only a ruleset name. Named presets are
convenient setup templates; they are not sufficient historical records because presets
may be tuned in later releases.

Saved games use an initial FEN and ordered UCI moves as their canonical replay data.
Derived board state, legal moves, repetition counts, and result explanations are rebuilt
and checked during load.

## Rules contract version 1

- `stalemate`: trapped player loses or wins.
- `repetition.threshold`: 3.
- `repetition.completingPlayerLoses`: true.
- `repetition.forcedMoveException`: true.
- `deadPosition`: material victory or final-capture victory.
- `bareKing`: continue or bare king loses. This optional field defaults to `continue`
  when absent from a legacy v1 save.
- `fiftyMove`: disabled, completing player loses, forced-move exception, or material victory.
- `materialValues`: pawn 1, knight 3, bishop 3, rook 5, queen 9.

The shipped Drawless and Escape templates use `bare_king_loses` and default `fiftyMove`
to `material_victory`. New games therefore end when one player has only a king, or after
100 halfmoves without a pawn move or capture. At that limit, higher material wins; tied
material is resolved by the side that made the last capture. If no capture exists, the
forced-move exception determines the winner. Older v1 saves without `bareKing` continue
under their original behavior so replay cannot invent an earlier result.

## Outcome precedence

When one move appears to trigger multiple conditions:

1. Checkmate or stalemate
2. Repetition
3. Bare-king adjudication
4. Dead-position adjudication
5. Configured 50-move outcome

This ordering is part of the versioned contract and cannot silently change for existing
games.

## Compatibility rule

- Unknown major schema version: refuse rated replay and preserve raw data for export.
- Known version with added optional fields: load using documented defaults.
- Rules changes require a new contract version and fixture tests for old games.
- Engine changes never rewrite a completed game's recorded outcome.
