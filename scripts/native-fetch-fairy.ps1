#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$UpstreamOnly,
    [string]$Destination
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    throw "native-fetch-fairy: $Message"
}

function Get-LockedProperty {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name
    )

    $values = foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line.StartsWith("$Name=", [System.StringComparison]::Ordinal)) {
            $line.Substring($Name.Length + 1).TrimEnd("`r")
        }
    }
    if (@($values).Count -ne 1) {
        Fail "expected exactly one $Name property in $Path"
    }
    return [string]$values
}

function Invoke-Native {
    param(
        [Parameter(Mandatory)][string]$Executable,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$Capture
    )

    if ($Capture) {
        $output = & $Executable @Arguments 2>&1
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            Fail "command failed ($exitCode): $Executable $($Arguments -join ' ')`n$($output -join "`n")"
        }
        return (($output -join "`n").Trim())
    }

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        Fail "command failed ($LASTEXITCODE): $Executable $($Arguments -join ' ')"
    }
}

function Add-Bytes {
    param(
        [Parameter(Mandatory)][System.IO.Stream]$Stream,
        [Parameter(Mandatory)][byte[]]$Bytes
    )
    $Stream.Write($Bytes, 0, $Bytes.Length)
}

function Add-Utf8 {
    param(
        [Parameter(Mandatory)][System.IO.Stream]$Stream,
        [Parameter(Mandatory)][string]$Text
    )
    Add-Bytes -Stream $Stream -Bytes ([System.Text.Encoding]::UTF8.GetBytes($Text))
}

$scriptDirectory = $PSScriptRoot
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDirectory '..'))
$nativeRoot = Join-Path $repositoryRoot 'engine/native'
$lockFile = Join-Path $nativeRoot 'upstream.properties'

if (-not (Test-Path -LiteralPath $lockFile -PathType Leaf)) {
    Fail "missing lock file: $lockFile"
}
$gitCommand = Get-Command git.exe -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $gitCommand) {
    $gitCommand = Get-Command git -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
}
if (-not $gitCommand) {
    Fail 'Git for Windows is required on PATH'
}
$gitExecutable = $gitCommand.Source

$upstreamRepository = Get-LockedProperty -Path $lockFile -Name repository
$revision = Get-LockedProperty -Path $lockFile -Name revision
$tree = Get-LockedProperty -Path $lockFile -Name tree
$patchedTree = Get-LockedProperty -Path $lockFile -Name patchedTree
$sourceDirectory = Get-LockedProperty -Path $lockFile -Name sourceDirectory
$patchSeriesRelative = Get-LockedProperty -Path $lockFile -Name patchSeries
$expectedPatchHash = Get-LockedProperty -Path $lockFile -Name patchSeriesSha256
$patchVersion = Get-LockedProperty -Path $lockFile -Name drawlessPatchVersion

if ($upstreamRepository -cne 'https://github.com/fairy-stockfish/Fairy-Stockfish.git') {
    Fail 'unexpected upstream repository in lock'
}
if ($revision -cnotmatch '^[0-9a-f]{40}$') { Fail 'revision must be a full 40-character Git hash' }
if ($tree -cnotmatch '^[0-9a-f]{40}$') { Fail 'tree must be a full 40-character Git hash' }
if ($patchedTree -cnotmatch '^[0-9a-f]{40}$') { Fail 'patched tree must be a full 40-character Git hash' }
if ($expectedPatchHash -cnotmatch '^[0-9a-f]{64}$') { Fail 'patch-series SHA-256 is invalid' }
if ($patchVersion -notmatch '^[1-9][0-9]*$') { Fail 'invalid Drawless patch version' }

if ([string]::IsNullOrWhiteSpace($Destination)) {
    $destinationPath = [System.IO.Path]::GetFullPath((Join-Path $nativeRoot $sourceDirectory))
} elseif ([System.IO.Path]::IsPathRooted($Destination)) {
    $destinationPath = [System.IO.Path]::GetFullPath($Destination)
} else {
    $destinationPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $Destination))
}

if (Test-Path -LiteralPath $destinationPath) {
    Fail "destination already exists; validate it or remove it deliberately: $destinationPath"
}

$patchSeries = [System.IO.Path]::GetFullPath((Join-Path $nativeRoot $patchSeriesRelative))
if (-not $UpstreamOnly -and -not (Test-Path -LiteralPath $patchSeries -PathType Leaf)) {
    Fail "missing ordered patch series: $patchSeries"
}

$destinationParent = Split-Path -Parent $destinationPath
[System.IO.Directory]::CreateDirectory($destinationParent) | Out-Null
$temporaryRoot = Join-Path $destinationParent ('.fairy-fetch.' + [Guid]::NewGuid().ToString('N'))
$temporaryCheckout = Join-Path $temporaryRoot 'Fairy-Stockfish'
[System.IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null

try {
    Invoke-Native -Executable $gitExecutable -Arguments @('init', '--quiet', $temporaryCheckout)
    # Ignore machine-wide line-ending settings so the checkout and staged patch remain deterministic.
    Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'config', 'core.autocrlf', 'false')
    Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'config', 'core.eol', 'lf')
    Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'config', 'core.filemode', 'false')
    Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'remote', 'add', 'origin', $upstreamRepository)
    Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'fetch', '--quiet', '--depth', '1', '--no-tags', 'origin', $revision)
    Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'checkout', '--quiet', '--detach', 'FETCH_HEAD')

    $actualRevision = Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'rev-parse', 'HEAD') -Capture
    $actualTree = Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'rev-parse', 'HEAD^{tree}') -Capture
    if ($actualRevision -cne $revision) {
        Fail "fetched revision mismatch: expected $revision, received $actualRevision"
    }
    if ($actualTree -cne $tree) {
        Fail "fetched tree mismatch: expected $tree, received $actualTree"
    }

    $patchesApplied = (-not $UpstreamOnly).ToString().ToLowerInvariant()
    $patchHash = 'none'
    if (-not $UpstreamOnly) {
        $patchDirectory = Split-Path -Parent $patchSeries
        $seriesBytes = [System.IO.File]::ReadAllBytes($patchSeries)
        $seriesLines = [System.IO.File]::ReadAllLines($patchSeries)
        $hashInput = [System.IO.MemoryStream]::new()
        try {
            Add-Utf8 -Stream $hashInput -Text "series`0"
            Add-Bytes -Stream $hashInput -Bytes $seriesBytes
            $patchCount = 0

            foreach ($rawEntry in $seriesLines) {
                $patchEntry = $rawEntry.TrimEnd("`r")
                if ([string]::IsNullOrWhiteSpace($patchEntry) -or $patchEntry.StartsWith('#')) {
                    continue
                }
                $segments = $patchEntry -split '[\\/]'
                if ([System.IO.Path]::IsPathRooted($patchEntry) -or $segments -contains '..') {
                    Fail "unsafe patch path in series: $patchEntry"
                }

                $patchFile = [System.IO.Path]::GetFullPath((Join-Path $patchDirectory $patchEntry))
                $safeRoot = [System.IO.Path]::GetFullPath($patchDirectory) + [System.IO.Path]::DirectorySeparatorChar
                if (-not $patchFile.StartsWith($safeRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                    Fail "unsafe patch path in series: $patchEntry"
                }
                if (-not (Test-Path -LiteralPath $patchFile -PathType Leaf)) {
                    Fail "series references missing patch: $patchFile"
                }

                Add-Utf8 -Stream $hashInput -Text "`0patch`0$patchEntry`0"
                Add-Bytes -Stream $hashInput -Bytes ([System.IO.File]::ReadAllBytes($patchFile))
                Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'apply', '--check', '--index', $patchFile)
                Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'apply', '--index', $patchFile)
                $patchCount++
            }

            if ($patchCount -eq 0) { Fail 'the production patch series is empty' }
            Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'diff', '--cached', '--check')
            $hashInput.Position = 0
            $hasher = [System.Security.Cryptography.SHA256]::Create()
            try {
                $patchHash = ([BitConverter]::ToString($hasher.ComputeHash($hashInput))).Replace('-', '').ToLowerInvariant()
            } finally {
                $hasher.Dispose()
            }
        } finally {
            $hashInput.Dispose()
        }

        if ($patchHash -cne $expectedPatchHash) {
            Fail "patch series SHA-256 mismatch: expected $expectedPatchHash, received $patchHash"
        }
        $actualPatchedTree = Invoke-Native -Executable $gitExecutable -Arguments @('-C', $temporaryCheckout, 'write-tree') -Capture
        if ($actualPatchedTree -cne $patchedTree) {
            Fail "patched tree mismatch: expected $patchedTree, received $actualPatchedTree"
        }
    }

    $stateFile = Join-Path $temporaryCheckout '.drawless-source-state.properties'
    $state = @(
        'schemaVersion=1'
        "upstreamRevision=$revision"
        "upstreamTree=$tree"
        "patchedTree=$patchedTree"
        "patchVersion=$patchVersion"
        "patchesApplied=$patchesApplied"
        "patchSeriesSha256=$patchHash"
        ''
    ) -join "`n"
    [System.IO.File]::WriteAllText($stateFile, $state, [System.Text.UTF8Encoding]::new($false))

    if (Test-Path -LiteralPath $destinationPath) {
        Fail "destination appeared before publication: $destinationPath"
    }
    try {
        [System.IO.Directory]::Move($temporaryCheckout, $destinationPath)
    } catch {
        Fail "could not atomically publish source at $destinationPath`: $($_.Exception.Message)"
    }

    Write-Host "Prepared Fairy-Stockfish source at $destinationPath"
    Write-Host "  upstream revision: $revision"
    Write-Host "  upstream tree:     $tree"
    Write-Host "  patches applied:   $patchesApplied"
    Write-Host "  patch series hash: $patchHash"
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
