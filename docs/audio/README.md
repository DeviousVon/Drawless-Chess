# Sampled audio library

The Android app ships 104 short mono, 48 kHz Ogg/Vorbis effects under
`android/app/src/main/res/raw`. `SampledSoundCatalog.kt` groups them into shuffle bags, including
50 ordinary-move variations that all play before a pool repeats.

Normal moves, captures, and castling are derived only from approved physical chess-piece/board
contacts. Victory assets are derived only from genuine firework recordings. Defeat glass and
reserved check, promotion, hint, low-time, game-start, and undo pools use the separately audited
sources in `audio_manifest.json`. Reserved pools remain disconnected until their UI events have
one-shot behavior across process recreation and saved-game restore.

## Curated rebuild

The 2026-07-13 imported archive was technically valid but failed listening review: a slide source
contaminated 22/50 moves and 8/12 captures, and six household pops had been presented as
fireworks. That imported pack is rejected and superseded as a whole: 46 runtime assets were
rebuilt, while the other 58 passed the corrected source, license, and listening audit and remain
byte-identical carry-forwards. Its SHA-256 was
`0e593a0eaa199f22026a6cba3b72d5bb9342610f1acdcca84ba0be4bcc050808`;
historical checksums are retained under `legacy/` only.

The current manifest is release-gate locked at SHA-256
`b25ed214614f9a71c7995193ba48317d5991b19fc9ae0a297d728dda69ab6bd8`.
The required verifier decodes all 104 files and checks format, hashes, duration, uniqueness,
silence/clipping, source allowlists, transient onset, and an anti-sweep early-energy rule.

Rebuild and then refresh the manifest with:

```powershell
wsl.exe -e bash /mnt/c/src/scripts/audio/rebuild_curated_foley.sh
pwsh -NoProfile -File scripts/audio/update_curated_audio_manifest.ps1
```

The checked-in assets were built with Ubuntu 22.04 packages
`ffmpeg 7:4.4.2-0ubuntu0.22.04.1` and `libvorbisenc2 1.3.7-1build2`. The builder fails closed on
a different toolchain unless `DRAWLESS_ALLOW_UNPINNED_AUDIO_TOOLCHAIN=1` is set for an intentional
audited migration. It uses fixed source inputs, filters, Ogg flags, and stream serials; two passes
on the pinned toolchain must produce zero SHA-256 changes across all 46 generated assets. Other
FFmpeg builds may produce audibly equivalent decoded PCM but different Ogg bytes and must never be
used to refresh the manifest lock without independent review. After an intentional recipe or
toolchain change, independently review the new manifest before replacing the verifier's locked
hash.

## Review material

- `audio_manifest.json` records every output hash, category, processing description, and source.
- `source_recordings/` retains the approved CC0 inputs and exact MIT upstream recordings.
- `licenses/` retains the complete CC0 and ion.sound MIT terms.
- `previews/` contains numbered listening reels only; the app never reads them.
- `THIRD_PARTY_AUDIO.md` records licensing and immutable source identities.
- `scripts/audio/rebuild_curated_previews.sh` recreates reels and includes every move.

Run the release gate with `npm run test:audio`.
