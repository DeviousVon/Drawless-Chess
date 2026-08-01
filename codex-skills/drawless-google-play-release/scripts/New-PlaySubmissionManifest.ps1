[CmdletBinding()]
param(
    [string]$RepositoryRoot = 'C:\src',
    [string]$EvidenceManifest = 'build\release-evidence\play-aab.json',
    [string]$Bundle = 'android\app\build\outputs\bundle\release\app-release.aab',
    [string]$ReleaseNotes,
    [string]$ReleaseName,
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
$status = @(& git -c core.excludesFile= -C $RepositoryRoot status --porcelain=v1 --untracked-files=all 2>$null)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Git status.' }
if (-not $AllowDirty -and $status.Count -gt 0) {
    throw 'Google Play submission requires a clean worktree.'
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
Assert-Equal 'application ID' ([string]$evidence.applicationId) 'com.drawlesschess'
Assert-Equal 'signature verification' ([bool]$evidence.signatureVerified).ToString() 'True'
Assert-Equal 'native page alignment' ([string]$evidence.apkNativePageAlignment) 'PAGE_ALIGNMENT_16K'
if ([int]$evidence.targetSdk -lt 36) {
    throw "Target SDK is below the project release baseline: $($evidence.targetSdk)"
}
$actualAbis = @($evidence.abis | Sort-Object)
$expectedAbis = @('arm64-v8a', 'x86_64')
if (Compare-Object $expectedAbis $actualAbis -CaseSensitive) {
    throw "ABI mismatch: expected $($expectedAbis -join ', '), found $($actualAbis -join ', ')"
}

$bundleHash = (Get-FileHash -LiteralPath $bundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
Assert-Equal 'AAB SHA-256' $bundleHash ([string]$evidence.sha256)
$head = (& git -C $RepositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the repository HEAD.' }
$candidateCommit = [string]$evidence.repositoryCommit
$resolvedCandidate = (& git -C $RepositoryRoot rev-parse "$candidateCommit^{commit}").Trim()
if ($LASTEXITCODE -ne 0) { throw "Candidate commit is not present locally: $candidateCommit" }
Assert-Equal 'resolved candidate commit' $resolvedCandidate $candidateCommit
if ($head -cne $candidateCommit) {
    & git -C $RepositoryRoot merge-base --is-ancestor $candidateCommit $head
    if ($LASTEXITCODE -ne 0) {
        throw 'The verified candidate is not an ancestor of the current repository HEAD.'
    }
    $postCandidateFiles = @(& git -C $RepositoryRoot diff --name-only "$candidateCommit..$head")
    $releaseAffectingFiles = @($postCandidateFiles | Where-Object {
        $_ -cne 'AGENTS.md' -and $_ -cnotmatch '^codex-skills/'
    })
    if ($releaseAffectingFiles.Count -gt 0) {
        throw "Repository HEAD contains post-candidate product changes: $($releaseAffectingFiles -join ', ')"
    }
}

$version = [string]$evidence.versionName
if (-not $ReleaseNotes) { $ReleaseNotes = "play\release-notes-$version.md" }
$releaseNotesPath = Resolve-RepoPath $ReleaseNotes
if (-not (Test-Path -LiteralPath $releaseNotesPath -PathType Leaf)) {
    throw "Localized release notes are missing: $releaseNotesPath"
}

$notesText = Get-Content -LiteralPath $releaseNotesPath -Raw
$pattern = '(?ms)^##\s+(?<locale>[a-z]{2}(?:-[A-Za-z0-9]{2,3})?)\s*\r?\n\s*```text\s*\r?\n(?<note>.*?)\r?\n```'
$noteMatches = [regex]::Matches($notesText, $pattern)
if ($noteMatches.Count -eq 0) {
    throw 'No localized text blocks were found in the release-notes file.'
}
$localizedNotes = [ordered]@{}
foreach ($match in $noteMatches) {
    $locale = $match.Groups['locale'].Value
    $note = $match.Groups['note'].Value.Trim()
    if ($localizedNotes.Contains($locale)) { throw "Duplicate release-note locale: $locale" }
    if ($note.Length -gt 500) { throw "Release notes for $locale exceed 500 characters." }
    $localizedNotes[$locale] = $note
}
if (-not $localizedNotes.Contains('en-US')) { throw 'Release notes must include en-US.' }

if (-not $ReleaseName) {
    $ReleaseName = "$version ($([int]$evidence.versionCode))"
}

[ordered]@{
    ok = $true
    developerName = 'BB_Games'
    developerId = '8465135086815564930'
    accountIndex = 1
    appName = 'Drawless Chess'
    consoleAppId = '4975227002124776938'
    packageName = 'com.drawlesschess'
    track = [ordered]@{
        type = 'closedTesting'
        name = 'Alpha'
        id = '4699411573101185907'
        url = 'https://play.google.com/console/u/1/developers/8465135086815564930/app/4975227002124776938/tracks/4699411573101185907'
    }
    versionName = $version
    versionCode = [int]$evidence.versionCode
    releaseName = $ReleaseName
    repositoryCommit = $candidateCommit
    repositoryHead = $head
    bundlePath = $bundlePath
    bundleSha256 = $bundleHash
    targetSdk = [int]$evidence.targetSdk
    abis = $actualAbis
    uploadCertificateSha256 = [string]$evidence.uploadCertificateSha256
    releaseNotesPath = $releaseNotesPath
    localizedReleaseNotes = $localizedNotes
    dirtyWorktreeAllowedForThisCheck = [bool]$AllowDirty
} | ConvertTo-Json -Depth 8
