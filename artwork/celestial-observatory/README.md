# Celestial Observatory sculpture-atlas provenance

Celestial Observatory replaces the Amethyst Geode development theme on Android
and iOS. The owner selected the astronomical concept on 2026-09-07 for an
installed test build, with visual acceptance still pending.

The concept sheet and both sculpture atlases were generated for Drawless Chess
using OpenAI's built-in image-generation tool. The concept sheet was generated
from an original text description; no third-party reference image was supplied.
The selected project concept was the reference for the ivory atlas, and that
atlas plus the concept guided the midnight variant. The full board concept is
not a runtime asset: native code renders the board material and brass border.

## Packaged source images

| Side | Dimensions | SHA-256 |
| --- | --- | --- |
| Ivory | 1774 × 887 RGB PNG | `224f6aed0aa519ac7f89fb4023ea1faf584b942b7df10d419ceb47753ef06d66` |
| Midnight | 1748 × 900 RGB PNG | `e86c2e74fe57673c5e147cd5fb0b86e8979b2149f20a70bb0ff0b770517ffa53` |

Android resources are
`android/app/src/main/res/drawable-nodpi/celestial_ivory_atlas_chroma.png` and
`celestial_midnight_atlas_chroma.png`. iOS packages the exact same respective
bytes in `CelestialIvoryAtlas.imageset` and `CelestialMidnightAtlas.imageset`
under `iosApp/DrawlessChess/Assets.xcassets`. Neither generated file was
retouched or rescaled. The tool output dimensions differ from requested
dimensions; crop tables use the actual respective files.

Both atlases contain pawn, rook, knight, bishop, queen, king from left to right.
The renderer removes their green background with a native color matrix and
caches the resulting pieces. Celestial alone reconstructs the green channel as
0.6 × red + 0.4 × blue to suppress backdrop contamination at translucent edges;
alpha still uses the original source channels. This preserves thin armillary
rings and sun rays without tightening the transparency threshold. It preserves aspect ratio and aligns the bases.
Height ratios distinguish the short moon pawns from the bishop and royal
instruments. A code-native fallback remains available if an asset cannot load.
No live 3D renderer, network generation, animation loop, or per-frame pixel
processing is required.

| Piece | Ivory crop (x, y, width, height) | Midnight crop |
| --- | --- | --- |
| Pawn | 49, 421, 208, 285 | 48, 414, 205, 283 |
| Rook | 295, 282, 263, 421 | 290, 276, 261, 417 |
| Knight | 584, 301, 266, 400 | 575, 295, 263, 396 |
| Bishop | 882, 252, 256, 444 | 869, 247, 253, 439 |
| Queen | 1169, 165, 254, 539 | 1151, 161, 251, 533 |
| King | 1447, 120, 268, 584 | 1426, 117, 265, 576 |

To the extent copyright or related rights subsist in these outputs, they are
included in the project's GPL-3.0-or-later grant.

## Final generation prompts

### Ivory atlas

```text
Use case: stylized-concept. Production game asset: IVORY CELESTIAL OBSERVATORY chess sprite atlas. The supplied concept sheet is reference for exact design, material, quality, camera, and style. Generate a NEW clean atlas containing EXACTLY SIX individual IVORY sculptures in a SINGLE horizontal row in this exact order left to right PAWN, ROOK, KNIGHT, BISHOP, QUEEN, KING. Plain solid chroma-key GREEN background exact #00FF00 with NO texture, floor, cast shadows, text, swatches, labels or board. Canvas 2048 x 1024. Six evenly spaced invisible cells; centers x171,512,853,1195,1536,1877; circular bases aligned around y870. Every entire piece visible, no touching other cells, no crop. Sculptures match the reference: pawn=short cratered moon sphere held in a 3-prong brass instrument cradle on round foot; rook=broad white observatory tower with brass bands and recognizable split dome opening; knight=alabaster horse head with fine swept brass comet mane on round base; bishop=slender pointed ivory mitre/gnomon with deep diagonal OPEN notch containing one golden star, distinct bishop silhouette; queen=slender regal ivory figure with OPEN radiant sun-ray crown and brass dress fittings; king=taller broad ivory regal figure topped by a large OPEN brass armillary sphere with a single gold Polaris star at center. All on dimensional layered classic circular plinths, finely engraved brass latitude/calibration bands. Ivory bodies with subtle lunar patina, intricate tiny brass rivets and polished champagne edge detailing. Premium feature-film 3D cartoon miniature art, exceptionally rich sculpted materials and texture, not flat graphics. The bodies must be warm white/cream not gray. King tallest top near y170, queen y230, bishop y230, knight y310, rook y360, pawn y490. Maximum piece width270px including crown. Consistent near-front slightly elevated view, soft studio light from upper left, controlled rim light. No clouds, smoke, stars floating outside pieces, extra ornaments on green background, flags, weapons, purple, watermark. Green must fill every gap including inside armillary sphere, bishop notch and between sun rays. Output only the six-piece atlas.
```

### Midnight atlas

```text
Use case: precise-object-edit / matching game asset. IMAGE 1 is the authoritative six-piece IVORY CELESTIAL OBSERVATORY atlas. IMAGE 2 is the overall celestial art-direction reference. Produce its matching DARK MIDNIGHT ENAMEL six-piece atlas. Keep IMAGE 1 canvas dimensions 1774x887, exact single-row composition, piece spacing, silhouettes, sizes, camera, baseline and six types in order PAWN ROOK KNIGHT BISHOP QUEEN KING. Change ivory/white ceramic and limestone surfaces into luxurious deep MIDNIGHT NAVY ENAMEL, near-black blue with subtle engraved constellations and polished pale-blue edge highlights. Change champagne trim to slightly aged warm brushed BRASS. Moon pawn sphere must become DARK cratered moonstone, distinct from white pawn. Keep all main body surfaces dark so armies are immediately distinguishable. Keep small brass stars and celestial instruments warm gold: bishop's inset star, queen's open sunray crown, king's open armillary sphere and Polaris stars. Preserve fine carved details and dimensional circular bases. Entire outside backdrop and gaps remain flat vivid CHROMA GREEN #00FF00, including openings in bishops, crown rays, armillary rings, under moon cradle; no added floor, cast shadow, extra props, particles, text or labels. Same premium intricate 3D miniature sculpture quality as image1 and concept. Keep metallic details within sculptures. Do not change shapes or add crowns. Do not tint the green background. Output ONLY matching dark full atlas.
```
