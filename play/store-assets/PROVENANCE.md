# Google Play store asset provenance

This package uses only Drawless Chess repository artwork and real app captures. No AI image generation, stock imagery, or third-party promotional artwork was used.

## Brand artwork

- `icon/drawless-chess-store-icon-512.png` reproduces the exact king geometry from `android/app/src/main/res/drawable/ic_launcher_foreground.xml` on the exact `#174E43` launcher background from `android/app/src/main/res/values/colors.xml`.
- `feature/drawless-chess-feature-graphic-1024x500.png` is an original layout made from that same repository-native king, the established app name, and the accurate product claims “Every game has a winner” and “Offline · Decisive · No ads.” It is a 24-bit RGB image with no alpha channel.
- Segoe UI, a system font on the Windows build machine, is used only for the feature graphic's text.

## Screenshot sources and processing

Every screenshot comes from checked app captures under `play/store-assets/source-captures`, `build/theme-visuals`, or `build/visual-checks`. Processing is limited to aspect-ratio cropping, uniform scaling, conversion to 24-bit RGB PNG, and removal of unrelated notification/service identifiers from system status bars. No app UI or game result was invented or retouched. Status-bar cleanup uses a flat patch sampled from the adjacent bar background; clocks, connectivity indicators, and battery state remain intact.

The two home screenshots use final-build captures retained locally outside version
control because raw device chrome can expose unrelated notification activity:

- `source-captures/phone-home-final.png` — 1080 × 2400, SHA-256 `e8f0325dd9cc5a12cc0e19aff3330c9dfdccb4589fc8a14337203f52cee4e0bd`.
- `source-captures/tablet-home-final.png` — 1200 × 2000, SHA-256 `2b0416abf259f742575f7d24b65b14fa28aaca8bb3a2f062e24352b39822a17f`.

Both visibly include the final Privacy control and are the authoritative home-screen sources for this package.

- Phone home and theme captures are cropped vertically from 1080 × 2400 to 1080 × 1920. The phone home crop excludes its system bars while preserving every app control, including Privacy.
- Phone gameplay is top-cropped from 1080 × 2400 to 1080 × 1920 so the complete play surface and controls remain visible.
- Phone win/loss captures are cropped to 9:16 and uniformly enlarged from 1008 × 1792 to 1080 × 1920.
- The four portrait tablet captures are uniformly fitted from 1200 × 2000 to 1080 × 1800, top-aligned on a 1080 × 1920 black canvas. This preserves the complete tablet UI without stretching or cutting edge controls; the unused area extends the existing black navigation region. The final tablet home remains fully visible, including Privacy.
- The landscape tablet capture keeps the full 2000-pixel UI width, removes only top/bottom system-bar space, and uniformly scales to 1920 × 1080.

Exact source files, transformations, dimensions, byte sizes, hashes, and suggested alt text are recorded in `asset-manifest.csv`.

## Validation and visual review

- Store icon: 512 × 512, 32-bit ARGB PNG, fully opaque, 6,461 bytes (under 1 MB).
- Feature graphic: 1024 × 500, 24-bit RGB PNG, 43,272 bytes, no alpha.
- Phone screenshots: five 1080 × 1920 24-bit RGB PNGs.
- Tablet screenshots: four 1080 × 1920 and one 1920 × 1080 24-bit RGB PNGs.
- All outputs stay within a 2:1 maximum long-side/short-side ratio.
- All twelve images were visually inspected at their final rendered dimensions. Text is legible; important controls and result effects are visible; no output is stretched.

## Local regeneration

The committed script records the exact transforms and validates every final output, but
its raw app captures are intentionally local and are not part of a fresh clone. On the
capture machine, from the repository root in PowerShell:

```powershell
& .\play\store-assets\build-store-assets.ps1
```

The script overwrites only its named generated PNG outputs, validates dimensions and pixel
formats, and prints fresh SHA-256 hashes. The final sanitized outputs and their manifest
are the versioned release assets.
