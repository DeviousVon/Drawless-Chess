#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RAW="$ROOT/android/app/src/main/res/raw"
PREVIEWS="$ROOT/docs/audio/previews"
FFMPEG="${FFMPEG:-ffmpeg}"
STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT

mkdir -p "$PREVIEWS"

encode_preview() {
    local args=("$@")
    local output_index=$((${#args[@]} - 1))
    local output="${args[$output_index]}"
    unset 'args[output_index]'
    "$FFMPEG" -hide_banner -loglevel error -y "${args[@]}" \
        -map_metadata -1 -fflags +bitexact -flags:a +bitexact \
        -vn -ac 1 -ar 48000 -c:a libvorbis -q:a 5 -serial_offset 4242 "$output"
}

make_reel() {
    local output="$1" gap="$2"
    shift 2
    local files=("$@") inputs=() filter='' labels=''
    local index
    for index in "${!files[@]}"; do
        inputs+=( -i "${files[$index]}" )
        filter+="[$index:a]aresample=48000,aformat=channel_layouts=mono,apad=pad_dur=${gap}[a${index}];"
        labels+="[a${index}]"
    done
    filter+="${labels}concat=n=${#files[@]}:v=0:a=1[out]"
    encode_preview "${inputs[@]}" -filter_complex "$filter" -map '[out]' "$output"
}

MOVES=()
for number in $(seq -w 1 50); do MOVES+=("$RAW/chess_move_wood_${number}.ogg"); done
CAPTURES=()
for number in $(seq -w 1 12); do CAPTURES+=("$RAW/chess_capture_wood_${number}.ogg"); done
CASTLES=()
for number in $(seq -w 1 6); do CASTLES+=("$RAW/chess_castle_wood_0${number}.ogg"); done
FIREWORKS=(
    "$RAW/chess_firework_low_01.ogg"
    "$RAW/chess_firework_low_02.ogg"
    "$RAW/chess_firework_mid_01.ogg"
    "$RAW/chess_firework_mid_02.ogg"
    "$RAW/chess_firework_high_01.ogg"
    "$RAW/chess_firework_high_02.ogg"
)

make_reel "$STAGING/preview-moves.ogg" 0.180 "${MOVES[@]}"
make_reel "$STAGING/preview-captures-and-castling.ogg" 0.260 "${CAPTURES[@]}" "${CASTLES[@]}"
make_reel "$STAGING/preview-fireworks.ogg" 0.500 "${FIREWORKS[@]}"

# Defeat timing mirrors the runtime: impact at 0, recognizable fracture at 22 ms, shards at 704 ms.
encode_preview \
    -i "$RAW/chess_glass_impact_01.ogg" \
    -i "$RAW/chess_glass_fracture_01.ogg" \
    -i "$RAW/chess_glass_shards_01.ogg" \
    -filter_complex \
    '[0:a]aresample=48000,apad=pad_dur=2.2[i];[1:a]aresample=48000,adelay=22,apad=pad_dur=2.178[f];[2:a]aresample=48000,adelay=704,apad=pad_dur=1.496[s];[i][f][s]amix=inputs=3:duration=longest:normalize=0,atrim=0:2.2[out]' \
    -map '[out]' "$STAGING/preview-glass-loss.ogg"

make_reel "$STAGING/audio-pack-preview.ogg" 0.800 \
    "$STAGING/preview-moves.ogg" \
    "$STAGING/preview-captures-and-castling.ogg" \
    "$STAGING/preview-fireworks.ogg" \
    "$STAGING/preview-glass-loss.ogg"

cp "$STAGING"/*.ogg "$PREVIEWS/"

{
    echo 'Drawless Chess curated sampled-audio audition order'
    echo
    echo 'preview-moves.ogg — every ordinary move is included; no subset is hidden:'
    for number in $(seq -w 1 50); do echo "  ${number}. chess_move_wood_${number}.ogg"; done
    echo
    echo 'preview-captures-and-castling.ogg:'
    track=1
    for number in $(seq -w 1 12); do printf '  %02d. chess_capture_wood_%s.ogg\n' "$track" "$number"; track=$((track + 1)); done
    for number in $(seq -w 1 6); do printf '  %02d. chess_castle_wood_0%s.ogg\n' "$track" "$number"; track=$((track + 1)); done
    echo
    echo 'preview-fireworks.ogg:'
    track=1
    for file in "${FIREWORKS[@]}"; do printf '  %02d. %s\n' "$track" "$(basename "$file")"; track=$((track + 1)); done
    echo
    echo 'preview-glass-loss.ogg — runtime-aligned variant 01 at 0 / 22 / 704 ms.'
    echo 'audio-pack-preview.ogg — the four reels above, in that order.'
} > "$PREVIEWS/audio-pack-preview-map.txt"

echo "Curated audition reels rebuilt in $PREVIEWS"
