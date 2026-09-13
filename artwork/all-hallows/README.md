# All Hallows’ Court sculpture-atlas provenance

The All Hallows’ Court ivory and obsidian chess-piece atlases were created for
Drawless Chess on 2026-08-31 with OpenAI's built-in image-generation tool. A
single Halloween chess photograph supplied by the project owner was used only
as a style, material, lighting, and quality reference. The photograph is not in
the repository, no source pixels from it are shipped, and the generated board
environment was discarded.

The exact 1774 × 887 RGB PNG outputs are the platform resources themselves:

| Side | Android resource | iOS resource | SHA-256 |
| --- | --- | --- | --- |
| Obsidian | `android/app/src/main/res/drawable-nodpi/all_hallows_obsidian_atlas_chroma.png` | `iosApp/DrawlessChess/Assets.xcassets/AllHallowsObsidianAtlas.imageset/all-hallows-obsidian-atlas-chroma.png` | `8561a31a9bd5b1e880c8ea3b3a04d1c75af5d23b76214ab370c2ce8f91fccd48` |
| Ivory | `android/app/src/main/res/drawable-nodpi/all_hallows_ivory_atlas_chroma.png` | `iosApp/DrawlessChess/Assets.xcassets/AllHallowsIvoryAtlas.imageset/all-hallows-ivory-atlas-chroma.png` | `7cd157222646e02904386ba7560a4a39626d769258d88e5963f2c0168a62eda5` |

The duplicated Android and iOS files must remain byte-identical. The source
order is pawn, rook, knight, bishop, queen, king. The renderer crops those six
cells, removes the generated green backdrop with one color matrix, and retains
a code-native fallback. The atlases are static: the apps do not perform live
3D rendering, animation, or per-frame CPU pixel processing.

To the extent copyright or related rights subsist in these outputs, they are
included in the project's GPL-3.0-or-later grant.

## Final generation prompts

### Obsidian sculptures

```text
Use case: stylized-concept
Asset type: production mobile-game chess-piece sprite atlas
Input images: Image 1 is a style, material, lighting, and quality reference only; do not reproduce its board or environment.
Primary request: Create one ultra-polished high-resolution 3D cartoon atlas containing exactly SIX BLACK Halloween chess pieces in a single horizontal row, in this exact left-to-right order: PAWN, ROOK, KNIGHT, BISHOP, QUEEN, KING.
Scene/backdrop: genuinely transparent background with clean alpha; no floor, no board, no scenery, no frame.
Subject: six distinct miniature sculptures. Pawn is a carved jack-o’-lantern on a black chess base; rook is a haunted obsidian tower with tiny warm windows; knight is an elegant black spectral horse with an ember eye; bishop is a hooded wraith whose silhouette still reads unmistakably as a bishop; queen is a regal dark witch queen in a sculpted gown; king is a crowned pumpkin-headed gothic monarch. Every piece has a classic round chess base and an unmistakable chess silhouette.
Style/medium: premium feature-animation-quality 3D cartoon render, exceptionally detailed sculpting, believable polished obsidian and aged blackened metal, restrained surface wear, rounded tactile forms, refined—not cute clip art, not photoreal horror.
Composition/framing: perfectly straight single row; six equal-width invisible cells; one centered piece per cell; identical camera, scale, base diameter, baseline, and elevated three-quarter-front viewing angle; generous clear separation; every full silhouette visible with padding; queen and king may be taller but must remain inside their cells.
Lighting/mood: controlled cinematic studio lighting with soft rim light; subtle warm orange internal glow only in eyes, pumpkin cuts, and windows; transparent outside the pieces; consistent light direction across all six.
Color palette: near-black obsidian, charcoal, aged gunmetal, ember orange, tiny muted bronze accents.
Materials/textures: high-frequency carved detail, fabric folds, mane strands, masonry, crown metalwork, micro-scratches, glossy and matte material variation.
Constraints: exact six pieces only; correct order; no duplicate or missing type; no text, labels, letters, numbers, logos, watermark, board, ground plane, cast shadow outside each piece, smoke, fog, cobweb strands extending beyond silhouettes, detached props, weapons, gore, or frightening imagery. Strong silhouette and readable detail when reduced to a 48px mobile board square. Preserve genuine alpha transparency.
```

### Ivory sculptures

```text
Use case: stylized-concept
Asset type: production mobile-game chess-piece sprite atlas
Input images: Image 1 is the original style/quality reference. Image 2 is the matching black-piece atlas and is the authoritative reference for camera, cell spacing, baseline, scale, base diameter, lighting direction, 3D-cartoon finish, and exact piece order. Do not reproduce any board or environment.
Primary request: Create one ultra-polished high-resolution 3D cartoon atlas containing exactly SIX IVORY-WHITE Halloween chess pieces in a single horizontal row, in this exact left-to-right order: PAWN, ROOK, KNIGHT, BISHOP, QUEEN, KING.
Scene/backdrop: genuinely transparent background with clean alpha; no floor, no board, no scenery, no frame.
Subject: six distinct miniature sculptures. Pawn is a charming sheet-ghost chess pawn with tiny dark eye openings and a classic rounded base; rook is an intricately carved haunted ivory stone tower; knight is an elegant pale spectral/skeletal horse with a richly sculpted mane; bishop is a tall hooded white specter whose silhouette still reads unmistakably as a bishop; queen is a regal ghost queen in a flowing sculpted gown and delicate crown; king is a dignified crowned ghost monarch with a royal mantle. Every piece has a classic round chess base and an unmistakable chess silhouette.
Style/medium: premium feature-animation-quality 3D cartoon render, exceptionally detailed sculpting, believable aged ivory, pale stone, bone, cloth, and subtle warm metal, rounded tactile forms, refined—not flat clip art, not photoreal horror.
Composition/framing: match Image 2 exactly: perfectly straight single row; six equal-width invisible cells; one centered piece per cell; identical camera, scale, base diameter, baseline, and elevated three-quarter-front viewing angle; generous clear separation; every full silhouette visible with padding; queen and king may be taller but remain inside their cells.
Lighting/mood: controlled cinematic studio lighting matching Image 2, soft rim light and ambient occlusion; restrained warm candle-gold accents only in crowns/jewelry; transparent outside the pieces; consistent light direction across all six.
Color palette: warm ghost ivory, aged cream stone, pale bone, muted champagne gold, deep charcoal eye openings.
Materials/textures: high-frequency fabric folds, carved masonry, mane strands, crown filigree, subtle age patina, micro-scratches, glossy/matte material variation.
Constraints: exact six pieces only; correct order; no duplicate or missing type; no text, labels, letters, numbers, logos, watermark, board, ground plane, cast shadow outside each piece, smoke, fog, cobweb strands extending beyond silhouettes, detached props, weapons, gore, or frightening imagery. Strong silhouette and readable detail when reduced to a 48px mobile board square. Preserve genuine alpha transparency.
```

The image tool returned opaque checkerboard images despite the requested alpha.
One transparency-only edit also remained opaque, so it was rejected. The two
selected atlases were produced by editing only the backdrop to chroma green:

### Obsidian chroma edit

```text
Use case: precise-object-edit
Asset type: production black chess-piece sprite atlas prepared for chroma-key compositing
Input images: Image 1 is the exact edit target.
Primary request: Replace ONLY the pale checkerboard backdrop with a perfectly flat, uniform chroma-key green background, exact solid RGB (0, 255, 0), hex #00FF00, edge to edge.
Constraints: preserve all six black Halloween chess sculptures exactly—their silhouettes, scale, order, positions, spacing, colors, textures, lighting, internal orange glow, bases, and canvas layout. Do not redraw, restyle, move, resize, crop, add, remove, or reinterpret any piece. Keep clean antialiased piece edges with minimal green spill. Remove all checkerboard squares and all cast shadows outside the piece silhouettes. No floor, gradient, vignette, texture, grid, text, watermark, or any background variation. The entire background outside the pieces must be one exact color: #00FF00.
```

### Ivory chroma edit

```text
Use case: precise-object-edit
Asset type: production ivory chess-piece sprite atlas prepared for chroma-key compositing
Input images: Image 1 is the exact edit target.
Primary request: Replace ONLY the pale checkerboard backdrop with a perfectly flat, uniform chroma-key green background, exact solid RGB (0, 255, 0), hex #00FF00, edge to edge.
Constraints: preserve all six ivory-white Halloween chess sculptures exactly—their silhouettes, scale, order, positions, spacing, colors, textures, lighting, fine crown/mane/cloth details, bases, and canvas layout. Do not redraw, restyle, move, resize, crop, add, remove, or reinterpret any piece. Keep clean antialiased piece edges with minimal green spill. Remove all checkerboard squares and all cast shadows outside the piece silhouettes. No floor, gradient, vignette, texture, grid, text, watermark, or any background variation. The entire background outside the pieces must be one exact color: #00FF00.
```

### Obsidian monarch-base refinement

Bob requested that the orange-eyed obsidian queen and king stand on orange
bases matching their eye glow. The final obsidian atlas was produced with this
exact edit:

```text
Precision edit of the supplied six-piece obsidian Halloween chess atlas. Keep the canvas exactly 1774 x 887 pixels. Preserve the exact vivid chroma-key green background, exact piece order, exact positions, scale, silhouette, camera, lighting, materials, faces, crowns, costumes, and all details. Preserve the pawn, rook, knight, and bishop completely unchanged. Edit ONLY the circular stepped plinth bases under the fifth piece (queen) and sixth piece (king). Make those two monarch bases unmistakably rich ember-orange, matching the exact warm orange glow of their existing eyes and jack-o'-lantern faces. The bases must remain premium, sculpted, dimensional 3D cartoon objects: layered circular stone/resin plinths with carved gothic bevels, subtle internal amber glow, controlled highlights, darker burnt-orange recesses, and fine high-resolution surface detail. Orange must be the dominant base color, not merely a thin stripe; retain a very narrow dark obsidian contact rim only where needed for definition. Keep the bases fully inside their current footprints and crop bounds. Do not add detached particles, floor shadows, smoke, reflections, text, checkerboard, new objects, or any changes to the chroma background. Output one clean atlas only.
```

### Ivory monarch-base refinement

Bob then requested the same ember-orange plinth treatment for the ivory queen
and king. The first four ivory pieces and both monarch bodies remain unchanged;
only the two royal bases were edited. The final ivory atlas was produced with
this exact prompt:

```text
Precision edit of Image 1, the supplied six-piece IVORY Halloween chess atlas. Image 2 is reference only for the exact ember-orange sculpted plinth material already used under its queen and king. Output ONE edited ivory atlas only. Keep Image 1's canvas exactly 1774 x 887 pixels. Preserve the exact vivid chroma-key green background, exact piece order, exact positions, scale, silhouette, camera, lighting, ivory materials, faces, crowns, costumes, and all details. Preserve the pawn, rook, knight, and bishop completely unchanged. Edit ONLY the circular stepped plinth bases under the fifth piece (ivory queen) and sixth piece (ivory king). Make those two royal bases unmistakably rich ember-orange and visually matched to Image 2's queen/king bases: premium layered circular stone/resin plinths with carved gothic bevels, subtle internal amber glow, controlled highlights, darker burnt-orange recesses, and fine high-resolution 3D-cartoon surface detail. Orange must be the dominant base color, not merely a thin stripe; retain a very narrow dark burnt-orange contact rim only where needed for definition. Keep both bases fully inside their existing footprints and crop cells. Do not alter the ivory bodies above the bases. Do not add orange eyes, detached particles, floor shadows, smoke, reflections, text, checkerboard, new objects, or any changes to the chroma background. Output one clean atlas only.
```

## Tall, ragged bishop refinement — 2026-09-07

The owner found the hooded bishops difficult to distinguish and selected the
tall pointed mitre with a ragged chunk missing from its edge and orange light
inside. Both sides now use a narrow upright cleric silhouette. The smaller,
straight-notch alternative was rejected. These are built-in image-generation
edits of the existing project atlases. Both platform copies remain byte-identical.
The generated full atlases retain the piece order and royal orange plinths;
small incidental pixel differences outside the bishop are possible.

The obsidian crop is (887, 135, 270, 615); ivory is (910, 110, 250, 649).
The expanded crops retain both tall tips and center the regenerated ivory figure.

### Selected obsidian edit prompt

```text
Use case: precise-object-edit. Edit target: the supplied Halloween chess sprite atlas. Change ONLY the FOURTH chess piece from the left, the black obsidian BISHOP. Replace its ghost-like hood with a clearly recognizable tall pointed chess bishop mitre with a large deep DIAGONAL NOTCH cut into the upper-right side, filled with vivid glowing ember orange. The notch must visibly interrupt the silhouette, and remain recognizable at 45px tall. Make the bishop an upright narrow haunted cleric with restrained obsidian robes, small orange eyes, no crown, no staff, no cross ornament at the top. Maintain the existing polished black obsidian and bronze detailing style and existing black stepped circular base. Keep the entire bishop within x887..1157 y225..750 on the original 1774x887 canvas, same bottom baseline and centered around x1000, with top near y235. Preserve canvas dimensions, flat chroma green backdrop, lighting, view angle, spacing, and ALL FIVE OTHER PIECES exactly including their orange royal bases. Do not redesign the pawn, rook, knight, queen or king. No text, watermark, extra pieces, or background shadows. Deliver edited full atlas.
```

The selected result exceeded the requested height; its taller hat was explicitly
preferred, so the runtime crop was expanded rather than shortening the artwork.

### Matching ivory edit prompt

```text
Precise object edit. IMAGE 1 ivory chess atlas is edit target. IMAGE 2 black atlas is reference for fourth piece BISHOP ONLY. Replace ONLY image 1's fourth ivory bishop with an ivory version of the EXACT tall narrow haunted cleric bishop in image 2, including its striking VERY TALL POINTED HAT with a LARGE RAGGED CHUNK MISSING from its upper right edge and vivid glowing orange inside that ragged opening. Preserve the asymmetrical jagged bitten-away silhouette of the reference hat; do not simplify to a straight slash and do not shorten the hat. Match the reference bishop proportions, height with tip near y150 on the 1774x887 canvas, upright narrow robed body, arms and small orange eyes; render in warm ivory sculpted ceramic matching the other ivory pieces, on its ivory stepped round plinth at the existing baseline y740. Entire bishop within x892..1142 y135..759. Preserve all FIVE OTHER PIECES of image1, layout, positions, dimensions, lighting, vivid chroma green backdrop and orange queen/king bases. No text, extra objects, staff or crown. Output edited ivory full atlas only.
```
