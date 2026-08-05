[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path,
    [string]$EvidenceManifest = 'build\release-evidence\play-aab.json',
    [string]$Bundle = 'android\app\build\outputs\bundle\release\app-release.aab',
    [string]$Version,
    [string]$ReleaseCommit,
    [switch]$AllowDirty
)

$ErrorActionPreference = 'Stop'

function Resolve-RepoPath([string]$PathValue) {
    if ([IO.Path]::IsPathRooted($PathValue)) {
        return [IO.Path]::GetFullPath($PathValue)
    }
    return [IO.Path]::GetFullPath((Join-Path $RepositoryRoot $PathValue))
}

function Assert-Equal([string]$Label, [string]$Actual, [string]$Expected) {
    if ($Actual -cne $Expected) {
        throw "$Label mismatch: expected '$Expected', found '$Actual'"
    }
}

$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot)
if (-not (Test-Path -LiteralPath (Join-Path $RepositoryRoot '.git'))) {
    throw "Not a Git repository: $RepositoryRoot"
}

$status = @(& git -c core.excludesFile= -C $RepositoryRoot status --porcelain=v1 --untracked-files=all 2>$null)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Git status.' }
if (-not $AllowDirty -and $status.Count -gt 0) {
    throw 'Release publishing requires a clean worktree.'
}

$evidencePath = Resolve-RepoPath $EvidenceManifest
$bundlePath = Resolve-RepoPath $Bundle
if (-not (Test-Path -LiteralPath $evidencePath -PathType Leaf)) {
    throw "Release evidence is missing: $evidencePath"
}
if (-not (Test-Path -LiteralPath $bundlePath -PathType Leaf)) {
    throw "Signed AAB is missing: $bundlePath"
}

$evidence = Get-Content -LiteralPath $evidencePath -Raw | ConvertFrom-Json
if (-not $Version) { $Version = [string]$evidence.versionName }
if (-not $ReleaseCommit) { $ReleaseCommit = [string]$evidence.repositoryCommit }

Assert-Equal 'application ID' ([string]$evidence.applicationId) 'com.drawlesschess'
Assert-Equal 'version name' ([string]$evidence.versionName) $Version
Assert-Equal 'release commit format' ([bool]([string]$ReleaseCommit -cmatch '^[0-9a-f]{40}$')).ToString() 'True'
Assert-Equal 'signature verification' ([bool]$evidence.signatureVerified).ToString() 'True'
Assert-Equal 'native page alignment' ([string]$evidence.apkNativePageAlignment) 'PAGE_ALIGNMENT_16K'

$actualAbis = @($evidence.abis | Sort-Object)
$expectedAbis = @('arm64-v8a', 'x86_64')
if (Compare-Object $expectedAbis $actualAbis -CaseSensitive) {
    throw "ABI mismatch: expected $($expectedAbis -join ', '), found $($actualAbis -join ', ')"
}

$resolvedCommit = (& git -C $RepositoryRoot rev-parse "$ReleaseCommit^{commit}").Trim()
if ($LASTEXITCODE -ne 0) { throw "Release commit is not present locally: $ReleaseCommit" }
Assert-Equal 'resolved release commit' $resolvedCommit $ReleaseCommit
Assert-Equal 'evidence source commit' ([string]$evidence.sourceArchive.commit) $ReleaseCommit

$bundleHash = (Get-FileHash -LiteralPath $bundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
Assert-Equal 'AAB SHA-256' $bundleHash ([string]$evidence.sha256)

$sourcePath = Resolve-RepoPath (Join-Path 'release' ([string]$evidence.sourceArchive.file))
if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    throw "Corresponding-source archive is missing: $sourcePath"
}
$sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
Assert-Equal 'source archive SHA-256' $sourceHash ([string]$evidence.sourceArchive.sha256)

$tracked = @(& git -C $RepositoryRoot ls-files)
$forbidden = @($tracked | Where-Object {
    $_ -cmatch '(?i)(^|/)(signing\.properties|[^/]+\.(jks|keystore|p12|pfx|pem|key))$'
})
if ($forbidden.Count -gt 0) {
    throw "Tracked signing material is forbidden: $($forbidden -join ', ')"
}

$branch = (& git -C $RepositoryRoot branch --show-current).Trim()
$remote = (& git -C $RepositoryRoot remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0 -or $remote -notmatch 'github\.com[:/]DeviousVon/Drawless-Chess(?:\.git)?$') {
    throw "Unexpected GitHub origin: $remote"
}

[ordered]@{
    ok = $true
    repository = 'DeviousVon/Drawless-Chess'
    repositoryRoot = $RepositoryRoot
    branch = $branch
    versionName = $Version
    versionCode = [int]$evidence.versionCode
    tag = "v$Version"
    releaseCommit = $ReleaseCommit
    bundle = $bundlePath
    bundleSha256 = $bundleHash
    sourceArchive = $sourcePath
    sourceArchiveSha256 = $sourceHash
    uploadCertificateSha256 = [string]$evidence.uploadCertificateSha256
    dirtyWorktreeAllowedForThisCheck = [bool]$AllowDirty
} | ConvertTo-Json -Depth 4
