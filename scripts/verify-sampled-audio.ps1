[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$FfmpegPath,
    [switch]$RequireDecode
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    throw "verify-sampled-audio: $Message"
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-GitBlobSha1([string]$Path) {
    $content = [IO.File]::ReadAllBytes($Path)
    $header = [Text.Encoding]::UTF8.GetBytes("blob $($content.Length)`0")
    $blob = [byte[]]::new($header.Length + $content.Length)
    [Buffer]::BlockCopy($header, 0, $blob, 0, $header.Length)
    [Buffer]::BlockCopy($content, 0, $blob, $header.Length, $content.Length)
    $sha1 = [Security.Cryptography.SHA1]::Create()
    try {
        ([BitConverter]::ToString($sha1.ComputeHash($blob)) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha1.Dispose()
    }
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$raw = Join-Path $root 'android/app/src/main/res/raw'
$catalogPath = Join-Path $root 'android/app/src/main/kotlin/com/drawlesschess/ui/SampledSoundCatalog.kt'
$manifestPath = Join-Path $root 'docs/audio/audio_manifest.json'
$sourceRoot = Join-Path $root 'docs/audio/source_recordings'
$previewRoot = Join-Path $root 'docs/audio/previews'
$previewMapPath = Join-Path $previewRoot 'audio-pack-preview-map.txt'
$thirdPartyNotices = Join-Path $root 'THIRD_PARTY_NOTICES.md'

foreach ($required in @(
    $raw,
    $catalogPath,
    $manifestPath,
    $sourceRoot,
    $previewMapPath,
    (Join-Path $root 'docs/audio/licenses/CC0-1.0.txt'),
    (Join-Path $root 'docs/audio/licenses/ion-sound-MIT.txt'),
    $thirdPartyNotices
)) {
    if (-not (Test-Path -LiteralPath $required)) { Fail "missing $required" }
}

$auditedManifestSha256 = '6e10b3348d37ebc6995317596c5c7c9731a4d549b1a9ba35fffe8ad023737ec8'
if ((Get-Sha256 $manifestPath) -ne $auditedManifestSha256) {
    Fail 'audio_manifest.json differs from the independently audited source identities and pins'
}

$auditedPreviewSha256 = [ordered]@{
    'audio-pack-preview-map.txt' = '9b014f86bb791c5df353ebf136d0082bc1dd49be8078b04715efda959bfb795a'
    'audio-pack-preview.ogg' = 'c426b92962b7b8a4bcf3566fd558847a451f8efd6a6d5a3d53fc86dbf55e3c78'
    'preview-captures-and-castling.ogg' = 'a4fb5a4eb4ed9727ca4d21dda3f83250bc241d1e54495a5f4a311d1ef15aba01'
    'preview-fireworks.ogg' = '5ef68c1f4d7236416fbed7b6d19ced0b6d82179c953a1999269bc6306083c374'
    'preview-glass-loss.ogg' = 'c5644a540d571eb8c46fd741b1be737f11304874e3f43a2e58f33d25bbe45218'
    'preview-moves.ogg' = 'de1594f58c2105824ec758ae76123357a1a2878c15f27bc6e1746c747247d0dd'
}
foreach ($entry in $auditedPreviewSha256.GetEnumerator()) {
    $path = Join-Path $previewRoot $entry.Key
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Fail "missing curated preview $($entry.Key)"
    }
    if ((Get-Sha256 $path) -ne $entry.Value) {
        Fail "curated preview $($entry.Key) differs from its audited capture-pack reel"
    }
    if ($entry.Key.EndsWith('.ogg')) {
        $stream = [IO.File]::OpenRead($path)
        try {
            $header = [byte[]]::new(4)
            $read = $stream.Read($header, 0, $header.Length)
        } finally {
            $stream.Dispose()
        }
        if ($read -ne 4 -or [Text.Encoding]::ASCII.GetString($header) -ne 'OggS') {
            Fail "curated preview $($entry.Key) does not have an Ogg identification header"
        }
    }
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.schema -ne 3 -or
    $manifest.format.codec -ne 'Ogg Vorbis' -or
    $manifest.format.sample_rate_hz -ne 48000 -or
    $manifest.format.channels -ne 2 -or
    $manifest.format.quality -ne 'libvorbis q8') {
    Fail 'manifest does not declare the required high-quality stereo Vorbis format'
}
$expectedCounts = [ordered]@{
    move = 50
    capture = 12
    castle = 6
    firework_low = 2
    firework_mid = 2
    firework_high = 2
    glass_impact = 3
    glass_fracture = 3
    glass_shards = 3
    check = 1
    checkmate = 1
    en_passant = 1
    promotion = 4
    hint = 3
    low_time = 4
    game_start = 3
    undo = 3
}
$expectedTotal = ($expectedCounts.Values | Measure-Object -Sum).Sum
$assets = @($manifest.assets)
if ($assets.Count -ne $expectedTotal) {
    Fail "expected $expectedTotal manifest assets, found $($assets.Count)"
}

$previewMapText = Get-Content -LiteralPath $previewMapPath -Raw
if ($previewMapText -match 'chess_capture_wood_') {
    Fail 'curated preview map still names the retired capture-contact family'
}
$mappedPreviewNames = @([regex]::Matches(
    $previewMapText,
    '(?m)^\s+\d{2}\. ([a-z][a-z0-9_]*\.ogg)\s*$'
) | ForEach-Object { $_.Groups[1].Value })
$previewedCategories = @(
    'move', 'capture', 'castle',
    'firework_low', 'firework_mid', 'firework_high'
)
$expectedPreviewNames = @($assets |
    Where-Object { $_.category -in $previewedCategories } |
    ForEach-Object { [string]$_.file })
if ($mappedPreviewNames.Count -ne $expectedPreviewNames.Count -or
    @($mappedPreviewNames | Sort-Object -Unique).Count -ne $expectedPreviewNames.Count -or
    @($mappedPreviewNames | Where-Object { $_ -notin $expectedPreviewNames }).Count -ne 0 -or
    @($expectedPreviewNames | Where-Object { $_ -notin $mappedPreviewNames }).Count -ne 0) {
    Fail 'curated preview map file set differs from the manifest move/capture/castle/firework assets'
}

$declaredSources = @{}
$manifest.sources.PSObject.Properties | ForEach-Object { $declaredSources[$_.Name] = $_.Value }
if ($declaredSources.Count -ne 19) { Fail "expected 19 declared sources, found $($declaredSources.Count)" }
foreach ($entry in $declaredSources.GetEnumerator()) {
    $source = $entry.Value
    if (-not $source.title -or -not $source.author -or -not $source.source) {
        Fail "source $($entry.Key) omits title, author, or source URL"
    }
    if ([string]$source.license -notin @('CC0-1.0', 'MIT')) {
        Fail "source $($entry.Key) has unsupported license '$($source.license)'"
    }
    if ([string]$source.source -notmatch '^https://') {
        Fail "source $($entry.Key) does not use an HTTPS identity"
    }
    if ($source.local_sha256 -and [string]$source.local_sha256 -notmatch '^[0-9a-f]{64}$') {
        Fail "source $($entry.Key) has an invalid local SHA-256"
    }
}
foreach ($id in @('disk_drop_1', 'disk_drop_2', 'disk_drop_3', 'disk_drop_4', 'disk_drop_5')) {
    $source = $declaredSources[$id]
    if ($source.license -ne 'CC0-1.0' -or $source.source -notmatch '^https://freesound\.org/s/\d+/$') {
        Fail "CC0 source $id is not pinned to its Freesound sound page"
    }
}
foreach ($entry in @(
    @{ id = 'mh2o_alabaster'; sound = '351518'; page = 'https://freesound.org/s/351518/'; preview = '351518_4502687-hq.mp3' },
    @{ id = 'aerror_stonehit1'; sound = '350750'; page = 'https://freesound.org/people/aerror/sounds/350750/'; preview = '350750_2472895-hq.mp3' },
    @{ id = 'rudmer_firework_pops'; sound = '334042'; page = 'https://freesound.org/s/334042/'; preview = '334042_4921277-hq.mp3' },
    @{ id = 'rudmer_firework_rocket'; sound = '336008'; page = 'https://freesound.org/s/336008/'; preview = '336008_4921277-hq.mp3' }
)) {
    $source = $declaredSources[$entry.id]
    if ($source.license -ne 'CC0-1.0' -or
        $source.source -ne $entry.page -or
        $source.source_preview -ne "https://cdn.freesound.org/previews/$($entry.sound.Substring(0, 3))/$($entry.preview)" -or
        $source.source_preview_sha256 -notmatch '^[0-9a-f]{64}$' -or
        $source.source_preview_sha256 -ne $source.local_sha256 -or
        $source.original_download -notmatch "/sounds/$($entry.sound)/download/") {
        Fail "$($entry.id) omits its exact Freesound page, HQ preview, hash, or original-download identity"
    }
}
foreach ($entry in @(
    @{
        id = 'discofield_brick_crash'
        page = 'https://freesound.org/people/discofield/sounds/711656/'
        download = 'https://freesound.org/people/discofield/sounds/711656/download/711656__discofield__brick-crash.wav'
        sha256 = 'd6a90aae61d4d216cc0c2900e6a941230f5ad07bcee3a4b272786447b40ea21f'
        metadata = 'WAV, 2.041678 seconds, 44100 Hz, 16-bit stereo'
    },
    @{
        id = 'discofield_stone_crash'
        page = 'https://freesound.org/people/discofield/sounds/711657/'
        download = 'https://freesound.org/people/discofield/sounds/711657/download/711657__discofield__stone-crash.wav'
        sha256 = '5a91523c53b04d22c5b38fea41c4f1ba22d3ec64fc4b3ff954a6f378504c1afe'
        metadata = 'WAV, 2.500000 seconds, 44100 Hz, 16-bit stereo'
    }
)) {
    $source = $declaredSources[$entry.id]
    if ($source.license -ne 'CC0-1.0' -or
        $source.source -ne $entry.page -or
        $source.original_download -ne $entry.download -or
        $source.local_sha256 -ne $entry.sha256 -or
        $source.original_metadata -ne $entry.metadata -or
        $source.retained_representation -notmatch '^Original lossless Freesound WAV') {
        Fail "$($entry.id) differs from the audited original Freesound WAV identity"
    }
}
foreach ($index in 1..5) {
    $source = $declaredSources["disk_drop_$index"]
    if ($source.intermediate -notmatch '/blob/[0-9a-f]{40}/' -or
        $source.intermediate_sha256 -notmatch '^[0-9a-f]{64}$' -or
        $source.intermediate_git_blob_sha1 -notmatch '^[0-9a-f]{40}$') {
        Fail "disk_drop_$index omits immutable intermediate evidence"
    }
}
$ion = $declaredSources.ion_sound
if ($ion.license -ne 'MIT' -or $ion.version -ne '3.0.7' -or
    $ion.commit -notmatch '^[0-9a-f]{40}$' -or $ion.source -notmatch [regex]::Escape($ion.commit)) {
    Fail 'ion_sound parent identity is not pinned to version 3.0.7 and its commit'
}
foreach ($entry in $declaredSources.GetEnumerator() | Where-Object { $_.Value.license -eq 'MIT' -and $_.Key -ne 'ion_sound' }) {
    $source = $entry.Value
    if ($source.source -notmatch [regex]::Escape($ion.commit) -or
        $source.upstream_git_blob_sha1 -notmatch '^[0-9a-f]{40}$') {
        Fail "MIT source $($entry.Key) omits its pinned upstream blob identity"
    }
}
$assetNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$assetHashes = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$usedSourceIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$categoryCounts = @{}

foreach ($asset in $assets) {
    $name = [string]$asset.file
    if ($name -notmatch '^[a-z][a-z0-9_]*\.ogg$') { Fail "invalid Android resource name $name" }
    if (-not $assetNames.Add($name)) { Fail "duplicate manifest file $name" }
    if (-not $assetHashes.Add([string]$asset.sha256)) { Fail "duplicate encoded asset hash $($asset.sha256)" }
    $category = [string]$asset.category
    $categoryCounts[$category] = 1 + [int]($categoryCounts[$category] ?? 0)
    if ([double]$asset.duration_seconds -lt 0.03 -or [double]$asset.duration_seconds -gt 2.50) {
        Fail "implausible manifest duration for $name"
    }
    if (@($asset.sources).Count -eq 0) { Fail "$name has no declared sources" }
    if (-not $asset.processing) { Fail "$name has no processing description" }
    foreach ($sourceId in @($asset.sources)) {
        if (-not $declaredSources.ContainsKey([string]$sourceId)) {
            Fail "$name references unresolved source '$sourceId'"
        }
        if ([string]$sourceId -in @('ion_sound', 'keyboard_desk')) {
            Fail "$name references aggregate or intentionally unused source '$sourceId'"
        }
        $null = $usedSourceIds.Add([string]$sourceId)
    }

    $assetSources = @($asset.sources | ForEach-Object { [string]$_ })
    $contactSources = @('disk_drop_1', 'disk_drop_2', 'disk_drop_3', 'disk_drop_4', 'disk_drop_5', 'mh2o_alabaster')
    if ($category -in @('move', 'castle') -and
        @($assetSources | Where-Object { $_ -notin $contactSources }).Count -ne 0) {
        Fail "$name reintroduces a non-contact or slide source into the $category pool"
    }
    if ($category -eq 'capture' -and
        ($assetSources.Count -ne 1 -or $assetSources[0] -ne 'aerror_stonehit1')) {
        Fail "$name is not derived solely from the selected CC0 stone-crush recording"
    }
    if ($category -eq 'en_passant' -and
        ($assetSources.Count -ne 1 -or $assetSources[0] -ne 'discofield_brick_crash')) {
        Fail "$name is not derived solely from the selected original CC0 brick-crash recording"
    }
    if ($category -eq 'checkmate' -and
        ($assetSources.Count -ne 1 -or $assetSources[0] -ne 'discofield_stone_crash')) {
        Fail "$name is not derived solely from the selected original CC0 stone-crash recording"
    }
    if ($category -in @('firework_low', 'firework_mid') -and
        ($assetSources.Count -ne 1 -or $assetSources[0] -ne 'rudmer_firework_pops')) {
        Fail "$name is not derived solely from the approved real firework-pop recording"
    }
    if ($category -eq 'firework_high' -and
        ($assetSources.Count -ne 1 -or $assetSources[0] -ne 'rudmer_firework_rocket')) {
        Fail "$name is not derived solely from the approved real firework-rocket recording"
    }

    $path = Join-Path $raw $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Fail "missing runtime asset $name" }
    if ((Get-Sha256 $path) -ne ([string]$asset.sha256).ToLowerInvariant()) {
        Fail "manifest hash mismatch for $name"
    }
    $stream = [IO.File]::OpenRead($path)
    try {
        $header = [byte[]]::new(64)
        $read = $stream.Read($header, 0, $header.Length)
    } finally {
        $stream.Dispose()
    }
    if ($read -lt 4 -or [Text.Encoding]::ASCII.GetString($header, 0, 4) -ne 'OggS') {
        Fail "$name does not have an Ogg identification header"
    }
}

foreach ($entry in $expectedCounts.GetEnumerator()) {
    $actual = [int]($categoryCounts[$entry.Key] ?? 0)
    if ($actual -ne $entry.Value) { Fail "$($entry.Key): expected $($entry.Value), found $actual" }
    $recorded = [int]$manifest.counts.($entry.Key)
    if ($recorded -ne $entry.Value) {
        Fail "manifest counts.$($entry.Key) says $recorded instead of $($entry.Value)"
    }
}

$diskNames = @(Get-ChildItem -LiteralPath $raw -Filter '*.ogg' -File | ForEach-Object Name)
if (@($diskNames | Where-Object { $_ -notin $assetNames }).Count -ne 0 -or
    @($assetNames | Where-Object { $_ -notin $diskNames }).Count -ne 0) {
    Fail 'runtime OGG file set differs from the manifest'
}

$catalogText = Get-Content -LiteralPath $catalogPath -Raw
$catalogNames = @([regex]::Matches($catalogText, 'R\.raw\.([a-z][a-z0-9_]*)') |
    ForEach-Object { "$($_.Groups[1].Value).ogg" })
if ($catalogNames.Count -ne $expectedTotal -or @($catalogNames | Sort-Object -Unique).Count -ne $expectedTotal) {
    Fail "SampledSoundCatalog does not reference $expectedTotal distinct resources exactly once"
}
if (@($catalogNames | Where-Object { $_ -notin $assetNames }).Count -ne 0 -or
    @($assetNames | Where-Object { $_ -notin $catalogNames }).Count -ne 0) {
    Fail 'SampledSoundCatalog file set differs from the manifest'
}

$sourceFiles = @{}
Get-ChildItem -LiteralPath $sourceRoot -Recurse -File | ForEach-Object {
    if ($sourceFiles.ContainsKey($_.BaseName)) { Fail "duplicate retained source stem $($_.BaseName)" }
    $sourceFiles[$_.BaseName] = $_.FullName
}
$hashedSourceIds = @($declaredSources.GetEnumerator() | Where-Object { $_.Value.local_sha256 })
foreach ($entry in $hashedSourceIds) {
    if (-not $sourceFiles.ContainsKey($entry.Key)) { Fail "missing retained source for $($entry.Key)" }
    if ((Get-Sha256 $sourceFiles[$entry.Key]) -ne ([string]$entry.Value.local_sha256).ToLowerInvariant()) {
        Fail "retained source hash mismatch for $($entry.Key)"
    }
}
if ($sourceFiles.Count -ne $hashedSourceIds.Count) {
    Fail "retained source set ($($sourceFiles.Count)) differs from hashed manifest sources ($($hashedSourceIds.Count))"
}
$unexpectedUnused = @($declaredSources.Keys | Where-Object {
    $_ -notin @('ion_sound', 'keyboard_desk', 'mh2o_alabaster', 'snap') -and -not $usedSourceIds.Contains($_)
})
if ($unexpectedUnused.Count -ne 0) {
    Fail "runtime assets do not cite retained sources: $($unexpectedUnused -join ', ')"
}
foreach ($entry in $declaredSources.GetEnumerator() | Where-Object {
    $_.Value.license -eq 'MIT' -and $_.Key -ne 'ion_sound'
}) {
    $actualBlob = Get-GitBlobSha1 $sourceFiles[$entry.Key]
    if ($actualBlob -ne ([string]$entry.Value.upstream_git_blob_sha1).ToLowerInvariant()) {
        Fail "retained MIT source $($entry.Key) does not match its audited upstream Git blob"
    }
}

$notices = Get-Content -LiteralPath $thirdPartyNotices -Raw
foreach ($requiredText in @(
    'Copyright © 2019 by Denis Ineshin',
    'Permission is hereby granted, free of charge',
    'Creative Commons Zero 1.0',
    'https://freesound.org/people/discofield/sounds/711656/',
    'https://freesound.org/people/discofield/sounds/711657/'
)) {
    if (-not $notices.Contains($requiredText)) { Fail "root third-party notices omit '$requiredText'" }
}
$cc0Text = Get-Content -LiteralPath (Join-Path $root 'docs/audio/licenses/CC0-1.0.txt') -Raw
$mitText = Get-Content -LiteralPath (Join-Path $root 'docs/audio/licenses/ion-sound-MIT.txt') -Raw
foreach ($requiredText in @('CC0 1.0 Universal', 'Statement of Purpose', 'Affirmer', 'Copyright and Related Rights')) {
    if (-not $cc0Text.Contains($requiredText)) { Fail "retained CC0 text omits '$requiredText'" }
}
foreach ($requiredText in @(
    'Copyright © 2019 by Denis Ineshin',
    'Permission is hereby granted, free of charge',
    'THE SOFTWARE IS PROVIDED',
    'LIABILITY, WHETHER IN AN ACTION OF CONTRACT'
)) {
    if (-not $mitText.Contains($requiredText)) { Fail "retained MIT text omits '$requiredText'" }
    if (-not $notices.Contains($requiredText)) { Fail "root third-party notices omit MIT text '$requiredText'" }
}

if (-not $FfmpegPath) {
    $command = Get-Command ffmpeg -ErrorAction SilentlyContinue
    if ($command) { $FfmpegPath = $command.Source }
}
$useWslFfmpeg = $false
if ((-not $FfmpegPath -or -not (Test-Path -LiteralPath $FfmpegPath)) -and
    (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    $wslFfmpeg = (& wsl.exe -e sh -lc 'command -v ffmpeg' 2>$null | Out-String).Trim()
    $useWslFfmpeg = $LASTEXITCODE -eq 0 -and [bool]$wslFfmpeg
}
if ($RequireDecode -and
    (-not $useWslFfmpeg) -and
    (-not $FfmpegPath -or -not (Test-Path -LiteralPath $FfmpegPath))) {
    Fail 'full FFmpeg is required for decoded-audio verification'
}

$decoded = 0
$decodedHashes = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
if (($FfmpegPath -and (Test-Path -LiteralPath $FfmpegPath)) -or $useWslFfmpeg) {
    $decodeRoot = Join-Path ([IO.Path]::GetTempPath()) "drawless-audio-verify-$([guid]::NewGuid().ToString('N'))"
    $null = New-Item -ItemType Directory -Path $decodeRoot
    try {
        foreach ($previewName in @('preview-captures-and-castling.ogg', 'audio-pack-preview.ogg')) {
            $previewPath = Join-Path $previewRoot $previewName
            if ($useWslFfmpeg) {
                $wslPreview = (& wsl.exe -e wslpath -a $previewPath | Out-String).Trim()
                $output = (& wsl.exe -e ffmpeg -hide_banner -nostats -i $wslPreview `
                    -map '0:a:0' -f null - 2>&1 | Out-String)
            } else {
                $output = (& $FfmpegPath -hide_banner -nostats -i $previewPath -map '0:a:0' `
                    -f null - 2>&1 | Out-String)
            }
            if ($LASTEXITCODE -ne 0) { Fail "FFmpeg could not decode curated preview $previewName" }
            if ($output -notmatch 'Audio:\s+vorbis[^\r\n]*,\s+48000 Hz,\s+stereo') {
                Fail "curated preview $previewName is not stereo 48 kHz Vorbis"
            }
        }

        foreach ($asset in $assets) {
            $path = Join-Path $raw $asset.file
            $pcmPath = Join-Path $decodeRoot "$($asset.file).s16le"
            if ($useWslFfmpeg) {
                $wslInput = (& wsl.exe -e wslpath -a $path | Out-String).Trim()
                $wslOutput = (& wsl.exe -e wslpath -a $pcmPath | Out-String).Trim()
                $output = (& wsl.exe -e ffmpeg -hide_banner -nostats -i $wslInput -map '0:a:0' `
                    -ac 2 -ar 48000 -f s16le -y $wslOutput 2>&1 | Out-String)
            } else {
                $output = (& $FfmpegPath -hide_banner -nostats -i $path -map '0:a:0' `
                    -ac 2 -ar 48000 -f s16le -y $pcmPath 2>&1 | Out-String)
            }
            if ($LASTEXITCODE -ne 0) { Fail "FFmpeg could not decode $($asset.file)" }
            if ($output -notmatch 'Audio:\s+vorbis[^\r\n]*,\s+48000 Hz,\s+stereo') {
                Fail "unexpected source format for $($asset.file)"
            }

            $bytes = [IO.File]::ReadAllBytes($pcmPath)
            if ($bytes.Length -eq 0 -or $bytes.Length % 2 -ne 0) {
                Fail "invalid decoded PCM length for $($asset.file)"
            }
            $duration = $bytes.Length / (48000.0 * 2.0 * 2.0)
            # Short Vorbis effects can differ from the Ogg granule duration by one codec block
            # because of pre-skip/end padding; keep the allowance far below an audible tail edit.
            if ([Math]::Abs($duration - [double]$asset.duration_seconds) -gt 0.025) {
                Fail "decoded duration mismatch for $($asset.file): manifest=$($asset.duration_seconds) actual=$duration"
            }

            $peak = 0
            [double]$sumSquares = 0
            for ($offset = 0; $offset -lt $bytes.Length; $offset += 2) {
                $sample = [BitConverter]::ToInt16($bytes, $offset)
                $magnitude = [Math]::Abs([int]$sample)
                if ($magnitude -gt $peak) { $peak = $magnitude }
                $sumSquares += [double]$sample * [double]$sample
            }
            $sampleCount = $bytes.Length / 2
            $frameCount = $sampleCount / 2
            $rms = [Math]::Sqrt($sumSquares / $sampleCount)
            if ($peak -ge 32767) { Fail "decoded clipping in $($asset.file)" }
            if ($rms -le 1.04) { Fail "decoded audio is effectively silent in $($asset.file)" }

            $onsetThreshold = [Math]::Max(64, [Math]::Floor($peak * 0.08))
            $onsetSample = $null
            [double]$first50msSquares = 0
            for ($sampleIndex = 0; $sampleIndex -lt $sampleCount; $sampleIndex += 1) {
                $sample = [BitConverter]::ToInt16($bytes, $sampleIndex * 2)
                if ($null -eq $onsetSample -and [Math]::Abs([int]$sample) -ge $onsetThreshold) {
                    $onsetSample = $sampleIndex
                }
                if ($sampleIndex -lt 4800) {
                    $first50msSquares += [double]$sample * [double]$sample
                }
            }
            if ($null -eq $onsetSample) { Fail "no meaningful transient in $($asset.file)" }
            $onsetMillis = [Math]::Floor($onsetSample / 2.0) / 48.0
            if ($asset.category -in @(
                    'move', 'capture', 'castle',
                    'en_passant', 'checkmate',
                    'firework_low', 'firework_mid', 'firework_high',
                    'glass_impact', 'glass_fracture', 'glass_shards'
                ) -and $onsetMillis -gt 30.0) {
                Fail "late audible onset in $($asset.file): $([Math]::Round($onsetMillis, 2)) ms"
            }
            if ($asset.category -eq 'move') {
                $earlyEnergyRatio = $first50msSquares / $sumSquares
                if ($earlyEnergyRatio -lt 0.75) {
                    Fail "sweep-like energy distribution in $($asset.file): only $([Math]::Round($earlyEnergyRatio * 100, 1))% occurs in the first 50 ms"
                }
            }

            $decodedHash = Get-Sha256 $pcmPath
            if (-not $decodedHashes.Add($decodedHash)) {
                Fail "duplicate decoded audio content in $($asset.file)"
            }
            $decoded += 1
        }
    } finally {
        Remove-Item -LiteralPath $decodeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($decoded -eq 0) {
    Write-Host "Sampled audio STRUCTURE PASS: $expectedTotal assets, $($sourceFiles.Count) retained sources; decode not run."
} else {
    Write-Host "Sampled audio PASS: $expectedTotal assets, $($sourceFiles.Count) retained sources, $decoded decoded and unique."
}
