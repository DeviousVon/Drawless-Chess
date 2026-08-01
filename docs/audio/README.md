# High-quality stereo sampled audio library

The Android app ships 103 stereo, 48 kHz Ogg/Vorbis q8 effects under
`android/app/src/main/res/raw`. `SampledSoundCatalog.kt` groups them into shuffle bags, including
50 ordinary-move variations that all play before a pool repeats. Android packaging stores the
OGG resources without APK deflation so `SoundPool` receives seekable files.

The July 2026 quality remaster replaced the mono low-quality Ogg/Vorbis runtime pack after listening review
found weak levels, truncated firework tails, collapsed stereo ambience, and procedural production
cues. The current pack:

- preserves genuine source stereo for fireworks and other stereo recordings;
- centers mono chess-contact recordings without artificial widening;
- uses stereo Vorbis q8 for a practical quality/size balance;
- retains substantially longer natural firework and glass decays;
- masters effects to category-specific loudness and a -1 dB true-peak ceiling;
- excludes the brittle 8 kHz alabaster preview from runtime recipes; and
- routes captures to 12 recorded stone-crush variants and check to one selected, longer recorded
  mechanical cue rather than layering either event over a normal move; and
- gives en passant and checkmate dedicated Brick and Stone crash cues processed from the original
  lossless CC0 WAVs, with only their inaudible trailing silence removed.

The retained Freesound firework inputs are public HQ MP3 previews because original WAV downloads
require an authenticated Freesound account. The q8 encode cannot reconstruct information already
absent from those previews, but avoids the severe low-bitrate/downmixed result of the rejected
pack. The manifest keeps the original-WAV identities for a future source-only upgrade.

## Rebuild

Run the deterministic PowerShell builder with FFmpeg and FFprobe available:

```powershell
pwsh -NoProfile -File scripts/audio/rebuild_lossless_audio.ps1
```

The builder renders all 103 files into staging, verifies the complete count, atomically replaces
the Android runtime set, and refreshes durations, SHA-256 hashes, source mappings, processing
descriptions, and format metadata in `audio_manifest.json`.

After changing the capture family, rebuild the stereo capture/castling reel and aggregate
audition reel from the finished runtime assets:

```bash
bash scripts/audio/rebuild_curated_previews.sh
```

Run the runtime builder first and the preview builder second. The preview script preserves the
standalone move, firework, and glass reels byte-for-byte. Review the regenerated reels before
updating their audited SHA-256 pins in `scripts/verify-sampled-audio.ps1`; the verifier
intentionally rejects an unreviewed preview change.

## Verification

`npm run test:audio` checks the locked manifest, file/source hashes, category counts, unique
encoded and decoded content, Ogg headers, stereo 48 kHz Vorbis format, duration, onset,
non-silence, headroom, source allowlists, and licensing notices. Android instrumentation also
loads every resource through both `MediaExtractor` and `SoundPool`.

The runtime pack is approximately 1.57 MB (1.50 MiB). Historical rejected Ogg identities remain
under `legacy/` as evidence only and are never packaged.
