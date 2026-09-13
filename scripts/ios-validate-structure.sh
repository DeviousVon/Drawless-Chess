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
ivory_atlas="$root/iosApp/DrawlessChess/Assets.xcassets/AllHallowsIvoryAtlas.imageset/all-hallows-ivory-atlas-chroma.png"
obsidian_atlas="$root/iosApp/DrawlessChess/Assets.xcassets/AllHallowsObsidianAtlas.imageset/all-hallows-obsidian-atlas-chroma.png"
android_ivory_atlas="$root/android/app/src/main/res/drawable-nodpi/all_hallows_ivory_atlas_chroma.png"
android_obsidian_atlas="$root/android/app/src/main/res/drawable-nodpi/all_hallows_obsidian_atlas_chroma.png"
privacy_manifest="$root/iosApp/DrawlessChess/PrivacyInfo.xcprivacy"

[[ -f "$visuals" ]] || { echo "iOS board and piece renderer is missing" >&2; exit 1; }

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

for theme in imperial_marble desert_sandstone glacier_slate verdigris_copper celestial_observatory halloween_emberwood halloween_witchglass; do
  rg -Fq "\"$theme\"" "$visuals" || {
    echo "iOS board renderer is missing theme: $theme" >&2
    exit 1
  }
done

for texture in sandstone marble slate verdigris celestial allHallows; do
  rg -q "case $texture" "$visuals" || {
    echo "iOS board renderer is missing procedural texture: $texture" >&2
    exit 1
  }
done

for motif in Pawn Rook Knight Bishop Queen King; do
  rg -Fq "drawAllHallows$motif" "$visuals" || {
    echo "iOS All Hallows pieces are missing the $motif vector fallback" >&2
    exit 1
  }
done

for atlas in "$ivory_atlas" "$obsidian_atlas"; do
  [[ -f "$atlas" ]] || {
    echo "iOS is missing a high-resolution All Hallows sculpture atlas: $atlas" >&2
    exit 1
  }
  atlas_metadata="$(sips -g pixelWidth -g pixelHeight -g format -g hasAlpha "$atlas" 2>/dev/null)"
  rg -q 'pixelWidth: 1774' <<<"$atlas_metadata" &&
    rg -q 'pixelHeight: 887' <<<"$atlas_metadata" &&
    rg -q 'format: png' <<<"$atlas_metadata" &&
    rg -q 'hasAlpha: no' <<<"$atlas_metadata" || {
    echo "All Hallows sculpture atlases must remain opaque 1774×887 PNG source textures" >&2
    exit 1
  }
done

cmp -s "$ivory_atlas" "$android_ivory_atlas" || {
  echo "Android and iOS must package the same reviewed ivory sculpture atlas" >&2
  exit 1
}
cmp -s "$obsidian_atlas" "$android_obsidian_atlas" || {
  echo "Android and iOS must package the same reviewed obsidian sculpture atlas" >&2
  exit 1
}

# Celestial retains each generated source's native dimensions and exact cross-platform bytes.
python3 - "$root" <<'PYATLAS'
from pathlib import Path
import hashlib, struct, sys
root = Path(sys.argv[1])
for side, dimensions, digest in [
    ("ivory", (1774, 887), "224f6aed0aa519ac7f89fb4023ea1faf584b942b7df10d419ceb47753ef06d66"),
    ("midnight", (1748, 900), "e86c2e74fe57673c5e147cd5fb0b86e8979b2149f20a70bb0ff0b770517ffa53"),
]:
    android = root / f"android/app/src/main/res/drawable-nodpi/celestial_{side}_atlas_chroma.png"
    ios = root / f"iosApp/DrawlessChess/Assets.xcassets/Celestial{side.title()}Atlas.imageset/celestial-{side}-atlas-chroma.png"
    data = ios.read_bytes()
    assert data == android.read_bytes(), f"Celestial {side} platform assets differ"
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"Celestial {side} is not PNG"
    assert struct.unpack(">II", data[16:24]) == dimensions, f"Celestial {side} dimensions changed"
    assert hashlib.sha256(data).hexdigest() == digest, f"Celestial {side} artwork changed without provenance update"
PYATLAS

for token in SculpturePieceAtlasPainter croppedPieceImage chromaKeyMatrix; do
  rg -Fq "$token" "$visuals" || {
    echo "iOS All Hallows high-resolution renderer is missing: $token" >&2
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
  echo "Both live and preview boards must use the shared project-owned piece renderer" >&2
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

echo "PASSED iOS structure checks (privacy manifest, RC1 icon, 7 board choices, shared Halloween sculptures, retained concept atlases and vector fallback, 8 opponents, RC1 audio cues)"
