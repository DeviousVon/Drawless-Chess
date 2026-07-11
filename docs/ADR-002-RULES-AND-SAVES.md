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
- `fiftyMove`: disabled, completing player loses, or forced-move exception.
- `materialValues`: pawn 1, knight 3, bishop 3, rook 5, queen 9.

The shipped Drawless and Escape templates default `fiftyMove` to `disabled`. Games still
have decisive exits: recognized dead positions are adjudicated immediately, and the
third-occurrence rule eventually terminates any continuing cycle in the finite chess
state space. The two 50-move loss policies remain versioned options for old saves and
future experiments, but Quick Play does not impose an arbitrary 100-halfmove ending.

## Outcome precedence

When one move appears to trigger multiple conditions:

1. Checkmate or stalemate
2. Repetition
3. Dead-position adjudication
4. Configured 50-move outcome

This ordering is part of the versioned contract and cannot silently change for existing
games.

## Compatibility rule

- Unknown major schema version: refuse rated replay and preserve raw data for export.
- Known version with added optional fields: load using documented defaults.
- Rules changes require a new contract version and fixture tests for old games.
- Engine changes never rewrite a completed game's recorded outcome.
