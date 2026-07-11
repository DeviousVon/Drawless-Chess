# Chess-law core checkpoint

Status: compiled and verified on JVM

## Implemented

- Strict six-field FEN parsing and deterministic FEN serialization.
- Immutable 64-square board and standard UCI moves.
- Full standard legal move generation.
- Check, checkmate, and stalemate detection.
- King safety, pins, and double-check filtering through post-move validation.
- King- and queen-side castling, including attacked transit-square rules.
- En-passant generation, capture application, and discovered-check rejection.
- Four-piece promotion generation.
- Castling-right, en-passant, halfmove, and fullmove state updates.
- Canonical repetition keys.
- Replay with exact failing-ply reporting.
- Drawless `MoveTransition` generation containing every legal alternative.
- Conservative dead-position detection with no known false-positive material cases.

## Perft verification

Perft counts the legal move tree and is a standard way to find move-generation and
make-move defects. The test suite includes:

| Fixture | Verified depth/count |
| --- | --- |
| Starting position | 1/20, 2/400, 3/8,902 |
| Kiwipete | 1/48, 2/2,039, 3/97,862 |
| Position 3 | 3/2,812 |
| Position 4 | 2/264 |
| Position 5 | 2/1,486 |
| Position 6 | 2/2,079 |

Canonical reference:

- https://www.chessprogramming.org/Perft_Results

## Repetition identity

The position key contains:

- Piece placement
- Side to move
- Castling rights
- En-passant target only when the side to move has a legal en-passant capture

It excludes halfmove and fullmove counters. This matches move-equivalence needs while
preserving the counters separately for configurable 50-move adjudication.

## Dead-position boundary

The detector returns true for:

- Bare kings
- King plus one bishop or knight versus a bare king
- Positions containing only kings and bishops where every bishop is confined to the
  same square color

It deliberately returns false for unusual positions whose impossibility depends on a
specific blocked arrangement. A false negative merely lets play continue to repetition;
a false positive would incorrectly end a game, so the conservative direction is safer.
A later Fairy-Stockfish integration may supply general dead-position proof if needed.

## Trust and performance boundary

The implementation is intentionally correctness-first and immutable. It is not intended
to replace Fairy-Stockfish search. Its jobs are legal UI moves, replay, authoritative game
law, and engine-input validation. We can optimize allocation only after Android profiling
shows a user-visible need.
