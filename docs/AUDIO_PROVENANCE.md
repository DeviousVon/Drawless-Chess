# Audio design and provenance

Drawless Chess ships 103 high-quality stereo, 48 kHz Ogg/Vorbis resources for board moves,
captures, en passant, castling, checkmate, completion effects, and reserved UI cues. Production
playback uses Android `SoundPool` through `GameSoundPlayer.kt`; the deterministic renderer in
`ProceduralGameAudio.kt` also supplies immediate move, crush-capture, and single-check fallbacks
while an ordinary sample is unavailable or still loading.

## Sources and licenses

The library combines five CC0 close-board placement cuts traced to “Chess Pieces Move (Close)”
by JJTaynos, the CC0 “stonehit1.wav” rock recording by aerror, genuine CC0 firework recordings
by Rudmer_Rotteveel, the CC0 pump-action recording by AugustSandberg, the original lossless CC0
“Brick crash” and “Stone crash” WAVs by discofield, and MIT-licensed glass/UI recordings from
ion.sound 3.0.7 by Denis Ineshin. Complete URLs, immutable source hashes,
licenses, processing descriptions, and output identities are recorded in
`docs/audio/audio_manifest.json`.

The original Rudmer firework files are 44.1 kHz, 16-bit stereo WAV recordings. Freesound exposes
those original downloads only to authenticated users, so the repository currently retains the
public HQ stereo MP3 previews. The runtime q8 Ogg files preserve the available stereo field at a
high quality setting but do not claim that information absent from the upstream MP3 was restored.
The original download URLs remain pinned for a future source-only upgrade.

The 8 kHz mono `mh2o_alabaster` preview remains provenance evidence but is excluded from all
runtime recipes because listening review identified it as a source of brittle, piezo-like tone.

## Listening correction

The former pack was technically valid but failed perceptual review. It downmixed every effect to
mono Ogg/Vorbis, trimmed fireworks to 0.56–0.82 seconds, allowed a low firework to peak around
-17.9 dBFS, and temporarily routed captures/checks to procedural noise and damped-sine renderers.

The replacement pack uses high-quality stereo Vorbis q8. Real stereo microphone perspective is
preserved where available; mono chess contacts are centered without fake widening. Firework
peaks are mastered near -1 dBFS, their natural tails extend as long as 2.24 seconds, and captured
piece/check events use recorded samples. The in-game control is now a true linear 0–100% master,
with category balance applied after asset mastering.

Board variations use real recorded contacts with small timing, body, and restrained EQ changes.
Captures are short stereo cuts of the selected recording of a stone striking rocks, with granular
fracture texture and no normal-move contact underneath. En passant uses discofield’s brick crash;
checkmate uses the stone crash. Both preserve their complete audible events while trimming only
trailing silence. Castling contains two clearly separated contacts. No sampled runtime event uses
pitch-shifted household substitutes or synthesized alerts.

## Runtime and verification

- all 103 resources preload asynchronously through one `SoundPool`;
- deterministic physical-model fallbacks keep move, stone-crush capture, and single-check cues
  available during loading or a decoder failure;
- stale startup requests expire after 250 ms;
- shuffle bags prevent immediate cycle-boundary repeats;
- checkmate’s Stone cue starts first, with the first victory/defeat cue targeted about 500 ms
  later while accounting for its animation marker and any final opponent-move animation;
- victory/defeat layers remain synchronized to their visual animation markers;
- Android packaging marks OGG as `noCompress` for seekable file descriptors; and
- mute, lifecycle exit, Home, Rematch, and shutdown cancel pending cues and streams.

`scripts/audio/rebuild_lossless_audio.ps1` performs the deterministic full-pack build.
`scripts/verify-sampled-audio.ps1 -RequireDecode` verifies the locked manifest, 103 unique PCM
outputs, stereo 48 kHz Vorbis format, hashes, duration, onset, energy, headroom, source allowlists,
and complete CC0/MIT notices.

Historical checksums under `docs/audio/legacy/` document rejected packs only and have no runtime
authority.
