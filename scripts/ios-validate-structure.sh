#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
content="$root/iosApp/DrawlessChess/ContentView.swift"
model="$root/iosApp/DrawlessChess/DrawlessChessModel.swift"
visuals="$root/iosApp/DrawlessChess/BoardVisuals.swift"
runtime="$root/multiplatform/shared-core/src/commonMain/kotlin/com/drawlesschess/shared/SharedGameRuntime.kt"
feedback="$root/iosApp/DrawlessChess/GameFeedback.swift"
portraits="$root/iosApp/DrawlessChess/Portraits"
audio="$root/iosApp/DrawlessChess/Audio"
app_icon="$root/iosApp/DrawlessChess/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
privacy_manifest="$root/iosApp/DrawlessChess/PrivacyInfo.xcprivacy"

[[ -f "$visuals" ]] || { echo "iOS code-native board renderer is missing" >&2; exit 1; }

[[ -f "$privacy_manifest" ]] || {
  echo "iOS privacy manifest is missing" >&2
  exit 1
}
plutil -lint "$privacy_manifest" >/dev/null || {
  echo "iOS privacy manifest is invalid" >&2
  exit 1
}

privacy_tracking="$(plutil -extract NSPrivacyTracking raw -o - "$privacy_manifest")"
privacy_domains="$(plutil -extract NSPrivacyTrackingDomains json -o - "$privacy_manifest")"
privacy_collection="$(plutil -extract NSPrivacyCollectedDataTypes json -o - "$privacy_manifest")"
privacy_apis="$(plutil -extract NSPrivacyAccessedAPITypes json -o - "$privacy_manifest")"

[[ "$privacy_tracking" == "false" && "$privacy_domains" == "[]" ]] || {
  echo "iOS privacy manifest must declare tracking disabled with no tracking domains" >&2
  exit 1
}
[[ "$privacy_collection" == "[]" ]] || {
  echo "iOS privacy manifest must declare no collected data types" >&2
  exit 1
}

PRIVACY_APIS="$privacy_apis" /usr/bin/ruby -rjson -e '
  entries = JSON.parse(ENV.fetch("PRIVACY_APIS"))
  expected = {
    "NSPrivacyAccessedAPICategoryUserDefaults" => ["CA92.1"],
    "NSPrivacyAccessedAPICategorySystemBootTime" => ["35F9.1"],
    "NSPrivacyAccessedAPICategoryFileTimestamp" => ["C617.1"],
  }
  actual = entries.to_h do |entry|
    [entry.fetch("NSPrivacyAccessedAPIType"), entry.fetch("NSPrivacyAccessedAPITypeReasons")]
  end
  abort "iOS privacy manifest required-reason declarations differ" unless
    entries.length == expected.length && actual == expected
' || exit 1

for theme in imperial_marble desert_sandstone glacier_slate verdigris_copper amethyst_geode; do
  rg -Fq "\"$theme\"" "$visuals" || {
    echo "iOS board renderer is missing theme: $theme" >&2
    exit 1
  }
done

for texture in sandstone marble slate verdigris amethyst; do
  rg -q "case $texture" "$visuals" || {
    echo "iOS board renderer is missing procedural texture: $texture" >&2
    exit 1
  }
done

for piece in pawn knight bishop rook queen king; do
  rg -q "case $piece" "$visuals" || {
    echo "iOS board renderer is missing code-native piece: $piece" >&2
    exit 1
  }
done

for token in whiteQueenAccent blackQueenAccent; do
  rg -Fq "$token" "$visuals" || {
    echo "iOS piece palette is missing frozen Android token: $token" >&2
    exit 1
  }
done

rg -Fq 'CGPoint(x: 77, y: 20)' "$visuals" || {
  echo "iOS king renderer is missing the frozen Android notched crown" >&2
  exit 1
}

rg -Fq 'BoardMoveArrowOverlay' "$content" || {
  echo "iOS gameplay board is missing the shared hint move arrow" >&2
  exit 1
}

[[ $(rg -Fc '.frame(width: boardWidth)' "$content") -eq 2 ]] || {
  echo "The live iOS board must claim an explicit playable width in both orientations" >&2
  exit 1
}

rg -Fq 'TimelineView(.periodic' "$content" || {
  echo "The iOS clock must update independently instead of rebuilding the board" >&2
  exit 1
}

rg -Fq 'runtime.presentationRevision()' "$model" || {
  echo "The iOS model must use cheap revision polling before rebuilding the game projection" >&2
  exit 1
}

rg -Fq 'guard presentedRuntimeRevision != revision else { return }' "$model" || {
  echo "The iOS model is missing its unchanged-state rendering guard" >&2
  exit 1
}

rg -Fq 'hintFromSquare' "$runtime" || {
  echo "Apple runtime is not exporting the shared hint arrow endpoints" >&2
  exit 1
}

[[ $(rg -c 'BoardSquareSurface\(' "$content") -ge 2 ]] || {
  echo "Both live and preview boards must use procedural square surfaces" >&2
  exit 1
}
[[ $(rg -c 'ChessPieceView\(' "$content") -ge 2 ]] || {
  echo "Both live and preview boards must use code-native pieces" >&2
  exit 1
}

if rg -n 'Text\(cell\.pieceSymbol\)|Times New Roman|[♔♕♖♗♘♙♚♛♜♝♞♟]' "$content"; then
  echo "The iOS board must not fall back to font-dependent Unicode pieces" >&2
  exit 1
fi

if ! rg -Fq "PieceType.KNIGHT -> 'N'" "$runtime" || ! rg -Fq "PieceType.KING -> 'K'" "$runtime"; then
  echo "The shared Apple bridge must keep knight and king piece codes distinct" >&2
  exit 1
fi

for opponent in adaptive learner casual club challenger expert master grandmaster; do
  [[ -f "$portraits/opponent_$opponent.png" ]] || {
    echo "iOS is missing opponent portrait: opponent_$opponent" >&2
    exit 1
  }
done

for cue in chess_capture_crush chess_check_mechanical chess_en_passant_brick chess_checkmate_stone; do
  rg -Fq "\"$cue\"" "$feedback" || {
    echo "iOS game feedback is missing RC1 sound cue: $cue" >&2
    exit 1
  }
  find "$audio" -maxdepth 1 -name "$cue*.m4a" -print -quit | rg -q . || {
    echo "iOS is missing converted RC1 sound asset: $cue" >&2
    exit 1
  }
done

[[ -f "$app_icon" ]] || {
  echo "iOS is missing the RC1 app icon" >&2
  exit 1
}
icon_metadata="$(sips -g pixelWidth -g pixelHeight -g format -g hasAlpha "$app_icon" 2>/dev/null)"
rg -q 'pixelWidth: 1024' <<<"$icon_metadata" &&
  rg -q 'pixelHeight: 1024' <<<"$icon_metadata" &&
  rg -q 'format: png' <<<"$icon_metadata" &&
  rg -q 'hasAlpha: no' <<<"$icon_metadata" || {
  echo "The iOS app icon must be an opaque 1024×1024 PNG" >&2
  exit 1
}

rg -Uq 'struct BoardSquareSurface[\s\S]*?Canvas\([\s\S]*?\.clipped\(\)' "$visuals" || {
  echo "Procedural square drawing must be clipped to its own board cell" >&2
  exit 1
}
[[ -f "$root/scripts/RenderBoardVisualPreview.swift" ]] || {
  echo "The repeatable host-side board visual catalog is missing" >&2
  exit 1
}

echo "PASSED iOS structure checks (privacy manifest, RC1 icon, 5 textures, 5 palettes, 6 code-native pieces, 8 opponents, RC1 audio cues)"
