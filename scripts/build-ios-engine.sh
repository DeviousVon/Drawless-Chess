#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
native_root="$root/engine/native"
source_root="$native_root/upstream/Fairy-Stockfish"
source_dir="$source_root/src"
manifest="$native_root/source-manifest.txt"
properties="$native_root/upstream.properties"
bridge_dir="$root/android/engine/src/main/cpp"
header_dir="$root/ios-engine/include"
output_root="$root/build/ios-engine"

bash "$root/scripts/native-validate-structure.sh" --require-source

property() {
  sed -n "s/^$1=//p" "$properties"
}

upstream_revision="$(property revision)"
upstream_tree="$(property tree)"
patched_tree="$(property patchedTree)"
patch_series_sha="$(property patchSeriesSha256)"
patch_version="$(property drawlessPatchVersion)"
bridge_version="$(property nativeBridgeAbiVersion)"

for required in \
  "$upstream_revision" "$upstream_tree" "$patched_tree" "$patch_series_sha" \
  "$patch_version" "$bridge_version"; do
  [[ -n "$required" ]] || {
    echo "build-ios-engine: native lock is incomplete" >&2
    exit 1
  }
done

task_tmp="$(mktemp -d "${TMPDIR:-/tmp}/drawless-ios-engine.XXXXXX")"
cleanup() {
  case "$task_tmp" in
    "${TMPDIR:-/tmp}"/drawless-ios-engine.*) rm -rf -- "$task_tmp" ;;
    *) echo "build-ios-engine: refusing unsafe cleanup path: $task_tmp" >&2 ;;
  esac
}
trap cleanup EXIT

sources=()
while IFS= read -r relative_source || [[ -n "$relative_source" ]]; do
  case "$relative_source" in
    ""|\#*) continue ;;
  esac
  sources+=("$source_dir/$relative_source")
done < "$manifest"
sources+=(
  "$bridge_dir/native_bridge.cpp"
  "$bridge_dir/native_identity.cpp"
)

common_flags=(
  -std=c++17
  -stdlib=libc++
  # RC1's native rules patch performs full legal-set checks inside search. Match the Android
  # debug/test engine's optimization level so real analysis budgets remain meaningful.
  -O2
  -Wall
  -Wcast-qual
  -fno-exceptions
  -fno-strict-aliasing
  -fvisibility=hidden
  -fvisibility-inlines-hidden
  -pthread
  -I"$source_dir"
  -I"$header_dir"
  -DIS_64BIT
  -DUSE_PTHREADS
  -DNNUE_EMBEDDING_OFF
  -DDRAWLESS_HOST_BRIDGE_TEST
  -DDRAWLESS_APPLE_BRIDGE
  "-DDRAWLESS_UPSTREAM_REVISION=\"$upstream_revision\""
  "-DDRAWLESS_UPSTREAM_TREE=\"$upstream_tree\""
  "-DDRAWLESS_PATCHED_TREE=\"$patched_tree\""
  "-DDRAWLESS_PATCH_SERIES_SHA256=\"$patch_series_sha\""
  "-DDRAWLESS_PATCH_VERSION=$patch_version"
  "-DDRAWLESS_BRIDGE_ABI_VERSION=$bridge_version"
)

compile_slice() {
  local name="$1"
  local sdk="$2"
  local target="$3"
  shift 3
  local slice_dir="$task_tmp/$name"
  local object_dir="$slice_dir/objects"
  local sdk_path
  sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
  mkdir -p "$object_dir"

  local index=0
  local source_file
  local object_file
  for source_file in "${sources[@]}"; do
    object_file="$object_dir/$(printf '%03d' "$index").o"
    xcrun --sdk "$sdk" clang++ \
      -target "$target" \
      -isysroot "$sdk_path" \
      "${common_flags[@]}" \
      "$@" \
      -c "$source_file" \
      -o "$object_file"
    index=$((index + 1))
  done

  xcrun ar rcs "$slice_dir/libdrawless_fairy.a" "$object_dir"/*.o
}

compile_slice simulator-x86_64 iphonesimulator x86_64-apple-ios15.0-simulator \
  -DUSE_SSE2 -DNO_PREFETCH -msse2 &
simulator_x86_pid=$!
compile_slice simulator-arm64 iphonesimulator arm64-apple-ios15.0-simulator \
  -DUSE_NEON -DUSE_POPCNT &
simulator_arm_pid=$!
compile_slice device-arm64 iphoneos arm64-apple-ios15.0 \
  -DUSE_NEON -DUSE_POPCNT &
device_arm_pid=$!

build_failed=0
wait "$simulator_x86_pid" || build_failed=1
wait "$simulator_arm_pid" || build_failed=1
wait "$device_arm_pid" || build_failed=1
[[ "$build_failed" == 0 ]] || {
  echo "build-ios-engine: one or more Apple slices failed to compile" >&2
  exit 1
}

mkdir -p "$task_tmp/simulator-universal"
xcrun lipo -create \
  "$task_tmp/simulator-x86_64/libdrawless_fairy.a" \
  "$task_tmp/simulator-arm64/libdrawless_fairy.a" \
  -output "$task_tmp/simulator-universal/libdrawless_fairy.a"

xcodebuild -create-xcframework \
  -library "$task_tmp/simulator-universal/libdrawless_fairy.a" \
  -headers "$header_dir" \
  -library "$task_tmp/device-arm64/libdrawless_fairy.a" \
  -headers "$header_dir" \
  -output "$task_tmp/DrawlessFairy.xcframework"

mkdir -p "$output_root"
if [[ -e "$output_root/DrawlessFairy.xcframework" ]]; then
  case "$output_root/DrawlessFairy.xcframework" in
    "$root"/build/ios-engine/DrawlessFairy.xcframework)
      rm -rf -- "$output_root/DrawlessFairy.xcframework"
      ;;
    *)
      echo "build-ios-engine: refusing unsafe generated-artifact replacement" >&2
      exit 1
      ;;
  esac
fi
mv "$task_tmp/DrawlessFairy.xcframework" "$output_root/DrawlessFairy.xcframework"
mkdir -p "$output_root/slices"
install -m 0644 "$task_tmp/simulator-x86_64/libdrawless_fairy.a" \
  "$output_root/slices/libdrawless_fairy-simulator-x86_64.a"
install -m 0644 "$task_tmp/simulator-arm64/libdrawless_fairy.a" \
  "$output_root/slices/libdrawless_fairy-simulator-arm64.a"
install -m 0644 "$task_tmp/device-arm64/libdrawless_fairy.a" \
  "$output_root/slices/libdrawless_fairy-device-arm64.a"

echo "Built $output_root/DrawlessFairy.xcframework"
xcrun lipo -info "$task_tmp/simulator-universal/libdrawless_fairy.a"
