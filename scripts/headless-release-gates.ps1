[CmdletBinding(PositionalBinding = $false)]
param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string] $RunId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'),

    [string] $RunDirectory,

    [ValidateRange(24.0, 168.0)]
    [double] $SoakHours = 24.0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7 -or -not [Environment]::Is64BitProcess) {
    throw 'headless-release-gates.ps1 requires 64-bit PowerShell 7 (pwsh).'
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runsRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build/headless/runs')).TrimEnd('\', '/')
$pwshPath = Join-Path $PSHOME 'pwsh.exe'
$wrapperPath = Join-Path $PSScriptRoot 'headless-selfplay.ps1'
$soakPath = Join-Path $PSScriptRoot 'headless-selfplay-soak.ps1'
$validatorPath = Join-Path $PSScriptRoot 'headless-release-campaign-validate.ps1'
$campaignConfig = Join-Path $repositoryRoot 'tools/selfplay/config/release-campaign.properties'
$sameSoakConfig = Join-Path $repositoryRoot 'tools/selfplay/config/release-soak-same-level.properties'
$adjacentSoakConfig = Join-Path $repositoryRoot 'tools/selfplay/config/release-soak-adjacent.properties'

function Assert-UnderRoot {
    param([Parameter(Mandatory)] [string] $Path, [Parameter(Mandatory)] [string] $Root)
    $full = [IO.Path]::GetFullPath($Path)
    $prefix = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path must remain under '$Root': '$full'."
    }
    return $full
}

foreach ($required in @($pwshPath, $wrapperPath, $soakPath, $validatorPath, $campaignConfig, $sameSoakConfig, $adjacentSoakConfig)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required release-gate input is missing: '$required'."
    }
}

[IO.Directory]::CreateDirectory($runsRoot) | Out-Null
$runCandidate = if ([string]::IsNullOrWhiteSpace($RunDirectory)) {
    Join-Path $runsRoot "release-gates-$RunId"
} elseif ([IO.Path]::IsPathRooted($RunDirectory)) {
    $RunDirectory
} else {
    Join-Path $repositoryRoot $RunDirectory
}
$runRoot = Assert-UnderRoot -Path $runCandidate -Root $runsRoot
if (Test-Path -LiteralPath $runRoot) {
    throw "Release-gate run directory already exists: '$runRoot'."
}
[IO.Directory]::CreateDirectory($runRoot) | Out-Null
$evidenceRoot = Join-Path $runRoot 'frozen-inputs'
[IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null

$statePath = Join-Path $runRoot 'state.json'
$lockPath = Join-Path $runRoot 'coordinator.lock'
$campaignReport = Join-Path $runRoot 'campaign-10032.jsonl'
$soakRunId = "$RunId-final-24h"
$soakRoot = Join-Path $runsRoot "soak-$soakRunId"
$utf8 = [Text.UTF8Encoding]::new($false)
$lockStream = $null
$sleepPreventionEnabled = $false
$state = [ordered]@{
    schemaVersion = 1
    runId = $RunId
    status = 'starting'
    phase = 'initializing'
    coordinatorPid = $PID
    startedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    lastHeartbeatUtc = (Get-Date).ToUniversalTime().ToString('o')
    finishedAtUtc = $null
    campaignGamesRequired = 10032
    cleanTailRequired = 5000
    soakHoursRequired = $SoakHours
    campaignReport = $campaignReport
    campaignReportBytes = 0
    campaignValidation = $null
    soakRunId = $soakRunId
    soakState = Join-Path $soakRoot 'state.json'
    activeChildPid = $null
    activePurpose = $null
    baselineIdentity = $null
    baselineInputIdentity = $null
    gitHead = $null
    gitStatus = @()
    error = $null
}

function Write-State {
    $state.lastHeartbeatUtc = (Get-Date).ToUniversalTime().ToString('o')
    if (Test-Path -LiteralPath $campaignReport -PathType Leaf) {
        $state.campaignReportBytes = (Get-Item -LiteralPath $campaignReport).Length
    }
    $temporary = "$statePath.tmp.$PID"
    [IO.File]::WriteAllText($temporary, ($state | ConvertTo-Json -Depth 12) + [Environment]::NewLine, $utf8)
    [IO.File]::Move($temporary, $statePath, $true)
}

function Get-ArtifactIdentity {
    $engine = Join-Path $repositoryRoot 'build/headless/linux-x86_64/drawless-fairy'
    $runner = Join-Path $repositoryRoot 'build/headless/drawless-selfplay.jar'
    $variants = Join-Path $repositoryRoot 'engine/variants.ini'
    foreach ($path in @($engine, $runner, $variants)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Frozen artifact is missing: '$path'."
        }
    }
    return [ordered]@{
        engineSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $engine).Hash.ToLowerInvariant()
        runtimeSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $runner).Hash.ToLowerInvariant()
        variantsSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $variants).Hash.ToLowerInvariant()
    }
}

function Assert-Identity {
    param([Parameter(Mandatory)] $Expected, [Parameter(Mandatory)] $Actual, [Parameter(Mandatory)] [string] $Description)
    foreach ($key in @('engineSha256', 'runtimeSha256', 'variantsSha256')) {
        if ([string]$Expected[$key] -cne [string]$Actual[$key]) {
            throw "$Description changed $key from '$($Expected[$key])' to '$($Actual[$key])'."
        }
    }
}

function Get-ReleaseInputIdentity {
    $inputs = [ordered]@{
        campaignConfigSha256 = $campaignConfig
        sameSoakConfigSha256 = $sameSoakConfig
        adjacentSoakConfigSha256 = $adjacentSoakConfig
        releasePositionsSha256 = Join-Path $repositoryRoot 'tools/selfplay/fixtures/release-positions.tsv'
        openingsSha256 = Join-Path $repositoryRoot 'tools/selfplay/fixtures/openings.tsv'
        ladderLevelsSha256 = Join-Path $repositoryRoot 'tools/selfplay/fixtures/ladder-levels.tsv'
        adjacentMatchupsSha256 = Join-Path $repositoryRoot 'tools/selfplay/fixtures/adjacent-matchups.tsv'
    }
    $identity = [ordered]@{}
    foreach ($entry in $inputs.GetEnumerator()) {
        if (-not (Test-Path -LiteralPath $entry.Value -PathType Leaf)) {
            throw "Frozen release input is missing: '$($entry.Value)'."
        }
        $identity[$entry.Key] = (Get-FileHash -Algorithm SHA256 -LiteralPath $entry.Value).Hash.ToLowerInvariant()
    }
    return $identity
}

function Assert-ReleaseInputIdentity {
    param([Parameter(Mandatory)] $Expected, [Parameter(Mandatory)] $Actual, [Parameter(Mandatory)] [string] $Description)
    foreach ($key in @(
        'campaignConfigSha256',
        'sameSoakConfigSha256',
        'adjacentSoakConfigSha256',
        'releasePositionsSha256',
        'openingsSha256',
        'ladderLevelsSha256',
        'adjacentMatchupsSha256'
    )) {
        if ([string]$Expected[$key] -cne [string]$Actual[$key]) {
            throw "$Description changed $key from '$($Expected[$key])' to '$($Actual[$key])'."
        }
    }
}

function Assert-CampaignFixtureIdentity {
    param([Parameter(Mandatory)] $Expected, [Parameter(Mandatory)] $Validation)
    foreach ($key in @('releasePositionsSha256', 'ladderLevelsSha256', 'adjacentMatchupsSha256')) {
        if ([string]$Expected[$key] -cne [string]$Validation[$key]) {
            throw "Campaign evidence used $key='$($Validation[$key])'; frozen input is '$($Expected[$key])'."
        }
    }
}

function Invoke-Child {
    param(
        [Parameter(Mandatory)] [string] $Purpose,
        [Parameter(Mandatory)] [string] $Script,
        [Parameter(Mandatory)] [string[]] $Arguments,
        [Parameter(Mandatory)] [int] $TimeoutMinutes
    )
    $label = ($Purpose -replace '[^A-Za-z0-9._-]+', '-').Trim('-').ToLowerInvariant()
    $stdoutPath = Join-Path $runRoot "$label.stdout.log"
    $stderrPath = Join-Path $runRoot "$label.stderr.log"
    $start = Get-Date
    $process = $null
    try {
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $pwshPath
        $startInfo.WorkingDirectory = $repositoryRoot
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        foreach ($argument in @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $Script) + $Arguments) {
            $startInfo.ArgumentList.Add($argument)
        }
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        if (-not $process.Start()) {
            throw "Could not start $Purpose."
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $state.activeChildPid = $process.Id
        $state.activePurpose = $Purpose
        Write-State
        while (-not $process.WaitForExit(15000)) {
            if (((Get-Date) - $start).TotalMinutes -ge $TimeoutMinutes) {
                $process.Kill($true)
                throw "$Purpose exceeded its $TimeoutMinutes-minute watchdog."
            }
            Write-State
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        [IO.File]::WriteAllText($stdoutPath, $stdout, $utf8)
        [IO.File]::WriteAllText($stderrPath, $stderr, $utf8)
        if ($process.ExitCode -ne 0) {
            throw "$Purpose failed with exit code $($process.ExitCode); see '$stdoutPath' and '$stderrPath'."
        }
        return $stdout
    } finally {
        $state.activeChildPid = $null
        $state.activePurpose = $null
        Write-State
        if ($null -ne $process) { $process.Dispose() }
    }
}

try {
    $lockStream = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DrawlessReleaseGatePower {
    [DllImport("kernel32.dll")]
    public static extern uint SetThreadExecutionState(uint flags);
}
'@
    $sleepPreventionEnabled = [DrawlessReleaseGatePower]::SetThreadExecutionState([uint32]2147483649) -ne 0
    if (-not $sleepPreventionEnabled) {
        throw 'Windows refused the system-awake request for the release-gate coordinator.'
    }

    $state.gitHead = (& git -C $repositoryRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not record the release-gate Git HEAD.' }
    $state.gitStatus = @(& git -C $repositoryRoot status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw 'Could not record the release-gate Git status.' }
    foreach ($relative in @(
        'build/headless/drawless-selfplay.jar.sha256',
        'build/headless/drawless-selfplay.jar.metadata',
        'build/headless/drawless-selfplay.sources.sha256',
        'build/headless/linux-x86_64/drawless-fairy.sha256',
        'build/headless/linux-x86_64/drawless-fairy.metadata',
        'scripts/headless-release-gates.ps1',
        'scripts/headless-release-campaign-validate.ps1',
        'scripts/headless-selfplay.ps1',
        'scripts/headless-selfplay.sh',
        'scripts/headless-selfplay-validate.sh',
        'scripts/headless-selfplay-soak.ps1',
        'tools/selfplay/config/release-campaign.properties',
        'tools/selfplay/config/release-soak-same-level.properties',
        'tools/selfplay/config/release-soak-adjacent.properties',
        'tools/selfplay/fixtures/release-positions.tsv',
        'tools/selfplay/fixtures/openings.tsv',
        'tools/selfplay/fixtures/ladder-levels.tsv',
        'tools/selfplay/fixtures/adjacent-matchups.tsv',
        'engine/variants.ini'
    )) {
        $source = Join-Path $repositoryRoot $relative
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Frozen evidence input is missing: '$source'."
        }
        $destination = Join-Path $evidenceRoot ($relative.Replace('/', '__'))
        Copy-Item -LiteralPath $source -Destination $destination -ErrorAction Stop
    }
    $state.baselineIdentity = Get-ArtifactIdentity
    $state.baselineInputIdentity = Get-ReleaseInputIdentity
    Write-State

    $state.status = 'preflight'
    $state.phase = 'validating frozen artifacts and release portfolio'
    Write-State
    $null = Invoke-Child -Purpose 'preflight' -Script $wrapperPath -Arguments @(
        '--no-build',
        '--validate-only',
        '--config', 'tools/selfplay/config/release-campaign.properties'
    ) -TimeoutMinutes 30
    Assert-Identity -Expected $state.baselineIdentity -Actual (Get-ArtifactIdentity) -Description 'Artifacts during preflight'
    Assert-ReleaseInputIdentity -Expected $state.baselineInputIdentity -Actual (Get-ReleaseInputIdentity) -Description 'Release inputs during preflight'

    $state.status = 'running'
    $state.phase = '10,032-game diversified campaign; final 5,000 must remain clean'
    Write-State
    $null = Invoke-Child -Purpose 'campaign-10032' -Script $wrapperPath -Arguments @(
        '--no-build',
        '--skip-runner-tests',
        '--config', 'tools/selfplay/config/release-campaign.properties',
        '--output', ([IO.Path]::GetRelativePath($repositoryRoot, $campaignReport).Replace('\', '/'))
    ) -TimeoutMinutes 2880
    Assert-Identity -Expected $state.baselineIdentity -Actual (Get-ArtifactIdentity) -Description 'Artifacts during campaign'
    Assert-ReleaseInputIdentity -Expected $state.baselineInputIdentity -Actual (Get-ReleaseInputIdentity) -Description 'Release inputs during campaign'

    $state.phase = 'campaign zero-work resume'
    Write-State
    $null = Invoke-Child -Purpose 'campaign-zero-work-resume' -Script $wrapperPath -Arguments @(
        '--no-build',
        '--skip-runner-tests',
        '--config', 'tools/selfplay/config/release-campaign.properties',
        '--output', ([IO.Path]::GetRelativePath($repositoryRoot, $campaignReport).Replace('\', '/'))
    ) -TimeoutMinutes 30
    Assert-ReleaseInputIdentity -Expected $state.baselineInputIdentity -Actual (Get-ReleaseInputIdentity) -Description 'Release inputs during campaign resume'

    $state.phase = 'streaming campaign audit'
    Write-State
    $validationJson = Invoke-Child -Purpose 'campaign-validation' -Script $validatorPath -Arguments @(
        '-ReportPath', $campaignReport,
        '-ExpectedGames', '10032',
        '-RequiredCleanTail', '5000',
        '-RequireZeroWorkResume'
    ) -TimeoutMinutes 180
    $state.campaignValidation = $validationJson | ConvertFrom-Json -AsHashtable -Depth 10
    Assert-Identity -Expected $state.baselineIdentity -Actual $state.campaignValidation -Description 'Campaign evidence'
    Assert-CampaignFixtureIdentity -Expected $state.baselineInputIdentity -Validation $state.campaignValidation
    Assert-Identity -Expected $state.baselineIdentity -Actual (Get-ArtifactIdentity) -Description 'Artifacts before soak'
    Assert-ReleaseInputIdentity -Expected $state.baselineInputIdentity -Actual (Get-ReleaseInputIdentity) -Description 'Release inputs before soak'

    $state.status = 'soaking'
    $state.phase = "$SoakHours-hour release-default soak"
    Write-State
    $null = Invoke-Child -Purpose 'final-24h-soak' -Script $soakPath -Arguments @(
        '-MinimumHours', $SoakHours.ToString([Globalization.CultureInfo]::InvariantCulture),
        '-RunId', $soakRunId,
        '-RunDirectory', $soakRoot,
        '-SameLevelConfig', 'tools/selfplay/config/release-soak-same-level.properties',
        '-AdjacentConfig', 'tools/selfplay/config/release-soak-adjacent.properties',
        '-ReleaseDefaultRules'
    ) -TimeoutMinutes ([math]::Ceiling(($SoakHours + 12.0) * 60.0))

    $soakStatePath = Join-Path $soakRoot 'state.json'
    if (-not (Test-Path -LiteralPath $soakStatePath -PathType Leaf)) {
        throw "Final soak state is missing: '$soakStatePath'."
    }
    $soakState = Get-Content -LiteralPath $soakStatePath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable -Depth 30
    $soakFailures = (@($soakState.reports) | Measure-Object -Property failures -Sum).Sum
    if (
        [string]$soakState.status -cne 'completed' -or
        [double]$soakState.selfPlaySeconds -lt ($SoakHours * 3600.0) -or
        [int]$soakFailures -ne 0
    ) {
        throw "Final soak did not satisfy duration/zero-failure gates: status=$($soakState.status), seconds=$($soakState.selfPlaySeconds), failures=$soakFailures."
    }
    Assert-Identity -Expected $state.baselineIdentity -Actual $soakState.baselineIdentity -Description 'Final soak evidence'
    Assert-Identity -Expected $state.baselineIdentity -Actual (Get-ArtifactIdentity) -Description 'Artifacts after soak'
    Assert-ReleaseInputIdentity -Expected $state.baselineInputIdentity -Actual (Get-ReleaseInputIdentity) -Description 'Release inputs after soak'

    $state.status = 'completed'
    $state.phase = '10K campaign, 5K clean tail, and final soak passed'
    $state.finishedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    Write-State
} catch {
    $state.status = 'failed'
    $state.phase = 'failed'
    $state.error = $_.Exception.ToString()
    $state.finishedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    Write-State
    throw
} finally {
    if ($sleepPreventionEnabled) {
        $null = [DrawlessReleaseGatePower]::SetThreadExecutionState([uint32]2147483648)
    }
    if ($null -ne $lockStream) { $lockStream.Dispose() }
}
