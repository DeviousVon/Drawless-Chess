# Google Play upload selection

The icon, feature graphic, and all ten screenshots are current and ready for the first Play Console
listing upload. They form one coherent July 18, 2026 set from app candidate SHA-256
`76e896c3b9ab6728c352f27050d0771219fd33dd9958f63dd8d72072cb44f8b3`.

## Phone screenshots

Upload in this order:

1. `screenshots/phone/phone-01-home.png` — current navigation and product promise
2. `screenshots/phone/phone-02-themes.png` — all five themes
3. `screenshots/phone/phone-03-gameplay.png` — Theo, board, captures, scores, and controls
4. `screenshots/phone/phone-04-victory.png` — deterministic real fireworks frame
5. `screenshots/phone/phone-05-defeat.png` — deterministic real shattered-glass frame

Each phone image is a 1080 × 1920 24-bit RGB PNG. The source device was the Android 16 / API 36
1080 × 2400 emulator; the images are not represented as Pixel captures.

## Tablet screenshots

Upload in this order:

1. `screenshots/tablet/tablet-01-home.png`
2. `screenshots/tablet/tablet-02-themes.png`
3. `screenshots/tablet/tablet-03-gameplay.png`
4. `screenshots/tablet/tablet-04-victory.png`
5. `screenshots/tablet/tablet-05-defeat-landscape.png`

The first four tablet images are 1200 × 1838 portrait 24-bit RGB PNGs. The final image is a
2000 × 1054 landscape 24-bit RGB PNG. All were captured on the Android 16 / API 36 emulator's
deterministic tablet profile and show the current responsive tablet layout.

## Upload checks

- Do not upload files from `source-captures`; those are local raw captures with device chrome.
- Use only the versioned files under `icon`, `feature`, and `screenshots`.
- If the UI changes after this candidate is frozen, regenerate all ten screenshots as a set rather
  than mixing revisions.
- The exact dimensions, sizes, hashes, alt text, sources, and transforms are in
  `asset-manifest.csv`; provenance and the deterministic legal positions are in `PROVENANCE.md`.
- No Google Play upload was performed while preparing these files.
