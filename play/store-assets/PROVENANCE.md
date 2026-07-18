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

All ten screenshots were recaptured on July 18, 2026 from the same current debug candidate:

- app APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- size: 18,861,297 bytes
- SHA-256: `76e896c3b9ab6728c352f27050d0771219fd33dd9958f63dd8d72072cb44f8b3`

The phone set came from the Android 16 / API 36 emulator at its native 1080 × 2400 display size.
The tablet set came from the same API 36 emulator using a deterministic 1200 × 2000, 240 dpi
tablet profile; its landscape capture is 2000 × 1200. Both sets are identified as emulator output
and are not represented as physical Pixel or tablet captures. Physical-device play testing remains
a separate release gate.

Raw device captures are retained locally under `source-captures/current` and ignored by Git so
system chrome is never unintentionally published. Their exact hashes are:

| Raw capture | SHA-256 |
|---|---|
| `phone-home-current.png` | `f8c5f27e5933e41e4b57bd244b7942d870942d80b9d3f7d0cb1f8da30b362e64` |
| `phone-themes-current.png` | `57e83aa9dce4c548b452d495afc46b0cea89d3c7ae472bbfef1ad3012715266b` |
| `phone-gameplay-current.png` | `6ce51c6409fd768473824fdf9658b279e685b2238a8c99befd78172f9025d2fa` |
| `phone-victory-current.png` | `2def6327b49c5b898ebc574c10c45321dacf8f19d287254e7d50dfc3881efdf3` |
| `phone-defeat-current.png` | `fd38b4fe5883ad2555f532f5e05375eda9d05231ad7364695b2135d409190598` |
| `tablet-home-current.png` | `b3d2815604389f8ee08fdb08588ca647dedacdd993a05a5021863a5a0887bdcb` |
| `tablet-themes-current.png` | `ae26b7971757530f7f4c669031759f3d20074ef3026dfe4f0a0ca9d301469054` |
| `tablet-gameplay-current.png` | `225e188c1da1e81639d0b4f755832404b9db5bd564a45e40932dbf977d5f791c` |
| `tablet-victory-current.png` | `f3a4741d674ac15cfb749832f9b7e714917c9ae3319fadb0ba24799547b56342` |
| `tablet-defeat-landscape-current.png` | `bc395c3dbfc99a50909764272809912eb99ccfce5e48441e73be08482b3d2ce2` |

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
repeatable, legible frame without synthesizing or retouching any overlay. This refreshed capture
run passed on both the API 36 phone and tablet emulator profiles.

## Processing

`build-store-assets.ps1` performs only rectangular cropping and conversion to 24-bit RGB PNG.
No screenshot is stretched, composited, painted over, or altered inside the app region.

- Phone captures are cropped without scaling to 1080 × 1920. Crop origins are recorded per row
  in `asset-manifest.csv`; system bars and unused vertical device area are excluded.
- Portrait tablet captures exclude emulator system chrome and produce 1200 × 1838 images without
  scaling.
- The landscape tablet capture excludes emulator system chrome and remains 2000 × 1054 without
  scaling.

Exact source paths, transformations, output dimensions, byte sizes, hashes, and suggested alt text
are recorded in `asset-manifest.csv`.

## Validation and visual review

- Store icon: 512 × 512, fully opaque 32-bit ARGB PNG, 6,461 bytes.
- Feature graphic: 1024 × 500, 24-bit RGB PNG, 43,272 bytes.
- Phone screenshots: five 1080 × 1920 24-bit RGB PNGs.
- Tablet screenshots: four 1200 × 1838 and one 2000 × 1054 24-bit RGB PNGs.
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
