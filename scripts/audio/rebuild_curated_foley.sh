#!/usr/bin/env bash
set -euo pipefail

# Rebuilds only the curated runtime sounds replaced after the July 2026 listening audit.
# The five JJTaynos cuts and the mh2o contact are genuine chess-piece/board recordings.
# No slide, sweep, synthesized transient, or generic household "firework" source is used.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RAW="$ROOT/android/app/src/main/res/raw"
CONTACT_ROOT="$ROOT/docs/audio/source_recordings/CC0-processed-cuts"
FREESOUND_ROOT="$ROOT/docs/audio/source_recordings/CC0-Freesound-previews"
FFMPEG="${FFMPEG:-ffmpeg}"
EXPECTED_FFMPEG_VERSION="ffmpeg version 4.4.2-0ubuntu0.22.04.1"
EXPECTED_VORBIS_PACKAGE=$'libvorbisenc2:amd64\t1.3.7-1build2'

command -v "$FFMPEG" >/dev/null 2>&1 || {
    echo "FFmpeg is required (set FFMPEG or add ffmpeg to PATH)." >&2
    exit 1
}

# Ogg/Vorbis container bytes can change between otherwise compatible FFmpeg/libvorbis builds.
# Fail closed so an ordinary rebuild cannot silently replace the manifest's audited hashes.
# A deliberate toolchain audit may opt out, but must regenerate and independently review every
# resulting hash before updating the release lock.
if [[ "${DRAWLESS_ALLOW_UNPINNED_AUDIO_TOOLCHAIN:-0}" != "1" ]]; then
    actual_ffmpeg_version="$($FFMPEG -version | head -n 1)"
    [[ "$actual_ffmpeg_version" == "$EXPECTED_FFMPEG_VERSION"* ]] || {
        echo "Unsupported audio rebuild toolchain: $actual_ffmpeg_version" >&2
        echo "Expected: $EXPECTED_FFMPEG_VERSION (or set DRAWLESS_ALLOW_UNPINNED_AUDIO_TOOLCHAIN=1 for an audited migration)." >&2
        exit 1
    }
    if command -v dpkg-query >/dev/null 2>&1; then
        actual_vorbis_package="$(dpkg-query -W libvorbisenc2 2>/dev/null || true)"
        [[ "$actual_vorbis_package" == "$EXPECTED_VORBIS_PACKAGE" ]] || {
            echo "Unsupported libvorbis encoder package: ${actual_vorbis_package:-not installed}" >&2
            echo "Expected: $EXPECTED_VORBIS_PACKAGE (or use the audited migration override)." >&2
            exit 1
        }
    fi
fi

mkdir -p "$RAW"
STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT

declare -A CONTACT_FILE=(
    [d1]="$CONTACT_ROOT/disk_drop_1.wav"
    [d2]="$CONTACT_ROOT/disk_drop_2.wav"
    [d3]="$CONTACT_ROOT/disk_drop_3.wav"
    [d4]="$CONTACT_ROOT/disk_drop_4.wav"
    [d5]="$CONTACT_ROOT/disk_drop_5.wav"
    [mh]="$FREESOUND_ROOT/mh2o_alabaster.mp3"
)

# Fixed normalization brings each retained recording to roughly -5 dBFS before layering.
# Values are derived from the pinned source files and intentionally avoid dynamic processors
# that can smear the first board-contact transient.
declare -A CONTACT_GAIN_DB=(
    [d1]=20.0
    [d2]=15.8
    [d3]=15.6
    [d4]=-4.1
    [d5]=11.1
    [mh]=-3.7
)

for source in "${CONTACT_FILE[@]}"; do
    [[ -f "$source" ]] || { echo "Missing contact source: $source" >&2; exit 1; }
done

encode_vorbis() {
    local args=("$@")
    local output_index=$((${#args[@]} - 1))
    local output="${args[$output_index]}"
    unset 'args[output_index]'
    "$FFMPEG" -hide_banner -loglevel error -y "${args[@]}" \
        -map_metadata -1 -fflags +bitexact -flags:a +bitexact \
        -vn -ac 1 -ar 48000 -c:a libvorbis -q:a 5 -serial_offset 4242 "$output"
}

make_move() {
    local output="$1" primary="$2" secondary="$3" recipe_index="$4"
    local offset_ms=$((1 + recipe_index % 5))
    local duration_ms=$((108 + recipe_index % 5 * 7))
    local fade_start_ms=$((duration_ms - 24))
    local tone_hz=$((650 + recipe_index % 6 * 310))
    local tone_gain=$((recipe_index % 5 - 2))
    local secondary_db=$((-12 - recipe_index % 4))
    local duration fade_start
    printf -v duration '0.%03d' "$duration_ms"
    printf -v fade_start '0.%03d' "$fade_start_ms"

    encode_vorbis \
        -i "${CONTACT_FILE[$primary]}" -i "${CONTACT_FILE[$secondary]}" \
        -filter_complex \
        "[0:a]aresample=48000,aformat=channel_layouts=mono,highpass=f=90,lowpass=f=9000,volume=${CONTACT_GAIN_DB[$primary]}dB,equalizer=f=${tone_hz}:t=q:w=0.9:g=${tone_gain},atrim=0:0.145,asetpts=PTS-STARTPTS[p];[1:a]aresample=48000,aformat=channel_layouts=mono,highpass=f=110,lowpass=f=8500,volume=${CONTACT_GAIN_DB[$secondary]}dB,volume=${secondary_db}dB,atrim=0:0.140,asetpts=PTS-STARTPTS,adelay=${offset_ms}[s];[p][s]amix=inputs=2:duration=longest:normalize=0,volume=-1dB,apad=pad_dur=${duration},atrim=0:${duration},afade=t=out:st=${fade_start}:d=0.024[out]" \
        -map '[out]' "$STAGING/$output"
}

# These 22 slots formerly contained the rejected el_boss slide layer. Ordered source pairs,
# 1-5 ms contact offsets, restrained EQ and level changes provide audible variety while every
# variant remains a short physical placement.
MOVE_IDS=(01 04 07 08 09 10 14 15 21 22 26 28 29 32 35 36 37 42 43 48 49 50)
MOVE_PRIMARY=(d1 d2 d3 d4 d5 mh mh mh mh mh d1 d2 d3 d4 d5 d1 d2 d3 d4 d5 d1 d2)
MOVE_SECONDARY=(mh mh mh mh mh d1 d2 d3 d4 d5 d3 d4 d5 d1 d2 d4 d5 d1 d2 d3 d5 d3)
for index in "${!MOVE_IDS[@]}"; do
    make_move \
        "chess_move_wood_${MOVE_IDS[$index]}.ogg" \
        "${MOVE_PRIMARY[$index]}" \
        "${MOVE_SECONDARY[$index]}" \
        "$index"
done

make_capture() {
    local output="$1" removed="$2" placed="$3" body="$4" recipe_index="$5"
    local placement_delay_ms=$((48 + recipe_index % 6 * 6))
    local body_delay_ms=$((placement_delay_ms + 2 + recipe_index % 3))
    local duration_ms=$((172 + recipe_index % 4 * 12))
    local fade_start_ms=$((duration_ms - 32))
    local duration fade_start
    printf -v duration '0.%03d' "$duration_ms"
    printf -v fade_start '0.%03d' "$fade_start_ms"

    encode_vorbis \
        -i "${CONTACT_FILE[$removed]}" \
        -i "${CONTACT_FILE[$placed]}" \
        -i "${CONTACT_FILE[$body]}" \
        -filter_complex \
        "[0:a]aresample=48000,aformat=channel_layouts=mono,highpass=f=100,lowpass=f=8500,volume=${CONTACT_GAIN_DB[$removed]}dB,volume=-5dB,atrim=0:0.130,asetpts=PTS-STARTPTS[r];[1:a]aresample=48000,aformat=channel_layouts=mono,highpass=f=85,lowpass=f=9000,volume=${CONTACT_GAIN_DB[$placed]}dB,atrim=0:0.140,asetpts=PTS-STARTPTS,adelay=${placement_delay_ms}[p];[2:a]aresample=48000,aformat=channel_layouts=mono,highpass=f=70,lowpass=f=1800,volume=${CONTACT_GAIN_DB[$body]}dB,volume=-14dB,atrim=0:0.120,asetpts=PTS-STARTPTS,adelay=${body_delay_ms}[b];[r][p][b]amix=inputs=3:duration=longest:normalize=0,volume=-1.5dB,apad=pad_dur=${duration},atrim=0:${duration},afade=t=out:st=${fade_start}:d=0.032[out]" \
        -map '[out]' "$STAGING/$output"
}

# Captures are two real actions: a quieter removal followed by the firmer placement of the
# capturing piece. Rebuilding all 12 also removes generic door/tap layers from this pool.
CAPTURE_REMOVED=(d1 d2 d3 d4 d5 mh d2 d3 d4 d5 mh d1)
CAPTURE_PLACED=(d4 d5 mh d1 d2 d3 d5 mh d1 d2 d3 d4)
CAPTURE_BODY=(d2 d3 d4 d5 mh d1 d3 d4 d5 mh d1 d2)
for index in "${!CAPTURE_REMOVED[@]}"; do
    printf -v number '%02d' "$((index + 1))"
    make_capture \
        "chess_capture_wood_${number}.ogg" \
        "${CAPTURE_REMOVED[$index]}" \
        "${CAPTURE_PLACED[$index]}" \
        "${CAPTURE_BODY[$index]}" \
        "$index"
done

make_castle() {
    local output="$1" king="$2" rook="$3" recipe_index="$4"
    local gap_ms=$((92 + recipe_index * 9))
    local duration_ms=$((255 + recipe_index * 12))
    local fade_start_ms=$((duration_ms - 34))
    local duration fade_start
    printf -v duration '0.%03d' "$duration_ms"
    printf -v fade_start '0.%03d' "$fade_start_ms"

    encode_vorbis \
        -i "${CONTACT_FILE[$king]}" -i "${CONTACT_FILE[$rook]}" \
        -filter_complex \
        "[0:a]aresample=48000,aformat=channel_layouts=mono,highpass=f=90,lowpass=f=9000,volume=${CONTACT_GAIN_DB[$king]}dB,volume=-1.5dB,atrim=0:0.145,asetpts=PTS-STARTPTS[k];[1:a]aresample=48000,aformat=channel_layouts=mono,highpass=f=80,lowpass=f=8800,volume=${CONTACT_GAIN_DB[$rook]}dB,atrim=0:0.145,asetpts=PTS-STARTPTS,adelay=${gap_ms}[r];[k][r]amix=inputs=2:duration=longest:normalize=0,volume=-1.5dB,apad=pad_dur=${duration},atrim=0:${duration},afade=t=out:st=${fade_start}:d=0.034[out]" \
        -map '[out]' "$STAGING/$output"
}

CASTLE_KING=(d1 d2 d3 d4 d5 mh)
CASTLE_ROOK=(d4 d5 mh d1 d2 d3)
for index in "${!CASTLE_KING[@]}"; do
    printf -v number '%02d' "$((index + 1))"
    make_castle \
        "chess_castle_wood_${number}.ogg" \
        "${CASTLE_KING[$index]}" \
        "${CASTLE_ROOK[$index]}" \
        "$index"
done

FIREWORK_POPS="$FREESOUND_ROOT/rudmer_firework_pops.mp3"
FIREWORK_ROCKET="$FREESOUND_ROOT/rudmer_firework_rocket.mp3"
[[ -f "$FIREWORK_POPS" ]] || { echo "Missing firework source: $FIREWORK_POPS" >&2; exit 1; }
[[ -f "$FIREWORK_ROCKET" ]] || { echo "Missing firework source: $FIREWORK_ROCKET" >&2; exit 1; }

make_firework() {
    local output="$1" source="$2" start="$3" duration="$4" left="$5" right="$6" tone_gain="$7"
    # Fade positions are passed explicitly below to avoid deriving them with locale-sensitive tools.
    local fade_at
    case "$duration" in
        0.650) fade_at=0.570 ;;
        0.820) fade_at=0.720 ;;
        0.560) fade_at=0.490 ;;
        *) echo "Unsupported firework duration: $duration" >&2; exit 1 ;;
    esac

    encode_vorbis \
        -i "$source" \
        -filter_complex \
        "[0:a]atrim=start=${start}:duration=${duration},asetpts=PTS-STARTPTS,pan=mono|c0=${left}*c0+${right}*c1,aresample=48000,highpass=f=55,lowpass=f=17000,equalizer=f=2400:t=q:w=0.8:g=${tone_gain},volume=-1dB,afade=t=out:st=${fade_at}:d=0.070[out]" \
        -map '[out]' "$STAGING/$output"
}

# These are real pyrotechnic recordings. The first two tiers use the two separate small pops in
# Freesound 334042; the high tier uses the explosion from 336008. Alternate variants preserve
# different stereo microphone perspectives without pitch-shifting or household substitutes.
make_firework chess_firework_low_01.ogg  "$FIREWORK_POPS"  0.395 0.650 0.50 0.50 -1.0
make_firework chess_firework_low_02.ogg  "$FIREWORK_POPS"  0.395 0.650 0.68 0.32  0.5
make_firework chess_firework_mid_01.ogg  "$FIREWORK_POPS"  1.245 0.820 0.50 0.50  0.0
make_firework chess_firework_mid_02.ogg  "$FIREWORK_POPS"  1.245 0.820 0.32 0.68  1.0
make_firework chess_firework_high_01.ogg "$FIREWORK_ROCKET" 1.140 0.560 0.50 0.50  0.5
make_firework chess_firework_high_02.ogg "$FIREWORK_ROCKET" 1.140 0.560 0.67 0.33  1.5

cp "$STAGING"/*.ogg "$RAW/"
echo "Curated foley rebuild complete: 22 moves, 12 captures, 6 castles, 6 real fireworks."
