# Sampled audio integration for Drawless Chess

This pack is designed around the current Compose implementation rather than a generic chess-sound API.

## Current runtime hooks

The app currently owns one process-wide `GameSoundPlayer`, starts it in `DrawlessApplication`, and calls it from `GameRoute` after the visible ply count advances. The current call only distinguishes a capture by looking for `x` in SAN. Victory and defeat already have synchronized three-cue timelines:

- Victory: `FIREWORK_LOW`, `FIREWORK_MID`, `FIREWORK_HIGH` over 2.6 seconds.
- Defeat: `GLASS_IMPACT`, `GLASS_FRACTURE`, `GLASS_SHARDS` over 2.2 seconds.

The generated resource catalog mirrors those existing completion-cue names, so the finish overlay does not need a timing redesign.

## Recommended replacement architecture

Use Android `SoundPool` for these short effects. Construct the player with an application `Context`, load every resource ID from `SampledSoundCatalog`, and retain the current `stopAll()` / `close()` lifecycle behavior.

```kotlin
// DrawlessApplication.kt
gameSoundPlayer = GameSoundPlayer(this)
```

Change the post-move call to pass SAN instead of only a capture flag:

```kotlin
val latestSan = model.history.lastPlayedSan().orEmpty()
soundPlayer.playMove(latestSan)
```

Route SAN in this order:

```kotlin
when {
    san.startsWith("O-O") -> playFrom(castleBag)
    'x' in san -> playFrom(captureBag)
    else -> playFrom(moveBag)
}

if ('=' in san) playFrom(promotionBag, volume = 0.30f)
if (san.endsWith('+')) playFrom(checkBag, volume = 0.18f)
// Do not add the check accent on '#': the result sequence follows immediately.
```

## Shuffle-bag selection

Do not call `Random.nextInt(pool.size)` for each move. A shuffle bag gives every recording a turn before repeating:

```kotlin
private class ShuffleBag(
    private val source: IntArray,
    private val random: Random = Random.Default,
) {
    private var remaining = IntArray(0)
    private var cursor = 0
    private var last = Int.MIN_VALUE

    fun next(): Int {
        if (cursor >= remaining.size) refill()
        return remaining[cursor++].also { last = it }
    }

    private fun refill() {
        remaining = source.copyOf().also { it.shuffle(random) }
        if (remaining.size > 1 && remaining[0] == last) {
            val swap = 1 + random.nextInt(remaining.lastIndex)
            val first = remaining[0]
            remaining[0] = remaining[swap]
            remaining[swap] = first
        }
        cursor = 0
    }
}
```

Use separate bags for move, capture, castle, check, promotion, and each finish tier. This prevents a firework or glass layer from influencing move selection.

## Completion-cue mapping

Map the current enum directly:

```kotlin
when (cue) {
    CompletionEffectCue.FIREWORK_LOW -> playFrom(fireworkLowBag, volume = 0.55f)
    CompletionEffectCue.FIREWORK_MID -> playFrom(fireworkMidBag, volume = 0.52f)
    CompletionEffectCue.FIREWORK_HIGH -> playFrom(fireworkHighBag, volume = 0.48f)
    CompletionEffectCue.GLASS_IMPACT -> playFrom(glassImpactBag, volume = 0.64f)
    CompletionEffectCue.GLASS_FRACTURE -> playFrom(glassFractureBag, volume = 0.52f)
    CompletionEffectCue.GLASS_SHARDS -> playFrom(glassShardsBag, volume = 0.42f)
}
```

The firework recordings are deliberately small pops. The low/mid/high names describe filtering and mix position, not larger explosions.

## Additional cues found in the current game

These are included but should remain optional and quiet:

| Cue | Current game hook | Recommendation |
| --- | --- | --- |
| Check | SAN ends in `+` | Layer at very low volume over the normal move. |
| Promotion | SAN contains `=` / promotion dialog completes | Layer once after the piece placement. |
| Castling | SAN starts `O-O` | Use the dedicated two-piece recording instead of a normal move. |
| Hint | Hint result becomes available | One soft wood cue; do not sound while the engine is merely thinking. |
| Low time | Clock crosses a threshold | Sound once at ten seconds, not every second. |
| Game start | A new board becomes playable | Optional two-piece settle, once per game. |
| Undo | Undo succeeds | Optional, very quiet; never sound for disabled-button taps. |

The pause/resume, flip, theme, Home, Rematch, and ordinary dialog buttons do **not** need bespoke sound effects. Android interaction feedback plus the tactile board sounds are enough; adding audio to every control would work against the premium board-game direction.

## Suggested gain hierarchy

- Normal move: `0.42–0.48`
- Capture / castle: `0.50–0.58`
- Check / promotion layer: `0.16–0.30`
- Hint / low-time: `0.20–0.30`
- Fireworks: `0.48–0.55`
- Glass: `0.42–0.64`, descending from impact to shards

Keep master SFX gain user-adjustable and add a mute switch before public release.

## Verification

```bash
python3 scripts/audio/verify_sampled_audio_pack.py
```

The verifier enforces exactly 50 ordinary move recordings, resource-safe names, manifest hashes, Ogg/Vorbis decodeability, mono 48 kHz output, and bounded clip durations.
