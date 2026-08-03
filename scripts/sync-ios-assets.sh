#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
android_res="$root/android/app/src/main/res"
ios_assets="$root/iosApp/DrawlessChess"
audio_dir="$ios_assets/Audio"
portrait_dir="$ios_assets/Portraits"
app_icon="$ios_assets/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"

mkdir -p "$audio_dir" "$portrait_dir" "$(dirname "$app_icon")"

# These pre-RC1 cues were replaced by the authored crush and mechanical identities.
for stale in \
  chess_capture_wood_01.m4a chess_capture_wood_02.m4a chess_capture_wood_03.m4a \
  chess_check_crystal_01.m4a chess_check_crystal_02.m4a chess_check_crystal_03.m4a; do
  rm -f "$audio_dir/$stale"
done

for source in "$android_res"/drawable-nodpi/opponent_*.webp "$android_res"/drawable-nodpi/home_hero_kings.webp; do
  name="$(basename "${source%.webp}")"
  ffmpeg -hide_banner -loglevel error -y -i "$source" "$portrait_dir/$name.png"
done

patterns=()
append_series() {
  local prefix="$1"
  local last="$2"
  local index name
  for ((index = 1; index <= last; index++)); do
    printf -v name '%s_%02d.ogg' "$prefix" "$index"
    patterns+=("$name")
  done
}

append_series chess_move_wood 50
append_series chess_capture_crush 12
append_series chess_castle_wood 6
append_series chess_promotion 4
append_series chess_hint 3
append_series chess_low_time 4
append_series chess_game_start 3
append_series chess_undo 3
append_series chess_firework_low 2
append_series chess_firework_mid 2
append_series chess_firework_high 2
append_series chess_glass_impact 3
append_series chess_glass_fracture 3
append_series chess_glass_shards 3

patterns+=(
  chess_check_mechanical_02.ogg
  chess_en_passant_brick_01.ogg
  chess_checkmate_stone_01.ogg
)

for name in "${patterns[@]}"; do
  source="$android_res/raw/$name"
  target="$audio_dir/${name%.ogg}.m4a"
  [[ -f "$source" ]] || {
    echo "Missing audited audio asset: $source" >&2
    exit 1
  }
  ffmpeg -hide_banner -loglevel error -y -i "$source" -c:a aac -b:a 96k "$target"
done

cp "$android_res/resources.properties" "$audio_dir/ATTRIBUTION.properties"
swift "$root/scripts/RenderAppIcon.swift" "$app_icon"
echo "Synced iOS app icon, portraits and sampled audio."
