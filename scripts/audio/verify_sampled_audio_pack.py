#!/usr/bin/env python3
"""Verify generated Drawless Chess sampled audio resources."""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ANDROID_NAME = re.compile(r"^[a-z][a-z0-9_]*\.ogg$")
EXPECTED = {
    "move": 50,
    "capture": 12,
    "castle": 6,
    "firework_low": 2,
    "firework_mid": 2,
    "firework_high": 2,
    "glass_impact": 3,
    "glass_fracture": 3,
    "glass_shards": 3,
    "check": 4,
    "promotion": 4,
    "hint": 3,
    "low_time": 4,
    "game_start": 3,
    "undo": 3,
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def probe(path: Path) -> dict:
    result = subprocess.run(
        [
            "ffprobe", "-v", "error", "-select_streams", "a:0",
            "-show_entries", "stream=codec_name,sample_rate,channels,duration",
            "-of", "json", str(path),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    streams = json.loads(result.stdout).get("streams", [])
    if len(streams) != 1:
        raise AssertionError(f"Expected one audio stream: {path}")
    return streams[0]


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    manifest_path = root / "docs/audio/audio_manifest.json"
    raw = root / "android/app/src/main/res/raw"
    catalog = root / "android/app/src/main/kotlin/com/drawlesschess/ui/SampledSoundCatalog.kt"
    if not manifest_path.is_file():
        raise SystemExit(f"Missing {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assets = manifest.get("assets", [])
    counts: dict[str, int] = {}
    seen: set[str] = set()
    failures: list[str] = []
    for asset in assets:
        name = asset["file"]
        category = asset["category"]
        counts[category] = counts.get(category, 0) + 1
        if name in seen:
            failures.append(f"duplicate manifest entry: {name}")
        seen.add(name)
        if not ANDROID_NAME.fullmatch(name):
            failures.append(f"invalid Android resource name: {name}")
        path = raw / name
        if not path.is_file():
            failures.append(f"missing asset: {path}")
            continue
        if sha256(path) != asset["sha256"]:
            failures.append(f"hash mismatch: {name}")
        try:
            info = probe(path)
        except Exception as exc:
            failures.append(f"decode failed: {name}: {exc}")
            continue
        if info.get("codec_name") != "vorbis":
            failures.append(f"not Vorbis: {name}: {info.get('codec_name')}")
        if int(info.get("sample_rate", 0)) != 48_000:
            failures.append(f"not 48 kHz: {name}: {info.get('sample_rate')}")
        if int(info.get("channels", 0)) != 1:
            failures.append(f"not mono: {name}: {info.get('channels')}")
        duration = float(info.get("duration") or 0.0)
        if not 0.035 <= duration <= 1.25:
            failures.append(f"duration out of bounds: {name}: {duration:.3f}s")
        if abs(duration - float(asset["duration_seconds"])) > 0.08:
            failures.append(
                f"manifest duration differs: {name}: probe={duration:.3f} manifest={asset['duration_seconds']}"
            )
    for category, expected in EXPECTED.items():
        actual = counts.get(category, 0)
        if actual != expected:
            failures.append(f"{category}: expected {expected}, got {actual}")
    disk = {path.name for path in raw.glob("chess_*.ogg")}
    if disk != seen:
        failures.append(f"manifest/disk set differs: only_disk={sorted(disk-seen)}, only_manifest={sorted(seen-disk)}")
    if not catalog.is_file():
        failures.append(f"missing generated catalog: {catalog}")
    else:
        catalog_text = catalog.read_text(encoding="utf-8")
        for name in seen:
            resource = name.removesuffix(".ogg")
            if f"R.raw.{resource}" not in catalog_text:
                failures.append(f"catalog missing resource: {resource}")
    if failures:
        print("Sampled audio verification FAILED:")
        for failure in failures:
            print(f"- {failure}")
        return 1
    print(f"PASSED sampled audio verification: {len(assets)} assets")
    for category in sorted(counts):
        print(f"  {category}: {counts[category]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
