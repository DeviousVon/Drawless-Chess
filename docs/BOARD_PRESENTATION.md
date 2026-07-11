# Board presentation checkpoint

Status: platform-independent presentation model compiled and verified on JVM

## Implemented interaction behavior

- Tap a friendly piece to select it.
- Tap another friendly piece to change selection.
- Tap the selected piece again to deselect it.
- Tap a legal target to submit the move.
- Drag a friendly piece and drop on a legal target.
- Invalid drops snap back while leaving the source selected.
- Promotion blocks unrelated board interaction until a piece is chosen or cancelled.
- Promotion choices are ordered queen, rook, bishop, knight.
- Board flipping works even while the game is not interactive.
- Any new FEN automatically clears stale selection, drag, and promotion state.

The reducer emits `BoardAction.SubmitMove`; it never applies the move itself. The Android
state holder sends the action to `GameCoordinator`, then renders the next immutable
snapshot.

## Presentation state

Every displayed square includes:

- Logical square and display row/column
- Semantic piece asset key
- Selection state
- Quiet or capture target
- Last-move highlight
- King-in-check highlight
- Accessibility label

Board orientation is an invertible square/display mapping, not a transformed board model.
White-at-bottom and Black-at-bottom therefore share all interaction logic.

## Responsive layout

| Width class | Threshold | Arrangement |
| --- | --- | --- |
| Compact | Below 600 dp | Board above controls |
| Medium | 600–839 dp | Board beside 260 dp panel |
| Expanded | 840 dp and above | Board beside 320 dp panel |

The board remains square and is constrained by both available width and height. Compose
will use these metrics as policy input rather than hard-coded device names.

## Initial visual system

Three semantic board themes are defined:

- Obsidian Glass
- Arctic Slate
- Modern Walnut

Three piece-set contracts are reserved:

- Modern Flat
- Glass
- Sculpted

The contracts intentionally reference semantic asset keys. The currently exposed Modern Flat
implementation is original code-native vector artwork; its provenance is recorded in the root
`NOTICE`. Any future Glass or Sculpted implementation must be original or carry a
release-compatible license before it is exposed.

## Accessibility

Each square exposes a spoken label such as:

- “White knight on f3”
- “Empty e4, legal move”
- “Black pawn on d6, legal capture”
- “White king on e1, king in check”

The Compose adapter should expose 64 traversable semantic nodes, preserve logical reading
order for the current orientation, support keyboard/D-pad activation, and avoid conveying
selection, check, or legal moves through color alone.

## Compose boundary

The next layer is responsible only for pixels, gestures, animation, and Android semantics:

- Render `BoardScreenState`.
- Convert tap and drag gestures into `BoardEvent`.
- Animate a committed move after the coordinator changes position.
- Render promotion as a modal choice.
- Use vector/raster assets selected by `PieceView.assetKey`.
- Send `BoardAction.SubmitMove` to the state holder.

No chess legality, result, clock, or bot logic belongs in a composable.
