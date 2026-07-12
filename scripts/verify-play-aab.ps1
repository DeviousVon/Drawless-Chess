[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Bundle,

    [Parameter(Mandatory = $true)]
    [string]$SourceArchive,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedUploadCertificateSha256,

    [string]$OutputManifest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$Message) {
    throw "verify-play-aab: $Message"
}

function Read-Properties([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            Fail "invalid property in ${Path}: $trimmed"
        }
        $values[$trimmed.Substring(0, $separator).Trim()] =
            $trimmed.Substring($separator + 1).Trim()
    }
    return $values
}

function Find-JavaTool([string]$Name) {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += Join-Path $env:JAVA_HOME "bin\$Name.exe"
        $candidates += Join-Path $env:JAVA_HOME "bin\$Name"
    }
    $candidates += "C:\Program Files\Android\Android Studio\jbr\bin\$Name.exe"
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command -and $command.Source -notmatch '(?i)\\Windows\\System32\\bash\.exe$') {
        $candidates += $command.Source
    }
    $tool = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if (-not $tool) {
        Fail "$Name was not found; set JAVA_HOME to a JDK 17 or newer"
    }
    return (Resolve-Path -LiteralPath $tool).Path
}

function Find-Bash {
    $candidates = @('C:\Program Files\Git\bin\bash.exe')
    $command = Get-Command bash -ErrorAction SilentlyContinue
    if ($command) {
        $candidates += $command.Source
    }
    $bash = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if (-not $bash) {
        Fail 'bash was not found; canonical corresponding-source regeneration is required'
    }
    return (Resolve-Path -LiteralPath $bash).Path
}

function Find-AndroidSdk([string]$AndroidRoot) {
    $candidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    ) | Where-Object { $_ }
    $localProperties = Join-Path $AndroidRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $sdkProperty = Read-Properties $localProperties
        if ($sdkProperty.ContainsKey('sdk.dir')) {
            $candidates = @($sdkProperty['sdk.dir'] -replace '\\:', ':' -replace '\\\\', '\') +
                $candidates
        }
    }
    $sdk = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Container } |
        Select-Object -First 1
    if (-not $sdk) {
        Fail 'Android SDK not found; set ANDROID_SDK_ROOT or ANDROID_HOME'
    }
    return (Resolve-Path -LiteralPath $sdk).Path
}

function Quote-GradleJavaArgument([string]$Value) {
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Invoke-Bundletool([string[]]$Arguments) {
    $argumentLine = ($Arguments | ForEach-Object { Quote-GradleJavaArgument $_ }) -join ' '
    Push-Location $script:AndroidRoot
    try {
        $result = & $script:GradleWrapper -q bundletool "--args=$argumentLine" --console=plain 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $text = ($result | ForEach-Object { $_.ToString() }) -join "`n"
    if ($exitCode -ne 0) {
        Fail "bundletool failed:`n$text"
    }
    return $text.Trim()
}

function Assert-Equal([string]$Label, $Actual, $Expected) {
    if ($Actual -cne $Expected) {
        Fail "$Label mismatch; expected '$Expected', found '$Actual'"
    }
}

function Get-ZipEntrySha256([IO.Compression.ZipArchiveEntry]$Entry) {
    $stream = $Entry.Open()
    $digest = [Security.Cryptography.SHA256]::Create()
    try {
        return ($digest.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $digest.Dispose()
        $stream.Dispose()
    }
}

$script:RepositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$script:AndroidRoot = Join-Path $script:RepositoryRoot 'android'
$script:GradleWrapper = Join-Path $script:AndroidRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $script:GradleWrapper -PathType Leaf)) {
    Fail "Gradle wrapper not found: $script:GradleWrapper"
}

$bundlePath = (Resolve-Path -LiteralPath $Bundle -ErrorAction Stop).Path
if ([IO.Path]::GetExtension($bundlePath) -ne '.aab') {
    Fail "bundle must have the .aab extension: $bundlePath"
}

$appBuildPath = Join-Path $script:AndroidRoot 'app\build.gradle.kts'
$appBuild = Get-Content -LiteralPath $appBuildPath -Raw
$applicationMatch = [regex]::Match($appBuild, '(?m)^\s*applicationId\s*=\s*"([^"]+)"')
$versionCodeMatch = [regex]::Match($appBuild, '(?m)^\s*versionCode\s*=\s*(\d+)')
$versionNameMatch = [regex]::Match($appBuild, '(?m)^\s*versionName\s*=\s*"([^"]+)"')
$minSdkMatch = [regex]::Match($appBuild, '(?m)^\s*minSdk\s*=\s*(\d+)')
$targetSdkMatch = [regex]::Match($appBuild, '(?m)^\s*targetSdk\s*=\s*(\d+)')
foreach ($match in @(
    $applicationMatch,
    $versionCodeMatch,
    $versionNameMatch,
    $minSdkMatch,
    $targetSdkMatch
)) {
    if (-not $match.Success) {
        Fail "could not read release metadata from $appBuildPath"
    }
}

$expectedApplicationId = $applicationMatch.Groups[1].Value
$expectedVersionCode = $versionCodeMatch.Groups[1].Value
$expectedVersionName = $versionNameMatch.Groups[1].Value
$expectedMinSdk = $minSdkMatch.Groups[1].Value
$expectedTargetSdk = $targetSdkMatch.Groups[1].Value
$expectedAbis = @('arm64-v8a', 'x86_64')
$expectedCertificateDigest = $ExpectedUploadCertificateSha256.Replace(':', '').Trim().ToLowerInvariant()
if ($expectedCertificateDigest -cnotmatch '^[0-9a-f]{64}$') {
    Fail 'expected upload-certificate SHA-256 must contain exactly 64 hexadecimal digits'
}

$git = Get-Command git -ErrorAction SilentlyContinue
if (-not $git) {
    Fail 'git was not found; exact release verification requires a clean source commit'
}
$commitOutput = @(& $git.Source -C $script:RepositoryRoot rev-parse --verify HEAD 2>&1 |
    ForEach-Object { $_.ToString() })
if ($LASTEXITCODE -ne 0) {
    Fail "could not resolve repository commit: $($commitOutput -join ' ')"
}
$repositoryCommit = ($commitOutput -join '').Trim()
if ($repositoryCommit -cnotmatch '^[0-9a-f]{40}$') {
    Fail "repository HEAD is not a full Git commit: $repositoryCommit"
}
$statusOutput = @(& $git.Source -C $script:RepositoryRoot status --porcelain `
    --untracked-files=all 2>&1 | ForEach-Object { $_.ToString() })
if ($LASTEXITCODE -ne 0) {
    Fail "could not inspect repository state: $($statusOutput -join ' ')"
}
if ($statusOutput.Count -gt 0) {
    Fail 'repository must be clean so the AAB and corresponding source have one exact identity'
}

$sourceArchivePath = (Resolve-Path -LiteralPath $SourceArchive -ErrorAction Stop).Path
if (-not $sourceArchivePath.EndsWith('.tar.gz', [StringComparison]::OrdinalIgnoreCase)) {
    Fail "source archive must end in .tar.gz: $sourceArchivePath"
}
$tar = Get-Command tar -ErrorAction SilentlyContinue
if (-not $tar) {
    Fail 'tar was not found; it is required to verify SOURCE-COMMIT and SOURCE-MANIFEST.sha256'
}
$sourceRoot = "drawless-chess-$expectedVersionName-source"
$sourceInspectionRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'drawless-source-inspection-' + [guid]::NewGuid().ToString('N')
)
New-Item -ItemType Directory -Path $sourceInspectionRoot | Out-Null
$sourceManifestHashes = @{}
$sourceManifestSha256 = ''
$sourceVerifiedFileCount = 0
$archiveCommit = ''
try {
    $entryOutput = @(& $tar.Source -tzf $sourceArchivePath 2>&1 |
        ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0 -or $entryOutput.Count -eq 0) {
        Fail 'source archive could not be listed or is empty'
    }
    $duplicateEntries = @($entryOutput | Group-Object { $_.ToLowerInvariant() } |
        Where-Object Count -gt 1)
    if ($duplicateEntries.Count -gt 0) {
        Fail "source archive contains duplicate or case-colliding paths: $($duplicateEntries[0].Name)"
    }
    foreach ($entryName in $entryOutput) {
        if ($entryName.Contains('\') -or $entryName.Contains(':') -or
            ($entryName -cne $sourceRoot -and $entryName -cne "$sourceRoot/" -and
                -not $entryName.StartsWith("$sourceRoot/", [StringComparison]::Ordinal))) {
            Fail "source archive contains an unsafe or unexpected path: $entryName"
        }
        $relativeName = if ($entryName.StartsWith("$sourceRoot/", [StringComparison]::Ordinal)) {
            $entryName.Substring($sourceRoot.Length + 1).TrimEnd('/')
        } else {
            ''
        }
        $parts = @($relativeName.Split('/', [StringSplitOptions]::RemoveEmptyEntries))
        if ($parts -contains '.' -or $parts -contains '..') {
            Fail "source archive contains path traversal: $entryName"
        }
    }

    $verboseOutput = @(& $tar.Source -tvzf $sourceArchivePath 2>&1 |
        ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0 -or $verboseOutput.Count -ne $entryOutput.Count) {
        Fail 'source archive type listing was incomplete'
    }
    foreach ($verboseLine in $verboseOutput) {
        if (-not $verboseLine -or $verboseLine[0] -notin @('-', 'd')) {
            Fail 'source archive contains a link, device, pipe, or unsupported entry type'
        }
    }

    & $tar.Source -xzf $sourceArchivePath -C $sourceInspectionRoot
    if ($LASTEXITCODE -ne 0) {
        Fail 'source archive extraction failed after its safety checks'
    }
    $extractedRoot = Join-Path $sourceInspectionRoot $sourceRoot
    $commitPath = Join-Path $extractedRoot 'SOURCE-COMMIT'
    $manifestPath = Join-Path $extractedRoot 'SOURCE-MANIFEST.sha256'
    if (-not (Test-Path -LiteralPath $commitPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        Fail 'source archive is missing SOURCE-COMMIT or SOURCE-MANIFEST.sha256'
    }
    $archiveCommit = [IO.File]::ReadAllText($commitPath).Trim()
    Assert-Equal 'source archive commit' $archiveCommit $repositoryCommit
    $sourceManifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).
        Hash.ToLowerInvariant()

    $manifestPathSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $caseFoldedPathSet = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::OrdinalIgnoreCase
    )
    foreach ($manifestLine in Get-Content -LiteralPath $manifestPath) {
        $match = [regex]::Match($manifestLine, '^([0-9a-f]{64})\s+\*?(\./.+)$')
        if (-not $match.Success) {
            Fail "source manifest contains an invalid row: $manifestLine"
        }
        $expectedHash = $match.Groups[1].Value
        $relativePath = $match.Groups[2].Value.Substring(2)
        if ($relativePath -ceq 'SOURCE-MANIFEST.sha256' -or $relativePath.Contains('\') -or
            $relativePath.Contains(':') -or [IO.Path]::IsPathRooted($relativePath)) {
            Fail "source manifest contains an unsafe path: $relativePath"
        }
        $parts = @($relativePath.Split('/', [StringSplitOptions]::RemoveEmptyEntries))
        if ($parts.Count -eq 0 -or $parts -contains '.' -or $parts -contains '..') {
            Fail "source manifest contains path traversal: $relativePath"
        }
        if (-not $manifestPathSet.Add($relativePath) -or
            -not $caseFoldedPathSet.Add($relativePath)) {
            Fail "source manifest contains a duplicate or case-colliding path: $relativePath"
        }
        $extractedPath = [IO.Path]::GetFullPath((
            Join-Path $extractedRoot ($relativePath.Replace('/', [IO.Path]::DirectorySeparatorChar))
        ))
        $extractedPrefix = $extractedRoot.TrimEnd(
            [IO.Path]::DirectorySeparatorChar,
            [IO.Path]::AltDirectorySeparatorChar
        ) + [IO.Path]::DirectorySeparatorChar
        if (-not $extractedPath.StartsWith($extractedPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $extractedPath -PathType Leaf)) {
            Fail "source manifest file is absent or escapes the archive root: $relativePath"
        }
        $actualHash = (Get-FileHash -LiteralPath $extractedPath -Algorithm SHA256).
            Hash.ToLowerInvariant()
        if ($actualHash -cne $expectedHash) {
            Fail "source manifest hash mismatch: $relativePath"
        }
        $sourceManifestHashes[$relativePath] = $actualHash
    }

    $sourceManifestDigestPath = Join-Path $extractedRoot 'SOURCE-MANIFEST.sha256.digest'
    if (-not (Test-Path -LiteralPath $sourceManifestDigestPath -PathType Leaf)) {
        Fail 'source archive is missing SOURCE-MANIFEST.sha256.digest'
    }
    $recordedManifestDigest = [IO.File]::ReadAllText($sourceManifestDigestPath).Trim()
    if ($recordedManifestDigest -cnotmatch '^[0-9a-f]{64}$' -or
        $recordedManifestDigest -cne $sourceManifestSha256) {
        Fail 'source archive manifest digest does not match SOURCE-MANIFEST.sha256'
    }

    $actualArchiveFiles = @(Get-ChildItem -LiteralPath $extractedRoot -Recurse -File |
        ForEach-Object {
            [IO.Path]::GetRelativePath($extractedRoot, $_.FullName).Replace('\', '/')
        } | Where-Object {
            $_ -cne 'SOURCE-MANIFEST.sha256' -and
            $_ -cne 'SOURCE-MANIFEST.sha256.digest'
        })
    if ($actualArchiveFiles.Count -ne $manifestPathSet.Count) {
        Fail 'source archive file set does not match SOURCE-MANIFEST.sha256'
    }
    foreach ($actualRelativePath in $actualArchiveFiles) {
        if (-not $manifestPathSet.Contains($actualRelativePath)) {
            Fail "source archive contains an unmanifested file: $actualRelativePath"
        }
    }
    $sourceVerifiedFileCount = $manifestPathSet.Count

    $archivedNativeGit = Join-Path $extractedRoot (
        'engine\native\upstream\Fairy-Stockfish\.git'
    )
    if (Test-Path -LiteralPath $archivedNativeGit) {
        Fail 'source archive contains administrative native Git metadata'
    }
    if (-not $sourceManifestHashes.ContainsKey('engine/native/archive-fairy-source.sha256')) {
        Fail 'source archive is missing its deterministic native source manifest'
    }
} finally {
    if (Test-Path -LiteralPath $sourceInspectionRoot) {
        Remove-Item -LiteralPath $sourceInspectionRoot -Recurse -Force
    }
}

$releaseReportNames = @('release-runtime-dependencies.txt', 'release-sbom.cdx.json')
$releaseReportEvidence = [Collections.Generic.List[object]]::new()
$freshnessRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'drawless-sbom-freshness-' + [guid]::NewGuid().ToString('N')
)
New-Item -ItemType Directory -Path $freshnessRoot | Out-Null
try {
    & (Join-Path $script:RepositoryRoot 'scripts\generate-release-sbom.ps1') `
        -OutputDirectory $freshnessRoot
    foreach ($reportName in $releaseReportNames) {
        $repositoryReport = Join-Path $script:RepositoryRoot "release\reports\$reportName"
        $freshReport = Join-Path $freshnessRoot $reportName
        if (-not (Test-Path -LiteralPath $repositoryReport -PathType Leaf) -or
            -not (Test-Path -LiteralPath $freshReport -PathType Leaf)) {
            Fail "release dependency evidence is absent: $reportName"
        }
        $repositoryHash = (Get-FileHash -LiteralPath $repositoryReport -Algorithm SHA256).Hash.
            ToLowerInvariant()
        $freshHash = (Get-FileHash -LiteralPath $freshReport -Algorithm SHA256).Hash.
            ToLowerInvariant()
        if ($repositoryHash -cne $freshHash) {
            Fail "release dependency evidence is stale: $reportName; regenerate and commit it"
        }
        $sourceReportPath = "release/reports/$reportName"
        if (-not $sourceManifestHashes.ContainsKey($sourceReportPath) -or
            $sourceManifestHashes[$sourceReportPath] -cne $repositoryHash) {
            Fail "source archive does not contain the exact $reportName"
        }
        $releaseReportEvidence.Add([ordered]@{
            file = $reportName
            sha256 = $repositoryHash
            fresh = $true
            sourceArchiveMatched = $true
        })
    }
} finally {
    if (Test-Path -LiteralPath $freshnessRoot) {
        Remove-Item -LiteralPath $freshnessRoot -Recurse -Force
    }
}
$sourceArchiveSha256 = (Get-FileHash -LiteralPath $sourceArchivePath -Algorithm SHA256).Hash.
    ToLowerInvariant()
$canonicalSourceRelative = 'build/release-evidence/canonical-source-' +
    [guid]::NewGuid().ToString('N') + '.tar.gz'
$canonicalSourcePath = Join-Path $script:RepositoryRoot (
    $canonicalSourceRelative.Replace('/', [IO.Path]::DirectorySeparatorChar)
)
$canonicalInspectionRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'drawless-canonical-source-' + [guid]::NewGuid().ToString('N')
)
[void][IO.Directory]::CreateDirectory((Split-Path -Parent $canonicalSourcePath))
[void][IO.Directory]::CreateDirectory($canonicalInspectionRoot)
$bash = Find-Bash
try {
    $gitRoot = Split-Path -Parent (Split-Path -Parent $bash)
    $cygpath = Join-Path $gitRoot 'usr\bin\cygpath.exe'
    $bashRepositoryRoot = if (Test-Path -LiteralPath $cygpath -PathType Leaf) {
        $converted = @(& $cygpath -u $script:RepositoryRoot 2>&1 |
            ForEach-Object { $_.ToString() })
        if ($LASTEXITCODE -ne 0) {
            Fail "could not convert repository path for Git Bash: $($converted -join ' ')"
        }
        ($converted -join '').Trim()
    } else {
        $script:RepositoryRoot
    }
    Push-Location $script:RepositoryRoot
    try {
        $canonicalOutput = @(& $bash -lc 'cd -- "$1" && scripts/source-bundle.sh "$2"' `
            'drawless-source' $bashRepositoryRoot $canonicalSourceRelative 2>&1 |
                ForEach-Object { $_.ToString() })
        $canonicalExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($canonicalExitCode -ne 0 -or
        -not (Test-Path -LiteralPath $canonicalSourcePath -PathType Leaf)) {
        Fail "canonical source regeneration failed: $($canonicalOutput -join ' ')"
    }
    & $tar.Source -xzf $canonicalSourcePath -C $canonicalInspectionRoot `
        "$sourceRoot/SOURCE-MANIFEST.sha256" `
        "$sourceRoot/SOURCE-MANIFEST.sha256.digest"
    if ($LASTEXITCODE -ne 0) {
        Fail 'canonical source regeneration did not contain its source manifest and digest'
    }
    $canonicalManifestPath = Join-Path $canonicalInspectionRoot (
        "$sourceRoot\SOURCE-MANIFEST.sha256"
    )
    $canonicalDigestPath = Join-Path $canonicalInspectionRoot (
        "$sourceRoot\SOURCE-MANIFEST.sha256.digest"
    )
    $canonicalManifestHash = (Get-FileHash -LiteralPath $canonicalManifestPath `
        -Algorithm SHA256).Hash.ToLowerInvariant()
    $canonicalRecordedDigest = [IO.File]::ReadAllText($canonicalDigestPath).Trim()
    if ($canonicalManifestHash -cne $sourceManifestSha256 -or
        $canonicalRecordedDigest -cne $sourceManifestSha256) {
        Fail 'source archive contents differ from the canonical clean-repository manifest'
    }
} finally {
    if (Test-Path -LiteralPath $canonicalSourcePath) {
        Remove-Item -LiteralPath $canonicalSourcePath -Force
    }
    if (Test-Path -LiteralPath $canonicalInspectionRoot) {
        Remove-Item -LiteralPath $canonicalInspectionRoot -Recurse -Force
    }
}

$sdkRoot = Find-AndroidSdk $script:AndroidRoot
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$java = Find-JavaTool 'java'
$jarsigner = Find-JavaTool 'jarsigner'
$keytool = Find-JavaTool 'keytool'
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)

$nativeLock = Read-Properties (Join-Path $script:RepositoryRoot 'engine\native\upstream.properties')
foreach ($requiredProperty in @('androidNdkVersion', 'nativeLibraryName')) {
    if (-not $nativeLock.ContainsKey($requiredProperty) -or -not $nativeLock[$requiredProperty]) {
        Fail "native lock is missing $requiredProperty"
    }
}
$ndkRootCandidates = @(
    $env:ANDROID_NDK_HOME,
    $env:ANDROID_NDK_ROOT,
    (Join-Path $sdkRoot "ndk\$($nativeLock['androidNdkVersion'])")
) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) }
$ndkRoot = $ndkRootCandidates | Select-Object -First 1
if (-not $ndkRoot) {
    Fail "Android NDK $($nativeLock['androidNdkVersion']) was not found"
}
$readelf = Get-ChildItem -LiteralPath (Join-Path $ndkRoot 'toolchains\llvm\prebuilt') -Recurse -File |
    Where-Object { $_.Name -in @('llvm-readelf', 'llvm-readelf.exe') } |
    Select-Object -ExpandProperty FullName -First 1
if (-not $readelf) {
    Fail "llvm-readelf was not found under $ndkRoot"
}

$manifestText = Invoke-Bundletool @('dump', 'manifest', "--bundle=$bundlePath")
$manifestStart = $manifestText.IndexOf('<manifest')
if ($manifestStart -lt 0) {
    Fail 'bundletool did not return an Android manifest'
}
[xml]$manifestXml = $manifestText.Substring($manifestStart)
$manifest = $manifestXml.DocumentElement
$androidNamespace = 'http://schemas.android.com/apk/res/android'
Assert-Equal 'application ID' $manifest.GetAttribute('package') $expectedApplicationId
Assert-Equal 'version code' $manifest.GetAttribute('versionCode', $androidNamespace) $expectedVersionCode
Assert-Equal 'version name' $manifest.GetAttribute('versionName', $androidNamespace) $expectedVersionName
$usesSdk = $manifest.SelectSingleNode('uses-sdk')
if (-not $usesSdk) {
    Fail 'bundle manifest has no uses-sdk element'
}
Assert-Equal 'minimum SDK' $usesSdk.GetAttribute('minSdkVersion', $androidNamespace) $expectedMinSdk
Assert-Equal 'target SDK' $usesSdk.GetAttribute('targetSdkVersion', $androidNamespace) $expectedTargetSdk

$configText = Invoke-Bundletool @('dump', 'config', "--bundle=$bundlePath")
$configStart = $configText.IndexOf('{')
if ($configStart -lt 0) {
    Fail 'bundletool did not return bundle configuration JSON'
}
$bundleConfig = $configText.Substring($configStart) | ConvertFrom-Json
$nativePackaging = $bundleConfig.optimizations.uncompressNativeLibraries
if ($nativePackaging.enabled -ne $true) {
    Fail 'bundle is not configured to emit uncompressed native libraries'
}
Assert-Equal 'APK native page alignment' $nativePackaging.alignment 'PAGE_ALIGNMENT_16K'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($bundlePath)
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("drawless-aab-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
$nativeEvidence = [Collections.Generic.List[object]]::new()
$packagedAssetEvidence = [Collections.Generic.List[object]]::new()
$requiredPackagedAssets = [ordered]@{
    'base/assets/legal/drawless-chess/LICENSE' =
        (Join-Path $script:RepositoryRoot 'LICENSE')
    'base/assets/legal/drawless-chess/NOTICE' =
        (Join-Path $script:RepositoryRoot 'NOTICE')
    'base/assets/legal/drawless-chess/THIRD_PARTY_NOTICES.md' =
        (Join-Path $script:RepositoryRoot 'THIRD_PARTY_NOTICES.md')
    'base/assets/third_party/android-runtime/APACHE-2.0.txt' =
        (Join-Path $script:RepositoryRoot 'APACHE-2.0.txt')
    'base/assets/third_party/android-runtime/release-sbom.cdx.json' =
        (Join-Path $script:RepositoryRoot 'release\reports\release-sbom.cdx.json')
    'base/assets/third_party/fairy-stockfish/Copying.txt' =
        (Join-Path $script:RepositoryRoot 'engine\native\upstream\Fairy-Stockfish\Copying.txt')
    'base/assets/third_party/fairy-stockfish/AUTHORS' =
        (Join-Path $script:RepositoryRoot 'engine\native\upstream\Fairy-Stockfish\AUTHORS')
    'base/assets/third_party/fairy-stockfish/SOURCE_NOTICE.txt' =
        (Join-Path $script:RepositoryRoot 'engine\native\SOURCE_NOTICE.txt')
    'base/assets/third_party/fairy-stockfish/upstream.properties' =
        (Join-Path $script:RepositoryRoot 'engine\native\upstream.properties')
    'base/assets/third_party/fairy-stockfish/wasm-poc.properties' =
        (Join-Path $script:RepositoryRoot 'engine\native\wasm-poc.properties')
    'base/assets/engine/drawless-variants.ini' =
        (Join-Path $script:RepositoryRoot 'engine\variants.ini')
}
$patchRoot = Join-Path $script:RepositoryRoot 'engine\patches'
Get-ChildItem -LiteralPath $patchRoot -File | Where-Object {
    $_.Name -in @('series', 'checksums.sha256', 'README.md') -or
    $_.Extension -in @('.patch', '.diff', '.json')
} | ForEach-Object {
    $requiredPackagedAssets["base/assets/third_party/fairy-stockfish/patches/$($_.Name)"] =
        $_.FullName
}
try {
    $duplicatePaths = $archive.Entries | Group-Object FullName | Where-Object Count -gt 1
    if ($duplicatePaths) {
        Fail "bundle contains duplicate archive paths: $(($duplicatePaths.Name | Sort-Object) -join ', ')"
    }
    foreach ($bundleEntry in $archive.Entries) {
        $entryPath = $bundleEntry.FullName
        $entryParts = @($entryPath.Split('/', [StringSplitOptions]::RemoveEmptyEntries))
        if (-not $entryPath -or $entryPath.StartsWith('/', [StringComparison]::Ordinal) -or
            $entryPath.Contains('\') -or $entryPath.Contains(':') -or
            $entryParts -contains '.' -or $entryParts -contains '..') {
            Fail "bundle contains an unsafe archive path: $entryPath"
        }
    }

    foreach ($assetPath in $requiredPackagedAssets.Keys) {
        $sourcePath = $requiredPackagedAssets[$assetPath]
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            Fail "repository source for packaged asset is absent: $sourcePath"
        }
        $entry = $archive.GetEntry($assetPath)
        if ($null -eq $entry) {
            Fail "bundle is missing required legal/source identity asset: $assetPath"
        }
        $expectedHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).
            Hash.ToLowerInvariant()
        $actualHash = Get-ZipEntrySha256 $entry
        if ($actualHash -cne $expectedHash) {
            Fail "packaged legal/source identity asset differs from the repository: $assetPath"
        }
        $packagedAssetEvidence.Add([ordered]@{
            asset = $assetPath
            sha256 = $actualHash
        })
    }

    $sourceCommitAssetPath = 'base/assets/release/SOURCE-COMMIT'
    $sourceCommitEntry = $archive.GetEntry($sourceCommitAssetPath)
    if ($null -eq $sourceCommitEntry) {
        Fail "bundle is missing its release source commit asset: $sourceCommitAssetPath"
    }
    $commitStream = $sourceCommitEntry.Open()
    $commitReader = [IO.StreamReader]::new($commitStream, [Text.UTF8Encoding]::new($false), $true)
    try {
        $packagedSourceCommit = $commitReader.ReadToEnd().Trim()
    } finally {
        $commitReader.Dispose()
        $commitStream.Dispose()
    }
    Assert-Equal 'packaged release source commit' $packagedSourceCommit $repositoryCommit
    $packagedAssetEvidence.Add([ordered]@{
        asset = $sourceCommitAssetPath
        sha256 = Get-ZipEntrySha256 $sourceCommitEntry
    })

    $nativeEntries = $archive.Entries | Where-Object {
        $_.FullName -cmatch '^base/lib/([^/]+)/([^/]+\.so)$'
    }
    if (-not $nativeEntries) {
        Fail 'bundle contains no base-module native libraries'
    }
    $actualAbis = @($nativeEntries | ForEach-Object {
        [regex]::Match($_.FullName, '^base/lib/([^/]+)/').Groups[1].Value
    } | Sort-Object -Unique -CaseSensitive)
    if (Compare-Object $expectedAbis $actualAbis -CaseSensitive) {
        Fail "ABI set mismatch; expected $($expectedAbis -join ', '), found $($actualAbis -join ', ')"
    }

    $engineLibrary = "lib$($nativeLock['nativeLibraryName']).so"
    foreach ($abi in $expectedAbis) {
        $abiEntries = @($nativeEntries | Where-Object { $_.FullName -clike "base/lib/$abi/*" })
        if ($abiEntries.Name -cnotcontains $engineLibrary) {
            Fail "$abi is missing required engine library $engineLibrary"
        }
        foreach ($entry in $abiEntries) {
            $destination = Join-Path $temporaryRoot (
                'native-' + [guid]::NewGuid().ToString('N') + '.so'
            )
            $inputStream = $entry.Open()
            $outputStream = [IO.File]::Create($destination)
            try {
                $inputStream.CopyTo($outputStream)
            } finally {
                $outputStream.Dispose()
                $inputStream.Dispose()
            }

            $elfHeader = & $readelf -h $destination 2>&1
            if ($LASTEXITCODE -ne 0) {
                Fail "llvm-readelf rejected $($entry.FullName): $($elfHeader -join ' ')"
            }
            $expectedMachine = if ($abi -eq 'arm64-v8a') { 'AArch64' } else { 'Advanced Micro Devices X86-64' }
            if (($elfHeader -join "`n") -notmatch [regex]::Escape($expectedMachine)) {
                Fail "$($entry.FullName) does not target $expectedMachine"
            }

            $programHeaders = & $readelf -lW $destination 2>&1
            if ($LASTEXITCODE -ne 0) {
                Fail "could not read program headers for $($entry.FullName)"
            }
            $loadHeaders = @($programHeaders | Where-Object { $_ -match '^\s*LOAD\s' })
            if (-not $loadHeaders) {
                Fail "$($entry.FullName) has no ELF LOAD segments"
            }
            foreach ($loadHeader in $loadHeaders) {
                $alignmentText = ($loadHeader.Trim() -split '\s+')[-1]
                try {
                    $alignment = [Convert]::ToInt64($alignmentText.Substring(2), 16)
                } catch {
                    Fail "could not parse ELF alignment '$alignmentText' in $($entry.FullName)"
                }
                if ($alignment -lt 0x4000) {
                    Fail "$($entry.FullName) has LOAD alignment $alignmentText; 0x4000 is required"
                }
            }
            $nativeEvidence.Add([ordered]@{
                abi = $abi
                library = $entry.Name
                sizeBytes = $entry.Length
                loadAlignment = '0x4000-or-greater'
            })
        }
    }
} finally {
    $archive.Dispose()
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}

$signatureOutput = & $jarsigner -verify -verbose -certs $bundlePath 2>&1
$signatureText = ($signatureOutput | ForEach-Object { $_.ToString() }) -join "`n"
if ($LASTEXITCODE -ne 0 -or $signatureText -notmatch '(?m)^jar verified\.$') {
    Fail 'bundle does not have a valid JAR signature from an upload key'
}
if ($signatureText -match '(?i)jar is unsigned|unsigned entries|not signed') {
    Fail 'bundle contains unsigned content'
}
if ($signatureText -match '(?i)CN\s*=\s*Android Debug') {
    Fail 'bundle is signed with an Android debug certificate, not the Google Play upload key'
}

$certificateOutput = & $keytool -printcert -rfc -jarfile $bundlePath 2>&1
if ($LASTEXITCODE -ne 0) {
    Fail "could not read the upload certificate: $(($certificateOutput | ForEach-Object { $_.ToString() }) -join ' ')"
}
$certificateText = ($certificateOutput | ForEach-Object { $_.ToString() }) -join "`n"
$certificateMatch = [regex]::Match(
    $certificateText,
    '(?s)-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----'
)
if (-not $certificateMatch.Success) {
    Fail 'keytool did not return an upload certificate'
}
$certificateBytes = [Convert]::FromBase64String(($certificateMatch.Groups[1].Value -replace '\s', ''))
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $certificateDigest = ($sha256.ComputeHash($certificateBytes) | ForEach-Object {
        $_.ToString('x2')
    }) -join ''
} finally {
    $sha256.Dispose()
}
Assert-Equal 'upload certificate SHA-256' $certificateDigest $expectedCertificateDigest

$report = [ordered]@{
    verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
    bundle = [IO.Path]::GetFileName($bundlePath)
    sizeBytes = (Get-Item -LiteralPath $bundlePath).Length
    sha256 = (Get-FileHash -LiteralPath $bundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
    repositoryCommit = $repositoryCommit
    sourceArchive = [ordered]@{
        file = [IO.Path]::GetFileName($sourceArchivePath)
        sha256 = $sourceArchiveSha256
        commit = $archiveCommit
        manifestSha256 = $sourceManifestSha256
        verifiedFileCount = $sourceVerifiedFileCount
        allManifestHashesVerified = $true
        manifestDigestVerified = $true
        canonicalCommittedBlobsMatched = $true
        canonicalManifestMatched = $true
    }
    releaseReports = @($releaseReportEvidence)
    applicationId = $expectedApplicationId
    versionCode = [int]$expectedVersionCode
    versionName = $expectedVersionName
    minSdk = [int]$expectedMinSdk
    targetSdk = [int]$expectedTargetSdk
    abis = $expectedAbis
    apkNativePageAlignment = 'PAGE_ALIGNMENT_16K'
    packagedLegalAndSourceAssets = @($packagedAssetEvidence)
    nativeLibraries = @($nativeEvidence)
    uploadCertificateSha256 = $certificateDigest
    signatureVerified = $true
}

if ($OutputManifest) {
    $outputPath = [IO.Path]::GetFullPath($OutputManifest)
    $outputDirectory = Split-Path -Parent $outputPath
    if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }
    [IO.File]::WriteAllText(
        $outputPath,
        (($report | ConvertTo-Json -Depth 8) + "`n"),
        [Text.UTF8Encoding]::new($false)
    )
}

Write-Host "Google Play AAB verification PASS"
Write-Host "  $expectedApplicationId $expectedVersionName ($expectedVersionCode), target SDK $expectedTargetSdk"
Write-Host "  ABIs: $($expectedAbis -join ', '); native page alignment: 16 KB"
Write-Host "  Upload certificate SHA-256: $certificateDigest"
Write-Host "  Bundle SHA-256: $($report.sha256)"
