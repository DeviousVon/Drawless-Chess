#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
content="$root/iosApp/DrawlessChess/ContentView.swift"
visuals="$root/iosApp/DrawlessChess/BoardVisuals.swift"
runtime="$root/multiplatform/shared-core/src/commonMain/kotlin/com/drawlesschess/shared/SharedGameRuntime.kt"
feedback="$root/iosApp/DrawlessChess/GameFeedback.swift"
portraits="$root/iosApp/DrawlessChess/Portraits"
audio="$root/iosApp/DrawlessChess/Audio"
app_icon="$root/iosApp/DrawlessChess/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"

[[ -f "$visuals" ]] || { echo "iOS code-native board renderer is missing" >&2; exit 1; }

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
icon_size="$(sips -g pixelWidth -g pixelHeight "$app_icon" 2>/dev/null)"
rg -q 'pixelWidth: 1024' <<<"$icon_size" && rg -q 'pixelHeight: 1024' <<<"$icon_size" || {
  echo "The iOS app icon must be a 1024×1024 PNG" >&2
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

echo "PASSED iOS structure checks (RC1 icon, 5 textures, 5 palettes, 6 code-native pieces, 8 opponents, RC1 audio cues)"
