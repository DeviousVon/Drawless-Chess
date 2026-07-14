# Audio design and provenance

Drawless Chess ships 104 mono, 48 kHz Ogg/Vorbis resources covering board moves,
captures, castling, completion effects, and reserved UI cues. Production playback uses
Android `SoundPool` through `GameSoundPlayer.kt`; the runtime catalog and files live under
`android/app/src/main`.

## Sources and licenses

The curated library combines:

- five CC0 close-board placement cuts traced to “Chess Pieces Move (Close)” by JJTaynos;
- the CC0 “chess_move_on_alabaster.wav” contact by mh2o;
- the CC0 “2 Firework pops” and “Whistle and Explosion Single_Firework” recordings by
  Rudmer_Rotteveel; and
- MIT-licensed glass and reserved-UI recordings from ion.sound 3.0.7 by Denis Ineshin.

CC0 permits unrestricted reuse. The ion.sound copyright and MIT permission notice must
remain with source and binary distributions. Root `NOTICE` and `THIRD_PARTY_NOTICES.md`
are bundled into the Android artifact. Complete terms, URLs, retained inputs, hashes,
processing descriptions, and output identities are under `docs/audio/`.

The exact record is `docs/audio/audio_manifest.json`. The five JJTaynos cuts retain their
immutable intermediate identities. The three user-auditioned Freesound HQ previews are
retained and hash-pinned along with the corresponding source pages and login-gated original
WAV download identities. Six retained ion.sound MP3s match immutable upstream Git blobs.

## July 2026 listening correction

The initial imported pack passed technical decoding checks but failed the product listening
gate. Its el_boss slide appeared in 22 of 50 moves and 8 of 12 captures, and its six alleged
fireworks were made from household pop/snap recordings rather than pyrotechnics. The el_boss
slide and the rejected can, camera, and cork inputs—and every runtime dependency on them—have
been removed. The MIT-licensed ion.sound `snap.mp3` remains intentionally retained only for two
reserved low-time cues; the verifier forbids it and every other non-Rudmer source from the
firework pool.

The corrected runtime keeps all 50 move slots. Twenty-eight approved contact-only moves remain,
and `scripts/audio/rebuild_curated_foley.sh` reproducibly creates 22 additional variations from
the six approved chess contacts. It also rebuilds all 12 captures as a quieter removal followed
by a firmer placement, and all six castling cues as two physical placements. Variations use
ordered real-source pairings, 1–5 ms contact offsets, restrained EQ/body and level changes,
trimming, and fades—never sliding, sweeping, pitch-shifting, or synthesis.

All six victory assets now contain real fireworks cut from the two Rudmer_Rotteveel recordings.
The low and mid tiers use the two separate small pops; the high tier uses the isolated rocket
explosion. Alternate variants preserve different stereo microphone perspectives before the
required mono conversion.

## Runtime behavior

- a shuffle bag uses all 50 move variations before repeating and prevents a cycle-boundary
  duplicate;
- SAN routes castling separately from captures and ordinary moves;
- asynchronous preload requests expire after 250 ms so startup cannot produce ghost moves;
- victory and defeat sounds follow the 2.6 s and 2.2 s visual timelines;
- recognizable glass fracture now begins 22 ms after impact, alongside the first visible crack,
  instead of lagging it by 308 ms;
- the three defeat layers use one numbered glass variant as a coherent sequence; and
- mute, lifecycle exit, Home, Rematch, and player shutdown cancel pending cues and streams.

The older deterministic renderer in `ProceduralGameAudio.kt` remains reference/test material
only and is not called by production playback.

## Verification and audition

`scripts/verify-sampled-audio.ps1 -RequireDecode` decodes all 104 runtime clips and verifies:

- exact manifest, output, retained-source, and upstream identities;
- unique encoded and decoded content;
- non-silence, headroom, duration, mono 48 kHz Vorbis format, and Android resource names;
- meaningful onset within 30 ms for board, firework, and glass events;
- at least 75% of every normal move's energy in its first 50 ms, rejecting sweep-like clips;
- chess-contact-only move/capture/castling sources and real-firework-only victory sources; and
- complete CC0 and MIT notices.

`scripts/audio/rebuild_curated_previews.sh` produces numbered reels under
`docs/audio/previews/`. Every one of the 50 moves is present in the move reel; the preview map
lists each exact runtime filename so future listening review cannot hide a rejected subset.

The original archive SHA-256 was
`0e593a0eaa199f22026a6cba3b72d5bb9342610f1acdcca84ba0be4bcc050808`.
Its checksums remain historical evidence under `docs/audio/legacy/`. The imported pack is
superseded as a whole: 46 runtime assets were rebuilt (22 moves, 12 captures, six castles, and six
fireworks), while 58 source- and license-audited assets are byte-identical carry-forwards. The
original archive is therefore evidence of the import, not the provenance authority for the
current runtime.
