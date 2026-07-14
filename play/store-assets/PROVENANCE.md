# Google Play store asset provenance

This package uses only Drawless Chess repository artwork and current app captures. The fictional
opponent portraits were generated specifically for this project with OpenAI's image-generation
tool; their source masters, prompts, derivation details, and hashes are retained under
`artwork/opponents`. No stock imagery or third-party promotional artwork is used.

## Brand artwork

- `icon/drawless-chess-store-icon-512.png` reproduces the king geometry in
  `android/app/src/main/res/drawable/ic_launcher_foreground.xml` on the launcher's exact
  `#174E43` background from `android/app/src/main/res/values/colors.xml`.
- `feature/drawless-chess-feature-graphic-1024x500.png` is an original layout made from the same
  repository-native king, app name, and the accurate claims “Every game has a winner” and
  “Offline · Decisive · No ads.”
- Segoe UI, a Windows system font, is used only for feature-graphic text.

## Current screenshot capture set

All ten screenshots were captured on July 14, 2026 from the same current debug candidate:

- app APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- size: 17,709,024 bytes
- SHA-256: `25a252a21b65a768c19b74e1dfecdb4ee7af2093ee0761c9fa06e3c85d0b87ff`

The phone set came from the Android 16 / API 36 emulator at its native 1080 × 2400 display size.
It is identified as emulator output and is not represented as a Pixel capture. The physical Pixel
had already completed its separate candidate acceptance run before disconnecting.

The tablet set came from a physical TAB R6 Ultra running Android 13 / API 33. Portrait captures
are native 1200 × 2000; the landscape defeat capture is native 2000 × 1200. The tablet was awake,
unlocked, and kept on USB power. Its original automatic-rotation setting was restored after the
landscape run.

Raw device captures are retained locally under `source-captures/current` and ignored by Git so
system chrome is never unintentionally published. Their exact hashes are:

| Raw capture | SHA-256 |
|---|---|
| `phone-home-current.png` | `c26da515159c75d89a44d5aeb754b76491a8a55f0252cfac17a53bae8257ec4b` |
| `phone-themes-current.png` | `65abc974f88d1aadfeef57b4ddd5b390488f69c954887cd708502926e5543fb1` |
| `phone-gameplay-current.png` | `925d1888c265ace174d479bf52517498bfd1f4feff0a7b7bc0a93ff8711ed97a` |
| `phone-victory-current.png` | `47ec9af9e692a3dcfccb199d92d175256cf8f7893705b20610ccc1ba59d4d0a1` |
| `phone-defeat-current.png` | `208f97642007091225552d38cbbd67ecc3f511dec307f61f475f9ea2c9078ff7` |
| `tablet-home-current.png` | `71ea54e1e0fd99d21d99613259ed4b605e2b41313316599a2dba60126e27e0a1` |
| `tablet-themes-current.png` | `ebae43410f126b22349d2f254eec2c8a396f726915c2cd6ce8a2d4143d3c6792` |
| `tablet-gameplay-current.png` | `d0373a76e94dbce93f9324468b43de60904c4278c6d739377b05d9f2066b9dff` |
| `tablet-victory-current.png` | `8864a3e5e7b9a008ce2670867594f44d2c1201f64ebb7b4dd3fde30d365a3e70` |
| `tablet-defeat-landscape-current.png` | `f558443919931e32fee0e99cb1a979ae4bdd5c056cb2846e9457d71541a8e551` |

## Deterministic marketing states

`StoreScreenshotHarness.kt` is opt-in Android-test-only tooling. It renders the production Home,
theme picker, responsive game body, opponent portrait, pieces, and completion-effect composables
inside the debug instrumentation host. It uses an in-memory Room database and test-package
preferences, so screenshot generation does not read, clear, or modify an installed player's game
or statistics.

The game states are legal, reproducible chess positions rather than stress fixtures:

- gameplay: `e4 d5 exd5 Qxd5 Nc3 Qd8 d4 Nf6 Nf3 c6 Bd3 Bg4`;
- victory: Scholar's Mate, `e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#`;
- defeat: Fool's Mate, `f3 e5 g4 Qh4#`.

The production victory timeline is captured at exactly 1,210 ms and the production defeat
timeline at exactly 890 ms by the Compose test clock. This keeps the real animation renderer at a
repeatable, legible frame without synthesizing or retouching any overlay. The harness passed on
both the API 36 phone emulator and the physical API 33 tablet.

## Processing

`build-store-assets.ps1` performs only rectangular cropping and conversion to 24-bit RGB PNG.
No screenshot is stretched, composited, painted over, or altered inside the app region.

- Phone captures are cropped without scaling to 1080 × 1920. Crop origins are recorded per row
  in `asset-manifest.csv`; system bars and unused vertical device area are excluded.
- Portrait tablet captures remove only the 41-pixel status bar and 41-pixel navigation bar,
  producing 1200 × 1918 images without scaling.
- The landscape tablet capture removes the same system bars and remains 2000 × 1118 without
  scaling.

Exact source paths, transformations, output dimensions, byte sizes, hashes, and suggested alt text
are recorded in `asset-manifest.csv`.

## Validation and visual review

- Store icon: 512 × 512, fully opaque 32-bit ARGB PNG, 6,461 bytes.
- Feature graphic: 1024 × 500, 24-bit RGB PNG, 43,272 bytes.
- Phone screenshots: five 1080 × 1920 24-bit RGB PNGs.
- Tablet screenshots: four 1200 × 1918 and one 2000 × 1118 24-bit RGB PNGs.
- Every screenshot is within Google's 2:1 maximum long-side/short-side ratio.
- All twelve outputs were inspected at rendered and original resolution. Text is legible, modal
  content is complete, no image is stretched, and no system notification identifier remains.
- The gameplay set visibly includes current opponent portraits, captured-piece scores, and current
  controls. The tablet gameplay and defeat images also show the current piece icons in move
  history.

## Local regeneration

The raw inputs are intentionally local. With those captures present, run from the repository root:

```powershell
& .\play\store-assets\build-store-assets.ps1
```

The script overwrites only the named generated PNG outputs, validates dimensions, formats, and
aspect ratios, and prints fresh SHA-256 hashes. The final sanitized outputs and manifest are the
versioned release assets.
