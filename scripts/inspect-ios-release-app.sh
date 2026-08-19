#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
inspection_mode=signed
scanner_self_test=false
if [[ "${1:-}" == --release-channel-scanner-self-test ]]; then
  scanner_self_test=true
  shift
elif [[ "${1:-}" == --unsigned-rebuild ]]; then
  inspection_mode=unsigned-rebuild
  shift
fi
app="${1:-}"
archive="${2:-}"

[[ "$scanner_self_test" == true ]] ||
  [[ "$#" -ge 1 && "$#" -le 2 && -n "$app" && -d "$app" ]] || {
  echo 'Usage: inspect-ios-release-app.sh [--unsigned-rebuild] "/path/Drawless Chess.app" [archive.xcarchive]' >&2
  exit 2
}
[[ "$inspection_mode" == signed || -z "$archive" ]] || {
  echo 'STOP: --unsigned-rebuild does not accept an xcarchive' >&2
  exit 2
}

for tool in base64 cmp codesign dwarfdump file nm otool plutil rg security shasum sips xcrun; do
  command -v "$tool" >/dev/null || {
    echo "STOP: missing required tool: $tool" >&2
    exit 1
  }
done
plist_buddy='/usr/libexec/PlistBuddy'
[[ -x "$plist_buddy" ]] || {
  echo "STOP: missing required tool: $plist_buddy" >&2
  exit 1
}
[[ -x /usr/bin/ruby ]] || {
  echo 'STOP: missing required tool: /usr/bin/ruby' >&2
  exit 1
}

fail() {
  echo "STOP: $*" >&2
  exit 1
}

verify_bundled_resource() {
  local source_relative="$1"
  local bundled_name="$2"
  local description="$3"
  local source="$root/$source_relative"
  local bundled="$app/$bundled_name"
  local bundled_sha256

  [[ -f "$source" && ! -L "$source" ]] ||
    fail "$description source is missing, not regular, or a symbolic link: $source_relative"
  [[ -f "$bundled" && ! -L "$bundled" ]] ||
    fail "$description is missing, not regular, or a symbolic link in the app root: $bundled_name"
  cmp -s "$source" "$bundled" ||
    fail "$description differs from the exact candidate source: $bundled_name"
  bundled_sha256="$(shasum -a 256 "$bundled" | awk '{print $1}')"
  [[ "$bundled_sha256" =~ ^[0-9a-f]{64}$ ]] ||
    fail "$description SHA-256 could not be resolved: $bundled_name"
  printf '%s' "$bundled_sha256"
}

forbidden_ascii_pattern='DRAWLESS_(XCTEST_|PHYSICAL_REVIEW_PROBE|ENGINE_SEARCH_GRACE_MILLIS|VARIANTS_PATH)'
forbidden_utf16le_pattern='D\x00R\x00A\x00W\x00L\x00E\x00S\x00S\x00_\x00(X\x00C\x00T\x00E\x00S\x00T\x00_\x00|P\x00H\x00Y\x00S\x00I\x00C\x00A\x00L\x00_\x00R\x00E\x00V\x00I\x00E\x00W\x00_\x00P\x00R\x00O\x00B\x00E\x00|E\x00N\x00G\x00I\x00N\x00E\x00_\x00S\x00E\x00A\x00R\x00C\x00H\x00_\x00G\x00R\x00A\x00C\x00E\x00_\x00M\x00I\x00L\x00L\x00I\x00S\x00|V\x00A\x00R\x00I\x00A\x00N\x00T\x00S\x00_\x00P\x00A\x00T\x00H\x00)'
forbidden_utf16be_pattern='\x00D\x00R\x00A\x00W\x00L\x00E\x00S\x00S\x00_(\x00X\x00C\x00T\x00E\x00S\x00T\x00_|\x00P\x00H\x00Y\x00S\x00I\x00C\x00A\x00L\x00_\x00R\x00E\x00V\x00I\x00E\x00W\x00_\x00P\x00R\x00O\x00B\x00E|\x00E\x00N\x00G\x00I\x00N\x00E\x00_\x00S\x00E\x00A\x00R\x00C\x00H\x00_\x00G\x00R\x00A\x00C\x00E\x00_\x00M\x00I\x00L\x00L\x00I\x00S|\x00V\x00A\x00R\x00I\x00A\x00N\x00T\x00S\x00_\x00P\x00A\x00T\x00H)'

validate_scanner_positive_controls() {
  local marker
  for marker in \
    DRAWLESS_XCTEST_POSITIVE_CONTROL \
    DRAWLESS_PHYSICAL_REVIEW_PROBE \
    DRAWLESS_ENGINE_SEARCH_GRACE_MILLIS \
    DRAWLESS_VARIANTS_PATH
  do
    LC_ALL=C rg -a -q "$forbidden_ascii_pattern" <(printf '%s' "$marker") ||
      fail "ASCII release-seam scanner positive control did not match: $marker"
    LC_ALL=C rg -a -q "$forbidden_utf16le_pattern" \
      <(MARKER="$marker" /usr/bin/ruby -e \
        'print ENV.fetch("MARKER").encode("UTF-16LE")') ||
      fail "UTF-16LE release-seam scanner positive control did not match: $marker"
    LC_ALL=C rg -a -q "$forbidden_utf16be_pattern" \
      <(MARKER="$marker" /usr/bin/ruby -e \
        'print ENV.fetch("MARKER").encode("UTF-16BE")') ||
      fail "UTF-16BE release-seam scanner positive control did not match: $marker"
  done
}

scan_release_binary_for_pattern() {
  local release_binary="$1"
  local encoding="$2"
  local forbidden_pattern="$3"
  local scan_result
  set +e
  LC_ALL=C rg -a -q "$forbidden_pattern" "$release_binary"
  scan_result="$?"
  set -e
  case "$scan_result" in
    0)
      fail "test-only environment seam is present in Release binary $(basename "$release_binary") ($encoding)"
      ;;
    1) ;;
    *)
      fail "could not search Release binary for test seams: $release_binary"
      ;;
  esac
}

scan_release_binary_for_test_seams() {
  local release_binary="$1"
  scan_release_binary_for_pattern "$release_binary" ASCII "$forbidden_ascii_pattern"
  scan_release_binary_for_pattern "$release_binary" UTF-16LE "$forbidden_utf16le_pattern"
  scan_release_binary_for_pattern "$release_binary" UTF-16BE "$forbidden_utf16be_pattern"
}

binary_contains_encoded_literal() {
  local release_binary="$1"
  local marker="$2"
  local encoding="$3"
  BINARY="$release_binary" MARKER="$marker" ENCODING="$encoding" \
    /usr/bin/ruby -e '
      begin
        binary = File.binread(ENV.fetch("BINARY"))
        marker = ENV.fetch("MARKER").encode(ENV.fetch("ENCODING")).b
        exit(binary.include?(marker) ? 0 : 1)
      rescue StandardError => error
        warn error.message
        exit 2
      end
    '
}

is_exact_thin_arm64_macho_description() {
  [[ "$1" == 'Mach-O 64-bit executable arm64' ]]
}

validate_release_channel_architecture_controls() {
  local description
  is_exact_thin_arm64_macho_description \
    'Mach-O 64-bit executable arm64' ||
    fail 'thin arm64 Mach-O architecture positive control was rejected'
  for description in \
    'Mach-O 64-bit executable arm64e' \
    'Mach-O universal binary with 2 architectures: [x86_64] [arm64:Mach-O 64-bit executable arm64]'
  do
    if is_exact_thin_arm64_macho_description "$description"; then
      fail "non-thin-arm64 architecture poison was accepted: $description"
    fi
  done
}

arm64_disassembly_contains_swift_small_string_literal() {
  local disassembly_file="$1"
  local marker="$2"
  local context_mode="${3:-release-channel}"
  local architecture="${4:-arm64}"
  DISASSEMBLY_FILE="$disassembly_file" MARKER="$marker" \
    CONTEXT_MODE="$context_mode" ARCHITECTURE="$architecture" \
    /usr/bin/ruby -e '
    begin
      lines = File.readlines(ENV.fetch("DISASSEMBLY_FILE"),
                             encoding: "UTF-8", chomp: true)
      marker = ENV.fetch("MARKER").b
      mode = ENV.fetch("CONTEXT_MODE")
      architecture = ENV.fetch("ARCHITECTURE")
      exit 2 unless architecture == "arm64"
      exit 2 unless marker.bytesize.between?(9, 15)
      exit 2 unless ["release-channel", "anywhere"].include?(mode)

      instructions = []
      seen_addresses = {}
      lines.each do |line|
        match = line.match(/\A([0-9a-f]{16})[\t ]+(.+)\z/i)
        next unless match
        address = Integer(match[1], 16)
        exit 2 unless (address % 4).zero?
        exit 2 if seen_addresses.key?(address)
        seen_addresses[address] = true
        instructions << { address: address, text: match[2].strip }
      end
      exit 2 if instructions.empty?
      exit 2 unless instructions.each_cons(2).all? do |left, right|
        left[:address] < right[:address]
      end

      constants = []
      instructions.each_with_index do |instruction, index|
        initial = instruction[:text].match(
          /\Amov\s+x([0-9]|[12][0-9]|30),\s+#0x([0-9a-f]{1,4})\z/i
        )
        next unless initial
        register = initial[1]
        value = initial[2].to_i(16)
        valid = [16, 32, 48].each_with_index.all? do |expected_shift, offset|
          continuation = instructions[index + offset + 1]
          next false unless continuation
          next false unless continuation[:address] ==
                            instruction[:address] + ((offset + 1) * 4)
          match = continuation[:text].match(
            /\Amovk\s+x([0-9]|[12][0-9]|30),\s+#0x([0-9a-f]{1,4}),\s+lsl\s+#(\d+)\z/i
          )
          next false unless match && match[1] == register
          next false unless match[3].to_i == expected_shift
          value |= match[2].to_i(16) << expected_shift
          true
        end
        next unless valid
        constants << {
          first_address: instruction[:address],
          last_address: instruction[:address] + 12,
          value: value,
        }
      end

      first_word = marker.byteslice(0, 8).unpack1("Q<")
      tail = marker.byteslice(8, marker.bytesize - 8)
      tagged_tail = tail.ljust(7, "\0") + [(0xe0 | marker.bytesize)].pack("C")
      second_word = tagged_tail.unpack1("Q<")
      first_matches = constants.select { |entry| entry[:value] == first_word }
      second_matches = constants.select { |entry| entry[:value] == second_word }

      literal_references = instructions.select do |instruction|
        instruction[:text].match?(
          /\Aadd\s+x([0-9]|[12][0-9]|30),\s+x\1,\s+#0x[0-9a-f]+\s+;\s+literal pool for: "releaseChannel\.marker"\z/i
        )
      end

      bounded_return_free = lambda do |addresses|
        low = addresses.min
        high = addresses.max
        next false if high - low > 0x180
        region = instructions.select do |instruction|
          instruction[:address].between?(low, high)
        end
        expected_count = ((high - low) / 4) + 1
        next false unless region.length == expected_count
        next false unless region.each_cons(2).all? do |left, right|
          right[:address] == left[:address] + 4
        end
        region.none? { |instruction| instruction[:text].match?(/\Aret(?:aa|ab)?(?:\s|\z)/i) }
      end

      first_matches.product(second_matches).each do |first, second|
        if mode == "anywhere"
          addresses = [first[:first_address], first[:last_address],
                       second[:first_address], second[:last_address]]
          exit 0 if bounded_return_free.call(addresses)
          next
        end
        literal_references.each do |reference|
          addresses = [first[:first_address], first[:last_address],
                       second[:first_address], second[:last_address],
                       reference[:address]]
          exit 0 if bounded_return_free.call(addresses)
        end
      end
      exit 1
    rescue StandardError => error
      warn error.message
      exit 2
    end
  '
}

validate_release_channel_scanner_positive_controls() {
  local controls valid private candidate scan_result description fixture_address
  local scanner_architecture
  controls="$(mktemp -d -t drawless-release-marker-controls)"
  valid="$controls/valid"
  private="$controls/private"

  printf '%s\n' \
    'fixture: stripped otool -tvV output' \
    '(__TEXT,__text) section' \
    '<__text>:' \
    '0000000100001000  mov x25, #0x2d65' \
    '0000000100001004  movk x25, #0x6f63, lsl #16' \
    '0000000100001008  movk x25, #0x6572, lsl #32' \
    '000000010000100c  movk x25, #0xee00, lsl #48' \
    '0000000100001010  bl 0x100003000' \
    '0000000100001014  nop' \
    '0000000100001018  add x8, x8, #0xd0 ; literal pool for: "releaseChannel.marker"' \
    '000000010000101c  bl 0x100003020' \
    '0000000100001020  mov x8, #0x7061' \
    '0000000100001024  movk x8, #0x2d70, lsl #16' \
    '0000000100001028  movk x8, #0x7473, lsl #32' \
    '000000010000102c  movk x8, #0x726f, lsl #48' \
    '0000000100001030  bl 0x100003040' \
    '0000000100001034  ret' > "$valid"
  arm64_disassembly_contains_swift_small_string_literal \
    "$valid" app-store-core release-channel arm64 || {
    rm -rf -- "$controls"
    fail 'ARM64 Swift small-string scanner positive control did not match app-store-core'
  }

  printf '%s\n' \
    'fixture: stripped private marker with numeric calls' \
    '(__TEXT,__text) section' \
    '<__text>:' \
    '0000000100002000  mov x20, #0x7270' \
    '0000000100002004  movk x20, #0x7669, lsl #16' \
    '0000000100002008  movk x20, #0x7461, lsl #32' \
    '000000010000200c  movk x20, #0x2d65, lsl #48' \
    '0000000100002010  bl 0x100004000' \
    '0000000100002014  mov x19, #0x6572' \
    '0000000100002018  movk x19, #0x6976, lsl #16' \
    '000000010000201c  movk x19, #0x7765, lsl #32' \
    '0000000100002020  movk x19, #0xee00, lsl #48' \
    '0000000100002024  ret' > "$private"
  arm64_disassembly_contains_swift_small_string_literal \
    "$private" private-review anywhere arm64 || {
    rm -rf -- "$controls"
    fail 'ARM64 Swift small-string scanner positive control did not match private-review'
  }

  candidate="$controls/altered"
  sed 's/#0x7061/#0x7062/' "$valid" > "$candidate"
  for description in altered missing reordered wrong-register wrong-shift \
    ret-separated key-outside-window unbound raw-path-only appended-bytes-only \
    debug-metadata-only malformed x86 arm64e universal
  do
    case "$description" in
      altered) ;;
      missing)
        candidate="$controls/$description"
        sed '/movk x8, #0x2d70/d' "$valid" > "$candidate"
        ;;
      reordered)
        candidate="$controls/$description"
        sed -e 's/#0x2d70/#0xaaaa/' -e 's/#0x7473/#0x2d70/' \
          -e 's/#0xaaaa/#0x7473/' "$valid" > "$candidate"
        ;;
      wrong-register)
        candidate="$controls/$description"
        sed 's/movk x8, #0x2d70/movk x9, #0x2d70/' "$valid" > "$candidate"
        ;;
      wrong-shift)
        candidate="$controls/$description"
        sed 's/#0x2d70, lsl #16/#0x2d70, lsl #24/' "$valid" > "$candidate"
        ;;
      ret-separated)
        candidate="$controls/$description"
        sed 's/0000000100001014  nop/0000000100001014  ret/' \
          "$valid" > "$candidate"
        ;;
      key-outside-window)
        candidate="$controls/$description"
        sed -e 's/add x8, x8, #0xd0 ; literal pool for: "releaseChannel.marker"/nop/' \
          -e 's/0000000100001034  ret/0000000100001034  nop/' \
          "$valid" > "$candidate"
        fixture_address=$((0x100001038))
        while [[ "$fixture_address" -lt $((0x100001190)) ]]; do
          printf '%016x  nop\n' "$fixture_address" >> "$candidate"
          fixture_address=$((fixture_address + 4))
        done
        printf '%s\n' \
          '0000000100001190  add x8, x8, #0xd0 ; literal pool for: "releaseChannel.marker"' \
          >> "$candidate"
        ;;
      unbound)
        candidate="$controls/$description"
        sed 's/add x8, x8, #0xd0 ; literal pool for: "releaseChannel.marker"/nop/' \
          "$valid" > "$candidate"
        ;;
      raw-path-only)
        candidate="$controls/$description"
        printf '%s\n' \
          'LINKEDIT/path-app-store-core-poison/DrawlessChess' \
          'releaseChannel.marker app-store-core' \
          '0000000100003000  bl 0x100005000' \
          '0000000100003004  ret' > "$candidate"
        ;;
      appended-bytes-only)
        candidate="$controls/$description"
        printf '%s\n' \
          '0000000100004000  nop' \
          '0000000100004004  ret' \
          'APPENDED_BYTES=releaseChannel.marker:app-store-core' > "$candidate"
        ;;
      debug-metadata-only)
        candidate="$controls/$description"
        printf '%s\n' \
          'DEBUG_PATH=/tmp/app-store-core/releaseChannel.marker.swift' \
          '0000000100005000  bl 0x100006000' \
          '0000000100005004  ret' > "$candidate"
        ;;
      malformed)
        candidate="$controls/$description"
        printf '%s\n' \
          '0000000100006000  nop' \
          '0000000100006000  ret' > "$candidate"
        ;;
      x86|arm64e|universal)
        candidate="$valid"
        ;;
    esac
    case "$description" in
      x86) scanner_architecture=x86_64 ;;
      arm64e) scanner_architecture=arm64e ;;
      universal) scanner_architecture='x86_64 arm64' ;;
      *) scanner_architecture=arm64 ;;
    esac
    set +e
    arm64_disassembly_contains_swift_small_string_literal \
      "$candidate" app-store-core release-channel "$scanner_architecture"
    scan_result="$?"
    set -e
    [[ "$scan_result" -ne 0 ]] || {
      rm -rf -- "$controls"
      fail "$description poison satisfied the ARM64 release-channel scanner"
    }
    if [[ "$description" == malformed || "$description" == x86 || \
      "$description" == arm64e || "$description" == universal ]]; then
      [[ "$scan_result" -eq 2 ]] || {
        rm -rf -- "$controls"
        fail "$description poison was not rejected as malformed or non-arm64"
      }
    fi
  done

  candidate="$controls/core-and-private"
  cp "$valid" "$candidate"
  tail -n +4 "$private" >> "$candidate"
  arm64_disassembly_contains_swift_small_string_literal \
    "$candidate" app-store-core release-channel arm64 || {
    rm -rf -- "$controls"
    fail 'core-and-private control lost its core marker proof'
  }
  set +e
  arm64_disassembly_contains_swift_small_string_literal \
    "$candidate" private-review anywhere arm64
  scan_result="$?"
  set -e
  rm -rf -- "$controls"
  [[ "$scan_result" -eq 0 ]] ||
    fail 'core-and-private poison did not expose the private marker pair'
}

validate_core_only_review_scanner_positive_controls() {
  local marker encoding control
  control="$(mktemp -t drawless-review-scanner)"
  for marker in game.postGame.reviewGate review.header review.board; do
    for encoding in UTF-8 UTF-16LE UTF-16BE; do
      MARKER="$marker" ENCODING="$encoding" /usr/bin/ruby -e '
        print ENV.fetch("MARKER").encode(ENV.fetch("ENCODING"))
      ' > "$control"
      binary_contains_encoded_literal "$control" "$marker" "$encoding" || {
        rm -f -- "$control"
        fail "core-only review scanner positive control did not match: $marker ($encoding)"
      }
    done
  done
  rm -f -- "$control"
}

reject_core_only_review_identifiers() {
  local release_binary="$1"
  local marker encoding scan_result
  for marker in game.postGame.reviewGate review.header review.board; do
    for encoding in UTF-8 UTF-16LE UTF-16BE; do
      set +e
      binary_contains_encoded_literal "$release_binary" "$marker" "$encoding"
      scan_result="$?"
      set -e
      case "$scan_result" in
        0)
          fail "Game Review UI identifier is present in app-store-core executable: $marker ($encoding)"
          ;;
        1) ;;
        *)
          fail "could not scan app-store-core executable for $marker ($encoding)"
          ;;
      esac
    done
  done
}

require_core_only_release_channel_marker() {
  local release_binary="$1"
  local disassembly encoding executable_file_info scan_result
  executable_file_info="$(file -b "$release_binary")" ||
    fail 'file could not inspect the release executable before channel verification'
  is_exact_thin_arm64_macho_description "$executable_file_info" ||
    fail 'release-channel verification requires an arm64 iOS Mach-O executable'
  binary_contains_encoded_literal \
    "$release_binary" releaseChannel.marker UTF-8 ||
    fail 'release-channel preference key is missing from the executable'

  disassembly="$(mktemp -t drawless-release-channel-disassembly)"
  otool -tvV "$release_binary" > "$disassembly" || {
    rm -f -- "$disassembly"
    fail 'could not disassemble the release executable for channel verification'
  }
  set +e
  arm64_disassembly_contains_swift_small_string_literal \
    "$disassembly" app-store-core release-channel arm64
  scan_result="$?"
  set -e
  case "$scan_result" in
    0) ;;
    1)
      rm -f -- "$disassembly"
      fail 'app-store-core executable marker is missing from the release-channel logic'
      ;;
    *)
      rm -f -- "$disassembly"
      fail 'could not prove the optimized app-store-core executable marker'
      ;;
  esac

  set +e
  arm64_disassembly_contains_swift_small_string_literal \
    "$disassembly" private-review anywhere arm64
  scan_result="$?"
  set -e
  rm -f -- "$disassembly"
  case "$scan_result" in
    0) fail 'private-review optimized marker is present in app-store-core executable' ;;
    1) ;;
    *) fail 'could not scan the optimized private-review executable marker' ;;
  esac

  for encoding in UTF-8 UTF-16LE UTF-16BE; do
    set +e
    binary_contains_encoded_literal "$release_binary" private-review "$encoding"
    scan_result="$?"
    set -e
    case "$scan_result" in
      0) fail "private-review channel marker is present in app-store-core executable ($encoding)" ;;
      1) ;;
      *) fail "could not scan private-review executable marker ($encoding)" ;;
    esac
  done
}

validate_scanner_positive_controls
validate_core_only_review_scanner_positive_controls
validate_release_channel_architecture_controls
validate_release_channel_scanner_positive_controls

if [[ "$scanner_self_test" == true ]]; then
  if [[ -n "$app" ]]; then
    [[ -f "$app" ]] || fail "scanner self-test executable is missing: $app"
    require_core_only_release_channel_marker "$app"
  fi
  printf 'release-channel scanner self-test PASS\n'
  exit 0
fi

plist="$app/Info.plist"
privacy="$app/PrivacyInfo.xcprivacy"
[[ -f "$plist" ]] || fail "Info.plist is missing from $app"
[[ -f "$privacy" ]] || fail "PrivacyInfo.xcprivacy is missing from the app root"
plutil -lint "$plist" "$privacy" >/dev/null

license_sha256="$(verify_bundled_resource LICENSE LICENSE 'GNU GPL license')"
apache_license_sha256="$(
  verify_bundled_resource APACHE-2.0.txt APACHE-2.0.txt 'Apache 2.0 license'
)"
notice_sha256="$(verify_bundled_resource NOTICE NOTICE 'project notice')"
third_party_notices_sha256="$(
  verify_bundled_resource THIRD_PARTY_NOTICES.md THIRD_PARTY_NOTICES.md \
    'third-party notices'
)"
engine_source_notice_sha256="$(
  verify_bundled_resource engine/native/SOURCE_NOTICE.txt SOURCE_NOTICE.txt \
    'Fairy-Stockfish source notice'
)"
privacy_manifest_sha256="$(
  verify_bundled_resource \
    iosApp/DrawlessChess/PrivacyInfo.xcprivacy \
    PrivacyInfo.xcprivacy \
    'privacy manifest'
)"

bundle_id="$(plutil -extract CFBundleIdentifier raw -o - "$plist")"
short_version="$(plutil -extract CFBundleShortVersionString raw -o - "$plist")"
build_version="$(plutil -extract CFBundleVersion raw -o - "$plist")"
minimum_os="$(plutil -extract MinimumOSVersion raw -o - "$plist")"
sdk_name="$(plutil -extract DTSDKName raw -o - "$plist")"
encryption="$(plutil -extract ITSAppUsesNonExemptEncryption raw -o - "$plist")"
executable_name="$(plutil -extract CFBundleExecutable raw -o - "$plist")"
executable="$app/$executable_name"
expected_short_version="$(awk -F'"' '/MARKETING_VERSION:/ { print $2; exit }' "$root/iosApp/project.yml")"
expected_build_version="$(awk -F'"' '/CURRENT_PROJECT_VERSION:/ { print $2; exit }' "$root/iosApp/project.yml")"

[[ "$bundle_id" == com.drawlesschess ]] || fail "unexpected bundle ID: $bundle_id"
[[ "$short_version" == "$expected_short_version" ]] || fail "unexpected marketing version: $short_version"
[[ "$build_version" == "$expected_build_version" ]] || fail "unexpected build version: $build_version"
[[ "$minimum_os" == 15.0 ]] || fail "unexpected minimum iOS: $minimum_os"
case "$inspection_mode:$sdk_name" in
  signed:iphoneos26*|unsigned-rebuild:iphoneos26*|unsigned-rebuild:iphonesimulator26*) ;;
  *) fail "app was not built with an accepted iOS 26 SDK for $inspection_mode: $sdk_name" ;;
esac
[[ "$encryption" == false ]] || fail "export-compliance encryption flag is not false"
[[ -f "$executable" ]] || fail "app executable is missing: $executable"
require_core_only_release_channel_marker "$executable"
[[ ! -e "$app/$executable_name.debug.dylib" ]] ||
  fail 'debug dylib is present in the purported Release app'
[[ ! -e "$app/__preview.dylib" ]] ||
  fail 'SwiftUI preview dylib is present in the purported Release app'

release_scan_binaries=("$executable")
if [[ -d "$app/Frameworks" ]]; then
  framework_candidates="$(
    find "$app/Frameworks" -type f -maxdepth 3 | LC_ALL=C sort
  )" || fail 'could not enumerate bundled frameworks'
  if [[ -n "$framework_candidates" ]]; then
    while IFS= read -r framework_binary; do
      framework_description="$(file "$framework_binary")" ||
        fail "file could not inspect bundled framework item: $framework_binary"
      if rg -q 'Mach-O|current ar archive' <<<"$framework_description"; then
        release_scan_binaries+=("$framework_binary")
      fi
    done <<<"$framework_candidates"
  fi
fi
for release_binary in "${release_scan_binaries[@]}"; do
  scan_release_binary_for_test_seams "$release_binary"
done
reject_core_only_review_identifiers "$executable"

DEVICE_FAMILY="$(plutil -extract UIDeviceFamily json -o - "$plist")" \
SUPPORTED_PLATFORMS="$(plutil -extract CFBundleSupportedPlatforms json -o - "$plist")" \
INSPECTION_MODE="$inspection_mode" \
/usr/bin/ruby -rjson -e '
  abort "UIDeviceFamily must contain iPhone and iPad" unless
    JSON.parse(ENV.fetch("DEVICE_FAMILY")) == [1, 2]
  platforms = JSON.parse(ENV.fetch("SUPPORTED_PLATFORMS"))
  expected = if ENV.fetch("INSPECTION_MODE") == "signed"
    [["iPhoneOS"]]
  else
    [["iPhoneOS"], ["iPhoneSimulator"]]
  end
  abort "CFBundleSupportedPlatforms differs for the inspection mode" unless
    expected.include?(platforms)
' || exit 1
profile_plist=''
entitlements_plist=''
certificate_dir=''
portrait_compare_dir=''
cleanup() {
  [[ -z "$profile_plist" ]] || rm -f -- "$profile_plist"
  [[ -z "$entitlements_plist" ]] || rm -f -- "$entitlements_plist"
  [[ -z "$certificate_dir" ]] || rm -rf -- "$certificate_dir"
  [[ -z "$portrait_compare_dir" ]] || rm -rf -- "$portrait_compare_dir"
}
trap cleanup EXIT

if [[ "$inspection_mode" == signed ]]; then
  expected_apple_team_id="${DRAWLESS_EXPECTED_APPLE_TEAM_ID:-}"
  [[ "$expected_apple_team_id" =~ ^[A-Z0-9]{10}$ ]] ||
    fail 'signed inspection requires a valid DRAWLESS_EXPECTED_APPLE_TEAM_ID'
  expected_application_identifier="$expected_apple_team_id.com.drawlesschess"

  codesign --verify --deep --strict --verbose=2 "$app"
  signature_info="$(codesign -dvv "$app" 2>&1)"
  grep -Fqx "TeamIdentifier=$expected_apple_team_id" <<<"$signature_info" ||
    fail 'release app is not signed by the expected Apple team'
  if grep -Eq '^Authority=Apple Development:' <<<"$signature_info"; then
    signing_mode='development'
  elif grep -Eq '^Authority=Apple Distribution:' <<<"$signature_info"; then
    signing_mode='distribution'
  else
    fail 'release app does not have an Apple Development or Distribution signature'
  fi

  embedded_profile="$app/embedded.mobileprovision"
  [[ -f "$embedded_profile" ]] || fail 'embedded.mobileprovision is missing'
  profile_plist="$(mktemp -t drawless-profile)"
  security cms -D -i "$embedded_profile" > "$profile_plist"
  plutil -lint "$profile_plist" >/dev/null
  profile_team="$(plutil -extract TeamIdentifier.0 raw -o - "$profile_plist")"
  profile_app_id="$(plutil -extract Entitlements.application-identifier raw -o - "$profile_plist")"
  profile_expiration="$(plutil -extract ExpirationDate raw -o - "$profile_plist")"
  [[ "$profile_team" == "$expected_apple_team_id" ]] ||
    fail 'provisioning profile has an unexpected Apple team'
  [[ "$profile_app_id" == "$expected_application_identifier" ]] ||
    fail 'provisioning profile has an unexpected application identifier'
  PROFILE_EXPIRATION="$profile_expiration" /usr/bin/ruby -rtime -e '
    abort "embedded provisioning profile is expired" unless
      Time.iso8601(ENV.fetch("PROFILE_EXPIRATION")) > Time.now
  ' || exit 1

  entitlements_plist="$(mktemp -t drawless-entitlements)"
  codesign -d --entitlements - --xml "$app" > "$entitlements_plist" 2>/dev/null
  plutil -lint "$entitlements_plist" >/dev/null
  signed_app_id="$(
    "$plist_buddy" -c 'Print :application-identifier' "$entitlements_plist"
  )" || fail 'signed application-identifier entitlement is missing'
  signed_team="$(
    "$plist_buddy" -c 'Print :com.apple.developer.team-identifier' "$entitlements_plist"
  )" || fail 'signed team-identifier entitlement is missing'
  signed_get_task_allow="$(
    "$plist_buddy" -c 'Print :get-task-allow' "$entitlements_plist"
  )" || fail 'signed get-task-allow entitlement is missing'
  profile_get_task_allow="$(
    plutil -extract Entitlements.get-task-allow raw -o - "$profile_plist"
  )" || fail 'profile get-task-allow entitlement is missing'
  [[ "$signed_app_id" == "$expected_application_identifier" ]] ||
    fail 'signed application identifier differs from the expected identifier'
  [[ "$signed_team" == "$expected_apple_team_id" ]] ||
    fail 'signed Apple team differs from the expected team'
  [[ "$signed_get_task_allow" == "$profile_get_task_allow" ]] ||
    fail 'signed get-task-allow entitlement differs from the provisioning profile'
  if [[ "$signing_mode" == development ]]; then
    [[ "$signed_get_task_allow" == true ]] ||
      fail 'development-signed device build does not allow debugger attachment'
  else
    [[ "$signed_get_task_allow" == false ]] ||
      fail 'distribution-signed Release app unexpectedly allows debugger attachment'
  fi

  certificate_dir="$(mktemp -d -t drawless-certificates)"
  codesign --display --extract-certificates="$certificate_dir/signer" "$app" >/dev/null 2>&1
  [[ -f "$certificate_dir/signer0" ]] || fail 'codesign did not extract the leaf signer certificate'
  signer_sha1="$(shasum -a 1 "$certificate_dir/signer0" | awk '{print toupper($1)}')"
  if [[ "$signing_mode" == development ]]; then
    expected_development_signer_sha1="${DRAWLESS_EXPECTED_APPLE_DEVELOPMENT_CERTIFICATE_SHA1:-}"
    [[ "$expected_development_signer_sha1" =~ ^[0-9A-F]{40}$ ]] ||
      fail 'development-signed inspection requires a valid expected certificate SHA-1'
    [[ "$signer_sha1" == "$expected_development_signer_sha1" ]] ||
      fail 'the Release app was not signed by the pinned Apple Development identity'
  fi

  profile_certificate_index=0
  profile_contains_signer=false
  while profile_certificate_base64="$(
    plutil -extract "DeveloperCertificates.$profile_certificate_index" raw -o - \
      "$profile_plist" 2>/dev/null
  )"; do
    printf '%s' "$profile_certificate_base64" | base64 -D \
      > "$certificate_dir/profile-certificate"
    if cmp -s "$certificate_dir/signer0" "$certificate_dir/profile-certificate"; then
      profile_contains_signer=true
    fi
    profile_certificate_index=$((profile_certificate_index + 1))
  done
  [[ "$profile_certificate_index" -gt 0 ]] || fail 'provisioning profile has no developer certificates'
  [[ "$profile_contains_signer" == true ]] ||
    fail 'the app signer certificate is not allowed by the embedded provisioning profile'
else
  signing_mode=unsigned-rebuild
  profile_expiration=not-applicable
  [[ ! -e "$app/embedded.mobileprovision" ]] ||
    fail 'unsigned rebuild unexpectedly contains embedded.mobileprovision'
  [[ ! -e "$app/_CodeSignature" ]] ||
    fail 'unsigned rebuild unexpectedly contains a code-signature directory'
  set +e
  codesign --verify --deep --strict "$app" >/dev/null 2>&1
  unsigned_verify_status="$?"
  set -e
  [[ "$unsigned_verify_status" -ne 0 ]] ||
    fail 'unsigned rebuild unexpectedly has a valid code signature'
fi

executable_file_info="$(file "$executable")" ||
  fail 'file could not inspect the release executable'
if [[ "$inspection_mode" == signed ]]; then
  rg -q 'Mach-O 64-bit executable arm64' <<<"$executable_file_info" ||
    fail "release executable is not arm64 iOS Mach-O"
else
  rg -q 'Mach-O 64-bit executable (arm64|x86_64)' <<<"$executable_file_info" ||
    fail "unsigned rebuild executable is not a supported 64-bit Mach-O"
fi
vtool_build_info="$(xcrun vtool -show-build "$executable")" ||
  fail 'vtool could not inspect the release executable'
if [[ "$sdk_name" == iphonesimulator* ]]; then
  rg -q 'platform IOSSIMULATOR' <<<"$vtool_build_info" ||
    fail "unsigned simulator rebuild is not an iOS-simulator binary"
else
  rg -q 'platform IOS' <<<"$vtool_build_info" ||
    fail "release executable is not an iOS-platform binary"
fi

dependencies="$(otool -L "$executable")" || fail 'otool could not inspect release dependencies'
while IFS= read -r dependency; do
  case "$dependency" in
    /System/Library/*|/usr/lib/*|@rpath/*) ;;
    *) fail "unexpected linked dependency: $dependency" ;;
  esac
done < <(tail -n +2 <<<"$dependencies" | awk '{print $1}')

PRIVACY_MANIFEST="$(plutil -convert json -o - "$privacy")" \
/usr/bin/ruby -rjson -e '
  manifest = JSON.parse(ENV.fetch("PRIVACY_MANIFEST"))
  abort "privacy tracking must be false" unless manifest["NSPrivacyTracking"] == false
  abort "privacy tracking domains must be empty" unless
    manifest["NSPrivacyTrackingDomains"] == []
  abort "privacy collected-data declarations must be empty" unless
    manifest["NSPrivacyCollectedDataTypes"] == []
  entries = manifest.fetch("NSPrivacyAccessedAPITypes")
  abort "privacy manifest must contain exactly three required-reason entries" unless
    entries.length == 3
  categories = entries.map { |entry| entry.fetch("NSPrivacyAccessedAPIType") }
  abort "privacy required-reason categories must be unique" unless
    categories.uniq.length == categories.length
  actual = entries.to_h do |entry|
    [entry.fetch("NSPrivacyAccessedAPIType"), entry.fetch("NSPrivacyAccessedAPITypeReasons")]
  end
  expected = {
    "NSPrivacyAccessedAPICategoryUserDefaults" => ["CA92.1"],
    "NSPrivacyAccessedAPICategorySystemBootTime" => ["35F9.1"],
    "NSPrivacyAccessedAPICategoryFileTimestamp" => ["C617.1"],
  }
  abort "bundled privacy required-reason declarations differ" unless actual == expected
' || exit 1

undefined_symbols="$(nm -u "$executable")" || fail 'nm could not inspect release symbols'
if rg -q ' _fstat$' <<<"$undefined_symbols"; then
  privacy_api_json="$(
    plutil -extract NSPrivacyAccessedAPITypes json -o - "$privacy"
  )" || fail 'could not read required-reason API declarations'
  rg -q 'NSPrivacyAccessedAPICategoryFileTimestamp' <<<"$privacy_api_json" ||
    fail "binary imports fstat without the FileTimestamp privacy category"
fi

expected_lprojs='de.lproj en.lproj es-419.lproj fr.lproj pt-BR.lproj '
actual_lprojs="$(
  find "$app" -maxdepth 1 -type d -name '*.lproj' -exec basename {} \; |
    LC_ALL=C sort |
    tr '\n' ' '
)"
[[ "$actual_lprojs" == "$expected_lprojs" ]] ||
  fail "bundled localization set differs: $actual_lprojs"
for locale in de en es-419 fr pt-BR; do
  source_strings_file="$root/iosApp/DrawlessChess/$locale.lproj/Localizable.strings"
  strings_file="$app/$locale.lproj/Localizable.strings"
  [[ -f "$source_strings_file" ]] || fail "source localization is missing: $locale"
  [[ -f "$strings_file" ]] || fail "bundled localization is missing: $locale"
  plutil -lint "$source_strings_file" "$strings_file" >/dev/null
  /usr/bin/ruby -rjson -e '
    source = JSON.parse(File.read(ARGV.fetch(0)))
    bundled = JSON.parse(File.read(ARGV.fetch(1)))
    abort "localized dictionaries differ" unless source == bundled
  ' \
    <(plutil -convert json -o - "$source_strings_file") \
    <(plutil -convert json -o - "$strings_file") ||
    fail "bundled localization content differs from source: $locale"
done

source_audio_count="$(find "$root/iosApp/DrawlessChess/Audio" -maxdepth 1 -type f -name '*.m4a' | wc -l | tr -d ' ')"
app_audio_count="$(find "$app" -maxdepth 1 -type f -name '*.m4a' | wc -l | tr -d ' ')"
[[ "$source_audio_count" == 103 ]] ||
  fail "source audio catalog has $source_audio_count files instead of 103"
[[ "$app_audio_count" == "$source_audio_count" ]] ||
  fail "bundled audio count $app_audio_count differs from source count $source_audio_count"
source_audio_names="$(
  find "$root/iosApp/DrawlessChess/Audio" -maxdepth 1 -type f -name '*.m4a' -exec basename {} \; |
    LC_ALL=C sort
)"
app_audio_names="$(
  find "$app" -maxdepth 1 -type f -name '*.m4a' -exec basename {} \; |
    LC_ALL=C sort
)"
[[ "$app_audio_names" == "$source_audio_names" ]] ||
  fail 'bundled audio filenames differ from source'
while IFS= read -r audio; do
  cmp -s "$audio" "$app/$(basename "$audio")" ||
    fail "bundled audio differs from source: $(basename "$audio")"
done < <(find "$root/iosApp/DrawlessChess/Audio" -maxdepth 1 -type f -name '*.m4a' | sort)

source_portrait_names="$(
  find "$root/iosApp/DrawlessChess/Portraits" -maxdepth 1 -type f -name '*.png' -exec basename {} \; |
    LC_ALL=C sort
)"
source_portrait_count="$(find "$root/iosApp/DrawlessChess/Portraits" -maxdepth 1 -type f -name '*.png' | wc -l | tr -d ' ')"
[[ "$source_portrait_count" == 9 ]] ||
  fail "source portrait catalog has $source_portrait_count files instead of 9"
app_portrait_names="$(
  find "$app" -maxdepth 1 -type f -name '*.png' \
    ! -name 'AppIcon*.png' -exec basename {} \; |
    LC_ALL=C sort
)"
[[ "$app_portrait_names" == "$source_portrait_names" ]] ||
  fail 'bundled portrait filenames differ from source'
portrait_compare_dir="$(mktemp -d -t drawless-portraits)"
while IFS= read -r portrait; do
  portrait_name="$(basename "$portrait")"
  bundled_portrait="$app/$portrait_name"
  [[ -f "$bundled_portrait" ]] || fail "bundled portrait is missing: $portrait_name"
  sips -s format png "$portrait" \
    --out "$portrait_compare_dir/source.png" >/dev/null ||
    fail "could not normalize source portrait: $portrait_name"
  sips -s format png "$bundled_portrait" \
    --out "$portrait_compare_dir/bundled.png" >/dev/null ||
    fail "could not normalize bundled portrait: $portrait_name"
  cmp -s "$portrait_compare_dir/source.png" "$portrait_compare_dir/bundled.png" ||
    fail "bundled portrait pixels differ from source: $portrait_name"
done < <(find "$root/iosApp/DrawlessChess/Portraits" -maxdepth 1 -type f -name '*.png' | sort)
[[ -f "$app/Assets.car" ]] || fail 'compiled asset catalog is missing'
[[ "$(plutil -extract CFBundleIcons.CFBundlePrimaryIcon.CFBundleIconName raw -o - "$plist")" == AppIcon ]] ||
  fail 'iPhone primary app icon is not AppIcon'
[[ "$(plutil -extract 'CFBundleIcons~ipad'.CFBundlePrimaryIcon.CFBundleIconName raw -o - "$plist")" == AppIcon ]] ||
  fail 'iPad primary app icon is not AppIcon'
[[ -f "$app/variants.ini" ]] || fail 'variants.ini is missing from the app bundle'
cmp -s "$root/engine/variants.ini" "$app/variants.ini" ||
  fail 'bundled variants.ini differs from source'

if [[ -n "$archive" ]]; then
  [[ -d "$archive" ]] || fail "archive does not exist: $archive"
  archive_app="$archive/Products/Applications/Drawless Chess.app"
  archive_plist="$archive/Info.plist"
  archive_executable="$archive_app/Drawless Chess"
  [[ -f "$archive_plist" ]] || fail 'archive Info.plist is missing'
  [[ -f "$archive_executable" ]] || fail 'archive app executable is missing'
  plutil -lint "$archive_plist" >/dev/null
  archive_bundle_id="$(plutil -extract ApplicationProperties.CFBundleIdentifier raw -o - "$archive_plist")"
  archive_short_version="$(plutil -extract ApplicationProperties.CFBundleShortVersionString raw -o - "$archive_plist")"
  archive_build_version="$(plutil -extract ApplicationProperties.CFBundleVersion raw -o - "$archive_plist")"
  [[ "$archive_bundle_id" == "$bundle_id" ]] || fail 'archive bundle ID differs from app'
  [[ "$archive_short_version" == "$short_version" ]] || fail 'archive marketing version differs from app'
  [[ "$archive_build_version" == "$build_version" ]] || fail 'archive build version differs from app'
  dsym_binary="$archive/dSYMs/Drawless Chess.app.dSYM/Contents/Resources/DWARF/Drawless Chess"
  [[ -f "$dsym_binary" ]] || fail "archive dSYM executable is missing"
  app_uuids="$(dwarfdump --uuid "$executable" | awk '{print $2}' | sort)"
  archive_app_uuids="$(dwarfdump --uuid "$archive_executable" | awk '{print $2}' | sort)"
  dsym_uuids="$(dwarfdump --uuid "$dsym_binary" | awk '{print $2}' | sort)"
  [[ "$app_uuids" == "$archive_app_uuids" ]] || fail 'inspected app and archived app UUIDs differ'
  [[ "$app_uuids" == "$dsym_uuids" ]] || fail 'app and dSYM UUIDs differ'
fi

printf 'bundle_id=%s\nversion=%s\nbuild=%s\nminimum_os=%s\nsdk=%s\n' \
  "$bundle_id" "$short_version" "$build_version" "$minimum_os" "$sdk_name"
printf 'release_channel=app-store-core\n'
printf 'release_channel_evidence=arm64-swift-small-string\n'
printf 'signing_mode=%s\nprofile_expiration=%s\n' "$signing_mode" "$profile_expiration"
printf 'license_sha256=%s\napache_license_sha256=%s\nnotice_sha256=%s\n' \
  "$license_sha256" "$apache_license_sha256" "$notice_sha256"
printf 'third_party_notices_sha256=%s\nengine_source_notice_sha256=%s\n' \
  "$third_party_notices_sha256" "$engine_source_notice_sha256"
printf 'privacy_manifest_sha256=%s\n' "$privacy_manifest_sha256"
printf 'legal_resources=5\nprivacy_manifests=1\n'
printf 'core_only_review_identifier_scan=PASS\n'
printf 'audio_assets=%s\nportrait_assets=%s\nlocalizations=5\nrelease_app_inspection=PASS\n' \
  "$app_audio_count" "$source_portrait_count"
