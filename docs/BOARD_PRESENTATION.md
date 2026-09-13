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

## Halloween move indicators

All Hallows’ Court uses opaque pumpkin orange (`#FF941F`) for quiet-move dots,
with a 2 dp/pt charcoal (`#11161C`) outline in Compose and SwiftUI. The orange
interior is visible on dark stone, while the outline separates it from pale
stone. Other themes retain their existing fills and transparent outlines.
Capture rings remain a separate target shape.

## Responsive layout

| Width class | Threshold | Arrangement |
| --- | --- | --- |
| Compact | Below 600 dp | Board above controls |
| Medium | 600–839 dp | Portrait stacks the board above controls; a sufficiently wide landscape window uses a 260 dp side panel |
| Expanded | 840 dp and above | Uses a 320 dp side panel when at least a 360 dp board fits; otherwise stacks the board above controls |

The board remains square and is constrained by both available width and height. Side-panel
layouts reserve vertical space for the clock row below the board and are selected only when
the resulting board is at least 360 dp. Compose uses these metrics as policy input rather
than hard-coded device names.

## Visual system

The current development catalog exposes seven persisted board-theme choices:

- Imperial Marble
- Desert Sandstone
- Glacier Slate
- Verdigris Copper
- Celestial Observatory
- Emberwood Court
- Witchglass

Imperial Marble remains the default. Selecting a theme updates only the chess board and its
piece presentation; menus and the home screen keep the product color system. The stable theme ID is
stored as a presentation preference, so it survives relaunches without becoming part of a
game checkpoint or rules contract. Unknown or retired IDs safely fall back to Imperial Marble.

Celestial Observatory uses the stable ID `celestial_observatory` and replaces the
former purple theme. Persisted `amethyst_geode` selections resolve to the new
canonical theme. The visible catalog has seven choices, including two temporary Halloween board candidates. Lunar alabaster squares
carry quiet engraved chart arcs; midnight enamel squares carry sparse brass
constellations, framed by a calibrated brass border. It uses the standard piece
shapes with ivory and midnight colors; custom sculpture pieces are reserved for
holiday themes. Solid pale-brass legal-move dots have a dark outline;
check/capture red and cool last-move shading remain separate semantic signals.

The experimental Celestial atlases and their provenance in
`artwork/celestial-observatory/README.md` are retained as unused concept assets.
They are no longer selected by either platform's theme catalog. This replacement
is an owner-review candidate and has not been approved for release.

Landscape boards use zero outer layout padding and the full available height,
limited only by the width required for the adjacent controls. On iOS, navigation
and theme controls live in the side panel so they do not reduce board height.

The Halloween comparison offers two visible stable IDs, `halloween_emberwood` and
`halloween_witchglass`, while reusing the existing All Hallows sculpture set.
Persisted `all_hallows_court` preferences resolve to Emberwood Court. The old
stone board is not an additional visible choice; there are exactly two Halloween
options alongside five everyday boards. A final winner has not been selected.

Emberwood Court uses muted pumpkin-amber endgrain and scorched chestnut timber,
with fine irregular rings, wood pores, and dark grain. Witchglass uses cloudy
sage and mulberry glass, quiet swirls, and a few tiny trapped bubbles kept away
from square centers. Both materials use deterministic cached native drawing,
hairline tile joins, and a thin integral iron rim without increasing padding.
Both retain solid orange legal-move dots with dark outlines. Emberwood uses cool
sage selection and blue-gray last-move shading to separate those states from
its warm board; Witchglass retains the amber selection and last-move shading.

Both candidates use the same project-owned 3D cartoon sculpture atlases.
The obsidian side uses jack-o’-lantern pawns, haunted
towers, spectral horses, tall notched-mitre cleric bishops, a dark queen, and a crowned pumpkin king. The
ivory side answers with sheet-ghost pawns, carved towers, pale spectral horses, ivory cleric bishops,
and a ghostly royal pair. Both sides' queen and king stand on dominant sculpted ember-orange
plinths; the obsidian pair's bases match their eye glow, while the ivory pair provides a deliberate
royal color echo. Both bishops have tall pointed mitres with a ragged chunk missing
from one edge, glowing orange inside, and narrow upright bodies. Each piece remains recognizable by chess silhouette and base at compact board
sizes. The art is playful gothic rather than gore or photoreal horror.

The shared catalog continues to own stable theme IDs and the semantic selected, legal-move,
legal-capture, last-move, and check palette. Compose and SwiftUI remain responsible for their
native pixels. Both generate board materials procedurally from stable theme and square
coordinates and perform no continuous theme animation. Each side is packaged as one opaque
1774 × 887 source atlas ordered pawn, rook, knight, bishop, queen, king. Android decodes an atlas
once, crops and color-keys each needed piece into its bounded 192 px raster cache, then reuses the
result. SwiftUI caches the twelve exact `CGImage` crops and applies the same green-removal matrix
inside an asynchronous canvas. Neither platform performs live 3D rendering or per-frame CPU
pixel loops. The original code-native All Hallows ornament renderer remains a safe fallback if an
asset cannot load.

Board grain and sculpture detail must remain subordinate to the position. Ivory and obsidian
silhouettes need to remain readable on both square colors, compact history and promotion pieces
must retain their identifying forms, and board state must remain distinguishable by shape and
spoken semantics rather than color alone. Exact generation prompts, reference-use boundaries,
asset hashes, and the GPL grant are recorded in `artwork/all-hallows/README.md`.

The Halloween comparison is a post-release development candidate. Its presence here does not claim
that either public 1.0.2 binary contains the theme or that candidate-specific visual,
accessibility, performance, or physical-device acceptance has completed.

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
