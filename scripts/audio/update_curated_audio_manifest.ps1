[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

$ErrorActionPreference = 'Stop'

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-WslPath([string]$Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $converted = (& wsl.exe -e wslpath -a $resolved | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $converted) {
        throw "Could not convert path for WSL: $resolved"
    }
    $converted
}

function Get-DurationSeconds([string]$Path) {
    $wslPath = Get-WslPath $Path
    $value = (& wsl.exe -e ffprobe -v error -show_entries format=duration `
        -of 'default=noprint_wrappers=1:nokey=1' $wslPath | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "FFprobe failed for $Path" }
    [Math]::Round([double]::Parse($value, [Globalization.CultureInfo]::InvariantCulture), 4)
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$manifestPath = Join-Path $root 'docs/audio/audio_manifest.json'
$rawRoot = Join-Path $root 'android/app/src/main/res/raw'
$sourceRoot = Join-Path $root 'docs/audio/source_recordings/CC0-Freesound-previews'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable

$manifest.schema = 2
$manifest.pack = 'Drawless Chess Curated Physical Foley Pack'

foreach ($rejected in @(
    'piece_slide',
    'beer_can_opening',
    'camera_flashing',
    'door_bump',
    'pop_cork',
    'tap'
)) {
    $manifest.sources.Remove($rejected)
}

$newSources = [ordered]@{
    mh2o_alabaster = [ordered]@{
        title = 'chess_move_on_alabaster.wav — Freesound HQ preview'
        author = 'mh2o'
        license = 'CC0-1.0'
        source = 'https://freesound.org/s/351518/'
        original_download = 'https://freesound.org/people/mh2o/sounds/351518/download/351518__mh2o__chess_move_on_alabaster.wav'
        source_preview = 'https://cdn.freesound.org/previews/351/351518_4502687-hq.mp3'
        source_preview_sha256 = Get-Sha256 (Join-Path $sourceRoot 'mh2o_alabaster.mp3')
        original_metadata = 'WAV, 0.072 seconds, 8000 Hz, 16-bit mono'
        retained_representation = 'Public Freesound HQ MP3 preview auditioned by the product owner'
        local_sha256 = Get-Sha256 (Join-Path $sourceRoot 'mh2o_alabaster.mp3')
    }
    rudmer_firework_pops = [ordered]@{
        title = '2 Firework pops — Freesound HQ preview'
        author = 'Rudmer_Rotteveel'
        license = 'CC0-1.0'
        source = 'https://freesound.org/s/334042/'
        original_download = 'https://freesound.org/people/Rudmer_Rotteveel/sounds/334042/download/334042__rudmer_rotteveel__2-firework-pops.wav'
        source_preview = 'https://cdn.freesound.org/previews/334/334042_4921277-hq.mp3'
        source_preview_sha256 = Get-Sha256 (Join-Path $sourceRoot 'rudmer_firework_pops.mp3')
        original_metadata = 'WAV, 3.536 seconds, 44100 Hz, 16-bit stereo'
        retained_representation = 'Public Freesound HQ MP3 preview auditioned by the product owner'
        local_sha256 = Get-Sha256 (Join-Path $sourceRoot 'rudmer_firework_pops.mp3')
    }
    rudmer_firework_rocket = [ordered]@{
        title = 'Whistle and Explosion Single_Firework — Freesound HQ preview'
        author = 'Rudmer_Rotteveel'
        license = 'CC0-1.0'
        source = 'https://freesound.org/s/336008/'
        original_download = 'https://freesound.org/people/Rudmer_Rotteveel/sounds/336008/download/336008__rudmer_rotteveel__whistle-and-explosion-single_firework.wav'
        source_preview = 'https://cdn.freesound.org/previews/336/336008_4921277-hq.mp3'
        source_preview_sha256 = Get-Sha256 (Join-Path $sourceRoot 'rudmer_firework_rocket.mp3')
        original_metadata = 'WAV, 2.391 seconds, 44100 Hz, 16-bit stereo'
        retained_representation = 'Public Freesound HQ MP3 preview auditioned by the product owner'
        local_sha256 = Get-Sha256 (Join-Path $sourceRoot 'rudmer_firework_rocket.mp3')
    }
}
foreach ($entry in $newSources.GetEnumerator()) {
    $manifest.sources[$entry.Key] = $entry.Value
}

$assetsByFile = @{}
foreach ($asset in $manifest.assets) { $assetsByFile[[string]$asset.file] = $asset }

function Set-GeneratedAsset(
    [string]$File,
    [string[]]$Sources,
    [string]$Processing
) {
    if (-not $assetsByFile.ContainsKey($File)) { throw "Manifest omits generated asset $File" }
    $asset = $assetsByFile[$File]
    $path = Join-Path $rawRoot $File
    $asset.duration_seconds = Get-DurationSeconds $path
    $asset.sha256 = Get-Sha256 $path
    $asset.sources = @($Sources)
    $asset.processing = $Processing
}

$moveIds = @('01','04','07','08','09','10','14','15','21','22','26','28','29','32','35','36','37','42','43','48','49','50')
$movePrimary = @('disk_drop_1','disk_drop_2','disk_drop_3','disk_drop_4','disk_drop_5','mh2o_alabaster','mh2o_alabaster','mh2o_alabaster','mh2o_alabaster','mh2o_alabaster','disk_drop_1','disk_drop_2','disk_drop_3','disk_drop_4','disk_drop_5','disk_drop_1','disk_drop_2','disk_drop_3','disk_drop_4','disk_drop_5','disk_drop_1','disk_drop_2')
$moveSecondary = @('mh2o_alabaster','mh2o_alabaster','mh2o_alabaster','mh2o_alabaster','mh2o_alabaster','disk_drop_1','disk_drop_2','disk_drop_3','disk_drop_4','disk_drop_5','disk_drop_3','disk_drop_4','disk_drop_5','disk_drop_1','disk_drop_2','disk_drop_4','disk_drop_5','disk_drop_1','disk_drop_2','disk_drop_3','disk_drop_5','disk_drop_3')
for ($index = 0; $index -lt $moveIds.Count; $index += 1) {
    Set-GeneratedAsset `
        -File "chess_move_wood_$($moveIds[$index]).ogg" `
        -Sources @($movePrimary[$index], $moveSecondary[$index]) `
        -Processing 'Two genuine piece/board contacts; deterministic 1-5 ms offset, restrained EQ, level matching, trim and fade. No slide, sweep, pitch shift, or synthesis.'
}

$captureRemoved = @('disk_drop_1','disk_drop_2','disk_drop_3','disk_drop_4','disk_drop_5','mh2o_alabaster','disk_drop_2','disk_drop_3','disk_drop_4','disk_drop_5','mh2o_alabaster','disk_drop_1')
$capturePlaced = @('disk_drop_4','disk_drop_5','mh2o_alabaster','disk_drop_1','disk_drop_2','disk_drop_3','disk_drop_5','mh2o_alabaster','disk_drop_1','disk_drop_2','disk_drop_3','disk_drop_4')
$captureBody = @('disk_drop_2','disk_drop_3','disk_drop_4','disk_drop_5','mh2o_alabaster','disk_drop_1','disk_drop_3','disk_drop_4','disk_drop_5','mh2o_alabaster','disk_drop_1','disk_drop_2')
for ($index = 0; $index -lt 12; $index += 1) {
    Set-GeneratedAsset `
        -File ('chess_capture_wood_{0:D2}.ogg' -f ($index + 1)) `
        -Sources @($captureRemoved[$index], $capturePlaced[$index], $captureBody[$index]) `
        -Processing 'Two-action physical capture: quieter recorded-piece removal followed by a firmer recorded-piece placement and restrained board body. No slide, household impact, or synthesis.'
}

$castleKing = @('disk_drop_1','disk_drop_2','disk_drop_3','disk_drop_4','disk_drop_5','mh2o_alabaster')
$castleRook = @('disk_drop_4','disk_drop_5','mh2o_alabaster','disk_drop_1','disk_drop_2','disk_drop_3')
for ($index = 0; $index -lt 6; $index += 1) {
    Set-GeneratedAsset `
        -File ('chess_castle_wood_{0:D2}.ogg' -f ($index + 1)) `
        -Sources @($castleKing[$index], $castleRook[$index]) `
        -Processing 'Two distinct genuine piece/board placements with a deterministic king/rook gap. No generic tap or synthesis.'
}

foreach ($tier in @('low','mid')) {
    foreach ($variant in 1..2) {
        Set-GeneratedAsset `
            -File ('chess_firework_{0}_{1:D2}.ogg' -f $tier, $variant) `
            -Sources @('rudmer_firework_pops') `
            -Processing 'Onset-aligned segment from a genuine CC0 firework recording; mono microphone-perspective mix, restrained EQ, trim and tail fade. No pitch shift, synthesis, or household substitute.'
    }
}
foreach ($variant in 1..2) {
    Set-GeneratedAsset `
        -File ('chess_firework_high_{0:D2}.ogg' -f $variant) `
        -Sources @('rudmer_firework_rocket') `
        -Processing 'Onset-aligned explosion from a genuine CC0 firework-rocket recording; mono microphone-perspective mix, restrained EQ, trim and tail fade. No pitch shift, synthesis, or household substitute.'
}

$json = $manifest | ConvertTo-Json -Depth 20
[IO.File]::WriteAllText($manifestPath, "$json`n", [Text.UTF8Encoding]::new($false))
Write-Host "Updated curated audio manifest: $manifestPath"
