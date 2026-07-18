[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$FfmpegPath = 'ffmpeg',
    [string]$FfprobePath = 'ffprobe'
)

$ErrorActionPreference = 'Stop'
$Invariant = [Globalization.CultureInfo]::InvariantCulture

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with exit code $LASTEXITCODE"
    }
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-DurationSeconds([string]$Path) {
    $text = (& $FfprobePath -v error -show_entries format=duration `
        -of 'default=noprint_wrappers=1:nokey=1' $Path | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $text) { throw "Could not probe $Path" }
    [Math]::Round([double]::Parse($text, $Invariant), 4)
}

function Format-Decimal([double]$Value) {
    $Value.ToString('0.####', $Invariant)
}

function Get-MasterFilter([double]$IntegratedLoudness) {
    "loudnorm=I=$(Format-Decimal $IntegratedLoudness):TP=-1.0:LRA=7," +
        'aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo'
}

function Get-PreparedInput([int]$Index, [double]$GainDb = 0.0) {
    "[$($Index):a]aresample=48000," +
        'aformat=sample_fmts=fltp:channel_layouts=stereo,' +
        'silenceremove=start_periods=1:start_duration=0:start_threshold=-55dB,' +
        'highpass=f=45,lowpass=f=19000,loudnorm=I=-18:TP=-3:LRA=7,' +
        "volume=$(Format-Decimal $GainDb)dB"
}

function Invoke-OggBuild(
    [string[]]$Inputs,
    [string]$Filter,
    [string]$Output
) {
    $arguments = [Collections.Generic.List[string]]::new()
    @('-hide_banner', '-loglevel', 'error', '-y') | ForEach-Object { $arguments.Add($_) }
    foreach ($input in $Inputs) {
        $arguments.Add('-i')
        $arguments.Add($input)
    }
    @(
        '-filter_complex', $Filter,
        '-map', '[out]',
        '-map_metadata', '-1',
        '-fflags', '+bitexact',
        '-flags:a', '+bitexact',
        '-ar', '48000',
        '-ac', '2',
        '-c:a', 'libvorbis',
        '-q:a', '8',
        '-serial_offset', '4242',
        $Output
    ) | ForEach-Object { $arguments.Add($_) }
    Invoke-Checked $FfmpegPath $arguments.ToArray()
}

function Get-AssetNumber([string]$Stem) {
    $match = [regex]::Match($Stem, '(\d+)$')
    if (-not $match.Success) { return 1 }
    [int]$match.Groups[1].Value
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$rawRoot = Join-Path $root 'android/app/src/main/res/raw'
$manifestPath = Join-Path $root 'docs/audio/audio_manifest.json'
$sourceRoot = Join-Path $root 'docs/audio/source_recordings'
$stagingRoot = Join-Path $root 'build/audio-stereo-vorbis-staging'

if (-not (Test-Path -LiteralPath $rawRoot -PathType Container)) {
    throw "Missing Android raw resource directory: $rawRoot"
}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Missing audio manifest: $manifestPath"
}

if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
$null = New-Item -ItemType Directory -Path $stagingRoot

$sourcePaths = @{}
Get-ChildItem -LiteralPath $sourceRoot -Recurse -File | ForEach-Object {
    if ($sourcePaths.ContainsKey($_.BaseName)) {
        throw "Duplicate retained source stem: $($_.BaseName)"
    }
    $sourcePaths[$_.BaseName] = $_.FullName
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$assets = @($manifest.assets)
if ($assets.Count -ne 104) { throw "Expected 104 manifest assets, found $($assets.Count)" }

$rebuilt = [Collections.Generic.List[object]]::new()
for ($assetIndex = 0; $assetIndex -lt $assets.Count; $assetIndex += 1) {
    $asset = $assets[$assetIndex]
    $stem = [IO.Path]::GetFileNameWithoutExtension([string]$asset.file)
    $number = Get-AssetNumber $stem
    $sourceIds = @($asset.sources | ForEach-Object {
        # The retained alabaster preview is only 8 kHz mono and is the strongest source of the
        # brittle/piezo character. Replace it deterministically with a genuine 48 kHz contact.
        if ($_ -eq 'mh2o_alabaster') { "disk_drop_$((($assetIndex + $number) % 5) + 1)" } else { [string]$_ }
    })
    $sourceIds = @($sourceIds | Select-Object -Unique)
    $inputs = @($sourceIds | ForEach-Object {
        if (-not $sourcePaths.ContainsKey($_)) { throw "No retained source file for $_" }
        $sourcePaths[$_]
    })
    $output = Join-Path $stagingRoot "$stem.ogg"
    $master = $null
    $filter = $null
    $processing = $null

    switch ([string]$asset.category) {
        'move' {
            $delay = 2 + ($number % 6)
            $tone = 600 + ($number % 8) * 320
            $toneGain = ($number % 5) - 2
            $second = [Math]::Min(1, $inputs.Count - 1)
            $master = Get-MasterFilter -IntegratedLoudness -16
            $filter = "$(Get-PreparedInput 0 0),equalizer=f=$tone`:t=q:w=1:g=$toneGain," +
                "atrim=0:0.21,asetpts=PTS-STARTPTS[a];" +
                "$(Get-PreparedInput $second -7),atrim=0:0.19,asetpts=PTS-STARTPTS,adelay=$delay|$delay[b];" +
                "[a][b]amix=inputs=2:duration=longest:normalize=0," +
                "apad=pad_dur=0.22,atrim=0:0.22,afade=t=out:st=0.18:d=0.04,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 master from genuine chess contacts; aligned transient, restrained body layer, full contact tail, and loudness/true-peak mastering. No synthesis or pitch shift.'
        }
        'capture' {
            $placementDelay = 76 + (($number - 1) % 4) * 8
            $bodyDelay = $placementDelay + 10
            $second = [Math]::Min(1, $inputs.Count - 1)
            $third = [Math]::Min(2, $inputs.Count - 1)
            $master = Get-MasterFilter -IntegratedLoudness -14
            $filter = "$(Get-PreparedInput 0 -2),atrim=0:0.18,asetpts=PTS-STARTPTS[r];" +
                "$(Get-PreparedInput $second 0),atrim=0:0.21,asetpts=PTS-STARTPTS,adelay=$placementDelay|$placementDelay[p];" +
                "$(Get-PreparedInput $third -12),lowpass=f=2200,atrim=0:0.18,asetpts=PTS-STARTPTS,adelay=$bodyDelay|$bodyDelay[b];" +
                "[r][p][b]amix=inputs=3:duration=longest:normalize=0," +
                "apad=pad_dur=0.36,atrim=0:0.36,afade=t=out:st=0.31:d=0.05,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 two-action capture from recorded piece removal and placement contacts with natural board body and mastered impact. No procedural audio.'
        }
        'castle' {
            $gap = 132 + (($number - 1) % 4) * 12
            $second = [Math]::Min(1, $inputs.Count - 1)
            $master = Get-MasterFilter -IntegratedLoudness -15
            $filter = "$(Get-PreparedInput 0 -2),atrim=0:0.21,asetpts=PTS-STARTPTS[k];" +
                "$(Get-PreparedInput $second 0),atrim=0:0.21,asetpts=PTS-STARTPTS,adelay=$gap|$gap[r];" +
                "[k][r]amix=inputs=2:duration=longest:normalize=0," +
                "apad=pad_dur=0.42,atrim=0:0.42,afade=t=out:st=0.37:d=0.05,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 king-and-rook sequence from two genuine chess contacts, with audible separation and natural tails.'
        }
        'firework_low' {
            $master = Get-MasterFilter -IntegratedLoudness -12
            $perspective = if ($number % 2 -eq 0) { 'pan=stereo|c0=c1|c1=c0,' } else { '' }
            $filter = "[0:a]atrim=start=0.395:end=1.20,asetpts=PTS-STARTPTS,aresample=48000," +
                "aformat=sample_fmts=fltp:channel_layouts=stereo,$perspective" +
                "highpass=f=35,lowpass=f=19500,afade=t=out:st=0.685:d=0.12,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 runtime master preserving the real microphone stereo field and complete first-firework decay from the retained HQ preview; loudness and true-peak mastered without synthesis.'
        }
        'firework_mid' {
            $master = Get-MasterFilter -IntegratedLoudness -11
            $perspective = if ($number % 2 -eq 0) { 'pan=stereo|c0=c1|c1=c0,' } else { '' }
            $filter = "[0:a]atrim=start=1.245:end=3.42,asetpts=PTS-STARTPTS,aresample=48000," +
                "aformat=sample_fmts=fltp:channel_layouts=stereo,$perspective" +
                "highpass=f=35,lowpass=f=19500,afade=t=out:st=1.995:d=0.18,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 runtime master preserving the real microphone stereo field and long second-firework decay from the retained HQ preview; loudness and true-peak mastered without synthesis.'
        }
        'firework_high' {
            $master = Get-MasterFilter -IntegratedLoudness -10
            $perspective = if ($number % 2 -eq 0) { 'pan=stereo|c0=c1|c1=c0,' } else { '' }
            $filter = "[0:a]atrim=start=1.14:end=2.391,asetpts=PTS-STARTPTS,aresample=48000," +
                "aformat=sample_fmts=fltp:channel_layouts=stereo,$perspective" +
                "highpass=f=30,lowpass=f=19500,equalizer=f=95:t=q:w=0.8:g=2.5," +
                "afade=t=out:st=1.09:d=0.161,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 runtime master preserving the real rocket explosion stereo field, low-frequency body, and complete natural decay from the retained HQ preview; loudness and true-peak mastered.'
        }
        'glass_impact' {
            $second = [Math]::Min(1, $inputs.Count - 1)
            $bodyDelay = ($number - 1) * 8
            $master = Get-MasterFilter -IntegratedLoudness -12
            $filter = "$(Get-PreparedInput 0 0),atrim=start=0.04:end=0.38,asetpts=PTS-STARTPTS[a];" +
                "$(Get-PreparedInput $second -7),lowpass=f=3500,atrim=start=0.04:end=0.34," +
                "asetpts=PTS-STARTPTS," +
                "adelay=$bodyDelay|$bodyDelay[b];" +
                "[a][b]amix=inputs=2:duration=longest:normalize=0," +
                "apad=pad_dur=0.42,atrim=0:0.42,afade=t=out:st=0.36:d=0.06,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 recorded break impact with preserved source stereo, low body, natural tail, and impact mastering.'
        }
        'glass_fracture' {
            $second = [Math]::Min(1, $inputs.Count - 1)
            $fractureDelay = 12 + $number * 8
            $master = Get-MasterFilter -IntegratedLoudness -15
            $filter = "$(Get-PreparedInput 0 -1),atrim=start=0.04:end=0.82,asetpts=PTS-STARTPTS[a];" +
                "$(Get-PreparedInput $second -4),highpass=f=650,atrim=0:0.55,asetpts=PTS-STARTPTS," +
                "adelay=$fractureDelay|$fractureDelay[b];" +
                "[a][b]amix=inputs=2:duration=longest:normalize=0," +
                "apad=pad_dur=0.84,atrim=0:0.84,afade=t=out:st=0.72:d=0.12,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 recorded glass fracture with preserved spatial detail and extended natural decay.'
        }
        'glass_shards' {
            $second = [Math]::Min(1, $inputs.Count - 1)
            $shardDelay = 50 + $number * 30
            $master = Get-MasterFilter -IntegratedLoudness -18
            $filter = "$(Get-PreparedInput 0 -3),atrim=start=0.04:end=1.39,asetpts=PTS-STARTPTS[a];" +
                "$(Get-PreparedInput $second -7),highpass=f=1000,atrim=0:0.55,asetpts=PTS-STARTPTS," +
                "adelay=$shardDelay|$shardDelay[b];" +
                "[a][b]amix=inputs=2:duration=longest:normalize=0," +
                "apad=pad_dur=1.42,atrim=0:1.42,afade=t=out:st=1.18:d=0.24,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 recorded falling-glass tail with preserved source ambience and long natural fade.'
        }
        'check' {
            $start = (($number - 1) % 4) * 0.028
            $master = Get-MasterFilter -IntegratedLoudness -18
            $filter = "$(Get-PreparedInput 0 -2),atrim=start=$(Format-Decimal $start):duration=0.22," +
                "asetpts=PTS-STARTPTS,highpass=f=900,afade=t=out:st=0.17:d=0.05,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 cue cut from a recorded glass tick; replaces the procedural two-tone check cue.'
        }
        'promotion' {
            $second = [Math]::Min(1, $inputs.Count - 1)
            $third = [Math]::Min(2, $inputs.Count - 1)
            $placementDelay = 46 + $number * 6
            $accentDelay = 74 + $number * 9
            $master = Get-MasterFilter -IntegratedLoudness -15
            $filter = "$(Get-PreparedInput 0 -3),atrim=0:0.20,asetpts=PTS-STARTPTS[a];" +
                "$(Get-PreparedInput $second -6),atrim=0:0.18,asetpts=PTS-STARTPTS," +
                "adelay=$placementDelay|$placementDelay[b];" +
                "$(Get-PreparedInput $third -10),highpass=f=900,atrim=0:0.32,asetpts=PTS-STARTPTS," +
                "adelay=$accentDelay|$accentDelay[c];" +
                "[a][b][c]amix=inputs=3:duration=longest:normalize=0," +
                "apad=pad_dur=0.48,atrim=0:0.48,afade=t=out:st=0.40:d=0.08," +
                "$master,volume=5dB,alimiter=limit=0.80:level=false[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 promotion cue from recorded piece contacts and glass accent, with a complete decay.'
        }
        'hint' {
            $tone = 700 + $number * 350
            $toneGain = $number - 2
            $master = Get-MasterFilter -IntegratedLoudness -20
            $filter = "$(Get-PreparedInput 0 -4),equalizer=f=$tone`:t=q:w=1:g=$toneGain," +
                "atrim=0:0.22,asetpts=PTS-STARTPTS," +
                "afade=t=out:st=0.17:d=0.05,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 soft hint from a genuine chess contact.'
        }
        'low_time' {
            $tone = 1000 + $number * 500
            $toneGain = ($number % 3) - 1
            $master = Get-MasterFilter -IntegratedLoudness -18
            $filter = "$(Get-PreparedInput 0 -2),equalizer=f=$tone`:t=q:w=1:g=$toneGain," +
                "atrim=0:0.34,asetpts=PTS-STARTPTS," +
                "afade=t=out:st=0.27:d=0.07,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 threshold cue preserving the recorded source channels and transient.'
        }
        'game_start' {
            $second = [Math]::Min(1, $inputs.Count - 1)
            $gap = 112 + ($number % 3) * 18
            $master = Get-MasterFilter -IntegratedLoudness -18
            $filter = "$(Get-PreparedInput 0 -5),atrim=0:0.20,asetpts=PTS-STARTPTS[a];" +
                "$(Get-PreparedInput $second -5),atrim=0:0.20,asetpts=PTS-STARTPTS,adelay=$gap|$gap[b];" +
                "[a][b]amix=inputs=2:duration=longest:normalize=0," +
                "apad=pad_dur=0.42,atrim=0:0.42,afade=t=out:st=0.36:d=0.06,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 game-start cue from two soft recorded chess placements.'
        }
        'undo' {
            $tone = 650 + $number * 420
            $toneGain = $number - 2
            $master = Get-MasterFilter -IntegratedLoudness -20
            $filter = "$(Get-PreparedInput 0 -5),equalizer=f=$tone`:t=q:w=1:g=$toneGain," +
                "atrim=0:0.22,asetpts=PTS-STARTPTS," +
                "afade=t=out:st=0.17:d=0.05,$master[out]"
            $processing = 'High-quality 48 kHz stereo Vorbis q8 understated undo cue from a genuine recorded chess contact; no reversed or synthesized audio.'
        }
        default { throw "Unsupported audio category: $($asset.category)" }
    }

    Invoke-OggBuild -Inputs $inputs -Filter $filter -Output $output
    $rebuilt.Add([pscustomobject]@{
        Asset = $asset
        File = "$stem.ogg"
        Path = $output
        Sources = $sourceIds
        Processing = $processing
    })
}

if ($rebuilt.Count -ne 104 -or (Get-ChildItem -LiteralPath $stagingRoot -Filter '*.ogg' -File).Count -ne 104) {
    throw 'Stereo Vorbis build did not create exactly 104 OGG resources'
}

# Generation is complete before the runtime set changes. Exact roots are resolved above and are
# both inside this repository, so a failed render cannot leave a half-replaced audio pack.
Get-ChildItem -LiteralPath $rawRoot -Filter '*.ogg' -File | Remove-Item -Force
Get-ChildItem -LiteralPath $rawRoot -Filter '*.wav' -File | Remove-Item -Force
foreach ($entry in $rebuilt) {
    Move-Item -LiteralPath $entry.Path -Destination (Join-Path $rawRoot $entry.File)
    $entry.Asset.file = $entry.File
    $entry.Asset.duration_seconds = Get-DurationSeconds (Join-Path $rawRoot $entry.File)
    $entry.Asset.sha256 = Get-Sha256 (Join-Path $rawRoot $entry.File)
    $entry.Asset.sources = @($entry.Sources)
    $entry.Asset.processing = $entry.Processing
}

$manifest.schema = 3
$manifest.pack = 'Drawless Chess High-Quality Stereo Foley Pack'
$manifest.format.codec = 'Ogg Vorbis'
$manifest.format.sample_rate_hz = 48000
$manifest.format.channels = 2
$manifest.format.PSObject.Properties.Remove('bit_depth')
if ($manifest.format.PSObject.Properties.Name -notcontains 'quality') {
    $manifest.format | Add-Member -NotePropertyName quality -NotePropertyValue 'libvorbis q8'
} else {
    $manifest.format.quality = 'libvorbis q8'
}
$json = $manifest | ConvertTo-Json -Depth 20
[IO.File]::WriteAllText($manifestPath, "$json`n", [Text.UTF8Encoding]::new($false))
Remove-Item -LiteralPath $stagingRoot -Force

Write-Host 'Audio rebuild complete: 104 high-quality stereo 48 kHz Vorbis q8 resources.'
