# Third-party audio provenance

## CC0 chess recordings

Move, capture, and castling families use two recording sets published under Creative Commons
Zero 1.0. CC0 permits copying, modification, commercial use, and redistribution without an
attribution condition. Attribution remains here for auditability.

- **“Chess Pieces Move (Close)”**, JJTaynos:
  <https://freesound.org/people/JJTaynos/sounds/733927/>. Five retained cuts correspond to the
  immutable DeLinev/HanoiTower copies at commit
  `2f0e3114859e948a8ed7ae5da1663f9762e58986`; exact URLs, Git blob IDs, and hashes are in
  `audio_manifest.json`.
- **“chess_move_on_alabaster.wav”**, mh2o:
  <https://freesound.org/people/mh2o/sounds/351518/>. Freesound identifies the original as a
  0.072-second, 8 kHz, 16-bit mono WAV. The retained input is the public HQ MP3 preview the
  product owner auditioned; both its exact CDN URL/hash and the login-gated original-download
  identity are pinned in the manifest.

The previously imported el_boss “Piece Slide.mp3” was rejected after listening review and is no
longer retained or referenced by any runtime asset.

## CC0 firework recordings

Victory cues use genuine pyrotechnics recorded by Rudmer_Rotteveel with a Tascam DR-05:

- **“2 Firework pops”**: <https://freesound.org/people/Rudmer_Rotteveel/sounds/334042/>
- **“Whistle and Explosion Single_Firework”**:
  <https://freesound.org/people/Rudmer_Rotteveel/sounds/336008/>

Both Freesound pages designate the recordings CC0. The retained inputs are the public HQ MP3
previews heard during product review; exact CDN hashes, source metadata, and original WAV download
identities are pinned in `audio_manifest.json`. Runtime assets isolate natural onsets/tails and
never substitute household snaps, corks, cans, or camera sounds.

The complete CC0 1.0 legal text is in `licenses/CC0-1.0.txt`.

## ion.sound physical recordings

Glass and reserved UI layers use recordings from **ion.sound 3.0.7** by Denis Ineshin under the
MIT License. Every retained MP3 is byte-for-byte identical to its Git blob at commit
`74d51c5bd14be428f06b3afb5e40125b8e407fbc`:

<https://github.com/IonDen/ion.sound/tree/74d51c5bd14be428f06b3afb5e40125b8e407fbc/sounds>

The authoritative license is pinned at:

<https://github.com/IonDen/ion.sound/blob/d0eed04fba8de5c05925320d04a9accbcab1e75b/License.md>

The complete copyright and permission notice is retained in `licenses/ion-sound-MIT.txt` and
repeated in root `THIRD_PARTY_NOTICES.md`, which is bundled inside release APKs and App Bundles.

## Processing and runtime

`scripts/audio/rebuild_curated_foley.sh` documents the exact corrected move, capture, castling,
and fireworks recipes. Those families use trimming, fades, gain staging, restrained filtering,
micro-offset physical layering, and stereo-to-mono microphone mixes. They use no slide/sweep,
pitch shift, procedural oscillator/noise renderer, or household firework substitute.

`GameSoundPlayer` preloads the compact library with Android `SoundPool`, expires stale requests,
honors the persisted mute setting, cancels result cues on lifecycle exit, and keeps all three
glass layers from the same numbered variant.
