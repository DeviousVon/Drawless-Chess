#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
compiler="$root/node_modules/kotlin-compiler/bin/kotlinc"
core_jar="$root/build/kotlin-core-tests.jar"
log="$root/build/compose-structure.log"
runtime_jar="$root/build/app-runtime-structure.jar"
engine_jar="$root/build/android-engine-structure.jar"

[[ -x "$compiler" ]] || { echo "Kotlin compiler missing" >&2; exit 1; }
[[ -f "$core_jar" ]] || { echo "Run npm run test:kotlin first" >&2; exit 1; }

mapfile -t sources < <(find "$root/android/app/src/main/kotlin" -name '*.kt' -print | sort)
[[ ${#sources[@]} -ge 4 ]] || { echo "Compose source set is incomplete" >&2; exit 1; }

mapfile -t engine_sources < <(find "$root/android/engine/src/main/kotlin" -name '*.kt' -print | sort)
[[ ${#engine_sources[@]} -ge 5 ]] || { echo "Android engine source set is incomplete" >&2; exit 1; }

"$compiler" -jvm-target 17 -classpath "$core_jar" \
  "$root/scripts/stubs/android/content/res/AssetManager.kt" \
  "$root/scripts/stubs/android/content/Context.kt" \
  "$root/scripts/stubs/com/drawlesschess/engine/BuildConfig.kt" \
  "${engine_sources[@]}" \
  -d "$engine_jar"

"$compiler" -jvm-target 17 -classpath "$core_jar:$engine_jar" \
  "$root/scripts/stubs/android/content/Context.kt" \
  "$root/scripts/stubs/android/os/SystemClock.kt" \
  "$root/scripts/stubs/android/util/Log.kt" \
  "$root/scripts/stubs/com/drawlesschess/BuildConfig.kt" \
  "$root/scripts/stubs/com/drawlesschess/R.kt" \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GamePacing.kt" \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/StartingColor.kt" \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameRuntime.kt" \
  -d "$runtime_jar"

mapfile -t compose_sources < <(printf '%s\n' "${sources[@]}" | rg -v '/(GamePacing|GameRuntime|StartingColor)\.kt$')
set +e
"$compiler" -jvm-target 17 -classpath "$core_jar:$engine_jar:$runtime_jar" "${compose_sources[@]}" \
  -d "$root/build/compose-structure.jar" >"$log" 2>&1
set -e

# Android/Compose symbols are intentionally unresolved in this container. Kotlin still
# parses every file before reporting those missing dependencies, so fail on parser errors.
if rg -n "syntax error|expecting an element|unexpected tokens|missing '}'|unclosed comment" "$log"; then
  echo "Compose source contains Kotlin parser errors" >&2
  exit 1
fi

rg -q 'fun DrawlessApp\(' "$root/android/app/src/main/kotlin"
rg -q 'fun GameRoute\(' "$root/android/app/src/main/kotlin"
rg -q 'fun CompletionEffectOverlay\(' "$root/android/app/src/main/kotlin"
rg -q 'drawVictoryFireworks' "$root/android/app/src/main/kotlin/com/drawlesschess/ui/CompletionEffectOverlay.kt"
rg -q 'drawDefeatCracks' "$root/android/app/src/main/kotlin/com/drawlesschess/ui/CompletionEffectOverlay.kt"
rg -q 'durationMillis = 2_600' "$root/android/app/src/main/kotlin/com/drawlesschess/ui/CompletionEffectTimeline.kt"
rg -q 'durationMillis = 2_200' "$root/android/app/src/main/kotlin/com/drawlesschess/ui/CompletionEffectTimeline.kt"
rg -q 'CompletionEffectCue.GLASS_FRACTURE, 0.05f' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/CompletionEffectTimeline.kt"
rg -q 'class GameSoundPlayer' "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameSoundPlayer.kt"
rg -Fq 'GameSoundPlayer(context: Context)' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameSoundPlayer.kt"
rg -Fq 'fun setEnabled(value: Boolean)' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameSoundPlayer.kt"
rg -q 'PENDING_SAMPLE_MAX_AGE_MS = 250L' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameSoundPlayer.kt"
rg -q 'object SampledSoundCatalog' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/SampledSoundCatalog.kt"
rg -Fq 'soundPlayer.playMove(latestSan)' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameScreen.kt"
sample_count=$(find "$root/android/app/src/main/res/raw" -maxdepth 1 -type f -name 'chess_*.ogg' | wc -l)
[[ $sample_count -eq 104 ]] || {
  echo "Expected 104 sampled audio resources, found $sample_count" >&2
  exit 1
}
# The deterministic renderer is retained as reference/test material, not production playback.
rg -q 'SOUND_SAMPLE_RATE = 44_100' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/ProceduralGameAudio.kt"
rg -q 'renderCompletionCue' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/ProceduralGameAudio.kt"
rg -Fq 'testTag("post_game_feedback")' "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameScreen.kt"
rg -q 'class MainActivity' "$root/android/app/src/main/kotlin"
rg -q 'android.intent.action.MAIN' "$root/android/app/src/main/AndroidManifest.xml"
rg -Fq 'android:launchMode="singleTask"' "$root/android/app/src/main/AndroidManifest.xml"
rg -Uq 'fun exitGame\(\)[^{]*\{[^}]*runtime = null[^}]*previous\?\.close\(\)[^}]*route = AppRoute\.HOME' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/DrawlessAppViewModel.kt"
rg -q 'closed\.compareAndSet\(false, true\)' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameRuntime.kt"
rg -Fq 'implementation(project(":engine"))' "$root/android/app/build.gradle.kts"
rg -q 'BuildConfig.USE_DEVELOPMENT_ENGINE' "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameRuntime.kt"
rg -q 'AndroidFairyEngineFactory' "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameRuntime.kt"
rg -q 'class AndroidFairyEngineFactory' \
  "$root/android/engine/src/main/kotlin/com/drawlesschess/engine/AndroidFairyEngineFactory.kt"
rg -Fq 'fun create(): AndroidFairyEngineSession' \
  "$root/android/engine/src/main/kotlin/com/drawlesschess/engine/AndroidFairyEngineFactory.kt"

if rg -n 'catch.*DevelopmentChessEngine|runCatching.*DevelopmentChessEngine' \
  "$root/android/app/src/main/kotlin/com/drawlesschess/ui/GameRuntime.kt"; then
  echo "Native startup failure must never trigger a silent development-engine fallback" >&2
  exit 1
fi

if ! rg -Uq 'release\s*\{[^}]*USE_DEVELOPMENT_ENGINE", "false"' "$root/android/app/build.gradle.kts"; then
  echo "Release must hard-disable the development engine" >&2
  exit 1
fi

if rg -n 'TODO\(|FIXME' "$root/android/app/src/main/kotlin"; then
  echo "Compose source contains unfinished markers" >&2
  exit 1
fi

echo "PASSED Compose source structure checks (full Android type-check pending SDK environment)"
