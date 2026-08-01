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
        -vn -ac 2 -ar 48000 -c:a libvorbis -q:a 5 -serial_offset 4242 "$output"
}

make_reel() {
    local output="$1" gap="$2"
    shift 2
    local files=("$@") inputs=() filter='' labels=''
    local index
    for index in "${!files[@]}"; do
        inputs+=( -i "${files[$index]}" )
        filter+="[$index:a]aresample=48000,aformat=channel_layouts=stereo,apad=pad_dur=${gap}[a${index}];"
        labels+="[a${index}]"
    done
    filter+="${labels}concat=n=${#files[@]}:v=0:a=1[out]"
    encode_preview "${inputs[@]}" -filter_complex "$filter" -map '[out]' "$output"
}

CAPTURES=()
for number in $(seq -w 1 12); do CAPTURES+=("$RAW/chess_capture_crush_${number}.ogg"); done
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

for preserved in preview-moves.ogg preview-fireworks.ogg preview-glass-loss.ogg; do
    [[ -f "$PREVIEWS/$preserved" ]] || {
        echo "Missing preserved curated reel: $PREVIEWS/$preserved" >&2
        exit 1
    }
done

# The capture family changed from a chess-contact layer to the selected stone-crush
# recording. Rebuild that reel and the aggregate while preserving unrelated reels.
make_reel "$STAGING/preview-captures-and-castling.ogg" 0.260 "${CAPTURES[@]}" "${CASTLES[@]}"
make_reel "$STAGING/audio-pack-preview.ogg" 0.800 \
    "$PREVIEWS/preview-moves.ogg" \
    "$STAGING/preview-captures-and-castling.ogg" \
    "$PREVIEWS/preview-fireworks.ogg" \
    "$PREVIEWS/preview-glass-loss.ogg"

cp "$STAGING/preview-captures-and-castling.ogg" "$PREVIEWS/"
cp "$STAGING/audio-pack-preview.ogg" "$PREVIEWS/"

{
    echo 'Drawless Chess curated sampled-audio audition order'
    echo
    echo 'preview-moves.ogg — every ordinary move is included; no subset is hidden:'
    for number in $(seq -w 1 50); do echo "  ${number}. chess_move_wood_${number}.ogg"; done
    echo
    echo 'preview-captures-and-castling.ogg:'
    track=1
    for number in $(seq -w 1 12); do printf '  %02d. chess_capture_crush_%s.ogg\n' "$track" "$number"; track=$((track + 1)); done
    for number in $(seq -w 1 6); do printf '  %02d. chess_castle_wood_0%s.ogg\n' "$track" "$number"; track=$((track + 1)); done
    echo
    echo 'preview-fireworks.ogg:'
    track=1
    for file in "${FIREWORKS[@]}"; do printf '  %02d. %s\n' "$track" "$(basename "$file")"; track=$((track + 1)); done
    echo
    echo 'preview-glass-loss.ogg — runtime-aligned variant 01 at 0 / 22 / 704 ms.'
    echo 'audio-pack-preview.ogg — the four reels above, in that order.'
} > "$PREVIEWS/audio-pack-preview-map.txt"

echo "Curated capture and aggregate audition reels rebuilt in $PREVIEWS"
